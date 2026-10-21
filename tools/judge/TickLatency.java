import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 判定时刻量化：<b>每个音符「真正被判定的时刻」减 note.time 是多少</b>。
 *
 * <p>为什么需要它：{@code Session.tick()} 只挂在 {@code ClientTickEvents.END_CLIENT_TICK}（20Hz），
 * 而 Tap 是「输入事件到了立刻判」（{@code Session.hitAt/pressKey} 里现读 {@code seconds()}）。
 * 所以 Drag/Flick 的判定时刻被量化到帧边界，Tap 不被量化 —— 打击音在判定那一刻播，
 * 于是 Drag/Flick 听起来比 Tap 晚。
 *
 * <p>本程序用真实谱面驱动 {@link Engine}，按不同驱动频率采样「判定发生的驱动时刻」：
 * <ul>
 *   <li><b>自动游玩</b>：{@code engine.autoplay(t)}（= {@code Session.tick()} 的自动分支）；</li>
 *   <li><b>手动</b>：{@code engine.update(t)} + 「按住任意键」（{@code setFlickKey(true)}，
 *       等价 keyDownCount != 0 的 PreJudge 条件）；Tap/Hold 按玩家的真实按键时刻
 *       （{@code engine.keyDown(note.time)}，两帧之间也能发生）。</li>
 * </ul>
 *
 * <p>同时统计「同一驱动步里起播了几个打击音」——一次判定播一声，同一帧里多个音符的打击音
 * 会同时起播，听感就是「两声叠在一起」。
 *
 * <p>用法：{@code java -cp "<core out>;gson" TickLatency <谱面包.zip|pez> [驱动频率...]}
 */
public final class TickLatency {

    public static void main(String[] args) throws Exception {
        Path zip = Path.of(args.length > 0 ? args[0]
                : "C:\\dsh-work\\payload\\Cyberneko Pulse (2).pez");
        double[] freqs = args.length > 1
                ? java.util.Arrays.stream(args, 1, args.length).mapToDouble(Double::parseDouble).toArray()
                : new double[]{20, 60, 144};

        String json = chartJson(zip);
        Chart chart = ChartParser.parse(json);
        System.out.printf("谱面：%s | 格式 %s | 音符 %d | 时长 %.1fs | 判定线 %d%n",
                zip.getFileName(), chart.format, chart.noteCount(), chart.duration, chart.lines.size());

        for (double f : freqs) {
            System.out.printf("%n==================== 驱动 %.0f Hz（周期 %.2f ms）====================%n",
                    f, 1000.0 / f);
            replay(chart, f, true, "自动游玩 autoplay(t)");
            replay(chart, f, false, "手动 update(t) + 按住键");
        }
    }

    // ------------------------------------------------------------------

    private static final class Sample {
        final Chart.Note.Type type;
        final double latency;     // 判定发生时刻 - note.time（秒）
        final double frameTime;   // 判定发生的驱动时刻
        final int frameIndex;     // 第几个驱动步（同一步 = 同时起播）

        Sample(Chart.Note.Type type, double latency, double frameTime, int frameIndex) {
            this.type = type;
            this.latency = latency;
            this.frameTime = frameTime;
            this.frameIndex = frameIndex;
        }
    }

    private static void replay(Chart chart, double hz, boolean autoplay, String label) {
        Engine engine = new Engine(chart, new Judgement.Config());
        engine.reset();
        double dt = 1.0 / hz;
        double end = chart.duration + 2.0;

        // 玩家按键时刻：Tap/Hold 的 note.time（keyboardTap 只吃 Tap/Hold）
        List<Double> pressTimes = new ArrayList<>();
        for (Chart.Line line : chart.lines) {
            for (Chart.Note n : line.notes) {
                if (!n.fake && (n.type == Chart.Note.Type.TAP || n.type == Chart.Note.Type.HOLD)) {
                    pressTimes.add(n.time);
                }
            }
        }
        pressTimes.sort(Double::compare);
        int pressIdx = 0;

        List<Sample> samples = new ArrayList<>();
        Map<Integer, Integer> perFrame = new java.util.HashMap<>();
        int frame = 0;
        for (double t = 0; t <= end; t += dt, frame++) {
            if (!autoplay) {
                // 两帧之间的真实按键：立刻判（这就是 Tap 不被量化的原因）
                while (pressIdx < pressTimes.size() && pressTimes.get(pressIdx) <= t + 1e-12) {
                    double pt = pressTimes.get(pressIdx++);
                    engine.keyDown(pt);
                    drain(engine, pt, frame, samples, perFrame);
                }
                engine.setFlickKey(true);        // 按住键：Drag/Flick 的 PreJudge 条件
                engine.update(t);
            } else {
                engine.autoplay(t);
            }
            drain(engine, t, frame, samples, perFrame);
        }

        report(label, samples, perFrame, dt);
    }

