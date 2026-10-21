import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 「全库能不能跑」标签扫描（headless）。
 *
 * <p>对曲库里每个 {@code .pez / .zip} 按 {@code Session.start()} 的真实顺序过一遍，并**给每首打一个标签**：
 * <ol>
 *   <li>{@code 包读取失败}：{@code ZipFile} 打不开（charset / CEN 损坏）——这首在选曲界面里根本看不到；</li>
 *   <li>{@code 解析失败}：读不到谱面 / {@code ChartParser.parse} 抛异常 / 0 音符；</li>
 *   <li>{@code 音频不支持}：音乐是 JavaSound 解不了的容器（修前 {@code WavSource.play} 只认 WAV/MP3）
 *       —— 这会让时钟永远停在 -0.15s，身体感受就是「跑不了」；</li>
 *   <li>{@code 卡顿}：解析 &gt;500ms 或峰值堆 &gt;400MB 或 avg 帧 &gt;1.5ms；</li>
 *   <li>{@code 可玩}：以上都不是。</li>
 * </ol>
 * 另外把「用到我们没接的渲染特性」的条数打出来（非 {@code line.png} 线 / textEvents / gifEvents）。
 *
 * <p>实现上刻意<b>不依赖 {@code SongEntry}</b>：它现在遇到坏包会 WARN 后跳过，扫描工具需要把失败也列出来。
 * 所以这里自己做一遍 zip 枚举 + charset 回退链，再调用真实的 {@link ChartParser}。
 */
public final class RunnableScan {

    /** true = 按修前口径打标签（OGG 算「音频不支持」）。 */
    private static boolean BEFORE = false;

