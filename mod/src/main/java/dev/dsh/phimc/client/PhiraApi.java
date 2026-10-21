package dev.dsh.phimc.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phira 在线谱面库的网络层（<b>纯 Java</b>，不碰 Minecraft 的类，也不碰渲染线程）。
 *
 * <h2>域名与失败切换</h2>
 * 两个镜像，按 {@link #BASES} 顺序试：{@code https://phira.5wyxi.com} → {@code https://api.phira.cn}，
 * <b>一个请求最多切换一次</b>（需求 3）。第一次失败（连接失败 / 超时 / 5xx）就换备用域名重试一次，
 * 还失败才把中文原因返回给界面。
 *
 * <p>顺序调过：官方客户端源码（{@code phira/src/client.rs:26}）里写的是
 * {@code API_URL = "https://phira.5wyxi.com"}，而 {@code api.phira.cn} 是套了 CDN 的老入口，
 * 实测从本机打它单次要 1.7~27 秒（还会连上但不回）。让它当「备胎」，正常路径一次都不打它。
 *
 * <h2>超时（三层，缺一不可）</h2>
 * <ol>
 *   <li>{@code connectTimeout = 5s}（{@link #CONNECT_TIMEOUT}）——治「TCP 连不上 / DNS 慢」；</li>
 *   <li>{@code HttpRequest.timeout = min(15s, 剩余预算)}（{@link #REQUEST_TIMEOUT}）——治「连上了不回」；</li>
 *   <li>{@link #sendWithDeadline 外层硬截止}（{@link #SEARCH_BUDGET_MS}）——因为 JDK 的
 *       {@code HttpRequest.timeout} <b>不是</b>硬上限（实测配 15s 的请求跑过 27.2s：响应头到了之后
 *       读响应体不再受这个定时器管），所以再用 {@code CompletableFuture.get(remaining)} 兜一层。</li>
 * </ol>
 *
 * <h2>线程模型</h2>
 * <b>本类的每个 public 方法都只能在后台线程调用</b>（{@link #runAsync} 或
 * {@link PhiraDownloader} / {@link PhiraCovers} 自己的线程池）。这里从不 {@code join}、
 * 也不回调界面；界面每帧只读 {@link PhiraBrowser} 的 volatile 快照。
 *
 * <p>{@link #API_POOL} 是 <b>2 条</b>线程的池子（原来只有 1 条：一条被慢请求占住，
 * 后面所有搜索就都排队排到天荒地老 —— 用户报的「第一次勉强、之后一直 loading」就是这个）。
 * 队列只有 {@link #API_QUEUE} 个位置，满了<b>丢最老的那个请求</b>：它的结果本来就会
 * 被 {@link PhiraBrowser} 的序号丢掉，留着只会白占线程。
 *
 * <h2>缓存</h2>
 * 搜索结果按「搜索词 + 排序 + 页 + 每页条数 + 仅上架」缓存 <b>60 秒</b>（{@link #CACHE_TTL_MS}），
 * 同一搜索词在 60 秒内翻来翻去不会重复打接口。失败的请求<b>不缓存</b>（下次点「搜索」会重试）。
 */
public final class PhiraApi {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /**
     * 主域名 + 备用域名（需求 3：失败自动切备用一次）。
     *
     * <p><b>顺序有依据</b>：官方客户端 {@code phira/src/client.rs:26} 的
     * {@code API_URL = "https://phira.5wyxi.com"} —— 这是官方在用的地址；
     * {@code api.phira.cn} 是套了 CDN 的旧入口（{@code phira-monitor/src/scene.rs:42} 还在用它），
     * 2026-10 实测从本机访问单次要 1.7~27 秒（偶尔直接连接超时），所以放到第二位当备胎。
     */
    public static final String[] BASES = {"https://phira.5wyxi.com", "https://api.phira.cn"};

    /** 连接超时 5 秒。 */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 单个请求超时 15 秒（含搜索、曲绘、谱面包）。 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 一次搜索的<b>总预算</b>（含主域名失败后换备用域名的那一次）：4.5 秒。
     *
     * <p>为什么是 4.5s：界面要求「连点 10 次搜索每次 5 秒内回来」，所以一次请求（含重试）
     * 必须压在 5 秒以内，留 0.5 秒给解析和调度。
     */
    public static final long SEARCH_BUDGET_MS = 4_500L;

    /** 曲绘（图片）单次下载预算：8 秒（缩略图只有几十 ~ 几百 KB，慢到这个程度就画占位块）。 */
    public static final long IMAGE_BUDGET_MS = 8_000L;

    /**
     * 一个域名失败后被「靠后站」的时长（{@link #BASE_COOLDOWN_MS}，60 秒）。
     *
     * <p>失败过的域名在冷却期内排到后面：留着备用的位置（真全挂的时候还得试），
     * 但正常请求不再先去踩它 —— 否则每次搜索都要白等一个超时。
     */
    private static final long BASE_COOLDOWN_MS = 60_000L;

    /** 搜索结果缓存时长：同一搜索词 60 秒内不重复请求。 */
    private static final long CACHE_TTL_MS = 60_000L;
    /** 缓存条目上限（一个 30 条的搜索结果 ≈ 几十 KB，64 条足够翻页来回）。 */
    private static final int CACHE_MAX = 64;

    /** 谱面包每条最多下 128 MB（防接口抽风写满磁盘；正常 .pez 是 3~12 MB）。 */
    private static final long MAX_DOWNLOAD_BYTES = 128L * 1024L * 1024L;

    private static final Gson GSON = new Gson();

    /** HttpClient 单例（线程安全，可以给多个后台线程共用）。executor 用 daemon，退出游戏不会被挂住。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newCachedThreadPool(daemonFactory("phimc-http")))
            .build();

    /** 搜索线程池的线程数：至少 2（一条被慢请求占住时，新的搜索还能立刻跑）。 */
    private static final int API_THREADS = 2;
    /** 搜索线程池的队列长度（多了就丢最老的）。 */
    private static final int API_QUEUE = 8;

    /**
     * 搜索用的线程池：{@value #API_THREADS} 条 daemon，队列 {@value #API_QUEUE} 个。
     *
     * <p><b>为什么不是单线程</b>：原来 1 条线程 + 单线程队列，任何一次慢请求
     * （实测旧主域名单次能跑到 27~65 秒）都会把后面所有搜索堵在队列里，界面就一直 loading。
     *
     * <p><b>队列满了怎么办</b>：丢掉队首（最老的那次请求）。旧请求的结果铁定会被
     * {@link PhiraBrowser} 的序号丢掉，留着只会白占一个线程位置；新请求必须能进来。
     */
    private static final ThreadPoolExecutor API_POOL = new ThreadPoolExecutor(
            API_THREADS, API_THREADS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(API_QUEUE),
            daemonFactory("phimc-phira-api"),
            (task, pool) -> {
                if (pool.getQueue().poll() == null) throw new RejectedExecutionException("Phira 线程池已关闭");
                pool.execute(task);
            });

    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_MAX;
        }
    };

    /** 域名 → 冷却结束的毫秒时间戳（失败的域名在冷却期内排到最后，见 {@link #BASE_COOLDOWN_MS}）。 */
    private static final Map<String, Long> BASE_DOWN_UNTIL = new ConcurrentHashMap<>();

    private PhiraApi() {
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------
    // 后台线程调度
    // ------------------------------------------------------------------

    /**
     * 把一次网络调用丢到后台线程（搜索 / 封面共用）。<b>永远不要在渲染线程直接调
     * {@link #search} 或 {@link #download}</b>。
     *
     * <p>任务里的任何 {@link Throwable} 都在这里被吃掉（否则会顺着
     * {@link PhiraBrowser} 的回调把「loading = false」那一步跳过，界面就永远转圈）。
     *
     * @return {@code false} = 线程池已经关闭、这次任务没能入队（调用方必须自己把状态收尾）
     */
    public static boolean runAsync(Runnable task) {
        try {
            API_POOL.execute(() -> {
                // 上一次任务如果踩过 InterruptedException，中断标记会留在池线程上：
                // HttpClient 一进去就立刻抛 InterruptedException，等于整条线程被废掉。
                Thread.interrupted();
                try {
                    task.run();
                } catch (Throwable t) {
                    // 兜底：任务自己应该把异常翻成中文消息，跑飞了也不能把线程池搞挂
                    LOG.warn("[PhiMC] Phira 后台任务异常", t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            LOG.warn("[PhiMC] Phira 后台任务没能入队：{}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 搜索结果
    // ------------------------------------------------------------------

    /**
     * 一次搜索的结果。
     *
     * @param ok     是否成功（{@code false} 时 {@link #error()} 是给玩家看的中文原因）
     * @param charts 这一页的谱面（是否只保留 {@code stable && reviewed} 由调用方的
     *               {@code onlyStable} 决定，见 {@link PhiraBrowser#onlyStable()}）
     * @param count  服务端报的总条数（跟着请求里的 stable / reviewed 参数走）
     */
    public record ListResult(boolean ok, List<PhiraChart> charts, int count, String error) {
        static ListResult fail(String message) {
            return new ListResult(false, List.of(), 0, message);
        }
    }

    private record CacheEntry(long at, ListResult result) {
    }

    /**
     * 搜一页谱面（<b>阻塞</b>，只能在后台线程调）。命中 60 秒缓存就直接返回。
     *
     * <p>等价于 {@code search(query, order, page, pageSize, true)}（默认「仅上架」）。
     */
    public static ListResult search(String query, String order, int page, int pageSize) {
        return search(query, order, page, pageSize, true);
    }

    /**
     * 搜一页谱面（<b>阻塞</b>，只能在后台线程调）。命中 60 秒缓存就直接返回。
     *
     * @param query      搜索词（曲名 / 谱师，空串 = 按排序取全部）
     * @param order      {@code -rating}（评分）或 {@code -updated}（最新）
     * @param page       1 起的页码
     * @param pageSize   每页条数（界面按能显示的行数给，1~30）
     * @param onlyStable {@code true} = 只要「已上架 + 已过审」（请求里带 {@code stable=true&reviewed=true}）；
     *                   {@code false} = 全部谱面（未上架 / 未过审也返回，界面用灰色标出来）。
     *                   <b>过滤不在这里做死</b>：本方法只负责把参数带进请求、把响应解析成 {@link PhiraChart}，
     *                   真正的「显示哪些」由 {@link PhiraBrowser#onlyStable()} 在界面层决定。
     */
    public static ListResult search(String query, String order, int page, int pageSize, boolean onlyStable) {
        String q = query == null ? "" : query.trim();
        String ord = order == null || order.isBlank() ? "-rating" : order;
        int p = Math.max(1, page);
        int n = Math.max(1, Math.min(30, pageSize));
        // 缓存键要带 onlyStable：两种模式是两次不同的请求，不能互相串
        String key = q + '\u0001' + ord + '\u0001' + p + '\u0001' + n + '\u0001' + (onlyStable ? "1" : "0");
        synchronized (CACHE) {
            CacheEntry hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_TTL_MS) {
                return hit.result();
            }
        }
        ListResult result;
        try {
            result = requestSearch(q, ord, p, n, onlyStable);
        } catch (Throwable t) {
            // 兜底：网络 / 解析层任何没被翻成 ListResult 的意外，都必须变成一个「失败结果」。
            // 不抛给 PhiraBrowser，是因为那边只要收不到结果，界面就会永远停在「搜索中…」。
            LOG.warn("[PhiMC] Phira 搜索异常", t);
            result = ListResult.fail("搜索失败：" + describe(t));
        }
        if (result.ok()) {
            synchronized (CACHE) {
                CACHE.put(key, new CacheEntry(System.currentTimeMillis(), result));
            }
        }
        return result;
    }

    private static ListResult requestSearch(String query, String order, int page, int pageSize,
                                            boolean onlyStable) {
        String path = "/chart?search=" + enc(query)
                + "&page=" + page
                + "&pageNum=" + pageSize
                + "&order=" + enc(order)
                // 「仅上架」时服务端先筛一道（省流量、页大小不会被过滤打碎）；
                // 关掉时**不带**这两个参数，未上架 / 未过审的也会回来（界面灰色标注）
                + "&division=regular" + (onlyStable ? "&stable=true&reviewed=true" : "");
        long deadline = System.nanoTime() + SEARCH_BUDGET_MS * 1_000_000L;
        String body = null;
        String error = "";
        // 先健康的、后冷却中的（失败过的域名排到后面），全挂时还是按原顺序各试一次
        String[] bases = orderedBases();
        for (int i = 0; i < bases.length; i++) {
            String base = bases[i];
            long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainMs < 200L) {
                // 预算被前一个域名吃光了：不再起新请求（否则界面等的时间就没上限了）
                if (error.isEmpty()) error = "请求超时（预算 " + seconds(SEARCH_BUDGET_MS) + "）：" + hostOf(base);
                break;
            }
            try {
                body = getString(base + path, deadline);
                markBaseUp(base);
                break;
            } catch (PhiraFail e) {
                error = e.getMessage();
                markBaseDown(base);
                if (i + 1 < bases.length) {
                    LOG.warn("[PhiMC] Phira 搜索失败（{}），切备用域名：{}", base, e.getMessage());
                }
            }
        }
        if (body == null) return ListResult.fail(error.isEmpty() ? "网络请求失败" : error);
        return parseList(body);
    }

    private static ListResult parseList(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return ListResult.fail("接口返回的不是 JSON 对象");
            JsonObject obj = root.getAsJsonObject();
            int count = obj.has("count") && !obj.get("count").isJsonNull()
                    ? obj.get("count").getAsInt() : 0;
            JsonElement arrEl = obj.get("results");
            if (arrEl == null || arrEl.isJsonNull()) arrEl = obj.get("result");   // 两个镜像的字段名都见过
            if (arrEl == null || !arrEl.isJsonArray()) return ListResult.fail("接口返回里没有谱面列表");
            JsonArray arr = arrEl.getAsJsonArray();
            List<PhiraChart> out = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) continue;
                PhiraChart chart;
                try {
                    chart = GSON.fromJson(el, PhiraChart.class);
                } catch (RuntimeException bad) {
                    continue;                       // 单条坏数据不影响整页
                }
                // 这里**不过滤** stable / reviewed：要显示哪些由界面层（PhiraBrowser.onlyStable）决定，
                // 关掉「仅上架」时未上架 / 未过审的谱面也要能显示（灰色标注）。
                if (chart != null && chart.id > 0) out.add(chart);
            }
            return new ListResult(true, List.copyOf(out), count, "");
        } catch (RuntimeException e) {
            LOG.warn("[PhiMC] Phira 搜索响应解析失败", e);
            return ListResult.fail("接口返回解析失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 曲绘 / 通用下载
    // ------------------------------------------------------------------

    /** 曲绘缩略图的原始字节（失败返回 {@code null}，界面画占位块；<b>后台线程专用</b>）。 */
    public static byte[] fetchBytes(String url) {
        if (url == null || url.isBlank()) return null;
        List<String> urls = new ArrayList<>(2);
        urls.add(url);
        // 备用域名：同一个 path 在 api.phira.cn 上也能取到（CDN 前面挂了代理）
        String alt = mirrorOf(url);
        if (alt != null) urls.add(alt);
        for (String u : urls) {
            try {
                return getBytes(u, System.nanoTime() + IMAGE_BUDGET_MS * 1_000_000L);
            } catch (PhiraFail e) {
                LOG.warn("[PhiMC] Phira 资源下载失败 {}：{}", u, e.getMessage());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 域名健康度（失败的域名在冷却期内排到最后）
    // ------------------------------------------------------------------

    /** 域名是否还在冷却期。 */
    private static boolean isDown(String base) {
        Long until = BASE_DOWN_UNTIL.get(base);
        return until != null && until > System.currentTimeMillis();
    }

    private static void markBaseDown(String base) {
        BASE_DOWN_UNTIL.put(base, System.currentTimeMillis() + BASE_COOLDOWN_MS);
    }

    private static void markBaseUp(String base) {
        BASE_DOWN_UNTIL.remove(base);
    }

    /**
     * 按「先健康的、后冷却中的」排一遍 {@link #BASES}（同组内保持原顺序，排序稳定）。
     *
     * <p>两个都挂时顺序不变 —— 冷却不是拉黑，备用域名仍然会被试到（需求 3 的「切备用」不能失效）。
     */
    private static String[] orderedBases() {
        String[] out = BASES.clone();
        Arrays.sort(out, (a, b) -> Boolean.compare(isDown(a), isDown(b)));
        return out;
    }

    /** 把 {@code phira.5wyxi.com} 的 URL 换到另一个域名上（换不了返回 null）。 */
    private static String mirrorOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return null;
            String other = host.contains("api.phira.cn") ? "https://phira.5wyxi.com" : "https://api.phira.cn";
            if (!host.contains("phira")) return null;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return other + path + query;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 谱面包下载
    // ------------------------------------------------------------------

    /** 下载进度回调（后台线程里调，实现方只更新 volatile 字段）。 */
    public interface Progress {
        void update(long received, long total);
    }

    /** 下载失败（消息已经是可以直接给玩家看的中文）。 */
    public static final class PhiraFail extends Exception {
        public PhiraFail(String message) {
            super(message);
        }
    }

    /**
     * 把谱面包下载到 {@code target}（<b>阻塞</b>，后台线程专用）。
     *
     * <p>先写 {@code <target>.part}，校验「zip 魔数 + 非空」后再原子改名成 {@code target}：
     * 半截文件不会被 {@link SongEntry#scan} 当成谱面包扫进去，也不会覆盖已经下好的同名包。
     *
     * @throws PhiraFail 超时 / HTTP 错误 / 磁盘错误 / 内容不是谱面包（消息是中文）
     */
    public static void download(PhiraChart chart, Path target, Progress progress) throws PhiraFail {
        String url = chart.fileUrl();
        if (url.isEmpty()) throw new PhiraFail("接口没有给谱面包地址");
        long id = chart.id;

        List<String> urls = new ArrayList<>(4);
        if (url.toLowerCase(Locale.ROOT).startsWith("http")) urls.add(url);
        // 需求里的两个接口写法都试：① CDN 绝对 URL ② /file/{文件ID}
        if (!chart.fileId().isEmpty()) {
            for (String base : BASES) {
                String viaId = base + "/file/" + chart.fileId();
                if (!urls.contains(viaId)) urls.add(viaId);
            }
        } else if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
            for (String base : BASES) urls.add(base + (url.startsWith("/") ? url : "/" + url));
        }
        String alt = url.toLowerCase(Locale.ROOT).startsWith("http") ? mirrorOf(url) : null;
        if (alt != null && !urls.contains(alt)) urls.add(alt);

        PhiraFail last = null;
        for (String u : urls) {
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".part");
            try {
                Path dir = target.getParent();
                if (dir != null) Files.createDirectories(dir);
                fetchToFile(u, tmp, progress);
                long size = Files.size(tmp);
                if (size <= 4 || !looksLikeZip(tmp)) {
                    deleteQuietly(tmp);
                    throw new PhiraFail("下载到的不是谱面包（可能是错误页 / 被拦截），已删除临时文件");
                }
                move(tmp, target);
                progress.update(size, size);
                return;
            } catch (PhiraFail e) {
                deleteQuietly(tmp);
                last = e;
                LOG.warn("[PhiMC] 谱面包下载失败 {}：{}", u, e.getMessage());
            } catch (IOException e) {
                deleteQuietly(tmp);
                last = new PhiraFail("磁盘写入失败：" + e.getMessage() + "（检查 config/phimc/songs/ 的权限和剩余空间）");
                LOG.warn("[PhiMC] 谱面包写入失败 {}", target, e);
            }
        }
        throw last == null ? new PhiraFail("下载失败：没有可用的下载地址") : last;
    }

    /** 单次 HTTP 下载（写临时文件 + 报进度）。 */
    private static void fetchToFile(String url, Path tmp, Progress progress) throws PhiraFail {
        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(getRequest(url), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new PhiraFail("请求超时（15 秒）：" + hostOf(url));
        } catch (IOException e) {
            throw new PhiraFail("连接失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhiraFail("下载被中断");
        }
        if (resp.statusCode() != 200) {
            closeQuietly(resp.body());
            throw new PhiraFail(httpMessage(resp.statusCode()));
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (total > MAX_DOWNLOAD_BYTES) {
            closeQuietly(resp.body());
            throw new PhiraFail("谱面包过大（" + (total / 1024 / 1024) + " MB），已放弃下载");
        }
        long got = 0;
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (got > MAX_DOWNLOAD_BYTES) throw new PhiraFail("谱面包超过 128 MB 上限，已中断");
                if (progress != null) progress.update(got, total);
            }
        } catch (PhiraFail e) {
            throw e;
        } catch (IOException e) {
            throw new PhiraFail("下载中断（网络或磁盘）：" + e.getMessage());
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** zip 魔数 {@code 50 4B 03 04}（.pez 就是 zip）。 */
    private static boolean looksLikeZip(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && (head[0] & 0xFF) == 0x50 && (head[1] & 0xFF) == 0x4B
                    && (head[2] & 0xFF) == 0x03 && (head[3] & 0xFF) == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 底层 HTTP
    // ------------------------------------------------------------------

    private static HttpRequest getRequest(String url) throws PhiraFail {
        return getRequest(url, System.nanoTime() + REQUEST_TIMEOUT.toMillis() * 1_000_000L);
    }

    /**
     * 建请求：{@code timeout = min(15s, 到这个截止时间还剩多少)}。
     *
     * <p>注意：JDK 的 {@code HttpRequest.timeout} 只管到「响应头到手」，
     * 头部到了之后读响应体不再受它约束（实测 15s 的请求跑过 27.2s），真正的硬上限靠
     * {@link #sendWithDeadline}。
     */
    private static HttpRequest getRequest(String url, long deadlineNanos) throws PhiraFail {
        try {
            long remainMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.min(REQUEST_TIMEOUT.toMillis(), remainMs)))
                    .header("Accept", "application/json, */*")
                    // 社区接口对默认 UA 不友好，带一个能识别的（不含任何个人信息）
                    .header("User-Agent", "PhiMC/0.1 (Minecraft mod; local)")
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            throw new PhiraFail("非法的下载地址：" + url);
        }
    }

    /**
     * 带<b>硬截止时间</b>的 GET。
     *
     * <p>为什么不直接用 {@code HTTP.send}：{@code HttpRequest.timeout} 不是硬上限
     * （见 {@link #getRequest(String, long)} 的注释），只有把 {@code sendAsync} 的结果
     * 用 {@code get(remaining)} 掐一次，才能保证「一次搜索 4.5 秒内一定有结论」。
     * 到点后 {@code cancel(true)} ——JDK 内部的交换已经挂了它自己的定时器，会自己收尾。
     */
    private static <T> HttpResponse<T> sendWithDeadline(HttpRequest req, HttpResponse.BodyHandler<T> handler,
                                                        long deadlineNanos, String url) throws PhiraFail {
        long remainMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        CompletableFuture<HttpResponse<T>> future = HTTP.sendAsync(req, handler);
        try {
            return future.get(remainMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PhiraFail("请求超时（" + seconds(remainMs) + "）：" + hostOf(url));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PhiraFail("请求被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new PhiraFail("请求超时（" + seconds(remainMs) + "）：" + hostOf(url));
            }
            if (cause instanceof IOException) {
                throw new PhiraFail("连接失败：" + cause.getMessage());
            }
            throw new PhiraFail("网络请求失败：" + describe(cause));
        }
    }

    private static String getString(String url, long deadlineNanos) throws PhiraFail {
        HttpResponse<String> resp = sendWithDeadline(getRequest(url, deadlineNanos),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), deadlineNanos, url);
        if (resp.statusCode() != 200) throw new PhiraFail(httpMessage(resp.statusCode()));
        return resp.body();
    }

    private static byte[] getBytes(String url, long deadlineNanos) throws PhiraFail {
        HttpResponse<byte[]> resp = sendWithDeadline(getRequest(url, deadlineNanos),
                HttpResponse.BodyHandlers.ofByteArray(), deadlineNanos, url);
        if (resp.statusCode() != 200) throw new PhiraFail(httpMessage(resp.statusCode()));
        return resp.body();
    }

    /** HTTP 状态码 → 中文提示（界面上直接显示，不再拼英文）。 */
    private static String httpMessage(int status) {
        if (status == 429) return "请求过于频繁（HTTP 429），等一分钟再试";
        if (status == 404) return "资源不存在（HTTP 404）";
        if (status >= 500) return "服务器错误（HTTP " + status + "），稍后再试";
        if (status >= 400) return "请求被拒绝（HTTP " + status + "）";
        return "HTTP " + status;
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h;
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** 毫秒 → {@code 4.5 秒}（提示信息里用，不写成 4500ms）。 */
    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0) + " 秒";
    }

    /** 异常 → 一行中文（只留类型 + 消息，不带堆栈）。 */
    private static String describe(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + "：" + msg;
    }

    private static String enc(String s) {
        // URLEncoder 把空格编成 '+'；统一成 %20，两个镜像都稳
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 清空搜索缓存和域名健康度（自测用；界面上没有入口）。 */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        BASE_DOWN_UNTIL.clear();
    }
}