    private static void drain(Engine engine, double at, int frame, List<Sample> samples,
                              Map<Integer, Integer> perFrame) {
        for (Engine.JudgeEvent e : engine.drainJudgements()) {
            // 只有 Perfect / Good 会播打击音（Session.playHitSound）
            boolean sound = e.grade() == Judgement.Grade.PERFECT || e.grade() == Judgement.Grade.GOOD;
            samples.add(new Sample(e.state().note.type, at - e.state().note.time, at, frame));
            if (sound) perFrame.merge(frame, 1, Integer::sum);
        }
    }

    private static void report(String label, List<Sample> all, Map<Integer, Integer> perFrame, double dt) {
        System.out.printf("-- %s%n", label);
        System.out.printf("   %-6s %6s %9s %9s %9s %9s %9s %8s%n",
                "类型", "数量", "平均(ms)", "p50(ms)", "p95(ms)", "最大(ms)", ">16.7ms", ">50ms");
        for (Chart.Note.Type type : new Chart.Note.Type[]{
                Chart.Note.Type.TAP, Chart.Note.Type.HOLD, Chart.Note.Type.DRAG, Chart.Note.Type.FLICK}) {
            List<Double> lat = new ArrayList<>();
            for (Sample s : all) if (s.type == type) lat.add(s.latency);
            if (lat.isEmpty()) continue;
            lat.sort(Double::compare);
            double sum = 0;
            int over167 = 0, over50 = 0;
            for (double v : lat) {
                sum += v;
                if (v > 0.0167) over167++;
                if (v > 0.050) over50++;
            }
            System.out.printf("   %-8s %6d %9.2f %9.2f %9.2f %9.2f %8d %8d%n",
                    type, lat.size(), 1000 * sum / lat.size(),
                    1000 * lat.get(lat.size() / 2),
                    1000 * lat.get((int) Math.min(lat.size() - 1, Math.round(lat.size() * 0.95))),
                    1000 * lat.get(lat.size() - 1), over167, over50);
        }
        // 同帧叠播：一次判定播一声，同一驱动步里的多个音符会同时起播
        int steps = 0, multi = 0, max = 0, total = 0;
        for (int v : perFrame.values()) {
            steps++;
            total += v;
            max = Math.max(max, v);
            if (v >= 2) multi++;
        }
        System.out.printf("   打击音：%d 声 / %d 个「有判定的驱动步」（平均 %.2f 声/步）；"
                        + "同一步 ≥2 声的步数=%d（%.1f%%），单步最多 %d 声%n",
                total, steps, steps == 0 ? 0 : (double) total / steps, multi,
                steps == 0 ? 0 : 100.0 * multi / steps, max);
        // 「两声叠在一起」的最强证据：相邻两声的起播间隔 < 5ms
        List<Sample> sound = new ArrayList<>();
        for (Sample s : all) sound.add(s);
        sound.sort((a, b) -> Double.compare(a.frameTime, b.frameTime));
        int near = 0;
        for (int i = 1; i < sound.size(); i++) {
            if (Math.abs(sound.get(i).frameTime - sound.get(i - 1).frameTime) < 0.005) near++;
        }
        System.out.printf("   相邻两声起播间隔 <5ms 的对数 = %d（这些是「完全同时起播」，听感就是一坨）%n", near);
        System.out.printf("   （驱动周期 %.2f ms）%n", 1000 * dt);
    }

    // ------------------------------------------------------------------

    private static String chartJson(Path zip) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry chart = null;
            ZipEntry unique = null;
            int jsonCount = 0;
            for (var en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry z = en.nextElement();
                if (z.isDirectory()) continue;
                String low = z.getName().toLowerCase(java.util.Locale.ROOT);
                if (!low.endsWith(".json")) continue;
                jsonCount++;
                unique = z;
                String base = low.contains("/") ? low.substring(low.lastIndexOf('/') + 1) : low;
                if (base.contains("chart")) chart = z;
            }
            ZipEntry e = chart != null ? chart : (jsonCount == 1 ? unique : null);
            if (e == null) throw new IllegalStateException("压缩包里找不到唯一谱面 JSON");
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
