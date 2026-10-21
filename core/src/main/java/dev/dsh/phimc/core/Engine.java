package dev.dsh.phimc.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时判定引擎。所有时间都以「音频播放位置」为准，由外部（MC 侧 OpenAL、或自测脚本）每帧喂进来。
 *
 * <p>设计原则：这个类不知道 Minecraft 的存在，也不知道 JSON 长什么样——纯粹是
 * 「给定秒数 t，出可见音符；给定点击 (t, x, y)，出判定结果」。
 *
 * <p>几何口径全部照 {@code prpr} 抄，关键换算链（单位很多，先在这里钉死）：
 * <ul>
 *   <li>Phira「世界坐标」：{@code x ∈ [-1, 1]} = 整屏宽，{@code y ∈ [-1/aspect, 1/aspect]} = 整屏高
 *       （相机 {@code zoom.y = -aspect}，{@code resource.rs:507}）。所以 1 世界单位 = 半个屏宽。</li>
 *   <li>判定线世界位置 = {@code (xAt*2-1, (yAt*2-1)/aspect)}——y 除以 aspect 见
 *       {@code object.rs:52-57} {@code now_translation}。</li>
 *   <li>音符相对判定线的世界 y = {@code Δheight / aspect * note.speed}
 *       （{@code note.rs:218-223} 的 {@code line_height} / {@code height} / {@code base}）。</li>
 *   <li>换成「屏高比例」（{@link Visible#yFrac}，1.0 = 一整个屏幕高、正数 = 判定线之上）：
 *       {@code n = Δheight * speed / 2}，见 {@link Chart#HEIGHT_TO_SCREEN}。</li>
 * </ul>
 */
public final class Engine {

    // ------------------------------------------------------------------
    // 判定常量：逐个对应 prpr/src/judge.rs
    // ------------------------------------------------------------------

    /** judge.rs:20 {@code LIMIT_PERFECT}。 */
    public static final double LIMIT_PERFECT = 0.08;
    /** judge.rs:21 {@code LIMIT_GOOD}。 */
    public static final double LIMIT_GOOD = 0.16;
    /** judge.rs:22 {@code LIMIT_BAD}。 */
    public static final double LIMIT_BAD = 0.22;
    /** judge.rs:23 {@code UP_TOLERANCE}（hold 抬手指的宽限）。 */
    public static final double UP_TOLERANCE = 0.05;
    /** judge.rs:24 {@code DIST_FACTOR}（离音符中心越远，排序代价越大）。 */
    public static final double DIST_FACTOR = 0.2;
    /** judge.rs:26 {@code EARLY_OFFSET}：早打 70ms 以内不扣判定偏移（judge.rs:580）。 */
    public static final double EARLY_OFFSET = 0.07;

    /**
     * 横向判定容差，judge.rs:417 {@code const X_DIFF_MAX: f64 = 0.21 / (16. / 9.) * 2.}，
     * 单位 = 判定线本地坐标系（世界单位）。比较方式见 judge.rs:583-586：
     * {@code dist = |note_local_x - touch_local_x| / judge_area}，{@code dist > X_DIFF_MAX} 就跳过。
     */
    public static final double X_DIFF_MAX = 0.21 / (16.0 / 9.0) * 2.0;

    /** core.rs:15 {@code NOTE_WIDTH_RATIO_BASE}，仅用于 judge.rs:601 的排序代价。 */
    public static final double NOTE_WIDTH_RATIO_BASE = 0.13175016;

    /**
     * 音符消失淡出时长，note.rs:10 {@code FADEOUT_TIME}：音符过了判定时间 0.16s 之后不再绘制
     * （note.rs:236-241 的 {@code res.time - FADEOUT_TIME >= self.time}）。
     */
    public static final double FADEOUT_TIME = 0.16;

    /** line.rs:386 的视口横向余量 {@code vw = 1.1}（几何剔除用，源码留了 10% 边）。 */
    public static final double VIEW_X = 1.1;

    /** pgr.rs:207：{@code position_x * (2. * 9. / 160.)} = 0.1125 世界单位 / 谱面 x 单位。 */
    public static final double X_WORLD_PER_UNIT = 0.1125;

    /** 调用方不传宽高比时按 16:9（prpr info.rs:79 的默认 {@code aspect_ratio}）。 */
    public static final double DEFAULT_ASPECT = Chart.DEFAULT_ASPECT;

    /**
     * {@code note.rs:172}：控制器轨道的自变量是
     * {@code (note.height - line_height + translation.y/speed) * RPE_HEIGHT / 2}
     * （RPE_HEIGHT = 900 ⇒ ×450）。见 {@link Chart.Line#ctrlAt}。
     */
    public static final double CTRL_HEIGHT_SCALE = 450.0;

    /** 伪音符的判定面积系数（pgr.rs:225 恒为 1；RPE 才有每音符 judgeArea）。 */
    public static final double DEFAULT_JUDGE_AREA = 1.0;

    public static final class NoteState {
        public final Chart.Note note;
        public final Chart.Line line;
        /** 该音符所属判定线在 {@code chart.lines} 里的下标，用于每帧只算一次累计高度。 */
        final int lineSlot;
        /** pgr.rs:225：PGR 谱面的 judge_area 恒为 1。 */
        final double judgeArea;
        public boolean judged;
        public boolean hit;
        public boolean holding;      // hold 头已判、尾巴还没结束
        public boolean tailDone;     // hold 尾已结算
        public boolean tailMissed;   // 提前松手
        public Judgement.Grade grade = Judgement.Grade.MISS;
        public double judgedAt = Double.NaN;
        /**
         * judge.rs:140 {@code JudgeStatus::PreJudge}：被「预判」了、但还没到音符时间。
         * 键盘游玩里按下键会让 Flick（judge.rs:742-758 的通路）进这个状态，
         * 到音符时间后由 judge.rs:784-793 转成 Perfect。
         */
        public boolean preJudged;
        /**
         * judge.rs:142 {@code JudgeStatus::Hold(.., up_time)}：最近一次「键/手指全离开」的时刻，
         * 初值 +∞（judge.rs:690 hold 头命中时传 {@code f64::INFINITY}）。
         */
        public double holdUpTime = Double.POSITIVE_INFINITY;
        /** judge.rs:142 {@code JudgeStatus::Hold(pre_judge)}：已进尾巴区间，之后不可能再断（judge.rs:705-708）。 */
        public boolean holdPreJudge;

        NoteState(Chart.Note note, Chart.Line line, int lineSlot) {
            this.note = note;
            this.line = line;
            this.lineSlot = lineSlot;
            // pgr.rs:225 对 PGR 恒为 1；RPE 才有每音符 judgeArea（rpe.rs:604）
            this.judgeArea = note.judgeArea;
        }

        /**
         * 该音符所属判定线在 {@code chart.lines} 里的下标。
         * 渲染层用它把「每条线每帧的变换」缓存起来（{@code PlayScreen}），
         * 不用给每个音符重算一遍 {@code xAt/yAt/rotateAt}。
         */
        public int lineSlot() {
            return lineSlot;
        }
    }

    /**
     * 渲染层要的东西：屏幕坐标（0~1）、透明度、所在判定线的角度、音符缩放。
     *
     * <p>{@code scale} 是 RPE 才有的东西（{@code note.size} × {@code sizeControl}，
     * {@code note.rs:185-189}）；PGR 恒为 1。渲染层按它缩放贴图即可。
     */
    public record Visible(NoteState state, double xFrac, double yFrac, double alpha,
                          double rotationDeg, double scale) {
    }

    private final Chart chart;
    private final Judgement.Config cfg;
    private final List<NoteState> states = new ArrayList<>();
    /** 每帧复用的「各判定线当前累计高度」，下标与 chart.lines 一致；同一 sec 内按需算一次就缓存。 */
    private final double[] lineFloor;
    private final boolean[] lineFloorDone;
    private double lineFloorSec = Double.NaN;
    /**
     * {@link #visible} 的前缀游标：{@code states[0, visibleStart)} 里的音符按当前绘制规则永远不会
     * 再被画出来，所以每帧可以整段跳过。只前进；{@link #reset()} 与「时间倒退」时归零。
     * 对应 {@code prpr/src/core/line.rs:185-208} 里 {@code above_indices/below_indices} 的推进。
     */
    private int visibleStart;
    private double visibleStartSec = Double.NEGATIVE_INFINITY;
    private double time;
    private int combo;
    private int maxCombo;
    private int perfect;
    private int good;
    private int bad;
    private int miss;
    private int tailMiss;
    private double weightSum;
    private int total;             // 参与计分的音符数（排除 fake）
    private int judgedCount;
    /**
     * 最近一次被判定（含 Miss）的音符。给 app 层补 Miss 的表现用——Miss 是 {@link #update(double)}
     * 里批量判的，没有点击坐标，只能靠它找回音符位置。等价于 prpr judge.rs:268-281 暴露给上层的
     * {@code judgements} 队列，但不保存队列，只留最后一条。
     */
    private NoteState lastJudgedNote;

    // ------------------------------------------------------------------
    // 键盘游玩 / 手指状态：逐个对应 prpr 里 TOUCHES 那一套（judge.rs:284-295）
    // ------------------------------------------------------------------

    /**
     * judge.rs:278 {@code key_down_count}：**跨帧**累计的「当前按下的键数」。
     * 每帧 {@code saturating_add_signed(key_delta)}（judge.rs:471），关掉键盘游玩时恒为 0。
     */
    private int keyDownCount;
    /** 空格键：专用「甩」键（用户要求；Phira 里是任意键按住即可，这里额外给空格一个独立通路）。 */
    private boolean flickKey;
    /** judge.rs:287 {@code key_delta}：本帧所有按键事件的增量（按下 +1、抬起 −1），每帧清零。 */
    private int keyDelta;
    /** judge.rs:288 {@code keys_down}：本帧**新按下**的键数（源码里 {@code key_up} 不减它），每帧清零。 */
    private int keyTaps;
    /** 源码的「手指」（judge.rs:424-461 的 {@code touches}）：鼠标此刻是否按着。 */
    private boolean pointerDown;
    /** 手指位置（屏幕比例 0~1）；NaN = 不知道位置。 */
    private double pointerXFrac = Double.NaN;
    private double pointerYFrac = Double.NaN;
    /** 记录手指位置时的宽高比（换算回 {judge.rs:473-474} 的世界坐标要用）。 */
    private double pointerAspect = DEFAULT_ASPECT;

    /** 一条判定事件，等价于 judge.rs:268 的 {@code judgements} 队列元素。 */
    public record JudgeEvent(NoteState state, Judgement.Grade grade) {
    }

    /**
     * 本帧产生的判定（judge.rs:268 {@code type Judgements = Vec<(f64, u32, u32, Result<Judgement, bool>)>;}
     * 里的 {@code Ok(grade)} 那部分）。表现层每帧 {@link #drainJudgements()} 取走发特效，
     * 对应 judge.rs:797-861 那个「按判定结果发粒子」的循环。
     */
    private final List<JudgeEvent> pendingJudgements = new ArrayList<>();

    public Engine(Chart chart, Judgement.Config cfg) {
        this.chart = chart;
        this.cfg = cfg;
        for (int slot = 0; slot < chart.lines.size(); slot++) {
            Chart.Line line = chart.lines.get(slot);
            for (Chart.Note n : line.notes) {
                // fake 音符也进 states：它们不参与判定（judge.rs:307 建索引时过滤掉 fake），
                // 但必须被渲染（note.rs:237 里 fake 音符只是「到点即隐」，不是不画）。
                states.add(new NoteState(n, line, slot));
                if (!n.fake) total++;
            }
        }
        this.lineFloor = new double[chart.lines.size()];
        this.lineFloorDone = new boolean[chart.lines.size()];
        states.sort((a, b) -> Double.compare(a.note.time, b.note.time));
    }

    public void reset() {
        for (NoteState s : states) {
            s.judged = false;
            s.hit = false;
            s.holding = false;
            s.tailDone = false;
            s.tailMissed = false;
            s.preJudged = false;
            s.holdUpTime = Double.POSITIVE_INFINITY;
            s.holdPreJudge = false;
            s.grade = Judgement.Grade.MISS;
            s.judgedAt = Double.NaN;
        }
        time = 0;
        combo = maxCombo = 0;
        perfect = good = bad = miss = tailMiss = 0;
        weightSum = 0;
        judgedCount = 0;
        lastJudgedNote = null;
        keyDownCount = 0;
        keyDelta = 0;
        keyTaps = 0;
        flickKey = false;
        pointerDown = false;
        pointerXFrac = Double.NaN;
        pointerYFrac = Double.NaN;
        pendingJudgements.clear();
        visibleStart = 0;
        visibleStartSec = Double.NEGATIVE_INFINITY;
        lineFloorSec = Double.NaN;
    }

    public double time() {
        return time;
    }

    /** 自动播放：把到点的音符全部判成 Perfect，再推进时钟。fake 音符不判（judge.rs:307）。 */
    public void autoplay(double sec) {
        for (NoteState s : states) {
            if (s.note.time > sec) break;
            if (s.judged || s.note.fake) continue;
            judge(s, Judgement.Grade.PERFECT, 0, s.note.time);
            s.hit = true;
        }
        update(sec);
    }

    /**
     * 每帧调用：推进时钟、吃掉本帧输入、结算到期的音符。顺序照 judge.rs:412-871。
     *
     * <ol>
     *   <li>judge.rs:462-472：本帧 {@code key_delta} 累进 {@code key_down_count}；</li>
     *   <li>judge.rs:649-698：本帧每新按下一次键，吃掉一个「最早未判的 Tap/Hold」；</li>
     *   <li>judge.rs:699-760：hold 维持 + Miss + Flick/Drag 预判；</li>
     *   <li>judge.rs:762-796：PreJudge → Perfect；</li>
     *   <li>judge.rs:766-772：Hold 到尾巴结算。</li>
     * </ol>
     *
     * <p><b>有意偏离 judge.rs:742</b>：源码里 Drag 不受 {@code key_down_count == 0} 那条限制约束
     * （钥匙是 {@code !matches!(note.kind, NoteKind::Drag)}），手指落在 x 范围内就能 PreJudge。
     * 本实现曾经直接让 Drag「到点自动 Perfect」；现在按用户要求取消自动判定：
     * <b>Drag 也必须有输入</b>（键按着 / 空格甩键 / 指针在范围内）才进 PreJudge，否则到点照常 Miss，
     * 并且 Drag 与 Flick 走<b>完全一样</b>的那一段条件。判定等级照 judge.rs:762-793 固定 Perfect。
     * 理由：用户要求「Drag 不能白给；键盘上 Drag 与 Flick 同逻辑」。
     */
    public void update(double sec) {
        this.time = sec;
        applyKeyFrame(sec);
        // judge.rs:699-760：hold 维持 / Miss / Flick + Drag 预判，一趟扫完
        double bad = cfg.bad();
        for (NoteState s : states) {
            if (s.note.fake) continue;                                  // judge.rs:307 索引里没有 fake
            if (s.holding && !s.tailDone) {
                maintainHold(s, sec);                                   // judge.rs:703-728
                continue;
            }
            if (s.judged || s.preJudged) continue;                      // judge.rs:729 只处理 NotJudged
            double dt = (sec - s.note.time) / cfg.speed;                // judge.rs:733
            if (dt > bad) {                                             // judge.rs:734-738
                judge(s, Judgement.Grade.MISS, sec - s.note.time, sec);
                continue;
            }
            if (-dt > bad) break;                                       // judge.rs:739-741（后面都是未来的）
            // **有意偏离 judge.rs:742**：源码里 Flick 要求 key_down_count != 0、Drag 则无条件放行。
            // 用户要求「键盘上 Drag 和 Flick 一模一样」，所以两类放同一条条件里，
            // 并且都必须真有输入（键按着 / 空格甩键 / 指针在范围内）才进 PreJudge。
            if (s.note.type != Chart.Note.Type.FLICK && s.note.type != Chart.Note.Type.DRAG) continue;
            // judge.rs:749-758：只看「有没有输入」，键盘路径完全不校验横向位置
            if (keyDownCount != 0 || flickKey || pointerCovers(s, sec, Math.abs(dt))) s.preJudged = true;
        }
        // judge.rs:762-796：PreJudge 到点转 Perfect
        for (NoteState s : states) {
            if (s.note.fake || s.judged || !s.preJudged) continue;
            // judge.rs:781-783：非 Click 的 PreJudge 要等 t >= note.time
            if (sec < s.note.time) continue;
            s.preJudged = false;
            // judge.rs:790-793：非 Click 的 PreJudge 直接 Perfect（Drag / Flick 都是 Perfect）
            judge(s, Judgement.Grade.PERFECT, 0, sec);
        }
        completeHolds(sec);
    }

    // ------------------------------------------------------------------
    // 键盘游玩（judge.rs:462-472、649-698、1009-1018）
    // ------------------------------------------------------------------

    /**
     * 一个键按下（judge.rs:1009-1014 {@code key_down_event}，{@code repeat == false} 时）：
     * {@code key_delta += 1; keys_down += 1;} 然后立刻结算这一帧。
     */
    public void keyDown(double sec) {
        keyDelta++;                                                     // judge.rs:1011
        keyTaps++;                                                      // judge.rs:1012
        applyKeyFrame(sec);
    }

    /**
     * 一个键抬起（judge.rs:1016-1018 {@code key_up_event}）：<b>只减 {@code key_delta}，
     * 不动 {@code keys_down}</b>。所以「抬起」不会产生 Tap 判定，只会让
     * {@code key_down_count} 掉下去（hold 就是靠这个断的）。
     */
    public void keyUp(double sec) {
        keyDelta--;                                                     // judge.rs:1017
        applyKeyFrame(sec);
    }

    /**
     * 空格专用甩键（用户要求；Phira 里是任意键按住即可，这里额外给空格一条独立通路）。
     * 它不参与 {@code key_down_count}（不消耗 Tap/Hold），只作为「有输入」让 Flick / Drag 进 PreJudge
     * ——见 {@link #update(double)} 里那段判断。
     */
    public void setFlickKey(boolean down) {
        this.flickKey = down;
    }

    public boolean flickKey() {
        return flickKey;
    }

    /** 当前按下的键数（judge.rs:278 {@code key_down_count}）。 */
    public int keyDownCount() {
        return keyDownCount;
    }

    /** 丢掉所有键盘状态（关掉键盘游玩 / 重开时用，避免卡在「永远按着」）。 */
    public void clearKeys() {
        keyDownCount = 0;
        keyDelta = 0;
        keyTaps = 0;
        flickKey = false;
    }

    /**
     * judge.rs:462-472 + judge.rs:367-380：一「帧」的键盘增量结算。
     *
     * <p>源码每帧用 {@code repeat_all_miniquad_input} 把本帧输入事件重放一遍，攒出
     * {@code key_delta} 与 {@code keys_down}，然后整块替换 {@code TOUCHES}
     * （judge.rs:374-376 {@code *it.borrow_mut() = handler.status;}）——
     * 所以这两个量<b>每帧被清零一次</b>；{@code key_down_count} 才是跨帧累计的
     * （judge.rs:471 {@code saturating_add_signed}）。
     *
     * <p>我们这边没有输入重放，是按事件回调即时记账；为了保持同一套语义，
     * 每次按键事件和每帧 {@link #update(double)} 都走一遍本方法：
     * 累加 delta → 把攒下的按键次数全部吃成判定 → 两个本帧量清零。
     */
    private void applyKeyFrame(double sec) {
        long v = (long) keyDownCount + keyDelta;            // judge.rs:471
        keyDownCount = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, v));   // saturating_add_signed
        keyDelta = 0;                                       // TOUCHES 每帧重建 ⇒ 清零
        int taps = keyTaps;
        keyTaps = 0;
        for (int i = 0; i < taps; i++) {                    // judge.rs:649 {@code for _ in 0..keys_down}
            if (!keyboardTap(sec)) break;                   // judge.rs:695-697
        }
    }

    /**
     * judge.rs:649-698：一次按键吃掉「一个窗口内的未判 Click/Hold」。逐条照抄：
     * <ul>
     *   <li>候选 = {@code JudgeStatus::NotJudged} 且类型是 Click / Hold（judge.rs:660-663）；</li>
     *   <li>{@code dt = |t - note.time| / spd}，Click 用 {@link #LIMIT_BAD}、Hold 用 {@link #LIMIT_GOOD}
     *       （judge.rs:669-670）；</li>
     *   <li><b>不校验横向位置</b>，也<b>没有</b>点击路径里那套 {@link #EARLY_OFFSET} / 距离代价。</li>
     * </ul>
     * Click 按 dt 落 Perfect / Good / Bad（judge.rs:672-686）；Hold 落 Perfect 或 Good 并开始维持
     * （judge.rs:687-691）。
     *
     * <p><b>有意偏离 judge.rs:660-666（两处，都是用户体感要求）</b>：
     * <ol>
     *   <li><b>跳过「已经超出自己窗口」的音符</b>：源码的候选只要是 {@code NotJudged} 就算，于是那些
     *       这一帧迟早会被 Miss 扫描判掉的音符仍然占着「最早未判」的位置——一轮按键挑到它、发现超窗
     *       就空转（judge.rs:695-697 的 break 语义）。这里先按各自窗口（Click 比 {@link #LIMIT_BAD}、
     *       Hold 比 {@link #LIMIT_GOOD}）过滤，让它们交给 {@link #update(double)} 的 Miss 扫描结算。</li>
     *   <li><b>挑「时间上最近的那个」而不是「最早的」</b>：源码是 {@code min_by_key(note.time)}
     *       （judge.rs:666）。可是密集段里只要漏按一个，它就会在 220ms 的 BAD 窗里继续当「最早未判」，
     *       把接下来每一次按键都吃掉——按键整体错位一格，玩家瞄准的音符反而永远吃不到，听感就是
     *       「后面按什么都没反应」，而且最后还要多 Miss 一个（见 {@code tools/judge/DenseHarness.java}
     *       的复现：10 个 Tap 间隔 20ms、漏第 5 个，修前 9 次按键分别吃 t=10.00…10.16，
     *       玩家瞄准的 10.10…10.18 一个都没吃到）。
     *       改成「取 {@code |dt|} 最小、并列时取更早的」，按键就永远落在玩家此刻想打的那个音符上，
     *       没按到的那个自然由 Miss 扫描结算。</li>
     * </ol>
     * 两条都只影响「这一下按键打谁」，窗口数值与计分公式一个都没动。
     *
     * @return false 表示这一轮压根没有候选（源码里是 {@code else { break }}，跳出 {@code for _ in 0..keys_down}）
     */
    private boolean keyboardTap(double sec) {
        NoteState best = null;
        double bestAbs = Double.MAX_VALUE;
        for (NoteState s : states) {
            if (s.note.fake || s.judged || s.preJudged) continue;
            if (s.note.type != Chart.Note.Type.TAP && s.note.type != Chart.Note.Type.HOLD) continue;
            // 已经必 Miss 的（超出自己的窗口）不再当候选（有意偏离 judge.rs:660-666 之一）
            double ownWindow = s.note.type == Chart.Note.Type.TAP ? LIMIT_BAD : LIMIT_GOOD;
            double abs = Math.abs(sec - s.note.time) / cfg.speed;
            if (abs > ownWindow) continue;
            // 取「时间上最近」的；并列才比谁更早（有意偏离 judge.rs:660-666 之二）
            if (best == null || abs < bestAbs - 1e-12
                    || (Math.abs(abs - bestAbs) <= 1e-12 && s.note.time < best.note.time)) {
                best = s;
                bestAbs = abs;
            }
        }
        if (best == null) return false;
        double dt = Math.abs(sec - best.note.time) / cfg.speed;                     // judge.rs:669
        double limit = best.note.type == Chart.Note.Type.TAP ? LIMIT_BAD : LIMIT_GOOD;  // judge.rs:670
        if (dt > limit) return true;    // 上面已按窗口过滤过，这里只做兜底（judge.rs:670-694）
        if (best.note.type == Chart.Note.Type.TAP) {
            Judgement.Grade g = dt <= LIMIT_PERFECT ? Judgement.Grade.PERFECT
                    : dt <= LIMIT_GOOD ? Judgement.Grade.GOOD
                    : Judgement.Grade.BAD;                                          // judge.rs:674-685
            judge(best, g, sec - best.note.time, sec);
        } else {
            judge(best, dt <= LIMIT_PERFECT ? Judgement.Grade.PERFECT : Judgement.Grade.GOOD,
                    sec - best.note.time, sec);                                     // judge.rs:687-691
            best.holding = true;
            best.tailDone = false;
            best.holdUpTime = Double.POSITIVE_INFINITY;                             // judge.rs:690 的 f64::INFINITY
        }
        best.hit = true;
        return true;
    }

    // ------------------------------------------------------------------
    // 「手指」（源码 touches 里的鼠标那一路）
    // ------------------------------------------------------------------

    /**
     * 喂「手指」状态。{@code xFrac/yFrac} 是屏幕比例 0~1，null = 不知道位置。
     * 用途只有两处：judge.rs:712-716（hold 维持的 x 范围）、judge.rs:749-755（Flick/Drag 预判）。
     */
    public void setPointer(boolean down, Double xFrac, Double yFrac, double aspect) {
        this.pointerDown = down;
        this.pointerXFrac = xFrac == null ? Double.NaN : xFrac;
        this.pointerYFrac = yFrac == null ? 0.5 : yFrac;
        if (aspect > 0.1 && aspect < 20.0) this.pointerAspect = aspect;
    }

    /** 手指在判定线本地系里的 x（judge.rs:526-534 的 {@code inv.transform_point}，同 {@link #clickLocalX}）。 */
    private double pointerLocalX(Chart.Line line, double sec) {
        double wx = pointerXFrac * 2.0 - 1.0;                              // judge.rs:473-474
        double wy = -(pointerYFrac * 2.0 - 1.0) / pointerAspect;           // judge.rs:474 + 533 的负号
        return clickLocalX(line, sec, pointerAspect, wx, wy);
    }

    /** 音符本地 x（judge.rs:581-583 只比这一维；与 {@link #find} 同一口径）。 */
    private static double noteJudgeX(NoteState s) {
        return s.note.x * X_WORLD_PER_UNIT;
    }

    /**
     * judge.rs:749-755 的第二项：手指在 x 范围内**并且**时间也在
     * {@code LIMIT_BAD - LIMIT_PERFECT * max(dx - 0.9, 0)} 内（越远窗越窄）。
     */
    private boolean pointerCovers(NoteState s, double sec, double dtAbs) {
        if (!pointerDown || Double.isNaN(pointerXFrac)) return false;
        double dx = Math.abs(noteJudgeX(s) - pointerLocalX(s.line, sec)) / s.judgeArea;
        return dx <= X_DIFF_MAX && dtAbs <= LIMIT_BAD - LIMIT_PERFECT * Math.max(dx - 0.9, 0.0);
    }

    // ------------------------------------------------------------------
    // Hold 维持（judge.rs:699-727）
    // ------------------------------------------------------------------

    /**
     * judge.rs:699-727。三条规则：
     * <ol>
     *   <li>离尾巴不到 {@link #LIMIT_BAD}（220ms）→ 置 {@code pre_judge = true} 并返回，
     *       之后不可能再断（judge.rs:705-708）；</li>
     *   <li>「松开了」才算开始计时：第一次记下 {@code up_time = t}（judge.rs:720-722），
     *       之后超过 {@link #UP_TOLERANCE}（50ms）还没按回来 → 判 Miss（judge.rs:717-719）；</li>
     *   <li>否则（还有键按着 / 鼠标按着）{@code up_time = +∞}，把这 50ms 撤销（judge.rs:723-725）。</li>
     * </ol>
     *
     * <p><b>有意偏离 judge.rs:712-716</b>：源码的「松开」判据是
     * {@code key_down_count == 0 && 没有任何手指落在音符 x 的 X_DIFF_MAX 内}。
     * 我们只有一根指针（鼠标），它同时也是「按住鼠标疯狂划」的那根手指——如果保留 x 判据，
     * 玩家一旦为了吃后面的音符把鼠标划走，手上还按着的 hold 就会在 50ms 后被判掉，整首再也 AP 不了。
     * 所以这里把「手指在 x 范围内」放宽成「鼠标还按着」：`key_down_count == 0 && !pointerDown` 才算松开。
     * 理由：单指针设备 + 用户明确要求保留「按住鼠标划动就能 AP」的手感；不影响窗口数值与计分。
     *
     * <p>Miss 的表现沿用本实现的 {@code tailMiss}：不额外增加 total 里的音符数、不计 miss 计数，
     * 只断连（判分公式不动）。
     */
    private void maintainHold(NoteState s, double sec) {
        double end = s.note.endTime();
        if ((end - sec) / cfg.speed <= LIMIT_BAD) {                     // judge.rs:705
            s.holdPreJudge = true;                                      // judge.rs:706
            return;                                                     // judge.rs:707
        }
        if (keyDownCount == 0 && !pointerDown) {                        // judge.rs:712-716（放宽了 x 判据）
            if (sec > s.holdUpTime + UP_TOLERANCE) {                     // judge.rs:717-719
                s.tailDone = true;
                s.holding = false;
                s.tailMissed = true;
                tailMiss++;
                combo = 0;
            } else if (Double.isInfinite(s.holdUpTime)) {                // judge.rs:720-722
                s.holdUpTime = sec;
            }
        } else {
            s.holdUpTime = Double.POSITIVE_INFINITY;                     // judge.rs:723-725
        }
    }

    /**
     * 点击 / 击打（1 维：只校验横向）。autoplay / 自测走这条。
     *
     * @param sec   当前音频时间
     * @param xFrac 点击位置（0~1 的屏幕横向比例），null 表示不校验位置
     */
    public NoteState hit(double sec, Double xFrac) {
        return hit(sec, xFrac, null, DEFAULT_ASPECT);
    }

    /** 2 维点击（横向 + 纵向）。{@code yFrac == null} 时纵向不参与（源码本来也不看纵向）。 */
    public NoteState hit(double sec, Double xFrac, Double yFrac) {
        return hit(sec, xFrac, yFrac, DEFAULT_ASPECT);
    }

    /**
     * 点击判定，几何与窗口照 judge.rs:524-647：
     * <ol>
     *   <li>点击点 → 世界坐标（judge.rs:473-474 {@code x/w*2-1, y/h*2-1}；judge.rs:533 再取 {@code -y}）；</li>
     *   <li>用判定线变换的逆把点击点搬进判定线本地系（judge.rs:526-534），只取本地 x（judge.rs:581-583）；</li>
     *   <li>{@code dist = |note_local_x - click_local_x| / judge_area}，超过 {@link #X_DIFF_MAX} 跳过（judge.rs:584-586）；</li>
     *   <li>时间窗：Click 用 {@code LIMIT_BAD - LIMIT_PERFECT*max(dist-0.9,0)}，其它种类用 {@code LIMIT_GOOD}
     *       （judge.rs:587-595）；早打按 {@code EARLY_OFFSET} 折回（judge.rs:580）；</li>
     *   <li>在所有候选里取 {@code key = dt + max(dist/NOTE_WIDTH_RATIO_BASE-1,0)*DIST_FACTOR} 最小的
     *       （judge.rs:601-606），而不是「最早的那个」。</li>
     * </ol>
     * 源码没有「纵向容差」：只要是同一条判定线、本地 x 够近就算命中，点屏幕任意高度都行。
     * 所以本方法的 yFrac 只参与「换算回本地 x」（判定线旋转后这一点才重要），从不单独比较 y。
     */
    public NoteState hit(double sec, Double xFrac, Double yFrac, double aspect) {
        return hit(sec, xFrac, yFrac, aspect, Double.NaN);
    }

    /**
     * 同上，但可以给「接受窗口」加一个上限 {@code maxDt}（{@code NaN} = 不加）。
     *
     * <p>只给「按住鼠标移动」那条路用（{@link #dragTouch}）：移动派生的判定在源码里永远是
     * Perfect（judge.rs:742-758 先 PreJudge，judge.rs:790-793 再固定推 Perfect），所以移动通路
     * 只接受落在 {@code LIMIT_PERFECT} 里的候选，不会因为「划得早了一点」把音符吃成 Good/Bad。
     * 判定窗口的数值本身一个都没改，这里只是「这条路愿意接受哪个档」。
     */
    public NoteState hit(double sec, Double xFrac, Double yFrac, double aspect, double maxDt) {
        // 点击点 → 世界坐标；xFrac == null 表示不比横向，值取中点即可
        double clickWx = (xFrac == null ? 0.0 : xFrac * 2.0 - 1.0);
        double clickWy = (yFrac == null ? 0.0 : -(yFrac * 2.0 - 1.0) / aspect);
        Candidate best = find(sec, true, xFrac != null, clickWx, clickWy, aspect, maxDt);
        if (best == null) return null;
        NoteState s = best.state;
        switch (s.note.type) {
            case DRAG -> {
                return null;                        // judge.rs:609-612：点击不能判 Drag
            }
            case FLICK -> {
                return null;                        // judge.rs:616-618：点击不能判 Flick（要划）
            }
            case HOLD -> {
                if (best.dt > LIMIT_GOOD) return null;   // judge.rs:587-595 已经把窗口卡在 LIMIT_GOOD
                boolean perfect = best.dt <= LIMIT_PERFECT;
                judge(s, perfect ? Judgement.Grade.PERFECT : Judgement.Grade.GOOD, sec - s.note.time, sec);
                s.hit = true;
                s.holding = true;
                s.tailDone = false;
                return s;
            }
            default -> {
                if (best.dt <= LIMIT_GOOD) {
                    judge(s, best.dt <= LIMIT_PERFECT ? Judgement.Grade.PERFECT : Judgement.Grade.GOOD,
                            sec - s.note.time, sec);
                } else {
                    judge(s, Judgement.Grade.BAD, sec - s.note.time, sec);   // judge.rs:632-639
                }
                s.hit = true;
                return s;
            }
        }
    }

    /**
     * 划动判定（Flick）。judge.rs:554-558 只把 {@code TouchPhase::Moved/Stationary} 且
     * {@code FlickTracker.flicked} 的触摸当 flick；flick / drag 的时间窗是 {@code LIMIT_GOOD}，
     * 且排序代价要再加一个 {@code LIMIT_GOOD}（judge.rs:596-600）。
     *
     * <p>命中后按 judge.rs:640-646 + 776-793 结算成 Perfect。
     */
    public NoteState flick(double sec, Double xFrac, Double yFrac, double aspect) {
        double clickWx = (xFrac == null ? 0.0 : xFrac * 2.0 - 1.0);
        double clickWy = (yFrac == null ? 0.0 : -(yFrac * 2.0 - 1.0) / aspect);
        Candidate best = find(sec, false, xFrac != null, clickWx, clickWy, aspect, Double.NaN);
        if (best == null) return null;
        NoteState s = best.state;
        judge(s, Judgement.Grade.PERFECT, 0, sec);
        s.hit = true;
        return s;
    }

    /**
     * 鼠标「按住并在屏幕上移动」的一次判定 —— <b>有意偏离源码</b>。
     *
     * <p>源码里 {@code TouchPhase::Moved/Stationary} 的触摸只有 {@code FlickTracker.flicked} 才会
     * 产生判定，而且只判 Flick/Drag（judge.rs:556-575 的 {@code if !click && matches!(kind, Click|Hold)}
     * 直接跳过），Tap/Hold 只能由 {@code TouchPhase::Started}（按下瞬间）判定。
     * 用户要求保留「按住鼠标疯狂划可以 AP」的手感：按住左键移动时每次移动事件都要能连续吃音符
     * （Tap / Hold 都算；Drag 本来到点自动判；Flick 走划动通路），所以把一次移动当成一次「按下」：
     * <ol>
     *   <li>先走 {@link #hit} 点击通路（judge.rs:554-647）——判定线本地 x 校验、候选挑选、
     *       距离代价全部沿用，<b>只把接受窗口收成 {@link #LIMIT_PERFECT}</b>；</li>
     *   <li>点击通路没吃到（例如最近的候选是 Flick，被 judge.rs:616-618 拒了），再走 {@link #flick}
     *       划动通路（judge.rs:554-558 / 640-646），Flick 照旧吃。</li>
     * </ol>
     *
     * <p><b>为什么窗口收到 Perfect</b>：源码里「移动派生」的判定永远是 Perfect——judge.rs:742-758
     * 先把音符置 {@code PreJudge}，judge.rs:790-793 再固定推 {@code Perfect}。如果这条通路沿用点击的
     * 160~220ms 坏窗口，疯狂划动会把大量音符在窗口边缘吃成 Bad/Good（实测 pkg1：全连但 749 个 Bad），
     * 那就不叫「划成 AP」了。所以移动通路只接受落在 {@code LIMIT_PERFECT} 里的候选：
     * 划早了就不判、等划到窗口再判。窗口数值一个都没改（{@link #LIMIT_PERFECT} 还是 0.08），
     * 变的只是「这条路愿意接受哪个档」，与 judge.rs:790-793 的 Perfect-only 一致。
     *
     * <p>每次移动最多产生 1 次判定，所以「疯狂划」= 每个移动事件吃 1 个音符（实测 pkg1 240Hz /
     * 每秒 4 个来回 ⇒ 1000000 / PHI / 0 Miss）。
     *
     * @param flickQualified 速度是否达到 {@code FLICK_THRESHOLD}（judge.rs:112-134，由调用方算）
     * @return 吃到的那一个音符，没有就 null
     */
    public NoteState dragTouch(double sec, Double xFrac, Double yFrac, double aspect, boolean flickQualified) {
        NoteState s = hit(sec, xFrac, yFrac, aspect, LIMIT_PERFECT);   // 只吃 Perfect 档的 Click/Hold
        if (s != null) return s;
        return flickQualified ? flick(sec, xFrac, yFrac, aspect) : null;   // 再吃 Flick（划动通路）
    }

    private static final class Candidate {
        NoteState state;
        double dist;
        double dt;
    }

    /**
     * judge.rs:563-606 的候选筛选：返回 key 最小的候选，没有就 null。
     * {@code click=false} 走 flick/drag 分支（窗口 LIMIT_GOOD + 额外 LIMIT_GOOD 的排序偏移）。
     */
    private Candidate find(double sec, boolean click, boolean checkX, double wx, double wy, double aspect,
                           double maxDt) {
        double key = LIMIT_BAD + Math.max(X_DIFF_MAX / NOTE_WIDTH_RATIO_BASE - 1.0, 0.0) * DIST_FACTOR;
        double[] localX = new double[chart.lines.size()];
        boolean[] done = new boolean[chart.lines.size()];
        Candidate best = null;
        for (NoteState s : states) {
            if (s.judged || s.note.fake) continue;
            // judge.rs:573-575：flick 路径（click=false）不看 Click/Hold
            if (!click && (s.note.type == Chart.Note.Type.TAP || s.note.type == Chart.Note.Type.HOLD)) continue;
            double dtRaw = (s.note.time - sec) / cfg.speed;
            if (dtRaw >= key) break;                     // judge.rs:577-579（states 按时间升序）
            double dt = dtRaw < 0 ? Math.abs(Math.min(dtRaw + EARLY_OFFSET, 0.0)) : dtRaw;  // judge.rs:580
            // 候选必须落在**自己的**窗口里：已经必 Miss 的音符直接跳过，绝不参与竞争。
            // （judge.rs:587-595 本来也会把它 continue 掉；这里提前到算 dist 之前，
            //   与 keyboardTap 的「已 Miss 不当候选」口径一致。）
            if (dt > (click ? LIMIT_BAD : LIMIT_GOOD)) continue;
            double dist = 0.0;
            if (checkX) {
                int slot = s.lineSlot;
                if (!done[slot]) {
                    localX[slot] = clickLocalX(s.line, sec, aspect, wx, wy);
                    done[slot] = true;
                }
                dist = Math.abs(s.note.x * X_WORLD_PER_UNIT - localX[slot]) / s.judgeArea;
                if (dist > X_DIFF_MAX) continue;                                    // judge.rs:584-586
            }
            double limit = click
                    ? LIMIT_BAD - LIMIT_PERFECT * Math.max(dist - 0.9, 0.0)         // judge.rs:587-589
                    : LIMIT_GOOD;                                                   // judge.rs:590-592
            if (!Double.isNaN(maxDt)) limit = Math.min(limit, maxDt);               // 调用方额外收紧（移动通路）
            if (dt > limit) continue;
            // flick / drag 的排序代价要再加一个 LIMIT_GOOD（judge.rs:596-600）
            // judge.rs:596-600：Flick / Drag 的排序代价要再加 LIMIT_GOOD —— 按**音符类型**，不是按路径。
            // 之前写成 `click ? dt : dt + LIMIT_GOOD`，导致点击时 Drag 没有惩罚，会抢走同一时刻 Tap 的点击（漏打）。
            double dtKey = (s.note.type == Chart.Note.Type.FLICK || s.note.type == Chart.Note.Type.DRAG)
                    ? dt + LIMIT_GOOD : dt;
            double k = dtKey + Math.max(dist / NOTE_WIDTH_RATIO_BASE - 1.0, 0.0) * DIST_FACTOR;  // judge.rs:601
            if (k < key) {
                key = k;
                best = new Candidate();
                best.state = s;
                best.dist = dist;
                best.dt = dt;
            }
        }
        return best;
    }

    /** 点击点（世界坐标）在判定线本地坐标系里的 x（judge.rs:526-534 的 {@code inv.transform_point}）。 */
    static double clickLocalX(Chart.Line line, double sec, double aspect, double wx, double wy) {
        double th = Math.toRadians(line.rotateAt(sec));                 // object.rs:48-50 角度单位是度
        double c = Math.cos(th), sn = Math.sin(th);
        double dx = wx - (line.xAt(sec, aspect) * 2.0 - 1.0);           // pgr.rs:141-146: [0,1] → [-1,1]
        double dy = wy - (line.yAt(sec, aspect) * 2.0 - 1.0) / aspect;  // object.rs:52-57: y 再除以 aspect
        return dx * c + dy * sn;                                        // 乘 R(-θ)
    }

    /**
     * 音符此刻在判定线本地坐标系里的位置，单位 = Phira 世界单位（1 = 半个屏宽）。
     * {@code x} 取 {@code 音符 x 世界值}（judge.rs:581-583 只比这一维），
     * {@code y} = {@code Δheight/aspect*spd}（note.rs:220-223 的 {@code base}）。
     *
     * <p>RPE 的 4 条控制器也在这里生效（{@code note.rs:171-232}）：
     * {@code pos} 乘在 x 上（{@code note.rs:181}）、{@code y} 乘在下落速度上（{@code note.rs:218}）。
     * 轨道为空时乘数恒为 1，PGR 的算法一个乘法都不多。
     */
    public static double[] noteLocal(NoteState s, double sec, double aspect) {
        Chart.Line line = s.line;
        double floorNow = line.floorPositionAt(sec);
        // pgr.rs:213：PGR 的 hold 恒速 1（speed 字段是尾巴的速度）；其它音符用谱面值
        double spd = (s.note.type == Chart.Note.Type.HOLD) ? 1.0 : s.note.speed;
        double dh = s.note.height - floorNow;
        // note.rs:171-173：控制器按「音符到线的高度」×450 求值（yOffset 已经烘进 height）
        double ctrlH = dh * CTRL_HEIGHT_SCALE;
        double cPos = Chart.Line.ctrlAt(line.ctrlPos, ctrlH);
        double cY = Chart.Line.ctrlAt(line.ctrlY, ctrlH);
        double y = dh * spd * cY / aspect;
        if (s.holding) y = 0;      // 按住时头停在判定线上（note.rs:287 的 body 起点）
        if (!s.note.above) y = -y; // chart.rs:159 对 notesBelow 整体做 y 翻转
        // note.rs:178-182：hold 不吃 posControl
        double xMul = (s.note.type == Chart.Note.Type.HOLD) ? 1.0 : cPos;
        return new double[]{s.note.x * X_WORLD_PER_UNIT * xMul, y};
    }

    /**
     * 音符此刻在屏幕上的位置 {@code {xFrac, yFrac}}（0~1，y 向下，含判定线位移与旋转）。
     * 与 PlayScreen 的绘制同一套公式；打击特效落在它上面
     * （prpr resource.rs:577-587 {@code emit_at_origin} 就是把特效放在当前模型原点）。
     */
    public static double[] screenPos(NoteState s, double sec, double aspect) {
        double[] local = noteLocal(s, sec, aspect);
        double th = Math.toRadians(s.line.rotateAt(sec));
        double c = Math.cos(th), sn = Math.sin(th);
        double ox = s.line.xAt(sec, aspect) * 2.0 - 1.0;
        double oy = (s.line.yAt(sec, aspect) * 2.0 - 1.0) / aspect;
        double wx = ox + local[0] * c - local[1] * sn;           // line.rs:230-234 rotation ∘ translation
        double wy = oy + local[0] * sn + local[1] * c;
        return new double[]{(wx + 1.0) / 2.0, 1.0 - (wy * aspect + 1.0) / 2.0};
    }

    /**
     * 判定线本地坐标系里「可视区域」的 y 上下界 {@code {minY, maxY}}。
     *
     * <p>照抄 line.rs:386-394：视口四角取 {@code (±1.1, ±1)}，用判定线变换的逆搬进本地系，
     * 取 y 的极值当作上下限；line.rs:406/423 用它剔除
     * （{@code note.height - line_height + translation > limit} 换算成本地 y 就是「超出上下限」）。
     *
     * <p>注意这里 y 用的是 {@code ±1} 而不是世界坐标的 {@code ±1/aspect}：源码的
     * {@code screen_to_world} 只走 model stack（判定线变换），并不含相机 {@code zoom.y = -aspect}
     * （resource.rs:507），所以源码算出的上界比真实屏幕边缘大 {@code aspect} 倍。这里按源码原样实现——
     * 宁可多提交（超出屏幕的部分由 GPU 裁掉），也不提前剔除音符。
     */
    public static double[] viewportLocalBounds(Chart.Line line, double sec, double aspect) {
        double th = Math.toRadians(line.rotateAt(sec));
        double c = Math.cos(th), sn = Math.sin(th);
        double ox = line.xAt(sec, aspect) * 2.0 - 1.0;
        double oy = (line.yAt(sec, aspect) * 2.0 - 1.0) / aspect;
        double[] cxs = {-VIEW_X, VIEW_X, -VIEW_X, VIEW_X};
        double[] cys = {-1.0, -1.0, 1.0, 1.0};
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double dx = cxs[i] - ox, dy = cys[i] - oy;
            double ly = -dx * sn + dy * c;       // R(-θ) 的 y 分量
            min = Math.min(min, ly);
            max = Math.max(max, ly);
        }
        return new double[]{min, max};
    }

    /** 当前应该画出来的音符（等价于 prpr line.rs:396-428 的两趟绘制循环，筛出「会被提交绘制」的）。 */
    public List<Visible> visible(double sec) {
        return visible(sec, DEFAULT_ASPECT);
    }

    /**
     * 当前应该画出来的音符。没有「提前 N 秒出生」的窗口——源码里不存在这种参数：
     * <ul>
     *   <li>note.rs:236-241：过了 {@code note.time + FADEOUT_TIME} 的非 hold 不再画；fake 音符到点即隐；
     *       这两条只在 {@code !draw_below}（= 判定线下面不画）时生效；</li>
     *   <li>note.rs:238：还没到时间、但已经掉到判定线下方的音符不画；</li>
     *   <li>line.rs:406/423：本地 y 超出视口上下界的音符不画（aggressive 模式，默认开）。</li>
     * </ul>
     * 透明度 = 淡出系数（note.rs:258）× 音符自带 alpha/visibleTime（note.rs:214）×
     * 控制器 alpha（note.rs:217）；缩放 = note.size × 控制器 size（note.rs:185-189）。
     *
     * <h2>为什么原来每帧要扫全部音符（以及怎么修的）</h2>
     * <p>{@code prpr} 的绘制循环不是「遍历所有音符再逐个剔除」：
     * <ul>
     *   <li>{@code JudgeLineCache} 给每条线维护 {@code above_indices / below_indices}
     *       （{@code line.rs:86-133}），{@code line.rs:185-208} 每帧把它们<b>向前推进</b>过已经
     *       {@code Judged} 的音符；</li>
     *   <li>绘制循环从这些下标<b>开始</b>（{@code line.rs:396-428}），碰到「已经飘出视口」的音符就
     *       {@code break}（{@code line.rs:406/423}）。</li>
     * </ul>
     * 合起来每帧只碰「还在屏上的音符」。我们以前是把 {@code states} 从下标 0 扫到末尾、对每个音符
     * 单独跑剔除，于是复杂度是 <b>O(全谱音符数) 每帧</b>：
     * {@code 琵琶曲DJ(1.15x).pez} 有 34211 个音符排在<b>一条</b>线上，实测 {@code visible()} 平均
     * <b>1.21ms/帧</b>（开机第一秒 294ms），而且越到后面越慢，永远不会变快。
     *
     * <p>修法（<b>「源码无此项，这是平台优化」</b>——{@code prpr} 用 {@code JudgeLineCache} 干同一件事，
     * 但那套结构依赖它自己「按 (plain, above, speed, height*speed) 排序」的音符数组，而我们的
     * {@code states} 必须按时间排序（判定通路 {@link #find}/{@link #keyboardTap} 依赖它），所以不能照抄）：
     * 因为 {@code states} 已经按时间升序，凡是「淡出窗口/尾巴都过去了」的音符<b>一定</b>排在最前面，
     * 于是只需要一个只前进的 {@link #visibleStart} 游标。语义上等价于源码的
     * {@code above_indices} 推进 + {@code note.rs:236-241} 的剔除，可见结果一个像素都不变
     * （唯一的行为变化是下面那条 hold 的剔除，那条是修 bug，见 {@code note.rs:281-283}）。
     */
    public List<Visible> visible(double sec, double aspect) {
        // floorPositionAt 是线性扫速度事件：同一条判定线在这一帧里只算一次（按需懒算，
        // 空帧不会白算几十条线），别每个音符都算一次。
        if (sec != lineFloorSec) {
            java.util.Arrays.fill(lineFloorDone, false);
            lineFloorSec = sec;
        }
        // 回退（重来 / seek）时游标必须归零，否则会把该画的音符跳过
        if (sec < visibleStartSec) visibleStart = 0;
        visibleStartSec = sec;
        // 只前进：跳过「按现在的绘制规则永远不会再被画」的前缀
        while (visibleStart < states.size()) {
            NoteState s = states.get(visibleStart);
            if (s.line.showBelow) break;                 // note.rs:236 的剔除只在 !draw_below 时生效
            if (s.holding && !s.tailDone) break;         // 正在按住的 hold 必须画
            boolean gone = s.note.type == Chart.Note.Type.HOLD
                    ? sec >= s.note.endTime()             // note.rs:281-283：过了尾巴直接 return
                    : sec - FADEOUT_TIME >= s.note.time;  // note.rs:237
            if (!gone) break;
            visibleStart++;
        }
        double[] boundMin = new double[chart.lines.size()];
        double[] boundMax = new double[chart.lines.size()];
        boolean[] boundDone = new boolean[chart.lines.size()];
        // 判定线 alpha / 角度也按「每条线每帧一次」缓存：它们本来会被每个候选音符各算一遍
        // （都是二分查找）。空帧不算，所以只会为真正有音符的线付钱。
        double[] lineAlphaCache = new double[chart.lines.size()];
        double[] lineRotCache = new double[chart.lines.size()];
        boolean[] lineInfoDone = new boolean[chart.lines.size()];
        List<Visible> out = new ArrayList<>();
        for (int si = visibleStart; si < states.size(); si++) {
            NoteState s = states.get(si);
            boolean hold = s.note.type == Chart.Note.Type.HOLD;
            boolean heldHold = hold && s.holding;
            // note.rs:195-197：已判定的非 hold 不再绘制；已判定的 hold 仍要画身体（note.rs:277-280 半透明）
            if (s.judged && !hold && !heldHold) continue;
            // note.rs:281-283：hold 过了尾巴 `if res.time >= end_time { return; }`
            // （以前没这条，判完的 hold 会一直把「头」钉在判定线上，是「特效谱画得很乱」的一部分）
            if (hold && sec >= s.note.endTime()) continue;

            Chart.Line line = s.line;
            int slot = s.lineSlot;
            if (!lineInfoDone[slot]) {
                lineAlphaCache[slot] = line.alphaAt(sec);
                lineRotCache[slot] = line.rotateAt(sec);
                lineInfoDone[slot] = true;
            }
            // line.rs:365-368：判定线 alpha 为负时<b>整条线（连音符）都不画</b>。
            // RPE 的 pe_alpha_extension 是 false（chart.rs:19 默认），所以负数 = 直接 return。
            // PEC 是 true（pec.rs:380），负数变成扩展编码：floor(-a)==1 也是「不画」，
            // 其它编码（2 / 100..1000）见报告「未做」。
            double lineAlpha = lineAlphaCache[slot];
            if (lineAlpha < 0.0) {
                if (!chart.peAlphaExtension) continue;                       // line.rs:366
                if ((int) Math.floor(-lineAlpha) == 1) continue;             // line.rs:371-373
            }
            // note.rs:236-241：这两个剔除只在 draw_below == false 时生效
            // （draw_below = RPE 的 isCover != 1，见 rpe.rs:821；PGR 恒为 false，行为不变）
            if (!line.showBelow
                    && !hold && (sec - FADEOUT_TIME >= s.note.time
                    || (s.note.fake && sec >= s.note.time))) continue;

            double floorNow;
            if (lineFloorDone[slot]) {
                floorNow = lineFloor[slot];
            } else {
                floorNow = line.floorPositionAt(sec);
                lineFloor[slot] = floorNow;
                lineFloorDone[slot] = true;
            }
            double spd = hold ? 1.0 : s.note.speed;          // pgr.rs:213
            double dh = s.note.height - floorNow;             // Phira 高度单位
            // note.rs:171-173：4 条控制器轨道按「音符到线的高度 × 450」求值（空轨道恒为 1）
            double ctrlH = dh * CTRL_HEIGHT_SCALE;
            double cAlpha = Chart.Line.ctrlAt(line.ctrlAlpha, ctrlH);
            double cSize = Chart.Line.ctrlAt(line.ctrlSize, ctrlH);
            double cPos = Chart.Line.ctrlAt(line.ctrlPos, ctrlH);
            double cY = Chart.Line.ctrlAt(line.ctrlY, ctrlH);
            double localY = dh * spd * cY / aspect;           // note.rs:218-222 base
            if (heldHold) localY = 0;                         // 按住时头钉在判定线上
            // note.rs:175-182 的 incline：一个「随音符离判定线的高度线性张开/收缩」的横向乘子。
            //   incline_val = 1 - incline_sin * (base*aspect + translation.y) * RPE_HEIGHT/2 / 360
            //   tr.x *= incline_val * ctrl_obj.pos        （hold 例外，直接乘 1）
            // 其中 base*aspect + translation.y == (height-line_height)*spd*cY + yOffsetRaw*spd*cY
            //                              == dh*spd*cY == localY*aspect（本文件 927 行的量）
            // RPE_HEIGHT/2/360 = 450/360 = 1.25
            // 注意：用**未翻转**的 localY（源码的 base 也是翻转前的）。
            double inclineVal = 1.0;
            if (!line.incline.isEmpty()) {
                double sin = Math.sin(Math.toRadians(line.inclineAt(sec)));
                if (sin != 0.0) inclineVal = 1.0 - sin * (localY * aspect) * (CTRL_HEIGHT_SCALE / 360.0);
            }
            // note.rs:238：未来的音符如果已经在判定线下方（负速度段），不画（同样只在 !draw_below）
            if (!line.showBelow && !heldHold && s.note.time > sec && localY <= -0.001) continue;
            if (!s.note.above) localY = -localY;              // chart.rs:159

            if (!boundDone[slot]) {
                double[] b = viewportLocalBounds(line, sec, aspect);
                boundMin[slot] = b[0];
                boundMax[slot] = b[1];
                boundDone[slot] = true;
            }
            // 视口剔除（line.rs:406/423）。**hold 必须按「身体这一整段」判**，不能只判端点：
            //   身体在本地坐标里是 [头, 身体末端] 这条线段。端点都在视口外、但**线段横穿视口**的
            //   情形真实存在（hold 比一屏还长），只判端点会把整根剔掉 ——
            //   画面表现就是「闪一下没了、下一帧又出来」。
            // 所以做线段 vs 区间的相交判定：只有整段都落在视口同一侧才剔。
            //
            // 身体末端 = prpr 的 end_height（rpe.rs:542-547），即 `floorPositionAt(note.endTime())`，
            // 与 PlayScreen 的 `tailPx = (endH - nowH)*0.5*h - |dy0|` 同源（note.rs:284-289
            // `top = end_height - line_height`）。按住时（heldHold）头钉在判定线上，另走下面的单点判定。
            if (hold && !heldHold) {
                double endH = line.floorPositionAt(s.note.endTime());
                double tailLocalY = (endH - floorNow) * spd * cY / aspect;
                if (!s.note.above) tailLocalY = -tailLocalY;
                double lo = Math.min(localY, tailLocalY);
                double hi = Math.max(localY, tailLocalY);
                if (hi < boundMin[slot] || lo > boundMax[slot]) continue;
            } else if (localY > boundMax[slot] || localY < boundMin[slot]) {
                continue;
            }

            double alpha = 1.0;
            if (!hold) {
                alpha = Math.min(0.0, s.note.time - sec) / FADEOUT_TIME + 1.0;   // note.rs:258
            }
            if (s.judged && !heldHold) alpha *= 0.5;                             // note.rs:277-280（已判定/miss 的 hold 身体半透明）
            // note.rs:214 + note.rs:217：音符自带 alpha（含 visibleTime）与控制器 alpha 都是乘上去的
            alpha *= s.note.alphaAt(sec) * cAlpha;
            if (alpha <= 0.0) continue;                                          // 全透明就不用提交了
            double n = localY * aspect / 2.0;                                    // 屏高比例
            // note.rs:178-182：hold 的 `tr.x` 直接乘 1，**不吃** incline 也**不吃** posControl
            //（源码 `if matches!(self.kind, NoteKind::Hold{..}) { 1. } else { incline_val * pos }`）
            double xBase = s.note.x * (hold ? 1.0 : cPos);
            if (!hold && inclineVal != 1.0) xBase *= inclineVal;
            double x = xBase * 0.05625 + 0.5;                                    // pgr.rs:207 / 2 × posControl
            double scale = s.note.size * cSize;                                  // note.rs:185-189
            out.add(new Visible(s, x, n, alpha, lineRotCache[slot], scale));
        }
        return out;
    }

    /**
     * 到点自动收尾：还按着的 hold 算完成（judge.rs:766-772：
     * {@code pre_judge} 且 {@code end_time <= t} ⇒ 结算成完美/良好）。
     *
     * <p>被 {@link #maintainHold} 判掉尾巴的 hold 已经 {@code tailDone}，不会再走到这里。
     * 提前松手<b>不</b>在这个方法里处理——那是每帧维持逻辑的事（judge.rs:710-727）。
     */
    private void completeHolds(double sec) {
        for (NoteState s : states) {
            if (!s.holding || s.tailDone) continue;
            if (sec >= s.note.endTime()) {                     // judge.rs:768
                s.tailDone = true;
                s.holding = false;
            }
        }
    }

    /** 正在按住的 hold（渲染 hold 粒子用，note.rs:143-163）。 */
    public List<NoteState> activeHolds() {
        List<NoteState> out = new ArrayList<>();
        for (NoteState s : states) {
            if (s.holding && !s.tailDone) out.add(s);
        }
        return out;
    }

    /**
     * 记一次判定。除了计数，还把事件塞进 {@link #pendingJudgements}——对应
     * judge.rs:797-861 那个「遍历 judgements、按结果发特效」的循环的数据来源。
     */
    private void judge(NoteState s, Judgement.Grade g, double err, double at) {
        if (s.judged) return;
        s.judged = true;
        s.grade = g;
        s.judgedAt = at;
        lastJudgedNote = s;
        judgedCount++;
        pendingJudgements.add(new JudgeEvent(s, g));
        if (g == Judgement.Grade.MISS) {
            miss++;
            combo = 0;
        } else {
            weightSum += Judgement.weight(g);
            switch (g) {
                case PERFECT -> perfect++;
                case GOOD -> good++;
                case BAD -> bad++;
                default -> {
                }
            }
            if (g == Judgement.Grade.PERFECT || g == Judgement.Grade.GOOD) {
                combo++;
                maxCombo = Math.max(maxCombo, combo);
            } else {
                combo = 0;
            }
        }
    }

    /**
     * 取走本帧产生的判定（取走后清空）。表现层拿它发打击特效，
     * 对应 judge.rs:797-861；源码里这条队列是 {@code self.judgements}（judge.rs:268/281）。
     */
    public List<JudgeEvent> drainJudgements() {
        if (pendingJudgements.isEmpty()) return List.of();
        List<JudgeEvent> out = new ArrayList<>(pendingJudgements);
        pendingJudgements.clear();
        return out;
    }

    /**
     * 0 ~ 1000000。900000 来自准确率，100000 来自最大连击（judge.rs:227-235）。
     * 准确率 = (Perfect + Good×0.65) / 音符数（judge.rs:215-217），Bad / Miss 都是 0。
     */
    public int score() {
        if (total == 0) return 0;
        if (perfect == total) return 1000000;
        double acc = weightSum / total;
        double comboPart = (double) maxCombo / total;
        return (int) Math.round(900000.0 * acc + 100000.0 * comboPart);
    }

    /** 准确率，分母固定为音符总数（judge.rs:215-217 {@code accuracy()}）。 */
    public double accuracy() {
        return total == 0 ? 1.0 : weightSum / total;
    }

    /** 实时准确率，分母是「已判定的音符数」（judge.rs:219-225 {@code real_time_accuracy()}）。 */
    public double realTimeAccuracy() {
        return judgedCount == 0 ? 1.0 : weightSum / judgedCount;
    }

    public String rank() {
        return Judgement.rank(score());
    }

    public int combo() {
        return combo;
    }

    public int maxCombo() {
        return maxCombo;
    }

    public int perfect() {
        return perfect;
    }

    public int good() {
        return good;
    }

    public int bad() {
        return bad;
    }

    public int miss() {
        return miss;
    }

    /** hold 提前松手导致的尾巴 Miss 次数（不计入 total，但会断连）。 */
    public int tailMiss() {
        return tailMiss;
    }

    public int total() {
        return total;
    }

    public int judgedCount() {
        return judgedCount;
    }

    public Chart chart() {
        return chart;
    }

    /** 最近一次判定的音符（可能是 Miss）。Miss 没有点击坐标，表现层用它定位。 */
    public NoteState lastJudged() {
        return lastJudgedNote;
    }
}
