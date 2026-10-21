package dev.dsh.phimc.core;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

/** 单个音符的逐帧诊断：{@code Debug <chart> <lineIndex> <time> [frames]}。 */
public final class Debug {
    private static final Field F_STATES;

    static {
        try {
            F_STATES = Engine.class.getDeclaredField("states");
            F_STATES.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
        int lineIdx = Integer.parseInt(args[1]);
        double time = Double.parseDouble(args[2]);
        int frames = args.length > 3 ? Integer.parseInt(args[3]) : 12;
        double aspect = 16.0 / 9.0;
        Chart chart = ChartParser.parse(p);
        Engine e = new Engine(chart, new Judgement.Config());
        e.reset();
        @SuppressWarnings("unchecked")
        List<Engine.NoteState> states = (List<Engine.NoteState>) F_STATES.get(e);
        double t0 = time - frames / 120.0;
        for (double t = t0; t <= time + 4.0 / 60; t += 1.0 / 120.0) {
            e.update(t);
            System.out.printf("t=%.4f%n", t);
            List<Engine.Visible> vis = e.visible(t);
            for (Engine.NoteState s : states) {
                if (s.line.index != lineIdx) continue;
                if (Math.abs(s.note.time - time) > 0.06) continue;
                double[] loc = Engine.noteLocal(s, t, aspect);
                double[] b = Engine.viewportLocalBounds(s.line, t, aspect);
                boolean inVis = vis.stream().anyMatch(v -> v.state() == s);
                System.out.printf("   time=%.4f type=%-4s judged=%s holding=%s localY=%9.4f bounds=[%.3f,%.3f] alphaInList=%s vis=%s%n",
                        s.note.time, s.note.type, s.judged, s.holding, loc[1], b[0], b[1], inVis, vis.size());
            }
        }
    }
}
