package dev.dsh.phimc.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线页的<b>搜索状态机 + 跨线程快照</b>（一个选曲界面一个实例）。
 *
 * <h2>线程模型（需求 3「主线程禁止阻塞」的落点）</h2>
 * <ul>
 *   <li><b>后台线程</b>（{@link PhiraApi#runAsync}，2 条 daemon；原来只有 1 条，见
 *       {@link PhiraApi} 类注释）：真正发 HTTP 的
 *       {@link PhiraApi#search}，以及把响应解析成 {@link PhiraChart} 列表。</li>
 *   <li><b>渲染线程</b>：只调 {@link #search} 起任务，然后每帧读
 *       {@link #charts()} / {@link #total()} / {@link #loading()} / {@link #error()} 这些
 *       {@code volatile} 快照来画。这里<b>没有</b> {@code join} / {@code get()} / {@code synchronized}
 *       等待，渲染线程永远不会被网络拖住。</li>
 *   <li><b>过期结果丢弃</b>：每次请求自增 {@link #serial}，后台回来时如果序号已经变了
 *       （用户又改了搜索词 / 翻了页 / 切了「仅上架」），结果<b>直接丢掉</b>，不会出现「后发先至」
 *       把界面刷成上一页。</li>
 *   <li><b>loading 一定收得回来</b>：后台回调里先拿结果（{@code PhiraApi.search} 保证不抛），
 *       再写状态；任务没能入队时同步把 {@code loading} 放掉。任何一条路都不会让界面停在
 *       「搜索中…」——这是「一直 loading」那类卡死的第二道闸。</li>
 * </ul>
 *
 * <h2>「仅上架」开关（{@link #onlyStable()}）</h2>
 * 默认 {@code true}：只显示 {@code stable && reviewed}。关掉后请求里不再带
 * {@code stable=true&reviewed=true}，未上架 / 未过审的谱面也会返回；<b>过滤在这一层做</b>
 * （开的时候还会再筛一遍，服务端忽略参数也拦得住），{@code PhiraApi} 只负责请求与解析。
 *
 * <p>这个开关是 <b>static + volatile</b> 的：一次游戏进程内有效（关掉界面再进来仍然记得），
 * 重启游戏回到默认「开」；<b>不写 {@code profile.json}</b>（那是生存玩法存档，不该混一个浏览偏好）。
 *
 * <p>缓存（60 秒，同一搜索词不重复请求）在 {@link PhiraApi} 里，本类只管「当前这一页是谁」。
 */
public final class PhiraBrowser {

    /** 排序：评分（Phira 的 {@code -rating}，高→低）。 */
    public static final String ORDER_RATING = "-rating";
    /** 排序：最新（{@code -updated}）。 */
    public static final String ORDER_NEW = "-updated";

    /**
     * 「仅上架」开关（默认开）。{@code true} = 只要 {@code stable && reviewed}；
     * {@code false} = 全部谱面（未上架 / 未过审的在界面上灰色 + {@code [未上架]}）。
     *
     * <p>static：本局（游戏进程）有效，不落盘。
     */
    private static volatile boolean onlyStable = true;

    /** 当前是否「仅上架」。渲染线程随时读。 */
    public static boolean onlyStable() {
        return onlyStable;
    }

    /** 改「仅上架」。<b>不会自动重搜</b>，调用方改完要自己调一次 {@link #search}（界面是这么做的）。 */
    public static void setOnlyStable(boolean value) {
        onlyStable = value;
    }

    private volatile List<PhiraChart> charts = List.of();
    private volatile int total;
    private volatile boolean loading;
    private volatile String error = "";
    private volatile String query = "";
    private volatile String order = ORDER_RATING;
    private volatile int page = 1;
    private volatile int pageSize = 20;
    /** 请求序号：晚到的旧结果靠它丢弃。 */
    private volatile long serial;

    /** 当前这一页（「仅上架」开时已经筛掉未上架 / 未过审）。 */
    public List<PhiraChart> charts() {
        return charts;
    }

    /** 服务端报的总条数（跟着「仅上架」走：开 = 只数稳定谱面，关 = 全部）。 */
    public int total() {
        return total;
    }

    /** 是否有一趟请求在路上（界面显示「搜索中…」，翻页按钮变灰）。 */
    public boolean loading() {
        return loading;
    }

    /** 上一次请求失败的中文原因；成功时是空串。 */
    public String error() {
        return error;
    }

    public String query() {
        return query;
    }

    public String order() {
        return order;
    }

    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }

    /** 总页数（至少 1，和本地页签的口径一致）。 */
    public int pages() {
        int size = Math.max(1, pageSize);
        int count = total;
        return Math.max(1, (count + size - 1) / size);
    }

    /**
     * 发起一次搜索（<b>立即返回</b>，网络在后台线程）。渲染线程可以放心调。
     *
     * @param query    搜索词（曲名 / 谱师，空串 = 不筛）
     * @param order    {@link #ORDER_RATING} / {@link #ORDER_NEW}
     * @param page     1 起的页码（会被夹到合法范围）
     * @param pageSize 每页条数（界面按能放下的行数给）
     */
    public void search(String query, String order, int page, int pageSize) {
        String q = query == null ? "" : query.trim();
        String ord = ORDER_NEW.equals(order) ? ORDER_NEW : ORDER_RATING;
        int size = Math.max(1, Math.min(30, pageSize));
        int p = Math.max(1, page);
        this.query = q;
        this.order = ord;
        this.pageSize = size;
        this.page = p;
        this.loading = true;
        this.error = "";
        final long mySerial = ++serial;
        final boolean stableOnly = onlyStable;
        boolean queued = PhiraApi.runAsync(() -> {
            PhiraApi.ListResult result;
            try {
                result = PhiraApi.search(q, ord, p, size, stableOnly);
            } catch (Throwable t) {
                // PhiraApi 自己已经把异常翻成 ListResult 了，这一层只防「它连 ListResult 都没返回」
                // ——收不到结果就跳过下面的 loading = false，界面会永远停在「搜索中…」。
                result = new PhiraApi.ListResult(false, List.of(), 0, "搜索失败：" + t.getClass().getSimpleName());
            }
            if (mySerial != serial) return;               // 已经有更新的请求，这一份过期了
            try {
                if (result.ok()) {
                    charts = stableOnly ? keepStable(result.charts()) : result.charts();
                    total = result.count();
                    error = "";
                } else {
                    charts = List.of();
                    total = 0;
                    error = result.error();
                }
            } catch (Throwable t) {
                // 写状态本身出意外（数据坏到 keepStable 都炸）：也要落到 FAILED，不能停在「搜索中…」
                charts = List.of();
                total = 0;
                error = "搜索失败：" + t.getClass().getSimpleName();
            } finally {
                loading = false;                          // ← 无论走哪条路，loading 一定收回来
            }
        });
        if (!queued) {
            // 线程池已关闭 / 拒绝：不把 loading 放掉的话，界面这一帧之后永远显示「搜索中…」
            error = "搜索请求没能提交（后台线程池已关闭），请重启游戏后再试";
            loading = false;
        }
    }

    /**
     * 「仅上架」时再筛一遍（服务端参数只是省流量，这一道才是准的）。
     *
     * <p>服务端正常时不会筛掉任何一条（请求里已经带了 {@code stable=true&reviewed=true}），
     * 所以不会出现「一页只剩几行」；服务端忽略参数时至少不会把未上架的漏出来。
     */
    private static List<PhiraChart> keepStable(List<PhiraChart> in) {
        if (in.isEmpty()) return in;
        List<PhiraChart> out = new ArrayList<>(in.size());
        for (PhiraChart c : in) {
            if (c != null && c.isPublicStable()) out.add(c);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /** 用当前条件重新搜一次（失败后点「搜索」/ 回到在线页 / 切换「仅上架」时用）。 */
    public void reload(int pageSize) {
        search(query, order, page, pageSize);
    }

    /** 切到别的页（页码会被夹到 1..总页数；结果还没回来时按当前 total 估算，够了）。 */
    public void gotoPage(int target, int pageSize) {
        int size = Math.max(1, Math.min(30, pageSize));
        int pages = Math.max(1, (total + size - 1) / size);
        int p = Math.max(1, Math.min(pages, target));
        search(query, order, p, size);
    }
}
