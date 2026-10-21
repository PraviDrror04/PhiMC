package dev.dsh.phimc.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 一首歌的谱面来源。支持两种形态：
 * <ul>
 *   <li><b>压缩包</b>：<code>songs/xxx.zip</code> / <code>songs/xxx.pez</code>
 *       —— 里面是 info.txt + 谱面 JSON + 音乐 + 曲绘</li>
 *   <li><b>目录</b>：<code>songs/xxx/</code> —— chart.json + music.ogg/wav/mp3</li>
 * </ul>
 *
 * <p>两种谱面包的布局差别很大，<b>不能靠文件名猜</b>：
 * <table border="1">
 *   <tr><th></th><th>官方 Phigros 包</th><th>RPE 包（.pez）</th></tr>
 *   <tr><td>谱面</td><td><code>Chart_IN.json</code>（名字里有 chart）</td>
 *       <td><code>9691407376462442.json</code>（随机 id）</td></tr>
 *   <tr><td>音乐</td><td><code>music.wav</code> / <code>music.ogg</code></td>
 *       <td><code>9691407376462442.mp3</code></td></tr>
 *   <tr><td>曲绘</td><td><code>defaultImage.png</code></td><td><code>9691407376462442.jpg</code></td></tr>
 *   <tr><td>索引</td><td><code>info.txt</code>（Name/Level/...）</td>
 *       <td><code>info.txt</code>（多出 <code>Chart:</code> / <code>Song:</code> / <code>Picture:</code>）</td></tr>
 * </table>
 *
 * <p>所以查找顺序是：<b>① info.txt 里声明的文件名 → ② 后缀唯一的那个
 * → ③ 老包的名字启发式</b>（见 {@link #resolveChart} / {@link #resolveAudio}）。
 */
public final class SongEntry {

    public final String display;    // "Verruckt · IN Lv.?"
    public final String level;      // "IN Lv.?"
    /** 规范化难度（EZ / HD / IN / AT，识别不出时为 level 原文或 "-"）——交易单位里 {@code #} 后面那一段。 */
    public final String grade;
    public final Path path;
    public final boolean zip;
    /** 谱师（{@code Charter:}），没有就是空串。<b>这里只放 info.txt 的原值</b>，显示请用 {@link #charter()}。 */
    public final String charter;
    /** 曲师（{@code Composer:} / {@code SongBy:}），没有就是空串。同上，显示用 {@link #composer()}。 */
    public final String composer;
    /**
     * 画师（{@code Illustrator:} / {@code PictureBy:}），没有就是空串。
     *
     * <p><b>这是画师的「名字」，不是曲绘文件名</b>——文件名走 {@link #picture}（显示用 {@link #pictureName()}）。
     * 两个键长得很像，千万别混：RPE / Phira 的 info 里
     * {@code Picture: 1939818528942442.jpg}（文件）和 {@code Illustrator: 某个画师}（人名）
     * 可以同时存在。
     *
     * <p><b>RPE 的 info.txt 没有 Illustrator 字段</b>（实测 4 个 pez 全部没有），画师名在谱面 JSON 的
     * {@code META.illustration} 里 —— 那一路由 {@link SongMeta} 后台读出来填进 {@link #meta}，
     * 所以想拿「最终显示用的画师」请调 {@link #illustrator()}，不要直接读这个字段。
     */
    public final String illustrator;
    /**
     * 曲绘的<b>包内文件名</b>（来自 info.txt），没有就是空串。
     *
     * <p>真实谱包的写法（19 个样本里 19 个都有 {@code Picture:}）：官方 PGR 包
     * {@code Picture: defaultImage.png}，RPE 包 {@code Picture: 9691407376462442.jpg}。
     * 也认 {@code Illustration:}（Phira 的 info.yml 用这个名字）。
     * <b>不认 {@code Illustrator:}</b>——那是「曲绘画师」的名字，不是文件名。
     *
     * <p>同样只放 info.txt 的原值；info.txt 缺 {@code Picture:} 时由 {@code META.background} 兜底
     * （见 {@link #pictureName()}）。
     */
    public final String picture;

    /**
     * 谱面 JSON {@code META} 里读到的兜底字段（{@link SongMeta} 后台填，可能一直是 {@code null}）。
     *
     * <p>为什么放这里而不是在 {@link #scan} 时就填：谱面 JSON 10～14 MB，扫列表时全部解析会卡死
     * 渲染线程（{@code init()} 会随窗口缩放重跑）。所以只有「选中那一首、要显示详情」时，
     * {@link SongMeta} 才在后台读一次 META，读完写进这个字段。
     */
    private volatile MetaFallback meta;

    /** {@code META} 兜底字段。**人名和文件名分开放**，见 {@link ChartMeta} 的说明。 */
    public record MetaFallback(String composer, String charter, String illustrator,
                               String picture, String level) {
    }

    private SongEntry(String display, String level, String grade, Path path, boolean zip,
                      String charter, String composer, String illustrator, String picture) {
        this.display = display;
        this.level = level;
        this.grade = grade;
        this.path = path;
        this.zip = zip;
        this.charter = charter == null ? "" : charter;
        this.composer = composer == null ? "" : composer;
        this.illustrator = illustrator == null ? "" : illustrator;
        this.picture = picture == null ? "" : picture;
    }

    /** 曲名（{@code display} 里 {@code · } 之前那一段）。 */
    public String title() {
        int bar = display.indexOf(" · ");
        return bar > 0 ? display.substring(0, bar) : display;
    }

    // ---------- 最终显示值（info.txt 优先，META 只兜底） ----------

    /**
     * 曲师：info.txt 有就用 info.txt，缺了才用 {@code META.composer}。
     *
     * <p>info.txt 是「用户可见的权威字段」（RPE 编辑器里能直接改的就是它），META 只是它没写时的补丁。
     */
    public String composer() {
        return composer.isEmpty() ? metaOr(MetaFallback::composer) : composer;
    }

    /** 谱师：同上（info.txt {@code Charter:} → {@code META.charter}）。 */
    public String charter() {
        return charter.isEmpty() ? metaOr(MetaFallback::charter) : charter;
    }

    /**
     * 画师：info.txt {@code Illustrator:} → {@code META.illustration}（含 {@code cover} 等别名）。
     *
     * <p>RPE 包只有后者，这就是「画师不再是未知」的来源。
     */
    public String illustrator() {
        return illustrator.isEmpty() ? metaOr(MetaFallback::illustrator) : illustrator;
    }

    /**
     * 曲绘图文件名：info.txt {@code Picture:} → {@code META.background}。
     *
     * <p><b>只返回文件名</b>（{@code 9691407376462442.jpg}），永远不是画师名 ——
     * {@link #illustrator()} 和它来自两套互不相交的键（见 {@link ChartMeta}）。
     */
    public String pictureName() {
        if (!picture.isEmpty()) return picture;
        MetaFallback m = meta;
        return m == null || m.picture() == null ? "" : m.picture();
    }

    /** 难度显示文本：info.txt {@code Level:} → {@code META.level}；都没有就是 info.txt 里的 {@code "-"}。 */
    public String levelText() {
        boolean blank = level == null || level.isBlank() || level.equals("-");
        if (blank) {
            MetaFallback m = meta;
            if (m != null && m.level() != null && !m.level().isEmpty()) return m.level();
        }
        return level;
    }

    /**
     * 回填 META 兜底（由 {@link SongMeta} 在后台解析完成后、或从它的缓存里恢复时调用）。
     *
     * <p>幂等：同一个兜底实例重复回填直接返回 —— {@code SongMeta.get} 命中缓存时每帧都会调这里，
     * 这里挡掉多余的 volatile 写。
     */
    public void fillFromMeta(MetaFallback fallback) {
        if (fallback == null || this.meta == fallback) return;
        this.meta = fallback;
    }

    private String metaOr(java.util.function.Function<MetaFallback, String> getter) {
        MetaFallback m = meta;
        if (m == null) return "";
        String v = getter.apply(m);
        return v == null ? "" : v;
    }

    /**
     * 谱面 id = 曲目 + "#" + 难度，例如 {@code Verruckt#IN}（设计文档 §1）。
     * 这是**交易/解锁的最小单位**，也是 {@code PriceTable} 的哈希键。
     */
    public String chartId() {
        return title() + "#" + grade;
    }

    /** 从字符串里认出一个难度标记（按非字母数字切成 token，再全等匹配）。 */
    static String findGrade(String s) {
        if (s == null) return null;
        for (String tok : s.split("[^A-Za-z0-9]+")) {
            String t = tok.toUpperCase(java.util.Locale.ROOT);
            if (t.equals("EZ") || t.equals("HD") || t.equals("IN") || t.equals("AT")) return t;
        }
        return null;
    }

    // ---------- 扫描 ----------

    /**
     * 扫一遍 {@code songs/} 目录。
     *
     * <p><b>每次调用都会重建全部 SongEntry 对象</b>（选曲界面的 {@code init()} 每次都调它：
     * 打完一局返回、从设置页回来、换资源包回来都走这条路）。所以扫描完后必须把
     * <b>已经解析过的 META 兜底</b>从 {@link SongMeta} 的缓存里重新贴回来，
     * 否则画师 / 曲师 / 谱师会退回 info.txt 原值（RPE 包 = 「未知」）。
     *
     * <p>回填只看缓存（{@link SongMeta#refillAll}），<b>不会解析任何谱面 JSON</b>，
     * 列表初始化依旧是「只读 info.txt」的轻量扫描。
     */
    public static List<SongEntry> scan(Path songsDir) {
        List<SongEntry> out = new ArrayList<>();
        if (!Files.isDirectory(songsDir)) {
            try {
                Files.createDirectories(songsDir);
            } catch (IOException ignored) {
            }
            return out;
        }
        // 递归扫（最多 3 层）：谱面包可能被塞在大目录里，不是 songs/ 的直接子项
        try (var s = Files.walk(songsDir, 3)) {
            s.sorted().forEach(p -> {
                try {
                    if (p.equals(songsDir)) return;
                    String low = p.toString().toLowerCase();
                    if (Files.isRegularFile(p) && (low.endsWith(".zip") || low.endsWith(".pez"))) {
                        out.add(fromZip(p));
                    } else if (Files.isDirectory(p)
                            && (Files.exists(p.resolve("chart.json")) || Files.exists(p.resolve("info.txt")))) {
                        // 目录形态：官方 chart.json，或者 RPE 的 info.txt + <id>.json。
                        // 不要再往下钻（避免把子目录重复算成多首）
                        out.add(fromDir(p));
                    }
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger("phimc")
                            .warn("[PhiMC] 谱面包读取失败: {}", p, e);
                }
            });
        } catch (IOException ignored) {
        }
        // 重建 = 兜底字段全丢；把缓存里已有的那份贴回去（只查缓存，不解析）
        SongMeta.refillAll(out);
        return out;
    }

    /**
     * 打开谱面包（ZIP），<b>带 charset 回退</b>。
     *
     * <h2>为什么需要回退（实测）</h2>
     * <p>JDK 的 {@link ZipFile} 默认按 UTF-8 解析条目名，而且<b>校验很严</b>：
     * 只要 CEN 里有一个条目的名字不是合法 UTF-8，构造阶段就抛
     * {@code ZipException: invalid CEN header (bad entry name or comment)}，
     * 整个包一行都读不出来 —— {@link #scan(Path)} 里那句 catch 会把整个包<b>跳过</b>，
     * 于是这首谱面在选曲界面里<b>根本看不到</b>，玩家的说法就是「跑不了」。
     *
     * <p>曲库里实测有 2 个这样的包：{@code iLArtifact_IN.zip}、{@code volcanic_IN.zip}。
     * 它们的条目名里有 Shift-JIS/GBK 编码的日文（{@code 推活天使ビビアンちゃん .pez}），
     * 但没设置 ZIP 的 UTF-8 标志位（general purpose flag bit 11），所以 UTF-8 解码失败。
     * 用 {@code new ZipFile(f, "GBK")} 能正常打开并读全部条目（谱面/音频都是纯 ASCII 名，不受影响）。
     *
     * <p>回退顺序：UTF-8（默认，绝大多数包直接成功）→ GBK（中文/日文 Windows 打包最常见）
     * → Shift_JIS → ISO-8859-1（把字节 1:1 映射，永不失败，作为最后兜底）。
     * <b>只有默认路径抛异常时才会走后面几步</b>，正常包的行为一个字节都没变。
     *
     * @throws IOException 连 ISO-8859-1 都打不开（真正的坏包 / 不是 zip），抛出<b>最初</b>那个异常
     */
    static ZipFile openZip(Path p) throws IOException {
        IOException first = null;
        try {
            return new ZipFile(p.toFile());
        } catch (IOException e) {
            first = e;
        }
        for (String cs : new String[]{"GBK", "Shift_JIS", "ISO-8859-1"}) {
            try {
                java.nio.charset.Charset charset = java.nio.charset.Charset.forName(cs);
                ZipFile zf = new ZipFile(p.toFile(), charset);
                org.slf4j.LoggerFactory.getLogger("phimc").info(
                        "[PhiMC] 谱面包条目名不是合法 UTF-8，已用 {} 打开: {}", cs, p.getFileName());
                return zf;
            } catch (IOException | RuntimeException ignored) {
                // 这个 charset 也不行，试下一个
            }
        }
        throw first;
    }

    private static SongEntry fromZip(Path p) throws IOException {
        String base = p.getFileName().toString();
        if (base.toLowerCase().endsWith(".zip") || base.toLowerCase().endsWith(".pez")) {
            base = base.substring(0, base.length() - 4);
        }
        String name = base;
        String level = "-";
        Info info = null;
        try (ZipFile zf = openZip(p)) {
            info = readInfoZip(zf);
            if (info != null) {
                if (!info.name.isBlank()) name = info.name;
                if (!info.level.isBlank()) level = info.level;
            }
        }
        return build(name, level, base, p, true, info);
    }

    private static SongEntry fromDir(Path p) throws IOException {
        String name = p.getFileName().toString();
        String level = "-";
        Info parsed = null;
        Path info = p.resolve("info.txt");
        if (!Files.exists(info)) info = p.resolve("info.yml");
        if (Files.exists(info)) {
            parsed = parseInfo(new String(Files.readAllBytes(info), StandardCharsets.UTF_8));
            if (!parsed.name.isBlank()) name = parsed.name;
            if (!parsed.level.isBlank()) level = parsed.level;
        }
        return build(name, level, p.getFileName().toString(), p, false, parsed);
    }

    private static SongEntry build(String name, String level, String base, Path path, boolean zip, Info info) {
        String display = level.equals("-") || level.isEmpty() ? name : name + " · " + level;
        // 真实谱包的 info.txt 写的是 "Level: IN Lv.?" / "Level: AT Lv.17"，所以先按 token 认难度，
        // 认不出再看文件名（Verruckt_Raimukun_IN.zip），最后退回 level 原文。
        String grade = findGrade(level);
        if (grade == null) grade = findGrade(base);
        if (grade == null) grade = level.equals("-") || level.isEmpty() ? "-" : level;
        String charter = info == null ? "" : info.charter;
        String composer = info == null ? "" : info.composer;
        String illustrator = info == null ? "" : info.illustrator;
        String picture = info == null ? "" : info.picture;
        return new SongEntry(display, level, grade, path, zip, charter, composer, illustrator, picture);
    }

    // ---------- info.txt ----------

    /**
     * info.txt / info.yml 里我们用得上的字段（RPE 的 {@code Chart:} / {@code Song:} 也在里面）。
     *
     * <p>{@code picture} / {@code composer} / {@code charter} / {@code illustrator} 是选曲界面
     * 「右侧信息栏」要用的：曲绘文件名、曲师、谱师、画师。
     * <b>{@code picture} 是文件名，{@code illustrator} 是人名</b>，两者来自不同的键。
     */
    private record Info(String name, String level, String chart, String song,
                        String picture, String composer, String charter, String illustrator) {
    }

    private static Info readInfoZip(ZipFile zf) throws IOException {
        ZipEntry info = zf.getEntry("info.txt");
        if (info == null) info = zf.getEntry("info.yml");
        if (info == null) {
            // 有的包放在子目录里
            info = find(zf, "info", ".txt");
            if (info == null) info = find(zf, "info", ".yml");
        }
        if (info == null) return null;
        try (InputStream in = zf.getInputStream(info)) {
            return parseInfo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 逐行解析 {@code key: value}（info.txt 和 Phira 的 info.yml 都是这么写的）。
     * 未知键忽略；{@code Level} / {@code Difficulty} 都当难度。
     *
     * <p>曲绘文件名同时认 {@code Picture:}（官方 PGR 包 + RPE 包的实际写法）和
     * {@code Illustration:}（Phira info.yml）。<b>{@code Illustrator:} 不认</b>：那是画师名，
     * 落到 {@link Info#illustrator()} 里，绝不写进 {@code picture}（否则会拿人名去找文件）。
     */
    private static Info parseInfo(String text) {
        String name = "";
        String level = "-";
        String chart = "";
        String song = "";
        String picture = "";
        String composer = "";
        String charter = "";
        String illustrator = "";
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("#") || t.isEmpty()) continue;
            int c = t.indexOf(':');
            if (c <= 0) continue;
            String k = t.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT);
            String v = t.substring(c + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            switch (k) {
                case "name" -> name = v;
                case "level", "difficulty" -> level = v;
                case "chart" -> chart = v;
                case "song", "music" -> song = v;
                // 曲绘文件名：Picture / Illustration（image、cover 是同义兜底写法）
                case "picture", "illustration", "image", "cover" -> picture = v;
                case "composer", "songby" -> composer = v;
                case "charter", "chartby" -> charter = v;
                // 画师名（不是文件名！见类注释 / 字段注释）
                case "illustrator", "pictureby", "imageby", "illustby" -> illustrator = v;
                default -> {
                }
            }
        }
        return new Info(name, level, chart, song, picture, composer, charter, illustrator);
    }

    // ---------- 读取内容 ----------

    public String chartJson() throws IOException {
        try {
            if (!zip) {
                for (String n : new String[]{"chart.json", "Chart.json"}) {
                    Path p = path.resolve(n);
                    if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8);
                }
                Path unique = uniqueBySuffix(path, ".json");
                if (unique != null) return Files.readString(unique, StandardCharsets.UTF_8);
                throw new IOException("目录里没有谱面 JSON: " + path);
            }
            try (ZipFile zf = openZip(path)) {
                ZipEntry chart = resolveChart(zf);
                if (chart == null) throw new IOException("压缩包里没有谱面 JSON");
                try (InputStream in = zf.getInputStream(chart)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 诊断：包路径 + 异常类型/消息（JSON 大小 / 开头 200 字符由 Session 在解析失败时补）
            org.slf4j.LoggerFactory.getLogger("phimc").warn(
                    "[PhiMC] 读取谱面 JSON 失败: {} | path={} | {}: {}",
                    display, path == null ? "-" : path.toAbsolutePath(),
                    e.getClass().getName(), e.getMessage());
            throw e;
        }
    }

    /** @return null 表示没有音频 */
    public Audio payload() throws IOException {
        if (!zip) {
            for (String n : new String[]{"music.wav", "music.mp3", "music.ogg"}) {
                Path p = path.resolve(n);
                if (Files.exists(p)) return new Audio(Files.readAllBytes(p), n);
            }
            Path any = uniqueBySuffix(path, ".wav", ".mp3", ".ogg");
            if (any != null) {
                return new Audio(Files.readAllBytes(any), any.getFileName().toString());
            }
            return null;
        }
        try (ZipFile zf = openZip(path)) {
            ZipEntry music = resolveAudio(zf);
            if (music == null) return null;
            try (InputStream in = zf.getInputStream(music)) {
                return new Audio(in.readAllBytes(), music.getName());
            }
        }
    }

    // ---------- 曲绘 ----------

    /**
     * 曲绘能接受的后缀。注意<b>后缀不代表真实格式</b>：有的 RPE 包把 JPEG 命名成 {@code .png}
     * （实测 4 个 pez 里有 1 个），所以格式判定只能看魔数，见 {@code CoverCodec}。
     */
    private static final String[] IMAGE_SUFFIX = {".png", ".jpg", ".jpeg", ".bmp", ".gif"};

    /**
     * 曲绘原始字节（原样返回，<b>不做转码</b>；可能是 PNG，也可能是 JPEG），没有就返回 null。
     *
     * <p>解码 / 格式转换在 {@code CoverTextures} → {@code CoverCodec} 那一侧做
     * （{@code NativeImage.read} 只吃 PNG，JPEG 要先转，见 {@link CoverCodec}）。
     *
     * <p><b>只在需要时调用</b>：选曲界面是「切到哪首读哪首」，{@code init()} 里不批量读，
     * 结算界面只读当前这一首。调用方负责解码成贴图（见 {@code CoverTextures}）。
     *
     * <p>查找顺序照 {@link #chartJson()} 的思路：① info.txt 声明的文件名（没有就用
     * {@code META.background}，见 {@link #pictureName()}）→ ② 后缀唯一的那个 →
     * ③ 老包的名字启发式（illustration / defaultImage / cover / image）。
     */
    public byte[] cover() throws IOException {
        String declared = pictureName();
        if (!zip) {
            Path hit = dirImage(path, declared);
            return hit == null ? null : Files.readAllBytes(hit);
        }
        try (ZipFile zf = openZip(path)) {
            ZipEntry e = entryNamed(zf, declared);
            if (e == null) e = uniqueBySuffix(zf, IMAGE_SUFFIX);
            if (e == null) e = findAny(zf, IMAGE_SUFFIX, "illustration", "defaultimage", "cover", "image");
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    /** 目录形态的曲绘：声明的名字（含子目录 / 只写 basename）→ 目录内后缀唯一 → 深层找关键字。 */
    private static Path dirImage(Path dir, String declared) throws IOException {
        if (declared != null && !declared.isBlank()) {
            String want = declared.trim().replace('\\', '/');
            String wantBase = want.contains("/") ? want.substring(want.lastIndexOf('/') + 1) : want;
            Path direct = dir.resolve(want);
            if (Files.isRegularFile(direct)) return direct;
            Path byBase = dir.resolve(wantBase);
            if (Files.isRegularFile(byBase)) return byBase;
            try (var s = Files.walk(dir, 2)) {
                for (Path p : s.toList()) {
                    if (Files.isRegularFile(p) && p.getFileName().toString().equalsIgnoreCase(wantBase)) return p;
                }
            }
        }
        Path unique = uniqueBySuffix(dir, IMAGE_SUFFIX);
        if (unique != null) return unique;
        try (var s = Files.walk(dir, 2)) {
            Path best = null;
            for (Path p : s.toList()) {
                if (!Files.isRegularFile(p)) continue;
                String low = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                boolean img = false;
                for (String suf : IMAGE_SUFFIX) {
                    if (low.endsWith(suf)) {
                        img = true;
                        break;
                    }
                }
                if (!img) continue;
                if (low.contains("illustration") || low.contains("defaultimage") || low.contains("cover")) {
                    if (best == null || p.getNameCount() < best.getNameCount()) best = p;
                }
            }
            return best;
        }
    }

    /**
     * 按<b>文件名</b>取谱面包里的任意一个资源（RPE 的 {@code Texture: xxx.png} 用）。
     *
     * <p>匹配规则（宽松一点，因为不同导出工具的写法不统一）：
     * <ol>
     *   <li>先把请求名按 {@code /} 切成 basename 再比（谱面里可能写 {@code "img/heart.png"}）；</li>
     *   <li>先找「basename 完全相等（忽略大小写）」的条目；</li>
     *   <li>找不到再找「basename 去掉后缀相同」的（谱面写 {@code heart} 而包里叫 {@code heart.png}）。</li>
     * </ol>
     *
     * <p>目录形态的谱面包走同样的规则（递归 2 层）。找不到 / 读失败一律返回 {@code null}。
     */
    public byte[] asset(String name) {
        if (name == null || name.isEmpty() || path == null) return null;
        String want = name.replace('\\', '/');
        int slash = want.lastIndexOf('/');
        if (slash >= 0) want = want.substring(slash + 1);
        want = want.toLowerCase(java.util.Locale.ROOT);
        if (want.isEmpty()) return null;
        String stem = want;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        try {
            if (!zip) {
                try (var s = Files.walk(path, 2)) {
                    Path exact = null;
                    Path byStem = null;
                    for (Path p : s.toList()) {
                        if (!Files.isRegularFile(p)) continue;
                        String low = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (low.equals(want)) {
                            exact = p;
                            break;
                        }
                        if (byStem == null) {
                            int d2 = low.lastIndexOf('.');
                            if ((d2 > 0 ? low.substring(0, d2) : low).equals(stem)) byStem = p;
                        }
                    }
                    Path hit = exact != null ? exact : byStem;
                    return hit == null ? null : Files.readAllBytes(hit);
                }
            }
            try (ZipFile zf = openZip(path)) {
                ZipEntry exact = null;
                ZipEntry byStem = null;
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry z = en.nextElement();
                    if (z.isDirectory()) continue;
                    String full = z.getName().replace('\\', '/');
                    String base = full.contains("/") ? full.substring(full.lastIndexOf('/') + 1) : full;
                    String low = base.toLowerCase(java.util.Locale.ROOT);
                    if (low.equals(want)) {
                        exact = z;
                        break;
                    }
                    if (byStem == null) {
                        int d2 = low.lastIndexOf('.');
                        if ((d2 > 0 ? low.substring(0, d2) : low).equals(stem)) byStem = z;
                    }
                }
                ZipEntry hit = exact != null ? exact : byStem;
                if (hit == null) return null;
                try (InputStream in = zf.getInputStream(hit)) {
                    return in.readAllBytes();
                }
            }
        } catch (Throwable t) {
            // 贴图缺失不该让整首曲子挂掉：渲染层拿 null 就当这条线不画
            return null;
        }
    }

    /** 后缀匹配 + 文件名里含任一关键字（优先级按关键字顺序），取路径最短的那个。 */
    private static ZipEntry findAny(ZipFile zf, String[] suffixes, String... keywords) {
        for (String key : keywords) {
            ZipEntry best = null;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (z.isDirectory()) continue;
                String low = z.getName().toLowerCase(java.util.Locale.ROOT);
                String base = low.contains("/") ? low.substring(low.lastIndexOf('/') + 1) : low;
                boolean img = false;
                for (String suf : suffixes) {
                    if (base.endsWith(suf)) {
                        img = true;
                        break;
                    }
                }
                if (!img || !base.contains(key)) continue;
                if (best == null || z.getName().length() < best.getName().length()) best = z;
            }
            if (best != null) return best;
        }
        return null;
    }

    // ---------- 包内条目查找 ----------

    /** 谱面 JSON：info.txt 声明的名字 → 唯一的 .json → 老包（名字里带 chart） */
    private static ZipEntry resolveChart(ZipFile zf) throws IOException {
        ZipEntry e = entryNamed(zf, readInfoZipField(zf, true));
        if (e != null) return e;
        e = uniqueBySuffix(zf, ".json");
        if (e != null) return e;
        return find(zf, "chart", ".json");
    }

    /**
     * 音频：info.txt 声明的名字 → 唯一的 .mp3/.wav/.ogg → 老包（music.wav / music.ogg）。
     *
     * <p>RPE 包里的音频叫 {@code <随机id>.mp3}，按名字永远匹配不到，只能这么找。
     */
    private static ZipEntry resolveAudio(ZipFile zf) throws IOException {
        ZipEntry e = entryNamed(zf, readInfoZipField(zf, false));
        if (e != null) return e;
        e = uniqueBySuffix(zf, ".mp3", ".wav", ".ogg");
        if (e != null) return e;
        e = find(zf, "music", ".wav");
        if (e == null) e = find(zf, "music", ".ogg");
        if (e == null) e = find(zf, "music", ".mp3");
        return e;
    }

    /** 只为了拿 {@code Chart:} / {@code Song:} 两个字段。 */
    private static String readInfoZipField(ZipFile zf, boolean chart) throws IOException {
        Info info = readInfoZip(zf);
        if (info == null) return null;
        String v = chart ? info.chart() : info.song();
        return v == null || v.isBlank() ? null : v;
    }

    /** 按 info.txt 里声明的文件名找条目：先整路径相等，再只比 basename（大小写不敏感）。 */
    private static ZipEntry entryNamed(ZipFile zf, String name) {
        if (name == null || name.isBlank()) return null;
        String want = name.trim().replace('\\', '/');
        while (want.startsWith("./") || want.startsWith("/")) {
            want = want.substring(want.startsWith("./") ? 2 : 1);
        }
        String wantBase = want.contains("/") ? want.substring(want.lastIndexOf('/') + 1) : want;
        ZipEntry byBase = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry z = en.nextElement();
            if (z.isDirectory()) continue;
            String n = z.getName().replace('\\', '/');
            if (n.equalsIgnoreCase(want)) return z;
            String b = n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
            if (byBase == null && b.equalsIgnoreCase(wantBase)) byBase = z;
        }
        return byBase;
    }

    /**
     * 后缀匹配且<b>全包唯一</b>才返回：0 个或多个都返回 null。
     * 「唯一 .json / 唯一 .mp3」是 RPE 包的标准形态（文件名是随机 id，没法按名字找）。
     */
    private static ZipEntry uniqueBySuffix(ZipFile zf, String... suffixes) {
        ZipEntry hit = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry z = en.nextElement();
            if (z.isDirectory()) continue;
            String low = z.getName().toLowerCase(java.util.Locale.ROOT);
            boolean match = false;
            for (String s : suffixes) {
                if (low.endsWith(s)) {
                    match = true;
                    break;
                }
            }
            if (!match) continue;
            if (hit != null) return null;       // 不唯一
            hit = z;
        }
        return hit;
    }

    private static Path uniqueBySuffix(Path dir, String... suffixes) throws IOException {
        Path hit = null;
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (!Files.isRegularFile(p)) continue;
                String low = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                boolean match = false;
                for (String suf : suffixes) {
                    if (low.endsWith(suf)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                if (hit != null) return null;   // 不唯一
                hit = p;
            }
        }
        return hit;
    }

    /**
     * 老包的名字启发式：先找完全等于 {@code chart.json}/{@code music.ogg} 这类标准名，
     * 再找名字里含关键字且后缀匹配的（允许子目录），取路径最短的，避免匹配到 res/ 里的杂项。
     */
    private static ZipEntry find(ZipFile zf, String contains, String suffix) {
        ZipEntry exact = null;
        ZipEntry hit = null;
        Enumeration<? extends ZipEntry> e = zf.entries();
        while (e.hasMoreElements()) {
            ZipEntry z = e.nextElement();
            if (z.isDirectory()) continue;
            String n = z.getName();
            String low = n.toLowerCase();
            String base = low.contains("/") ? low.substring(low.lastIndexOf('/') + 1) : low;
            if (!base.endsWith(suffix)) continue;
            if (base.startsWith(contains) && exact == null) exact = z;
            if (base.contains(contains) && (hit == null || n.length() < hit.getName().length())) hit = z;
        }
        return exact != null ? exact : hit;
    }

    public record Audio(byte[] data, String name) {
    }
}
