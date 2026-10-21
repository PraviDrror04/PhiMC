package dev.dsh.phimc.client;

import dev.dsh.phimc.client.audio.OggToWav;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;
import dev.dsh.phimc.core.audio.AudioSource;
import dev.dsh.phimc.core.audio.WavSource;
import dev.dsh.phimc.survival.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 一次游玩会话：谱面 + 判定引擎 + 音频时钟 + 输入 + 成绩存档。
 *
 * <p>曲目来源见 {@link SongEntry}：压缩包（info.txt + Chart_XX.json + music.wav）或目录。
 * 时间只认 {@link AudioSource#positionSeconds()}；音频不可用时退回 {@link AudioSource.WallClock}。
 */
public final class Session {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    private final Path songsDir;
    private final List<SongEntry> songs = new ArrayList<>();
    /** 最佳成绩存档（选曲界面显示，{@link Records}）。**按世界隔离**：进世界时切换到该世界的 records.tsv。 */
    private final Records records;
    /**
     * 玩法进度 + 客户端设置，{@link Profile}。两个文件：全局设置 {@code config/phimc/profile.json}，
     * 玩法进度（解锁/成绩/商店/赠礼/价格种子）{@code saves/<世界名>/phimc/profile.json}。
     * 进世界时由 {@link #onWorldJoin(Path)} 切换。
     */
    private final Profile profile;

    private Chart chart;
    private Engine engine;
    /**
     * 音乐源。<b>volatile</b>：后台音频线程在播放失败时会把这里换成墙钟兜底，
     * 渲染线程每帧都在读它，必须让替换可见（否则会出现「时钟永远停在 -0.15s」）。
     */
    private volatile AudioSource audio = new AudioSource.WallClock();
    private boolean playing;
    private volatile boolean audioOk;
    private String audioName = "-";
    private String lastGrade = "-";
    /** 上一次 {@link #start(int)} 失败的简短中文原因（选曲界面显示用）；成功时是空串。 */
    private volatile String lastFailReason = "";
    private double lastError;
    /** 当前曲目下标（重来用）。 */
    private int currentIndex = -1;
    /** 当前曲目的音频负载（重来时不重新解码）。{@code null} = 没有音频 / 还没转换好。 */
    private volatile SongEntry.Audio rawPayload;
    /** 转换后<b>可以直接交给 {@link WavSource}</b> 的负载（OGG 已经解成 WAV；MP3 / WAV 原样）。 */
    private volatile AudioSource.Payload cachedPayload;
    private SongEntry current;
    /** 暂停态：{@link #seconds()} 冻结在 {@link #pausedAt}。 */
    private volatile boolean paused;
    private double pausedAt;
    /**
     * 上一次报出去的谱面时间。{@link #seconds()} 只增不减。
     *
     * <p>prpr 的时间基准是墙钟（{@code prpr/src/time.rs:86-88} {@code now()} 由
     * {@code real_time - start_time} 算出，天然单调），音乐位置只用来纠偏（time.rs:90-94）。
     * 我们用音频位置当基准，就得自己保证「不倒退」：设备重开、缓冲区状态变化、
     * 暂停-继续衔接都可能让设备时间抖一下，而谱面时间只要退一次就是「重放」。
     */
    private double lastSeconds = Double.NEGATIVE_INFINITY;
    /** 一局结束（所有音符判完）——PlayScreen 用它决定是否显示结算。 */
    private boolean finished;
    /** 本局开始前的最佳成绩（结算界面显示「新纪录」用）。 */
    private Records.Rec bestBefore;

    // ------------------------------------------------------------------
    // 打击特效参数：全部来自 prpr（数值见各自注释）
    // ------------------------------------------------------------------

    /**
     * 特效寿命 = 资源包 {@code hitFxDuration}，默认 0.5s
     * （resource.rs:35-37 {@code default_duration}，resource.rs:324/336 注入两个 emitter）。
     */
    public static final double HIT_FX_LIFETIME = 0.5;

    /**
     * hit_fx 精灵的尺寸系数：{@code size = hit_fx_scale * note_scale / 5}
     * （resource.rs:368-371 {@code set_scale}；资源包 assets/respack/info.yml 里 {@code hitFxScale: 0.9}，
     * note_scale 默认 1.0 见 config.rs:109）。
     */
    public static final double HIT_FX_SPRITE_SIZE = 0.9 / 5.0;

    /** 4 颗方粒子的尺寸：{@code size = hit_fx_scale * note_scale / 44}（resource.rs:370）。 */
    public static final double HIT_FX_SQUARE_SIZE = 0.9 / 44.0;

    /** 方粒子初速 {@code 2.5 * scale}（resource.rs:341）。 */
    public static final double HIT_FX_SQUARE_SPEED = 2.5;

    /** 方粒子速度随机衰减比例 {@code 1./10.}（resource.rs:342）。 */
    public static final double HIT_FX_SPEED_RANDOMNESS = 1.0 / 10.0;

    /**
     * {@code linear_accel = -6.}（resource.rs:343）。particle.rs:664 是
     * {@code velocity += velocity * linear_accel * dt}，即速度按指数衰减，不是匀减速。
     */
    public static final double HIT_FX_SQUARE_ACCEL = -6.0;

    /** 方粒子方向散布 {@code 2π}（resource.rs:338，emitter_square 的 initial_direction_spread）。 */
    public static final double HIT_FX_SPREAD = Math.PI * 2.0;

    /** 精灵粒子颜色曲线：start=白(α1) → mid=白(α0.7) → end=白(α0)（resource.rs:311-318）。 */
    public static final double HIT_FX_MID_ALPHA = 0.7;

    /** hold 期间的粒子间隔 0.15s（note.rs:9 {@code HOLD_PARTICLE_INTERVAL}，note.rs:143-163）。 */
    public static final double HOLD_PARTICLE_INTERVAL = 0.15;

    /** 判定色：Perfect 用资源包 color_perfect 默认值 0xe1ffec9f，Good 用 0xebb4e1ff（resource.rs:40-47）。 */
    public static final int COLOR_PERFECT = 0xE1FFEC9F;
    public static final int COLOR_GOOD = 0xEBB4E1FF;

    /** BadNote 的颜色：judge.rs:392 {@code Color::new(0.423529, 0.262745, 0.262745, a)}。 */
    public static final int COLOR_BAD_NOTE = 0xFF6C4343;

    /**
     * 划动（flick）阈值：judge.rs:19 {@code FLICK_SPEED_THRESHOLD = 0.8}，judge.rs:99-103
     * {@code threshold = FLICK_SPEED_THRESHOLD * dpi / 386}（dpi 被硬编码成 275），
     * judge.rs:122 判定条件 {@code delta.magnitude()/dt >= threshold * 2}。
     * 单位 = 归一化屏幕坐标 / 秒。
     */
    public static final double FLICK_THRESHOLD = 0.8 * 275.0 / 386.0 * 2.0;

    public enum ParticleKind {
        /** hit_fx 图集精灵：静止、覆盖整个寿命（resource.rs:321-333，velocity=0，spread=0）。 */
        SPRITE,
        /** 4 颗飞散小方块（resource.rs:334-346）。 */
        SQUARE,
        /** Bad 判定后残留的音符本体（note.rs:369-396 BadNote）。 */
        BAD_NOTE
    }

    /** 一颗特效粒子：发射时刻、屏幕位置（0~1）、颜色（含 α 初值）、速度（世界单位/秒）。 */
    public record Particle(double time, double xFrac, double yFrac, int argb,
                           ParticleKind kind, double vx, double vy) {
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Random rng = new Random();
    /** 屏幕宽高比（PlayScreen 每帧喂进来），用于点击点 / 音符位置 ⇄ Phira 世界坐标（judge.rs:473-474）。 */
    private double aspect = Engine.DEFAULT_ASPECT;
    /** 上一次看到的 Miss 计数。 */
    private int seenMiss;
    /**
     * {@link #tick()} 的重入标记。判定驱动现在有两个调用点（渲染每帧 + 客户端 20Hz 兜底），
     * 万一将来有回调在 tick 内部又转回来调一次，这个标记保证不会递归。
     */
    private boolean inTick;
    /** 每个 hold 上一次发射粒子的时间（note.rs:143-163 的 {@code at} 字段）。 */
    private final java.util.IdentityHashMap<Engine.NoteState, Double> holdEmit = new java.util.IdentityHashMap<>();

    /**
     * 键盘游玩：<b>永远是开的</b>。
     *
     * <p>设置页的开关已经删掉（用户要求），但 {@code Profile.useKeyboard} 字段保留做存档兼容
     * （老 {@code profile.json} 里可能是 {@code false}）。所以这里<b>不读存档</b>、直接置 {@code true}，
     * 相当于「读取时强制 true」；{@link #setUseKeyboard(boolean)} 也只会把它设成 true。
     */
    private boolean useKeyboard = true;
    /**
     * 自动游玩：{@code true} 时每帧由 {@link Engine#autoplay(double)} 把到点的音符判成 Perfect，
     * 玩家不需要任何输入（{@link #tick()} 里替换掉 {@code engine.update(t)}）。
     *
     * <p>初值 / 每次开局都从 {@link Profile#autoplay()} 同步（选曲界面那个勾选按钮写的就是它）：
     * 「勾上 → 开局」生效，「局中改」不影响已开的这一局。
     *
     * <p>自动局<b>不写成绩、不发奖励</b>（{@link #finish()} 里判），否则等于用 AUTO 刷记录。
     */
    private boolean autoplay;
    /**
     * 「手指」状态：源码是 judge.rs:424-461 组出来的 {@code touches}（鼠标左键那一路）。
     * 只有两处用到——judge.rs:712-716 的 hold 维持判据、judge.rs:749-755 的 Flick/Drag 预判。
     */
    private boolean pointerDown;
    private double pointerX = 0.5;
    private double pointerY = 0.5;

    public Session(Path songsDir) {
        this.songsDir = songsDir;
        Path parent = songsDir.getParent();
        // 全局设置（B 类：延迟/音量/材质包/自动游玩…）= config/phimc/profile.json，整个实例共用
        this.profile = new Profile(parent == null ? songsDir.resolve("profile.json") : parent.resolve("profile.json"));
        // 最佳成绩（A 类）按世界隔离：进世界时由 onWorldJoin() 切到 saves/<世界>/phimc/records.tsv。
        // 还没进世界时传 null —— 不读不写，避免把上一个世界的成绩带进来。
        this.records = new Records(null);
        // 键盘游玩恒开（开关已从设置页移除）；不再读 profile.useKeyboard()，老存档里是 false 也一样开。
        this.useKeyboard = true;
        // 自动游玩读存档（默认 false），选曲界面勾选后写回存档
        this.autoplay = profile.autoplay();
        rescan();
    }

    /**
     * 进世界：把「玩法进度」切到这个世界自己的文件（{@code saves/<世界名>/phimc/}）。
     *
     * <p>由 {@code client/WorldHooks} 在 {@code ClientPlayConnectionEvents.JOIN} 里调用。
     *
     * @param worldSaveRoot 世界存档根目录（集成服务端的 {@code getSavePath(WorldSavePath.ROOT)}）；
     *                      {@code null} = 多人游戏 / 取不到 → 玩法进度退化成只读兜底，不写任何文件
     */
    public void onWorldJoin(Path worldSaveRoot) {
        if (worldSaveRoot == null) {
            profile.detachWorld();
            records.open(null);
            LOG.info("[PhiMC] 没有本地世界存档（多人 / 取不到存档目录）：玩法进度只读");
            return;
        }
        // 旧版单文件 profile.json 里的进度会在这一步迁到这个世界（只迁一次，见 Profile.attachWorld）
        boolean migrated = profile.attachWorld(WorldStore.profileFile(worldSaveRoot));
        records.open(WorldStore.recordsFile(worldSaveRoot), migrated ? legacyRecordsFile() : null);
        LOG.info("[PhiMC] 玩法进度已切换到 {}（旧进度迁移: {}，解锁 {} 首 / 成绩 {} 条）",
                WorldStore.progressDir(worldSaveRoot), migrated,
                profile.unlockedCount(), profile.records().size());
    }

    /**
     * 退世界：清掉当前世界的玩法进度，回到「没有世界」的只读状态。
     *
     * <p>必须清空：否则上一个世界的解锁留在内存里，可能被下一个世界当成旧版进度迁移过去。
     */
    public void onWorldLeave() {
        profile.detachWorld();
        records.open(null);
    }

    /** 旧版单文件的成绩表（{@code config/phimc/records.tsv}），只在一次性迁移时当种子用。 */
    private Path legacyRecordsFile() {
        Path parent = songsDir.getParent();
        return parent == null ? songsDir.resolve("records.tsv") : parent.resolve("records.tsv");
    }

    public void rescan() {
        songs.clear();
        songs.addAll(SongEntry.scan(songsDir));
    }

    /**
     * 谱面目录（{@code config/phimc/songs/}）。
     *
     * <p>在线页签把下载的谱面包写到这里，写完调 {@link #rescan()} 就能在本地页签看到，
     * 所以这里是本 mod 唯一对外暴露曲库路径的地方（其余代码都走 {@link #songs()}）。
     */
    public Path songsDir() {
        return songsDir;
    }

    public String songTitle() {
        if (current == null) return "";
        String d = current.display;
        int bar = d.indexOf(" · ");
        return bar > 0 ? d.substring(0, bar) : d;
    }

    public String songLevel() {
        if (current == null) return "";
        String d = current.display;
        int bar = d.indexOf(" · ");
        return bar > 0 ? d.substring(bar + 3) : "";
    }
    public List<SongEntry> songs() {
        return songs;
    }

    public boolean playing() {
        return playing;
    }

    /** 自动游玩是否开启（本局）。 */
    public boolean autoplay() {
        return autoplay;
    }

    /**
     * 设置自动游玩：写进本局标志 + 落盘到 {@code profile.json}（跨局保持）。
     *
     * <p>只影响<b>下一局及之后的帧</b>：正在打的一局如果已经在跑，切开关会立刻改变
     * {@link #tick()} 的判定通路（见那里的注释），但已经判过的音符不会重判。
     */
    public void setAutoplay(boolean on) {
        this.autoplay = on;
        profile.autoplay(on);
    }

    public boolean finished() {
        return finished;
    }

    public boolean paused() {
        return paused;
    }

    public boolean audioOk() {
        return audioOk;
    }

    public String audioName() {
        return audioName;
    }

    public Engine engine() {
        return engine;
    }

    public Chart chart() {
        return chart;
    }

    public String lastGrade() {
        return lastGrade;
    }

    public double lastError() {
        return lastError;
    }

    /**
     * 上一次 {@link #start(int)} 失败的中文原因（一行、可直接贴到界面上）；成功时是空串。
     *
     * <p>典型值：{@code JSON 语法错误：…}、{@code 谱面太大，内存不足}、
     * {@code 谱面里没有音符（判定线 0 条）}、{@code 读谱面包失败：…}。
     */
    public String lastFailReason() {
        return lastFailReason;
    }

    /** 本局开始前的最佳成绩，没有则 null。 */
    public Records.Rec bestBefore() {
        return bestBefore;
    }

    public Records records() {
        return records;
    }

    /** 生存玩法存档（解锁 / 成绩 / 商店）。 */
    public Profile profile() {
        return profile;
    }

    /** 当前曲目的谱面 id（{@code 曲目#难度}），没在玩时为 null。 */
    public String currentChartId() {
        SongEntry e = current;
        return e == null ? null : e.chartId();
    }

    /** 当前曲目（结算界面要拿它去取曲绘），没在玩时为 null。 */
    public SongEntry current() {
        return current;
    }

    /** 上一次打开的曲目下标（{@link #startLast()} 用），-1 表示还没玩过。 */
    public int currentIndex() {
        return currentIndex;
    }

    /** 重打上一首（平板潜行右键，设计文档 §2.1）。 */
    public boolean startLast() {
        return currentIndex >= 0 && start(currentIndex);
    }

    /**
     * 某一首在 {@link #songs()} 里的下标（<b>按谱面包路径比</b>，找不到返回 -1）。
     *
     * <p>选曲界面的列表会按「搜索 / 筛选 / 排序」重排成另一个顺序（{@code view}），
     * 而 {@link #start(int)} 要的是 {@code songs()} 里的下标；中间就靠这个方法换算。
     * 用路径而不是引用：{@link #rescan()} 会重建全部 SongEntry 对象。
     */
    public int indexOf(SongEntry entry) {
        if (entry == null || entry.path == null) return -1;
        Path want = entry.path.toAbsolutePath();
        for (int i = 0; i < songs.size(); i++) {
            Path p = songs.get(i).path;
            if (p != null && p.toAbsolutePath().equals(want)) return i;
        }
        return -1;
    }

    /** 全部谱面 id（商店货架的候选集）。 */
    public List<String> chartIds() {
        List<String> out = new ArrayList<>(songs.size());
        for (SongEntry e : songs) out.add(e.chartId());
        return out;
    }

    /** 某首曲子的最佳成绩（选曲界面用）。 */
    public Records.Rec best(SongEntry entry) {
        return entry == null ? null : records.get(keyOf(entry));
    }

    private static String keyOf(SongEntry entry) {
        return entry.path.getFileName().toString();
    }

    public double seconds() {
        if (paused) return pausedAt;
        double pos = audio.positionSeconds();
        // ① 谱面自带 offset：正数表示谱面比音乐快，要从音频时间里减掉
        double base = dev.dsh.phimc.core.Clock.toChartTime(
                Math.max(0, pos), chart == null ? 0.0 : chart.offset);
        // ② 玩家偏移（设置页「偏移 -300..+300ms」）：**+ = 谱面提前**。
        //    同一时刻的音频位置对应的谱面时间更大 ⇒ 音符更早到达判定线。
        //    和 ① 是两个独立的东西：谱面自带的写在谱面包里，这个只在本机存档（profile.offsetMs）。
        double t = base + profile.offsetMs() / 1000.0;
        // 只增不减：任何时刻都不允许谱面往回走（见 lastSeconds 的说明）
        if (t < lastSeconds) t = lastSeconds;
        lastSeconds = t;
        return t;
    }

    public boolean start(int index) {
        if (index < 0 || index >= songs.size()) return false;
        SongEntry entry = songs.get(index);
        this.current = entry;
        this.lastFailReason = "";
        String json = null;
        try {
            json = entry.chartJson();
            // 大谱面：>20MB 先提醒一句（解析要建完整对象树，内存吃紧时容易 OOM）
            if (json.length() > 20L * 1024L * 1024L) {
                LOG.warn("[PhiMC] 谱面 JSON 较大（{} MB），解析可能占用较多内存: {} | path={}",
                        String.format("%.1f", json.length() / 1048576.0), entry.display,
                        entry.path == null ? "-" : entry.path.toAbsolutePath());
            }
            chart = ChartParser.parse(json);
        } catch (Throwable e) {
            // 这里**故意** catch Throwable：大谱面解析爆内存（OutOfMemoryError）也要给玩家一句人话，
            // 而不是让整个客户端崩掉 / 只显示一句「解析失败」。
            chart = null;
            lastFailReason = summarizeParseFailure(e);
            logParseFailure(entry, json, e);
            return false;
        }
        if (chart.noteCount() == 0) {
            // 解析没抛异常，但一个音符都没有（judgeLineList 为空 / 整份数据都被跳过）
            lastFailReason = "谱面里没有音符（判定线 " + chart.lines.size() + " 条）";
            LOG.warn("[PhiMC] 谱面没有音符: {} | path={} | 格式 {} | 判定线 {} 条",
                    entry.display, entry.path == null ? "-" : entry.path.toAbsolutePath(),
                    chart.format, chart.lines.size());
            chart = null;
            return false;
        }
        engine = new Engine(chart, new Judgement.Config());
        engine.reset();
        currentIndex = index;
        // 自动游玩：每次开局从存档同步一次（选曲界面勾选的按钮写的就是 profile.autoplay）
        this.autoplay = profile.autoplay();
        finished = false;
        paused = false;
        pausedAt = 0;
        lastSeconds = Double.NEGATIVE_INFINITY;
        bestBefore = records.get(keyOf(entry));
        lastGrade = "-";
        lastError = 0;
        seenMiss = 0;
        particles.clear();
        holdEmit.clear();
        pointerDown = false;

        audio = new WavSource();
        audioOk = false;
        audioName = "none";
        SongEntry.Audio payload = null;
        try {
            payload = entry.payload();
        } catch (Exception e) {
            LOG.warn("[PhiMC] 读音频失败: {}", entry.display, e);
        }
        rawPayload = payload;
        cachedPayload = null;
        final double chartDuration = chart.duration;
        if (payload != null && WavSource.available()) {
            // 解码 + 开设备要几百毫秒，放后台线程，别卡渲染线程
            final SongEntry.Audio p = payload;
            final AudioSource src = audio;
            Thread loader = new Thread(() -> {
                try {
                    // OGG 必须在这里先解成 WAV：JavaSound 不认 Ogg 容器，
                    // 直接丢给 WavSource 会抛 UnsupportedAudioFileException，
                    // 然后时钟就永远停在 -0.15s（谱面一动不动）。
                    AudioSource.Payload playable = toPlayable(p);
                    cachedPayload = playable;             // 「重来」复用，不重新解码
                    src.play(playable);
                    // 解码/开设备期间用户可能已经按了 Esc：那时 play() 还没生效，
                    // pause() 是个空操作，音乐会在暂停菜单底下自己响。这里补一次。
                    if (paused) src.pause();
                    audioName = p.name();
                    audioOk = true;
                } catch (Throwable t) {
                    // **不能就这么算了**：以前这里只打一行日志，`audio` 留着一个从没 start 过的
                    // WavSource，positionSeconds() 恒为 0 ⇒ 谱面时间恒为 offset（-0.15s），
                    // 玩家看到的就是「进游戏后卡住/跑不了」。
                    // 现在退化成墙钟（无声但谱面照常走），至少能玩、能看谱面。
                    LOG.warn("[PhiMC] 音频播放失败，改用墙钟兜底（静音但谱面可玩）: {}", entry.display, t);
                    AudioSource.WallClock wc = new AudioSource.WallClock();
                    wc.setDuration(chartDuration);
                    audio = wc;
                    audioOk = false;
                    audioName = "墙钟兜底";
                }
            }, "phimc-audio");
            loader.setDaemon(true);
            loader.start();
            LOG.info("[PhiMC] 音频后台加载中: {}", payload.name());
        } else {
            AudioSource.WallClock wc = new AudioSource.WallClock();
            wc.setDuration(chart.duration);
            audio = wc;
        }
        playing = true;
        String audioState = audioOk ? audioName
                : (payload != null && WavSource.available() ? "后台加载中…" : "无（墙钟兜底）");
        LOG.info("[PhiMC] 载入 {} | 格式 {} | 音符 {} | 判定线 {} | 时长 {}s | 音频 {}",
                entry.display, chart.format, chart.noteCount(), chart.lines.size(),
                String.format("%.1f", chart.duration), audioState);
        return true;
    }

    /**
     * 谱面包里的音频字节 → {@link WavSource} 能直接播的形状。
     *
     * <p>JavaSound 只认 WAV；MP3 由 {@link WavSource} 内部用 JLayer 解（它自己有一段快速判定，
     * 所以这里原样透传）；<b>OGG 没有任何 provider 认</b>，必须先经
     * {@link OggToWav#decode(byte[])} 解成 PCM/WAV。
     *
     * <p>修前这里是把原始字节直接贴个 {@code "wav"} 标签交出去的，于是所有 {@code .ogg} 的谱面包
     * 都会在 {@code AudioSystem.getAudioInputStream} 抛
     * {@code UnsupportedAudioFileException: Stream of unsupported format}
     * （实测 4 首：{@code 14143_「僕は…」}、{@code 39417_Sphalerite}、{@code 48273_Undertale}、
     * {@code 78805_enchanted love}）。
     */
    private static AudioSource.Payload toPlayable(SongEntry.Audio a) throws Exception {
        byte[] data = a.data();
        if (OggToWav.isOgg(data)) {
            byte[] wav = OggToWav.decode(data);
            LOG.info("[PhiMC] OGG 已解成 PCM：{} {} 字节 → {} 字节 WAV",
                    a.name(), data.length, wav.length);
            return new AudioSource.Payload(wav, a.name(), "ogg->wav");
        }
        return new AudioSource.Payload(data, a.name(), "wav");
    }

    /**
     * 解析失败的诊断日志（需求 2「先让错误可诊断」）：
     * <b>谱面包绝对路径、谱面 JSON 大小、异常类型 + 消息、JSON 开头 200 字符</b>。
     *
     * <p>OOM 只打路径和大小：再截 200 字符又要复制一次大字符串，可能二次 OOM。
     */
    private void logParseFailure(SongEntry entry, String json, Throwable e) {
        String path = entry.path == null ? "-" : entry.path.toAbsolutePath().toString();
        long sizeChars = json == null ? -1 : json.length();
        if (e instanceof OutOfMemoryError) {
            LOG.error("[PhiMC] 谱面解析失败（内存不足）: {} | path={} | json={} 字符 | 建议：加大 -Xmx 或换更小的谱面",
                    entry.display, path, sizeChars);
            return;
        }
        String head = "";
        try {
            if (json != null) {
                head = json.substring(0, Math.min(200, json.length()))
                        .replace('\n', ' ').replace('\r', ' ')
                        .replaceAll("\\s+", " ");
            }
        } catch (Throwable ignored) {
            // 截断都失败就算了，日志里留空
        }
        LOG.warn("[PhiMC] 谱面解析失败: {} | path={} | json={} 字符 | {}: {} | 开头: {}",
                entry.display, path, sizeChars, e.getClass().getName(), e.getMessage(), head);
    }

    /** 异常 → 一句中文原因（界面提示用，不带堆栈）。 */
    static String summarizeParseFailure(Throwable e) {
        if (e instanceof OutOfMemoryError) return "谱面太大，内存不足（加大 -Xmx 再试）";
        if (e instanceof java.util.zip.ZipException) return "谱面包损坏：" + brief(e.getMessage(), 70);
        if (e instanceof java.io.IOException) return "读谱面包失败：" + brief(e.getMessage(), 70);
        if (e instanceof com.google.gson.JsonSyntaxException) {
            return "JSON 语法错误：" + brief(firstLine(e.getMessage()), 70)
                    + "（不是 JSON 谱面？需要 PEC / 其它格式的解析器）";
        }
        if (e instanceof IllegalArgumentException) return "谱面数据不合法：" + brief(e.getMessage(), 70);
        String name = e.getClass().getSimpleName();
        return name + (e.getMessage() == null ? "" : "：" + brief(e.getMessage(), 70));
    }

    private static String firstLine(String s) {
        if (s == null) return "-";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String brief(String s, int max) {
        if (s == null) return "-";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /**
     * 重来（暂停菜单的「重来」）。复用已经解析好的谱面与已解码的音频负载，
     * 不重新读盘/解码，只把引擎和时钟归零。
     */
    public void restart() {
        if (engine == null || chart == null) return;
        engine.reset();
        paused = false;
        pausedAt = 0;
        lastSeconds = Double.NEGATIVE_INFINITY;
        finished = false;
        lastGrade = "-";
        lastError = 0;
        seenMiss = 0;
        particles.clear();
        holdEmit.clear();
        pointerDown = false;
        audio.stop();
        AudioSource fresh;
        final AudioSource.Payload ready = cachedPayload;      // 已经是「可播放形状」
        if (ready != null && WavSource.available()) {
            fresh = new WavSource();
            try {
                fresh.play(ready);
                audioOk = true;
            } catch (Throwable t) {
                LOG.warn("[PhiMC] 重来失败，改用墙钟: {}", t.toString());
                AudioSource.WallClock wc = new AudioSource.WallClock();
                wc.setDuration(chart.duration);
                fresh = wc;
                audioOk = false;
            }
        } else if (rawPayload != null && WavSource.available()) {
            // 第一次加载还没转换完（或者转换失败）→ 现场转一次；失败就退墙钟
            fresh = new WavSource();
            try {
                AudioSource.Payload playable = toPlayable(rawPayload);
                cachedPayload = playable;
                fresh.play(playable);
                audioOk = true;
            } catch (Throwable t) {
                LOG.warn("[PhiMC] 重来：音频转换/播放失败，改用墙钟: {}", t.toString());
                AudioSource.WallClock wc = new AudioSource.WallClock();
                wc.setDuration(chart.duration);
                fresh = wc;
                audioOk = false;
            }
        } else {
            AudioSource.WallClock wc = new AudioSource.WallClock();
            wc.setDuration(chart.duration);
            fresh = wc;
            audioOk = false;
        }
        audio = fresh;
        playing = true;
        LOG.info("[PhiMC] 重来：{}", currentIndex >= 0 ? songs.get(currentIndex).display : "-");
    }

    public void stop() {
        playing = false;
        paused = false;
        lastSeconds = Double.NEGATIVE_INFINITY;
        audio.stop();
    }

    /**
     * Esc 暂停（prpr/src/scene/game.rs:849-858 {@code Scene::pause}：
     * {@code music.pause()} + {@code tm.pause()}）。
     *
     * <p>顺序：先冻结谱面时间，再冻结音频。{@link AudioSource#pause()} 内部也是
     * 「先取位置、再停设备」，所以这里读完 {@link #seconds()} 之后，
     * 音频侧冻结的位置和 {@link #pausedAt} 是同一个时刻，误差在微秒级。
     */
    public void pause() {
        if (!playing || paused) return;
        pausedAt = seconds();
        paused = true;
        audio.pause();
        LOG.info("[PhiMC] 暂停 @ {}s（音频 {}s）",
                String.format("%.3f", pausedAt), String.format("%.3f", audio.positionSeconds()));
    }

    /**
     * 暂停菜单的「继续」（prpr/src/scene/game.rs:860-865 {@code Scene::resume}：{@code tm.resume()}）。
     *
     * <p>先让音频从暂停点重新跑起来，再解冻谱面时间；顺序反了会出现
     * 「时间轴已经解冻、音频还停在 0」的窗口，那就是用户看到过的「谱面从 0 重放」。
     */
    public void resume() {
        if (!paused) return;
        audio.resume();
        double after = dev.dsh.phimc.core.Clock.toChartTime(
                Math.max(0, audio.positionSeconds()), chart == null ? 0.0 : chart.offset);
        double drift = after - pausedAt;
        if (Math.abs(drift) > 0.25) {
            // 设备重开异常时宁可让谱面停在暂停点等音频，也不要往回跳
            LOG.warn("[PhiMC] 继续后位置偏差 {}s（暂停点 {}s / 音频 {}s）",
                    String.format("%.3f", drift),
                    String.format("%.3f", pausedAt), String.format("%.3f", after));
        }
        paused = false;
        lastSeconds = Math.max(lastSeconds, pausedAt);
        LOG.info("[PhiMC] 继续 @ {}s（音频 {}s）",
                String.format("%.3f", pausedAt), String.format("%.3f", after));
    }

    /**
     * 推进一帧判定。**现在由渲染每帧驱动**（{@link PlayScreen#render}），不再是 20Hz。
     *
     * <h2>为什么从 20Hz 改成每帧</h2>
     * 以前唯一的驱动点是 {@code ClientTickEvents.END_CLIENT_TICK}（固定 20Hz，周期 50ms），
     * 而判定是<b>按驱动时刻采样</b>的：Drag/Flick 要先在 {@code engine.update()} 里进 PreJudge、
     * 再等 {@code t >= note.time} 转 Perfect（{@code judge.rs:781-783}），所以它们的判定时刻
     * 被量化到驱动步边界。实测（{@code tools/judge/TickLatency.java}，Cyberneko Pulse，
     * 3092 音符）：20Hz 时 Drag 平均晚 26.2ms / 最大 50.0ms，144Hz 时平均 3.5ms / 最大 6.9ms；
     * 而 Tap 走 {@code hitAt/pressKey}（事件到了现读 {@code seconds()}），不被量化、误差 0 ——
     * 这就是「Tap 听着准、Drag/Flick 听着拖」的全部原因（打击音在判定那一刻播）。
     *
     * <h2>两条判定通路</h2>
     * <ul>
     *   <li>手动：{@code pushPointer() → engine.update(t)} —— 吃本帧输入、扫 Miss；</li>
     *   <li>自动游玩（{@link #autoplay()}）：{@code engine.autoplay(t)} —— 引擎自己把
     *       {@code note.time <= t} 的音符判成 Perfect，<b>内部接着调 {@code update(t)}</b>
     *       （{@code Engine.java:235-243}），所以 Miss 扫描 / 时钟推进一步不少，
     *       和 {@code StressTest} 里 autoplay 完全同一条路径。</li>
     * </ul>
     * 自动局不推「手指」（{@link #pushPointer()}）：Drag/Flick 的预判输入对自动局没有意义，
     * 而且真被判掉也是 Perfect，不如让引擎一条路走完。判定 / 计分公式一行没改。
     *
     * <h2>幂等 / 可重入</h2>
     * 现在有两个调用点（渲染每帧 + 客户端 20Hz 兜底），所以：
     * <ul>
     *   <li><b>可重入安全</b>：{@link #inTick} 挡住「判定/特效回调里又转回来调 tick」的递归；</li>
     *   <li><b>幂等</b>：同一帧被驱动两次不会重复判定/重复播打击音 —— {@code Engine.judge} 有
     *       {@code if (s.judged) return} 守卫，判定事件队列 {@code drainJudgements()} 取走即清空，
     *       {@code Miss} 扫描与计时都是「看当前 t」而非累加，恢复指针/按键状态是覆盖赋值。
     *       所以重复调用的代价只是多一趟线性扫描，没有任何可听/可见的副作用。</li>
     * </ul>
     */
    public void tick() {
        if (inTick) return;
        if (!playing || engine == null || paused || finished) return;
        inTick = true;
        try {
            double t = seconds();
            if (autoplay) {
                engine.autoplay(t);                     // 内部会调 update(t)，不要重复调用
            } else {
                // judge.rs:424-461：把当前「手指」交给判定（hold 维持 / Flick 预判要用）
                pushPointer();
                engine.update(t);
            }
            // judge.rs:797-861：本帧所有判定（含 Drag 的自动 Perfect）都从这里发特效
            flushFx(t);
            int miss = engine.miss();
            if (miss > seenMiss) {
                // 源码里 Miss 不发任何特效（judge.rs:821-857 只处理 Perfect/Good/Bad），这里也不发。
                seenMiss = miss;
            }
            emitHoldParticles(t);
            pruneParticles(t);
            // 结算条件：所有音符判定完 且 音乐真正播完（chart.duration 是音符结尾，音频常比它长，
            // 之前用 chart.duration+1 会导致音乐没播完就出结算）。音频时长未知时才退回谱面时长。
            double endAt = audio.durationSeconds() > 0 ? audio.durationSeconds() : chart.duration;
            if (engine.judgedCount() >= engine.total() && t > endAt + 0.5) {
                finish();
            }
        } finally {
            inTick = false;
        }
    }

    /** 把「手指」状态推给判定引擎（judge.rs:424-461 的 {@code touches}，位置未知时传 null）。 */
    private void pushPointer() {
        if (engine == null) return;
        engine.setPointer(pointerDown, pointerDown ? pointerX : null, pointerDown ? pointerY : null, aspect);
    }

    /**
     * 发本帧攒下的判定特效 / 打击音。位置 = 音符自己的原点（prpr {@code emit_at_origin}，
     * resource.rs:577-587 用当前模型原点）；Hold 不发<b>特效</b>（judge.rs:818-820 直接 {@code continue}），
     * 但 Hold 头命中时<b>要</b>播打击音——源码在判定点就播（judge.rs:626/688），是两回事。
     */
    private void flushFx(double t) {
        if (engine == null) return;
        for (Engine.JudgeEvent e : engine.drainJudgements()) {
            playHitSound(e);
            addFx(t, e.state());
        }
    }

    /**
     * 判定命中时的打击音。
     *
     * <p>只有 Perfect / Good 播：judge.rs:821-857 只对这两种返回「真命中」，judge.rs:858-860
     * 才 {@code note.hitsound.play(res)}；Bad / Miss 不播。播哪一段由音符种类决定
     * （judge.rs:52-59 {@code HitSound::default_from_kind}）：Tap/Hold → click、Drag → drag、Flick → flick。
     * 音量 / 总开关在 {@link HitSoundPlayer} 里读 {@code profile.hitSoundVolume()} / {@code profile.hitSound()}。
     */
    private void playHitSound(Engine.JudgeEvent e) {
        if (e.grade() != Judgement.Grade.PERFECT && e.grade() != Judgement.Grade.GOOD) return;
        HitSoundPlayer.play(e.state().note.type, profile);
    }

    /**
     * 一局结束：停音频、存最佳成绩（records.tsv + profile.json）。结算界面留在屏幕上（{@link #finished()}）。
     *
     * <h2>自动游玩局的取舍（用户已定）</h2>
     * <b>不写成绩、不发奖励</b>：
     * <ul>
     *   <li>自动游玩每一局都是 1000000 / PHI，写进 {@code records.tsv} 或 {@code profile.json}
     *       就等于「用 AUTO 刷最佳成绩」，把「最佳」「FC」这类数据全部作废；</li>
     *   <li>{@code Rewards.grantOnFinish} 也<b>不调</b>，所以自动局拿不到任何结算物资 ——
     *       {@code Rewards.lastReward} 不会被这一局覆盖（结算界面按 {@code session.autoplay()}
     *       自己显示「奖励：无（自动游玩）」，见 {@code PlayScreen.drawResult}）。</li>
     * </ul>
     * 判定 / 计分本身完全照常跑（同 {@code StressTest}），只是「结算落盘」这一步被跳过；
     * 平板赠送、商店解锁不在这条路径上，不受影响。
     */
    private void finish() {
        if (finished) return;
        finished = true;
        playing = false;
        audio.stop();
        SongEntry entry = currentIndex >= 0 && currentIndex < songs.size() ? songs.get(currentIndex) : null;
        boolean auto = autoplay;                 // 本局是不是自动游玩（用开局时的标志）
        if (entry != null && engine != null) {
            if (auto) {
                LOG.info("[PhiMC] 自动游玩结算（不写成绩、不发奖励）：{} | 分数 {}",
                        entry.display, engine.score());
            } else {
                boolean fc = engine.maxCombo() == engine.total();
                // 字段对应 Phira 的 SimpleRecord（prpr/src/scene/game.rs:966-970）
                records.submit(keyOf(entry),
                        new Records.Rec(engine.score(), engine.accuracy(), engine.maxCombo(), engine.total(), fc));
                // 生存玩法的成绩（设计文档 §6：score / acc / fc / combo）。
                // acc 存百分数（与文档示例 98.42 一致）；只有更高分才会覆盖（Profile.record 内部判断）。
                profile.record(entry.chartId(), engine.score(), engine.accuracy() * 100.0, fc, engine.maxCombo());
                // 结算物资奖励（S5）：按评级发物品，装不下掉脚下。
                // 只读 engine.score()，判定与计分公式一行没改；这里是「一局结算」的唯一入口，
                // 所以奖励也只发一次（finish() 自身幂等，restart() 会复位 finished → 重打就是新的一次）。
                dev.dsh.phimc.survival.Rewards.grantOnFinish(entry.chartId(), engine.score());
            }
        }
        LOG.info("[PhiMC] 结算：{} | 分数 {} | ACC {:.2f}% | {}/{} | {}",
                entry == null ? "-" : entry.display, engine.score(), engine.accuracy() * 100.0,
                engine.maxCombo(), engine.total(), auto ? "自动游玩" : "手动");
    }

    /**
     * hold 按住期间的粒子：note.rs:143-163 每 {@code HOLD_PARTICLE_INTERVAL / res.config.speed}
     * 秒发一次，颜色用 fx_perfect / fx_good。
     */
    private void emitHoldParticles(double t) {
        for (Engine.NoteState s : engine.activeHolds()) {
            Double at = holdEmit.get(s);
            if (at == null) at = s.note.time;
            if (t > at) {
                holdEmit.put(s, at + HOLD_PARTICLE_INTERVAL);
                double[] p = Engine.screenPos(s, t, aspect);
                int color = s.grade == Judgement.Grade.PERFECT ? COLOR_PERFECT : COLOR_GOOD;
                emit(p[0], p[1], t, color, ParticleKind.SPRITE);
            }
        }
    }

    private void pruneParticles(double t) {
        particles.removeIf(p -> t - p.time() > HIT_FX_LIFETIME);
    }

    public void hit() {
        hitAt(null);
    }

    /**
     * 配置 {@code use_keyboard}：{@code config.rs:83} 声明、{@code config.rs:123} 默认 {@code false}。
     *
     * <p>本 mod <b>恒为 true</b>：设置页的「键盘 开/关」已删掉（用户要求），
     * {@code Profile.useKeyboard} 字段只为存档兼容保留。判定侧仍然尊重这个开关
     * （{@code res.config.use_keyboard == false} 时 {@code keys_down} / {@code key_delta} 当 0，
     * judge.rs:465-469），只是现在没有任何入口能关掉它。
     */
    public boolean useKeyboard() {
        return useKeyboard;
    }

    /**
     * 「打开」键盘游玩 —— 现在只有 true 有意义：入参会被忽略，永远落回 {@code true}。
     *
     * <p>{@link PlayScreen} 进界面时无条件调 {@code setUseKeyboard(true)}，
     * 顺手把 {@code profile.json} 里那个只做兼容的字段也写成 true（老存档第一次进游戏即自动纠正）。
     */
    public void setUseKeyboard(boolean on) {
        useKeyboard = true;
        profile.useKeyboard(true);
    }

    /**
     * 键盘按下（judge.rs:1009-1014 {@code key_down_event}）：{@code key_delta += 1; keys_down += 1;}。
     *
     * <p>判定本身在 {@link Engine#keyDown(double)} → {@code for _ in 0..keys_down} 里做
     * （judge.rs:649-698），**不校验横向位置、也不校验 EARLY_OFFSET**。
     * 同一次按下 N 个键就是 N 次判定，每次都吃掉「当前最早未判的 Tap/Hold」。
     */
    public void pressKey() {
        if (!useKeyboard) return;                       // judge.rs:465-469：关掉时 keys_down 恒为 0
        if (autoplay) return;                           // 自动游玩局不吃输入（见类里的 autoplay 说明）
        if (!playing || paused || finished || engine == null) return;
        double t = seconds();
        engine.keyDown(t);
        flushFx(t);
    }

    /**
     * 空格专用甩键（用户要求）：不消耗 Tap/Hold，只作为「有输入」让 Flick / Drag 进 PreJudge
     * （judge.rs:742-758；源码里任意键按住即可，空格是额外的独立通路）。
     * 和 {@link #pressKey()} 一样受 {@code use_keyboard} 开关约束（judge.rs:465-469）。
     */
    public void pressFlickKey() {
        if (!useKeyboard) return;
        if (autoplay) return;                           // 自动游玩局不吃输入
        if (!playing || paused || finished || engine == null) return;
        engine.setFlickKey(true);
    }

    public void releaseFlickKey() {
        if (engine != null) engine.setFlickKey(false);
    }

    /**
     * 键盘抬起（judge.rs:1016-1018 {@code key_up_event}）：源码里<b>只减 {@code key_delta}，
     * 不减 {@code keys_down}</b>。所以抬起不会产生 Tap 判定，只会让 {@code key_down_count} 掉下去；
     * Hold 的断连由每帧维持逻辑决定（judge.rs:710-727，见 {@link Engine} 里的说明）。
     */
    public void releaseKey() {
        if (!useKeyboard || engine == null) return;
        if (autoplay) return;                            // 自动游玩局不吃输入（keyUp 会走上帧结算）
        engine.keyUp(seconds());
    }

    /** 鼠标（= 源码里的「手指」）位置变了：judge.rs:424-461 的 touches。 */
    public void pointerMove(double xFrac, double yFrac) {
        pointerX = xFrac;
        pointerY = yFrac;
    }

    /**
     * 鼠标抬起：<b>不是</b>「立刻结束 hold」。源码里抬手只是让 {@code touches} 里那根手指消失，
     * 尾巴要不要断由每帧的 judge.rs:710-727 决定：
     * {@code key_down_count == 0} 且没有手指在音符 x 范围内 → 先记 {@code up_time}，
     * 超过 {@link Engine#UP_TOLERANCE}（50ms）才算 Miss；中途按回来就撤销。
     */
    public void pointerUp() {
        // 暂停 / 结算里的抬手不算「松开」：暂停面板得用鼠标点，那一下不能把 hold 弄断
        if (!playing || paused || finished) return;
        pointerDown = false;
        pushPointer();
    }

    public void hitAt(Double xFrac) {
        hitAt(xFrac, null);
    }

    /**
     * 鼠标点击（横向 + 纵向）：xFrac / yFrac 都是 0~1 的屏幕比例。
     * 判定几何全在 {@link Engine#hit(double, Double, Double, double)} 里，这里只转发 + 记录特效。
     */
    public void hitAt(Double xFrac, Double yFrac) {
        if (autoplay) return;                            // 自动游玩局不吃输入
        if (!playing || paused || finished || engine == null) return;
        pointerDown = true;                       // 按下 = 一根手指落在屏幕上（judge.rs:428-435）
        if (xFrac != null) pointerMove(xFrac, yFrac == null ? 0.5 : yFrac);
        double t = seconds();
        Engine.NoteState s = engine.hit(t, xFrac, yFrac, aspect);
        flushFx(t);
        if (s == null) return;
        lastGrade = s.grade.name();
        lastError = t - s.note.time;
    }

    /**
     * 鼠标「按住并在屏幕上移动」= 一次判定（**用户要求的行为**，见 {@link Engine#dragTouch}）。
     *
     * <p>源码里移动中的触摸只判 Flick（judge.rs:554-558），Tap/Hold 必须靠按下瞬间
     * （{@code TouchPhase::Started}）。这里把每次移动都当成一次按下：
     * 先走点击通路（Tap / Hold，横向校验照旧，接受窗口收到 Perfect 档——源码里移动派生的
     * 判定本来就是 Perfect，judge.rs:790-793），没吃到再走划动通路（Flick，judge.rs:640-646）。
     * 所以按住左键疯狂划动时，每个移动事件都会吃掉当时窗口内的一个音符，且必然是 Perfect ——
     * 这就是「按住鼠标划也能 AP」。
     *
     * @param speedNdc 归一化屏幕坐标 / 秒，达到 {@link #FLICK_THRESHOLD} 才算「划」
     */
    public void dragAt(Double xFrac, Double yFrac, double speedNdc) {
        if (autoplay) return;                            // 自动游玩局不吃输入
        if (!playing || paused || finished || engine == null) return;
        pointerDown = true;
        if (xFrac != null) pointerMove(xFrac, yFrac == null ? 0.5 : yFrac);
        double t = seconds();
        Engine.NoteState s = engine.dragTouch(t, xFrac, yFrac, aspect, speedNdc >= FLICK_THRESHOLD);
        flushFx(t);
        if (s == null) return;
        lastGrade = s.grade.name();
        lastError = t - s.note.time;
    }

    /** 屏幕宽高比，由渲染层每帧喂入（判定要把点击点换算成 Phira 世界坐标）。 */
    public void setAspect(double aspect) {
        if (aspect > 0.1 && aspect < 20.0) this.aspect = aspect;
    }

    /**
     * 判定特效落在**音符自己的原点**上——prpr 的 {@code emit_at_origin}
     * （resource.rs:577-587）用当前模型原点 {@code world_to_screen(Point::default())}，
     * 而音符渲染时模型原点正好是音符中心（note.rs:262 → draw_center）。
     *
     * <p>逐条照 judge.rs:797-861：
     * <ul>
     *   <li>judge.rs:818-820：{@code if matches!(note.kind, Hold) { continue; }} ⇒ Hold 不发打击特效；</li>
     *   <li>judge.rs:821-833：Perfect / Good 发粒子，颜色取 {@code fx_perfect} / {@code fx_good}，
     *       <b>与音符种类无关</b>；</li>
     *   <li>judge.rs:834-855：Bad 不发粒子，只在原位留一个 {@code BadNote}（note.rs:369-396），
     *       Hold 的 Bad 连 BadNote 都不留（judge.rs:835）。</li>
     * </ul>
     * 所以「判定为 Perfect 的 Drag」在原版是**会**发特效的（它既不是 Hold，也没被 Special Case），
     * 我们之前只在鼠标/键盘命中时发、Drag 的自动 Perfect 不发，这就是「Drag 命中没特效」的原因。
     */
    private void addFx(double t, Engine.NoteState s) {
        if (s.note.type == Chart.Note.Type.HOLD) return;          // judge.rs:818-820
        double[] p = Engine.screenPos(s, t, aspect);
        switch (s.grade) {
            case PERFECT, GOOD -> {
                if (t < s.note.time) {
                    // 早打的音符：prpr 在判定瞬间把模型时间设回 note.time 再取原点（judge.rs:800-801）
                    p = Engine.screenPos(s, s.note.time, aspect);
                }
                emit(p[0], p[1], t, s.grade == Judgement.Grade.PERFECT ? COLOR_PERFECT : COLOR_GOOD,
                        ParticleKind.SPRITE);
            }
            case BAD -> {
                // judge.rs:834-855：Bad 不发光，只在原位留一个 BadNote（note.rs:369-396），寿命 0.5s
                particles.add(new Particle(t, p[0], p[1], COLOR_BAD_NOTE, ParticleKind.BAD_NOTE, 0, 0));
            }
            default -> {
                // Miss：源码无特效
            }
        }
    }

    /** 发一组特效：1 个 hit_fx 精灵 + 4 颗方粒子（resource.rs:353-361 {@code emit_at}）。 */
    private void emit(double xFrac, double yFrac, double t, int color, ParticleKind kind) {
        if (particles.size() > 512) particles.remove(0);
        particles.add(new Particle(t, xFrac, yFrac, color, kind, 0, 0));
        for (int i = 0; i < 4; i++) {
            double dir = rng.nextDouble() * HIT_FX_SPREAD;       // initial_direction_spread = 2π
            double v = HIT_FX_SQUARE_SPEED * (1.0 - rng.nextDouble() * HIT_FX_SPEED_RANDOMNESS);
            particles.add(new Particle(t, xFrac, yFrac, color, ParticleKind.SQUARE,
                    Math.cos(dir) * v, Math.sin(dir) * v));
        }
    }

    /** 还活着的特效（渲染层负责画、tick 负责清理过期项）。 */
    public List<Particle> recentParticles() {
        return particles;
    }

    /**
     * 判定线颜色 —— 这就是 prpr 的 FC / AP 指示器本身（game.rs:1029-1037）：
     * <ul>
     *   <li>还没出现 Bad / Miss，且 {@code ap_fc_indicator} 打开（config.rs:57/97，默认开）：
     *       全 Perfect ⇒ {@code color_perfect}（默认 0xe1ffec9f），出过 Good ⇒ {@code color_good}
     *       （默认 0xebb4e1ff）；</li>
     *   <li>其它情况 ⇒ {@code WHITE}。</li>
     * </ul>
     * 开局（还没判定）时三个计数都是 0，所以线一开始就是 Perfect 色（game.rs:165 的 reset! 也显式这么做）。
     * 源码没有任何独立的 FC/AP 图标或文字，指示器就是「判定线整个变色」（line.rs:249
     * {@code color.unwrap_or(res.judge_line_color)}）。
     */
    public int judgeLineColor() {
        if (engine == null) return COLOR_PERFECT;
        if (engine.bad() + engine.miss() + engine.tailMiss() > 0) return 0xFFFFFFFF;
        return engine.good() == 0 ? COLOR_PERFECT : COLOR_GOOD;
    }

}
