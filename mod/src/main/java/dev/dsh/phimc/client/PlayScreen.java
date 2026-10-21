package dev.dsh.phimc.client;

import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.HoldLayout;
import dev.dsh.phimc.survival.Rewards;
import net.minecraft.client.MinecraftClient;
import dev.dsh.phimc.client.respack.ResPack;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 游玩界面：透明 Screen 接管鼠标。
 *
 * <p>渲染用「矩阵 + 单次 fill」：判定线、音符、Hold 尾巴都是旋转后的一个四边形。
 * 旋转中心 = 判定线自身的 (x,y)（prpr line.rs:230-234，线段关于原点对称，所以绕 (x,y) 与绕线段中点等价）。
 *
 * <p><b>单位契约</b>：{@link Engine.Visible#yFrac()} 是「屏高比例」（1.0 = 一整个屏幕高），
 * 由 core 里 {@code n = Δheight * speed / 2} 得到（{@link Chart#HEIGHT_TO_SCREEN}）。
 * 世界坐标里 1 单位 = 半屏宽 = {@code aspect/2} 个屏高，所以世界偏移 {@code (lx, ly)} 换成像素是
 * {@code (lx*W/2, -ly*W/2)}——两个方向同一个系数（因为 {@code aspect*H = W}），这也是判定线旋转
 * 在屏幕上保持各向同性的原因。
 *
 * <p><b>HUD 布局照 {@code prpr/src/scene/game.rs:428-532}</b>：
 * 分数在右上角（{@code score_right = 1 - margin}，{@code anchor(1.,0.)}，margin = 0.03），
 * 连击在正上方居中（{@code anchor(0.5,0.)}，且 {@code combo >= 3} 才显示，game.rs:468），
 * 游戏内没有「判定文字」（源码 HUD 只有 score / combo / 曲名 / 难度 / acc）。
 */
public class PlayScreen extends Screen {

    /** 音符宽度 = 屏宽 × 0.13175016（prpr/src/core.rs:15 NOTE_WIDTH_RATIO_BASE，note_scale 默认 1）。 */
    private static final double NOTE_W_FRAC = 0.13175;

    /**
     * 音符厚度 = 音符宽度 × 这个比例（<b>只用于没有资源包贴图时的纯色矩形</b>）。
     * 就是资源包 click.png 的高宽比 100/998（prpr note.rs:114-128 用 {@code tex.height()/tex.width()} 定高）。
     *
     * <p>为什么不再用「判定线厚度」：线宽只有 {@link #LINE_W_FRAC}=屏宽 0.005，
     * 1280 宽下只有 6px，音符细得像根线、还几乎被 1px 描边吃光（使用方反馈「太细了」）。
     * 纯色兜底里 tap / flick / drag / hold 头统一 = 原来 tap 的厚度（约屏宽 1.3%），
     * 不同音符之间不再有厚薄差。
     *
     * <p><b>有贴图时不用这个常数</b>（否则会和贴图比例冲突）：tap/flick/drag 的高 = 宽 × 贴图高宽比
     * （prpr note.rs:114-128 {@code draw_center}），hold 的头/尾高 = 贴图 atlas 段高 × 缩放
     * （prpr note.rs:324/342，见 {@link HoldLayout#capDestHeight}）。所以装了资源包后 hold 头会比
     * tap 薄（官方包 atlas 段 50px / 989 宽 ≈ 音符宽的 5%，而 tap 是 10%）——这是 Phira 的原样表现，
     * 保留贴图比例优先于「厚度统一」。
     */
    private static final double NOTE_THICK_FRAC = 100.0 / 998.0;

    /**
     * 音符描边粗细（像素）与颜色。
     *
     * <p><b>不是 prpr 的行为</b>：prpr 画的是贴图精灵，没有描边；这里是按使用方要求加的
     * 「很细很细的边」，用来在深浅不一的背景上把音符轮廓勾出来。只画 1px，
     * 且跟随音符自身的 alpha（miss/hold 半透明时描边一起变淡）。
     */
    private static final int NOTE_EDGE_PX = 1;
    private static final int NOTE_EDGE_RGB = 0xFF101014;

    /**
     * prpr note.rs:322-356 里 hold「头/尾」用的浅蓝（{@code 0xFFBFE9FF}）。
     *
     * <p>按使用方要求，<b>它与 hold 主体颜色对调</b>：头不再用这个色，改成用
     * {@link #HOLD_HEAD_RGB}；身体/尾巴用这个浅蓝。
     */
    private static final int HOLD_ACCENT = 0xFFBFE9FF;

    /**
     * hold 头的颜色：<b>浅青</b>。
     *
     * <p>要求是「比 tap 浅、又要和 hold 身体 / tap 都一眼分开」。原来的头直接用了
     * {@code colorOf(HOLD)} = tap 的 {@code #3AA6FF}，和 tap 完全同色；身体是 {@code #BFE9FF}（很浅的蓝）,
     * 纯蓝系里三档亮度挤在一起分不清，所以头改成色相偏青的浅色：
     * 亮度比 tap 高、饱和度比身体高、色相和两者都差 ~25°。
     *
     * <p>三个色（tap / hold 头 / hold 身体）：
     * {@code #3AA6FF}（中蓝） / {@code #8CEFF2}（浅青） / {@code #BFE9FF}（浅蓝）。
     * 想再拉开差距就改这一个常数（例如换成浅紫 {@code 0xFFC9A8FF}）。
     */
    private static final int HOLD_HEAD_RGB = 0xFF8CEFF2;

    /** 连击数字的放大倍数（1 = MC 原字号，一行高 9px）。 */
    private static final float COMBO_SCALE = 2.0f;
    /** 「COMBO」小字的放大倍数。 */
    private static final float COMBO_LABEL_SCALE = 1.0f;
    /** 结算界面每行文字的行高（像素，MC 字体一行 9px，留 9px 空隙）。 */
    private static final int ROW_H = 18;
    /** 结算界面「奖励」那一行的最大像素宽度（超了截断成 …，防止压到右侧曲绘）。 */
    private static final int REWARD_MAX_W = 300;

    /**
     * 判定线总宽 = 屏宽 × 0.005：line.rs:252 线宽 0.01 世界单位，世界 1 单位 = 半个屏宽。
     * 贴图线被缩放过时线宽用 0.0076（line.rs:239/252）；RPE 的 extended.scaleX/scaleY 会乘在
     * 长度/厚度上（line.rs:246 的 now_scale），PEC 固定 scaleX = 3.91/6（pec.rs:166）。
     */
    private static final double LINE_W_FRAC = 0.005;

    /**
     * 判定线半长 = 6 世界单位（info.rs:81 {@code line_length: 6.}，line.rs:251-252
     * {@code draw_line(-len, 0., len, 0., ...)}）⇒ 每侧 3 个屏宽。
     */
    private static final double LINE_HALF_LEN_FRAC = 3.0;

    /** PGR positionX → 屏宽比例：0.1125 世界单位 / 2 = 0.05625（prpr/src/parse/pgr.rs:207）。 */
    private static final double X_FRAC_PER_UNIT = 0.05625;

    /**
     * 判定特效「精灵粒子」的画法：<b>方形边框</b>（不是实心）。
     * 半边长 = 屏宽 × 0.05（沿用旧扩散环的 0.05 尺度），线宽 = 屏宽 × 0.004。
     * 说明：源码这里是 {@code hit_fx.png} 图集精灵（resource.rs:321-333），没有方框；
     * 方框是使用方指定的表现层替换，尺寸/寿命/颜色/位置仍按源码。
     */
    private static final double FX_FRAME_HALF_FRAC = 0.05;
    private static final double FX_FRAME_STROKE_FRAC = 0.004;

    /** HUD 边距，game.rs:423 {@code let margin = 0.03;}。 */
    private static final double HUD_MARGIN = 0.03;

    /**
     * 文字判定线的字号：Phira 的 {@code .size(1.)} 对应 em 高度 = <b>0.04 × 视口宽</b>像素。
     *
     * <p>依据 {@code prpr/src/ui/text.rs:95-97}：
     * {@code fn get_scale(&self, w: i32) -> f32 { 0.04 * self.size * w as f32 }}，
     * 绘制时 {@code .size(1.)}（{@code core/line.rs:296}）。所以与分辨率无关地占屏宽 4%，
     * 一行 20 个字大约占屏宽 40%——实测歌词/字幕正是这个量级。
     */
    private static final double TEXT_EM_FRAC = 0.04;

    /**
     * 文字内容 → MC {@link Text} 的缓存（同一段文字每帧复用，别每帧 new）。
     *
     * <p>打字机动画期间字符串会逐字变化，所以缓存带 LRU 上限（{@link #TEXT_CACHE_MAX}）：
     * 一条长歌词从空串长到 30 个字会留下 30 个中间串，一帧几十条线也就几百个，
     * 上限远大于实际用量，等于永不淘汰，只是防止某首离谱谱面无限增长。
     */
    /**
     * {@code attachUI} 联动开关。
     *
     * <p><b>暂时关闭</b>（用户报「暂停号、分数、combo 全部乱飞了」）。
     * 关闭后分数 / 连击 / 曲名 / 难度 / 暂停 / 进度条全部回到本 mod 原本的固定 HUD 位置，
     * 和 {@code ENABLE_ATTACH_UI = false} 之前的行为一致。
     *
     * <p>实现本身保留在 {@link #uiElem} / {@link #pushUi} / {@link #uiColor} 里，
     * 但<b>坐标口径还没完全对清楚</b>，所以先不用：乱飞比没有更糟。
     *
     * <h2>还没对清楚的那一点（下一轮要解决的）</h2>
     * <p>{@code chart.rs:86-92} 是：
     * <pre>
     * let mut tr = line.fetch_pos(res, lines);   // 判定线世界坐标
     * tr.y = -tr.y;
     * ui.with(Matrix::new_translation(&amp;tr) * ro * scale, ...)
     * </pre>
     * 而 {@code ui.text(...).pos(score_right, score_top)} 用的是 <b>UI 空间</b>坐标
     * （x∈[-1,1]、y 与屏幕同向），{@code ui.with} 是<b>把矩阵叠在 UI 的根变换之上</b>。
     * 也就是说元素最终落在 <b>{@code 默认位置 + 判定线位置}</b>，而不是「判定线位置」。
     * 我们现在的 {@code pushUi} 也是这么叠的（{@code translate(cx,cy)} 之后画在默认像素位置），
     * 但「默认位置」在两边用的不是同一个坐标系（我们用的是本 mod 的 HUD 像素位，Phira 用的是
     * 它自己的 UI 布局常量），所以叠出来的位移量级不一定等价 —— 需要先把
     * {@code Ui::with} 的语义（替换 vs 相乘）以及 {@code pos} 的单位彻底核对，
     * 再把每个元素的 {@code rotation_point}/{@code scale_point} 换成 Phira 的那组常量，才能重开。
     */
    private static final boolean ENABLE_ATTACH_UI = false;

    /** 底部「曲名 / 难度」那一行离屏幕底边的留白（像素），见 {@code bottomLabelY}。 */
    private static final int BOTTOM_LABEL_PAD = 4;

    /** 「贴图线首帧诊断」是否已经打过（每张谱面实例只打一次，免得刷屏）。 */
    private boolean imageLineLogged;

    /** 攒够这么多行「本帧画出的贴图线」就打一次整帧日志（8 行 ≈ 一屏能看出区别）。 */
    private static final int TEX_LOG_LINES = 8;

    /** 还在收集「本帧画了哪些贴图线」；打满一帧后置 false，不再收集。 */
    private boolean texFrameLog = true;

    /** 本帧已经画出的贴图线条数。 */
    private int texFrameCount;

    /** 「本帧画出的贴图线」明细（每帧开头清空）。 */
    private final StringBuilder texFrame = new StringBuilder();


    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("phimc");

    private static final java.util.LinkedHashMap<String, Text> TEXT_CACHE =
            new java.util.LinkedHashMap<>(128, 0.75f, true);
    private static final int TEXT_CACHE_MAX = 2048;

    /** 取（或建）文字对象。只在渲染线程调用。 */
    private static Text textOf(String s) {
        Text t = TEXT_CACHE.get(s);
        if (t != null) return t;
        t = Text.literal(s);
        TEXT_CACHE.put(s, t);
        if (TEXT_CACHE.size() > TEXT_CACHE_MAX) {
            var it = TEXT_CACHE.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        return t;
    }

    /**
     * 游玩背景的压暗系数，对应 prpr {@code info.rs:80} 的 {@code background_dim: 0.6}
     * （{@code game.rs:1187} {@code draw_rectangle(..., Color::new(0,0,0, res.alpha*background_dim))}）。
     *
     * <p>{@code 0x99} = 153/255 ≈ 0.60 的黑，正好是 Phira 的默认值；以前是 {@code 0x66}（0.40），
     * 世界只被压掉 40%。为什么这一项会影响「note 看起来亮不亮」：官方资源包的贴图有大量
     * <b>全透明</b>像素（click.png 7.2%、flick.png <b>53.4%</b>），半透明/透明的地方是背景透上来的。
     * 实测（{@code tools/judge} 里的像素统计）：flick.png 合成到黑底可见亮度 98.7、
     * 合成到底色 110 的亮背景是 158.0（+60%）。背景压得比 Phira 浅，note 整块就显亮。
     */
    private static final int BG_DIM_ARGB = 0x99000000;

    private final Session session;
    /**
     * 从「全部谱面可玩」的选曲界面进来的（谱面播放器 / P 键）。
     *
     * <p>只影响<b>退出时回到哪个形态的曲库</b>：{@code true} 时 {@link Action#EXIT} 会重新打开
     * 一个 {@code unlockAll = true} 的 {@link SongSelectScreen}，否则按平板那套解锁规则回去。
     * 判定 / 计分 / 渲染完全不受它影响。
     */
    private final boolean unlockAll;
    /** 上一次 mouseDragged 的归一化坐标与时间，用来算划动速度（judge.rs:112-134 FlickTracker）。 */
    private double lastDragX = Double.NaN;
    private double lastDragY = Double.NaN;
    private long lastDragNanos;
    /**
     * 每个键「上一次 keyPressed 的时刻」，用来复现 prpr {@code key_down_event} 的 {@code repeat} 参数
     * （judge.rs:1009-1013：只有 {@code !repeat} 才算一次按下）。
     *
     * <p>Minecraft 会把 GLFW 的自动重复（{@code GLFW_REPEAT}）也转发成 {@code Screen#keyPressed}，
     * 所以必须自己滤掉重复事件。这里<b>不</b>用「键码集合 + 等 keyReleased 移除」那种写法：
     * 一旦某次 {@code keyReleased} 丢了（切窗口失焦、GLFW 在失焦时不再补发 up 事件、上一个界面
     * 吃掉了一次释放……），那个键码会永远留在集合里，之后按同一个键全被当成 repeat 丢掉——
     * 表现就是「按什么都没反应」（用户报的密集段不响应，最像这一条）。
     *
     * <p>改成看时间差：GLFW 自动重复的间隔实测 30~50ms，而人手两次独立按下之间必然 ≥ 60ms
     * （而且正常释放会清掉记录）。所以「同一个键 60ms 内又收到一次 keyPressed」= 自动重复，忽略；
     * 超过 60ms = 新的一次按下，照常判定。这样即使丢了 release 也只会影响 60ms，不会永久失效。
     */
    private static final long REPEAT_WINDOW_NANOS = 60_000_000L;      // 60ms
    private final java.util.Map<Integer, Long> lastPressNanos = new java.util.HashMap<>();

    /** 暂停 / 结算界面的按钮动作。 */
    private enum Action { RESUME, RESTART, EXIT }

    private record Btn(int x0, int y0, int x1, int y1, String label, Action action) {
        boolean hit(double mx, double my) {
            return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
        }
    }

    public PlayScreen(Session session) {
        this(session, false);
    }

    /**
     * @param unlockAll 是否来自「全部可玩」的曲库（谱面播放器 / P 键）。只决定退出后回哪个曲库，
     *                  不参与判定 / 计分（见字段注释）。
     */
    public PlayScreen(Session session, boolean unlockAll) {
        super(Text.literal("PhiMC"));
        this.session = session;
        this.unlockAll = unlockAll;
    }

    @Override
    protected void init() {
        super.init();
        // 键盘游玩永远是开的（设置页的开关已删掉，Profile.useKeyboard 只为存档兼容保留）
        session.setUseKeyboard(true);
        // 进游戏前把存档里选的材质包加载好（贴图要注册到 TextureManager，只能在客户端线程做）。
        // 幂等：窗口缩放会重复触发 init，但已经应用过的包不会重载。
        PhiMCClient.ensureResPack();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 1.20.2+ 框架会在 render() 前自动调 renderBackground；1.20/1.20.1 不会，补一次保持观感一致
        //? if <1.20.2 {
        /*this.renderBackground(ctx);
        *///?}
        int w = width;
        int h = height;
        Engine engine = session.engine();
        Chart chart = session.chart();
        if (engine == null || chart == null) {
            close();
            return;
        }
        double aspect = w / (double) h;
        // 判定要用宽高比把点击点换算成 Phira 世界坐标（prpr judge.rs:473-474）。
        // 放在 tick 之前：本帧判定用的就是本帧的宽高比（以前是 20Hz tick，这里天然慢一帧）。
        session.setAspect(aspect);
        // 判定驱动 = 每帧一次（本方法就是每帧回调）。以前只有 20Hz 的客户端 tick 驱动，
        // Drag/Flick 的 PreJudge→Perfect 被量化到 50ms 边界，实测平均晚 26ms、最多 50ms；
        // 挪到这里以后量化到「一帧」（60fps 平均 9ms、最多 17ms；144fps 平均 3.5ms）。
        // tick() 自身幂等 + 可重入安全，游玩界面开着时 20Hz 那条驱动已在
        // PhiMCClient 里挡掉，所以同一帧不会算两遍。
        session.tick();
        double t = session.seconds();

        // 结算：不画谱面图层（判定线 / 音符 / 粒子），只留一个干净的背景
        if (session.finished()) {
            ctx.fill(0, 0, w, h, 0xF0101014);
            drawResult(ctx, w, h, mouseX, mouseY, engine);
            return;
        }

        ctx.fill(0, 0, w, h, BG_DIM_ARGB);

        // ---- 0) 每条判定线「本帧的屏幕变换」只算一次 ----
        // 以前「画线」和「画音符」两处各算一遍，而且音符循环里是**每个音符**都重算
        // xAt() / yAt() / rotateAt()（各一次二分查找）。prpr 不是这么干的：它每条线 push 一次
        // 模型矩阵（core/line.rs:240 with_model(now_transform)），线内所有音符都在那个矩阵里画，
        // 一个音符一次求值都没有。这里照同样的思路，把「每条线每帧的变换」缓存下来。
        // 懒算：只有真正要用到的线才算（大谱面 741 条线，绝大多数当帧不可见）。
        LineXform xf = new LineXform(chart, t, aspect, w, h);

        // ---- 1) 判定线 ----
        // 颜色就是 FC/AP 指示器（game.rs:1029-1037）：全 Perfect = 金色、出过 Good = 蓝色、出过 Bad/Miss = 白色。
        int lineColor = session.judgeLineColor();
        int lineHalf = Math.max(1, (int) Math.round(w * LINE_W_FRAC * 0.5));
        ResPack pack = ResPack.current();
        ResPack.Tex lineTex = pack == null ? null : pack.tex("line");
        // 贴图线用的动态贴图（换曲目时整批换掉；每帧限量新建，避免首帧把 100 张图全解一遍）
        LineTextures.beginFor(session.current());
        LineTextures.beginFrame();
        // 首帧诊断（只打一次）：本首谱有多少条贴图线、其中多少条的贴图取不到。
        if (!imageLineLogged) {
            imageLineLogged = true;
            int imgLines = 0;
            int missing = 0;
            for (Chart.Line l : chart.lines) {
                if (!l.imageLine) continue;
                imgLines++;
                if (LineTextures.get(session.current(), l.textureName) == null) missing++;
            }
            LOG.info("[PhiMC] 贴图线诊断: 共 {} 条 imageLine，首帧取不到贴图 {} 条"
                            + "（attachUI 线 {} 条，它们不画贴图）",
                    imgLines, missing,
                    chart.lines.stream().filter(l -> l.attachUi != 0).count());
        }
        // 每帧清空「本帧画了哪些贴图线」的收集器，攒满 TEX_LOG_LINES 行就整帧打一次。
        // ⚠ 上一版是「前 8 次绘制」计数，**跨帧累加** —— 结果 8 行日志全是同一帧的同一张静态图，
        // 看上去像「所有线都画成同一条」，其实只是日志口径错了（headless 复刻已证明一帧内画的是
        // 4~10 条互不相同的线）。
        texFrame.setLength(0);
        texFrameCount = 0;
        // 绘制顺序 = (zOrder, 下标) 升序（chart.rs:46-56 的 order）。以前按下标画，
        // 曲库里 8 首谱的 zOrder 不是按下标递增的，叠线时前后关系会和 Phira 相反。
        for (int slot : chart.drawOrder) {
            Chart.Line line = chart.lines.get(slot);
            // 贴图线（Texture != line.png）：Phira 画的是**那张图本身**（line.rs:254-290），
            // 不是横条。这些线就是「特效谱」的全部画面
            // （enchanted love 250/287 条、Undertale 129/177 条）。
            if (line.imageLine) {
                drawTextureLine(ctx, line, slot, xf, t, w, h, session);
                continue;
            }
            // 文字线（`line.png` + 有 textEvents）：Phira 画的是**文字本身**
            // （core/line.rs:291-298 的 JudgeLineKind::Text），不是横条。
            if (line.kind == Chart.Line.KIND_TEXT) {
                drawTextLine(ctx, line, slot, xf, t, w);
                continue;
            }
            // attachUI 线 / paint 线：Phira 画的是 UI 元素、全屏绘制（chart.rs:46-55、line.rs:299-356），
            // 我们也不画——否则会变成一条横贯屏幕的亮条（用户报的「神秘的横向线」就是这个）。
            if (!line.drawsAsBar()) continue;
            double alpha = line.alphaAt(t);
            if (alpha <= 0.01) continue;                 // line.rs:237
            // line.rs:246 now_scale：scaleX 缩放长度、scaleY 缩放厚度；乘到 0 就是不可见
            double sx = line.scaleXAt(t);
            double sy = line.scaleYAt(t);
            if (Math.abs(sx) <= 1e-4 || Math.abs(sy) <= 1e-4) continue;
            xf.ensure(slot);
            double cx = xf.cx[slot];
            double cy = xf.cy[slot];
            double ang = xf.rotRad[slot];                // 已经取过反（Phira 世界 y 向上，MC 屏幕 y 向下）
            // line.rs:238,249-250：谱面给了 colorEvents 就用谱面颜色（alpha 恒 1），否则用指示色；
            // 两种情况都再乘判定线自己的 alpha（color.a *= alpha）。
            int base = line.hasColor()
                    ? rgbOf(line.colorAt(t, 0), line.colorAt(t, 1), line.colorAt(t, 2))
                    : lineColor;
            double baseA = ((base >>> 24) & 0xFF) / 255.0;
            int color = withAlpha(base, alpha * baseA);
            int len = Math.max(1, (int) Math.round(w * LINE_HALF_LEN_FRAC * sx));
            // line.rs:239/252：线被缩放过时线宽用 0.0076 而不是 0.01
            int half = Math.max(1, (int) Math.round(lineHalf * sy * (Math.abs(sy - 1.0) > 1e-4 ? 0.76 : 1.0)));
            RenderCompat.Transform ms = RenderCompat.transform(ctx);
            ms.push();
            ms.translate((float) cx, (float) cy);
            ms.rotate((float) ang);
            if (lineTex != null) {
                // 资源包提供 line.png 就贴图（横向拉满整条线），颜色仍按判定色染
                drawTexTinted(ctx, lineTex, -len, -half, len * 2, half * 2, alpha * baseA);
            } else {
                ctx.fill(-len, -half, len, half, color);
            }
            ms.pop();
        }

        // ---- 2) 音符 ----
        List<Engine.Visible> visible = engine.visible(t, aspect);
        // 画序：prpr 把每个音符按 `NoteKind::order()`（core/note.rs:21-30，Hold=0 < Drag=1 <
        // Click=2 < Flick=3）塞进同一个顶点缓冲再排序（note.rs:242、109-111），所以叠在一起的
        // 音符是「hold 在最下、flick 在最上」。我们以前按时间序画，重叠时的前后关系是反的。
        // 用稳定排序，同一种音符之间仍然保持时间序。
        visible.sort(java.util.Comparator.comparingInt(v -> noteDrawOrder(v.state().note.type)));
        double[] holdFloorNow = new double[chart.lines.size()];
        boolean[] holdFloorDone = new boolean[chart.lines.size()];
        for (Engine.Visible v : visible) {
            Chart.Note n = v.state().note;
            Chart.Line line = v.state().line;
            int slot = v.state().lineSlot();
            xf.ensure(slot);
            double cx = xf.cx[slot];
            double cy = xf.cy[slot];
            double ang = xf.rotRad[slot];

            // RPE 有音符级缩放（note.size × sizeControl，note.rs:185-189）；PGR 恒为 1。
            // noteH 只在「没有贴图」的纯色兜底里用（有贴图时高由贴图比例决定，见 NOTE_THICK_FRAC）。
            int noteW = Math.max(1, (int) Math.round(w * NOTE_W_FRAC * v.scale()));
            int noteH = Math.max(2, (int) Math.round(noteW * NOTE_THICK_FRAC));
            int edge = Math.max(1, NOTE_EDGE_PX);
            int edgeColor = withAlpha(NOTE_EDGE_RGB, v.alpha());

            double dx0 = n.x * X_FRAC_PER_UNIT * w;
            double dy0 = -v.yFrac() * h;   // yFrac 单位 = 屏高比例；正 = 判定线之上
            // Hold 一过判定时间，头就钉在判定线上（prpr note.rs:287 `if self.time <= res.time { line_height }`）
            if (n.type == Chart.Note.Type.HOLD && t >= n.time) dy0 = 0;

            double rx = dx0 * Math.cos(ang) - dy0 * Math.sin(ang);
            double ry = dx0 * Math.sin(ang) + dy0 * Math.cos(ang);
            int x = (int) (cx + rx);
            int y = (int) (cy + ry);
            int color = withAlpha(colorOf(n.type), v.alpha());

            // 资源包里这张贴图，没有就退回纯色矩形（见 ResPack 的说明）
            ResPack.Tex tex = pack == null ? null : pack.tex(slotOf(n.type));

            RenderCompat.Transform ms = RenderCompat.transform(ctx);
            ms.push();
            ms.translate((float) x, (float) y);
            ms.rotate((float) ang);

            if (n.type == Chart.Note.Type.HOLD) {
                // 身体：从判定线（或头部实际位置）到尾巴。尾巴高度 = 高度曲线在 endTime 的值
                // （note.rs:284-289：top = end_height - line_height，bottom = h - line_height）。
                // floorPositionAt 是线性扫速度事件 ⇒ 同一条线这一帧只算一次（以前每个 hold 算两次）。
                if (!holdFloorDone[slot]) {
                    holdFloorNow[slot] = line.floorPositionAt(t);
                    holdFloorDone[slot] = true;
                }
                double nowH = holdFloorNow[slot];
                double endH = line.floorPositionAt(n.time + n.hold);
                // n = Δheight * speed / 2，hold 的 speed 恒为 1（pgr.rs:213）
                double tailFromLine = (endH - nowH) * Chart.HEIGHT_TO_SCREEN * h;
                double tailLen = Math.max(0.0, tailFromLine - Math.abs(dy0));
                int dir = dy0 < -1e-6 ? -1 : (dy0 > 1e-6 ? 1 : (n.above ? -1 : 1));
                int tailPx = dir * (int) Math.round(tailLen);
                int bodyTop = Math.min(0, tailPx);
                int bodyBottom = Math.max(0, tailPx);
                if (tex != null) {
                    // prpr note.rs:270-357：身体向「远离判定线」的方向长，头/尾整块贴在身体两端**外侧**；
                    // 判定线下面（above=false）的音符在源码里是整个模型转 180°（note.rs:132-134），
                    // 这里等价成「画在向上坐标系里，再绕原点转 180°」。
                    if (dir > 0) {
                        ms.rotate((float) Math.PI);
                    }
                    // 头只在命中前画（note.rs:322 `res.time < self.time || hold_keep_head`）
                    boolean drawHead = t < n.time || pack.holdKeepHead();
                    drawHoldTexture(ctx, pack, tex, noteW, bodyBottom - bodyTop, drawHead, v.alpha(),
                            n.tintR, n.tintG, n.tintB);
                } else {
                    // 用户要求：hold 头 与 hold 主体<b>颜色对调</b>（头不再是主体的蓝，身体改用浅蓝）
                    int holdBodyColor = withAlpha(HOLD_ACCENT, v.alpha());
                    int holdHeadColor = withAlpha(HOLD_HEAD_RGB, v.alpha());
                    if (bodyBottom > bodyTop) {
                        ctx.fill(-noteW / 2, bodyTop, noteW / 2, bodyBottom, holdBodyColor);
                        drawFrame(ctx, -noteW / 2, bodyTop, noteW / 2, bodyBottom, edge, edgeColor);
                    }
                    // 头的厚度 = 音符厚度（统一成「上一版 tap 厚度」）
                    int capH = noteH;
                    // 头：在判定线那一端（note.rs:322-339）。
                    // 尾巴**不再单独画一块**：prpr 那边尾巴是贴图末端（hold_tail_rect），同贴图同颜色，
                    // 看起来就是身体自然收尾；我们画的是纯色矩形，再补一块 + 描边就变成「结尾多出一个方框」。
                    if (dir < 0) {
                        ctx.fill(-noteW / 2, -capH, noteW / 2, 0, holdHeadColor);
                        drawFrame(ctx, -noteW / 2, -capH, noteW / 2, 0, edge, edgeColor);
                    } else {
                        ctx.fill(-noteW / 2, 0, noteW / 2, capH, holdHeadColor);
                        drawFrame(ctx, -noteW / 2, 0, noteW / 2, capH, edge, edgeColor);
                    }
                }
            } else if (tex != null) {
                // note.rs:114-129 draw_center：宽 = note_width，高 = 宽 × texH/texW（保持贴图比例）
                int drawH = Math.max(2, (int) Math.round(noteW * tex.aspect()));
                // note.rs:213-217：贴图 × (note.tint, alpha) —— PGR 的 tint 恒白，
                // RPE 取自谱面的 tint 三元组（见 Chart.Note.tintR）
                drawTexTinted(ctx, tex, -noteW / 2, -drawH / 2, noteW, drawH, v.alpha(),
                        n.tintR, n.tintG, n.tintB);
            } else {
                ctx.fill(-noteW / 2, -noteH / 2, noteW / 2, noteH / 2, color);
                // 细描边（1px）：不是 prpr 的行为，按使用方要求加
                drawFrame(ctx, -noteW / 2, -noteH / 2, noteW / 2, noteH / 2, edge, edgeColor);
            }
            ms.pop();
        }

        // ---- 3) 打击特效（prpr 粒子系统）----
        drawParticles(ctx, t, w, h);

        // 曲名（左下）/ 难度（右下）
        MinecraftClient mcBar = MinecraftClient.getInstance();
        int mgBar = Math.max(8, (int) Math.round(w * HUD_MARGIN));
        String titleTxt = session.songTitle();
        String levelTxt = session.songLevel();
        int levelW = mcBar.textRenderer.getWidth(levelTxt);
        // 底部这一行文字的 y（文字**顶边**，MC 字体一行 9px）。
        // 以前写死 h-34 = 离底边 34px，视觉上贴近底边；现在上移 10px。
        //   h=720 → y=676（文字 676..685，离底边 35px）
        //   h=480 → y=436（文字 436..445，离底边 35px）
        // 用「离底边固定像素」而不是固定比例：两个分辨率下留白一致，不会被底边裁掉，
        // 也不会碰到 MC 自己的底部区域（快捷栏/提示行在 y≈h-22 以下）。
        int bottomLabelY = h - BOTTOM_LABEL_PAD - 9;
        // ---- 4) HUD（布局照 game.rs:428-549）----
        MinecraftClient mc = MinecraftClient.getInstance();
        int margin = Math.max(8, (int) Math.round(w * HUD_MARGIN));
        boolean aui = ENABLE_ATTACH_UI;
        // 1 暂停按钮（UIElement::Pause，game.rs:455-467）/ 2 连击数字 / 3 COMBO 小字 /
        // 4 分数 / 5 进度条 / 6 曲名 / 7 难度
        UiElem eScore = aui ? uiElem(chart, 4, t, aspect, w, h, w - margin, margin) : UiElem.none();
        UiElem eCombo = aui ? uiElem(chart, 2, t, aspect, w, h, w / 2f, margin) : UiElem.none();
        UiElem eComboLabel = aui ? uiElem(chart, 3, t, aspect, w, h, w / 2f, margin) : UiElem.none();
        UiElem eName = aui ? uiElem(chart, 6, t, aspect, w, h, mgBar, bottomLabelY) : UiElem.none();
        UiElem eLevel = aui ? uiElem(chart, 7, t, aspect, w, h, w - mgBar - levelW, bottomLabelY) : UiElem.none();
        UiElem eBar = aui ? uiElem(chart, 5, t, aspect, w, h, 0f, margin * 0.5f) : UiElem.none();
        UiElem ePause = aui ? uiElem(chart, 1, t, aspect, w, h, margin, margin) : UiElem.none();

        RenderCompat.Transform hud = RenderCompat.transform(ctx);
        // 曲名 / 难度
        pushUi(hud, eName);
        ctx.drawText(mcBar.textRenderer, titleTxt, mgBar, bottomLabelY, uiColor(eName, 0xFFCCCCCC), false);
        hud.pop();
        pushUi(hud, eLevel);
        ctx.drawText(mcBar.textRenderer, levelTxt, w - mgBar - levelW, bottomLabelY, uiColor(eLevel, 0xFFCCCCCC), false);
        hud.pop();

        // 分数：右上角，右对齐（game.rs:431-444 score_right = 1 - margin, anchor(1.,0.), size 0.8）
        String score = String.format("%07d", engine.score());
        pushUi(hud, eScore);
        ctx.drawText(mc.textRenderer, score,
                w - margin - mc.textRenderer.getWidth(score), margin, uiColor(eScore, 0xFFFFFFFF), true);
        hud.pop();
        // 右上角评级显示已按用户要求删除（分数保留）
        // 自动游玩标记：左上角一个小框（game.rs 没有这个东西，是本 mod 的可见性提示）。
        // 位置 = (margin-3, margin-3) 起，宽 = 文字 + 6、高 = 15；分数在右上、连击在正上，
        // 这一列只有它，1280×720 与 854×480 都不与其它 HUD 文字重叠（margin 分别 38 / 26）。
        if (session.autoplay()) {
            String auto = "AUTO";
            int autoW = mc.textRenderer.getWidth(auto);
            ctx.fill(margin - 3, margin - 3, margin + autoW + 4, margin + 12, 0x90000000);
            ctx.fill(margin - 3, margin - 3, margin + autoW + 4, margin - 2, 0xFFFFEC9F);
            ctx.drawText(mc.textRenderer, auto, margin, margin, 0xFFFFEC9F, true);
        }
        // 连击：正上方居中（game.rs:468-515 anchor(0.5,0.)，combo >= 3 才显示），下面是 COMBO 小字。
        // 字号由 COMBO_SCALE / COMBO_LABEL_SCALE 控制（1 = MC 原字号，一行 9px 高）。
        if (engine.combo() >= 3) {
            String combo = Integer.toString(engine.combo());
            int digitH = (int) Math.ceil(9 * COMBO_SCALE);      // 数字占的高度（含阴影）
            pushUi(hud, eCombo);
            hud.push();
            hud.scale(COMBO_SCALE, COMBO_SCALE);
            ctx.drawText(mc.textRenderer, combo,
                    (int) (w / 2f / COMBO_SCALE - mc.textRenderer.getWidth(combo) / 2f),
                    (int) (margin / COMBO_SCALE),
                    uiColor(eCombo, 0xFFFFFFFF), true);
            hud.pop();
            hud.pop();
            String label = "COMBO";
            pushUi(hud, eComboLabel);
            hud.push();
            hud.scale(COMBO_LABEL_SCALE, COMBO_LABEL_SCALE);
            ctx.drawText(mc.textRenderer, label,
                    (int) (w / 2f / COMBO_LABEL_SCALE - mc.textRenderer.getWidth(label) / 2f),
                    (int) ((margin + digitH + 3) / COMBO_LABEL_SCALE),
                    uiColor(eComboLabel, 0xFFDDDDDD), true);
            hud.pop();
            hud.pop();
        }
        // 进度条（UIElement::Bar，game.rs:542-549）：只有启用 attachUI 且谱面给了 "bar" 线才画
        if (eBar.attached()) {
            double dur = Math.max(0.001, chart.duration);
            double prog = Math.max(0.0, Math.min(1.0, t / dur));
            int barH = Math.max(1, (int) Math.round(h * 0.003));
            int barY = Math.max(0, margin / 2);
            int barW = (int) Math.round(w * prog);
            pushUi(hud, eBar);
            ctx.fill(0, barY, barW, barY + barH, uiColor(eBar, 0x99FFFFFF));
            ctx.fill(Math.max(0, barW - 3), barY, barW + 3, barY + barH, uiColor(eBar, 0xFFFFFFFF));
            hud.pop();
        }
        // 暂停按钮（UIElement::Pause，game.rs:455-467 两个矩形）
        if (ePause.attached()) {
            int pw = Math.max(3, (int) Math.round(w * 0.015));
            int ph = Math.max(6, (int) Math.round(pw * 3.2));
            int px = margin + pw * 2;
            int py = margin + ph / 2;
            pushUi(hud, ePause);
            ctx.fill(px - pw, py - ph / 2, px, py + ph / 2, uiColor(ePause, 0xFFFFFFFF));
            ctx.fill(px + pw, py - ph / 2, px + pw * 2, py + ph / 2, uiColor(ePause, 0xFFFFFFFF));
            hud.pop();
        }

        // ---- 5) 暂停 / 结算覆盖层 ----
        if (session.finished()) {
            drawResult(ctx, w, h, mouseX, mouseY, engine);
        } else if (session.paused()) {
            drawPause(ctx, w, h, mouseX, mouseY);
        } else {
            // 键盘游玩恒开（设置页开关已删）：任意键都是 Tap/Hold（judge.rs:649-698），
            // Flick 靠按住任意键自动预判，空格是额外的甩键。
        // 底部游玩提示已按用户要求删除
        }
    }

    /**
     * 特效绘制。粒子系统的参数照 prpr：
     * <ul>
     *   <li>寿命 = hitFxDuration（0.5s，resource.rs:35-37；particle.rs:708 到点即删）</li>
     *   <li>颜色曲线 start→mid(0.5)→end（resource.rs:311-318，particle.rs:668-677）：α 走 1 → 0.7 → 0</li>
     *   <li>方粒子：{@code velocity += velocity * (-6) * dt}（particle.rs:664），即 v(t)=v0·e^(−6t)，
     *       位移 = v0·(1−e^(−6t))/6</li>
     *   <li>精灵粒子静止（initial_velocity = 0、spread = 0），只是原地淡出</li>
     * </ul>
     */
    private void drawParticles(DrawContext ctx, double t, int w, int h) {
        for (Session.Particle p : session.recentParticles()) {
            double age = t - p.time();
            if (age < 0) age = 0;
            if (age > Session.HIT_FX_LIFETIME) continue;
            double k = age / Session.HIT_FX_LIFETIME;
            double curve = k < 0.5
                    ? 1.0 + (Session.HIT_FX_MID_ALPHA - 1.0) * (k / 0.5)
                    : Session.HIT_FX_MID_ALPHA * (1.0 - (k - 0.5) / 0.5);
            double baseA = ((p.argb() >>> 24) & 0xFF) / 255.0;
            double alpha = baseA * curve;
            if (alpha <= 0.01) continue;
            int px = (int) Math.round(p.xFrac() * w);
            int py = (int) Math.round(p.yFrac() * h);
            int rgb = p.argb() & 0x00FFFFFF;

            switch (p.kind()) {
                case SPRITE -> {
                    // 方框（不是实心）：见 FX_FRAME_* 的注释
                    int half = (int) Math.round(w * FX_FRAME_HALF_FRAC);
                    int stroke = Math.max(1, (int) Math.round(w * FX_FRAME_STROKE_FRAC));
                    drawFrame(ctx, px - half, py - half, px + half, py + half, stroke,
                            withAlpha(0xFF000000 | rgb, alpha));
                }
                case SQUARE -> {
                    // v(t) = v0·e^(−6t)，位移 = v0(1−e^(−6t))/6（世界单位 → 像素乘 W/2）
                    double decay = 1.0 - Math.exp(Session.HIT_FX_SQUARE_ACCEL * age);   // accel 为负
                    double scale = decay / -Session.HIT_FX_SQUARE_ACCEL;
                    double dx = p.vx() * scale * w / 2.0;
                    double dy = -p.vy() * scale * w / 2.0;
                    int half = Math.max(1, (int) Math.round(Session.HIT_FX_SQUARE_SIZE * w / 2.0));
                    int cx = (int) Math.round(px + dx);
                    int cy = (int) Math.round(py + dy);
                    ctx.fill(cx - half, cy - half, cx + half, cy + half, withAlpha(0xFF000000 | rgb, alpha));
                }
                case BAD_NOTE -> {
                    // BadNote：judge.rs:834-855 把音符矩阵丢进 bad_notes，note.rs:376-396 原样重画，
                    // 透明度 = (time-now)/0.5 + 1，寿命 0.5s（note.rs:10 BAD_TIME）。
                    double a = 1.0 - age / Session.HIT_FX_LIFETIME;
                    if (a <= 0) continue;
                    // 残影和音符本体同厚（厚度 = 上一版 tap 的厚度）
                    int halfW = (int) Math.round(w * NOTE_W_FRAC / 2.0);
                    int halfH = Math.max(1, (int) Math.round(w * NOTE_W_FRAC * NOTE_THICK_FRAC / 2.0));
                    ctx.fill(px - halfW, py - halfH, px + halfW, py + halfH,
                            withAlpha(0xFF000000 | rgb, a));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 材质包（用户自己的 Phira/Phigros 资源包）
    // ------------------------------------------------------------------

    /** 音符种类 → 资源包里的贴图名（prpr resource.rs:218-225 的 NoteStyle 字段名）。 */
    private static String slotOf(Chart.Note.Type type) {
        return switch (type) {
            case TAP -> "click";
            case HOLD -> "hold";
            case FLICK -> "flick";
            case DRAG -> "drag";
        };
    }

    /**
     * 贴一整张图到 {@code (x, y, w, h)}，用当前矩阵（旋转/平移已经在里面了）。
     *
     * <p>用 {@code DrawContext.setShaderColor} 上透明度：1.21.1 的 {@code drawTexture} 没有颜色参数，
     * 但 GUI 贴图 shader 会乘 {@code ColorModulator}（{@code position_tex.fsh}：
     * {@code fragColor = texture(Sampler0, texCoord0) * ColorModulator}），
     * 所以「先设色、drawTexture、再复位」等价于染色 + 淡出。
     *
     * <p><b>和 Phira 的对应关系</b>：{@code prpr/src/core/note.rs:213-217} 把
     * {@code color = {a: now_alpha * res.alpha * ctrl_alpha, ..note.color}} 交给
     * {@code draw_tex(..., color, ...)}，也就是「贴图 × (note.tint, note.alpha)」。
     * 这里同样乘：RGB = {@code note.tint}（PGR 恒白，RPE 取自 {@code tint}），
     * A = {@code Engine.Visible.alpha}（已含淡出 / 音符 alpha / 控制器 alpha）。
     */
    private static void drawTexTinted(DrawContext ctx, ResPack.Tex tex, int x, int y, int w, int h, double alpha) {
        drawTexTinted(ctx, tex, x, y, w, h, alpha, 1.0, 1.0, 1.0);
    }

    /** 同 {@link #drawTexTinted(DrawContext, ResPack.Tex, int, int, int, int, double)}，额外乘一个 RGB。 */
    private static void drawTexTinted(DrawContext ctx, ResPack.Tex tex, int x, int y, int w, int h,
                                      double alpha, double r, double g, double b) {
        drawTexRegion(ctx, tex, x, y, w, h, 0f, 0f, tex.width(), tex.height(), alpha, r, g, b);
    }

    /** 贴图里的一块区域（u/v/宽高都是**纹理像素**）拉伸到 {@code (x, y, w, h)}。 */
    private static void drawTexRegion(DrawContext ctx, ResPack.Tex tex, int x, int y, int w, int h,
                                      float u, float v, int regionW, int regionH, double alpha) {
        drawTexRegion(ctx, tex, x, y, w, h, u, v, regionW, regionH, alpha, 1.0, 1.0, 1.0);
    }

    private static void drawTexRegion(DrawContext ctx, ResPack.Tex tex, int x, int y, int w, int h,
                                      float u, float v, int regionW, int regionH, double alpha,
                                      double r, double g, double b) {
        if (w <= 0 || h <= 0 || regionW <= 0 || regionH <= 0) return;
        float a = (float) Math.max(0.0, Math.min(1.0, alpha));
        if (a <= 0.004f) return;
        // 染色 + 淡出：1.21.1 走 setShaderColor、1.21.9 走 drawTexture 的颜色参数（见 RenderCompat）。
        // RGB 用 note.tint（PGR 恒白，RPE 取自 tint），A 是 Engine.Visible.alpha。
        RenderCompat.drawTexture(ctx, tex.id(), x, y, w, h, u, v, regionW, regionH,
                tex.width(), tex.height(), a, r, g, b);
    }

    /**
     * Hold 的贴图绘制，照 prpr {@code core/note.rs:270-357} 的三段式。
     *
     * <p>贴图是一张竖条（{@code hold.png}），{@code info.yml} 的 {@code holdAtlas: [tailPx, headPx]}：
     * <b>顶部 {@code tailPx} 行是尾巴、底部 {@code headPx} 行是头、中间是身体</b>
     * （{@code resource.rs:168-182} 的 {@code hold_head_rect / hold_body_rect / hold_tail_rect}，
     * 几何推导见 {@link HoldLayout}）。三段的分法：
     * <ul>
     *   <li>身体 = 中间那段，源 y 从 {@code tailPx} 开始、高 {@code bodyPx}；
     *       {@code holdRepeat=false} 时竖着<b>拉伸</b>，{@code true} 时按贴图自然比例<b>平铺</b>（源码 Repeat wrap）；</li>
     *   <li>头的源 = 贴图倒数 {@code headPx} 行，整块贴在身体「靠判定线」那一端的外侧；</li>
     *   <li>尾巴的源 = 贴图顶部 {@code tailPx} 行，整块贴在身体远端的外侧。</li>
     * </ul>
     * 头/尾的绘制高度 = {@code capPx * noteW / texW}（= Phira 的
     * {@code 2 * hf.y = 2 * (capPx/texH) * (noteW/2) * (texH/texW)}，{@link HoldLayout#capDestHeight}），
     * 也就是「整张贴图宽度缩到 noteW 时的高度」，<b>不再套 NOTE_THICK_FRAC</b>
     * （那个常数只用于没有贴图的纯色兜底，见类注释）。
     *
     * <p>坐标系：调用方已经把原点平移到「头端点」，+y = 屏幕向下；本方法按「身体朝 -y」画，
     * 判定线下面的音符由调用方先转 180°（prpr note.rs:132-134 的 {@code above=false}）。
     *
     * @param tailLen 身体长度（像素，>= 0）
     * @param drawHead 是否画头（prpr：{@code res.time < self.time || holdKeepHead}）
     */
    private static void drawHoldTexture(DrawContext ctx, ResPack pack, ResPack.Tex tex, int noteW,
                                        int tailLen, boolean drawHead, double alpha,
                                        double tintR, double tintG, double tintB) {
        int texW = tex.width();
        int texH = tex.height();
        HoldLayout layout = HoldLayout.of(texW, texH, pack.holdTailPx(), pack.holdHeadPx());
        if (!layout.hasAtlas()) {
            // info.yml 没给 holdAtlas：整张图当身体拉伸，头尾不单画（退化但仍比纯色好看）
            if (tailLen > 0) {
                drawTexRegion(ctx, tex, -noteW / 2, -tailLen, noteW, tailLen, 0f, 0f, texW, texH,
                        alpha, tintR, tintG, tintB);
            }
            return;
        }
        int headH = layout.capDestHeight(noteW, layout.headPx());
        int tailH = layout.capDestHeight(noteW, layout.tailPx());

        // 身体：中间那段（note.rs:294-320）。本地坐标 [-tailLen, 0]，0 = 头端点。
        if (tailLen > 0 && layout.bodyPx() > 0) {
            if (pack.holdRepeat()) {
                // 平铺：每一圈 = 身体纹理按自然比例的高度，最后一圈可能被裁掉一部分
                // （源码是给身体纹理设 Repeat wrap，见 resource.rs:237-254；这里手动画多块，效果等价）
                int tileH = Math.max(1, layout.bodyTileHeight(noteW));
                int done = 0;
                while (done < tailLen) {
                    int h = Math.min(tileH, tailLen - done);
                    int srcH = Math.max(1, Math.min(layout.bodyPx(),
                            (int) Math.round((double) layout.bodyPx() * h / tileH)));
                    drawTexRegion(ctx, tex, -noteW / 2, -tailLen + done, noteW, h,
                            0f, layout.bodySrcY(), texW, srcH, alpha, tintR, tintG, tintB);
                    done += h;
                }
            } else {
                // 拉伸：源是整段身体，dest 高度 = 实际长度（源码 dest_size.y = top - bottom）
                drawTexRegion(ctx, tex, -noteW / 2, -tailLen, noteW, tailLen,
                        0f, layout.bodySrcY(), texW, layout.bodyPx(), alpha, tintR, tintG, tintB);
            }
        }
        // 头：判定线那一端，整块在端点**外侧**（note.rs:321-339，`bottom - hf.y * 2`）。
        // 命中后不再画，否则会看到头挂在判定线上。
        if (drawHead && layout.headPx() > 0) {
            int y = pack.holdCompact() ? -(headH / 2) : 0;   // compact: 以端点为中点
            drawTexRegion(ctx, tex, -noteW / 2, y, noteW, headH,
                    0f, layout.headSrcY(), texW, layout.headPx(), alpha, tintR, tintG, tintB);
        }
        // 尾巴：远端，整块在身体顶端**外侧**（note.rs:340-356，`top` / `top - hf.y`）
        if (layout.tailPx() > 0) {
            int y = pack.holdCompact() ? -tailLen - tailH / 2 : -tailLen - tailH;
            drawTexRegion(ctx, tex, -noteW / 2, y, noteW, tailH,
                    0f, layout.tailSrcY(), texW, layout.tailPx(), alpha, tintR, tintG, tintB);
        }
    }

    /** 空心矩形（四条边），坐标是像素。 */
    private static void drawFrame(DrawContext ctx, int x0, int y0, int x1, int y1, int stroke, int color) {
        int s = Math.max(1, stroke);
        if (x1 - x0 <= 0 || y1 - y0 <= 0) return;
        ctx.fill(x0, y0, x1, Math.min(y1, y0 + s), color);            // 上
        ctx.fill(x0, Math.max(y0, y1 - s), x1, y1, color);            // 下
        if (y1 - y0 > 2 * s) {                                        // 太薄就不画左右两条，避免反向矩形
            ctx.fill(x0, y0 + s, x0 + s, y1 - s, color);              // 左
            ctx.fill(x1 - s, y0 + s, x1, y1 - s, color);              // 右
        }
    }

    // ------------------------------------------------------------------
    // 暂停菜单（game.rs:554-649 的三个按钮：退出 / 重来 / 继续）
    // ------------------------------------------------------------------

    private List<Btn> pauseButtons(int w, int h) {
        // 源码三个按钮横向排布、整体居中（game.rs:561-586，ct = (s*2+w)*i，i ∈ {-1,0,1}）。
        // 原来第 4 个「键盘 开/关」已经删掉：键盘游玩恒开，设置页也不再给开关。
        List<Btn> out = new ArrayList<>();
        int bw = 120;
        int bh = 28;
        int gap = 12;
        int totalW = bw * 3 + gap * 2;
        int x = w / 2 - totalW / 2;
        int y = h / 2 - bh / 2;
        out.add(new Btn(x, y, x + bw, y + bh, "退出", Action.EXIT));
        out.add(new Btn(x + bw + gap, y, x + bw * 2 + gap, y + bh, "重来", Action.RESTART));
        out.add(new Btn(x + (bw + gap) * 2, y, x + (bw + gap) * 2 + bw, y + bh, "继续", Action.RESUME));
        return out;
    }

    private void drawPause(DrawContext ctx, int w, int h, int mouseX, int mouseY) {
        // game.rs:559 暂停时铺 0.6 透明度的黑
        ctx.fill(0, 0, w, h, 0x99000000);
        MinecraftClient mc = MinecraftClient.getInstance();
        String title = "已暂停";
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(title), w / 2, h / 2 - 60, 0xFFFFFFFF);
        for (Btn b : pauseButtons(w, h)) {
            drawButton(ctx, mc, b, b.hit(mouseX, mouseY));
        }
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal("Esc 继续"),
                w / 2, h / 2 + 50, 0xFFAAAAAA);
    }

    // ------------------------------------------------------------------
    // 结算界面（prpr/src/scene/ending.rs：分数、准确率、最大连击、各判定数量、重来/继续按钮）
    // ------------------------------------------------------------------

    private List<Btn> resultButtons(int w, int h) {
        List<Btn> out = new ArrayList<>();
        int bw = 140;
        int bh = 28;
        int gap = 16;
        int x = w / 2 - (bw * 2 + gap) / 2;
        int y = h / 2 + 78;
        out.add(new Btn(x, y, x + bw, y + bh, "重来", Action.RESTART));
        out.add(new Btn(x + bw + gap, y, x + bw * 2 + gap, y + bh, "退出", Action.EXIT));
        return out;
    }

    private void drawResult(DrawContext ctx, int w, int h, int mouseX, int mouseY, Engine engine) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.fill(0, 0, w, h, 0xCC000000);
        int cx = w / 2;
        int top = h / 2 - 90;
        // 曲绘：缩小 + 半透明（α=0.72），贴在文字列<b>右侧</b>，从分数行一直盖到「此前最佳」行那一带。
        // 不挡字的依据：中间那列文字最长的一行是 P/G/B/M（~40 字符 ≈ 240px，半宽 120px），
        // 曲绘框从 cx+190 起步；854×480 时框在 (727, 200, 107×80)，1280×720 时在 (1100, 300, 160×120)，
        // 底边都在结算按钮（h/2+78）之上。窄屏放不下就干脆不画，绝不压字。
        int boxW = Math.max(80, Math.min(160, Math.round(w * 0.125f)));
        int boxH = Math.round(boxW * 0.75f);
        int boxX = w - 20 - boxW;
        int boxY = h / 2 - boxH / 2;
        if (boxX >= cx + 190) {
            CoverTextures.draw(ctx, CoverTextures.get(session.current()), boxX, boxY, boxW, boxH, 0.72f);
        }
        // 分数（7 位，ending.rs:324-353 用等宽数字）
        RenderCompat.Transform ms = RenderCompat.transform(ctx);
        String score = String.format("%07d", engine.score());
        ms.push();
        ms.scale(3f, 3f);
        ctx.drawText(mc.textRenderer, score, cx / 3 - mc.textRenderer.getWidth(score) / 2, top / 3, 0xFFFFFFFF, true);
        ms.pop();
        // 下面一行一行往下排（行高 ROW），每行只占一个 y。
        // 之前「曲名 · 难度」和「MAX COMBO」被写成了同一个 y（top + 68），两行字直接叠在一起。
        int row = top + 50;
        // 段位 + 准确率（ending.rs:368-374）
        drawRow(ctx, mc, cx, row, engine.rank() + "    ACC " + String.format("%.2f%%", engine.accuracy() * 100.0), 0xFFFFEC9F);
        row += ROW_H;
        // 曲名 · 难度
        drawRow(ctx, mc, cx, row, session.songTitle() + "  ·  " + session.songLevel(), 0xFFB4E1FF);
        row += ROW_H;
        // 最大连击（ending.rs:437-470）
        drawRow(ctx, mc, cx, row, "MAX COMBO " + engine.maxCombo() + " / " + engine.total(), 0xFFB4E1FF);
        row += ROW_H;
        // 各判定数量（ending.rs:406-425：每档 counts[id]）
        drawRow(ctx, mc, cx, row, "PERFECT " + engine.perfect() + "   GOOD " + engine.good()
                + "   BAD " + engine.bad() + "   MISS " + engine.miss(), 0xFFFFFFFF);
        row += ROW_H;
        // 此前最佳
        Records.Rec best = session.bestBefore();
        drawRow(ctx, mc, cx, row,
                best == null ? "首次通关" : "此前最佳 " + best.scoreText() + "  " + best.accText(), 0xFFAAAAAA);
        row += ROW_H;
        // 结算奖励（S5）：插在「此前最佳」与按钮之间（按钮 y = h/2+78）。
        // 1280×720：这一行 y = top+140 = 410，字底 419，按钮 438 起 ⇒ 空 19px；
        // 854×480：y = 290，字底 299，按钮 318 起 ⇒ 同样 19px。
        // 曲绘在右上角（x ≥ boxX、y = h/2±boxH/2），这行居中且最宽的真实文本
        // （"奖励：金锭×3 + 钻石×1" ≈ 120px）离它几百像素远；万一更长就按像素宽度截断。
        // 自动游玩局不发奖励（Session.finish() 不调 Rewards.grantOnFinish），这一行写清楚原因，
        // 不能显示上一局残留的 Rewards.lastRewardText()。
        if (session.autoplay()) {
            drawRow(ctx, mc, cx, row, truncateToWidth(mc, "奖励：无（自动游玩）", REWARD_MAX_W), 0xFFAAAAAA);
        } else {
            Rewards.LastReward reward = Rewards.lastReward();
            drawRow(ctx, mc, cx, row,
                    truncateToWidth(mc, Rewards.lastRewardText(), REWARD_MAX_W),
                    reward == null || reward.isEmpty() ? 0xFFAAAAAA : 0xFFFFD166);
        }
        for (Btn b : resultButtons(w, h)) {
            drawButton(ctx, mc, b, b.hit(mouseX, mouseY));
        }
    }

    /** 结算界面的一行居中文字。 */
    private void drawRow(DrawContext ctx, MinecraftClient mc, int cx, int y, String text, int color) {
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(text), cx, y, color);
    }

    /** 按像素宽度截断（超宽补省略号），保证结算界面的奖励行永远压不到右侧曲绘。 */
    private static String truncateToWidth(MinecraftClient mc, String text, int maxWidth) {
        if (text == null) return "";
        if (mc.textRenderer.getWidth(text) <= maxWidth) return text;
        int ellipsis = mc.textRenderer.getWidth("…");
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int w = mc.textRenderer.getWidth(ch);
            if (used + w + ellipsis > maxWidth) break;
            sb.append(ch);
            used += w;
        }
        return sb + "…";
    }

    private void drawButton(DrawContext ctx, MinecraftClient mc, Btn b, boolean hover) {
        ctx.fill(b.x0(), b.y0(), b.x1(), b.y1(), hover ? 0xFF3A5A8A : 0xFF2A2A2A);
        ctx.fill(b.x0(), b.y0(), b.x1(), b.y0() + 1, 0xFF9FB6D0);
        ctx.fill(b.x0(), b.y1() - 1, b.x1(), b.y1(), 0xFF9FB6D0);
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(b.label()),
                (b.x0() + b.x1()) / 2, (b.y0() + b.y1()) / 2 - 4, 0xFFFFFFFF);
    }

    private void doAction(Action action) {
        switch (action) {
            case RESUME -> session.resume();
            case RESTART -> session.restart();
            case EXIT -> {
                session.stop();
                // 全 mod 唯一的曲库界面；unlockAll 跟着进来时的形态走（播放器 → 还是全部可玩）
                if (client != null) client.setScreen(new SongSelectScreen(unlockAll));
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    // 1.21.9 起 GUI 输入换成了记录类（Click / KeyInput），回调签名全变。
    // 这里加一层「桥」：新签名 → 老的三参数方法，判定逻辑一行都不用动。
    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        return mouseClicked(click.x(), click.y(), click.button());
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        return mouseDragged(click.x(), click.y(), click.button(), deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        return mouseReleased(click.x(), click.y(), click.button());
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return keyPressed(input.key(), input.scancode(), input.modifiers());
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
        return keyReleased(input.key(), input.scancode(), input.modifiers());
    }
    //?}

    /**
     * 老版本直接调 {@code super.mouseClicked}；1.21.9 里没有这个重载 ——
     * PlayScreen 不带子控件，{@code Screen.mouseClicked} 本来也只会返回 false，所以等价于 false。
     */
    private boolean superMouseClicked(double mouseX, double mouseY, int button) {
        //? if >=1.21.9 {
        return false;
        //?} else {
        /*return super.mouseClicked(mouseX, mouseY, button);
        *///?}
    }

    private boolean superMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        //? if >=1.21.9 {
        return false;
        //?} else {
        /*return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        *///?}
    }

    private boolean superMouseReleased(double mouseX, double mouseY, int button) {
        //? if >=1.21.9 {
        return false;
        //?} else {
        /*return super.mouseReleased(mouseX, mouseY, button);
        *///?}
    }

    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return superMouseClicked(mouseX, mouseY, button);
        // 结算 / 暂停时不判定，只吃按钮
        if (session.finished()) {
            for (Btn b : resultButtons(width, height)) {
                if (b.hit(mouseX, mouseY)) {
                    doAction(b.action());
                    return true;
                }
            }
            return true;
        }
        if (session.paused()) {
            for (Btn b : pauseButtons(width, height)) {
                if (b.hit(mouseX, mouseY)) {
                    doAction(b.action());
                    return true;
                }
            }
            return true;
        }
        lastDragX = mouseX / width;
        lastDragY = mouseY / height;
        lastDragNanos = System.nanoTime();
        session.hitAt(mouseX / (double) width, mouseY / (double) height);
        return true;
    }

    /**
     * 按住左键移动 = 持续判定（用户要求：按住鼠标疯狂划就能 AP）。
     *
     * <p>每个移动事件走一次 {@link Session#dragAt}：先点击通路（Tap / Hold，横向校验照旧，
     * 接受窗口收到 Perfect 档——源码里移动派生的判定固定是 Perfect，judge.rs:790-793），
     * 没吃到再走划动通路（Flick）。速度只用来决定「算不算划」，不再决定「判不判」——
     * 所以慢慢划也能吃到音符，快速来回划就是每个事件吃一个。
     * 速度公式照 judge.rs:112-134 的 FlickTracker（位移 / 时间）。
     */
    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0) {
            if (session.paused() || session.finished()) return true;
            double nx = mouseX / width;
            double ny = mouseY / height;
            long now = System.nanoTime();
            if (!Double.isNaN(lastDragX)) {
                double dist = Math.hypot((nx - lastDragX) * 2.0, (ny - lastDragY) * 2.0);  // 归一化到 [-1,1]
                double dt = (now - lastDragNanos) / 1e9;
                double speed = dt > 1e-9 ? dist / dt : Double.POSITIVE_INFINITY;
                session.dragAt(nx, ny, speed);
            }
            lastDragX = nx;
            lastDragY = ny;
            lastDragNanos = now;
            return true;
        }
        return superMouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            lastDragX = Double.NaN;
            // 抬手 ≠ 立刻结束 hold：源码里只是这根手指从 touches 里消失，
            // 尾巴断不断由每帧的 judge.rs:710-727 决定（50ms 宽限，按回来就撤销）。
            // 暂停 / 结算里抬手指不算松开（否则点一下「继续」就断连）。
            session.pointerUp();
            return true;
        }
        return superMouseReleased(mouseX, mouseY, button);
    }

    /**
     * 键盘 = 游玩输入（键盘游玩打开时）。照 prpr：
     * <ul>
     *   <li>judge.rs:1009-1013 {@code key_down_event}：{@code repeat == false} 才算一次按下，
     *       {@code key_delta += 1; keys_down += 1;}；</li>
     *   <li>judge.rs:1016-1018 {@code key_up_event}：{@code key_delta -= 1;}（{@code keys_down} 不变）；</li>
     *   <li>judge.rs:465-469：{@code use_keyboard} 关掉时，这两个量强制当 0。</li>
     * </ul>
     * 所以「一次按 N 个键」= 本帧 {@code keys_down == N} = 连续 N 次吃掉「最早未判的 Tap/Hold」
     * （judge.rs:649-698），不校验横向位置。
     *
     * <p>空格是额外的「甩」键（用户要求）：它不计入 {@code keys_down}（不消耗 Tap/Hold），
     * 只让 Flick / Drag 进 PreJudge（judge.rs:742-758）。所以空格能甩 Flick 和 Drag。
     *
     * <p>Esc 不参与判定：它是暂停键（源码暂停是 Space，game.rs:1055-1066；这里 Space 让给了甩键）。
     */
    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 「同一个键 60ms 内又来一次」= 自动重复（对应 judge.rs:1009-1013 的 repeat == true）
        long now = System.nanoTime();
        Long last = lastPressNanos.put(keyCode, now);
        boolean repeat = last != null && now - last < REPEAT_WINDOW_NANOS;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            lastPressNanos.remove(keyCode);
            if (session.finished()) {
                doAction(Action.EXIT);
            } else if (session.paused()) {
                session.resume();
            } else {
                session.pause();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            // 专用甩键：只作为「有输入」让 Flick / Drag 进 PreJudge，不消耗 Tap/Hold
            if (!session.paused() && !session.finished()) session.pressFlickKey();
            return true;
        }
        if (!repeat && !session.paused() && !session.finished()) {
            session.pressKey();      // 内部按 use_keyboard 开关决定是否真的算一次（judge.rs:465-469）
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        lastPressNanos.remove(keyCode);
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            session.releaseFlickKey();
            return true;
        }
        session.releaseKey();        // judge.rs:1016-1018：只减 key_delta（hold 靠它断）
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        session.stop();
        if (client != null) client.setScreen(null);
    }

    /** tap / hold = 蓝，drag = 黄，flick = 红（与 Phira 资源包 note_style 观感对应）。 */
    private static int colorOf(Chart.Note.Type type) {
        return switch (type) {
            case TAP, HOLD -> 0xFF3AA6FF;
            case DRAG -> 0xFFFFD166;
            case FLICK -> 0xFFFF4D6D;
        };
    }

    /**
     * {@code prpr/src/core/note.rs:21-30} 的 {@code NoteKind::order()}：<b>小的先画</b>（压在下层）。
     * {@code Hold = 0, Drag = 1, Click = 2, Flick = 3}。
     * {@code note.rs:242} 取它、{@code note.rs:109-111} 把 {@code (order, texture)} 一起塞进缓冲，
     * 于是渲染顺序就是「hold → drag → click → flick」。
     */
    private static int noteDrawOrder(Chart.Note.Type type) {
        return switch (type) {
            case HOLD -> 0;
            case DRAG -> 1;
            case TAP -> 2;
            case FLICK -> 3;
        };
    }

    private static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 谱面颜色（{@code colorEvents}，0..1 三通道）→ ARGB，alpha 恒 255。
     * 对应 {@code rpe.rs:122-128} 的 {@code Color::from_rgba(r,g,b,255)}
     * 与 {@code core/line.rs:238-250} 的 {@code color.a *= alpha}。
     */
    private static int rgbOf(double r, double g, double b) {
        int ri = (int) Math.round(255 * Math.max(0, Math.min(1, r)));
        int gi = (int) Math.round(255 * Math.max(0, Math.min(1, g)));
        int bi = (int) Math.round(255 * Math.max(0, Math.min(1, b)));
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    /**
     * 画一条<b>贴图判定线</b>（{@code prpr/src/core/line.rs:254-290} 的 {@code JudgeLineKind::Texture}）。
     *
     * <pre>
     * // line.rs:254-262
     * let mut color = color.unwrap_or(WHITE);
     * color.a = alpha.max(0.0);
     * if color.a == 0.0 { return; }
     * let hf = vec2(texture.width(), texture.height());
     * draw_texture_ex(tex, -hf.x/2., -hf.y/2., color,
     *     DrawTextureParams { dest_size: Some(hf), flip_y: true, .. });
     * </pre>
     *
     * <p>要点：
     * <ul>
     *   <li>尺寸 = <b>图片像素数 × scale</b>，其中 scale 已经是「世界单位/像素」
     *       （{@code rpe.rs:695} 非 {@code line.png} 的 factor = {@code 2/RPE_WIDTH}）；</li>
     *   <li>世界单位 → 屏幕像素：1 世界单位 = 半个屏宽 ⇒ {@code px = world * w / 2}
     *       （屏幕 y 侧同理，因为相机把 y 乘了 aspect，{@code resource.rs:507}）；</li>
     *   <li>{@code flip_y: true} 的效果 = 图片顶行朝上。我们的坐标系 y 向下、角度已取反
     *       （和音符那套一致），所以「从左上角画起、不额外翻转」正好等价。</li>
     * </ul>
     * 取不到贴图（包里没这张图 / 解不开 / 名字对不上）就<b>跳过这一条</b>，不画占位块：
     * 特效谱里贴图线动辄上百条，画占位块会把画面糊死。
     */
    private void drawTextureLine(DrawContext ctx, Chart.Line line, int slot, LineXform xf,
                                 double t, int w, int h, Session session) {
        if (line.attachUi != 0) return;                       // UI 元素，画不了
        String name = line.textureName;
        if (name == null || name.isEmpty()) return;
        double alpha = line.alphaAt(t);
        if (alpha <= 0.01) return;                            // line.rs:237
        LineTextures.Tex tex = LineTextures.get(session.current(), name);
        if (tex == null) return;
        // 解码出来是 1×1 的图（坏图 / 全空）就别画了，否则就是一整块纯色
        if (tex.width() <= 1 || tex.height() <= 1) return;
        double sx = line.scaleXAt(t);
        double sy = line.scaleYAt(t);
        if (Math.abs(sx) <= 1e-6 || Math.abs(sy) <= 1e-6) return;
        // line.rs:260-271：dest_size = (texture.width(), texture.height())（世界单位）
        // 世界单位 → 屏幕像素：1 世界单位 = **半个屏宽**，x 和 y 都乘 w/2
        // （相机把 y 乘了 aspect，而 aspect = w/h，所以 aspect * h/2 == w/2）。
        // ⚠ 之前 y 侧错用了 h*0.5，16:9 下高度被压到 0.5625 倍。
        int quadW = (int) Math.round(Math.abs(tex.width() * sx * w * 0.5));
        int quadH = (int) Math.round(Math.abs(tex.height() * sy * w * 0.5));
        if (quadW <= 0 || quadH <= 0) return;
        xf.ensure(slot);
        // line.rs:255 color.unwrap_or(WHITE)：有 colorEvents 就用谱面颜色，否则白
        double r = line.hasColor() ? line.colorAt(t, 0) : 1.0;
        double g = line.hasColor() ? line.colorAt(t, 1) : 1.0;
        double b = line.hasColor() ? line.colorAt(t, 2) : 1.0;
        RenderCompat.Transform ms = RenderCompat.transform(ctx);
        ms.push();
        ms.translate((float) xf.cx[slot], (float) xf.cy[slot]);
        ms.rotate((float) xf.rotRad[slot]);
        // UV 走「显式 0→1」的那条（RenderCompat.drawTextureFull），不再依赖
        // 「u/v + 区域像素 + 贴图像素」的参数化路径 —— 那条虽然经反编译确认算出来也是 0→1，
        // 但换成由构造保证才彻底排除「采到单一纹素」。
        RenderCompat.drawTextureFull(ctx, tex.id(), -quadW / 2, -quadH / 2, quadW, quadH,
                alpha, r, g, b);
        ms.pop();
        // 把这一条记进「本帧画了什么」的缓冲（攒满 TEX_LOG_LINES 行整帧打一次日志）。
        // 每行都是**不同线**的图名 / 贴图 id / 贴图像素 / 目标矩形 / 屏幕位置 / 原始 alpha /
        // 颜色 / 角度 —— 一帧内 8 行应该是 8 张不同的图、不同的坐标。
        if (texFrameLog) {
            texFrameCount++;
            texFrame.append(String.format(java.util.Locale.ROOT,
                    "%n  #%d 线%d %s id=%s 贴图%dx%d → %dx%d @(%d,%d) α=%.3f(原始%.3f) rgb=(%.2f,%.2f,%.2f) rot=%.1f°",
                    texFrameCount, slot, name, tex.id(), tex.width(), tex.height(), quadW, quadH,
                    Math.round(xf.cx[slot]), Math.round(xf.cy[slot]),
                    alpha, line.alphaAt(t), r, g, b, line.rotateAt(t)));
            if (texFrameCount >= TEX_LOG_LINES) {
                texFrameLog = false;                 // 只打一帧，之后不再收集
                LOG.info("[PhiMC] 一帧里画出的贴图线（t={}s，共 {} 条）:{}",
                        String.format(java.util.Locale.ROOT, "%.2f", t), texFrameCount, texFrame);
            }
        }
    }

    /**
     * 画一条<b>文字判定线</b>（{@code prpr/src/core/line.rs:291-298} 的 {@code JudgeLineKind::Text}）。
     *
     * <pre>
     * JudgeLineKind::Text(anim) =&gt; {
     *     let mut color = color.unwrap_or(WHITE);
     *     color.a = alpha.max(0.0);
     *     let now = anim.now();
     *     res.apply_model_of(&amp;Matrix::identity().append_nonuniform_scaling(&amp;Vector::new(1., -1.)), |_| {
     *         ui.text(&amp;now).pos(0., 0.).anchor(0.5, 0.5).size(1.).color(color).multiline().draw();
     *     });
     * }
     * </pre>
     *
     * <p>逐项对应：
     * <ul>
     *   <li><b>画在哪</b>：判定线<b>原点</b>（{@code pos(0., 0.)}），锚点<b>居中</b>
     *       （{@code anchor(0.5, 0.5)}）。文本事件里<b>没有</b>位置字段 —— 谱面靠
     *       {@code moveXEvents/moveYEvents} 移动整条线来排版
     *       （实测 {@code 14143_「僕は…」} 每行只有一个假名，靠 10 条线的 x 排成一句歌词）；</li>
     *   <li><b>字号</b>：{@code size(1.)} 固定。{@code ui/text.rs:95-97} 的
     *       {@code get_scale = 0.04 * size * 视口宽}，也就是 em 高度 = {@code 0.04 × 屏宽} 像素
     *       （与分辨率无关地占屏宽 4%）；这里换算成 MC 字体缩放
     *       {@code em / fontHeight}；</li>
     *   <li><b>颜色</b>：{@code color.unwrap_or(WHITE)} —— 有 {@code colorEvents} 用谱面颜色，
     *       否则<b>纯白</b>（注意：和普通判定线不一样，普通线用的是 {@code judge_line_color}）；</li>
     *   <li><b>alpha</b>：{@code color.a = 判定线 alpha}（{@code line.rs:237} 的
     *       {@code object.alpha * res.alpha}）；alpha 为负时整条线（连文字）不画
     *       （{@code line.rs:365-368}）；</li>
     *   <li><b>跟着旋转/缩放</b>：文字在 {@code now_transform}（平移+旋转）和
     *       {@code now_scale}（scaleX/scaleY）<b>里面</b>画（{@code line.rs:240,246}），
     *       所以它跟着判定线一起转、一起缩放；</li>
     *   <li><b>那个 y 翻转矩阵</b>（{@code append_nonuniform_scaling(1,-1)}）是给 Phira 的
     *       y 轴向上坐标系补正的。我们的屏幕系 y 向下、角度已取反（和音符/贴图线同一套），
     *       正着画就是正的，不需要再翻 —— 再翻一次字就倒了。</li>
     * </ul>
     *
     * <p><b>多行</b>：{@code .multiline()} → 按 {@code \n} 切行，整体按行数居中。
     */
    private static void drawTextLine(DrawContext ctx, Chart.Line line, int slot, LineXform xf,
                                     double t, int w) {
        if (line.attachUi != 0) return;                 // UI 元素，画不了
        double alpha = line.alphaAt(t);
        if (alpha <= 0.0) return;                       // line.rs:365-368 / color.a = 0
        String s = line.textAt(t);
        if (s == null || s.isEmpty()) return;
        double sx = line.scaleXAt(t);
        double sy = line.scaleYAt(t);
        if (Math.abs(sx) < 1e-6 || Math.abs(sy) < 1e-6) return;
        xf.ensure(slot);
        MinecraftClient mc = MinecraftClient.getInstance();
        int fontH = Math.max(1, mc.textRenderer.fontHeight);
        // Phira 的「字号 1.0」= 0.04 × 视口宽（像素）的 em；换算成 MC 字体的绘制缩放
        double scale = (TEXT_EM_FRAC * w) / fontH;
        // line.rs:292 color.unwrap_or(WHITE)：没有 colorEvents 时是**白**，不是判定线指示色
        int base = line.hasColor()
                ? rgbOf(line.colorAt(t, 0), line.colorAt(t, 1), line.colorAt(t, 2))
                : 0xFFFFFFFF;
        int color = withAlpha(base, alpha);
        String[] rows = s.split("\n", -1);
        double totalH = rows.length * (double) fontH;
        RenderCompat.Transform ms = RenderCompat.transform(ctx);
        ms.push();
        ms.translate((float) xf.cx[slot], (float) xf.cy[slot]);
        ms.rotate((float) xf.rotRad[slot]);
        // line.rs:246 的 now_scale 也作用在文字上（文字线的 scale 默认是 1，除非谱面给了 scaleXEvents）
        ms.scale((float) (scale * sx), (float) (scale * sy));
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            if (row.isEmpty()) continue;
            int tw = mc.textRenderer.getWidth(row);
            ctx.drawText(mc.textRenderer, textOf(row),
                    Math.round(-tw / 2f),
                    Math.round((float) (-totalH / 2.0 + i * (double) fontH)),
                    color, false);
        }
        ms.pop();
    }

    /**
     * 一个 HUD 元素此刻的「attachUI 变换」。{@code attached=false} 表示这首谱没给这个元素指定判定线，
     * 按默认位置、白色画（= {@code chart.rs:93-95} 的 {@code f(ui, WHITE)} 分支）。
     *
     * @param cx,cy   判定线的屏幕位置（像素）
     * @param rotRad  判定线角度（已取反，MC 屏幕 y 向下）
     * @param sx,sy   判定线的 scaleX / scaleY
     * @param color   判定线颜色（{@code colorEvents}，否则白）
     * @param alpha   判定线 alpha
     * @param px,py   该元素的<b>默认位置</b>（像素）——旋转和缩放都以它为支点
     */
    private record UiElem(boolean attached, float cx, float cy, float rotRad,
                          double sx, double sy, int color, double alpha,
                          float px, float py) {
        static UiElem none() {
            return new UiElem(false, 0, 0, 0, 1, 1, 0xFFFFFFFF, 1, 0, 0);
        }
    }

    /**
     * {@code prpr/src/core/chart.rs:72-96} 的 {@code with_element}：
     * 如果谱面给这个 UI 元素指定了 {@code attachUI} 线，就取出那条线此刻的
     * 位置 / 旋转 / scale / alpha / 颜色，让 UI 元素<b>跟着线走</b>。
     *
     * <p>源码：
     * <pre>
     * if let Some(id) = self.attach_ui[element as usize - 1] {
     *     let mut tr = line.fetch_pos(res, lines);      // 世界坐标（含父线合成）
     *     tr.y = -tr.y;                                 // UI 空间 y 向下
     *     let color = lines[id].color.now_opt().unwrap_or(WHITE);
     *     let scale = obj.now_scale(scale_point);       // 以 scale_point 为原点缩放
     *     let ro = new_rotation_wrt_point(R(-rot), rotation_point);
     *     ui.with(translate(tr) * ro * scale, |ui| ui.alpha(obj.now_alpha(), |ui| f(ui, color)))
     * } else { f(ui, WHITE) }
     * </pre>
     *
     * <p><b>平台差异</b>：源码把 {@code rotation_point}（旋转支点）和 {@code scale_point}（缩放支点）
     * 分开传，个别元素两者差几个像素（例如 Score 的 scale_point 是文字中心）。我们对两者统一用
     * 元素的默认位置——差别是「缩放支点差几像素」，肉眼看不出来。
     */
    private static UiElem uiElem(Chart chart, int element, double t, double aspect,
                                 int w, int h, float px, float py) {
        int[] map = chart.attachUiLines;
        int idx = (map != null && element >= 1 && element <= map.length) ? map[element - 1] : -1;
        if (idx < 0 || idx >= chart.lines.size()) return UiElem.none();
        Chart.Line line = chart.lines.get(idx);
        float cx = (float) (line.xAt(t, aspect) * w);
        float cy = (float) ((1.0 - line.yAt(t, aspect)) * h);
        float rot = (float) -Math.toRadians(line.rotateAt(t));
        int color = line.hasColor()
                ? rgbOf(line.colorAt(t, 0), line.colorAt(t, 1), line.colorAt(t, 2))
                : 0xFFFFFFFF;
        return new UiElem(true, cx, cy, rot, line.scaleXAt(t), line.scaleYAt(t),
                color, Math.max(0.0, line.alphaAt(t)), px, py);
    }

    /**
     * 把 {@code chart.rs:92} 的 {@code translate(tr) * ro * scale} 压进当前变换栈：
     * {@code T(tr) · T(p)·R(-θ)·T(-p) · T(-p)·S·T(p)}。
     * {@code attached=false} 时只 push 一层空变换，调用方的绘制代码不用改。
     */
    private static void pushUi(RenderCompat.Transform ms, UiElem e) {
        ms.push();
        if (!e.attached()) return;
        ms.translate(e.cx(), e.cy());
        ms.translate(e.px(), e.py());
        ms.rotate(e.rotRad());
        ms.translate(-e.px(), -e.py());
        ms.translate(-e.px(), -e.py());
        ms.scale((float) e.sx(), (float) e.sy());
        ms.translate(e.px(), e.py());
    }

    /** UI 元素的最终颜色：没挂 attachUI 就用调用方给的默认色；挂了就乘判定线的颜色和 alpha。 */
    private static int uiColor(UiElem e, int fallback) {
        if (!e.attached()) return fallback;
        double a = e.alpha() * ((fallback >>> 24) & 0xFF) / 255.0;
        int base = (fallback & 0xFFFFFF) == 0xFFFFFF ? e.color() : fallback;
        return withAlpha(base, a);
    }

    /**
     * 一帧内「每条判定线的屏幕变换」缓存（懒算）。
     *
     * <p>对应 {@code prpr/src/core/line.rs:240} 的 {@code res.with_model(self.now_transform(res, lines))}：
     * Phira 每条线只算一次变换、把线内音符都放进那个模型矩阵里画；我们以前是**每个音符**重算
     * 一遍 {@code xAt/yAt/rotateAt}（三次二分查找），可见音符几百个时纯属白烧。
     *
     * <p>{@code cy} 用 {@code (1 - yFrac) * h}（MC 屏幕 y 向下），{@code rotRad} 已取反。
     */
    private static final class LineXform {
        final Chart chart;
        final double t;
        final double aspect;
        final int w;
        final int h;
        final double[] cx;
        final double[] cy;
        final double[] rotRad;
        final boolean[] done;

        LineXform(Chart chart, double t, double aspect, int w, int h) {
            this.chart = chart;
            this.t = t;
            this.aspect = aspect;
            this.w = w;
            this.h = h;
            int n = chart.lines.size();
            this.cx = new double[n];
            this.cy = new double[n];
            this.rotRad = new double[n];
            this.done = new boolean[n];
        }

        void ensure(int slot) {
            if (done[slot]) return;
            Chart.Line line = chart.lines.get(slot);
            cx[slot] = line.xAt(t, aspect) * w;
            cy[slot] = (1.0 - line.yAt(t, aspect)) * h;
            // Phira 世界 y 向上、MC 屏幕 y 向下 ⇒ 角度取反（和以前逐音符算时的取反一致）
            rotRad[slot] = -Math.toRadians(line.rotateAt(t));
            done[slot] = true;
        }
    }
}
