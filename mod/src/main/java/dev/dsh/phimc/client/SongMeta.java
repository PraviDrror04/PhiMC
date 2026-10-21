package dev.dsh.phimc.client;

import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 选曲界面「右侧信息栏」里那几个<b>只能从谱面 JSON 得到</b>的字段。
 *
 * <ul>
 *   <li>物量（音符总数）和 BPM —— {@code info.txt} 里没有这两项；</li>
 *   <li><b>RPE 包的画师 / 曲师 / 谱师 / 曲绘图文件名 / 难度原文</b> —— RPE 的 {@code info.txt}
 *       <b>没有 Illustrator 字段</b>（实测 4 个 pez 全没有），这些值只写在谱面 JSON 的
 *       {@code META} 里（见 {@link ChartMeta}）。</li>
 * </ul>
 *
 * <p>谱面动辄 10-14 MB，<b>不能在渲染线程里同步解析</b>，也不能在 {@code init()} 里批量解析
 * （19 首 = 几百 MB 的临时对象）。做法：<b>切到哪首才解析哪首</b>，解析放后台，结果按包路径缓存。
 * 解析完成前 {@link #get} 返回 {@code null}，界面显示「…」；失败同样返回 {@code null}，
 * 只记一次日志、不重试。
 *
 * <p><b>后台是「一个 worker + 一条队列」</b>（{@link #dispatch()} / {@link #drain()}）：
 * 同一时刻只解析一首（避免「点一下物量排序 → 19 个线程各啃 10 MB 的 JSON」把内存打爆）。
 * 需要全量数据的地方（本地页「排序：物量」）用 {@link #requestAll(List)} 一次排队，
 * 每个包仍然<b>只读一次</b>，读完进 {@link #CACHE}。
 *
 * <p>META 的读取是<b>流式</b>的（{@link ChartMeta#scan}）：只把 {@code META} 那个小对象读进来，
 * 读到就停，不会 lex 后面十几 MB 的音符数组，所以这条后台线程的主要成本仍然是
 * {@code ChartParser.parse}（本来就要为物量/BPM 跑）。
 */
public final class SongMeta {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 物量 + BPM。BPM 取「谱面里出现次数最多的那条判定线的 bpm」（多 BPM 谱面也不会乱跳）。 */
    public record Meta(int notes, double bpm) {
    }

    /**
     * 一条缓存：物量/BPM <b>和</b> META 兜底一起存。
     *
     * <p><b>为什么兜底也要进缓存</b>：{@link SongEntry} 是 {@code SongEntry.scan()} 每次重建的
     * <b>新对象</b>（打完一局返回、从设置页回来、换资源包回来都会 rescan），而 {@code entry.meta}
     * 是实例字段，重建就没了。只缓存 {@code Meta} 的话，命中缓存时「没什么可回填的」，
     * 新对象就永远退回 info.txt 原值 —— 这正是「画师一开始正常，打一局回来变回未知」的根因。
     */
    private record Cached(Meta meta, SongEntry.MetaFallback fallback) {
    }

    /** key = 谱面包绝对路径。 */
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    /** 待解析队列（串行消费，见 {@link #drain()}）。 */
    private static final Queue<SongEntry> QUEUE = new ConcurrentLinkedQueue<>();
    /** 是否已经有一个 worker 在跑（保证同一时刻只有一个解析线程）。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private SongMeta() {
    }

    /**
     * 取物量 / BPM：命中缓存立刻返回；没命中就<b>排进后台队列</b>（本次返回 {@code null}）。
     * 渲染线程安全 —— 从不阻塞。
     *
     * <p>后台 worker 顺手做第二件事：流式扫一遍 {@code META}（{@link ChartMeta#scan}），
     * 把 info.txt 缺的「曲师 / 谱师 / 画师 / 曲绘图文件名 / 难度原文」写回
     * {@link SongEntry#fillFromMeta}。
     *
     * <p><b>命中缓存时也要回填</b>：{@code entry} 可能是刚 rescan 出来的新对象
     * （{@code entry.meta == null}），缓存里那份兜底必须重新塞回去，否则界面又显示「未知」。
     * 这个回填只是一次 volatile 赋值（同实例时直接跳过），不会每帧写。
     */
    public static Meta get(SongEntry entry) {
        if (entry == null || entry.path == null) return null;
        String key = keyOf(entry);
        Cached hit = CACHE.get(key);
        if (hit != null) {
            entry.fillFromMeta(hit.fallback());        // ★ 重建后的新对象靠这一行恢复
            return hit.meta();
        }
        if (FAILED.contains(key)) return null;
        enqueue(entry);
        return null;
    }

    /**
     * <b>只查缓存</b>（不排队、不解析）—— 本地页「排序：物量」用它取音符数。
     *
     * @return 还没解析过时返回 {@code null}，排序那边把这种行排到最后
     */
    public static Meta peek(SongEntry entry) {
        if (entry == null || entry.path == null) return null;
        Cached c = CACHE.get(keyOf(entry));
        if (c == null) return null;
        entry.fillFromMeta(c.fallback());              // 顺手补兜底（幂等）
        return c.meta();
    }

    /**
     * 把一批谱面的物量 / BPM / META 排进后台队列（串行、每个包只读一次）。
     *
     * <p>只给「需要全量数据」的操作调用（本地页点了「排序：物量」）：<b>不会</b>在
     * {@code SongEntry.scan} / 界面 {@code init()} 里自动发生，所以列表初始化仍然是
     * 「只读 info.txt」的轻量扫描。
     */
    public static void requestAll(List<SongEntry> entries) {
        if (entries == null) return;
        for (SongEntry e : entries) {
            if (e == null || e.path == null) continue;
            String key = keyOf(e);
            if (CACHE.containsKey(key) || FAILED.contains(key)) continue;
            enqueue(e);
        }
    }

    private static String keyOf(SongEntry e) {
        return e.path.toAbsolutePath().toString();
    }

    /** 入队（去重：已经在队列里 / 正在解析的不会重复排）。 */
    private static void enqueue(SongEntry entry) {
        String key = keyOf(entry);
        if (!LOADING.add(key)) return;                 // 已排队 / 已在解析
        QUEUE.add(entry);
        dispatch();
    }

    /** 没有 worker 就起一个（{@link #RUNNING} 保证同一时刻只有一个）。 */
    private static void dispatch() {
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread t = new Thread(SongMeta::drain, "phimc-songmeta");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 串行消费队列：一次只解析一首，队列空了就把 {@link #RUNNING} 放开并退出。
     *
     * <p>退出前再抢一次锁（标准写法）：清标志的瞬间可能刚好有新条目入队，
     * 那个线程看到 {@code RUNNING == true} 就不会起新 worker，所以这里必须兜住。
     */
    private static void drain() {
        while (true) {
            SongEntry entry = QUEUE.poll();
            if (entry == null) {
                RUNNING.set(false);
                if (QUEUE.isEmpty() || !RUNNING.compareAndSet(false, true)) return;
                continue;
            }
            String key = keyOf(entry);
            try {
                parseInto(entry, key);
            } catch (Throwable e) {
                FAILED.add(key);
                LOG.warn("[PhiMC] 谱面信息解析失败（物量/BPM/META）: {}", entry.path, e);
            } finally {
                LOADING.remove(key);
            }
        }
    }

    /** 真正读一个包：① 流式 META → ② 全量解析拿物量/BPM → ③ 一起进缓存。 */
    private static void parseInto(SongEntry entry, String key) throws Exception {
        String json = entry.chartJson();
        // ① META（流式，读到 META 就停，不碰十几 MB 的音符数组）。
        //    PEC 文本谱面不是 JSON，ChartMeta.scan 会抛 JsonSyntaxException —— 这里单独兜住，
        //    不能让它把后面的「物量 / BPM」也一起拖死（PEC 谱面照样要有物量和 BPM）。
        SongEntry.MetaFallback fallback = null;
        try {
            ChartMeta meta = ChartMeta.scan(json);
            if (!meta.isEmpty()) {
                fallback = new SongEntry.MetaFallback(
                        meta.composer(), meta.charter(), meta.illustrator(),
                        meta.picture(), meta.level());
                entry.fillFromMeta(fallback);
                LOG.info("[PhiMC] META 兜底：{} | 曲师「{}」谱师「{}」画师「{}」曲绘「{}」",
                        entry.path.getFileName(), meta.composer(), meta.charter(),
                        meta.illustrator(), meta.picture());
            }
        } catch (RuntimeException noMeta) {
            LOG.info("[PhiMC] 没有 META 可读（非 JSON 谱面？）: {} | {}",
                    entry.path.getFileName(), noMeta.getClass().getSimpleName());
        }
        // ② 物量 / BPM（本来就要跑的那一趟全量解析）
        Chart chart = ChartParser.parse(json);
        CACHE.put(key, new Cached(new Meta(chart.noteCount(), bpmOf(chart)), fallback));
    }

    /**
     * 给刚扫出来的一批 {@link SongEntry} 回填 META 兜底（<b>只查缓存，绝不解析</b>）。
     *
     * <p>{@link SongEntry#scan} 建完对象后调它：打完一局返回 → rescan 出新对象 →
     * 缓存里已经有兜底的条目立刻恢复成真人名，不用等玩家再点到那一首。
     * 缓存里没有的条目<b>什么都不做</b>（保持惰性：等选中那一首时由 {@link #get} 解析）。
     */
    static void refillAll(List<SongEntry> entries) {
        if (entries == null || entries.isEmpty() || CACHE.isEmpty()) return;
        for (SongEntry e : entries) {
            if (e == null || e.path == null) continue;
            Cached c = CACHE.get(e.path.toAbsolutePath().toString());
            if (c != null) e.fillFromMeta(c.fallback());
        }
    }

    /** 是否正在后台解析（界面显示「…」还是「-」）。 */
    public static boolean isLoading(SongEntry entry) {
        return entry != null && entry.path != null
                && LOADING.contains(entry.path.toAbsolutePath().toString());
    }

    /**
     * 丢掉所有缓存（物量/BPM、META 兜底、失败标记）。
     *
     * <p>给「换资源包」那条路径用（{@code PhiMCClient.applyResPack}，那是本 mod 唯一的
     * 「重新加载一切」钩子）；也是自测里模拟「缓存被清」的入口。
     * 清掉之后下一次 {@link #get} 会重新解析并<b>重新回填</b> META 兜底 ——
     * 不会有「缓存没了就永远回不来」的状态。
     */
    public static void clear() {
        CACHE.clear();
        LOADING.clear();
        FAILED.clear();
        QUEUE.clear();
    }

    /** 出现次数最多的 bpm（保留 2 位小数比较）；全是 0 就当 120。 */
    private static double bpmOf(Chart chart) {
        Map<Long, Integer> hist = new HashMap<>();
        Map<Long, Double> exact = new HashMap<>();
        for (Chart.Line line : chart.lines) {
            long k = Math.round(line.bpm * 100.0);
            hist.merge(k, 1, Integer::sum);
            exact.put(k, line.bpm);
        }
        long best = -1;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : hist.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best <= 0) return 120.0;
        return exact.getOrDefault(best, 120.0);
    }

    /** BPM 显示文本：「175」/「175.5」（整数就不带小数点）。 */
    public static String bpmText(double bpm) {
        double r = Math.round(bpm * 10.0) / 10.0;
        return Math.abs(r - Math.rint(r)) < 1e-9
                ? Long.toString(Math.round(r))
                : String.format(java.util.Locale.ROOT, "%.1f", r);
    }
}
