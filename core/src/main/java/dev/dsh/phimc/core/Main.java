package dev.dsh.phimc.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 自测入口：不依赖 Minecraft，验证谱面解析 + 事件求值 + 判定/计分。
 *
 * <pre>
 *   java -cp "out;lib/gson-2.11.0.jar" dev.dsh.phimc.core.Main fixtures/spasmodic/chart.json
 * </pre>
 */
public final class Main {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path chartPath = Path.of(args.length > 0 ? args[0] : "fixtures/spasmodic/chart.json");
        if (!Files.exists(chartPath)) {
            System.err.println("找不到谱面: " + chartPath.toAbsolutePath());
            System.exit(2);
        }

        Chart chart = ChartParser.parse(chartPath);
        System.out.println("== 谱面 ==");
        System.out.println("文件       : " + chartPath);
        System.out.println("格式       : " + chart.format);
        System.out.println("formatVer  : " + chart.formatVersion);
        System.out.println("offset     : " + chart.offset);
        System.out.println("判定线     : " + chart.lines.size());
        System.out.println("音符       : " + chart.noteCount() + "（非 fake）");
        System.out.printf("时长       : %.2f s%n", chart.duration);

        check("音符总数 > 0", chart.noteCount() > 0);
        check("时长 > 10s", chart.duration > 10);

        // 1) 事件求值：抽几个时间点，确认没有 NaN / 断崖
        System.out.println("\n== 判定线事件抽样 ==");
        Chart.Line first = chart.lines.get(0);
        double[] probes = {0, 1, 5, 20, Math.max(0, chart.duration - 1)};
        for (double t : probes) {
            double x = first.xAt(t), y = first.yAt(t), rot = first.rotateAt(t);
            double a = first.alphaAt(t), f = first.floorPositionAt(t);
            System.out.printf("t=%6.1fs  x=%6.3f y=%6.3f rot=%7.2f alpha=%5.2f floor=%8.2f%n", t, x, y, rot, a, f);
            check("事件值非 NaN (t=" + t + ")", !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(rot)
                    && !Double.isNaN(a) && !Double.isNaN(f));
        }

        // 2) 事件单调性：startTime 必须递增（否则二分查找失效）
        for (Chart.Line l : chart.lines) {
            check("line" + l.index + " speedEvents 有序", ordered(l.speed));
            check("line" + l.index + " moveX 有序", orderedLayers(l.moveX));
            check("line" + l.index + " rotate 有序", orderedLayers(l.rotate));
        }

        // 3) Autoplay：每个音符精确击中，应当拿到 1000000 / PHI / 全连
        System.out.println("\n== Autoplay 模拟 ==");
        Judgement.Config cfg = new Judgement.Config();
        Engine engine = new Engine(chart, cfg);
        engine.reset();
        List<Chart.Note> notes = chart.allNotes();
        notes.sort((a, b) -> Double.compare(a.time, b.time));
        double step = 1.0 / 240.0;
        double t = 0;
        int idx = 0;
        while (idx < notes.size()) {
            t += step;
            engine.update(t);
            while (idx < notes.size() && notes.get(idx).time <= t) {
                Chart.Note n = notes.get(idx);
                if (!n.fake && n.type != Chart.Note.Type.DRAG) {
                    double xFrac = n.x * 0.05625 + 0.5;
                    // Flick 要「划」不能「点」（judge.rs:616-618），所以走 flick() 分支
                    Engine.NoteState s = n.type == Chart.Note.Type.FLICK
                            ? engine.flick(n.time, xFrac, null, Engine.DEFAULT_ASPECT)
                            : engine.hit(n.time, xFrac);
                    if (s == null) {
                        // 同一帧可能有多个音符，补一次不校验位置的
                        s = n.type == Chart.Note.Type.FLICK
                                ? engine.flick(n.time, null, null, Engine.DEFAULT_ASPECT)
                                : engine.hit(n.time, null);
                    }
                    check("音符被判定 (t=" + String.format("%.3f", n.time) + ")", s != null);
                }
                idx++;
            }
        }
        engine.update(chart.duration + 1.0);

        int score = engine.score();
        System.out.printf("得分       : %d  (%s)%n", score, engine.rank());
        System.out.printf("判定       : Perfect %d / Good %d / Bad %d / Miss %d%n",
                engine.perfect(), engine.good(), engine.bad(), engine.miss());
        System.out.printf("连击       : max %d / total %d%n", engine.maxCombo(), engine.total());

        check("Autoplay 全 Perfect", engine.perfect() == engine.total());
        check("Autoplay 满分", score == 1000000);

        // 4) 渲染数据：随机时刻取可见音符
        System.out.println("\n== 可见音符抽样 (t=20s) ==");
        Engine e2 = new Engine(chart, cfg);
        e2.reset();
        List<Engine.Visible> vis = e2.visible(20.0);
        System.out.println("可见 " + vis.size() + " 个音符");
        for (int i = 0; i < Math.min(5, vis.size()); i++) {
            Engine.Visible v = vis.get(i);
            System.out.printf("  x=%5.3f y=%6.3f alpha=%.2f rot=%6.1f type=%s%n",
                    v.xFrac(), v.yFrac(), v.alpha(), v.rotationDeg(), v.state().note.type);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("全部检查通过 ✅");
        } else {
            System.out.println(failures + " 项检查失败 ❌");
            System.exit(1);
        }
    }

    private static boolean orderedLayers(List<List<Chart.Ev>> layers) {
        for (List<Chart.Ev> l : layers) if (!ordered(l)) return false;
        return true;
    }

    private static boolean ordered(List<Chart.Ev> evs) {
        double last = Double.NEGATIVE_INFINITY;
        for (Chart.Ev e : evs) {
            if (e.t0 < last - 1e-9) return false;
            last = e.t0;
        }
        return true;
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("  ✗ " + what);
        }
    }
}
