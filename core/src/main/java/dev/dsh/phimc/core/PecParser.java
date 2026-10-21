package dev.dsh.phimc.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>PEC 文本谱面</b>解析器（社区 .pez 包里那个 {@code <id>.pec}）。
 *
 * <p>为什么需要它：Phira 的谱面包有三种形态——官方 PGR JSON（{@code Chart_IN.json}）、
 * RPE JSON（{@link RpeParser}）、以及 <b>PEC 纯文本</b>（{@code -1.pec} 这种）。
 * 用户曲库里 {@code 6954_-1.pez} 就是 PEC，之前走到 JSON 解析器直接
 * {@code MalformedJsonException}（界面上只显示「谱面解析失败」）。这里按
 * {@code prpr/src/parse/pec.rs} 的行为把它翻译成我们的 {@link Chart}。
 *
 * <h2>格式（一行一条命令，空白分隔）</h2>
 * <pre>
 * 第 1 行            偏移毫秒数（prpr：offset = ms/1000 - 0.15 秒）
 * bp  &lt;beat&gt; &lt;bpm&gt;                 变 BPM（第 1 个参数是「第几拍」，第 2 个才是 BPM）
 * n1 line beat x above fake           Tap
 * n2 line beat endBeat x above fake   Hold
 * n3 line beat x above fake           Flick
 * n4 line beat x above fake           Drag
 * # speed / &amp; size                   修饰「上一条音符」的速度 / 缩放（也可写在 n 行末尾）
 * cv line beat speed                 速度事件（高度 = 它的积分；value/5.85）
 * cp line beat x y                   判定线瞬移（x∈[0,2048]、y∈[0,1400]）
 * cd line beat angle                 旋转（prpr 取负：-angle）
 * ca line beat alpha                 透明度（0..255）
 * cm line startBeat endBeat x y tween      位移插值
 * cr line startBeat endBeat angle tween    旋转插值
 * cf line startBeat endBeat alpha          透明度插值
 * </pre>
 *
 * <h2>坐标换算（和 prpr 对齐，也和本项目的 PGR/RPE 口径对齐）</h2>
 * <ul>
 *   <li>判定线 x：{@code pec_x/2048}（PEC 是 0..2048、中心 1024；我们的层值是 0..1、中心 0.5）；</li>
 *   <li>判定线 y：{@code pec_y/1400}（PEC 0..1400、中心 700）；</li>
 *   <li>音符 x：{@code pec_x/115.2}。PEC 里音符 x 是 -1024..1024 的世界坐标，
 *       而我们的 {@link Chart.Note#x} 用 PGR 的 {@code positionX} 单位
 *       （世界 = x × 0.1125，见 {@code PlayScreen.X_FRAC_PER_UNIT}），所以除以
 *       1024×0.1125 = 115.2；</li>
 *   <li>旋转：取负（prpr {@code pec.rs} 的 {@code -it.take_f32()}），单位是度；</li>
 *   <li>透明度：{@code /255}。</li>
 * </ul>
 *
 * <p><b>容错</b>：任何一行解析失败（参数不足 / 数字不是数字 / 未知命令）只<b>跳过那一行</b>并计数，
 * 不整体失败；坏行比例和条数通过 {@link #parse(String)} 的返回值之外的
 * {@link #lastSkippedLines()} 暴露（headless 清单工具会打印）。
 */
public final class PecParser {

    /** PEC 音符 x 的换算：{@code 1024 × 0.1125}（世界坐标 → PGR positionX 单位）。 */
    private static final double PEC_NOTE_X_SCALE = 1024.0 * 0.1125;

    /** 判定线 x：0..2048 → 0..1。 */
    private static final double PEC_MOVE_X_SCALE = 2048.0;
    /** 判定线 y：0..1400 → 0..1。 */
    private static final double PEC_MOVE_Y_SCALE = 1400.0;
    /** PEC 判定线的横向缩放：{@code pec.rs:166} 的 {@code AnimFloat::fixed(3.91 / 6.)}。 */
    private static final double PEC_LINE_SCALE_X = 3.91 / 6.0;

    /** PEC 的速度单位（prpr {@code -it.take_f32()? / 5.85}）。 */
    private static final double PEC_SPEED_SCALE = 5.85;

    /** 上一次 {@link #parse} 跳过的坏行数（诊断用）。 */
    private static volatile int lastSkipped;
    /** 上一次 {@link #parse} 跳过的第一条坏行的原因（诊断用）。 */
    private static volatile String lastSkipReason = "";

    private PecParser() {
    }

    /** 上次解析跳过了多少行（自测 / 诊断用）。 */
    public static int lastSkippedLines() {
        return lastSkipped;
    }

    /** 上次解析跳过的第一行长什么样（诊断用，空串 = 没跳过）。 */
    public static String lastSkipReason() {
        return lastSkipReason;
    }

    /**
     * 这段文本像不像 PEC：首行是一个数字（偏移毫秒），且前几行里有 {@code bp} / {@code n1} / {@code cv} 命令。
     *
     * <p>JSON（以 {@code {} / {@code [} 开头）一定不是 PEC。只看开头 4KB 也能判（{@link ChartParser}
     * 用这个做文件头的「偷看」）。
     */
    public static boolean looksLikePec(String text) {
        if (text == null) return false;
        int i = 0;
        int n = text.length();
        // 跳过 BOM 与空白
        while (i < n && (text.charAt(i) == '\uFEFF' || Character.isWhitespace(text.charAt(i)))) i++;
        if (i >= n) return false;
        char c = text.charAt(i);
        if (c == '{' || c == '[') return false;
        // 首行必须是数字
        int eol = i;
        while (eol < n && text.charAt(eol) != '\n' && text.charAt(eol) != '\r') eol++;
        String first = text.substring(i, eol).trim();
        try {
            Double.parseDouble(first);
        } catch (NumberFormatException e) {
            return false;
        }
        // 前 8 个非空行里要有 PEC 命令
        int seen = 0;
        int pos = eol;
        while (pos < n && seen < 8) {
            int end = pos;
            while (end < n && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            String line = text.substring(pos, end).trim();
            pos = end + 1;
            if (line.isEmpty()) continue;
            seen++;
            String cmd = firstToken(line);
            if (cmd.equals("bp") || cmd.startsWith("n") || cmd.equals("cv")) return true;
        }
        return false;
    }

    /**
     * 解析 PEC 文本 → {@link Chart}。
     *
     * @throws IllegalArgumentException 连一行偏移都读不出来（真正的空文件 / 乱码）
     */
    public static Chart parse(String source) {
        lastSkipped = 0;
        lastSkipReason = "";
        if (source == null) throw new IllegalArgumentException("PEC 内容是空的");

        String[] lines = source.split("\\r?\\n");
        double offset = 0;
        int firstLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                firstLine = i;
                break;
            }
        }
        if (firstLine < 0) throw new IllegalArgumentException("PEC 里没有任何内容");
        try {
            offset = Double.parseDouble(lines[firstLine].trim()) / 1000.0 - 0.15;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PEC 第一行不是偏移毫秒数：" + cut(lines[firstLine], 40));
        }

        List<double[]> bpms = new ArrayList<>();               // {beat, bpm}
        Map<Integer, RawLine> rawLines = new HashMap<>();      // line id → raw
        RawNote lastNote = null;
        int maxLineId = 0;
        double maxTime = 0;

        for (int i = firstLine + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            String cmd = t[0];
            try {
                switch (cmd) {
                    case "bp" -> {
                        require(t, 3, cmd);
                        bpms.add(new double[]{num(t[1]), num(t[2])});
                    }
                    case "n1", "n3", "n4" -> {
                        require(t, 6, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        RawNote note = new RawNote(noteType(cmd), beatsToSeconds(bpms, num(t[2])),
                                num(t[3]) / PEC_NOTE_X_SCALE, 0);
                        note.above = (int) num(t[4]) == 1;
                        note.fake = (int) num(t[5]) == 1;
                        applyNoteModifiers(note, t, 6);       // pec.rs:281-290 的行内 # / &
                        rl.notes.add(note);
                        lastNote = note;
                        maxTime = Math.max(maxTime, note.time);
                    }
                    case "n2" -> {
                        require(t, 7, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double start = beatsToSeconds(bpms, num(t[2]));
                        double end = beatsToSeconds(bpms, num(t[3]));
                        RawNote note = new RawNote(Chart.Note.Type.HOLD, start,
                                num(t[4]) / PEC_NOTE_X_SCALE, Math.max(0, end - start));
                        note.above = (int) num(t[5]) == 1;
                        note.fake = (int) num(t[6]) == 1;
                        applyNoteModifiers(note, t, 7);       // pec.rs:281-290 的行内 # / &
                        rl.notes.add(note);
                        lastNote = note;
                        maxTime = Math.max(maxTime, Math.max(start, end));
                    }
                    case "#" -> {
                        require(t, 2, cmd);
                        if (lastNote != null) lastNote.speed = num(t[1]);
                    }
                    case "&" -> {
                        require(t, 2, cmd);
                        if (lastNote != null) lastNote.size = num(t[1]);
                    }
                    case "cv" -> {
                        require(t, 4, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        double time = beatsToSeconds(bpms, num(t[2]));
                        rawLine(rawLines, id).speedPts.add(new double[]{time, num(t[3]) / PEC_SPEED_SCALE});
                        maxTime = Math.max(maxTime, time);
                    }
                    case "cp" -> {
                        require(t, 5, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double time = beatsToSeconds(bpms, num(t[2]));
                        double x = num(t[3]) / PEC_MOVE_X_SCALE;
                        double y = num(t[4]) / PEC_MOVE_Y_SCALE;
                        rl.addSingle(rl.moveX, time, x);
                        rl.addSingle(rl.moveY, time, y);
                        maxTime = Math.max(maxTime, time);
                    }
                    case "cd" -> {
                        require(t, 4, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double time = beatsToSeconds(bpms, num(t[2]));
                        rl.addSingle(rl.rotate, time, -num(t[3]));
                        maxTime = Math.max(maxTime, time);
                    }
                    case "ca" -> {
                        require(t, 4, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double time = beatsToSeconds(bpms, num(t[2]));
                        rl.addSingle(rl.alpha, time, alphaValue(num(t[3])));
                        maxTime = Math.max(maxTime, time);
                    }
                    case "cm" -> {
                        require(t, 7, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double t0 = beatsToSeconds(bpms, num(t[2]));
                        double t1 = beatsToSeconds(bpms, num(t[3]));
                        double x = num(t[4]) / PEC_MOVE_X_SCALE;
                        double y = num(t[5]) / PEC_MOVE_Y_SCALE;
                        int tween = (int) num(t[6]);
                        rl.addSegment(rl.moveX, t0, t1, x, tween);
                        rl.addSegment(rl.moveY, t0, t1, y, tween);
                        maxTime = Math.max(maxTime, Math.max(t0, t1));
                    }
                    case "cr" -> {
                        require(t, 6, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double t0 = beatsToSeconds(bpms, num(t[2]));
                        double t1 = beatsToSeconds(bpms, num(t[3]));
                        double angle = -num(t[4]);
                        int tween = (int) num(t[5]);
                        rl.addSegment(rl.rotate, t0, t1, angle, tween);
                        maxTime = Math.max(maxTime, Math.max(t0, t1));
                    }
                    case "cf" -> {
                        require(t, 5, cmd);
                        int id = (int) num(t[1]);
                        maxLineId = Math.max(maxLineId, id);
                        RawLine rl = rawLine(rawLines, id);
                        double t0 = beatsToSeconds(bpms, num(t[2]));
                        double t1 = beatsToSeconds(bpms, num(t[3]));
                        // prpr 的 cf 用 easing id 2（线性）；这里也走线性
                        rl.addSegment(rl.alpha, t0, t1, alphaValue(num(t[4])), 2);
                        maxTime = Math.max(maxTime, Math.max(t0, t1));
                    }
                    default -> skip(line, "未知命令 " + cmd);
                }
            } catch (RuntimeException e) {
                // 单行坏了就跳过这一行，继续解析后面的（需求：不要整体失败）
                skip(line, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
            }
        }

        List<Chart.Line> out = new ArrayList<>(maxLineId + 1);
        double endGuard = Math.max(maxTime, 0) + 1.0;          // pec.rs:351-367：max_time = 最大事件/音符时刻 + 1
        for (int id = 0; id <= maxLineId; id++) {
            RawLine rl = rawLines.get(id);
            if (rl == null) {
                rl = new RawLine();
            }
            List<Chart.Ev> speed = speedEvents(rl.speedPts, endGuard);
            List<Chart.Note> notes = new ArrayList<>(rl.notes.size());
            for (RawNote rn : rl.notes) {
                double height = Chart.heightAt(speed, rn.time);
                notes.add(new Chart.Note(rn.type, rn.time, rn.hold, rn.x, rn.speed, height,
                        rn.above, rn.fake, false,
                        1.0, Double.NEGATIVE_INFINITY, rn.size));
            }
            notes.sort((a, b) -> Double.compare(a.time, b.time));
            // pec.rs:166：PEC 的线统一带 scaleX = 3.91/6（所以 PEC 的线比 RPE/PGR 的短线一截），
            // scaleY 留空 = 1（object.rs:77 now_with_def(1,1)）。以前没乘，线长了 1.53 倍。
            List<Chart.Ev> sx = List.of(new Chart.Ev(0.0, 1e9, PEC_LINE_SCALE_X, PEC_LINE_SCALE_X,
                    PEC_LINE_SCALE_X, PEC_LINE_SCALE_X, 0.0));
            out.add(new Chart.Line(id, bpms.isEmpty() ? 120.0 : bpms.get(0)[1], notes, speed,
                    keyframes(rl.moveX, 0.5), keyframes(rl.moveY, 0.5),
                    keyframes(rl.rotate, 0.0), keyframes(rl.alpha, 1.0),
                    -1, false, false, List.of(), List.of(), List.of(), List.of(),
                    0, false, sx, List.of()));
        }
        // pec.rs:380：PEC 的 ChartSettings.pe_alpha_extension = true
        // （判定线 alpha 为负不是「隐藏」而是 Phigros 的扩展编码，见 core/line.rs:365-385）
        return new Chart("pec", 1, offset, out, true);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 解析途中的一行判定线（事件先收集，速度最后积分成高度）。
     */
    private static final class RawLine {
        final List<RawNote> notes = new ArrayList<>();
        final List<double[]> speedPts = new ArrayList<>();     // {time, speed}
        final List<Seg> moveX = new ArrayList<>();
        final List<Seg> moveY = new ArrayList<>();
        final List<Seg> rotate = new ArrayList<>();
        final List<Seg> alpha = new ArrayList<>();

        /** 瞬移事件（{@code cp} / {@code cd} / {@code ca}）：零长段，值就是 {@code value}。 */
        void addSingle(List<Seg> track, double time, double value) {
            track.add(new Seg(time, time, value, value, 0));
        }

        /**
         * 插值事件（{@code cm} / {@code cr} / {@code cf}）：<b>只记终点值</b>。
         *
         * <p>起点值<b>不能</b>在这里算——它要等 {@link #sanitizeKfs} 排完序裁完重叠之后，
         * 取「前一个关键帧的终值」（{@code pec.rs:115} 的 {@code kfs.last().value}）。
         * 修前这里取的是「文件里前一条同轨道事件的值」，这就是缓动/位置跑偏的根因
         * （详见 {@link PecParser#keyframes} 的注释）。
         */
        void addSegment(List<Seg> track, double t0, double t1, double value, int tween) {
            track.add(new Seg(Math.min(t0, t1), Math.max(t0, t1), Double.NaN, value, tween));
        }
    }

    private static final class RawNote {
        final Chart.Note.Type type;
        final double time;
        final double x;
        final double hold;
        double speed = 1.0;
        double size = 1.0;
        boolean above = true;
        boolean fake;

        RawNote(Chart.Note.Type type, double time, double x, double hold) {
            this.type = type;
            this.time = time;
            this.x = x;
            this.hold = hold;
        }
    }

    /** 一段事件（单点事件 t0 == t1；插值段的 {@code v0} 是占位 {@code NaN}，见 {@link #sanitizeKfs}）。 */
    private record Seg(double t0, double t1, double v0, double v1, int tween) {
    }

    /**
     * {@code ca} / {@code cf} 的 alpha 值 → 我们的 0..1 口径（{@code pec.rs:154-158}）。
     *
     * <p>源码是：
     * <pre>
     * pec.alpha_events.iter_mut().for_each(|it| {
     *     if it.end &gt;= 0.0 { it.end /= 255.; }
     * });
     * </pre>
     * 也就是<b>只对非负值除 255</b>；<b>负数原样保留</b>，因为负 alpha 在 PEC 里是 Phigros 的
     * 扩展编码（{@code core/line.rs:365-385}，PEC 的 {@code pe_alpha_extension = true}，
     * 见 {@code pec.rs:380}）：{@code floor(-a) == 1} 表示整条线（连音符）不画。
     * 实测 {@code 28167_DX超性能フルメタル少女.pec} 有 160 条 {@code ca …, -1}。
     *
     * <p>修前这里无条件 {@code /255}，负值变成 -0.0039：线不画了（和 Phira 一致），
     * 但<b>音符还照画</b>（Phira 是整条线一起跳过的）。
     */
    private static double alphaValue(double raw) {
        return raw >= 0 ? raw / 255.0 : raw;
    }

    private static RawLine rawLine(Map<Integer, RawLine> map, int id) {
        return map.computeIfAbsent(id, k -> new RawLine());
    }

    /**
     * 速度点 → 事件（高度是速度的积分，{@link Chart#heightAt} 的语义）。
     *
     * <h2>单位：为什么这里要乘 {@link Chart#HEIGHT_RATIO}</h2>
     * <p>{@code prpr} 三种格式的「高度单位」是同一把尺子，但换算路径不一样：
     * <ul>
     *   <li>PGR：{@code pgr.rs:105} 把速度积分<b>除以</b> {@code HEIGHT_RATIO}；</li>
     *   <li>RPE：{@code rpe.rs:24} 的 {@code SPEED_RATIO = 10/45/HEIGHT_RATIO} 已经在速度值里除过；</li>
     *   <li>PEC：{@code pec.rs:122-138} {@code parse_speed_events} 直接
     *       {@code height += (time-last_time)*last_speed}，速度是 {@code value/5.85}
     *       <b>不做任何 HEIGHT_RATIO 换算</b>。</li>
     * </ul>
     * 我们的 {@link Chart#heightAt} 统一在最后除 {@code HEIGHT_RATIO}（PGR / RPE 都靠它对齐），
     * 所以 PEC 必须在速度值上先乘回去，除完才是 {@code prpr} 的原始积分。
     *
     * <p><b>修前</b>没乘，导致 PEC 音符「离判定线的距离」比 Phira 大
     * {@code 1/0.83175 = 1.2023} 倍（音符看起来飘得更高、落得更远）。
     * {@code tools/chart/PecCompare} 的 {@code h 我们/参考} 列可以直接看出来。
     *
     * <h2>尾段：{@code max_time} 之后高度恒定</h2>
     * <p>{@code pec.rs:136} 在 {@code max_time} 处补一个 {@code tween = 0}（Hold）的关键帧，
     * 之后高度<b>恒定</b>；{@link Chart#heightAt} 会按最后一段的斜率一直外推，所以这里补一条
     * 零速度段，把「外推」变成「恒定」。
     */
    private static List<Chart.Ev> speedEvents(List<double[]> pts, double endGuard) {
        List<double[]> sorted = new ArrayList<>(pts);
        sorted.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Chart.Ev> out = new ArrayList<>(sorted.size() + 2);
        if (sorted.isEmpty()) return out;
        if (sorted.get(0)[0] > 1e-9) {
            // pec.rs:123-125：第一个速度点之前插一个 (0, 0)
            sorted.add(0, new double[]{0, 0});
        }
        for (int i = 0; i < sorted.size(); i++) {
            double t0 = sorted.get(i)[0];
            double v = sorted.get(i)[1] * Chart.HEIGHT_RATIO;
            double t1 = i + 1 < sorted.size() ? sorted.get(i + 1)[0] : Math.max(endGuard, t0 + 1);
            if (t1 < t0) t1 = t0;
            out.add(new Chart.Ev(t0, t1, v, v, 0, 0, 0));
        }
        Chart.Ev last = out.get(out.size() - 1);
        if (last.t1 < endGuard) {
            out.set(out.size() - 1, new Chart.Ev(last.t0, endGuard, last.v0, last.v0, 0, 0, 0));
        }
        // pec.rs:136：max_time 之后恒定（速度 0 ⇒ 高度不再增长）
        out.add(new Chart.Ev(endGuard, endGuard + 1e7, 0, 0, 0, 0, 0));
        return out;
    }

    /**
     * 事件列表 → {@link Chart.Ev}：<b>照 {@code pec.rs:104-120} 的 keyframe 序列逐段求值</b>。
     *
     * <h2>这才是「缓动丢失」的真正来源（修前 / 修后）</h2>
     * {@code prpr} 的 {@code parse_events} 先把事件按 {@code (end_time, start_time)} 排序、把重叠段
     * 裁到上一段结尾（{@code pec.rs:84-102} {@code sanitize_events}），<b>然后</b>才生成关键帧：
     * <pre>
     * for e in events {                                  // 已排序、已裁剪
     *     if e.start_time == e.end_time {
     *         kfs.push(Keyframe::new(e.start_time, e.end, 0));                 // 瞬移
     *     } else {
     *         kfs.push(Keyframe::new(e.start_time, kfs.last().value, e.easing)); // ← 起点值 = 上一个关键帧的终值
     *         kfs.push(Keyframe::new(e.end_time,   e.end,           0));
     *     }
     * }
     * </pre>
     * 关键字是 <b>{@code kfs.last().value}</b>——插值段的<b>起点值</b>取「排序裁切之后的前一个关键帧」，
     * 而<b>不是</b>文件里前一条同轨道事件的值。
     *
     * <p><b>修前的做法</b>：{@link #addSegment} 在<b>解析时按文件顺序</b>拿
     * {@code track.get(track.size()-1).v1} 当起点值。PEC 生成器（BPM/拍号转换、手工修谱）经常把
     * {@code cp}（瞬移）和 {@code cm}（插值）交错导出，同一轨道在<b>文件里的先后</b>和
     * <b>按结束时刻排序后的先后</b>并不一致。{@code 6954_-1.pec} 的 line 18 就是活例子：
     * <pre>
     * cp 18 187.000 …     ← 文件里在 cm 之前
     * cp 18 191.250 …
     * cm 18 164.000 170.000 1180 306 20   ← 按 end_time 排，它应该排在 164 那段
     * cm 18 171.000 176.000 1724 1600 19
     * cm 18 191.250 194.000  784 1170  1  ← 起点值必须是 cp@191.25 的值，修前拿到的是上一条 cm 的终值
     * </pre>
     * 结果就是插值从一个<b>完全无关的值</b>出发：位置/角度整段跑偏，看起来就是「缓动丢了 / 不平滑」。
     *
     * <p><b>修后</b>：{@link #addSegment} 只记「终点值 + tween」，起点值等排序裁切完再按
     * {@code kfs.last().value} 补上（下面是 {@code sanitizeKfs} 做的事）——和
     * {@link RpeParser} 对 RPE 的多段轨道模型完全同构。
     *
     * @param def 首个关键帧之前的值（对齐 {@code prpr} 的 {@code AnimFloat::default().now()} 口径；
     *            {@code prpr} 在「第一条就是插值段」时是 {@code bail!}，我们退化成这个默认值，
     *            属于「比源码宽松」的容错，见类注释）
     */
    private static List<List<Chart.Ev>> keyframes(List<Seg> segs, double def) {
        List<Chart.Ev> evs = sanitizeKfs(segs, def);
        return evs.isEmpty() ? List.of() : List.of(evs);
    }

    /**
     * {@code pec.rs:84-120}：排序 → 裁重叠 → 逐段生成关键帧（起点值 = 上一关键帧的终值）。
     */
    private static List<Chart.Ev> sanitizeKfs(List<Seg> segs, double def) {
        if (segs.isEmpty()) return List.of();
        // pec.rs:85：按 (end_time, start_time) 排序
        List<Seg> sorted = new ArrayList<>(segs);
        sorted.sort((a, b) -> {
            int c = Double.compare(a.t1, b.t1);
            return c != 0 ? c : Double.compare(a.t0, b.t0);
        });
        List<Chart.Ev> out = new ArrayList<>(sorted.size());
        double lastEnd = Double.NEGATIVE_INFINITY;
        double prev = def;                                  // = kfs.last().value（首段之前退化成 def）
        for (Seg s : sorted) {
            double t0 = s.t0;
            if (t0 < lastEnd) t0 = lastEnd;                  // pec.rs:89-98：重叠段裁到上一段结尾
            double t1 = Math.max(t0, s.t1);
            lastEnd = Math.max(lastEnd, t1);
            out.add(new Chart.Ev(t0, t1, prev, s.v1, 0, 0, 0, tweenOf(s.tween)));
            prev = s.v1;
        }
        return out;
    }

    /** PEC 的 tween 编号和 RPE 是同一张表；线性（id 2）返回 null（走 {@link Chart.Ev#lerp} 的快路径）。 */
    private static Chart.Tween tweenOf(int pecTween) {
        int id = RpeParser.tweenId(pecTween);
        return id == 2 ? null : x -> RpeParser.tween(id, x);
    }

    /**
     * 拍号 → 秒：{@code bp} 表是分段的（{@code BpmList} 的口径，
     * 见 {@code prpr/src/core.rs:112-145}）：取最后一个 {@code beat <= 目标} 的段，
     * {@code time + (beats - startBeat) * 60/bpm}。
     */
    private static double beatsToSeconds(List<double[]> bpms, double beats) {
        if (bpms.isEmpty()) return beats;                       // 没有 bp：退化成「1 拍 = 1 秒」
        // 按 beat 排序（PEC 里通常已经有序，这里防御乱序）
        List<double[]> sorted = new ArrayList<>(bpms);
        sorted.sort((a, b) -> Double.compare(a[0], b[0]));
        double time = 0;
        double startBeat = sorted.get(0)[0];
        double bpm = sorted.get(0)[1];
        for (int i = 1; i < sorted.size(); i++) {
            double[] kf = sorted.get(i);
            if (kf[0] > beats) break;
            time += (kf[0] - startBeat) * (60.0 / (bpm <= 0 ? 120.0 : bpm));
            startBeat = kf[0];
            bpm = kf[1];
        }
        return time + (beats - startBeat) * (60.0 / (bpm <= 0 ? 120.0 : bpm));
    }

    /**
     * {@code pec.rs:281-290} 的<b>行内</b>音符修饰：{@code n1 0 4 100 1 0 # 1.2 & 0.8}。
     * 源码在读完 {@code n} 行的参数后还有：
     * <pre>
     * if it.next() == Some("#") { last_note!().speed = it.take_f32()?; }
     * if it.next() == Some("&") { ... note.object.scale.0 = AnimFloat::fixed(size); }
     * </pre>
     * 我们以前只支持<b>独立行</b>的 {@code # 1.2} / {@code & 0.8}（{@code pec.rs:292-301}）。
     * 实测本库 2 个 PEC 包全是独立行写法（行内 0 次），但两种写法语义相同，一并支持。
     */
    private static void applyNoteModifiers(RawNote note, String[] t, int from) {
        for (int i = from; i + 1 < t.length; i++) {
            try {
                if (t[i].equals("#")) {
                    note.speed = num(t[i + 1]);
                    i++;
                } else if (t[i].equals("&")) {
                    note.size = num(t[i + 1]);
                    i++;
                }
            } catch (RuntimeException ignored) {
                // 参数不是数字：忽略这一段修饰，不影响音符本身
            }
        }
    }

    private static Chart.Note.Type noteType(String cmd) {
        return switch (cmd) {
            case "n2" -> Chart.Note.Type.HOLD;
            case "n3" -> Chart.Note.Type.FLICK;
            case "n4" -> Chart.Note.Type.DRAG;
            default -> Chart.Note.Type.TAP;
        };
    }

    private static void require(String[] t, int n, String cmd) {
        if (t.length < n) {
            throw new IllegalArgumentException(cmd + " 需要 " + (n - 1) + " 个参数，只有 " + (t.length - 1) + " 个");
        }
    }

    private static double num(String s) {
        return Double.parseDouble(s);
    }

    private static String firstToken(String line) {
        int i = 0;
        while (i < line.length() && !Character.isWhitespace(line.charAt(i))) i++;
        return line.substring(0, i);
    }

    private static void skip(String line, String reason) {
        lastSkipped++;
        if (lastSkipReason.isEmpty()) lastSkipReason = cut(line, 80) + " → " + reason;
    }

    private static String cut(String s, int n) {
        if (s == null) return "-";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
