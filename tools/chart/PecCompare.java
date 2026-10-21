import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * PEC 缓动对照工具：<b>独立参考实现</b>（{@code prpr/src/parse/pec.rs} 的逐行移植，缓动表直接抄
 * {@code core/tween.rs} 的 {@code TWEEN_FUNCTIONS}）vs <b>我们的</b> {@link ChartParser} 解析结果。
 *
 * <p>为什么参考实现要独立写：验证的意义在于「两条独立路径算出同一个数」。
 * 这里刻意不调用 {@code RpeParser.tween}，而是把 {@code tween.rs:34-113} 的 33 条曲线和
 * {@code parse.rs:75-94} 的 {@code RPE_TWEEN_MAP} 重新打一遍。
 *
 * <p>单位口径（把两边的数换算到同一把尺子上）：
 * <ul>
 *   <li>{@code x}：prpr 的 {@code object.translation.0}（{@code cp/cm} 的 {@code v/2048*2-1}）；
 *       我们的是「屏幕比例」，换算 = {@code layerValue*2-1}；</li>
 *   <li>{@code y}：同上，{@code /1400*2-1}；</li>
 *   <li>{@code rot}：度，两边都已取负；</li>
 *   <li>{@code alpha}：0..1；</li>
 *   <li>{@code height}：prpr 的 {@code line.height.now()}（{@code pec.rs:122-138} 的积分）；
 *       {@code PecParser} 已经把速度值乘过 {@code HEIGHT_RATIO}（让 {@code heightAt} 里的除法抵消），</li>
 *       <li>       所以两边都是「直接取」。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 * java -cp "out/dev;gson.jar;slf4j.jar" PecCompare [pez路径] [线号]
 * </pre>
 */
public final class PecCompare {

    // ==================================================================
    // 参考实现：prpr/src/parse/pec.rs（逐行移植）
    // ==================================================================

    /** {@code parse.rs:75-94} RPE_TWEEN_MAP：下标 = PEC 的 tween 编号。 */
    private static final int[] TWEEN_MAP = {
            2, 2,                                   // 0,1 = linear
            e(0, 1), e(0, 0),                        // 2 SineOut, 3 SineIn
            e(1, 1), e(1, 0),                        // 4 QuadOut, 5 QuadIn
            e(0, 2), e(1, 2),                        // 6 SineInOut, 7 QuadInOut
            e(2, 1), e(2, 0),                        // 8 CubicOut, 9 CubicIn
            e(3, 1), e(3, 0),                        // 10 QuartOut, 11 QuartIn
            e(2, 2), e(3, 2),                        // 12 CubicInOut, 13 QuartInOut
            e(4, 1), e(4, 0),                        // 14 QuintOut, 15 QuintIn
            e(5, 1), e(5, 0),                        // 16 ExpoOut, 17 ExpoIn
            e(6, 1), e(6, 0),                        // 18 CircOut, 19 CircIn
            e(7, 1), e(7, 0),                        // 20 BackOut, 21 BackIn
            e(6, 2), e(7, 2),                        // 22 CircInOut, 23 BackInOut
            e(8, 1), e(8, 0),                        // 24 ElasticOut, 25 ElasticIn
            e(9, 1), e(9, 0),                        // 26 BounceOut, 27 BounceIn
            e(9, 2), e(8, 2),                        // 28 BounceInOut, 29 ElasticInOut
    };

    /** tween.rs 的 {@code easing_from(major, minor)} = 3 + major*3 + minor。 */
    private static int e(int major, int minor) {
        return 3 + major * 3 + minor;
    }

    /** {@code tween.rs:100-113} {@code TWEEN_FUNCTIONS}（33 条）。 */
    private static double tweenFn(int id, double x) {
        if (id < 0 || id > 32) return x;
        return switch (id) {
            case 0 -> 0.0;
            case 1 -> 1.0;
            case 2 -> x;
            default -> {
                int major = (id - 3) / 3;
                int minor = (id - 3) % 3;
                double r = switch (minor) {
                    case 0 -> base(major, x);
                    case 1 -> 1.0 - base(major, 1.0 - x);
                    default -> {
                        double x2 = x * 2.0;
                        yield x2 < 1.0 ? base(major, x2) / 2.0 : 1.0 - base(major, 2.0 - x2) / 2.0;
                    }
                };
                yield r;
            }
        };
    }

