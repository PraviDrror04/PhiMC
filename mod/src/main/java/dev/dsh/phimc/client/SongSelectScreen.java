package dev.dsh.phimc.client;

import dev.dsh.phimc.item.ChipItem;
import dev.dsh.phimc.survival.Profile;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 选曲界面（<b>全 mod 唯一的曲库界面</b>）：左侧曲绘 + 右侧详情（曲名 / 难度 / 物量 / BPM /
 * 曲师 / 谱师 / 画师 / 锁定状态 / 最佳成绩），下面是曲目列表，底部翻页 / 开始 / 商店 / 设置。
 *
 * <p>平板（{@code TabletItem} 右键）和选曲键（P）都落到这里；平板入口会带一个
 * {@code backTo}（{@link TabletScreen}，只剩商店页），所以底部的「关闭」会变成「商店」，
 * 从平板进来的用户看完曲库可以回商店，再按一次关闭才回游戏。
 *
 * <h2>布局（常量全在下面，1280×720 与 854×480 都逐像素核对过；见 {@code docs/REPORT-local-filter-sort.md}）</h2>
 * <pre>
 * PAD = 12                        屏幕左右留白
 * HEADER_H = 34                   顶部：页签 y=3（48×18）、标题 y=8 居中、副标题 y=20
 * dTop = 34                       详情区顶
 * S = max(48, round(H×3/16))      曲绘方框边长（480→90，720→135）
 * infoNeed = 18 + 7×12 + 9 + 4 = 115   详情信息列 8 行所需高度
 * detailBottom = dTop + max(S, infoNeed)（480→149，720→169）
 * filterY = detailBottom + 8      本地页「搜索 / 排序 / 锁定 / 难度」那一行（480→157，720→177）
 * listTop = filterY + 20 + 6      列表顶（480→183，720→203）
 * listBottom = H - 30             （480→450，720→690）
 * NOTICE_H = 12                   列表与底部按钮之间留给提示行（拷贝结果 / 未解锁）
 * 行高 ROW_H = 20，行距 ROW_STEP = 22
 * 每页条数 = clamp((listBottom - NOTICE_H - listTop) / ROW_STEP, 1, 20)（480→11，720→20）
 *
 * 曲绘方框：     (PAD, dTop, S, S)
 * 信息列：       x = PAD + S + 14，右边界 W - PAD；第 1 行（曲名，1.5 倍）y = dTop
 * 信息 8 行：    y = dTop + 18 + i×12（i = 难度/物量/BPM/曲师/谱师/画师/状态/最佳）
 * 筛选行：       搜索框 (PAD, filterY, localFieldW, 20) + 「排序：xx」84 + 「锁定：xx」76 + 「难度：xx」76
 *                四个控件右对齐到 W-PAD，间距 BTN_GAP=8
 * 列表行 i：     (PAD, listTop + i×22, W - 2·PAD, 20)
 * 「拷芯片」：   x = W - PAD - 4 - 56 = W - 72，宽 56 高 16，y = 行顶 + 2
 * 行内「未解锁」：与「拷芯片」同一个右侧槽位（右对齐），未解锁时**不生成按钮**
 * 最佳成绩：     右对齐到 拷芯片左边界 - 8
 * 底部按钮：     6 个槽位（5×70 + 「自动游玩」100），间距 8，整体居中，y = H - 26
 *                顺序：← 上一页 / 开始 / [✓] 自动游玩 / 商店(或关闭) / 设置 / 下一页 →
 * 提示行：       (PAD, listBottom - 10)
 * </pre>
 *
 * <h2>页签：本地 / 在线（{@link Tab}）</h2>
 * 顶部左上角两个页签按钮（y=3，48×18）：
 * <ul>
 *   <li><b>本地</b>：曲库界面 + 一行<b>搜索 / 排序 / 锁定筛选 / 难度筛选</b>（见下一节）；</li>
 *   <li><b>在线</b>：Phira 社区谱面库（{@link PhiraApi}）——搜索框（曲名 / 谱师）+ 排序
 *       （评分 / 最新）+ <b>「仅上架」开关</b> + 分页（复用底部「上一页 / 下一页」）+ 每行一个
 *       「下载」按钮，行内 20px 曲绘缩略图异步下载（{@link PhiraCovers}，LRU + 每帧最多注册 2 张）。
 *       「仅上架」默认<b>开</b>（只看 {@code stable && reviewed}）；关掉后未上架 / 未过审的也返回，
 *       曲名与元信息灰一档 + 难度后面挂 {@code [未上架]}（见 {@link #toggleOnlyStable()}）。</li>
 * </ul>
 * <b>没有把「商店」做成第三个页签</b>：商店是独立界面（{@link TabletScreen}，只能从平板进），
 * 底部那个「商店 / 关闭」按钮已经承担了导航；再做页签会和「来源界面」语义打架
 * （从平板进来时它本来就是商店）。
 *
 * <p>在线页的网络全在后台线程（{@link PhiraApi} / {@link PhiraDownloader}），
 * 本类只读它们的 {@code volatile} 快照；下载完成后由 {@link #pollDownload()} 在
 * <b>渲染线程</b>里做 {@code rescan()} + 切回本地页签 + 选中新谱面。
 *
 * <h2>本地页：搜索 / 排序 / 筛选（三者组合生效）</h2>
 * 一行四个控件（{@code filterY()}，只在本地页签可见）：
 * <pre>
 * [ 搜索框：曲名 / 曲师 / 谱师 子串 ]  [排序：默认]  [锁定：全部]  [难度：全部]
 * </pre>
 * <ul>
 *   <li><b>搜索</b>：{@code TextFieldWidget}，<b>边打边过滤</b>（每帧比一次文本，变了就重建视图）；
 *       匹配 {@code title()} / {@code composer()} / {@code charter()} 的子串，大小写不敏感；</li>
 *   <li><b>排序</b>（循环按钮）：默认（= {@code songs/} 扫描顺序）/ 曲名 / 难度（EZ&lt;HD&lt;IN&lt;AT）/ 物量 / 最佳成绩（降序）；</li>
 *   <li><b>筛选</b>（两个循环按钮）：锁定状态（全部 / 仅已解锁 / 仅未解锁）+
 *       难度（全部 → EZ → HD → IN → AT → 全部）。</li>
 * </ul>
 * 四个条件同时生效：`{@link #ensureView(List)}` 先按搜索 + 两个筛选挑，再按排序排，
 * 结果放进 `{@link #view}`；`{@link #selected}` 是 <b>view 的下标</b>（不是 {@code songs()} 的下标），
 * 点「开始」时用 {@link Session#indexOf(SongEntry)} 换回真下标。
 * 选中项跨过滤保持：`{@link #selectedPin}` 记住那一首，重建视图后按<b>路径</b>重新定位
 * （rescan 会换对象，不能按引用比）；它被过滤掉了就落到第 1 行。
 *
 * <p><b>「排序：物量」的成本</b>：物量只能解析谱面才知道（{@code info.txt} 没有），所以点这个排序时
 * 会把全部谱面排进 {@link SongMeta#requestAll(List)} 的<b>单线程后台队列</b>（一次只解析一首、
 * 每包只读一次、进缓存）。列表初始化（{@code scan} / {@code init}）<b>不会</b>触发它。
 *
 * <h2>锁定状态（生存玩法）</h2>
 * 统一闸门是 {@link #isLocked(SongEntry)}：
 * <ul>
 *   <li>从平板进来（{@code unlockAll = false}）：存档 {@code profile.isUnlocked(chartId)} 为真才能玩；</li>
 *   <li>从谱面播放器 / P 键进来（{@code unlockAll = true}）：<b>全部视为已解锁</b>，不显示「未解锁」，
 *       但<b>不写</b> {@code Profile.unlocked}。</li>
 * </ul>
 * 锁定时：
 * <ul>
 *   <li>列表行整行灰字 + 右侧写「未解锁」，<b>不生成「拷芯片」按钮</b>（不是变灰禁用——
 *       灰色按钮在 20px 高的行里和文字挤在一起，反而看不清状态；这里选择「不放」）；</li>
 *   <li>点击只能「选中」看详情，<b>不能开始</b>：底部「开始」按钮变灰、回车也只会给一行提示。</li>
 * </ul>
 * 「拷芯片」永远只对**真解锁**（存在 {@code Profile.unlocked} 里）的谱面出现，
 * 播放器模式不给拷芯片（{@code ChipItem.carveHeld} 也会再查一次存档）。
 *
 * <h2>自动游玩（{@code [✓] 自动游玩}）</h2>
 * 「开始」右边的勾选按钮：切换 {@code profile.autoplay}（跨局保持），开局后
 * {@code Session.tick()} 走 {@code Engine.autoplay(t)}，玩家不用输入。自动局
 * <b>不给成绩、不发奖励</b>（见 {@code Session.finish()}）。
 *
 * <p><b>曲绘是按需加载的</b>：只有 {@code selected} 那一首会去解码（见 {@link CoverTextures} 的
 * 缓存 + LRU），{@code init()} 里一张都不读。因为 <b>{@code init()} 会在窗口缩放 / 翻页时重复执行</b>，
 * 一旦在那里批量解码，19 首 × 8.8 MB 直接能把显存打爆。
 *
 * <p>物量 / BPM 只能解析谱面 JSON 才知道（info.txt 里没有），走 {@link SongMeta} 的后台线程，
 * 渲染线程永远不阻塞。
 */
public class SongSelectScreen extends Screen {

    // ---------------- 布局常量（见类注释） ----------------
    private static final int PAD = 12;
    private static final int HEADER_H = 34;
    private static final int ROW_H = 20;
    private static final int ROW_STEP = 22;
    private static final int PER_PAGE_MAX = 20;
    private static final int PER_PAGE_MIN = 1;
    /** 详情区每一行的行距（像素）。 */
    private static final int INFO_STEP = 12;
    /** 详情区第一行相对 {@link #detailTop()} 的偏移（让开 1.5 倍的曲名）。 */
    private static final int INFO_FIRST_DY = 18;
    /** 详情区行数：难度 / 物量 / BPM / 曲师 / 谱师 / 画师 / 状态 / 最佳。 */
    private static final int INFO_ROWS = 8;
    /** 一行文字的高度（MC 字体 9px）。 */
    private static final int INFO_TEXT_H = 9;
    /** 最后一行下面留的空隙。 */
    private static final int INFO_PAD_BOTTOM = 4;
    /** 信息列里「标签」列的宽度（两个汉字 ≈ 18px，留到 34 好看）。 */
    private static final int LABEL_W = 34;
    private static final int BOTTOM_H = 26;
    private static final int BTN_W = 70;
    /** 「[✓] 自动游玩」比普通按钮宽：4 个汉字 + 勾选框 ≈ 60px。 */
    private static final int AUTO_W = 100;
    private static final int BTN_GAP = 8;
    /** 列表和底部按钮之间留给提示行的条带高度。 */
    private static final int NOTICE_H = 12;
    /** 行内「拷芯片」按钮尺寸。 */
    private static final int COPY_W = 56;
    private static final int COPY_H = 16;

    // ---------------- 在线页（Phira）布局常量 ----------------
    /** 顶部页签按钮：48×18，左上角两个。 */
    private static final int TAB_W = 48;
    private static final int TAB_H = 18;
    private static final int SEARCH_H = 20;
    private static final int SEARCH_BTN_W = 56;
    private static final int ORDER_BTN_W = 84;
    /** 「[✓] 仅上架」按钮宽度（和「排序」一样宽，风格一致）。 */
    private static final int STABLE_BTN_W = 84;
    /** 在线列表行高 / 行距 / 缩略图边长（比本地行高一点，给缩略图留位置）。 */
    private static final int ONLINE_ROW_H = 24;
    private static final int ONLINE_ROW_STEP = 26;
    private static final int ONLINE_THUMB = 20;
    /** 在线页底部两条提示（下载状态 + 版权声明）占的高度。 */
    private static final int ONLINE_NOTICE_H = 24;
    /**
     * 在线页每页行数上限（接口的 {@code pageNum} 上限是 30）。
     *
     * <p>和本地页签的 {@link #PER_PAGE_MAX}=20 不一样：本地列表从详情面板下面开始（720 只放得下 20 行），
     * 在线列表从搜索栏下面开始（720 能放 23 行），所以这里的上限按接口能接受的最大值给，一页铺满一屏。
     */
    private static final int ONLINE_PAGE_MAX = 30;
    /** 在线页列表与搜索框之间的空隙。 */
    private static final int ONLINE_LIST_GAP = 6;

    // ---------------- 本地页：搜索 / 排序 / 筛选 那一行 ----------------
    /** 筛选行高（和在线页的搜索框一样高）。 */
    private static final int FILTER_H = SEARCH_H;
    /**
     * 筛选行与详情面板底的距离。
     *
     * <p>{@link #detailBottom()} 那块的半透明面板 fill 一直画到 {@code detailBottom() + 6}，
     * 而面板是在 {@code super.render()}（画子控件）<b>之后</b>画的 —— 让开 8px 就不会把搜索框压掉。
     */
    private static final int FILTER_GAP_TOP = 8;
    /** 筛选行与列表顶的距离。 */
    private static final int FILTER_GAP_LIST = 6;
    /** 「排序：默认」按钮宽（5 个汉字 ≈ 45px）。 */
    private static final int SORT_BTN_W = 84;
    /** 「锁定：全部」按钮宽。 */
    private static final int LOCK_BTN_W = 76;
    /** 「难度：全部」按钮宽。 */
    private static final int DIFF_BTN_W = 76;

    /** 顶部页签：本地曲库 / Phira 在线谱面库。 */
    private enum Tab {
        LOCAL,
        ONLINE
    }

    /** 本地页的锁定筛选（循环按钮：全部 → 仅已解锁 → 仅未解锁 → 全部）。 */
    private enum LockFilter {
        ALL("全部"),
        UNLOCKED("仅已解锁"),
        LOCKED("仅未解锁");

        final String label;

        LockFilter(String label) {
            this.label = label;
        }
    }

    /** 本地页的排序方式（循环按钮，顺序就是这个数组的顺序）。 */
    private enum SortMode {
        /** 默认 = {@code songs/} 的扫描顺序（文件名顺序），也就是「添加顺序」。 */
        DEFAULT("默认"),
        TITLE("曲名"),
        DIFFICULTY("难度"),
        /** 物量：需要 {@link SongMeta} 的音符数（后台串行读，没读到的先排最后）。 */
        NOTES("物量"),
        /** 最佳成绩降序：{@code records.tsv} 里的分数，没打过的排最后。 */
        BEST("最佳成绩");

        final String label;

        SortMode(String label) {
            this.label = label;
        }
    }

    /** 难度筛选的循环顺序：{@code ""} = 全部。 */
    private static final String[] DIFF_CYCLE = {"", "EZ", "HD", "IN", "AT"};
    /** 难度排序的档位（认不出的难度排最后）。 */
    private static final String[] DIFF_ORDER = {"EZ", "HD", "IN", "AT"};

    // ---------------- 颜色 ----------------
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_DIM = 0xFF9A9A9A;
    private static final int C_ACCENT = 0xFFB4E1FF;
    private static final int C_GOLD = 0xFFFFEC9F;
    private static final int C_ROW = 0x50000000;
    private static final int C_ROW_HOVER = 0x60FFFFFF;
    private static final int C_ROW_SEL = 0x8A3A5A8A;
    private static final int C_LOCKED = 0xFF6B6B6B;
    private static final int C_OK = 0xFF7CFC8A;
    /** 在线页的错误红（超时 / HTTP 错误 / 磁盘错误）。 */
    private static final int C_ERR = 0xFFFF8A8A;
    /** 在线页里「未上架 / 未过审」谱面的曲名灰（比正常行暗一档）。 */
    private static final int C_UNPUB = 0xFF9C9C9C;
    /** 未上架谱面的难度 / 评分 / 谱师灰。 */
    private static final int C_UNPUB_META = 0xFF7C7C7C;

    /** 关闭后回到哪个界面；{@code null} = 直接回游戏（P 键 / 结算界面进来）。 */
    private final Supplier<Screen> backTo;
    /**
     * <b>全部谱面视为已解锁</b>（谱面播放器 {@code chart_player} / P 键），不受
     * {@code Profile.isUnlocked} 限制、也不显示「未解锁」。
     *
     * <p>从<b>平板</b>进来时是 {@code false}（生存玩法：只有解锁过的能玩）。它只影响
     * 「能不能开始」这一件事：<b>绝不会</b>把谱面写进 {@code Profile.unlocked}（否则平板那边
     * 也会跟着变成解锁）；「拷芯片」也仍然只对真正解锁过的谱面出现
     * （{@code ChipItem.carveHeld} 自己会再查一次存档）。
     */
    private final boolean unlockAll;

    /** 当前选中的曲目下标（-1 = 还没初始化，init 时落到上次玩的那首）。 */
    private int selected = -1;
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;
    private ButtonWidget startBtn;
    /** 「[✓] 自动游玩 / [ ] 自动游玩」勾选按钮（挨着「开始」）。 */
    private ButtonWidget autoBtn;
    /** 自动游玩勾选状态（init 时从 {@code profile.autoplay()} 读，点一下写回存档）。 */
    private boolean autoplayOn;
    /** 每行一个「拷芯片」按钮（按行的屏幕槽位建，{@code render} 里按当前页决定显示哪些）。 */
    private final List<ButtonWidget> copyBtns = new ArrayList<>();
    /** 底部提示（拷贝结果 / 未解锁 / 谱面解析失败 / 自动游玩开关）。 */
    private String notice = "";

    // ---------------- 本地页：搜索 / 排序 / 筛选 ----------------
    /**
     * 本地页的搜索词 / 排序 / 两个筛选条件。
     *
     * <p><b>静态 = 跨「界面重建」保持</b>：打完一局回来、从设置页回来、窗口缩放都会重新
     * {@code new SongSelectScreen()} + {@code init()}，条件放实例里会每次都回到默认值，用起来很别扭。
     * <b>但不写 {@code profile.json}</b>（需求允许不持久化）：重启游戏即恢复默认
     * （{@code 默认 / 全部 / 全部 / 空搜索词}）。
     */
    private static String localQuery = "";
    private static SortMode sortMode = SortMode.DEFAULT;
    private static LockFilter lockFilter = LockFilter.ALL;
    /** 难度筛选：{@code ""} = 全部，否则 EZ / HD / IN / AT。 */
    private static String diffFilter = "";

    /**
     * 本地列表「搜索 + 筛选 + 排序」之后的视图（**{@link #selected} 是它的下标**）。
     *
     * <p>每次 {@link #ensureView(List)} 重建；只有「条件变了 / 列表 rescan 了 / 物量缓存又到了一批」
     * 才真的重排（{@link #viewDirty}），不是每帧。
     */
    private List<SongEntry> view = List.of();
    /** 视图需要重建。 */
    private boolean viewDirty = true;
    /** 上次建视图时已知物量的首数（「排序：物量」靠它感知「后台又读完几首」）。 */
    private int viewNotesKnown = -1;
    /**
     * 当前选中的那一首（**不是下标**）。过滤 / 排序 / rescan 之后按<b>路径</b>重新定位，
     * 所以「打完一局回来」「改个筛选条件」都不会把选中项甩回第 1 行。
     */
    private SongEntry selectedPin;
    /** 本地页搜索框（只在本地页签可见；内容跨页签 / 跨界面重建保留）。 */
    private TextFieldWidget localSearchField;
    private ButtonWidget sortBtn;
    private ButtonWidget lockBtn;
    private ButtonWidget diffBtn;

    // ---------------- 在线页签状态 ----------------
    /** 当前页签（本地 / 在线）。**只由 {@link #setTab(Tab)} 改**。 */
    private Tab tab = Tab.LOCAL;
    /** 在线页的搜索状态机（后台线程写、渲染线程读 volatile 快照，见 {@link PhiraBrowser}）。 */
    private final PhiraBrowser phira = new PhiraBrowser();
    /** 搜索框（只在在线页签可见；内容跨页签保留）。 */
    private TextFieldWidget searchField;
    private ButtonWidget tabLocalBtn;
    private ButtonWidget tabOnlineBtn;
    private ButtonWidget searchBtn;
    private ButtonWidget orderBtn;
    /** 「[✓] 仅上架 / [ ] 仅上架」开关（挨着「排序」，最右边那个）。 */
    private ButtonWidget stableBtn;
    /**
     * 每行一个「下载」按钮：和 {@link #copyBtns} 一样按<b>行槽位</b>建（最多
     * {@link #ONLINE_PAGE_MAX} 个），点的时候按当前页换算成 {@link PhiraChart}，
     * 翻页 / 重新搜索都不重建控件。
     */
    private final List<ButtonWidget> downloadBtns = new ArrayList<>();
    /** 每个下载按钮上一次设过的文字（只有变了才 {@code setMessage}，避免每帧造 Text）。 */
    private final List<String> downloadLabels = new ArrayList<>();
    /** 在线列表当前选中的行（-1 = 没选）；回车下载它。 */
    private int onlineSel = -1;
    /** 最近一次已知的鼠标坐标（1.20.1 的 renderBackground 不带鼠标参数，用它做 hover 高亮）。 */
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    /** 正在跟的下载任务 id（-1 = 没有）；完成 / 失败都只处理一次。 */
    private long activeJobId = -1;
    /** 已经消费过终态的任务 id（同一个任务不会重复提示 / 重复 rescan）。 */
    private long consumedJobId = -1;
    /** 在线页底部状态行（搜索中 / 下载中 / 失败原因）。 */
    private String onlineNotice = "";
    /** 在线页底部状态行的颜色（成功绿 / 失败红 / 一般说明灰）。 */
    private int onlineNoticeColor = C_DIM;

    /**
     * @param unlockAll 全部谱面可玩（谱面播放器 / P 键）
     */
    public SongSelectScreen(boolean unlockAll) {
        this(unlockAll, null);
    }

    /**
     * @param backTo 关闭后回到哪个界面；{@code null} = 回游戏。平板用 {@code TabletScreen::new}。
     */
    public SongSelectScreen(Supplier<Screen> backTo) {
        this(false, backTo);
    }

    /**
     * @param unlockAll 全部谱面可玩（谱面播放器 / P 键）；平板传 {@code false}
     * @param backTo    关闭后回到哪个界面；{@code null} = 回游戏
     */
    public SongSelectScreen(boolean unlockAll, Supplier<Screen> backTo) {
        super(Text.literal("PhiMC 选曲"));
        this.unlockAll = unlockAll;
        this.backTo = backTo;
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    /** 曲绘方框边长：屏高的 3/16（480→90，720→135）。 */
    private int coverSize() {
        return Math.max(48, Math.round(height * 3f / 16f));
    }

    /** 信息列 8 行所需的垂直空间（480 / 720 都是 115，比 480 的曲绘 90 高，所以详情区取 max）。 */
    private int infoNeed() {
        return INFO_FIRST_DY + (INFO_ROWS - 1) * INFO_STEP + INFO_TEXT_H + INFO_PAD_BOTTOM;
    }

    private int detailTop() {
        return HEADER_H;
    }

    /** 详情区高度 = max(曲绘边长, 信息列所需)；窄屏（854×480）由信息列决定高度。 */
    private int detailHeight() {
        return Math.max(coverSize(), infoNeed());
    }

    private int detailBottom() {
        return detailTop() + detailHeight();
    }

    /** 本地页筛选行的 y：详情面板下面（面板 fill 到 detailBottom()+6，让开它）。 */
    private int filterY() {
        return detailBottom() + FILTER_GAP_TOP;
    }

    /** 「难度：xx」按钮的左边界（贴右边界）。 */
    private int diffBtnX() {
        return width - PAD - DIFF_BTN_W;
    }

    /** 「锁定：xx」按钮的左边界。 */
    private int lockBtnX() {
        return diffBtnX() - BTN_GAP - LOCK_BTN_W;
    }

    /** 「排序：xx」按钮的左边界。 */
    private int sortBtnX() {
        return lockBtnX() - BTN_GAP - SORT_BTN_W;
    }

    /** 搜索框宽度：从 PAD 一直铺到「排序」按钮左边（窄屏保底 40）。 */
    private int localFieldW() {
        return Math.max(40, sortBtnX() - BTN_GAP - PAD);
    }

    /** 列表顶：筛选行下面留 {@link #FILTER_GAP_LIST}。 */
    private int listTop() {
        return filterY() + FILTER_H + FILTER_GAP_LIST;
    }

    private int listBottom() {
        return height - BOTTOM_H - 4;
    }

    /** 每页条数：按列表可用高度算（先让出 {@link #NOTICE_H}），保证最后一行不会压到提示行 / 底部按钮。 */
    private int perPage() {
        int n = (listBottom() - NOTICE_H - listTop()) / ROW_STEP;
        return Math.max(PER_PAGE_MIN, Math.min(PER_PAGE_MAX, n));
    }

    /** 选中项所在的页（页不再单独存，永远由 selected 推出，翻页和选中不会脱节）。 */
    private int currentPage(int perPage) {
        return selected / perPage;
    }

    private int rowY(int row) {
        return listTop() + row * ROW_STEP;
    }

    /** 行内「拷芯片」按钮的左边界。 */
    private int copyX() {
        return width - PAD - 4 - COPY_W;
    }

    /** 提示行的 y（列表最后一行的下面、底部按钮的上面）。 */
    private int noticeY() {
        return listBottom() - NOTICE_H + 2;
    }

    // ---------------- 在线页布局（见类注释） ----------------

    /** 顶部页签的 y（标题在 y=8 居中，页签在左上角，互不重叠）。 */
    private int tabY() {
        return 3;
    }

    private int tabLocalX() {
        return PAD;
    }

    private int tabOnlineX() {
        return tabLocalX() + TAB_W + 4;
    }

    /** 页签行的右边界（副标题从它右边开始左对齐，所以永远压不到页签）。 */
    private int tabsEndX() {
        return tabOnlineX() + TAB_W;
    }

    /** 搜索框的 y（标题区 34 下面第一行）。 */
    private int searchY() {
        return HEADER_H + 2;
    }

    /** 搜索框宽度：屏幕宽减去左右留白、「搜索」「排序」「仅上架」三个按钮和间距。 */
    private int searchFieldW() {
        int w = width - 2 * PAD - SEARCH_BTN_W - ORDER_BTN_W - STABLE_BTN_W - 3 * BTN_GAP;
        return Math.max(40, w);
    }

    private int searchBtnX() {
        return PAD + searchFieldW() + BTN_GAP;
    }

    private int orderBtnX() {
        return searchBtnX() + SEARCH_BTN_W + BTN_GAP;
    }

    /**
     * 「[✓] 仅上架」按钮的左边界：挨着「排序」，右边界贴 {@code W - PAD}。
     *
     * <p>由 {@link #searchFieldW()} 的算式保证：
     * {@code orderBtnX + ORDER_BTN_W + BTN_GAP + STABLE_BTN_W == width - PAD}。
     */
    private int stableBtnX() {
        return orderBtnX() + ORDER_BTN_W + BTN_GAP;
    }

    /** 在线列表顶：搜索框下面留 {@link #ONLINE_LIST_GAP}。 */
    private int onlineListTop() {
        return searchY() + SEARCH_H + ONLINE_LIST_GAP;
    }

    private int onlineRowY(int row) {
        return onlineListTop() + row * ONLINE_ROW_STEP;
    }

    /**
     * 在线页每页能放几行。**这个值同时是请求的 {@code pageNum}**（{@link PhiraBrowser} 的
     * {@code pageSize}）：一页正好铺满一屏，不用在页内再滚动（本地页签也是「一页 = 一屏」）。
     * 480 → 14，720 → 23（上限 {@link #ONLINE_PAGE_MAX} = 30）。
     */
    private int onlinePerPage() {
        int n = (listBottom() - ONLINE_NOTICE_H - onlineListTop()) / ONLINE_ROW_STEP;
        return Math.max(1, Math.min(ONLINE_PAGE_MAX, n));
    }

    /** 在线页第一行状态（下载中 / 失败原因）。 */
    private int onlineStatusY() {
        return listBottom() - ONLINE_NOTICE_H + 2;
    }

    /** 在线页第二行：版权声明（永远显示）。 */
    private int onlineLegalY() {
        return onlineStatusY() + 11;
    }

    /** 行内「下载」按钮的左边界（和本地页的「拷芯片」同一个右侧槽位）。 */
    private int downloadX() {
        return copyX();
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        Session session = PhiMCClient.session();
        if (session == null) return;
        // 顺序有讲究：rescan() 必须早于 ensureResPack()。
        // rescan → SongEntry 全部重建 → 立即从 SongMeta 缓存回填 META 兜底（画师/曲师/谱师）；
        // ensureResPack() 换包时会 SongMeta.clear()，顺序反了就会让这一帧先显示「未知」、
        // 等后台重解析完才恢复。（打完一局返回、从设置页回来走的都是这条路径。）
        session.rescan();
        List<SongEntry> songs = session.songs();
        // 存档里选的材质包（贴图要注册到 TextureManager，只能在客户端线程 / 界面 init 时做）
        PhiMCClient.ensureResPack();

        // 选中项：第一次进来落在「上次玩的那首」（之后由 selectedPin 跨搜索 / 筛选 / rescan 保持）。
        // selectedPin 按路径定位，rescan 之后 songs 里全是新对象，所以这里要用**这一批**里的对象。
        if (selectedPin == null && !songs.isEmpty()) {
            int cur = session.currentIndex();
            selectedPin = cur >= 0 && cur < songs.size() ? songs.get(cur) : songs.get(0);
        }
        viewDirty = true;          // 列表可能变了（下载完成 / 手动塞包）→ 视图重建

        int cx = width / 2;
        int bottomY = height - BOTTOM_H;
        // 6 个槽位（5 个 70 + 「自动游玩」100），间距 8，整体居中；窄屏按比例收窄，绝不溢出屏幕
        int bw = BTN_W;
        int autoW = AUTO_W;
        int total = bw * 5 + autoW + BTN_GAP * 5;
        int avail = width - 2 * PAD;
        if (total > avail) {
            bw = Math.max(30, (avail - BTN_GAP * 5) / 6);
            autoW = bw;
            total = bw * 6 + BTN_GAP * 5;
        }
        int x0 = cx - total / 2;
        int xPrev = x0;
        int xStart = xPrev + bw + BTN_GAP;
        int xAuto = xStart + bw + BTN_GAP;
        int xBack = xAuto + autoW + BTN_GAP;
        int xSet = xBack + bw + BTN_GAP;
        int xNext = xSet + bw + BTN_GAP;

        prevBtn = addDrawableChild(ButtonWidget.builder(Text.literal("← 上一页"),
                b -> prevPage()).dimensions(xPrev, bottomY, bw, 20).build());
        startBtn = addDrawableChild(ButtonWidget.builder(Text.literal("开始"),
                b -> startSelected()).dimensions(xStart, bottomY, bw, 20).build());
        // 勾选式「自动游玩」：紧挨在「开始」右边，点一下切换（状态存 profile.json）
        autoplayOn = PhiMCClient.session() != null
                && PhiMCClient.session().profile() != null
                && PhiMCClient.session().profile().autoplay();
        autoBtn = addDrawableChild(ButtonWidget.builder(Text.literal(autoplayLabel()),
                b -> toggleAutoplay()).dimensions(xAuto, bottomY, autoW, 20).build());
        // 从平板进来 = 「商店」（关闭 → 回 TabletScreen）；否则就是普通的关闭
        addDrawableChild(ButtonWidget.builder(Text.literal(backTo == null ? "关闭" : "商店"),
                b -> close()).dimensions(xBack, bottomY, bw, 20).build());
        // 设置入口：来源界面 = 选曲界面，返回时回到这里（setScreen 必须在客户端主线程，走 client.execute）
        if (client != null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("设置"), b ->
                    client.execute(() -> client.setScreen(new SettingsScreen(SongSelectScreen.this)))
            ).dimensions(xSet, bottomY, bw, 20).build());
        }
        nextBtn = addDrawableChild(ButtonWidget.builder(Text.literal("下一页 →"),
                b -> nextPage()).dimensions(xNext, bottomY, bw, 20).build());

        // ---- 每行的「拷芯片」按钮 ----
        // 按「屏幕槽位」建（每页最多 perPage() 个），点的时候再按当前页换算到曲目下标，
        // 这样翻页 / 上下移动选中项都不用重建控件。
        copyBtns.clear();
        for (int i = 0; i < perPage(); i++) {
            final int row = i;
            ButtonWidget copy = ButtonWidget.builder(Text.literal("拷芯片"), b -> copyRow(row))
                    .dimensions(copyX(), rowY(i) + (ROW_H - COPY_H) / 2, COPY_W, COPY_H).build();
            copy.visible = false;                 // 由 syncButtons 按「该行有谱面且已解锁」打开
            addDrawableChild(copy);
            copyBtns.add(copy);
        }

        if (songs.isEmpty()) {
            // 放在详情面板下面（面板 fill 画在 super.render 之后，压在面板里会被盖住）
            addDrawableChild(ButtonWidget.builder(Text.literal("关闭（songs 目录是空的）"), b -> close())
                    .dimensions(cx - 80, listTop() + 4, 160, 20).build());
        }

        // ---- 本地页：搜索框 + 排序 / 锁定 / 难度 三个循环按钮（详情面板与列表之间那一行） ----
        localSearchField = new TextFieldWidget(textRenderer, PAD, filterY(), localFieldW(), FILTER_H,
                Text.literal("搜索曲名 / 曲师 / 谱师"));
        localSearchField.setMaxLength(64);
        localSearchField.setText(localQuery);
        addDrawableChild(localSearchField);
        sortBtn = addDrawableChild(ButtonWidget.builder(Text.literal(sortLabel()), b -> cycleSort())
                .dimensions(sortBtnX(), filterY(), SORT_BTN_W, FILTER_H).build());
        lockBtn = addDrawableChild(ButtonWidget.builder(Text.literal(lockLabel()), b -> cycleLockFilter())
                .dimensions(lockBtnX(), filterY(), LOCK_BTN_W, FILTER_H).build());
        diffBtn = addDrawableChild(ButtonWidget.builder(Text.literal(diffLabel()), b -> cycleDiffFilter())
                .dimensions(diffBtnX(), filterY(), DIFF_BTN_W, FILTER_H).build());
        // 上次退出时如果停在「排序：物量」，进界面就把后台队列排上（每包只读一次、串行）
        if (sortMode == SortMode.NOTES) requestNotes();

        // ---- 顶部页签：本地 / 在线 ----
        tabLocalBtn = addDrawableChild(ButtonWidget.builder(Text.literal("本地"), b -> setTab(Tab.LOCAL))
                .dimensions(tabLocalX(), tabY(), TAB_W, TAB_H).build());
        tabOnlineBtn = addDrawableChild(ButtonWidget.builder(Text.literal("在线"), b -> setTab(Tab.ONLINE))
                .dimensions(tabOnlineX(), tabY(), TAB_W, TAB_H).build());

        // ---- 在线页：搜索框 + 搜索 / 排序 ----
        searchField = new TextFieldWidget(textRenderer, PAD, searchY(), searchFieldW(), SEARCH_H,
                Text.literal("搜索"));
        searchField.setMaxLength(64);
        searchField.setText(phira.query());
        addDrawableChild(searchField);
        searchBtn = addDrawableChild(ButtonWidget.builder(Text.literal("搜索"), b -> doSearch(1))
                .dimensions(searchBtnX(), searchY(), SEARCH_BTN_W, SEARCH_H).build());
        orderBtn = addDrawableChild(ButtonWidget.builder(Text.literal(orderLabel()), b -> toggleOrder())
                .dimensions(orderBtnX(), searchY(), ORDER_BTN_W, SEARCH_H).build());
        // 「仅上架」开关：默认开（只看 stable && reviewed）；关掉后未上架 / 未过审的也显示，灰色标注
        stableBtn = addDrawableChild(ButtonWidget.builder(Text.literal(stableLabel()), b -> toggleOnlyStable())
                .dimensions(stableBtnX(), searchY(), STABLE_BTN_W, SEARCH_H).build());

        // ---- 在线页：每行一个「下载」按钮（按行槽位建，可见性由 syncButtons 定） ----
        downloadBtns.clear();
        downloadLabels.clear();
        int dlOffset = (ONLINE_ROW_H - COPY_H) / 2;
        for (int i = 0; i < ONLINE_PAGE_MAX; i++) {
            final int row = i;
            ButtonWidget dl = ButtonWidget.builder(Text.literal("下载"), b -> downloadRow(row))
                    .dimensions(downloadX(), onlineRowY(i) + dlOffset, COPY_W, COPY_H).build();
            dl.visible = false;                 // 由 syncButtons 按「当前页签 + 这一行有没有谱面」打开
            addDrawableChild(dl);
            downloadBtns.add(dl);
            downloadLabels.add("下载");
        }
        // 已下载集合（只列一次目录，很轻）：进界面就同步，标「已下载」用
        PhiraDownloader.refresh(songsDir());
        // 窗口缩放 / 从别的界面回来：在线页签按新的行数重新取一页（命中 60 秒缓存，不会重复请求）
        if (tab == Tab.ONLINE) {
            int per = onlinePerPage();
            if (phira.pageSize() != per || phira.charts().isEmpty()) {
                phira.reload(per);
            }
        }
        syncButtons();
    }

    /**
     * 控件状态：页签 / 翻页 / 开始 / 每行的「拷芯片」/ 每行的「下载」——
     * 全部由「当前页签 + 当前选中的曲目 / 在线结果 + 当前页」推出来。
     *
     * <p>每帧都调（{@link #render} 里），所以这里只做<b>读 volatile + 赋值</b>，
     * 不读磁盘、不发请求。按钮文字只有变了才 {@code setMessage}（{@link #downloadLabels}）。
     *
     * <p>开头先 {@link #ensureView(List)}：本地页的 view / selected 都从那里来
     * （脏了才重排，不脏就是一次空调用）。
     */
    private void syncButtons() {
        Session s = PhiMCClient.session();
        List<SongEntry> songs = s == null ? List.of() : s.songs();
        ensureView(songs);
        boolean online = tab == Tab.ONLINE;
        int per = perPage();
        int page = view.isEmpty() ? 0 : currentPage(per);

        if (prevBtn != null) {
            prevBtn.active = online ? (phira.page() > 1 && !phira.loading()) : page > 0;
        }
        if (nextBtn != null) {
            nextBtn.active = online
                    ? (phira.page() < phira.pages() && !phira.loading())
                    : (page + 1) * per < view.size();
        }
        if (startBtn != null) {
            startBtn.active = !online && selected >= 0 && selected < view.size() && !isLocked(selected);
        }
        // 自动游玩是「开局」的开关，在线页签没有开局，整个藏掉（不留一个点不动的勾选框）
        if (autoBtn != null) autoBtn.visible = !online;

        // 顶部页签：当前那个 active=false（变灰 = 已选中），另一个可点
            if (tabLocalBtn != null) tabLocalBtn.active = true;
            if (tabOnlineBtn != null) tabOnlineBtn.active = true;
        if (searchField != null) searchField.visible = online;
        if (searchBtn != null) searchBtn.visible = online;
        if (orderBtn != null) orderBtn.visible = online;
        if (stableBtn != null) stableBtn.visible = online;
        // 本地页的搜索 / 排序 / 筛选那一行：只在本页签显示
        if (localSearchField != null) localSearchField.visible = !online;
        if (sortBtn != null) sortBtn.visible = !online;
        if (lockBtn != null) lockBtn.visible = !online;
        if (diffBtn != null) diffBtn.visible = !online;

        for (int i = 0; i < copyBtns.size(); i++) {
            ButtonWidget copy = copyBtns.get(i);
            int index = page * per + i;
            // 未解锁的行**不放**按钮（见类注释的取舍）；「拷芯片」只看**真解锁**，
            // 播放器模式（unlockAll）只是「能玩」，不允许把没解锁的谱面复制到芯片里。
            // 在线页签一律不显示（那是本地曲库的操作）。
            boolean show = !online && index >= 0 && index < view.size() && trulyUnlocked(view.get(index));
            copy.visible = show;
            copy.active = show;
        }

        List<PhiraChart> charts = phira.charts();
        int rows = onlinePerPage();
        for (int i = 0; i < downloadBtns.size(); i++) {
            ButtonWidget dl = downloadBtns.get(i);
            boolean show = online && i < rows && i < charts.size();
            dl.visible = show;
            if (!show) continue;
            String label = downloadLabel(charts.get(i));
            if (!label.equals(downloadLabels.get(i))) {
                downloadLabels.set(i, label);
                dl.setMessage(Text.literal(label));
            }
            // 「已下载」/「下载中」都禁用，不会重复下载
            dl.active = "下载".equals(label);
        }
    }

    /** 某一行右侧按钮的文字：{@code 下载} / {@code 下载中} / {@code 已下载}。 */
    private String downloadLabel(PhiraChart chart) {
        if (PhiraDownloader.isDownloaded(chart.id)) return "已下载";
        PhiraDownloader.Job job = PhiraDownloader.job(chart.id);
        if (job != null && (job.state() == PhiraDownloader.State.QUEUED
                || job.state() == PhiraDownloader.State.RUNNING)) return "下载中";
        return "下载";
    }

    // ------------------------------------------------------------------
    // 本地页：搜索 / 筛选 / 排序（三者组合生效）
    // ------------------------------------------------------------------

    /**
     * 重建本地视图，并把 {@link #selected} 重新定位到 {@link #selectedPin}。
     *
     * <p>顺序是<b>先过滤、再排序</b>：
     * <ol>
     *   <li>搜索词（曲名 / 曲师 / 谱师 子串，大小写不敏感）；</li>
     *   <li>锁定筛选（全部 / 仅已解锁 / 仅未解锁，判定走 {@link #isLocked(SongEntry)}）；</li>
     *   <li>难度筛选（全部 / EZ / HD / IN / AT，比 {@code grade}）；</li>
     *   <li>排序（默认 / 曲名 / 难度 / 物量 / 最佳成绩）；</li>
     * </ol>
     * 三个条件同时满足才进列表 —— 也就是「组合生效」，分页 / 行号 / 选中项全部跟着 view 走。
     *
     * <p><b>只在脏的时候重排</b>（{@link #viewDirty}）：搜索词变了、点了两个筛选 / 排序按钮、
     * 列表 rescan 了、或者「排序：物量」的后台又读完一首。其余情况每帧只做两次便宜的比较。
     */
    private void ensureView(List<SongEntry> songs) {
        // 搜索框「边打边过滤」：每帧比一次文本 —— 不依赖 TextFieldWidget 的回调 API，跨版本更稳
        if (localSearchField != null) {
            String t = localSearchField.getText();
            if (!t.equals(localQuery)) {
                localQuery = t;
                viewDirty = true;
            }
        }
        // 「排序：物量」需要全量音符数（后台串行读）：每读完一首顺序就要重排一次。
        // 只在「已知数量变了」时才再排一次队 —— 解析失败（进 FAILED）的谱面永远读不到，
        // 不能让它害我们每帧都调 requestAll。
        if (sortMode == SortMode.NOTES) {
            int known = knownNotes(songs);
            if (known != viewNotesKnown) {
                viewDirty = true;
                viewNotesKnown = known;
                if (known < songs.size()) requestNotes();     // 幂等：已排队 / 已缓存的直接跳过
            }
        }
        if (!viewDirty) return;
        viewDirty = false;

        List<SongEntry> out = new ArrayList<>(songs.size());
        for (SongEntry e : songs) {
            if (matchesLocal(e)) out.add(e);
        }
        sortLocal(out);
        view = out;

        // 选中项：按**路径**重新定位（rescan 之后 songs 里全是新对象，不能按引用比）；
        // 它被搜索 / 筛选挡掉了，就落到第 1 行（不能留在「看不见的选中项」上）。
        int idx = indexOfPath(out, selectedPin);
        if (idx < 0) idx = out.isEmpty() ? -1 : 0;
        selected = idx;
        selectedPin = idx >= 0 ? out.get(idx) : null;
        // 页码是「由 selected 推出」的，选中项换了之后可能翻页；这里不做别的，
        // render / mouseClicked 里统一用 currentPage(perPage()) 现算。
    }

    /** 当前选中的那一首（view 为空 / 下标越界时 {@code null}）。 */
    private SongEntry selectedSong() {
        return selected >= 0 && selected < view.size() ? view.get(selected) : null;
    }

    /** 一首歌是否符合当前「搜索 + 锁定筛选 + 难度筛选」。 */
    private boolean matchesLocal(SongEntry e) {
        if (e == null) return false;
        if (lockFilter == LockFilter.UNLOCKED && isLocked(e)) return false;
        if (lockFilter == LockFilter.LOCKED && !isLocked(e)) return false;
        if (!diffFilter.isEmpty() && !diffFilter.equalsIgnoreCase(nz(e.grade))) return false;
        if (localQuery.isEmpty()) return true;
        String q = localQuery.toLowerCase(Locale.ROOT);
        return containsText(e.title(), q) || containsText(e.composer(), q) || containsText(e.charter(), q);
    }

    /**
     * 按 {@link #sortMode} 排序（就地）。Java 的 sort 是<b>稳定排序</b>，
     * 相同键（同名 / 同难度 / 同物量 / 同分）保持 {@code songs/} 的原始顺序，
     * 所以「默认」这一档根本不用排。
     */
    private void sortLocal(List<SongEntry> out) {
        switch (sortMode) {
            case DEFAULT -> {
                // 采集顺序 = songs/ 扫描顺序 = 默认顺序
            }
            case TITLE -> out.sort(Comparator
                    .comparing((SongEntry e) -> nz(e.title()).toLowerCase(Locale.ROOT))
                    .thenComparingInt(SongSelectScreen::difficultyRank));
            case DIFFICULTY -> out.sort(Comparator
                    .comparingInt(SongSelectScreen::difficultyRank)
                    .thenComparing(e -> nz(e.title()).toLowerCase(Locale.ROOT)));
            case NOTES -> out.sort(Comparator
                    .comparingInt((SongEntry e) -> notesOf(e))          // 未知物量 = MAX ⇒ 排最后
                    .thenComparing(e -> nz(e.title()).toLowerCase(Locale.ROOT)));
            case BEST -> out.sort(Comparator
                    .comparingInt((SongEntry e) -> bestScoreOf(e)).reversed()   // 没打过的 -1 ⇒ 排最后
                    .thenComparing(e -> nz(e.title()).toLowerCase(Locale.ROOT)));
        }
    }

    /** 难度档位：EZ(0) &lt; HD(1) &lt; IN(2) &lt; AT(3)，认不出的（{@code -} / 原文）排最后。 */
    private static int difficultyRank(SongEntry e) {
        String g = nz(e.grade).toUpperCase(Locale.ROOT);
        for (int i = 0; i < DIFF_ORDER.length; i++) {
            if (DIFF_ORDER[i].equals(g)) return i;
        }
        return DIFF_ORDER.length;
    }

    /** 音符数（{@link SongMeta#peek} 只看缓存）；还没解析过的用 {@code MAX_VALUE} 排最后。 */
    private static int notesOf(SongEntry e) {
        SongMeta.Meta m = SongMeta.peek(e);
        return m == null ? Integer.MAX_VALUE : m.notes();
    }

    /** 已知物量的首数（判断「排序：物量」要不要重排）。 */
    private static int knownNotes(List<SongEntry> songs) {
        int n = 0;
        for (SongEntry e : songs) if (SongMeta.peek(e) != null) n++;
        return n;
    }

    /** 最佳成绩（没打过 = -1）。 */
    private static int bestScoreOf(SongEntry e) {
        Session s = PhiMCClient.session();
        Records.Rec r = s == null ? null : s.best(e);
        return r == null ? -1 : r.score();
    }

    /** 大小写不敏感的「包含」（{@code needle} 已经小写）。 */
    private static boolean containsText(String hay, String needleLower) {
        return hay != null && !hay.isEmpty() && hay.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 按<b>谱面包路径</b>在列表里找（rescan 会换对象，不能按引用）。 */
    private static int indexOfPath(List<SongEntry> list, SongEntry e) {
        if (e == null || e.path == null) return -1;
        Path want = e.path.toAbsolutePath();
        for (int i = 0; i < list.size(); i++) {
            Path p = list.get(i).path;
            if (p != null && p.toAbsolutePath().equals(want)) return i;
        }
        return -1;
    }

    /** 把全部谱面的物量 / BPM 排进 {@link SongMeta} 的后台队列（串行、每包只读一次）。 */
    private void requestNotes() {
        Session s = PhiMCClient.session();
        if (s != null) SongMeta.requestAll(s.songs());
    }

    // ---- 三个循环按钮的文字 / 点击 ----

    private String sortLabel() {
        return "排序：" + sortMode.label;
    }

    private String lockLabel() {
        return "锁定：" + lockFilter.label;
    }

    private String diffLabel() {
        return "难度：" + (diffFilter.isEmpty() ? "全部" : diffFilter);
    }

    /** 点「排序」：默认 → 曲名 → 难度 → 物量 → 最佳成绩 → 默认，立刻重排（不重建界面）。 */
    private void cycleSort() {
        SortMode[] all = SortMode.values();
        sortMode = all[(sortMode.ordinal() + 1) % all.length];
        if (sortBtn != null) sortBtn.setMessage(Text.literal(sortLabel()));
        viewDirty = true;
        viewNotesKnown = -1;
        // 物量要全量数据：切到这一档就立刻排队（后台串行，界面不等）
        if (sortMode == SortMode.NOTES) requestNotes();
        notice = "排序：" + sortMode.label + (sortMode == SortMode.NOTES
                ? "（物量要读谱面，后台一首一首来，数字先显示「…」）" : "");
    }

    /** 点「锁定」：全部 → 仅已解锁 → 仅未解锁 → 全部。 */
    private void cycleLockFilter() {
        LockFilter[] all = LockFilter.values();
        lockFilter = all[(lockFilter.ordinal() + 1) % all.length];
        if (lockBtn != null) lockBtn.setMessage(Text.literal(lockLabel()));
        viewDirty = true;
        notice = "筛选：" + lockFilter.label;
    }

    /** 点「难度」：全部 → EZ → HD → IN → AT → 全部。 */
    private void cycleDiffFilter() {
        int i = 0;
        for (int k = 0; k < DIFF_CYCLE.length; k++) {
            if (DIFF_CYCLE[k].equals(diffFilter)) {
                i = k;
                break;
            }
        }
        diffFilter = DIFF_CYCLE[(i + 1) % DIFF_CYCLE.length];
        if (diffBtn != null) diffBtn.setMessage(Text.literal(diffLabel()));
        viewDirty = true;
        notice = "难度筛选：" + (diffFilter.isEmpty() ? "全部" : diffFilter);
    }

    // ------------------------------------------------------------------
    // 页签
    // ------------------------------------------------------------------

    /** 切页签：本地 ↔ 在线。**不重建界面**（重建会把选中项 / 页码 / 搜索词再走一遍 init）。 */
    private void setTab(Tab want) {
        if (want == null || tab == want) return;
        tab = want;
        if (want == Tab.ONLINE) {
            // 进在线页前把**本地**搜索框焦点放掉：控件虽然 visible=false，但 Screen 仍会把
            // 字符键派发给「当前有焦点的控件」，不收的话打字的字符会被这个看不见的框吃掉。
            if (localSearchField != null) localSearchField.setFocused(false);
            // 进在线页先把「已下载」集合刷新一遍（别的进程往 songs/ 里丢过包也能标出来）
            PhiraDownloader.refresh(songsDir());
            // 第一次进来自动搜一页（空搜索词 = 按评分取最热门的稳定谱面）
            if (phira.charts().isEmpty() && !phira.loading()) {
                int per = onlinePerPage();
                phira.search(searchText(), phira.order(), 1, per);
                onlineSel = 0;
            }
        } else if (searchField != null) {
            // 回本地页时把搜索框焦点放掉：否则打字的英文 / 回车还会被那个看不见的输入框吃掉
            searchField.setFocused(false);
        }
        syncButtons();
    }

    /** 搜索框里的文字（搜索框还没建出来时退回上一次的搜索词）。 */
    private String searchText() {
        return searchField == null ? phira.query() : searchField.getText();
    }

    /** 点「搜索」/ 回车：从第 1 页重新搜。 */
    private void doSearch(int page) {
        int per = onlinePerPage();
        phira.search(searchText(), phira.order(), page, per);
        onlineSel = 0;
        // 「搜索中…」由 phira.loading() 那条状态行表达，这里清掉上一次的结果提示
        onlineNotice = "";
    }

    /** 点「排序」：评分 ⇄ 最新，立刻用新排序重搜第 1 页。 */
    private void toggleOrder() {
        String next = PhiraBrowser.ORDER_RATING.equals(phira.order())
                ? PhiraBrowser.ORDER_NEW : PhiraBrowser.ORDER_RATING;
        if (orderBtn != null) orderBtn.setMessage(Text.literal(orderLabel(next)));
        int per = onlinePerPage();
        phira.search(searchText(), next, 1, per);
        onlineSel = 0;
        onlineNotice = "";
    }

    /** 排序按钮的文字（当前排序高亮在这几个字里）。 */
    private String orderLabel() {
        return orderLabel(phira.order());
    }

    private String orderLabel(String order) {
        return "排序：" + (PhiraBrowser.ORDER_NEW.equals(order) ? "最新" : "评分");
    }

    /**
     * 「仅上架」开关的文字：{@code [✓] 仅上架} / {@code [ ] 仅上架}。
     *
     * <p>勾上 = 只显示「已上架 + 已过审」（和本功能原来的行为一致）；取消 = 全部谱面，
     * 未上架 / 未过审的行用灰色 + {@code [未上架]} 标出来。
     */
    private String stableLabel() {
        return (PhiraBrowser.onlyStable() ? "[✓] " : "[ ] ") + "仅上架";
    }

    /**
     * 切换「仅上架」并<b>立刻重搜第 1 页</b>（需求：点了开关要马上反映）。
     *
     * <p>两种模式在 {@link PhiraApi} 里的缓存键不同（键里带 onlyStable），所以来回切不会串；
     * 切回上一次的模式时 60 秒内直接命中缓存（0ms）。
     *
     * <p>只影响在线页：本地曲库 / 商店 / 平板都读不到这个开关。
     */
    private void toggleOnlyStable() {
        boolean next = !PhiraBrowser.onlyStable();
        PhiraBrowser.setOnlyStable(next);
        if (stableBtn != null) stableBtn.setMessage(Text.literal(stableLabel()));
        int per = onlinePerPage();
        // 从第 1 页重搜：总数会变（632 ⇄ 9737），停在旧页码会跳页
        phira.search(searchText(), phira.order(), 1, per);
        onlineSel = 0;
        onlineNotice = next
                ? "仅上架：开（只显示已上架 + 已过审的谱面）"
                : "仅上架：关（未上架 / 未过审的谱面也会显示，灰色 + [未上架]）";
        onlineNoticeColor = next ? C_DIM : C_ACCENT;
    }

    // ------------------------------------------------------------------
    // 翻页（本地 / 在线共用底部两个按钮）
    // ------------------------------------------------------------------

    private void prevPage() {
        if (tab == Tab.ONLINE) {
            if (phira.page() > 1 && !phira.loading()) {
                phira.gotoPage(phira.page() - 1, onlinePerPage());
                onlineSel = 0;
            }
            return;
        }
        move(-perPage());
    }

    private void nextPage() {
        if (tab == Tab.ONLINE) {
            if (phira.page() < phira.pages() && !phira.loading()) {
                phira.gotoPage(phira.page() + 1, onlinePerPage());
                onlineSel = 0;
            }
            return;
        }
        move(perPage());
    }

    // ------------------------------------------------------------------
    // 自动游玩
    // ------------------------------------------------------------------

    /** 勾选按钮的文字：{@code [✓] 自动游玩} / {@code [ ] 自动游玩}。 */
    private String autoplayLabel() {
        return (autoplayOn ? "[✓] " : "[ ] ") + "自动游玩";
    }

    /**
     * 点一下「自动游玩」：切换 + 写回 {@code profile.json}（跨局保持）+ 改按钮文字 + 底部提示。
     *
     * <p>不重建界面（{@code clearAndInit} 会把选中项/页码再走一遍 init）；
     * 下一局开局时 {@code Session.start()} 会从存档同步这个开关。
     */
    private void toggleAutoplay() {
        autoplayOn = !autoplayOn;
        Session s = PhiMCClient.session();
        if (s != null) s.setAutoplay(autoplayOn);
        if (autoBtn != null) autoBtn.setMessage(Text.literal(autoplayLabel()));
        notice = autoplayOn
                ? "自动游玩：开（引擎自动判 Perfect；这一局不给成绩、不发奖励）"
                : "自动游玩：关（恢复正常手动判定）";
    }

    // ------------------------------------------------------------------
    // 锁定 / 存档
    // ------------------------------------------------------------------

    private Profile profile() {
        Session s = PhiMCClient.session();
        return s == null ? null : s.profile();
    }

    /**
     * <b>唯一的锁定闸门</b>：这一首能不能玩。
     *
     * <p>{@link #unlockAll}（谱面播放器 / P 键）时一律可玩；否则看存档的
     * {@code Profile.isUnlocked}（没有存档 = 锁）。「不能开始」「整行灰 + 未解锁」
     * 「开始按钮变灰」「仅已解锁 / 仅未解锁 筛选」全都走这一个方法，不再各自判断。
     *
     * @param index <b>view 的下标</b>（不是 {@code songs()} 的下标）
     */
    private boolean isLocked(int index) {
        return isLocked(index >= 0 && index < view.size() ? view.get(index) : null);
    }

    private boolean isLocked(SongEntry e) {
        if (e == null) return true;
        if (unlockAll) return false;                 // 播放器模式：全部可玩（但不写进存档）
        return !trulyUnlocked(e);
    }

    /** 存档里**真正**解锁过没有（只给「拷芯片」用；{@code unlockAll} 不影响它）。 */
    private boolean trulyUnlocked(SongEntry e) {
        if (e == null) return false;
        Profile p = profile();
        return p != null && p.isUnlocked(e.chartId());
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /** 上下移动选中项（在 view 内 clamp，不循环）。 */
    private void move(int delta) {
        if (view.isEmpty()) return;
        selected = Math.max(0, Math.min(view.size() - 1, selected + delta));
        selectedPin = view.get(selected);
    }

    /**
     * 开当前选中的曲目。<b>锁定的直接拒绝</b>（只给一行提示，不进游戏）——
     * 锁定状态的唯一判定点就是 {@link #isLocked(int)}，行点击 / 回车 / 开始按钮都走这里。
     *
     * <p>{@code selected} 是 <b>view 的下标</b>，而 {@link Session#start(int)} 要的是
     * {@code songs()} 的下标 —— 中间用 {@link Session#indexOf(SongEntry)} 换算。
     */
    private void startSelected() {
        Session s = PhiMCClient.session();
        if (s == null || client == null) return;
        SongEntry e = selectedSong();
        if (e == null) return;
        if (isLocked(e)) {
            notice = "未解锁：" + e.display + "（先去商店解锁）";
            return;
        }
        int index = s.indexOf(e);
        if (index < 0) {
            notice = "这首谱面已经不在目录里了（重新进一次界面即可刷新）";
            viewDirty = true;
            return;
        }
        if (s.start(index)) {
            // unlockAll 跟着走：播放器局退出后还回「全部可玩」的曲库（不影响判定/计分）
            client.setScreen(new PlayScreen(s, unlockAll));
        } else {
            // 带上原因摘要（JSON 语法错误 / 内存不足 / 没有音符 / 读包失败…），
            // 详细诊断在日志里（路径 + JSON 大小 + 异常 + JSON 开头 200 字符）
            String reason = s.lastFailReason();
            notice = "谱面解析失败：" + e.display + (reason.isEmpty() ? "" : "（" + reason + "）");
        }
    }

    /**
     * 点击某行的「拷芯片」：把这张<b>真解锁</b>谱面写进背包里的空芯片。
     *
     * <p>真正的逻辑在 {@link ChipItem#carveHeld}（含单/双端背包口径、找不到空芯片等分支），
     * 这里只负责把 {@link ChipItem.CarveResult} 翻成底部提示。不重建界面——背包变化
     * 由 {@code setStack} 自己同步，界面不需要重建（重建会把翻页/选中状态再走一遍 init）。
     */
    private void copyRow(int row) {
        Session s = PhiMCClient.session();
        if (s == null || client == null || client.player == null) {
            notice = ChipItem.CarveResult.NO_PROFILE.message();
            return;
        }
        int per = perPage();
        int index = currentPage(per) * per + row;
        if (index < 0 || index >= view.size()) return;      // 行槽位 → view 下标
        SongEntry e = view.get(index);
        ChipItem.CarveResult result = ChipItem.carveHeld(client.player, s.profile(), e.chartId());
        notice = result == ChipItem.CarveResult.OK
                ? result.message() + "：" + e.display
                : result.message();
    }

    // ------------------------------------------------------------------
    // 在线页：下载（唯一下载入口 + 完成处理）
    // ------------------------------------------------------------------

    /** 唯一下载入口：点行内「下载」/ 再点一次已选中的行 / 回车都走这里。 */
    private void downloadRow(int row) {
        List<PhiraChart> charts = phira.charts();
        if (row < 0 || row >= charts.size()) return;
        PhiraChart chart = charts.get(row);
        Path dir = songsDir();
        if (dir == null) {
            onlineNotice = "没有谱面目录（Session 还没初始化）";
            onlineNoticeColor = C_ERR;
            return;
        }
        if (PhiraDownloader.isDownloaded(chart.id)) {
            onlineNotice = "已下载过：" + chart.title() + "（同 id 不重复下载）";
            onlineNoticeColor = C_DIM;
            return;
        }
        PhiraDownloader.Job job = PhiraDownloader.download(chart, dir);
        if (job == null) {
            onlineNotice = "下载没能开始（谱面数据不完整）";
            onlineNoticeColor = C_ERR;
            return;
        }
        activeJobId = job.chartId;
        consumedJobId = -1;
        onlineNotice = "开始下载：" + chart.title() + "（" + chart.levelText() + "）";
        onlineNoticeColor = C_ACCENT;
    }

    /**
     * 每帧查一次正在跟的下载任务（<b>只读 volatile</b>），终态在渲染线程处理一次：
     * 成功 → {@code rescan()} + 切回本地页签 + 选中刚下载的那首（需求 2）。
     *
     * <p>这是「网络线程 → UI 线程」的唯一交界：后台线程只把 {@link PhiraDownloader.State}
     * 和字节数写进 Job，界面这边<b>轮询</b>它 —— 不用回调、不 {@code join}、不 {@code get()}，
     * 所以渲染线程永远不等网络。
     */
    private void pollDownload() {
        if (activeJobId < 0) return;
        PhiraDownloader.Job job = PhiraDownloader.job(activeJobId);
        if (job == null) return;
        PhiraDownloader.State state = job.state();
        if (state == PhiraDownloader.State.QUEUED || state == PhiraDownloader.State.RUNNING) {
            int pct = job.percent();
            onlineNotice = "下载中：" + job.chartName + " "
                    + (pct >= 0 ? pct + "%（" + bytesText(job.received()) + " / " + bytesText(job.total()) + "）"
                                : bytesText(job.received()));
            onlineNoticeColor = C_ACCENT;
            return;
        }
        if (consumedJobId == job.chartId) return;
        consumedJobId = job.chartId;
        if (state == PhiraDownloader.State.FAILED) {
            onlineNotice = "下载失败：" + job.message();
            onlineNoticeColor = C_ERR;
            activeJobId = -1;
            return;
        }
        if (state == PhiraDownloader.State.DONE) {
            finishDownload(job);
        }
    }

    /** 下载成功：rescan → 找到新谱面 → 切回本地页签并选中它（需求 2 的最后一步）。 */
    private void finishDownload(PhiraDownloader.Job job) {
        activeJobId = -1;
        Session s = PhiMCClient.session();
        PhiraDownloader.refresh(songsDir());
        if (s == null) return;
        // rescan 是一次目录扫描（只读每个包的 info.txt），和 init() 里那一次同价：
        // 只在「一次下载完成」时发生，不是每帧。
        s.rescan();
        List<SongEntry> songs = s.songs();
        int index = -1;
        if (job.file() != null) {
            Path want = job.file().toAbsolutePath();
            for (int i = 0; i < songs.size(); i++) {
                Path p = songs.get(i).path;
                if (p != null && p.toAbsolutePath().equals(want)) {
                    index = i;
                    break;
                }
            }
        }
        tab = Tab.LOCAL;
        viewDirty = true;                       // 列表变了 → 视图重建
        if (index >= 0) {
            // 选中新下载的那一首：view 的下标由 ensureView 按路径重新定位。
            // 注意「搜索 / 筛选」可能正好把它挡在外面（比如只搜了别的曲名）：
            // 这种情况给一句提示，别让人以为下载失败。
            selectedPin = songs.get(index);
            syncButtons();
            if (selectedSong() != songs.get(index)) {
                localQuery = "";
                lockFilter = LockFilter.ALL;
                diffFilter = "";
                if (localSearchField != null) localSearchField.setText("");
                if (lockBtn != null) lockBtn.setMessage(Text.literal(lockLabel()));
                if (diffBtn != null) diffBtn.setMessage(Text.literal(diffLabel()));
                viewDirty = true;
                syncButtons();
                notice = "已下载并导入：" + songs.get(index).display + "（清掉了搜索 / 筛选条件以便显示它）";
            } else {
                notice = "已下载并导入：" + songs.get(index).display + "（共 " + songs.size() + " 首）";
            }
            onlineNotice = notice;
            onlineNoticeColor = C_OK;
        } else {
            notice = "下载完成但没能识别成谱面：" + (job.file() == null ? "-" : job.file().getFileName());
            onlineNotice = notice;
            onlineNoticeColor = C_ERR;
        }
        syncButtons();
    }

    /** 谱面目录（{@code config/phimc/songs/}）；没有 Session 时 {@code null}。 */
    private Path songsDir() {
        Session s = PhiMCClient.session();
        return s == null ? null : s.songsDir();
    }

    /** 字节数 → {@code 3.2 MB}（服务端没给 Content-Length 时用）。 */
    private static String bytesText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // 1.21.9 起 GUI 输入回调换成 Click / KeyInput（方法签名全变）。这里加一层桥：
    // 先给控件机会，再交给老三参数方法，选中/判定的逻辑一行都不用动。
    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;   // 底部按钮 / 行内「拷芯片」优先
        return mouseClicked(click.x(), click.y(), click.button());
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (super.keyPressed(input)) return true;
        return keyPressed(input.key(), input.scancode(), input.modifiers());
    }
    //?}

    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先给控件（底部按钮 / 行内「拷芯片」）机会：否则点「拷芯片」会连带切换选中项
        //? if <1.21.9 {
        /*if (super.mouseClicked(mouseX, mouseY, button)) return true;
        *///?}
        if (button != 0) return false;
        // 在线页签：行点击只换选中，再点一次已选中的行 = 下载（和本地页签「再点一次 = 开始」同构）
        if (tab == Tab.ONLINE) return clickOnlineRow(mouseX, mouseY);
        int per = perPage();
        int page = view.isEmpty() ? 0 : currentPage(per);
        for (int i = 0; i < per; i++) {
            int index = page * per + i;
            if (index >= view.size()) break;
            int ry = rowY(i);
            if (mouseX >= PAD && mouseX <= width - PAD && mouseY >= ry && mouseY < ry + ROW_H) {
                // 点未选中的 = 只切换选中（曲绘 / 信息跟着换）；再点一次已选中的 = 开始（未解锁则只提示）
                if (index == selected) {
                    startSelected();
                } else {
                    selected = index;
                    selectedPin = view.get(index);
                }
                return true;
            }
        }
        return false;
    }

    /** 在线页的行点击：换选中；再点一次已选中的行 = 下载（行内「下载」按钮由控件层先吃掉）。 */
    private boolean clickOnlineRow(double mouseX, double mouseY) {
        List<PhiraChart> charts = phira.charts();
        int rows = Math.min(onlinePerPage(), charts.size());
        for (int i = 0; i < rows; i++) {
            int ry = onlineRowY(i);
            if (mouseX >= PAD && mouseX <= width - PAD && mouseY >= ry && mouseY < ry + ONLINE_ROW_H) {
                if (i == onlineSel) {
                    downloadRow(i);
                } else {
                    onlineSel = i;
                }
                return true;
            }
        }
        return false;
    }

    /** 在线页的滚轮 / ↑↓：只在一页内移动选中项（换页用底部「上一页 / 下一页」，一页正好一屏）。 */
    private void scrollOnline(int delta) {
        int n = phira.charts().size();
        if (n <= 0) {
            onlineSel = -1;
            return;
        }
        if (onlineSel < 0) onlineSel = 0;
        else onlineSel = Math.max(0, Math.min(n - 1, onlineSel + delta));
    }

    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0) {
            if (tab == Tab.ONLINE) scrollOnline(verticalAmount > 0 ? -1 : 1);
            else move(verticalAmount > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (verticalAmount != 0) {
            if (tab == Tab.ONLINE) scrollOnline(verticalAmount > 0 ? -1 : 1);
            else move(verticalAmount > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, verticalAmount);
    }
    *///?}

    //? if <1.21.9 {
    /*@Override
    *///?}
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean online = tab == Tab.ONLINE;
        // 本地搜索框有焦点时（正在打字）：
        //   * ↑↓ 不切歌（不然打拼音 / 英文时光标一动，选中项也跟着跑）；
        //   * 回车 = 收摊（取消焦点），不开始游戏 —— 「正在搜谱面却把歌开了」最容易踩。
        // 另外：< 1.21.9 的版本里这个方法是 Screen 的替代实现，字符键在 default 分支才转给控件，
        // 所以这里必须主动挡掉这三个键。
        boolean typingLocal = !online && localSearchField != null && localSearchField.isFocused();
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> {
                if (typingLocal) return true;
                if (online) scrollOnline(-1);
                else move(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (typingLocal) return true;
                if (online) scrollOnline(1);
                else move(1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (typingLocal) {
                    localSearchField.setFocused(false);   // 回车 = 搜完收工（过滤是实时的，不用重新搜）
                    return true;
                }
                if (online) {
                    // 搜索框有焦点时回车 = 搜索；否则回车 = 下载选中的那一行
                    if (searchField != null && searchField.isFocused()) doSearch(1);
                    else downloadRow(onlineSel);
                } else {
                    startSelected();
                }
                return true;
            }
            default -> {
                //? if >=1.21.9 {
                return false;   // 桥里已经先调过 super.keyPressed(KeyInput)
                //?} else {
                /*return super.keyPressed(keyCode, scanCode, modifiers);
                *///?}
            }
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /**
     * 曲目列表画在这里：{@link Screen#render} 的顺序是
     * {@code renderBackground → 子控件（底部按钮 / 拷芯片）}，所以行底色必须画在
     * {@code renderBackground} 里、且在 {@code super.renderBackground}（模糊 + 压暗）之后，
     * 否则要么被背景盖掉，要么把行内的「拷芯片」按钮盖掉。
     */
    @Override
    //? if >=1.20.2 {
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
    //?} else {
    /*public void renderBackground(DrawContext ctx) {
        super.renderBackground(ctx);
        final int mouseX = lastMouseX, mouseY = lastMouseY;   // 1.20.1 不带鼠标坐标：用上一帧记下的（hover 慢一帧）
    *///?}
        Session session = PhiMCClient.session();
        if (session == null) return;
        if (tab == Tab.ONLINE) {
            drawOnlineList(ctx, mouseX, mouseY);
        } else {
            // 本地列表画的是**视图**（搜索 / 筛选 / 排序之后），不是 songs() 原始顺序。
            // view 由 syncButtons() → ensureView() 在本帧 super.render 之前建好。
            drawList(ctx, session, view, mouseX, mouseY);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // hover 高亮多版本一致：1.20.1 的 renderBackground 不带坐标，所以自己记一份
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        // 缩略图贴图的本帧预算（最多注册 2 张，见 PhiraCovers）
        PhiraCovers.beginFrame();

        Session session = PhiMCClient.session();
        if (session == null) {
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }
        // 下载完成的收尾（rescan + 切回本地页签）只在渲染线程做，每帧只读一次任务状态
        pollDownload();
        List<SongEntry> songs = session.songs();
        boolean online = tab == Tab.ONLINE;
        // 窗口缩放后「每页行数」会变：按新行数重取当前页（走 60 秒缓存，不会重复打接口）
        if (online && !phira.loading() && phira.pageSize() != onlinePerPage()) {
            phira.reload(onlinePerPage());
        }
        // 控件可见性必须在绘制之前定好（列表行 + 拷芯片 / 下载按钮都依赖当前页）。
        // 这里同时 ensureView()：本地页的 view / selected 由它维护（脏了才重排）。
        syncButtons();
        // 1.20.2+ 的 Screen.render 会先调 renderBackground（模糊底），行列表在那里画，这里不用管。
        // **但 1.20.1 / 1.20 的 Screen.render 根本不调 renderBackground**（框架 1.20.2 起才调，
        // 见 Screen.render 字节码：1.20.1 只遍历 drawables），所以这两个版本必须在这里手动补一次，
        // 否则「行列表」整块不画 —— 用户实测的「1.20.1 歌曲列表空白」就是这个。
        //? if <1.20.2 {
        /*this.renderBackground(ctx);
        *///?}
        super.render(ctx, mouseX, mouseY, delta);

        // ---- 顶部：标题居中；副标题从页签右边开始（所以永远压不到页签） ----
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, C_TEXT);
        int subX = Math.min(tabsEndX() + 8, width - 2 * PAD);
        int subW = Math.max(20, width - PAD - subX);
        String sub;
        if (online) {
            sub = "Phira 在线谱面 · 共 " + phira.total() + " 首"
                    + (PhiraBrowser.onlyStable() ? "（只显示稳定且已审核）" : "（含未上架 / 未过审，灰色标注）")
                    + " · 第 " + phira.page() + "/" + phira.pages() + " 页 · 回车下载选中 · Esc "
                    + (backTo == null ? "返回" : "去商店");
        } else {
            int per = perPage();
            int page = view.isEmpty() ? 0 : currentPage(per);
            int pages = Math.max(1, (view.size() + per - 1) / per);
            // 有搜索 / 筛选时把「筛后/总数」都写出来，否则一个数字就够了
            boolean filtered = view.size() != songs.size();
            sub = (filtered ? "筛后 " + view.size() + "/" + songs.size() + " 首" : "共 " + songs.size() + " 首")
                    + " · 第 " + (page + 1) + "/" + pages
                    + " 页 · 未解锁的谱面不能开始 · ↑↓/滚轮/点击切换 · 回车开始 · Esc "
                    + (backTo == null ? "返回" : "去商店");
        }
        ctx.drawText(textRenderer, fit(sub, subW), subX, 20, C_DIM, true);

        if (online) {
            drawOnlineOverlay(ctx);
            return;
        }

        SongEntry sel = selectedSong();

        // ---- 详情区：左曲绘 + 右信息 ----
        int dTop = detailTop();
        int S = coverSize();
        ctx.fill(PAD - 4, dTop - 2, width - PAD + 4, detailBottom() + 6, 0x70000000);
        // 曲绘：只解码当前选中这一首（按需加载）
        CoverTextures.Cover cover = CoverTextures.get(sel);
        CoverTextures.draw(ctx, cover, PAD, dTop, S, S, 1.0f);

        int ix = PAD + S + 14;
        int iw = width - PAD - ix;
        if (sel != null) {
            drawInfo(ctx, sel, ix, iw, dTop, detailHeight(), session);
        } else {
            // 曲库真的空 vs 被搜索 / 筛选筛空：两句提示不一样，否则用户会以为谱面丢了
            String msg = songs.isEmpty()
                    ? "曲库是空的：把谱面包放进 config/phimc/songs/ 再进来"
                    : "没有匹配的谱面（搜索 / 筛选后 0 首）：清掉搜索词或把「锁定 / 难度」调回全部";
            ctx.drawText(textRenderer, fit(msg, iw), ix, dTop + 4, C_DIM, true);
        }

        // ---- 提示行（拷贝结果 / 未解锁 / 排序筛选切换） ----
        if (!notice.isEmpty()) {
            ctx.drawText(textRenderer, fit(notice, width - 2 * PAD), PAD, noticeY(), C_ACCENT, true);
        }
    }

    // ------------------------------------------------------------------
    // 在线页渲染
    // ------------------------------------------------------------------

    /**
     * 在线页底部的两行：状态（搜索中 / 失败 / 下载进度）+ <b>版权声明</b>（需求 4，永远显示）。
     */
    private void drawOnlineOverlay(DrawContext ctx) {
        String status;
        int color;
        if (phira.loading()) {
            status = "正在请求 Phira 谱面库…（网络在后台线程，界面不会卡）";
            color = C_ACCENT;
        } else if (!phira.error().isEmpty()) {
            status = "搜索失败：" + phira.error();
            color = C_ERR;
        } else if (!onlineNotice.isEmpty()) {
            status = onlineNotice;
            color = onlineNoticeColor;
        } else if (phira.charts().isEmpty()) {
            status = "没有找到稳定且已审核的谱面，换个曲名 / 谱师再试";
            color = C_DIM;
        } else {
            status = "共 " + phira.total() + " 首 · 点「下载」把谱面包存到 config/phimc/songs/";
            color = C_DIM;
        }
        ctx.drawText(textRenderer, fit(status, width - 2 * PAD), PAD, onlineStatusY(), color, true);
        String legal = "谱面来自 Phira 社区，版权归原作者所有（只下载到本地，不打包进 mod）";
        ctx.drawText(textRenderer, fit(legal, width - 2 * PAD), PAD, onlineLegalY(), C_LOCKED, true);
    }

    /**
     * 在线列表：每行 = 20px 曲绘缩略图 + 曲名（白）+ 难度 / 评分 / 谱师（灰）+ 右侧「下载」按钮槽位。
     *
     * <p>行底色画在 {@code renderBackground} 里（和本地列表同一条理由：不能盖住行内按钮）。
     * 缩略图走 {@link PhiraCovers}：没下好就是占位块，<b>不会在这里等网络 / 解码</b>。
     */
    private void drawOnlineList(DrawContext ctx, int mouseX, int mouseY) {
        List<PhiraChart> charts = phira.charts();
        if (charts.isEmpty()) {
            ctx.fill(PAD, onlineListTop(), width - PAD, onlineListTop() + ONLINE_ROW_H, C_ROW);
            String msg = phira.loading() ? "搜索中…"
                    : (!phira.error().isEmpty() ? "搜索失败，原因见下方"
                    : "没有找到稳定且已审核的谱面（换个曲名 / 谱师再试）");
            ctx.drawText(textRenderer, fit(msg, width - 2 * PAD - 12), PAD + 8,
                    onlineListTop() + (ONLINE_ROW_H - 9) / 2 + 1, C_DIM, true);
            return;
        }
        int rows = Math.min(onlinePerPage(), charts.size());
        int textRight = downloadX() - 8;          // 右侧槽位（下载按钮）左边界 - 8
        for (int i = 0; i < rows; i++) {
            PhiraChart c = charts.get(i);
            int ry = onlineRowY(i);
            boolean sel = i == onlineSel;
            boolean hover = mouseX >= PAD && mouseX <= width - PAD
                    && mouseY >= ry && mouseY < ry + ONLINE_ROW_H;
            ctx.fill(PAD, ry, width - PAD, ry + ONLINE_ROW_H, sel ? C_ROW_SEL : (hover ? C_ROW_HOVER : C_ROW));
            if (sel) ctx.fill(PAD, ry, PAD + 3, ry + ONLINE_ROW_H, C_ACCENT);

            int thumbX = PAD + 4;
            int thumbY = ry + (ONLINE_ROW_H - ONLINE_THUMB) / 2;
            PhiraCovers.draw(ctx, c.illustrationUrl(), thumbX, thumbY, ONLINE_THUMB);

            int textX = thumbX + ONLINE_THUMB + 6;
            int textY = ry + (ONLINE_ROW_H - 9) / 2 + 1;
            // 右侧状态（「下载中 42%」/「失败」）先量宽度，左边的曲名 / 难度要给它让位
            String status = rowStatus(c);
            int statusW = status.isEmpty() ? 0 : textRenderer.getWidth(status) + 8;
            if (statusW > 0) {
                ctx.drawText(textRenderer, status, textRight - (statusW - 8), textY, C_ACCENT, true);
            }
            int limit = textRight - statusW - textX - 4;
            String shown = fit(c.title(), limit);
            // 未上架 / 未过审的曲名先灰一档（需求 2：灰色标记区分）
            boolean published = c.isPublicStable();
            int titleColor = published ? (sel ? C_TEXT : 0xFFE4E4E4) : C_UNPUB;
            ctx.drawText(textRenderer, shown, textX, textY, titleColor, true);
            int metaX = textX + textRenderer.getWidth(shown) + 10;
            // 再在难度 / 评分 / 谱师后面挂一个灰色 [未上架]
            String tag = published ? "" : " [未上架]";
            int tagW = textRenderer.getWidth(tag);
            int metaW = textRight - statusW - metaX - 4 - tagW;
            if (metaW > 12) {
                String meta = "· " + c.levelText() + "  · 评分 " + c.ratingText() + "  · " + c.charterText();
                String shownMeta = fit(meta, metaW);
                ctx.drawText(textRenderer, shownMeta, metaX, textY, published ? C_DIM : C_UNPUB_META, true);
                if (!tag.isEmpty()) {
                    ctx.drawText(textRenderer, tag, metaX + textRenderer.getWidth(shownMeta), textY, C_LOCKED, true);
                }
            } else if (!tag.isEmpty()) {
                // 曲名太长 + 屏窄，元信息放不下：至少把标记画出来，别让它整个消失
                String tagFit = fit(tag, Math.max(0, textRight - statusW - metaX - 4));
                if (!tagFit.isEmpty()) {
                    ctx.drawText(textRenderer, tagFit, metaX, textY, C_LOCKED, true);
                }
            }
        }
    }

    /** 行右侧的下载状态：{} / 排队中 / 下载中 N% / 失败（「已下载」由按钮文字表达，不重复）。 */
    private String rowStatus(PhiraChart chart) {
        PhiraDownloader.Job job = PhiraDownloader.job(chart.id);
        if (job == null) return "";
        return switch (job.state()) {
            case QUEUED -> "排队中";
            case RUNNING -> job.percent() >= 0
                    ? "下载中 " + job.percent() + "%"
                    : "下载中 " + bytesText(job.received());
            case FAILED -> "失败";
            case DONE -> "";
        };
    }

    /**
     * 曲目列表：整行底色 + 序号/曲名 + 右侧（已解锁：最佳成绩；未解锁：灰色的「未解锁」）。
     *
     * @param songs <b>本地视图</b>（{@link #view}，已经过搜索 / 筛选 / 排序），
     *              不是 {@code session.songs()} —— 行号 / 页码 / 选中项都按这个下标算
     */
    private void drawList(DrawContext ctx, Session session, List<SongEntry> songs, int mouseX, int mouseY) {
        int per = perPage();
        int page = songs.isEmpty() ? 0 : currentPage(per);
        for (int i = 0; i < per; i++) {
            int index = page * per + i;
            if (index >= songs.size()) break;
            SongEntry e = songs.get(index);
            int ry = rowY(i);
            boolean isSel = index == selected;
            boolean locked = isLocked(index);
            boolean hover = mouseX >= PAD && mouseX <= width - PAD && mouseY >= ry && mouseY < ry + ROW_H;
            ctx.fill(PAD, ry, width - PAD, ry + ROW_H,
                    isSel ? C_ROW_SEL : (hover ? C_ROW_HOVER : C_ROW));
            if (isSel) ctx.fill(PAD, ry, PAD + 3, ry + ROW_H, locked ? C_LOCKED : C_ACCENT);   // 选中竖条
            int textY = ry + (ROW_H - 9) / 2 + 1;

            // 右侧槽位：已解锁放「拷芯片」按钮（由 syncButtons 控制），未解锁就在这里写「未解锁」
            int slotRight = width - PAD - 4;              // 槽位右边界（= copyX() + COPY_W）
            int textRight = copyX() - 8;                  // 成绩文字右边界
            if (locked) {
                String lock = "未解锁";
                ctx.drawText(textRenderer, lock, slotRight - textRenderer.getWidth(lock), textY, C_LOCKED, true);
            } else {
                // 最佳成绩（Phira 选曲界面同样的三件套：段位 / 7 位分数 / 百分比）
                Records.Rec rec = session.best(e);
                String best = rec == null ? "" : rec.rank() + " " + rec.scoreText() + " " + rec.accText();
                if (!best.isEmpty()) {
                    int bestW = textRenderer.getWidth(best);
                    ctx.drawText(textRenderer, best, textRight - bestW, textY,
                            rec.fullCombo() ? C_ACCENT : C_DIM, true);
                }
            }
            String label = (index + 1) + ". " + e.display;
            int maxLabel = textRight - (PAD + 8) - 4;
            int color = locked ? C_LOCKED : (isSel ? C_TEXT : 0xFFD0D0D0);
            ctx.drawText(textRenderer, fit(label, maxLabel), PAD + 8, textY, color, true);
        }
    }

    /**
     * 右侧信息列（{@link #INFO_ROWS} 行，行距 {@link #INFO_STEP}）：
     * 难度 / 物量 / BPM / <b>曲师 / 谱师 / 画师</b> / 状态 / 最佳。
     *
     * <p>曲师 / 谱师 / 画师缺失时写「未知」（不是空、也不是「—」——用户要求这么显示）。
     * 状态行：{@code 已解锁} / {@code 已解锁（播放器）}（{@link #unlockAll} 且存档没解锁）/
     * {@code 未解锁}；锁定时曲名也跟着变灰。
     */
    private void drawInfo(DrawContext ctx, SongEntry e, int x, int w, int top, int bandH, Session session) {
        RenderCompat.Transform ms = RenderCompat.transform(ctx);
        boolean unlocked = !isLocked(e);

        // 曲名：放大 1.5 倍（宽度按缩放后算，超出就截断加省略号）
        float scale = 1.5f;
        ms.push();
        ms.translate((float) x, (float) top);
        ms.scale(scale, scale);
        ctx.drawText(textRenderer, fit(e.title(), (int) (w / scale)), 0, 0,
                unlocked ? C_TEXT : C_LOCKED, true);
        ms.pop();

        SongMeta.Meta meta = SongMeta.get(e);
        Records.Rec rec = session.best(e);
        String notes;
        String bpm;
        if (meta != null) {
            notes = Integer.toString(meta.notes());
            bpm = SongMeta.bpmText(meta.bpm());
        } else {
            notes = bpm = SongMeta.isLoading(e) ? "…" : "-";
        }
        String best;
        if (rec == null) {
            best = "未游玩";
        } else {
            best = rec.rank() + "  " + rec.scoreText() + "  " + rec.accText()
                    + (rec.fullCombo() ? "  FC" : "");
        }

        // 曲师 / 谱师 / 画师 / 难度：**用 SongEntry 的最终值**（info.txt 优先，缺了由 SongMeta
        // 后台读的谱面 META 兜底）。RPE（.pez）的 info.txt 没有 Illustrator 字段，画师名只在
        // META.illustration 里，所以这里绝不能再直接读 e.illustrator 字段（那样永远是「未知」）。
        String levelRaw = e.levelText();
        String level = levelRaw == null || levelRaw.isBlank() || levelRaw.equals("-") ? "—" : levelRaw;
        String composer = e.composer().isBlank() ? "未知" : e.composer();
        String charter = e.charter().isBlank() ? "未知" : e.charter();
        String illustrator = e.illustrator().isBlank() ? "未知" : e.illustrator();

        // 状态：真解锁 →「已解锁」；播放器模式且存档没解锁 →「已解锁（播放器）」；否则「未解锁」
        String state = unlocked
                ? (trulyUnlocked(e) ? "已解锁" : "已解锁（播放器）")
                : "未解锁";

        String[][] rows = {
                {"难度", level},
                {"物量", notes},
                {"BPM", bpm},
                {"曲师", composer},
                {"谱师", charter},
                {"画师", illustrator},
                {"状态", state},
                {"最佳", best},
        };
        int y = top + INFO_FIRST_DY;
        for (String[] row : rows) {
            if (y + INFO_TEXT_H > top + bandH) break;         // 详情区放不下就停（窄屏兜底）
            ctx.drawText(textRenderer, fit(row[0], LABEL_W), x, y, C_DIM, true);
            String label = row[0];
            int color;
            if ("状态".equals(label)) {
                color = unlocked ? C_OK : C_LOCKED;
            } else if ("最佳".equals(label)) {
                color = rec != null && rec.fullCombo() ? C_GOLD : C_ACCENT;
            } else if ("难度".equals(label)) {
                color = C_ACCENT;
            } else if ("曲师".equals(label) || "谱师".equals(label) || "画师".equals(label)) {
                color = "未知".equals(row[1]) ? C_LOCKED : C_TEXT;
            } else {
                color = C_TEXT;
            }
            ctx.drawText(textRenderer, fit(row[1], w - LABEL_W), x + LABEL_W, y, color, true);
            y += INFO_STEP;
        }
    }

    /** 按像素宽度截断（超出加省略号）——MC 字体宽度不是等宽的，只能逐字量。 */
    private String fit(String s, int maxPx) {
        if (s == null) return "";
        if (maxPx <= 0) return "";
        if (textRenderer.getWidth(s) <= maxPx) return s;
        String ell = "…";
        int budget = maxPx - textRenderer.getWidth(ell);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (textRenderer.getWidth(sb.toString() + s.charAt(i)) > budget) break;
            sb.append(s.charAt(i));
        }
        return sb.append(ell).toString();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** 关闭：从平板进来就回平板（商店页），否则回游戏。 */
    @Override
    public void close() {
        if (client == null) return;
        client.setScreen(backTo == null ? null : backTo.get());
    }
}
