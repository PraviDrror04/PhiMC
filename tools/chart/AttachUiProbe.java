import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code attachUI} 联动的 headless 验收。
 *
 * <p>对应 {@code prpr/src/core/chart.rs:72-96} 的 {@code with_element}：谱面给某个 UI 元素指定一条
 * 判定线之后，这个元素（分数 / 连击 / 曲名 / 难度 / 进度条 / 暂停键）就画在<b>那条线的位置</b>，
 * 并乘上它的旋转、scale、alpha、颜色。这里逐首打印「哪个元素挂到了哪条线、那条线在几个时刻
 * 落在哪、转多少度、alpha 多少」，用来验证映射与求值都对。
 *
 * <p>用法：{@code java -cp out/dev AttachUiProbe [目录]}
 */
public final class AttachUiProbe {

    private static final String[] NAMES = {"pause", "comboNumber", "combo", "score", "bar", "name", "level"};

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0]
                : "D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        List<SongEntry> songs = SongEntry.scan(dir);
        int chartsWith = 0;
        int totalAttached = 0;
        for (SongEntry e : songs) {
            String name = e.path.getFileName().toString();
            Chart c;
            try {
                c = ChartParser.parse(e.chartJson());
            } catch (Throwable t) {
                continue;
            }
            int n = 0;
            for (int slot : c.attachUiLines) if (slot >= 0) n++;
            if (n == 0) continue;
            chartsWith++;
            totalAttached += n;
            System.out.printf(Locale.ROOT, "== %s（%d 线，时长 %.1fs）%n", name, c.lines.size(), c.duration);
            // 采样时刻：取整首的中段，这样移动过的线能看到非默认位置
            double[] ts = {c.duration * 0.25, c.duration * 0.5, c.duration * 0.75};
            for (int i = 0; i < 7; i++) {
                int slot = c.attachUiLines[i];
                if (slot < 0) continue;
                Chart.Line l = c.lines.get(slot);
                StringBuilder sb = new StringBuilder();
                for (double t : ts) {
                    sb.append(String.format(Locale.ROOT, " (%.3f,%.3f,%.0f°,a%.2f)",
                            l.xAt(t), l.yAt(t), l.rotateAt(t), l.alphaAt(t)));
                }
                System.out.printf(Locale.ROOT, "   %-12s ← 线 %-4d 名称「%s」%n       位置%s%s%n",
                        NAMES[i], slot, cut(l.textureName.isEmpty() ? "line.png" : l.textureName, 20),
                        sb, l.hasColor() ? "  有 colorEvents" : "");
            }
            System.out.println();
        }
        System.out.printf(Locale.ROOT, "合计：%d 首谱带 attachUI，共 %d 个元素挂到判定线上%n",
                chartsWith, totalAttached);
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private AttachUiProbe() {
    }
}