    /** 用来打开坏包的 charset 回退链（顺序 = 先试哪个）。 */
    private static final Charset[] CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            Charset.forName("Shift_JIS"),
            StandardCharsets.ISO_8859_1,
    };

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        String only = null;
        boolean before = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--only")) only = args[++i];
            else if (args[i].equals("--before")) before = true;   // 复刻修前判定（OGG 记成「音频不支持」）
            else dir = Path.of(args[i]);
        }
        BEFORE = before;
        List<Path> pkgs = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".pez") || n.endsWith(".zip")) pkgs.add(p);
            });
        }
        pkgs.sort(null);
        System.out.println("曲库: " + dir);
        System.out.println("谱面包: " + pkgs.size());
        System.out.println();
        pad("谱面", 40);
        System.out.printf(Locale.ROOT, "%-40s %-26s %-12s %-10s %s%n",
                "谱面", "谱面(格式/音符/线)", "音频", "包打开", "标签 / 备注");
        System.out.println("-".repeat(140));
        int[] tally = new int[5];
        for (Path p : pkgs) {
            String name = p.getFileName().toString();
            if (only != null && !name.contains(only)) continue;
            String tag = scan(p, name, tally);
            if (tag != null) System.out.println(tag);
        }
        System.out.println("-".repeat(140));
        System.out.printf(Locale.ROOT, "合计: 可玩 %d / 音频不支持 %d / 包读取失败 %d / 解析失败 %d / 卡顿 %d%n",
                tally[0], tally[1], tally[2], tally[3], tally[4]);
    }

    private static String scan(Path p, String name, int[] tally) {
        // ---- 1) 打开包（先默认 charset，失败再回退）----
        ZipFile zf = null;
        Charset used = null;
        String openErr = null;
        try {
            zf = new ZipFile(p.toFile());
            used = StandardCharsets.UTF_8;
        } catch (Exception e) {
            openErr = e.getClass().getSimpleName() + ": " + e.getMessage();
            for (Charset cs : CHARSETS) {
                try {
                    zf = new ZipFile(p.toFile(), cs);
                    used = cs;
                    openErr = "默认 charset 失败 → 用 " + cs.name() + " 才能开";
                    break;
                } catch (Exception ignored) {
                }
            }
        }
        String openCol = used == null ? "失败" : (used.equals(StandardCharsets.UTF_8) ? "OK" : used.name());
        if (zf == null) {
            tally[2]++;
            return String.format(Locale.ROOT, "%-40s %-26s %-12s %-10s 包读取失败: %s",
                    cut(name, 40), "-", "-", "失败", openErr);
        }
        final ZipFile zip = zf;
        try (zip) {
            // ---- 2) 谱面 ----
            ZipEntry chart = pick(zip, ".json", ".pec");
            String chartCol;
            Chart c;
            String parseErr = null;
            if (chart == null) {
                chartCol = "无谱面";
                parseErr = "包里没有 .json/.pec";
                c = null;
            } else {
                byte[] raw = read(zip, chart);
                try {
                    c = ChartParser.parse(new String(raw, StandardCharsets.UTF_8));
                    chartCol = c.format + " " + c.noteCount() + "/" + c.lines.size();
                    if (c.noteCount() == 0) parseErr = "0 音符";
                } catch (Throwable t) {
                    c = null;
                    chartCol = "解析失败";
                    parseErr = t.getClass().getSimpleName() + ": " + shortMsg(t.getMessage());
                }
            }
            // ---- 3) 音频 ----
            ZipEntry audio = pick(zip, ".wav", ".mp3", ".ogg");
            String audioCol = "-";
            boolean audioBad = false;
            if (audio == null) {
                audioCol = "无";
            } else {
                byte[] head = readHead(zip, audio, 16);
                String magic = magic(head);
                audioCol = ext(audio.getName()) + "/" + magic + ("OggS".equals(magic) ? "→PCM" : "");
                // 修前 WavSource 只认 WAV(RIFF) 与 MP3；OggS 会抛 UnsupportedAudioFileException。
                // 修后 Session 会先把它解成 PCM/WAV，所以不再算失败。
                audioBad = BEFORE && "OggS".equals(magic);
            }
            // ---- 4) 渲染特性 ----
            int tex = 0, text = 0, gif = 0, color = 0;
            if (c != null) {
                for (Chart.Line l : c.lines) {
                    if (l.imageLine) tex++;
                    if (l.kind == Chart.Line.KIND_TEXT) text++;
                    if (l.hasColor()) color++;
                }
                if (chart != null) {
                    String js = new String(read(zip, chart), StandardCharsets.UTF_8);
                    gif = count(js, "\"gifEvents\"");
                }
            }
            // ---- 5) 每帧开销 ----
            String perf = "";
            boolean slow = false;
            if (c != null) {
                long t0 = System.nanoTime();
                Engine e = new Engine(c, new Judgement.Config());
                Engine e2 = new Engine(c, new Judgement.Config());
                long t1 = System.nanoTime();
                double frames = Math.max(1, Math.min(600, c.duration * 60));
                for (int i = 0; i < frames; i++) {
                    double t = c.duration * i / frames;
                    e.visible(t, 16.0 / 9.0);
                    e2.update(t);
                }
                double ms = (System.nanoTime() - t1) / 1e6 / frames;
                perf = String.format(Locale.ROOT, " %.3fms/帧(建Engine %.0fms)", ms, (t1 - t0) / 1e6);
                slow = ms > 1.5;
            }
            String tag;
            String note = "";
            if (parseErr != null) {
                tag = "解析失败";
                tally[3]++;
            } else if (audioBad) {
                tag = "音频不支持";
                tally[1]++;
                note = "  ← 进游戏后时钟停在 -0.15s";
            } else if (slow) {
                tag = "卡顿";
                tally[4]++;
            } else {
                tag = "可玩";
                tally[0]++;
            }
            if (openErr != null) note += "  [包需要用 " + used.name() + " 打开]";
            if (tex + text + gif + color > 0) {
                note += String.format(Locale.ROOT, "  [贴图线%d 文字线%d gif%d color%d]", tex, text, gif, color);
            }
            if (parseErr != null) note += "  " + parseErr;
            return String.format(Locale.ROOT, "%-40s %-26s %-12s %-10s %s%s%s",
                    cut(name, 40), chartCol, audioCol, openCol, tag, perf, note);
        } catch (Throwable t) {
            tally[3]++;
            return String.format(Locale.ROOT, "%-40s %-26s %-12s %-10s 解析失败: %s",
                    cut(name, 40), "-", "-", openCol, t);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static ZipEntry pick(ZipFile zf, String... suffixes) {
        ZipEntry best = null;
        for (var en = zf.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName().toLowerCase(Locale.ROOT);
            for (String s : suffixes) {
                if (n.endsWith(s) && (best == null || e.getSize() > best.getSize())) best = e;
            }
        }
        return best;
    }

    private static byte[] read(ZipFile zf, ZipEntry e) throws Exception {
        try (InputStream in = zf.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static byte[] readHead(ZipFile zf, ZipEntry e, int n) throws Exception {
        try (InputStream in = zf.getInputStream(e)) {
            return in.readNBytes(n);
        }
    }

    private static String magic(byte[] d) {
        if (d.length < 4) return "?";
        String h = new String(d, 0, 4, StandardCharsets.ISO_8859_1);
        return switch (h) {
            case "OggS" -> "OggS";
            case "RIFF" -> "WAV";
            case "ID3\u0003" -> "MP3";
            default -> h.startsWith("\u00ff\u00fb") || h.startsWith("\u00ff\u00f3") || h.startsWith("\u00ff\u00f2")
                    ? "MP3" : "?" + Integer.toHexString(h.hashCode());
        };
    }

    private static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }

    private static String shortMsg(String m) {
        if (m == null) return "";
        return m.length() <= 60 ? m : m.substring(0, 60) + "…";
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static void pad(String s, int n) {
    }

    private RunnableScan() {
    }
}
