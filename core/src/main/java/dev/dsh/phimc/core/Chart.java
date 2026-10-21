package dev.dsh.phimc.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 谱面内存模型（官方 Phigros PGR 与 RPE 共用）。
 *
 * <p>时间单位：<b>秒</b>。PGR 源文件里的「1/32 拍」和 RPE 源文件里的「拍」都由各自的解析器
 * 折成秒以后再进来（{@link ChartParser} / {@link RpeParser}）。
 *
 * <p><b>高度轴（本文件的核心）</b>：Phira 根本不读谱面里每个音符的 {@code floorPosition} 字段，
 * 而是用判定线的 {@code speedEvents} 积分出一条「判定线高度曲线」{@code height(t)}，
 * 再把每个音符的高度取成 {@code height(note.time)}（{@code prpr/src/parse/pgr.rs:213-217} 的
 * {@code height.set_time(time); height.now()}）。线的高度在运行时用同一条曲线求值
 * （{@code pgr.rs:96-107}，值再除以 {@code HEIGHT_RATIO}，{@code pgr.rs:105}）。
 * 这样「音符高度」与「判定线高度」天然同源，音符在自己的判定时刻必然落在判定线上
 * （{@code Δheight = 0}），这也是渲染不漂移的前提。
 *
 * <p><b>坐标口径</b>：{@link Line#xAt}/{@link Line#yAt} 返回的是「屏幕比例」（0 = 左/下，1 = 右/上），
 * {@link Line#rotateAt} 返回度。世界坐标与屏幕比例的换算是
 * {@code worldX = 2*x - 1}、{@code worldY = (2*y - 1) / aspect}
 * （{@code prpr/src/core/object.rs:53-57} 的 {@code tr.y /= res.aspect_ratio}）。
 *
 * <p><b>父判定线</b>（RPE 的 {@code father}）：照 {@code prpr/src/core/line.rs:211-228}
 * 的 {@code fetch_rot} / {@code fetch_pos} 递归合成。因为世界 y 要除 {@link #aspect}，
 * 合成结果<b>依赖宽高比</b>，所以求值函数是 {@code xAt(sec, aspect)} 这种签名；
 * 不带 aspect 的重载用 16:9（和 {@link Engine#DEFAULT_ASPECT} 一致）。
 */
public final class Chart {

    /**
     * prpr/src/core.rs:16 {@code HEIGHT_RATIO}。Phira 把速度事件积分出来的高度再除以它
     * （{@code pgr.rs:105}），这里保持同一口径，方便逐行对照源码。
     */
    public static final double HEIGHT_RATIO = 0.83175;

    /**
     * 高度（Phira 口径）→ 屏高比例：{@code note.rs:220-223} 里
     * {@code base = (note.height - line.height) / aspect * speed}，而世界 y 到屏幕是
     * 「1 世界单位 = 半屏宽 = {@code aspect/2} 个屏高」⇒ 屏高比例 {@code n = Δheight * speed / 2}。
     */
    public static final double HEIGHT_TO_SCREEN = 0.5;

    /** 默认宽高比（{@code prpr/src/info.rs:79} 的默认 {@code aspect_ratio}）。 */
    public static final double DEFAULT_ASPECT = 16.0 / 9.0;

    /**
     * 谱面来源格式：{@code "pgr"} = 官方 Phigros（{@code formatVersion} + {@code judgeLineList}），
     * {@code "rpe"} = Re:PhiEdit（{@code BPMList} + {@code META}，见 {@link RpeParser}），
     * {@code "pec"} = PEC 纯文本（见 {@link PecParser}）。
     */
    public final String format;
    public final int formatVersion;
    /** 谱面延迟，秒。正数表示谱面比音乐快。 */
    public final double offset;
    public final List<Line> lines;
    /** 最后一个音符 / hold 尾的结束时间，秒。 */
    public final double duration;

    /**
     * 判定线<b>绘制顺序</b>（{@code lines} 的下标，已按 {@code (zOrder, index)} 升序排好）。
     *
     * <p>照 {@code prpr/src/core/chart.rs:46-56}：先把 {@code attachUI} 的线摘掉
     * （源码注释 "lines with attach_ui will be removed from this list"），
     * 再 {@code order.sort_by_key(|it| (lines[*it].z_index, *it))}。
     * 渲染层必须按这个顺序画，否则叠在一起的线前后关系与 Phira 不一致
     * （实测曲库里 {@code 15927 / 39461 / 42810 / 48273 / 52943 / 53092 / 53239 / 78805}
     * 这 8 首的 zOrder 不是按下标递增的）。
     *
     * <p>判定 / 计分完全不读它。
     */
    public final int[] drawOrder;

    /**
     * {@code attachUI} 元素 → 判定线下标（{@code chart.rs:45-55} 的 {@code attach_ui: [Option<usize>; 7]}）。
     *
     * <p>下标 0..6 对应 {@code UIElement} 的 1..7：
     * {@code 0=pause, 1=comboNumber, 2=combo, 3=score, 4=bar, 5=name, 6=level}
     * （{@code core/line.rs:16-24} + {@code #[serde(rename_all = "lowercase")]}）。
     * {@code -1} = 这首谱没给这个元素指定判定线，按默认位置画。
     *
     * <p>Phira 的用法见 {@code chart.rs:72-96} 的 {@code with_element}：如果给了线，就把该 UI 元素
     * 画在<b>那条线的位置</b>，并乘上它的旋转 / scale / alpha / 颜色；没给就画在默认位置。
     * 判定 / 计分不读它。
     */
    public final int[] attachUiLines;

    /**
     * {@code ChartSettings.pe_alpha_extension}（{@code core/chart.rs:19}）：
     * {@code true} 时「判定线 alpha 为负数」不是「隐藏」，而是一套扩展编码
     * （{@code core/line.rs:365-385}）。只有 PEC 会置 true（{@code parse/pec.rs:380}）。
     */
    public final boolean peAlphaExtension;

    /** 官方 PGR 格式（保持原构造签名，老调用方不用改）。 */
    public Chart(int formatVersion, double offset, List<Line> lines) {
        this("pgr", formatVersion, offset, lines);
    }

    public Chart(String format, int formatVersion, double offset, List<Line> lines) {
        this(format, formatVersion, offset, lines, false);
    }

    public Chart(String format, int formatVersion, double offset, List<Line> lines,
                 boolean peAlphaExtension) {
        this.format = format;
        this.formatVersion = formatVersion;
        this.offset = offset;
        this.peAlphaExtension = peAlphaExtension;
        this.lines = List.copyOf(lines);
        // 父判定线要能递归求值，所以在这里把 index → Line 的引用接上（并挡掉环，
        // prpr/src/parse/rpe.rs:915-931 对环是直接报错，我们这里退化成「无父线」更耐操）
        for (Line l : this.lines) l.linkParent(this.lines);
        for (Line l : this.lines) l.breakCycle();
        double d = 0;
        for (Line l : this.lines) {
            d = Math.max(d, l.endTime());
        }
        this.duration = d;
        this.drawOrder = buildDrawOrder(this.lines);
        this.attachUiLines = buildAttachUi(this.lines);
    }

    /** {@code chart.rs:45-55}：把「有 attachUI 的线」按元素编号登记下来（后出现的覆盖先出现的）。 */
    private static int[] buildAttachUi(List<Line> lines) {
        int[] out = new int[7];
        java.util.Arrays.fill(out, -1);
        for (int i = 0; i < lines.size(); i++) {
            int e = lines.get(i).attachUi;
            if (e >= 1 && e <= 7) out[e - 1] = i;
        }
        return out;
    }

    /** {@code chart.rs:46-56}：摘掉 attachUI 的线，再按 {@code (z_index, index)} 升序。 */
    private static int[] buildDrawOrder(List<Line> lines) {
        List<Integer> order = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).attachUi == 0) order.add(i);
        }
        order.sort((a, b) -> {
            int c = Integer.compare(lines.get(a).zIndex, lines.get(b).zIndex);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[order.size()];
        for (int i = 0; i < out.length; i++) out[i] = order.get(i);
        return out;
    }

    public int noteCount() {
        int n = 0;
        for (Line l : lines) n += l.notes.size();
        return n;
    }

    /**
     * 判定线高度曲线的求值：{@code height(t)}，单位 = Phira 的「高度单位」（已经除过
     * {@link #HEIGHT_RATIO}）。
     *
     * <p>照 {@code pgr.rs:96-107} 的 keyframe 构造方式：每个速度事件只在自己的
     * {@code startTime} 处放一个 keyframe，值是「之前所有事件的累计高度」；
     * 相邻两个 keyframe 之间线性插值，最后一段按最后一个事件的值线性外推。
     * 所以段内的斜率 = {@code (endTime有 - startTime) * value / (下一个 startTime - 本 startTime)}，
     * 事件首尾相接时正好等于 {@code value}。
     */
    public static double heightAt(List<Ev> speed, double sec) {
        int m = speed.size();
        if (m == 0) {
            // AnimFloat::default().now() == 0（prpr core/anim.rs:141-145）：没有速度事件 ⇒ 高度恒为 0
            return 0.0;
        }
        if (sec < speed.get(0).t0) return 0.0;
        double pos = 0.0;                       // 当前 keyframe 的累计值
        for (int i = 0; i < m; i++) {
            Ev e = speed.get(i);
            double nextT = (i + 1 < m) ? speed.get(i + 1).t0 : Double.POSITIVE_INFINITY;
            if (sec < nextT) {
                if (i + 1 < m) {
                    double span = nextT - e.t0;
                    double f = span > 0 ? (sec - e.t0) / span : 1.0;
                    double nextPos = pos + (e.t1 - e.t0) * e.v0;
                    return (pos + (nextPos - pos) * f) / HEIGHT_RATIO;
                }
                // 最后一段：从 last.startTime 起按 last.value 线性外推（pgr.rs:101-103）
                return (pos + (sec - e.t0) * e.v0) / HEIGHT_RATIO;
            }
            pos += (e.t1 - e.t0) * e.v0;
        }
        return pos / HEIGHT_RATIO;
    }

    public static final class Line {
        public final int index;
        public final double bpm;
        public final List<Note> notes;
        /** 速度事件（合成成一条：高度是它的积分，{@link Chart#heightAt} 只吃一条）。 */
        public final List<Ev> speed;
        /**
         * 下面四组是「每条事件层一条轨道」（RPE 的 {@code eventLayers} 各层<b>相加</b>，
         * {@code anim.rs:27-33}）；PGR 只有一层。求和放在求值时做，
         * 这样多层 + 非线性缓动也能精确表达（不像折线化那样有误差）。
         */
        public final List<List<Ev>> moveX;
        public final List<List<Ev>> moveY;
        public final List<List<Ev>> rotate;
        public final List<List<Ev>> alpha;

        /** 父判定线下标（{@code rpe.rs:176} 的 {@code father}，-1 = 无）。 */
        public final int parentIndex;
        /** 子线的自转是否叠加父线旋转（{@code rpe.rs:178}，{@code line.rs:211-219}）。 */
        public final boolean rotateWithFather;
        /**
         * RPE 的 {@code isCover != 1}（{@code rpe.rs:821} {@code show_below}）。
         * {@code false} = 判定线下面的音符不画（note.rs:236-241），三个样本都是 false。
         */
        public final boolean showBelow;

        /**
         * RPE 的 4 条控制器轨道（{@code rpe.rs:727-732} 的 {@code CtrlObject}）。
         * 自变量不是时间，而是「音符相对判定线的高度」× RPE_HEIGHT/2（{@code note.rs:171-173}）：
         * {@code alpha} 乘在音符透明度上、{@code size} 乘在缩放上、{@code pos} 乘在横向位移上、
         * {@code y} 乘在下落速度上（{@code note.rs:181/185-189/218}）。
         * 没有事件时一律取 1。
         */
        public final List<Ev> ctrlAlpha;
        public final List<Ev> ctrlSize;
        public final List<Ev> ctrlPos;
        public final List<Ev> ctrlY;

        /**
         * RPE 的 {@code attachUI}（{@code rpe.rs:185}，1..7）：这条线是<b>贴到 UI 层</b>的元素，
         * Phira 会把它从「按 z 排序的判定线列表」里摘出去（{@code chart.rs:33-38} 的注释：
         * "lines with attach_ui will be removed from this list"），改由 UI 层按屏幕坐标画。
         * 我们不做 UI 层，所以这类线**不画**（以前会当成普通判定线画成一条横贯屏幕的亮条）。
         * 0 = 普通判定线。
         */
        public final int attachUi;

        /**
         * {@code Texture} 不是 {@code line.png}（{@code rpe.rs:740-806}）：Phira 把这种线画成
         * <b>那张图本身</b>（{@code line.rs:254-272} 的 {@code JudgeLineKind::Texture}），
         * 不是判定线条。我们没有图集/图尺寸，所以也不画（不画成横条）。
         */
        public final boolean imageLine;

        /**
         * {@code extended.scaleX / scaleY}（{@code rpe.rs:686-725}，作用见 {@code line.rs:246} 的
         * {@code now_scale}）：横向缩放 = 判定线<b>长度</b>，纵向 = <b>厚度</b>，默认 1。
         * 单个数组（不是按事件层），所以这里是单轨道。
         */
        public final List<Ev> scaleX;
        public final List<Ev> scaleY;

        /** {@link #kind}：普通判定线（{@code line.png}，{@code line.rs:248-253}）。 */
        public static final int KIND_NORMAL = 0;
        /** {@link #kind}：文字线（{@code textEvents}，{@code line.rs:291-298}）。我们不画。 */
        public static final int KIND_TEXT = 1;
        /** {@link #kind}：绘制线（{@code paintEvents}，{@code line.rs:299-356}）。我们画不了。 */
        public static final int KIND_PAINT = 2;
        /** {@link #kind}：贴图 / GIF 线（{@code line.rs:254-290}）。我们画不了。 */
        public static final int KIND_TEXTURE = 3;

        /**
         * {@code rpe.rs:740-806} 的 {@code JudgeLineKind}：
         * 只有 {@code Texture == "line.png"} 且没有 {@code paint/textEvents} 才是「一条判定线」。
         * 其它三种 Phira 画的是<b>图 / 文字 / 全屏绘制</b>，不是横条——我们照旧不画，
         * 但绝不能再画成一条横贯屏幕的亮条（那是最刺眼的一种「观感不对」）。
         */
        public final int kind;

        /**
         * {@code zOrder}（{@code rpe.rs:820} {@code z_index}）：绘制分层。
         * 只有 {@link Chart#drawOrder} 会用到它，判定 / 计分不读。
         */
        public final int zIndex;

        /**
         * {@code Texture} 字段原值（{@code rpe.rs:740-806}）。
         * {@code "line.png"} / 空 = 普通判定线；其它 = 这条线要画成<b>那张图本身</b>
         * （{@code line.rs:254-290} 的 {@code JudgeLineKind::Texture}）。
         * 渲染层拿它去谱面包里找同名图片。
         */
        public final String textureName;

        /**
         * {@code extended.inclineEvents}（{@code rpe.rs:734-738}）。
         *
         * <p>Phira 把它变成 {@code line.incline}（{@code line.rs:145}），渲染时取
         * {@code incline_sin = sin(incline.to_radians())}（{@code line.rs:363}），
         * 然后 <b>乘在音符的横向坐标上</b>（{@code note.rs:176-182}）：
         * <pre>
         * incline_val = 1 - incline_sin * (base * aspect + translation.y) * RPE_HEIGHT / 2 / 360
         * tr.x *= incline_val * ctrl_obj.pos     // hold 例外：直接乘 1
         * </pre>
         * 效果是「音符越远离判定线，横向越往外张/收」——也就是<b>倾斜下落</b>。
         *
         * <p><b>实测</b>：曲库里 30 首谱共 3169 条线带这个字段，但**非零值 0 个**（全是 0.0），
         * 所以对现有谱面没有像素影响；接通是为了以后的新谱面。
         * 单位是<b>度</b>（{@code line.rs:363} 的 {@code .to_radians()}）。
         */
        public final List<Ev> incline;

        /**
         * {@code extended.colorEvents}（{@code rpe.rs:806-810}）。
         *
         * <p>Phira 的判定线颜色 = {@code self.color.now_opt().unwrap_or(res.judge_line_color)}
         * （{@code line.rs:238,249}）：<b>谱面给了 colorEvents 就完全用谱面的颜色</b>，
         * 不再用 FC/AP 指示色。事件值是 {@code [r,g,b]}（0..255），alpha 恒 255
         * （{@code rpe.rs:122-128} {@code RGBColor → Color::from_rgba(r,g,b,255)}）。
         * 三条轨道共享同一个 tween（{@code Color} 的 {@code Tweenable} 逐通道插值）。
         *
         * <p>空列表 = 没有 colorEvents（用指示色）。
         */
        public final List<Ev> colorR;
        public final List<Ev> colorG;
        public final List<Ev> colorB;

        /**
         * {@code extended.textEvents}（{@code rpe.rs:746-748} 的 {@code JudgeLineKind::Text}）。
         *
         * <p><b>文本本身就是一个「事件轨道」</b>：{@code RPEEvent<String>} 的 {@code start}/{@code end}
         * 是<b>字符串</b>，`prpr` 用 {@code parse_events(r, events, Some(String::new()), ...)} 把它建成
         * {@code Anim<String>}（和其它轨道同构：关键帧 + 段内 {@code Tweenable::tween}），
         * 绘制时取 {@code anim.now()}（{@code line.rs:294}）。
         * {@code Tweenable for String}（{@code tween.rs:582-631}）实现了打字机效果：
         * 空串 → 文本是「逐字出现」，文本 → 空串是「逐字消失」，前缀相同则按长度插值。
         *
         * <p><b>没有</b>「位置 / 锚点 / 字号 / 颜色」字段——Phira 画文字的位置固定是判定线原点、
         * 锚点固定居中、字号固定 {@code size(1.)}、颜色取判定线颜色（{@code line.rs:291-298}）。
         * JSON 里的 {@code font} 键 {@code prpr} 根本不解析（{@code RPEEvent<String>} 里没有这个字段）。
         */
        public final List<TextEv> text;

        /** 解析后接上的父线（{@link Chart} 构造时填）。 */
        private Line parentLine;

        /**
         * 当前时刻要显示的文字；这条线没有 textEvents 时返回 {@code null}。
         *
         * <p>求值口径同 {@link Ev#valueAt}（取「最后一个 {@code t0 <= sec} 的段」，
         * 段内按缓动求参数再走 {@link Chart#tweenText}；落在段后保持终值）。
         */
        public String textAt(double sec) {
            int n = text.size();
            if (n == 0) return null;
            TextEv first = text.get(0);
            if (sec < first.t0) return first.v0;
            int lo = 0;
            int hi = n - 1;
            int idx = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (text.get(mid).t0 <= sec) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            TextEv e = text.get(idx);
            if (e.t1 <= e.t0 || sec > e.t1) return e.v1;
            double f = (sec - e.t0) / (e.t1 - e.t0);
            if (e.tween != null) f = e.tween.y(f);
            return tweenText(e.v0, e.v1, f);
        }

        public Line(int index, double bpm, List<Note> notes,
                    List<Ev> speed, List<List<Ev>> moveX, List<List<Ev>> moveY,
                    List<List<Ev>> rotate, List<List<Ev>> alpha) {
            this(index, bpm, notes, speed, moveX, moveY, rotate, alpha,
                    -1, false, false, List.of(), List.of(), List.of(), List.of(),
                    0, false, List.of(), List.of());
        }

        public Line(int index, double bpm, List<Note> notes,
                    List<Ev> speed, List<List<Ev>> moveX, List<List<Ev>> moveY,
                    List<List<Ev>> rotate, List<List<Ev>> alpha,
                    int parentIndex, boolean rotateWithFather, boolean showBelow,
                    List<Ev> ctrlAlpha, List<Ev> ctrlSize, List<Ev> ctrlPos, List<Ev> ctrlY) {
            this(index, bpm, notes, speed, moveX, moveY, rotate, alpha,
                    parentIndex, rotateWithFather, showBelow, ctrlAlpha, ctrlSize, ctrlPos, ctrlY,
                    0, false, List.of(), List.of());
        }

        public Line(int index, double bpm, List<Note> notes,
                    List<Ev> speed, List<List<Ev>> moveX, List<List<Ev>> moveY,
                    List<List<Ev>> rotate, List<List<Ev>> alpha,
                    int parentIndex, boolean rotateWithFather, boolean showBelow,
                    List<Ev> ctrlAlpha, List<Ev> ctrlSize, List<Ev> ctrlPos, List<Ev> ctrlY,
                    int attachUi, boolean imageLine, List<Ev> scaleX, List<Ev> scaleY) {
            this(index, bpm, notes, speed, moveX, moveY, rotate, alpha,
                    parentIndex, rotateWithFather, showBelow, ctrlAlpha, ctrlSize, ctrlPos, ctrlY,
                    attachUi, imageLine, scaleX, scaleY, 0,
                    imageLine ? KIND_TEXTURE : KIND_NORMAL, List.of(), List.of(), List.of(), "",
                    List.of(), List.of());
        }

        /**
         * 完整构造（RPE 解析器的出口）。
         *
         * @param kind    见 {@link #KIND_NORMAL}：{@code line.png} + 无 text/paint 才是普通判定线
         * @param zIndex  {@code zOrder}，绘制分层（{@code chart.rs:56}）
         * @param colorR/G/B {@code colorEvents} 的三条轨道，空表示「没有 colorEvents」
         */
        public Line(int index, double bpm, List<Note> notes,
                    List<Ev> speed, List<List<Ev>> moveX, List<List<Ev>> moveY,
                    List<List<Ev>> rotate, List<List<Ev>> alpha,
                    int parentIndex, boolean rotateWithFather, boolean showBelow,
                    List<Ev> ctrlAlpha, List<Ev> ctrlSize, List<Ev> ctrlPos, List<Ev> ctrlY,
                    int attachUi, boolean imageLine, List<Ev> scaleX, List<Ev> scaleY,
                    int zIndex, int kind, List<Ev> colorR, List<Ev> colorG, List<Ev> colorB,
                    String textureName, List<TextEv> text, List<Ev> incline) {
            this.index = index;
            this.bpm = bpm <= 0 ? 120.0 : bpm;
            this.notes = List.copyOf(notes);
            this.speed = List.copyOf(speed);
            this.moveX = copyLayers(moveX);
            this.moveY = copyLayers(moveY);
            this.rotate = copyLayers(rotate);
            this.alpha = copyLayers(alpha);
            this.parentIndex = parentIndex;
            this.rotateWithFather = rotateWithFather;
            this.showBelow = showBelow;
            this.ctrlAlpha = List.copyOf(ctrlAlpha);
            this.ctrlSize = List.copyOf(ctrlSize);
            this.ctrlPos = List.copyOf(ctrlPos);
            this.ctrlY = List.copyOf(ctrlY);
            this.attachUi = attachUi;
            this.imageLine = imageLine;
            this.scaleX = List.copyOf(scaleX);
            this.scaleY = List.copyOf(scaleY);
            this.zIndex = zIndex;
            this.kind = kind;
            this.colorR = List.copyOf(colorR);
            this.colorG = List.copyOf(colorG);
            this.colorB = List.copyOf(colorB);
            this.textureName = textureName == null ? "" : textureName;
            this.text = List.copyOf(text == null ? List.of() : text);
            this.incline = List.copyOf(incline == null ? List.of() : incline);
        }

        /**
         * 这条线该不该按「一条判定线」画出来。
         *
         * <p>四种情况 Phira 都不画成横条：{@code attachUI}（UI 元素，{@code chart.rs:46-55}）、
         * 非 {@code line.png} 贴图（画的是图，{@code line.rs:254-290}）、
         * 有 {@code textEvents}（画文字，{@code line.rs:291-298}）、
         * 有 {@code paintEvents}（画全屏圆形绘制，{@code line.rs:299-356}）。
         */
        public boolean drawsAsBar() {
            return attachUi == 0 && !imageLine && kind != KIND_TEXT && kind != KIND_PAINT;
        }

        /** 有没有 {@code colorEvents}（{@code line.rs:238-249} 用谱面颜色替换指示色）。 */
        public boolean hasColor() {
            return !colorR.isEmpty();
        }

        /** 谱面给的红通道（0..1）；没有 colorEvents 时返回 {@code NaN}（调用方用指示色）。 */
        public double colorAt(double sec, int channel) {
            List<Ev> t = switch (channel) {
                case 0 -> colorR;
                case 1 -> colorG;
                default -> colorB;
            };
            return Ev.valueAt(t, sec, Double.NaN);
        }

        /**
         * {@code rpe.rs:695-696}：{@code line.png} 的 scale 默认是 <b>1</b>；
         * 其它贴图线的默认是 {@code factor = 2 / RPE_WIDTH}（像素 → 世界单位）。
         * 修前一律按 1 兜底，对贴图线来说偏大 675 倍（1350/2）——只有开始画贴图线才会暴露。
         */
        private double scaleDefault() {
            return imageLine ? 2.0 / RpeParser.RPE_WIDTH : 1.0;
        }

        /** 判定线倾斜角（度，{@code line.rs:363} {@code incline.now_opt().unwrap_or_default()}）；没有事件时 0。 */
        public double inclineAt(double sec) {
            return Ev.valueAt(incline, sec, 0.0);
        }

        /** 横向缩放（长度倍率，{@code line.rs:246}）；没有事件时见 {@link #scaleDefault()}。 */
        public double scaleXAt(double sec) {
            return Ev.valueAt(scaleX, sec, scaleDefault());
        }

        /** 纵向缩放（厚度倍率，{@code line.rs:246}）；没有事件时见 {@link #scaleDefault()}。 */
        public double scaleYAt(double sec) {
            return Ev.valueAt(scaleY, sec, scaleDefault());
        }

        private static List<List<Ev>> copyLayers(List<List<Ev>> layers) {
            if (layers == null || layers.isEmpty()) return List.of();
            List<List<Ev>> out = new ArrayList<>(layers.size());
            for (List<Ev> l : layers) out.add(List.copyOf(l));
            return List.copyOf(out);
        }

        void linkParent(List<Line> all) {
            if (parentIndex >= 0 && parentIndex < all.size() && parentIndex != index) {
                parentLine = all.get(parentIndex);
            }
        }

        /** 父链有环时把父线摘掉（{@code rpe.rs:915-931} 是直接报错，这里选择能跑）。 */
        void breakCycle() {
            Line cur = parentLine;
            for (int i = 0; i < 64 && cur != null; i++) {
                if (cur == this) {
                    parentLine = null;
                    return;
                }
                cur = cur.parentLine;
            }
        }

        /** 一个「1/32 拍」是多少秒。 */
        public double unit() {
            return 60.0 / (32.0 * bpm);
        }

        public double endTime() {
            double t = 0;
            for (Note n : notes) t = Math.max(t, n.time + n.hold);
            return t;
        }

        // ---------------------------------------------------------- 变换求值

        /**
         * 判定线（含父链）的世界坐标：{@code out = {worldX, worldY}}（y 向上）。
         *
         * <p>照 {@code line.rs:221-228}：
         * <pre>
         * fetch_pos(self) = if 有父线 { fetch_pos(父) + R(fetch_rot(父)) · now_translation(self) }
         *                   else    { now_translation(self) }
         * </pre>
         * 其中 {@code now_translation = (2x-1, (2y-1)/aspect)}（{@code object.rs:53-57}：
         * {@code tr.y /= aspect}）。这一步<b>必须</b>按 aspect 做，否则父线一旋转，
         * 子线的纵偏移就会随窗口比例缩放错。
         */
        private void worldPos(double sec, double aspect, double[] out) {
            double lx = layerValue(moveX, sec, 0.5) * 2.0 - 1.0;
            double ly = (layerValue(moveY, sec, 0.5) * 2.0 - 1.0) / aspect;
            Line p = parentLine;
            if (p == null) {
                out[0] = lx;
                out[1] = ly;
                return;
            }
            double[] pw = new double[2];
            p.worldPos(sec, aspect, pw);
            double th = Math.toRadians(p.rotateAt(sec));
            double c = Math.cos(th);
            double s = Math.sin(th);
            out[0] = pw[0] + lx * c - ly * s;
            out[1] = pw[1] + lx * s + ly * c;
        }

        /** 合成后的横向屏幕比例（0 = 左边缘，1 = 右边缘）。 */
        public double xAt(double sec, double aspect) {
            double[] p = new double[2];
            worldPos(sec, aspect, p);
            return (p[0] + 1.0) / 2.0;
        }

        /** 合成后的纵向屏幕比例（0 = 底边，1 = 顶边，和 {@code PlayScreen} 的 {@code 1-yAt} 一致）。 */
        public double yAt(double sec, double aspect) {
            double[] p = new double[2];
            worldPos(sec, aspect, p);
            return (p[1] * aspect + 1.0) / 2.0;
        }

        /** 不带 aspect 的重载：按 16:9 求值（无父线时与带 aspect 的完全等价）。 */
        public double xAt(double sec) {
            return xAt(sec, DEFAULT_ASPECT);
        }

        public double yAt(double sec) {
            return yAt(sec, DEFAULT_ASPECT);
        }

        /** 角度（度），已含 {@code rotateWithFather} 的父线叠加（{@code line.rs:211-219}）。 */
        public double rotateAt(double sec) {
            double r = layerValue(rotate, sec, 0.0);
            if (rotateWithFather && parentLine != null) r += parentLine.rotateAt(sec);
            return r;
        }

        public double alphaAt(double sec) {
            return layerValue(alpha, sec, 1.0);
        }

        /**
         * 多事件层求和求值（{@code anim.rs:27-33}）。空层跳过；一条非空层都没有时返回
         * {@code fallback}（对齐 prpr 的 {@code AnimFloat::default()} / {@code unwrap_or} 口径）。
         */
        public static double layerValue(List<List<Ev>> layers, double t, double fallback) {
            boolean any = false;
            double sum = 0.0;
            for (List<Ev> l : layers) {
                if (l.isEmpty()) continue;
                sum += Ev.valueAt(l, t, 0.0);
                any = true;
            }
            return any ? sum : fallback;
        }

        /**
         * 控制器轨道求值。{@code height} 是「音符相对判定线的高度 × RPE_HEIGHT/2」
         * （{@code note.rs:171-173} 的 {@code set_height}）；轨道为空时返回 1（{@code note.rs:217} 的
         * {@code unwrap_or(1.)}）。
         */
        public static double ctrlAt(List<Ev> ctrl, double height) {
            return Ev.valueAt(ctrl, height, 1.0);
        }

        /** 该线此刻对音符的 4 个乘数：{@code {alpha, size, pos, y}}。 */
        public double[] ctrlAtHeight(double height) {
            return new double[]{
                    ctrlAt(ctrlAlpha, height),
                    ctrlAt(ctrlSize, height),
                    ctrlAt(ctrlPos, height),
                    ctrlAt(ctrlY, height),
            };
        }

        /**
         * 判定线此刻的高度（Phira 高度单位）。音符高度与它相减就是音符相对判定线的位置，
         * 见 {@link Note#height}。
         */
        public double floorPositionAt(double sec) {
            return heightAt(speed, sec);
        }
    }

    public static final class Note {
        public enum Type { TAP, DRAG, HOLD, FLICK }

        public final Type type;
        /** 判定时间，秒。 */
        public final double time;
        /** hold 持续时间，秒（非 hold 为 0）。 */
        public final double hold;
        /** 横向坐标，宽度单位（0.05625 * 屏宽）。 */
        public final double x;
        /** 速度倍率，乘在「音符相对判定线的距离」上（note.rs:218-223 的 spd）。 */
        public final double speed;
        /**
         * 音符高度，Phira 高度单位，= 判定线高度曲线在 {@code time} 处的取值
         * （{@code pgr.rs:213-217} 的 {@code height.set_time(time); height.now()}）。
         * <b>不是</b>谱面 JSON 里的 {@code floorPosition} 字段。
         */
        public final double height;
        /** true = 从上方下落（notesAbove），false = 从下方（notesBelow）。 */
        public final boolean above;
        /** 伪音符：不可击打、不参与判定，但会渲染（judge.rs:307 排除判定，note.rs:237 仍绘制到它的时间点）。 */
        public final boolean fake;

        /**
         * 音符自带透明度（0..1，RPE 的 {@code alpha}/255，{@code rpe.rs:578-583}）。PGR 恒为 1。
         */
        public final double alpha;
        /**
         * 从这个时刻起 {@link #alpha} 生效，之前是 0（RPE 的 {@code visibleTime}，
         * {@code rpe.rs:578-587}）。{@code Double.NEGATIVE_INFINITY} = 一直可见。
         *
         * <p>语义照源码抄：{@code visible_time >= time} 时是静态透明度，否则是
         * 「{@code time - visible_time} 之前全透明、之后跳到 {@code alpha}」。
         * 真实谱面里 fake 音符普遍用它做「到点闪一下」的效果（Cyberneko 1455 个 fake
         * 音符的 visibleTime 全是 0.1–0.2s），照着做画面才不脏。
         */
        public final double visibleFrom;
        /** 缩放倍率（RPE 的 {@code size}，{@code rpe.rs:595}；PGR 恒为 1）。 */
        public final double size;
        /**
         * 横向判定面积系数（RPE 的 {@code judgeArea}，{@code rpe.rs:604} / {@code judge.rs:581-586}
         * 的 {@code dist = |Δx| / judge_area}；PGR 恒为 1）。
         */
        public final double judgeArea;

        /**
         * 音符的自带颜色（0~1，乘在贴图上）——RPE 的 {@code tint} 三元组，
         * {@code rpe.rs:597} {@code color: note.tint.map_or(WHITE, |[r,g,b]| Color::from_rgba(r,g,b,255))}。
         *
         * <p><b>这是 Phira 里「note 颜色」的唯一来源</b>：{@code note.rs:213-217}
         * {@code let mut color = Color { a: ..., ..self.color }}，最终
         * {@code draw_tex(..., color, ...)} 把顶点色乘在贴图上。PGR 谱面恒为白
         * （{@code pgr.rs:223} {@code color: WHITE}），所以 PGR 包永远是 1/1/1。
         *
         * <p>实测 19 个样本包（15 个 PGR + 4 个 pez）里 {@code tint} 全是 255,255,255 或缺失，
         * 所以这一层对现有谱面<b>不改变任何像素</b>；它存在的意义是「谱面真给了 tint 时与 Phira 一致」。
         * 判定 / 计分完全不读这三个字段。
         */
        public final double tintR;
        public final double tintG;
        public final double tintB;

        public Note(Type type, double time, double hold, double x,
                    double speed, double height, boolean above, boolean fake) {
            this(type, time, hold, x, speed, height, above, fake, true);
        }

        /**
         * @param clampSpeed {@code true} = PGR 口径（{@code pgr.rs:213}：speed 缺省/非正数按 1 处理）；
         *                   {@code false} = RPE 口径（{@code rpe.rs:594} 原样保留，speed=0
         *                   表示音符贴在判定线上，样本里有成百上千个这样的音符）
         */
        public Note(Type type, double time, double hold, double x,
                    double speed, double height, boolean above, boolean fake, boolean clampSpeed) {
            this(type, time, hold, x, speed, height, above, fake, clampSpeed,
                    1.0, Double.NEGATIVE_INFINITY, 1.0, 1.0);
        }

        public Note(Type type, double time, double hold, double x,
                    double speed, double height, boolean above, boolean fake, boolean clampSpeed,
                    double alpha, double visibleFrom, double size) {
            this(type, time, hold, x, speed, height, above, fake, clampSpeed,
                    alpha, visibleFrom, size, 1.0);
        }

        public Note(Type type, double time, double hold, double x,
                    double speed, double height, boolean above, boolean fake, boolean clampSpeed,
                    double alpha, double visibleFrom, double size, double judgeArea) {
            this(type, time, hold, x, speed, height, above, fake, clampSpeed,
                    alpha, visibleFrom, size, judgeArea, 1.0, 1.0, 1.0);
        }

        /**
         * @param tintR 音符自带颜色的红分量（0~1），见 {@link #tintR}
         */
        public Note(Type type, double time, double hold, double x,
                    double speed, double height, boolean above, boolean fake, boolean clampSpeed,
                    double alpha, double visibleFrom, double size, double judgeArea,
                    double tintR, double tintG, double tintB) {
            this.type = type;
            this.time = time;
            this.hold = Math.max(0, hold);
            this.x = x;
            this.speed = clampSpeed
                    ? (speed <= 0 || !Double.isFinite(speed) ? 1.0 : speed)
                    : (Double.isFinite(speed) ? speed : 1.0);
            this.height = height;
            this.above = above;
            this.fake = fake;
            this.alpha = Double.isFinite(alpha) ? Math.max(0.0, Math.min(1.0, alpha)) : 1.0;
            this.visibleFrom = visibleFrom;
            this.size = Double.isFinite(size) && size > 0 ? size : 1.0;
            this.judgeArea = Double.isFinite(judgeArea) && judgeArea > 0 ? judgeArea : 1.0;
            this.tintR = clamp01(tintR);
            this.tintG = clamp01(tintG);
            this.tintB = clamp01(tintB);
        }

        private static double clamp01(double v) {
            return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 1.0;
        }

        public double endTime() {
            return time + hold;
        }

        /** 此刻音符自带的不透明度（含 {@code visibleTime} 的「到点才出现」语义）。 */
        public double alphaAt(double sec) {
            return sec >= visibleFrom ? alpha : 0.0;
        }
    }

    /** 缓动曲线：{@code [0,1] → 实数}（Back/Elastic 会超出 [0,1]，和 Phira 一致）。 */
    public interface Tween {
        double y(double x);
    }

    /**
     * 一段<b>文本</b>事件（{@code extended.textEvents}）：区间 {@code [t0, t1]} 内文字从 {@code v0}
     * 按 {@link #tween} 变成 {@code v1}，段内走 {@link Chart#tweenText}。
     *
     * <p>和 {@link Ev} 同构（{@code prpr} 对 String 用的就是同一个 {@code Anim<T>} 模型），
     * 只是值域是字符串，所以单独一个类型。
     */
    public static final class TextEv {
        public final double t0;
        public final double t1;
        public final String v0;
        public final String v1;
        /** 非线性缓动；{@code null} = 线性。 */
        public final Tween tween;

        public TextEv(double t0, double t1, String v0, String v1, Tween tween) {
            this.t0 = t0;
            this.t1 = t1;
            this.v0 = v0 == null ? "" : v0;
            this.v1 = v1 == null ? "" : v1;
            this.tween = tween;
        }
    }

    /**
     * {@code prpr/src/core/tween.rs:582-631} 的 {@code impl Tweenable for String}——数值插值之外，
     * 它还负责<b>打字机效果</b>，这是文字轨道唯一有意思的地方：
     * <ol>
     *   <li>两边都含 {@code %P%}：去掉标记后按数字插值（进度读数），整数补 {@code %.0f}、否则 {@code %.3f}；</li>
     *   <li>两边都空：空串；</li>
     *   <li>{@code y} 空：等价于把 {@code x}→空 的过程反过来（逐字<b>删除</b>）；</li>
     *   <li>{@code x} 空：取 {@code y} 的前 {@code round(t*len)} 个字（逐字<b>出现</b>）；</li>
     *   <li>{@code y} 以 {@code x} 开头：从 {@code x} 往后补字；</li>
     *   <li>{@code x} 以 {@code y} 开头：从 {@code x} 往回删字；</li>
     *   <li>其它：保持 {@code x}（{@code %P%} 则去掉标记）。</li>
     * </ol>
     * 计数一律按 <b>Unicode 码点</b>（对应 Rust 的 {@code chars()}），不是 UTF-16 单元，
     * 否则 emoji / 增补平面字符的「逐字」边界会和 Phira 不一样。
     */
    public static String tweenText(String x, String y, double t) {
        if (x == null) x = "";
        if (y == null) y = "";
        boolean xp = x.contains("%P%");
        boolean yp = y.contains("%P%");
        if (xp && yp) {
            String xs = x.replace("%P%", "");
            String ys = y.replace("%P%", "");
            if (t >= 1.0) return ys;
            if (t <= 0.0) return xs;
            double a;
            double b;
            try {
                a = Double.parseDouble(xs.trim());
            } catch (NumberFormatException e) {
                a = 0.0;
            }
            try {
                b = Double.parseDouble(ys.trim());
            } catch (NumberFormatException e) {
                b = 0.0;
            }
            double v = a + t * (b - a);
            boolean ints = a == Math.floor(a) && b == Math.floor(b);
            return String.format(java.util.Locale.ROOT, ints ? "%.0f" : "%.3f", v);
        }
        if (x.isEmpty() && y.isEmpty()) return "";
        if (y.isEmpty()) {
            String xs = xp ? x.replace("%P%", "") : x;
            return tweenText(y, xs, 1.0 - t);
        }
        if (x.isEmpty()) {
            int[] cps = y.codePoints().toArray();
            int take = (int) Math.round(t * cps.length);
            if (take <= 0) return "";
            if (take >= cps.length) return y;
            return new String(cps, 0, take);
        }
        int xLen = x.codePointCount(0, x.length());
        int yLen = y.codePointCount(0, y.length());
        if (y.startsWith(x)) {
            int takeNum = (int) Math.floor((yLen - xLen) * t) + xLen;
            int n = Math.max(0, takeNum - xLen);
            int[] cps = y.codePoints().toArray();
            return x + new String(cps, xLen, Math.min(n, cps.length - xLen));
        }
        if (x.startsWith(y)) {
            int takeNum = (int) Math.round((xLen - yLen) * (1.0 - t)) + yLen;
            int n = Math.max(0, takeNum - yLen);
            int[] cps = x.codePoints().toArray();
            return y + new String(cps, yLen, Math.min(n, cps.length - yLen));
        }
        return xp ? x.replace("%P%", "") : x;
    }

    /**
     * 一段事件。默认是线性插值（PGR 全部走这条）；RPE 的非线性缓动挂在 {@link #tween} 上，
     * 求值时 {@code f → tween.y(f)}，和 {@code prpr/src/core/anim.rs:113-121} 的
     * {@code kf1.tween.y(t)} 一一对应。
     */
    public static final class Ev {
        public final double t0;
        public final double t1;
        public final double v0;
        public final double v1;
        public final double v0b;
        public final double v1b;
        /** 仅 speed 事件用：该段起点的 floorPosition（谱面 JSON 自带值，Phira 不读，仅留作诊断）。 */
        public final double floorPosAtStart;
        /** 非线性缓动；{@code null} = 线性。 */
        public final Tween tween;

        public Ev(double t0, double t1, double v0, double v1, double v0b, double v1b, double floorPosAtStart) {
            this(t0, t1, v0, v1, v0b, v1b, floorPosAtStart, null);
        }

        public Ev(double t0, double t1, double v0, double v1, double v0b, double v1b,
                  double floorPosAtStart, Tween tween) {
            this.t0 = t0;
            this.t1 = t1;
            this.v0 = v0;
            this.v1 = v1;
            this.v0b = v0b;
            this.v1b = v1b;
            this.floorPosAtStart = floorPosAtStart;
            this.tween = tween;
        }

        /** 归一化参数（0..1）。 */
        public double frac(double t) {
            double span = t1 - t0;
            if (span <= 0) return 1.0;
            double f = (t - t0) / span;
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            return f;
        }

        public double lerp(double t) {
            if (t1 <= t0) return v1;
            double f = frac(t);
            if (tween != null) f = tween.y(f);
            return v0 + (v1 - v0) * f;
        }

        /**
         * 不做参数夹取的插值：{@code anim.rs:113-121} 在「第一个关键帧之前」是照常拿
         * {@code tween(负参数)} 往外算的（线性就是直线外推，缓动就按缓动曲线算），
         * {@link #valueAt} 只在「时间早于第一条事件」时用它。
         */
        public double lerpUnclamped(double t) {
            if (t1 <= t0) return v1;
            double f = (t - t0) / (t1 - t0);
            if (tween != null) return v0 + (v1 - v0) * tween.y(f);
            return v0 + (v1 - v0) * f;
        }

        public double lerpB(double t) {
            if (t1 <= t0) return v1b;
            double f = frac(t);
            if (tween != null) f = tween.y(f);
            return v0b + (v1b - v0b) * f;
        }

        /**
         * 事件求值。口径对齐 {@code anim.rs:89-121} 的 {@code Anim::set_time/now}：
         * <ol>
         *   <li>取「最后一个 {@code t0 <= sec} 的段」——同一时刻有多段时<b>后一段赢</b>
         *       （源码 {@code cursor} 只会往后走，边界上落在后一个关键帧）；</li>
         *   <li>段内按缓动插值；</li>
         *   <li>落在两段之间的空档里就保持前一段的终值（源码里那是 tween 0 = Hold）；</li>
         *   <li>比第一段还早就按第一段外推（见 {@link #lerpUnclamped}）。</li>
         * </ol>
         */
        public static double valueAt(List<Ev> evs, double sec, double fallback) {
            if (evs.isEmpty()) return fallback;
            Ev first = evs.get(0);
            if (sec < first.t0) return first.lerpUnclamped(sec);
            int lo = 0, hi = evs.size() - 1, idx = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (evs.get(mid).t0 <= sec) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            Ev e = evs.get(idx);
            return sec <= e.t1 ? e.lerp(sec) : e.v1;
        }

        /** 找到覆盖 {@code t} 的那一段（与 {@link #valueAt} 同口径：同刻取后一段）。 */
        public static Ev segmentAt(List<Ev> evs, double t) {
            if (evs.isEmpty()) return null;
            int lo = 0, hi = evs.size() - 1, idx = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (evs.get(mid).t0 <= t) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return evs.get(idx);
        }
    }

    public List<Note> allNotes() {
        List<Note> out = new ArrayList<>();
        for (Line l : lines) out.addAll(l.notes);
        return out;
    }
}
