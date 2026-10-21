import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 贴图判定线的 headless 验证（对应 {@code prpr/src/core/line.rs:254-290}）。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>名字能不能对上</b>：谱面里 {@code Texture: xxx.png} 的每个名字，
 *       用 {@link SongEntry#asset(String)} 在包里找一遍，统计命中率。
 *       对不上就等于「这条线还是画不出来」，必须量化。</li>
 *   <li><b>scale 口径对不对</b>：{@code rpe.rs:695-696} 规定非 {@code line.png} 的 scale
 *       默认值是 {@code factor = 2/RPE_WIDTH}（有事件时是 {@code 事件值 × factor}）。
 *       这里把「我们算的 {@code scaleXAt}」和「按 rpe.rs 手算」逐条对比，
 *       再把最终像素尺寸算出来（{@code px = 图片像素 × scale × 屏宽/2}），
 *       看是不是落在「装饰物」该有的量级。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp out/dev RunnableTex <pez…>}
 */
public final class RunnableTex {

    private static final double RPE_WIDTH = 1350.0;
    private static final int W = 1920;
    private static final int H = 1080;

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        if (args.length == 0) {
            files.add(Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs/78805_enchanted love.pez"));
            files.add(Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs/48273_Undertale.pez"));
        } else {
            for (String a : args) files.add(Path.of(a));
        }
        for (Path f : files) probe(f);
    }

    private static void probe(Path file) throws Exception {
        List<SongEntry> songs = SongEntry.scan(file.getParent());
        SongEntry entry = null;
        for (SongEntry e : songs) {
            if (e.path != null && e.path.getFileName().toString().equals(file.getFileName().toString())) {
                entry = e;
                break;
            }
        }
        System.out.printf(Locale.ROOT, "== %s ==%n", file.getFileName());
        if (entry == null) {
            System.out.println("   SongEntry.scan 没认出这个包");
            return;
        }
        Chart chart = ChartParser.parse(entry.chartJson());

        Map<String, Integer> names = new LinkedHashMap<>();
        int imageLines = 0;
        int withEvents = 0;
        double maxErr = 0;
        for (Chart.Line l : chart.lines) {
            if (!l.imageLine) continue;
            imageLines++;
            if (!l.scaleX.isEmpty()) withEvents++;
            names.merge(l.textureName, 1, Integer::sum);
        }
        int found = 0;
        int missing = 0;
        List<String> missList = new ArrayList<>();
        long bytes = 0;
        for (String n : names.keySet()) {
            byte[] b = entry.asset(n);
            if (b == null || b.length == 0) {
                missing++;
                if (missList.size() < 10) missList.add(n);
            } else {
                found++;
                bytes += b.length;
            }
        }
        System.out.printf(Locale.ROOT,
                "   贴图线 %d 条（带 scaleXEvents 的 %d 条），不同图名 %d 个%n",
                imageLines, withEvents, names.size());
        System.out.printf(Locale.ROOT,
                "   SongEntry.asset() 命中 %d / %d 个图名，共 %d 字节（缺 %d）%s%n",
                found, names.size(), bytes, missing,
                missing == 0 ? "" : "  缺: " + String.join(", ", missList));

        // scale 口径：按 rpe.rs:695-696 手算，和 Chart.Line.scaleXAt 比
        System.out.println("   前 8 条贴图线的尺寸（图片像素 × scale × 屏宽/2；scale 应为 事件值×2/1350 或默认 2/1350）:");
        System.out.printf(Locale.ROOT, "      %-34s %-6s %-12s %-12s %-16s%n",
                "Texture", "事件", "scaleX@0", "手算默认", "画面尺寸(px @1920)");
        int shown = 0;
        for (Chart.Line l : chart.lines) {
            if (!l.imageLine || shown >= 8) continue;
            byte[] b = entry.asset(l.textureName);
            int iw = 64;
            int ih = 64;
            if (b != null) {
                int[] wh = pngSize(b);
                if (wh != null) {
                    iw = wh[0];
                    ih = wh[1];
                }
            }
            double sx = l.scaleXAt(0.0);
            double expect = 2.0 / RPE_WIDTH;
            maxErr = Math.max(maxErr, l.scaleX.isEmpty() ? Math.abs(sx - expect) : 0);
            double px = iw * sx * W / 2.0;
            double py = ih * l.scaleYAt(0.0) * H / 2.0;
            System.out.printf(Locale.ROOT, "      %-34s %-6s %-12.6f %-12.6f %.0f × %.0f%n",
                    cut(l.textureName, 34), l.scaleX.isEmpty() ? "-" : "有", sx, expect, px, py);
            shown++;
        }
        System.out.printf(Locale.ROOT, "   默认 scale 误差（无事件的线，应为 0）：%.3e%n%n", maxErr);
    }

    /** 从 PNG 头里读宽高（IHDR 在固定偏移）；不是 PNG 返回 null。 */
    private static int[] pngSize(byte[] b) {
        if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
        int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
        return new int[]{w, h};
    }

    private static String cut(String s, int n) {
        if (s == null) return "-";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private RunnableTex() {
    }
}
