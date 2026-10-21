package dev.dsh.phimc.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 用真实谱面做压力自测：
 * 1) 全 Perfect 自动播放 → 必须是 1000000 / PHI
 * 2) 完全不操作 → 必须全 Miss、0 分（Drag 也不再自动 Perfect，见 Engine.update 的说明）
 * 3) 带 ±60ms 随机抖动 → 判定分布合理、分数 < 满分、连击会被打断
 */
public final class StressTest {

    public static void main(String[] args) throws Exception {
        Path chart = Path.of(args[0]);
        Chart c = ChartParser.parse(chart);
        System.out.printf("谱面：%d 音符 / %d 判定线 / %.1fs%n", c.noteCount(), c.lines.size(), c.duration);

        autoplay(c);
        allMiss(c);
        jitter(c);
    }

    private static void autoplay(Chart c) {
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        List<Chart.Note> notes = c.allNotes();
        notes.sort((a, b) -> Double.compare(a.time, b.time));
        for (Chart.Note n : notes) {
            e.autoplay(n.time);
        }
        e.update(c.duration + 2);
        System.out.printf("[自动播放] 分数=%d 评级=%s P=%d G=%d B=%d M=%d 最大连击=%d/%d%n",
                e.score(), e.rank(), e.perfect(), e.good(), e.bad(), e.miss(), e.maxCombo(), e.total());
        if (e.score() != 1000000) System.out.println("  ✗ 应该满分");
        if (e.miss() != 0) System.out.println("  ✗ 不该有 Miss");
    }

    /**
     * 完全不操作：**所有**音符都该 Miss。
     *
     * <p>行为变更（用户要求）：Drag 以前是「到点自动 Perfect」，所以这里的契约是
     * {@code Miss = total − Drag}；现在 Drag 也必须有输入（键 / 空格甩键 / 指针在范围内）
     * 才进 PreJudge（{@code Engine.update()} 里那段「有意偏离 judge.rs:742」），
     * 所以不操作时 Drag 一样 Miss，契约回到 {@code Miss = total}、{@code Perfect = 0}。
     */
    private static void allMiss(Chart c) {
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        e.update(c.duration + 2);
        int drags = 0;
        for (Chart.Note n : c.allNotes()) if (n.type == Chart.Note.Type.DRAG && !n.fake) drags++;
        System.out.printf("[不操作]   分数=%d P=%d M=%d/%d（Drag 不再自动判，%d 个 Drag 一并 Miss）%n",
                e.score(), e.perfect(), e.miss(), e.total(), drags);
        if (e.miss() != e.total()) System.out.println("  ✗ Miss 数应等于音符总数（Drag 不再自动 Perfect）");
        if (e.perfect() != 0) System.out.println("  ✗ 不操作不应该有 Perfect");
    }

    private static void jitter(Chart c) {
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        java.util.Random rnd = new java.util.Random(42);
        List<Chart.Note> notes = c.allNotes();
        notes.sort((a, b) -> Double.compare(a.time, b.time));
        for (Chart.Note n : notes) {
            double t = n.time + (rnd.nextDouble() - 0.5) * 0.12;
            e.update(t);
            e.hit(t, null);
        }
        e.update(c.duration + 2);
        System.out.printf("[±60ms]    分数=%d 评级=%s P=%d G=%d B=%d M=%d 最大连击=%d/%d%n",
                e.score(), e.rank(), e.perfect(), e.good(), e.bad(), e.miss(), e.maxCombo(), e.total());
        if (e.score() >= 1000000) System.out.println("  ✗ 有抖动不该满分");
        if (e.perfect() == e.total()) System.out.println("  ✗ 有抖动不该全 Perfect");
    }
}
