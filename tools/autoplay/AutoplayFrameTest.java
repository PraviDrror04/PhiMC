import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;

import java.nio.file.Path;

/**
 * 「自动游玩」按<b>帧</b>推进的自测（对应 {@code Session.tick()} 的 autoplay 分支）。
 *
 * <p>和 {@code StressTest} 的区别很重要：{@code StressTest} 是「每个音符调一次
 * {@code autoplay(note.time)}」，而游戏里是<b>每 tick 调一次</b>
 * {@code engine.autoplay(seconds())}：一帧可能跨过好几个音符、也可能一帧一个都没有。
 * 这个程序就照游戏那样按帧步进，验证三件事：
 * <ol>
 *   <li>按帧自动游玩仍然是满分 / 0 Miss / 全连；</li>
 *   <li>与帧率无关（20 / 60 / 144 / 240 fps 结果一致）；</li>
 *   <li>「关掉自动游玩」之后引擎状态不残留：再手动 {@code update(t)} 不会重复判定、
 *       不会突然冒出 Miss（{@code judgedCount} 已经等于 {@code total}）。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp "out;gson" AutoplayFrameTest <chart.json> [chart2.json ...]}
 */
public final class AutoplayFrameTest {

    private static final double[] FPS = {20, 60, 144, 240};

    public static void main(String[] args) throws Exception {
        int bad = 0;
        for (String arg : args) {
            Chart chart = ChartParser.parse(Path.of(arg));
            System.out.printf("%n=== %s ===%n", Path.of(arg).getFileName());
            System.out.printf("谱面：%d 音符 / %d 判定线 / %.1fs%n", chart.noteCount(), chart.lines.size(), chart.duration);
            for (double fps : FPS) {
                if (!runFrameAutoplay(chart, fps)) bad++;
            }
            if (!runSwitchOff(chart)) bad++;
        }
        System.out.println();
        if (bad == 0) {
            System.out.println("结论: ✔ 全部通过（按帧自动游玩 = 满分 0 Miss；关掉后无残留）");
        } else {
            System.out.println("结论: ✗ " + bad + " 项不达标");
            System.exit(1);
        }
    }

    /** 每帧一次 {@code engine.autoplay(t)}（= {@code Session.tick()} 的 autoplay 分支）。 */
    private static boolean runFrameAutoplay(Chart chart, double fps) {
        Engine e = new Engine(chart, new Judgement.Config());
        e.reset();
        double dt = 1.0 / fps;
        double end = chart.duration + 2.0;
        int frames = 0;
        for (double t = 0; t <= end; t += dt) {
            e.autoplay(t);                    // 内部会再调 update(t)
            e.drainJudgements();              // 表现层每帧取走判定（这里只是照做）
            frames++;
        }
        e.autoplay(end);                      // 收尾（等价于最后一帧）
        boolean ok = e.score() == 1000000 && e.miss() == 0 && e.perfect() == e.total();
        System.out.printf("  %3.0f fps（%d 帧） 分数=%d 评级=%s P=%d G=%d B=%d M=%d 连击=%d/%d  %s%n",
                fps, frames, e.score(), e.rank(), e.perfect(), e.good(), e.bad(), e.miss(),
                e.maxCombo(), e.total(), ok ? "✔" : "✗");
        return ok;
    }

    /**
     * 「勾掉自动游玩」之后：引擎继续按手动通路 {@code update(t)} 推进，
     * 必须<b>不重判、不补 Miss</b>（judgedCount 已经是 total）。
     */
    private static boolean runSwitchOff(Chart chart) {
        Engine e = new Engine(chart, new Judgement.Config());
        e.reset();
        double end = chart.duration + 2.0;
        for (double t = 0; t <= end; t += 1.0 / 60.0) e.autoplay(t);
        e.drainJudgements();
        int scoreBefore = e.score();
        int judgedBefore = e.judgedCount();
        int missBefore = e.miss();
        // 关掉自动游玩后继续跑 2 秒手动通路
        for (double t = end; t <= end + 2.0; t += 1.0 / 60.0) {
            e.update(t);
            e.drainJudgements();
        }
        boolean ok = e.judgedCount() == judgedBefore && e.miss() == missBefore && e.score() == scoreBefore
                && e.judgedCount() == e.total();
        System.out.printf("  关掉自动游玩后 2s：判定数 %d → %d，Miss %d → %d，分数 %d → %d  %s%n",
                judgedBefore, e.judgedCount(), missBefore, e.miss(), scoreBefore, e.score(), ok ? "✔" : "✗");
        return ok;
    }
}
