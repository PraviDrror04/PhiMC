import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 复刻 {@code PlayScreen.render()} 里那段「贴图线」遍历，逐行打印<b>实际会画出来的</b>
 * 图名 / 贴图 id 序号 / 目标矩形 / 屏幕位置 / alpha / 角度。
 *
 * <p>用途：验证游戏日志里「#1~#8 全是 1.png @(210,132)」到底是
 * <b>(a)</b> 谱面本身就是这样（多条线共用同一张图、同一位置），还是
 * <b>(b)</b> 我们的遍历取错了线 / 变换缓存没按 slot 更新。
 *
 * <p>遍历口径和 {@code PlayScreen} 完全一致：
 * {@code chart.drawOrder} → 跳过 {@code attachUi != 0} → 只看 {@code imageLine}
 * → {@code cx = xAt*W}、{@code cy = (1-yAt)*H}、{@code quadW = 贴图宽*scaleX*W*0.5}。
 *
 * <p>用法：{@code java -cp out/dev LineDrawProbe "IF = Infinity" [宽] [高]}
 */
public final class LineDrawProbe {

    public static void main(String[] args) throws Exception {
        String want = args.length > 0 ? args[0] : "IF = Infinity";
        int w = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
        int h = args.length > 2 ? Integer.parseInt(args[2]) : 1080;
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        SongEntry entry = null;
        for (SongEntry e : SongEntry.scan(dir)) {
            if (e.path != null && e.path.getFileName().toString().contains(want)) {
                entry = e;
                break;
            }
        }
        if (entry == null) {
            System.out.println("找不到 " + want);
            return;
        }
        Chart c = ChartParser.parse(entry.chartJson());
        double aspect = (double) w / h;
        System.out.printf(Locale.ROOT, "== %s ：%d 线，drawOrder %d 项，时长 %.1fs，%dx%d ==%n",
                entry.path.getFileName(), c.lines.size(), c.drawOrder.length, c.duration, w, h);

        System.out.println("--- 图名分布（全部线）---");
        java.util.TreeMap<String, Integer> hist = new java.util.TreeMap<>();
        for (Chart.Line l : c.lines) {
            if (!l.imageLine) continue;
            hist.merge(l.textureName, 1, Integer::sum);
        }
        hist.forEach((k, v) -> System.out.printf(Locale.ROOT, "   %-16s %d 条%n", k, v));

        System.out.println("--- attachUI 线（会被 drawTextureLine 直接跳过）---");
        for (int i = 0; i < c.lines.size(); i++) {
            Chart.Line l = c.lines.get(i);
            if (l.attachUi == 0) continue;
            System.out.printf(Locale.ROOT, "   线 %-4d attachUI=%d imageLine=%s 图名=%s%n",
                    i, l.attachUi, l.imageLine, l.textureName);
        }

        // 逐行复刻绘制：先找「一帧里出现的图名种类最多」的那个时刻，把那帧完整打出来 ——
        // 这正是新日志（每帧一行/每条线一行）会打印的内容。
        double bestT = 0;
        int bestDistinct = -1;
        for (double t = 0; t <= c.duration; t += 0.25) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            for (int slot : c.drawOrder) {
                Chart.Line l = c.lines.get(slot);
                if (!l.imageLine || l.attachUi != 0) continue;
                if (l.textureName.isEmpty()) continue;
                if (l.alphaAt(t) <= 0.01) continue;
                if (Math.abs(l.scaleXAt(t)) <= 1e-6 || Math.abs(l.scaleYAt(t)) <= 1e-6) continue;
                names.add(l.textureName);
            }
            if (names.size() > bestDistinct) {
                bestDistinct = names.size();
                bestT = t;
            }
        }
        System.out.printf(Locale.ROOT,
                "--- 图名种类最多的一帧：t=%.2fs，%d 种图 ---%n", bestT, bestDistinct);
        int drawn = 0;
        for (int slot : c.drawOrder) {
            Chart.Line l = c.lines.get(slot);
            if (!l.imageLine || l.attachUi != 0) continue;
            if (l.textureName.isEmpty()) continue;
            double alpha = l.alphaAt(bestT);
            if (alpha <= 0.01) continue;
            double sx = l.scaleXAt(bestT), sy = l.scaleYAt(bestT);
            if (Math.abs(sx) <= 1e-6 || Math.abs(sy) <= 1e-6) continue;
            double cx = l.xAt(bestT, aspect) * w;
            double cy = (1.0 - l.yAt(bestT, aspect)) * h;
            drawn++;
            System.out.printf(Locale.ROOT,
                    "   #%-3d 线 %-4d %-14s scale=(%.4f,%.4f) → 目标@(%d,%d) α=%.3f rot=%.1f°%n",
                    drawn, slot, l.textureName, sx, sy, Math.round(cx), Math.round(cy),
                    alpha, l.rotateAt(bestT));
        }
        System.out.printf(Locale.ROOT, "   合计 %d 条%n", drawn);
    }

    private LineDrawProbe() {
    }
}