    private static double base(int major, double x) {
        return switch (major) {
            case 0 -> 1.0 - Math.cos(x * Math.PI / 2.0);
            case 1 -> x * x;
            case 2 -> x * x * x;
            case 3 -> x * x * x * x;
            case 4 -> x * x * x * x * x;
            case 5 -> Math.pow(2.0, 10.0 * (x - 1.0));
            case 6 -> 1.0 - Math.sqrt(1.0 - x * x);
            case 7 -> {
                double c1 = 1.70158, c3 = c1 + 1.0;
                yield (c3 * x - c1) * x * x;
            }
            case 8 -> {
                double c4 = 2.0 * Math.PI / 3.0;
                yield -Math.pow(2.0, 10.0 * x - 10.0) * Math.sin((x * 10.0 - 10.75) * c4);
            }
            default -> {
                double n1 = 7.5625, d1 = 2.75;
                double u = 1.0 - x;
                double v;
                if (u < 1.0 / d1) v = n1 * u * u;
                else if (u < 2.0 / d1) {
                    double t = u - 1.5 / d1;
                    v = n1 * t * t + 0.75;
                } else if (u < 2.5 / d1) {
                    double t = u - 2.25 / d1;
                    v = n1 * t * t + 0.9375;
                } else {
                    double t = u - 2.625 / d1;
                    v = n1 * t * t + 0.984375;
                }
                yield 1.0 - v;
            }
        };
    }

    /** {@code pec.rs:53-73} {@code PECEvent}。 */
    private record PECEvent(double start, double end, double value, int easing) {
    }

    /** 关键帧序列：{@code Keyframe::new(time, value, tween)}；tween 作用在 [i, i+1]。 */
    private record Kfs(double[] time, double[] value, int[] tween) {
        static final Kfs EMPTY = new Kfs(new double[0], new double[0], new int[0]);

        /** {@code anim.rs:89-121}：cursor 语义，等价于「最后一个 time <= t 的关键帧」。 */
        double now(double t) {
            int n = time.length;
            if (n == 0) return Double.NaN;
            int lo = 0, hi = n - 1, idx = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (time[mid] <= t) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (idx == n - 1) return value[idx];
            double span = time[idx + 1] - time[idx];
            double f = span == 0 ? 1.0 : (t - time[idx]) / span;
            return value[idx] + (value[idx + 1] - value[idx]) * tweenFn(tween[idx], f);
        }
    }

