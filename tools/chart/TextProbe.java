import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文字判定线（{@code extended.textEvents}）的 headless 验收：每首「有多少条 text 线、内容是什么、落在哪」。
 *
 * <p>对应 {@code prpr/src/core/line.rs:291-298} 的 {@code JudgeLineKind::Text}：
 * 文字画在<b>判定线原点</b>、锚点居中、字号固定 {@code size(1.)}、颜色取判定线颜色、
 * alpha 取判定线 alpha。所以这里的「位置」就是判定线在那一刻的屏幕位置 + 角度。
 *
 * <p>用法：{@code java -cp out/dev TextProbe [目录] [--only 子串] [--events]}
 */
public final class TextProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        String only = null;
        boolean events = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--only")) only = args[++i];
            else if (args[i].equals("--events")) events = true;
            else dir = Path.of(args[i]);
        }
        List<SongEntry> songs = SongEntry.scan(dir);
        int totalLines = 0;
        int totalCharts = 0;
        for (SongEntry e : songs) {
            String name = e.path.getFileName().toString();
            if (only != null && !name.contains(only)) continue;
            Chart c;
            try {
                c = ChartParser.parse(e.chartJson());
            } catch (Throwable t) {
                System.out.printf(Locale.ROOT, "%-38s 解析失败: %s%n", cut(name, 38), t);
                continue;
            }
            List<Chart.Line> withText = new ArrayList<>();
            for (Chart.Line l : c.lines) {
                if (!l.text.isEmpty()) withText.add(l);
            }
            if (withText.isEmpty()) continue;
            totalCharts++;
            totalLines += withText.size();
            System.out.printf(Locale.ROOT, "== %s（%s，%d 音符 / %d 线，时长 %.1fs）%n",
                    name, c.format, c.noteCount(), c.lines.size(), c.duration);
            System.out.printf(Locale.ROOT, "   文字线 %d 条 / 共 %d 条判定线%n", withText.size(), c.lines.size());
            System.out.printf(Locale.ROOT, "   %-6s %-6s %-7s %-22s %-9s %-9s %-7s %s%n",
                    "线号", "事件数", "kind", "时间区间(s)", "x(0..1)", "y(0..1)", "角度°", "内容");
            for (Chart.Line l : withText) {
                double t0 = l.text.get(0).t0;
                double t1 = l.text.get(l.text.size() - 1).t1;
                if (!Double.isFinite(t1) || t1 > 1e8) t1 = l.text.get(l.text.size() - 1).t0;
                double mid = t0 + Math.min(1.0, Math.max(0.0, (t1 - t0) / 2));
                String s = l.textAt(mid);
                // 位置就是判定线原点（line.rs:295-296 的 pos(0,0)）
                double x = l.xAt(mid);
                double y = l.yAt(mid);
                double rot = l.rotateAt(mid);
                System.out.printf(Locale.ROOT, "   %-6d %-6d %-7s %-22s %-9.4f %-9.4f %-7.2f %s%n",
                        l.index, l.text.size(), kindName(l.kind),
                        String.format(Locale.ROOT, "%.2f ~ %.2f", t0, Math.min(t1, c.duration)),
                        x, y, rot, quote(s));
                if (events) {
                    for (Chart.TextEv ev : l.text) {
                        System.out.printf(Locale.ROOT,
                                "          [%7.2f → %7.2f] %s  →  %s%n",
                                ev.t0, ev.t1, quote(ev.v0), quote(ev.v1));
                    }
                }
            }
            System.out.println();
        }
        System.out.printf(Locale.ROOT, "合计：%d 首谱带文字，共 %d 条文字线%n", totalCharts, totalLines);
    }

    private static String kindName(int kind) {
        return switch (kind) {
            case Chart.Line.KIND_TEXT -> "TEXT";
            case Chart.Line.KIND_PAINT -> "PAINT";
            case Chart.Line.KIND_TEXTURE -> "TEX";
            default -> "NORMAL";
        };
    }

    private static String quote(String s) {
        if (s == null) return "null";
        String one = s.replace("\n", "\\n");
        return "「" + cut(one, 60) + "」";
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private TextProbe() {
    }
}
