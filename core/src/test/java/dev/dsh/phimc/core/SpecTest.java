package dev.dsh.phimc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 源码规格自检：把「必须成立、且能一眼对照 prpr 行号」的几条不变量写成断言。
 *
 * <pre>java -cp "out;lib/gson-2.11.0.jar" dev.dsh.phimc.core.SpecTest</pre>
 */
public final class SpecTest {

    private static int fail = 0;

    public static void main(String[] args) {
        fakeNote();
        fadeout();
        judgedHoldStillDrawn();
        heightInvariant();
        speedPassthrough();
        System.out.println(fail == 0 ? "全部规格检查通过 ✅" : fail + " 项失败 ❌");
        if (fail != 0) System.exit(1);
    }

    /** fake 音符：要渲染（note.rs:237 只让它「到点即隐」），但不参与判定（judge.rs:307）。 */
    private static void fakeNote() {
        Chart c = single(CHART_FAKE);
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        List<Engine.Visible> before = e.visible(9.9);
        List<Engine.Visible> after = e.visible(10.0);
        check("fake 音符判定前可见（note.rs:237）", before.size() == 1);
        check("fake 音符到点即隐（note.rs:237）", after.isEmpty());
        e.update(20.0);
        check("fake 音符不进 total（judge.rs:319 只数非 fake）", e.total() == 0);
        check("fake 音符不被判 Miss", e.miss() == 0);
    }

    /** 非 hold 音符过了 note.time + FADEOUT_TIME 就不再画（note.rs:10/236-241）。 */
    private static void fadeout() {
        Chart c = single(CHART_TAP);
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        check("过了判定时间还在淡出窗口内可见", !e.visible(10.15).isEmpty());
        // 让 update 不要把音符判走：直接跳过 update，只看 visible 的时间规则
        Engine e2 = new Engine(single(CHART_TAP), new Judgement.Config());
        e2.reset();
        check("过了 FADEOUT_TIME(0.16s) 不再绘制（note.rs:236-237）", e2.visible(10.17).isEmpty());
    }

    /** 已判定的 hold 仍然要画身体（note.rs:195-197 只拦非 hold；note.rs:277-280 半透明）。 */
    private static void judgedHoldStillDrawn() {
        Chart c = single(CHART_HOLD);
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        e.hit(10.0, null);
        e.hit(10.0, null);
        List<Engine.Visible> v = e.visible(10.5);
        check("按住的 hold 继续绘制", !v.isEmpty());
        boolean halfAlpha = v.stream().anyMatch(it -> Math.abs(it.alpha() - 1.0) < 1e-6);
        check("按住的 hold 透明度仍是 1（judge.rs:628 的 Hold 状态不算 Judged）", halfAlpha);
    }

    /** 音符在它自己的判定时刻必须落在判定线上（height 与 line 同源，pgr.rs:213-217）。 */
    private static void heightInvariant() {
        Chart c = single(CHART_WEIRD_SPEED);
        Engine e = new Engine(c, new Judgement.Config());
        e.reset();
        @SuppressWarnings("unchecked")
        List<Engine.NoteState> states = (List<Engine.NoteState>) states(e);
        double max = 0;
        for (Engine.NoteState s : states) {
            double[] loc = Engine.noteLocal(s, s.note.time, 16.0 / 9.0);
            max = Math.max(max, Math.abs(loc[1]));
        }
        check("含 999 / 负速度的谱面里，音符在判定时刻仍落在判定线上", max < 1e-6);
    }

    /** 速度事件值必须原样进入高度积分（pgr.rs:96-100），不能被「哨兵值兜底」改写。 */
    private static void speedPassthrough() {
        Chart c = single(CHART_WEIRD_SPEED);
        Chart.Line l = c.lines.get(0);
        double h1 = l.floorPositionAt(1.0);      // [0,1) 速度 100，持续 1s → 100/0.83175
        double expect = 1.0 * 100.0 / Chart.HEIGHT_RATIO;
        check(String.format(Locale.ROOT, "速度 100 原样积分（实得 %.3f，期望 %.3f）", h1, expect),
                Math.abs(h1 - expect) < 1e-6);
        double h2 = l.floorPositionAt(2.0);      // [1.0,2.0) 速度 -50 → 再减 50/0.83175
        check("负速度照常积分（pgr.rs:98 不做符号处理）", h2 < h1);
    }

    // ---- 构造小谱面 ----

    private static final int CHART_FAKE = 0;
    private static final int CHART_TAP = 1;
    private static final int CHART_HOLD = 2;
    private static final int CHART_WEIRD_SPEED = 3;

    private static Chart single(int kind) {
        List<Chart.Ev> speed = new ArrayList<>();
        if (kind == CHART_WEIRD_SPEED) {
            speed.add(new Chart.Ev(0.0, 1.0, 100.0, 100.0, 0, 0, 0));
            speed.add(new Chart.Ev(1.0, 2.0, -50.0, -50.0, 0, 0, 0));
            speed.add(new Chart.Ev(5.0, 5.01, 999.0, 999.0, 0, 0, 0));
            speed.add(new Chart.Ev(9.0, 20.0, 2.0, 2.0, 0, 0, 0));
        } else {
            speed.add(new Chart.Ev(0.0, 1e6, 1.0, 1.0, 0, 0, 0));
        }
        List<Chart.Ev> moveX = new ArrayList<>();
        moveX.add(new Chart.Ev(0, 1e6, 0.5, 0.5, 0, 0, 0));
        List<Chart.Ev> moveY = new ArrayList<>();
        moveY.add(new Chart.Ev(0, 1e6, 0.5, 0.5, 0, 0, 0));
        List<Chart.Note> notes = new ArrayList<>();
        double time = 10.0;
        double height = Chart.heightAt(speed, time);
        switch (kind) {
            case CHART_FAKE -> notes.add(new Chart.Note(Chart.Note.Type.TAP, time, 0, 0, 1, height, true, true));
            case CHART_HOLD -> notes.add(new Chart.Note(Chart.Note.Type.HOLD, time, 1.0, 0, 1, height, true, false));
            default -> notes.add(new Chart.Note(Chart.Note.Type.TAP, time, 0, 0, 1, height, true, false));
        }
        Chart.Line line = new Chart.Line(0, 120, notes, speed, List.of(moveX), List.of(moveY), List.of(), List.of());
        List<Chart.Line> lines = new ArrayList<>();
        lines.add(line);
        return new Chart(3, 0, lines);
    }

    private static List<?> states(Engine e) {
        try {
            java.lang.reflect.Field f = Engine.class.getDeclaredField("states");
            f.setAccessible(true);
            return (List<?>) f.get(e);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ✔ " : "  ✘ ") + what);
        if (!ok) fail++;
    }
}