    /** {@code pec.rs:84-102} {@code sanitize_events}。 */
    private static void sanitize(List<PECEvent> events) {
        events.sort(Comparator.<PECEvent>comparingDouble(a -> a.end).thenComparingDouble(a -> a.start));
        double lastEnd = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < events.size(); i++) {
            PECEvent e = events.get(i);
            double s = e.start;
            if (s < lastEnd) {
                s = lastEnd;
                events.set(i, new PECEvent(s, e.end, e.value, e.easing));
            }
            lastEnd = e.end;
        }
    }

    /** {@code pec.rs:104-120} {@code parse_events}。 */
    private static Kfs parseEvents(List<PECEvent> events) {
        sanitize(events);
        List<double[]> kf = new ArrayList<>();   // {time, value, tween}
        for (PECEvent e : events) {
            if (e.start == e.end) {
                kf.add(new double[]{e.start, e.value, 0});
            } else {
                if (kf.isEmpty()) return Kfs.EMPTY;    // prpr 在这里 bail；我们返回空（见报告）
                kf.add(new double[]{e.start, kf.get(kf.size() - 1)[1], TWEEN_MAP[Math.min(e.easing, TWEEN_MAP.length - 1)]});
                kf.add(new double[]{e.end, e.value, 0});
            }
        }
        double[] t = new double[kf.size()];
        double[] v = new double[kf.size()];
        int[] w = new int[kf.size()];
        for (int i = 0; i < kf.size(); i++) {
            t[i] = kf.get(i)[0];
            v[i] = kf.get(i)[1];
            w[i] = (int) kf.get(i)[2];
        }
        return new Kfs(t, v, w);
    }

    /** {@code pec.rs:122-138} {@code parse_speed_events}。 */
    private static Kfs parseSpeedEvents(List<double[]> pts, double maxTime) {
        List<double[]> pec = new ArrayList<>(pts);
        if (pec.isEmpty()) return Kfs.EMPTY;
        if (pec.get(0)[0] >= 1e-6) pec.add(0, new double[]{0, 0});
        List<double[]> kf = new ArrayList<>();
        double height = 0.0;
        double lastTime = 0.0;
        double lastSpeed = 0.0;
        for (double[] p : pec) {
            height += (p[0] - lastTime) * lastSpeed;
            kf.add(new double[]{p[0], height, 2});     // tween 2 = linear
            lastTime = p[0];
            lastSpeed = p[1];
        }
        kf.add(new double[]{maxTime, height + (maxTime - lastTime) * lastSpeed, 0});
        double[] t = new double[kf.size()];
        double[] v = new double[kf.size()];
        int[] w = new int[kf.size()];
        for (int i = 0; i < kf.size(); i++) {
            t[i] = kf.get(i)[0];
            v[i] = kf.get(i)[1];
            w[i] = (int) kf.get(i)[2];
        }
        return new Kfs(t, v, w);
    }

    private static final class RefLine {
        final List<double[]> speed = new ArrayList<>();
        final List<PECEvent> alpha = new ArrayList<>();
        final List<PECEvent> moveX = new ArrayList<>();
        final List<PECEvent> moveY = new ArrayList<>();
        final List<PECEvent> rot = new ArrayList<>();
        Kfs kSpeed, kAlpha, kMoveX, kMoveY, kRot;
        /** 「修前」口径（起点值取文件顺序里上一条同轨道事件）的等价轨道，只用于 before/after 对照。 */
        Kfs lMoveX, lMoveY, lRot, lAlpha;
    }

    /**
     * <b>修前的实现</b>（复刻改动前的 {@code PecParser}）：起点值在<b>解析时按文件顺序</b>取
     * 「上一条同轨道事件的终点值」，然后才排序 + 裁重叠。
     *
     * <p>和 {@link #parseEvents}（{@code pec.rs:104-120}，起点值取「排序裁切后的前一个关键帧」）
     * 的差别就是这次修的 bug。这里保留一份旧口径，专门用来生成「修前 / 修后」对照表。
     */
    private static Kfs parseEventsLegacy(List<PECEvent> events, double def) {
        record Leg(double s, double t1, double v0, double v1, int tw) {
        }
        List<Leg> legs = new ArrayList<>(events.size());
        double prev = def;
        for (PECEvent e : events) {
            legs.add(new Leg(e.start, e.end, prev, e.value, e.easing));
            prev = e.value;                       // ← 修前就是这个：文件顺序的上一条终点值
        }
        legs.sort(Comparator.<Leg>comparingDouble(Leg::t1).thenComparingDouble(Leg::s));
        List<double[]> kf = new ArrayList<>();
        double lastEnd = Double.NEGATIVE_INFINITY;
        for (Leg l : legs) {
            double s = Math.max(l.s, lastEnd);
            double t1 = Math.max(s, l.t1);
            lastEnd = Math.max(lastEnd, t1);
            kf.add(new double[]{s, l.v0, l.tw == 0 ? 0 : TWEEN_MAP[Math.min(l.tw, TWEEN_MAP.length - 1)]});
            kf.add(new double[]{t1, l.v1, 0});
        }
        double[] t = new double[kf.size()];
        double[] v = new double[kf.size()];
        int[] w = new int[kf.size()];
        for (int i = 0; i < kf.size(); i++) {
            t[i] = kf.get(i)[0];
            v[i] = kf.get(i)[1];
            w[i] = (int) kf.get(i)[2];
        }
        return new Kfs(t, v, w);
    }

    private static final class RefChart {
        final List<RefLine> lines = new ArrayList<>();
        final List<double[]> bpms = new ArrayList<>();
    }

    private static RefChart refParse(String source) {
        RefChart chart = new RefChart();
        String[] raw = source.split("\\r?\\n");
        int first = -1;
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                first = i;
                break;
            }
        }
        double maxTime = 0;
        List<String> body = new ArrayList<>();
        for (int i = first + 1; i < raw.length; i++) {
            String s = raw[i].trim();
            if (!s.isEmpty()) body.add(s);
        }
        // 先把 bp 全读掉（prpr 也是「bp 必须出现在第一个音符/事件之前」）
        for (String s : body) {
            String[] t = s.split("\\s+");
            if (t[0].equals("bp")) chart.bpms.add(new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2])});
        }
        // 一遍：按 prpr 的 bpm 列表折成秒
        for (String s : body) {
            String[] t = s.split("\\s+");
            String cmd = t[0];
            switch (cmd) {
                case "bp" -> {
                }
                case "n1", "n2", "n3", "n4" -> {
                    int id = Integer.parseInt(t[1]);
                    while (chart.lines.size() <= id) chart.lines.add(new RefLine());
                    double time = beatsToSeconds(chart.bpms, Double.parseDouble(t[2]));
                    maxTime = Math.max(maxTime, time);
                    if (cmd.equals("n2")) {
                        maxTime = Math.max(maxTime, beatsToSeconds(chart.bpms, Double.parseDouble(t[3])));
                    }
                }
                case "cv", "cp", "cd", "ca", "cm", "cr", "cf" -> {
                    int id = Integer.parseInt(t[1]);
                    while (chart.lines.size() <= id) chart.lines.add(new RefLine());
                    RefLine l = chart.lines.get(id);
                    double time = beatsToSeconds(chart.bpms, Double.parseDouble(t[2]));
                    maxTime = Math.max(maxTime, time);
                    switch (cmd) {
                        case "cv" -> l.speed.add(new double[]{time, Double.parseDouble(t[3]) / 5.85});
                        case "cp" -> {
                            l.moveX.add(new PECEvent(time, time, Double.parseDouble(t[3]), 0));
                            l.moveY.add(new PECEvent(time, time, Double.parseDouble(t[4]), 0));
                        }
                        case "cd" -> l.rot.add(new PECEvent(time, time, -Double.parseDouble(t[3]), 0));
                        case "ca" -> l.alpha.add(new PECEvent(time, time, Double.parseDouble(t[3]), 0));
                        case "cm" -> {
                            double end = beatsToSeconds(chart.bpms, Double.parseDouble(t[3]));
                            maxTime = Math.max(maxTime, end);
                            int tw = Integer.parseInt(t[6]);
                            l.moveX.add(new PECEvent(time, end, Double.parseDouble(t[4]), tw));
                            l.moveY.add(new PECEvent(time, end, Double.parseDouble(t[5]), tw));
                        }
                        case "cr" -> {
                            double end = beatsToSeconds(chart.bpms, Double.parseDouble(t[3]));
                            maxTime = Math.max(maxTime, end);
                            l.rot.add(new PECEvent(time, end, -Double.parseDouble(t[4]), Integer.parseInt(t[5])));
                        }
                        case "cf" -> {
                            double end = beatsToSeconds(chart.bpms, Double.parseDouble(t[3]));
                            maxTime = Math.max(maxTime, end);
                            // pec.rs:335 alpha 用 easing 2（线性）；/255 在 parse_judge_line 里统一做
                            l.alpha.add(new PECEvent(time, end, Double.parseDouble(t[4]), 2));
                        }
                        default -> {
                        }
                    }
                }
                default -> {
                }
            }
        }
        double limit = maxTime + 1.0;                                  // pec.rs:367
        for (RefLine l : chart.lines) {
            // pec.rs:152-158：先把「事件终点值」换算成 prpr 口径
            scaleEvents(l.moveX, 2.0 / 2048.0);
            scaleEvents(l.moveY, 2.0 / 1400.0);
            // pec.rs:154-158：alpha 只对「>= 0」的终点值除以 255，负数是 Phigros 的扩展编码
            scaleAlpha(l.alpha);
            // 修前口径必须先算：parseEvents / sanitize 会**原地排序**这几个 list，
            // 之后就拿不到「文件顺序」了（这正是修前那个 bug 的关键信息）。
            l.lMoveX = parseEventsLegacy(l.moveX, 0.5);
            l.lMoveY = parseEventsLegacy(l.moveY, 0.5);
            l.lRot = parseEventsLegacy(l.rot, 0.0);
            l.lAlpha = parseEventsLegacy(l.alpha, 1.0);
            l.kSpeed = parseSpeedEvents(l.speed, limit);
            l.kAlpha = parseEvents(l.alpha);
            l.kMoveX = parseEvents(l.moveX);
            l.kMoveY = parseEvents(l.moveY);
            l.kRot = parseEvents(l.rot);
        }
        return chart;
    }

    /** 对应 {@code pec.rs:152-153} 的 {@code it.end = it.end / 2048. * 2. - 1.}。 */
    private static void scaleEvents(List<PECEvent> list, double factor) {
        for (int i = 0; i < list.size(); i++) {
            PECEvent e = list.get(i);
            list.set(i, new PECEvent(e.start, e.end, e.value * factor - 1.0, e.easing));
        }
    }

    /** {@code pec.rs:154-158}：{@code if it.end >= 0.0 { it.end /= 255. }}。 */
    private static void scaleAlpha(List<PECEvent> list) {
        for (int i = 0; i < list.size(); i++) {
            PECEvent e = list.get(i);
            double v = e.value >= 0 ? e.value / 255.0 : e.value;
            list.set(i, new PECEvent(e.start, e.end, v, e.easing));
        }
    }

    private static double beatsToSeconds(List<double[]> bpms, double beats) {
        if (bpms.isEmpty()) return beats;
        List<double[]> sorted = new ArrayList<>(bpms);
        sorted.sort(Comparator.comparingDouble(a -> a[0]));
        double time = 0, startBeat = sorted.get(0)[0], bpm = sorted.get(0)[1];
        for (int i = 1; i < sorted.size(); i++) {
            double[] kf = sorted.get(i);
            if (kf[0] > beats) break;
            time += (kf[0] - startBeat) * (60.0 / (bpm <= 0 ? 120 : bpm));
            startBeat = kf[0];
            bpm = kf[1];
        }
        return time + (beats - startBeat) * (60.0 / (bpm <= 0 ? 120 : bpm));
    }

    // ==================================================================
    // 对照
    // ==================================================================

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0]
                : "D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs/6954_-1.pez";
        int wantLine = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        String pec = readPec(Path.of(file));
        String name = Path.of(file).getFileName().toString();

        Chart ours;
        long t0 = System.nanoTime();
        try {
            ours = ChartParser.parse(pec);
        } catch (Exception ex) {
            System.out.println("我们的解析器失败: " + ex);
            return;
        }
        long oursMs = (System.nanoTime() - t0) / 1_000_000L;
        long t1 = System.nanoTime();
        RefChart ref = refParse(pec);
        long refMs = (System.nanoTime() - t1) / 1_000_000L;

        System.out.printf(Locale.ROOT, "== %s ==%n", name);
        System.out.printf(Locale.ROOT, " 我们的: %d 线, 时长 %.2fs, 解析 %dms | 参考(pec.rs): %d 线, %dms%n",
                ours.lines.size(), ours.duration, oursMs, ref.lines.size(), refMs);
        int n = Math.min(ours.lines.size(), ref.lines.size());

        // ---- 全库偏差汇总 ----
        System.out.println();
        System.out.println("全判定线偏差（每 100ms 采样，误差上限 1e-3）：");
        System.out.printf(Locale.ROOT, "%-5s %-9s %-9s %-9s %-9s %-9s %-9s%n",
                "线", "Δx_max", "Δy_max", "Δrot_max", "Δalpha_max", "Δheight_max", "超差采样");
        double end = ours.duration;
        int worstLine = -1;
        double worstErr = -1;
        for (int li = 0; li < n; li++) {
            Chart.Line ol = ours.lines.get(li);
            RefLine rl = ref.lines.get(li);
            double dx = 0, dy = 0, dr = 0, da = 0, dh = 0;
            int over = 0;
            int samples = 0;
            for (double t = 0; t <= end; t += 0.1) {
                double ox = Chart.Line.layerValue(ol.moveX, t, 0.5) * 2 - 1;
                double oy = Chart.Line.layerValue(ol.moveY, t, 0.5) * 2 - 1;
                double or = Chart.Line.layerValue(ol.rotate, t, 0.0);
                double oa = Chart.Line.layerValue(ol.alpha, t, 1.0);
                double oh = ol.floorPositionAt(t);
                double rx = rl.kMoveX.time.length == 0 ? 0.0 : rl.kMoveX.now(t);
                double ry = rl.kMoveY.time.length == 0 ? 0.0 : rl.kMoveY.now(t);
                double rr = rl.kRot.time.length == 0 ? 0.0 : rl.kRot.now(t);
                double ra = rl.kAlpha.time.length == 0 ? 1.0 : rl.kAlpha.now(t);
                double rh = rl.kSpeed.time.length == 0 ? 0.0 : rl.kSpeed.now(t);
                double ex = Math.abs(ox - rx), ey = Math.abs(oy - ry), er = Math.abs(or - rr);
                double ea = Math.abs(oa - ra), eh = Math.abs(oh - rh);
                dx = Math.max(dx, ex);
                dy = Math.max(dy, ey);
                dr = Math.max(dr, er);
                da = Math.max(da, ea);
                dh = Math.max(dh, eh);
                if (Math.max(Math.max(ex, ey), Math.max(Math.max(er, ea), eh)) > 1e-3) over++;
                samples++;
            }
            double w = Math.max(Math.max(dx, dy), Math.max(Math.max(dr, da), dh));
            if (w > worstErr) {
                worstErr = w;
                worstLine = li;
            }
            System.out.printf(Locale.ROOT, "%-5d %-9.2e %-9.2e %-9.2e %-9.2e %-9.3e %d/%d%n",
                    li, dx, dy, dr, da, dh, over, samples);
        }
        System.out.printf(Locale.ROOT, "%n最大偏差在 line %d（%.3e）%n", worstLine, worstErr);

        // ---- 超差采样定位（前 12 个） ----
        if (worstLine >= 0) {
            Chart.Line ol = ours.lines.get(worstLine);
            RefLine rl = ref.lines.get(worstLine);
            System.out.printf(Locale.ROOT, "line %d 的超差采样（前 12 个）：%n", worstLine);
            int shown = 0;
            for (double t = 0; t <= end && shown < 12; t += 0.1) {
                double ox = Chart.Line.layerValue(ol.moveX, t, 0.5) * 2 - 1;
                double oy = Chart.Line.layerValue(ol.moveY, t, 0.5) * 2 - 1;
                double or = Chart.Line.layerValue(ol.rotate, t, 0.0);
                double oa = Chart.Line.layerValue(ol.alpha, t, 1.0);
                double oh = ol.floorPositionAt(t);
                double rx = rl.kMoveX.time.length == 0 ? 0.0 : rl.kMoveX.now(t);
                double ry = rl.kMoveY.time.length == 0 ? 0.0 : rl.kMoveY.now(t);
                double rr = rl.kRot.time.length == 0 ? 0.0 : rl.kRot.now(t);
                double ra = rl.kAlpha.time.length == 0 ? 1.0 : rl.kAlpha.now(t);
                double rh = rl.kSpeed.time.length == 0 ? 0.0 : rl.kSpeed.now(t);
                double e = Math.max(Math.max(Math.abs(ox - rx), Math.abs(oy - ry)),
                        Math.max(Math.max(Math.abs(or - rr), Math.abs(oa - ra)), Math.abs(oh - rh)));
                if (e <= 1e-3) continue;
                shown++;
                System.out.printf(Locale.ROOT,
                        "  t=%7.2f  x %8.4f/%8.4f  y %8.4f/%8.4f  rot %9.3f/%9.3f  a %8.4f/%8.4f  h %9.4f/%9.4f%n",
                        t, ox, rx, oy, ry, or, rr, oa, ra, oh, rh);
            }
        }

        // ---- 单线采样表（每 100ms）：修前 / 修后 / 参考，三列对照 ----
        int li = wantLine >= 0 ? wantLine : worstLine;
        if (li >= 0 && li < n) {
            Chart.Line ol = ours.lines.get(li);
            RefLine rl = ref.lines.get(li);
            System.out.println();
            System.out.printf(Locale.ROOT,
                    "line %d 采样（每 100ms；位置 = cp/cm 的 world x，角度 = cr 的 deg，α = ca/cf）%n", li);
            System.out.printf(Locale.ROOT,
                    "%-7s | %-9s %-9s %-9s %-8s %-8s | %-9s %-9s %-8s | %-9s %-9s %-8s%n",
                    "t(s)", "x参考", "x修前", "x修后", "Δ修前", "Δ修后",
                    "rot参考", "rot修前", "rot修后", "α参考", "α修前", "α修后");
            double maxBefore = 0;
            double maxAfter = 0;
            for (double t = 0; t <= end; t += 0.1) {
                double ox = Chart.Line.layerValue(ol.moveX, t, 0.5) * 2 - 1;
                double or = Chart.Line.layerValue(ol.rotate, t, 0.0);
                double oa = Chart.Line.layerValue(ol.alpha, t, 1.0);
                double rx = rl.kMoveX.time.length == 0 ? 0.0 : rl.kMoveX.now(t);
                double rr = rl.kRot.time.length == 0 ? 0.0 : rl.kRot.now(t);
                double ra = rl.kAlpha.time.length == 0 ? 1.0 : rl.kAlpha.now(t);
                double bx = rl.lMoveX.time.length == 0 ? 0.0 : rl.lMoveX.now(t);
                double br = rl.lRot.time.length == 0 ? 0.0 : rl.lRot.now(t);
                double ba = rl.lAlpha.time.length == 0 ? 1.0 : rl.lAlpha.now(t);
                double dBefore = Math.max(Math.max(Math.abs(bx - rx), Math.abs(br - rr)), Math.abs(ba - ra));
                double dAfter = Math.max(Math.max(Math.abs(ox - rx), Math.abs(or - rr)), Math.abs(oa - ra));
                maxBefore = Math.max(maxBefore, dBefore);
                maxAfter = Math.max(maxAfter, dAfter);
                // 只打印有意义的行：要么修前/修后差出一个可看的量，要么刚好整秒
                if (dBefore < 1e-3 && dAfter < 1e-3 && Math.abs(t - Math.round(t)) > 1e-9) continue;
                System.out.printf(Locale.ROOT,
                        "%-7.1f | %9.5f %9.5f %9.5f %-8.1e %-8.1e | %9.3f %9.3f %9.3f | %9.4f %9.4f %9.4f%n",
                        t, rx, bx, ox, Math.abs(bx - rx), Math.abs(ox - rx),
                        rr, br, or, ra, ba, oa);
            }
            System.out.printf(Locale.ROOT, "  → line %d 最大偏差：修前 %.3e，修后 %.3e（阈值 1e-3）%n",
                    li, maxBefore, maxAfter);
        }
    }

    private static String readPec(Path pez) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(pez.toFile())) {
            java.util.zip.ZipEntry e = null;
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zf.entries(); it.hasMoreElements(); ) {
                java.util.zip.ZipEntry c = it.nextElement();
                if (c.getName().toLowerCase(Locale.ROOT).endsWith(".pec")) {
                    e = c;
                    break;
                }
            }
            if (e == null) throw new IllegalStateException("包里没有 .pec");
            try (java.io.InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    private PecCompare() {
    }
}
