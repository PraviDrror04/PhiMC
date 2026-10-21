package dev.dsh.phimc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Phira 谱面包的<b>后台下载 + 落盘 + 判重</b>。
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>{@link #download} 只是登记一个 {@link Job} 然后把活丢给 2 条 daemon 线程
 *       （{@code phimc-phira-dl-*}），<b>立即返回</b>，渲染线程不阻塞；</li>
 *   <li>{@link #job} / {@link #isDownloaded} 读的都是 volatile / 并发容器，渲染线程每帧调都安全；</li>
 *   <li>下载完成的后续动作（{@code rescan()}、切回本地页签、选中新谱面）<b>不在本类做</b>：
 *       那是渲染线程的活，由 {@link SongSelectScreen#pollDownload()} 侦测 {@link State#DONE} 后执行，
 *       这样「网络线程」和「UI 更新」的分界是一条明确的线（见报告第 2 节）。</li>
 * </ul>
 *
 * <h2>文件名与判重</h2>
 * <pre>
 * config/phimc/songs/{id}_{安全化曲名}.pez
 *   例：6882_R.I.P..pez           ← 曲名里的点保留（Windows 允许），空格也保留
 *       24101_RE Aoharu.pez
 *       77769_big song (self insert) MY LIFETIME.pez
 * </pre>
 * 非法字符（{@code \ / : * ? " < > |} + 控制字符）换成 {@code _}；首尾的点 / 空格去掉；
 * 曲名按<b>码点</b>截到 60 个字符（不会把 emoji 的代理对切成半个）；空名退成 {@code chart}。
 *
 * <p><b>判重只看 id 前缀</b>：{@code songs/} 下任何 {@code {id}_*.pez}（也认 {@code .zip}）都算
 * 已经下过这首，界面标「已下载」并禁用按钮，不会重复下载。判重不依赖谱面内容，
 * 因为同一首谱面在 Phira 上改了名之后 id 不变、文件名会变 —— 前缀才是稳定键。
 */
public final class PhiraDownloader {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 曲名过长时按码点截断的长度。 */
    private static final int MAX_NAME_CODE_POINTS = 60;

    /** {@code 6882_R.I.P..pez} → 6882；只认我们自己的命名（id 前缀 + 谱面包后缀）。 */
    private static final Pattern OWN_FILE =
            Pattern.compile("^(\\d+)_[^/\\\\]*\\.(?:pez|zip)$", Pattern.CASE_INSENSITIVE);

    /** 下载任务状态。 */
    public enum State {
        /** 排队中（另一个下载在跑）。 */
        QUEUED,
        /** 正在下载。 */
        RUNNING,
        /** 已落盘（{@link Job#file}）。 */
        DONE,
        /** 失败（{@link Job#message} 是中文原因）。 */
        FAILED
    }

    /** 一次下载的共享状态：后台线程写、渲染线程读（全部 volatile）。 */
    public static final class Job {
        public final long chartId;
        public final String chartName;
        private volatile State state = State.QUEUED;
        private volatile long received;
        private volatile long total;
        private volatile String message = "";
        private volatile Path file;

        private Job(long chartId, String chartName) {
            this.chartId = chartId;
            this.chartName = chartName == null ? "" : chartName;
        }

        public State state() {
            return state;
        }

        public long received() {
            return received;
        }

        /** 总字节数；{@code -1} = 服务端没给 Content-Length（进度显示成已下载大小）。 */
        public long total() {
            return total;
        }

        public String message() {
            return message;
        }

        public Path file() {
            return file;
        }

        /** 进度百分比（0~100）；总量未知时返回 -1。 */
        public int percent() {
            if (total <= 0) return -1;
            long p = received * 100L / total;
            return (int) Math.max(0, Math.min(100, p));
        }
    }

    private static final Map<Long, Job> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(2, daemonFactory());
    /** songs/ 下已经存在的 Phira 谱面 id（volatile 快照，渲染线程直接读）。 */
    private static volatile Set<Long> DOWNLOADED = Set.of();

    private PhiraDownloader() {
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "phimc-phira-dl-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------
    // 判重
    // ------------------------------------------------------------------

    /**
     * 重新扫一遍 {@code songs/}，重建「已下载 id」集合（只列目录，不读谱面包）。
     *
     * <p>界面在 {@code init()}、切到在线页、下载完成后各调一次；成本 = 一次目录列举，
     * 和 {@link SongEntry#scan} 比起来可以忽略。
     */
    public static void refresh(Path songsDir) {
        DOWNLOADED = songsDir == null ? Set.of() : scan(songsDir);
    }

    /** 列目录算出已下载的 id 集合（不在磁盘上就返回空集）。 */
    public static Set<Long> scan(Path songsDir) {
        Set<Long> out = new HashSet<>();
        if (songsDir == null || !Files.isDirectory(songsDir)) return out;
        try (Stream<Path> s = Files.list(songsDir)) {
            List<Path> files = s.toList();
            for (Path p : files) {
                if (!Files.isRegularFile(p)) continue;
                Matcher m = OWN_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                try {
                    out.add(Long.parseLong(m.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            LOG.warn("[PhiMC] 列举谱面目录失败: {}", songsDir, e);
        }
        return out;
    }

    /** 当前快照里是否已经有这首（{@link #refresh} 之后的数据）。 */
    public static boolean isDownloaded(long chartId) {
        return DOWNLOADED.contains(chartId);
    }

    /** 某个 id 的下载任务（没有 = {@code null}）。 */
    public static Job job(long chartId) {
        return JOBS.get(chartId);
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 开始下载（<b>立即返回</b>）。同一 id 已经在排队 / 下载中时返回同一个任务，不会重复下。
     *
     * <p>落盘路径见类注释；写临时文件 {@code .part}，校验 zip 魔数后再改名，
     * 半截文件不会被 {@link SongEntry#scan} 扫成一首歌。
     */
    public static Job download(PhiraChart chart, Path songsDir) {
        if (chart == null || songsDir == null) return null;
        Job existing = JOBS.get(chart.id);
        if (existing != null && (existing.state() == State.QUEUED || existing.state() == State.RUNNING)) {
            return existing;
        }
        if (isDownloaded(chart.id)) {
            Job done = new Job(chart.id, chart.title());
            done.state = State.DONE;
            done.message = "已下载";
            done.file = existingFile(chart.id, songsDir);
            JOBS.put(chart.id, done);
            return done;
        }
        Job job = new Job(chart.id, chart.title());
        JOBS.put(chart.id, job);
        PhiraChart snapshot = chart;
        Path target = targetFile(chart, songsDir);
        POOL.execute(() -> {
            job.state = State.RUNNING;
            job.total = -1;
            LOG.info("[PhiMC] Phira 下载开始：{} -> {}", snapshot.title(), target);
            try {
                PhiraApi.download(snapshot, target, (received, total) -> {
                    job.received = received;
                    if (total > 0) job.total = total;
                });
                job.file = target;
                job.state = State.DONE;
                refresh(songsDir);
                LOG.info("[PhiMC] Phira 下载完成：{}（{} 字节）", target.getFileName(), job.received);
            } catch (PhiraApi.PhiraFail e) {
                job.state = State.FAILED;
                job.message = e.getMessage();
                LOG.warn("[PhiMC] Phira 下载失败：{} | {}", target.getFileName(), e.getMessage());
            } catch (Throwable t) {
                job.state = State.FAILED;
                job.message = "下载失败：" + t.getClass().getSimpleName() + "（" + t.getMessage() + "）";
                LOG.warn("[PhiMC] Phira 下载异常：{}", target.getFileName(), t);
            }
        });
        return job;
    }

    /**
     * 落盘路径：{@code songs/{id}_{安全化曲名}.pez}（需求 2）。
     *
     * <p>只做字符串处理，不碰磁盘，所以界面线程也可以调（判重 / 显示目标文件名用）。
     */
    public static Path targetFile(PhiraChart chart, Path songsDir) {
        return songsDir.resolve(chart.id + "_" + safeBaseName(chart.title()) + ".pez");
    }

    /** 曲名 → 安全的文件名主干（不含 id 前缀与后缀），规则见类注释。 */
    public static String safeBaseName(String raw) {
        String s = raw == null ? "" : raw;
        // ① Windows / Unix 都不允许的字符 + 全部控制字符 → 下划线
        s = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]", "_");
        // ② 连续空白压成一个空格（谱面名里常有全角空格 / 制表符）
        s = s.replaceAll("\\s+", " ").trim();
        // ③ 首尾的点和空格（Windows 上会被悄悄去掉，导致「文件找不到」）
        s = s.replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        // ④ 按码点截断（代理对不会被劈开）
        if (s.codePointCount(0, s.length()) > MAX_NAME_CODE_POINTS) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME_CODE_POINTS));
        }
        if (s.isEmpty()) s = "chart";
        return s;
    }

    /** 已经存在的同 id 文件（{@code null} = 没有）。 */
    private static Path existingFile(long chartId, Path songsDir) {
        if (songsDir == null || !Files.isDirectory(songsDir)) return null;
        String prefix = chartId + "_";
        try (Stream<Path> s = Files.list(songsDir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                String low = name.toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix) && (low.endsWith(".pez") || low.endsWith(".zip"))) return p;
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
