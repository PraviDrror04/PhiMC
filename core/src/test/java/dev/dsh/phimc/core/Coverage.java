package dev.dsh.phimc.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 无头验收程序：整首曲子按 60fps 步进，统计「每个音符在它的判定窗口内是否至少被
 * {@link Engine#visible} 返回过一次」，并给「从未出现」的音符归类。
 *
 * <pre>
 * java -cp "out;lib/gson-2.11.0.jar" dev.dsh.phimc.core.Coverage &lt;chart.json&gt; [fps] [aspect]
 * </pre>
 *
 * <p>同一份工具既能跑「修前」也能跑「修后」的 Engine（反射探测 {@code visible(double)}
 * 单参版与私有 {@code states} 字段）：
 * <ul>
 *   <li>修前：可见条件 = {@code dt ∈ [-0.2, lead]}（Engine.java:438-439）且 {@code |n| ≤ 4.0}（Engine.java:457）</li>
 *   <li>修后：可见条件 = 本地 y 落在判定线视口上下界内（line.rs:393-394/406/423）+ 时间规则（note.rs:236-241）</li>
 * </ul>
 * 只统计「音符还没被判走」的帧——被判走的音符本来就不该画（note.rs:195-197）。
 */
public final class Coverage {

    private static final double LEGACY_LEAD = 1.2;      // 修前 PlayScreen 传给 visible 的 lead
    private static final double LEGACY_GRACE = 0.20;    // 修前 Engine.GRACE
    private static final double LEGACY_ABS_N = 4.0;     // 修前 Engine.java:457 的兜底剔除阈值
    private static final double LEGACY_FADEOUT = 0.16;  // note.rs:10 FADEOUT_TIME

    private static final Field F_STATES = field(Engine.class, "states");
    private static final Method M_VISIBLE1 = method(Engine.class, "visible", double.class);
    private static final Method M_BOUNDS = method(Engine.class, "viewportLocalBounds", Chart.Line.class, double.class, double.class);

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalStateException("no field " + name);
    }

    private static Method method(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        double fps = args.length > 1 ? Double.parseDouble(args[1]) : 60.0;
        double aspect = args.length > 2 ? Double.parseDouble(args[2]) : 16.0 / 9.0;
        Chart chart = ChartParser.parse(p);
        Engine e = new Engine(chart, new Judgement.Config());
        e.reset();
        @SuppressWarnings("unchecked")
        List<Engine.NoteState> states = (List<Engine.NoteState>) F_STATES.get(e);
        boolean modern = M_VISIBLE1 != null;

        IdentityHashMap<Engine.NoteState, Integer> slot = new IdentityHashMap<>();
        for (int i = 0; i < states.size(); i++) slot.put(states.get(i), i);
        List<Chart.Note> all = chart.allNotes();
        all.sort((a, b) -> Double.compare(a.time, b.time));
        int fakeTotal = 0, fakeInEngine = 0;
        for (Chart.Note nn : all) if (nn.fake) fakeTotal++;
        for (Engine.NoteState s : states) if (s.note.fake) fakeInEngine++;

        int n = states.size();
        double[] minAbsY = new double[n];        // 未判定帧里离判定线最近的距离（屏高比例）
        double[] minAbsYt = new double[n];
        int[] insideFrames = new int[n];         // 未判定帧里落在可见区内的帧数
        int[] distOkFrames = new int[n];         // 修前：|n| ≤ 4 的帧数
        int[] windowOkFrames = new int[n];       // 修前：dt ∈ [-GRACE, lead] 的帧数
        boolean[] seen = new boolean[n];
        double[] firstVisible = new double[n];
        java.util.Arrays.fill(minAbsY, Double.MAX_VALUE);
        java.util.Arrays.fill(minAbsYt, Double.NaN);
        java.util.Arrays.fill(firstVisible, Double.NaN);

        double end = chart.duration + 2.0;
        double dt = 1.0 / fps;
        int frames = 0;
        for (double t = 0; t <= end; t += dt, frames++) {
            e.update(t);
            for (Engine.Visible v : visible(e, t, aspect)) {
                Integer i = slot.get(v.state());
                if (i == null) continue;
                if (!seen[i]) {
                    seen[i] = true;
                    firstVisible[i] = t;
                }
            }
            for (int i = 0; i < n; i++) {
                Engine.NoteState s = states.get(i);
                if (s.judged) continue;
                double[] loc = Engine.noteLocal(s, t, aspect);
                double localY = loc[1];
                // 两个版本的 noteLocal 都返回「世界本地 y」：世界 y=1 对应半屏高，故 |n| = |localY|*aspect/2
                double absN = Math.abs(localY) * aspect / 2.0;
                if (absN < minAbsY[i]) {
                    minAbsY[i] = absN;
                    minAbsYt[i] = t;
                }
                if (modern) {
                    double[] b = bounds(s.line, t, aspect);
                    if (localY >= b[0] && localY <= b[1]) insideFrames[i]++;
                } else {
                    double noteTime = s.note.time;
                    double d = noteTime - t;
                    if (Math.abs(localY) <= LEGACY_ABS_N) distOkFrames[i]++;
                    if (d >= -LEGACY_GRACE && d <= LEGACY_LEAD) windowOkFrames[i]++;
                }
            }
        }

        int seenCount = 0;
        Map<String, List<Integer>> reasons = new LinkedHashMap<>();
        reasons.put("窗口剔除", new ArrayList<>());
        reasons.put("距离剔除", new ArrayList<>());
        reasons.put("帧间掠过", new ArrayList<>());
        reasons.put("线贴屏幕边缘", new ArrayList<>());
        reasons.put("其它", new ArrayList<>());
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (seen[i]) {
                seenCount++;
                continue;
            }
            Engine.NoteState s = states.get(i);
            String reason;
            double dw = dwell(e, s, aspect, modern);
            if (modern) {
                double[] b = bounds(s.line, s.note.time, aspect);
                boolean edge = s.note.above ? b[1] <= 0.02 : b[0] >= -0.02;
                if (dw * fps < 1.0) reason = "帧间掠过";
                else if (edge) reason = "线贴屏幕边缘";
                else if (minAbsY[i] > 1.0) reason = "距离剔除";
                else if (insideFrames[i] > 0) reason = "其它";
                else reason = "其它";
            } else {
                if (distOkFrames[i] == 0) reason = "距离剔除";
                else if (windowOkFrames[i] == 0) reason = "窗口剔除";
                else reason = "其它";
            }
            List<Integer> bucket = reasons.get(reason);
            bucket.add(i);
            if (bucket.size() <= 25) {
                rows.append(String.format(Locale.ROOT,
                        "    [%s] line=%2d time=%8.3f type=%-4s speed=%8.3f above=%-5s minDist=%9.3f@%8.3f dwell=%7.4fs%n",
                        reason, s.line.index, s.note.time, s.note.type, s.note.speed, s.note.above,
                        minAbsY[i], minAbsYt[i], dw));
            }
        }
        System.out.printf(Locale.ROOT, "=== %s ===%n", p.getFileName());
        System.out.printf(Locale.ROOT,
                "engine=%s fps=%.0f aspect=%.4f 音符=%d fake=%d 进入判定引擎=%d 步进帧数=%d%n",
                modern ? "after" : "before", fps, aspect, all.size(), fakeTotal, n, frames);
        System.out.printf(Locale.ROOT, "曾经 visible() 返回过 = %d / %d%n", seenCount, n);
        System.out.printf(Locale.ROOT, "从未出现 = %d%n", n - seenCount);
        for (Map.Entry<String, List<Integer>> r : reasons.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-6s = %d%n", r.getKey(), r.getValue().size());
        }
        System.out.println("  从未出现的音符样本（每类最多 25 条）：");
        System.out.print(rows);
        for (Map.Entry<String, List<Integer>> r : reasons.entrySet()) {
            if (r.getValue().isEmpty()) continue;
            Map<Integer, Integer> perLine = new LinkedHashMap<>();
            for (int i : r.getValue()) perLine.merge(states.get(i).line.index, 1, Integer::sum);
            StringBuilder sb = new StringBuilder("  " + r.getKey() + " 按判定线: ");
            for (Map.Entry<Integer, Integer> pe : perLine.entrySet()) {
                sb.append("L").append(pe.getKey()).append("=").append(pe.getValue()).append(" ");
            }
            System.out.println(sb);
        }
        System.out.printf(Locale.ROOT, "fake 音符：谱面 %d 个，进入引擎 %d 个%n", fakeTotal, fakeInEngine);

        double maxAbs = 0;
        int bad = 0;
        for (int i = 0; i < n; i++) {
            Engine.NoteState s = states.get(i);
            double[] loc = Engine.noteLocal(s, s.note.time, aspect);
            double nn = Math.abs(loc[1]) * aspect / 2.0;
            if (nn > maxAbs) maxAbs = nn;
            if (nn > 0.5) bad++;
        }
        System.out.printf(Locale.ROOT, "判定时刻偏离判定线超过半屏高的音符 = %d，最大 |n| = %.3f%n", bad, maxAbs);
    }

    private static double[] bounds(Chart.Line line, double t, double aspect) throws Exception {
        if (M_BOUNDS != null) return (double[]) M_BOUNDS.invoke(null, line, t, aspect);
        return new double[]{-LEGACY_ABS_N, LEGACY_ABS_N};
    }

    /**
     * 音符「从进入屏幕到越过判定线」的时长（秒，按它在判定线附近的屏幕移动速度算）。
     * 60fps 下一帧 16.7ms，dwell 小于这个值就必然采不到——这是谱面本身的速度，不是渲染 bug。
     */
    private static double dwell(Engine e, Engine.NoteState s, double aspect, boolean modern) {
        double eps = 0.01;
        double n1 = Engine.noteLocal(s, s.note.time - eps, aspect)[1] * aspect / 2.0;
        double n2 = Engine.noteLocal(s, s.note.time + eps, aspect)[1] * aspect / 2.0;
        double rate = Math.abs(n2 - n1) / (2 * eps);          // 屏高比例 / 秒
        double bound = modern ? 0.5 : LEGACY_ABS_N;
        if (rate <= 1e-9) return 999.0;
        return 2.0 * bound / rate;
    }

    @SuppressWarnings("unchecked")
    private static List<Engine.Visible> visible(Engine e, double t, double aspect) throws Exception {
        if (M_VISIBLE1 != null) return (List<Engine.Visible>) M_VISIBLE1.invoke(e, t);
        return e.visible(t, LEGACY_LEAD);
    }
}
