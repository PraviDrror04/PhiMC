import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.PecParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「谱面解析失败清单」：headless 扫一个或多个目录，把里面的 {@code .pez / .zip / 目录形态}
 * 逐个用<b>真代码</b>（{@link SongEntry#chartJson()} + {@link ChartParser#parse(String)}）
 * 跑一遍，打印每首「成功 / 失败 + 原因」。
 *
 * <p>用途：用户看到界面上「谱面解析失败」时，跑这个就能一次看到整个曲库哪些不能玩、
 * 各自死在哪个字段上（不用一首首点进游戏试）。
 *
 * <p>失败时打印的信息和运行时日志一致：<b>谱面包绝对路径、谱面 JSON 大小、异常类型 + 消息、
 * JSON 开头 200 字符</b>。
 *
 * <p>用法：
 * <pre>
 * java -cp "&lt;out&gt;;gson.jar;jlayer.jar;slf4j-api.jar" ChartScan &lt;目录&gt; [目录2 …]
 * </pre>
 * 不带参数时扫 {@code C:\dsh-work\payload}。
 */
public final class ChartScan {

    public static void main(String[] args) {
        List<Path> dirs = new ArrayList<>();
        if (args.length == 0) {
            dirs.add(Path.of("C:\\dsh-work\\payload"));
        } else {
            for (String a : args) dirs.add(Path.of(a));
        }
        int total = 0;
        int ok = 0;
        List<String> failures = new ArrayList<>();
        for (Path dir : dirs) {
            System.out.println("== 扫描 " + dir + " ==");
            List<SongEntry> songs = SongEntry.scan(dir);
            if (songs.isEmpty()) {
                System.out.println("  （没有可识别的谱面包）");
            }
            for (SongEntry e : songs) {
                total++;
                String name = e.path.getFileName().toString();
                long t0 = System.nanoTime();
                try {
                    String json = e.chartJson();
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Chart c = ChartParser.parse(json);
                    double dur = c.duration;
                    ok++;
                    int skipped = ChartParser.lastSkippedData() + PecParser.lastSkippedLines();
                    System.out.printf(Locale.ROOT,
                            "  ✓ %-46s %-22s [%s] 音符 %5d / 判定线 %3d / %7.1fs / JSON %7.2f MB / %5dms%s%n",
                            cut(name, 46), e.display, c.format, c.noteCount(), c.lines.size(), dur,
                            json.length() / 1048576.0, ms,
                            skipped > 0 ? "（跳过坏数据 " + skipped + " 条）" : "");
                } catch (Throwable t) {
                    String jsonSize = "";
                    String head = "";
                    try {
                        String j = e.chartJson();
                        jsonSize = String.format(Locale.ROOT, "%.2f MB", j.length() / 1048576.0);
                        head = j.substring(0, Math.min(200, j.length())).replaceAll("\\s+", " ");
                    } catch (Throwable t2) {
                        jsonSize = "读不出来：" + t2.getClass().getSimpleName() + ": " + t2.getMessage();
                    }
                    String reason = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage());
                    failures.add(name + " → " + reason);
                    System.out.printf(Locale.ROOT, "  ✗ %-46s %-22s%n", cut(name, 46), e.display);
                    System.out.println("      路径   : " + e.path.toAbsolutePath());
                    System.out.println("      JSON   : " + jsonSize);
                    System.out.println("      异常   : " + reason);
                    System.out.println("      JSON 头 : " + head);
                }
            }
        }
        System.out.println();
        System.out.printf("合计 %d 首：成功 %d，失败 %d%n", total, ok, total - ok);
        if (!failures.isEmpty()) {
            System.out.println("失败清单：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, Math.max(0, n - 1)) + "…";
    }
}
