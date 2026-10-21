package dev.dsh.phimc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 旋转判定线用例表：验证 {@link Engine#hit} 的坐标变换 / 容差与 prpr 一致。
 *
 * <p>构造：一条判定线在屏幕正中，{@code judgeLineRotateEvents} 恒为 θ；一个 TAP 音符在
 * {@code t=10.0}，其判定线本地 x = +0.3 世界单位（judge.rs:583 比较的就是本地 x）。
 * 然后按「沿判定线方向」和「垂直于判定线方向」两种偏移造点击点，逐条比对。
 *
 * <p>源码依据：
 * <ul>
 *   <li>judge.rs:473-474 + 533 点击点 → 世界坐标（{@code x/w*2-1, y/h*2-1} 再取 {@code -y}）；</li>
 *   <li>judge.rs:526-534 用判定线变换的逆搬进本地系；</li>
 *   <li>judge.rs:581-586 只比本地 x：{@code |note_x - touch_x| / judge_area <= X_DIFF_MAX = 0.23625}；</li>
 *   <li>judge.rs:587-595 时间窗：Click 用 {@code LIMIT_BAD - LIMIT_PERFECT*max(dist-0.9,0)}；</li>
 *   <li>judge.rs:580 早打 {@code EARLY_OFFSET = 0.07} 折回。</li>
 * </ul>
 * 另一个 {@code legacy} 列是「修前」{@code Engine.hit} 的判定条件（旧 Engine.java:238-241）：
 * 除了同样的本地 x 容差，还额外要求 {@code |Δ本地y| <= Y_DIFF_MAX = X_DIFF_MAX*100/998 = 0.0237}。
 */
public final class RotTest {

    private static final double ASPECT = 16.0 / 9.0;
    private static final double NOTE_TIME = 10.0;
    /** 判定线本地 x = 0.3 世界单位 → 谱面 x = 0.3/0.1125。 */
    private static final double LOCAL_X = 0.3;

    /** 修前 Engine 的纵向容差（旧 Engine.java:59-70）。 */
    private static final double LEGACY_Y_DIFF_MAX = Engine.X_DIFF_MAX * (100.0 / 998.0);

    private record Case(String name, double alongOffset, double perpOffset, double dt) {
    }

    public static void main(String[] args) {
        List<Case> cases = List.of(
                new Case("点中音符本体", 0.0, 0.0, 0.0),
                new Case("沿线上偏 0.20 世界单位", 0.20, 0.0, 0.0),
                new Case("沿线上偏 0.50（超 X_DIFF_MAX）", 0.50, 0.0, 0.0),
                new Case("垂直偏 0.10", 0.0, 0.10, 0.0),
                new Case("垂直偏 0.50", 0.0, 0.50, 0.0),
                new Case("垂直偏 2.00", 0.0, 2.00, 0.0),
                new Case("晚 60ms（EARLY_OFFSET 折回）", 0.0, 0.0, 0.06),
                new Case("晚 100ms", 0.0, 0.0, 0.10),
                new Case("晚 190ms", 0.0, 0.0, 0.19),
                new Case("晚 250ms", 0.0, 0.0, 0.25),
                new Case("早 100ms", 0.0, 0.0, -0.10),
                new Case("早 190ms", 0.0, 0.0, -0.19),
                new Case("早 250ms", 0.0, 0.0, -0.25)
        );
        System.out.println("=== 旋转判定线用例表（本地 x = +0.3 世界单位，X_DIFF_MAX = "
                + String.format(Locale.ROOT, "%.5f", Engine.X_DIFF_MAX) + "）===");
        for (double theta : new double[]{0.0, 45.0, 90.0}) {
            System.out.printf(Locale.ROOT, "-- θ = %.0f° --%n", theta);
            System.out.println("  用例                              期望      现在(judge.rs)  修前(旧 Engine.hit)");
            for (Case c : cases) {
                Chart chart = build(theta);
                Engine e = new Engine(chart, new Judgement.Config());
                e.reset();
                @SuppressWarnings("unchecked")
                Engine.NoteState s = (Engine.NoteState) firstState(e);
                double[] click = clickPoint(s, theta, c.alongOffset, c.perpOffset);
                double t = NOTE_TIME + c.dt;
                Engine.NoteState got = e.hit(t, click[0], click[1], ASPECT);
                String now = got == null ? "未命中" : got.grade.name();

                Engine e2 = new Engine(build(theta), new Judgement.Config());
                e2.reset();
                @SuppressWarnings("unchecked")
                Engine.NoteState s2 = (Engine.NoteState) firstState(e2);
                String legacy = legacyHit(e2, s2, t, click[0], click[1], theta);
                System.out.printf(Locale.ROOT, "  %-32s %-8s %-14s %s%n", c.name, expect(c), now, legacy);
            }
        }
    }

    /**
     * 期望值照 judge.rs:576-595 推：
     * {@code dt = (note.time - t)/spd}（注意是「音符时间 - 点击时间」，所以<b>晚打</b>时 dt 为负），
     * 负的 dt 走 {@code (dt + EARLY_OFFSET).min(0).abs()}——也就是说 {@code EARLY_OFFSET = 0.07}
     * 实际宽恕的是「晚打」70ms，早打不打折。
     */
    private static String expect(Case c) {
        if (Math.abs(c.alongOffset) > Engine.X_DIFF_MAX) return "不命中";
        double dt = -c.dt;                                  // t - note.time 取反 = Phira 的 dt
        double eff = dt < 0 ? Math.abs(Math.min(dt + Engine.EARLY_OFFSET, 0.0)) : dt;
        if (eff > Engine.LIMIT_BAD) return "不命中";
        if (eff <= Engine.LIMIT_PERFECT) return "PERFECT";
        if (eff <= Engine.LIMIT_GOOD) return "GOOD";
        return "BAD";
    }

    /** 判定线自身坐标系里的偏移 → 屏幕比例点击点。 */
    private static double[] clickPoint(Engine.NoteState s, double theta, double along, double perp) {
        double[] loc = Engine.noteLocal(s, NOTE_TIME, ASPECT);
        double th = Math.toRadians(theta);
        double c = Math.cos(th), sn = Math.sin(th);
        // 本地 (x,y) → 世界
        double wx = (LOCAL_X + along) * c - (loc[1] + perp) * sn;
        double wy = (LOCAL_X + along) * sn + (loc[1] + perp) * c;
        return new double[]{(wx + 1.0) / 2.0, 1.0 - (wy * ASPECT + 1.0) / 2.0};
    }

    /** 旧 Engine.hit 的判定条件（旧 Engine.java:226-246 的等价转写，含 Y_DIFF_MAX 那条）。 */
    private static String legacyHit(Engine e, Engine.NoteState s, double t, double xFrac, double yFrac, double theta) {
        double bad = 0.22;
        double err = t - s.note.time;
        if (err > bad || err < -bad) return "未命中";
        double clickWx = xFrac * 2.0 - 1.0;
        double clickWy = -(yFrac * 2.0 - 1.0) / ASPECT;
        double th = Math.toRadians(theta);
        double c = Math.cos(th), sn = Math.sin(th);
        double dx = clickWx - (s.line.xAt(t) * 2.0 - 1.0);
        double dy = clickWy - (s.line.yAt(t) * 2.0 - 1.0) / ASPECT;
        double lx = dx * c + dy * sn;
        double ly = -dx * sn + dy * c;
        double[] np = Engine.noteLocal(s, t, ASPECT);
        if (Math.abs(np[0] - lx) > Engine.X_DIFF_MAX) return "未命中";
        if (Math.abs(np[1] - ly) > LEGACY_Y_DIFF_MAX) return "未命中";
        return Judgement.gradeFor(err, new Judgement.Config()).name();
    }

    /** 一条线 + 一个 TAP 音符：线在屏幕正中、高度恒 0、旋转恒 θ。 */
    private static Chart build(double theta) {
        List<Chart.Ev> speed = new ArrayList<>();
        speed.add(new Chart.Ev(0, 1e6, 0, 0, 0, 0, 0));           // 速度 0 → 高度恒 0
        List<Chart.Ev> moveX = new ArrayList<>();
        moveX.add(new Chart.Ev(0, 1e6, 0.5, 0.5, 0.5, 0.5, 0));
        List<Chart.Ev> moveY = new ArrayList<>();
        moveY.add(new Chart.Ev(0, 1e6, 0.5, 0.5, 0.5, 0.5, 0));
        List<Chart.Ev> rot = new ArrayList<>();
        rot.add(new Chart.Ev(0, 1e6, theta, theta, 0, 0, 0));
        List<Chart.Ev> alpha = new ArrayList<>();
        List<Chart.Note> notes = new ArrayList<>();
        notes.add(new Chart.Note(Chart.Note.Type.TAP, NOTE_TIME, 0, LOCAL_X / Engine.X_WORLD_PER_UNIT,
                1.0, 0.0, true, false));
        Chart.Line line = new Chart.Line(0, 120.0, notes, speed, List.of(moveX), List.of(moveY), List.of(rot), List.of(alpha));
        List<Chart.Line> lines = new ArrayList<>();
        lines.add(line);
        return new Chart(3, 0.0, lines);
    }

    private static Object firstState(Engine e) {
        try {
            java.lang.reflect.Field f = Engine.class.getDeclaredField("states");
            f.setAccessible(true);
            List<?> states = (List<?>) f.get(e);
            return states.get(0);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
