package dev.dsh.phimc.survival;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「新平板赠送 2 首」：玩家**第一次**打开平板时，把「商店里最便宜的 2 首未解锁谱面」直接
 * {@link Profile#unlock} 送掉。
 *
 * <h2>为什么是「最便宜」= (档位, 数量, chartId) 排序</h2>
 * 商店的定价（{@link PriceTable}）是「按档位抽物品、档位内随机数量」，不同物品之间没有
 * 统一的货币，严格说「价格最低」不可比。这里给一个**稳定、可复现、可单测**的定义：
 * <ol>
 *   <li>先比档位：EZ/HD 池（铁/铜/红石/煤/玻璃板/面包）&lt; IN 池（金/青金石/紫水晶/黏液球/蜂蜜瓶）
 *       &lt; AT 池（钻石/石英/荧石/末影珍珠/经验瓶）；</li>
 *   <li>再比数量少；</li>
 *   <li>最后比 chartId 字典序（保证同分时结果固定）。</li>
 * </ol>
 * 排序键只依赖 {@code chartId + worldSeed}（价格本来就是纯函数），所以**同一个存档里
 * 无论什么时候送、重启几次，送的永远是这两首**；老字段缺失时也不会「每次开界面都换一首」。
 *
 * <p>候选集是全世界的谱面而不是当天货架的 6 个：货架每天刷新，用它当候选会让「送哪两首」
 * 取决于第一次打开平板的时间，而且当天可能不满 2 首未解锁。谱面全集 ⊇ 货架候选，
 * 所以送出去的必然也是「商店里买得到的那一类」。
 *
 * <p>本类不 import 任何 Minecraft 类，可脱离游戏编译/单测（见压测脚本）。
 */
public final class TabletGift {

    /** 送几首。 */
    public static final int COUNT = 2;

    private TabletGift() {
    }

    /**
     * 候选谱面按「便宜 → 贵」排序（只保留未解锁的，保持唯一）。
     *
     * @param chartIds  世界里全部谱面 id（顺序无关）
     * @param unlocked  已解锁集合，{@code null} 视为空
     * @param worldSeed 价格种子（{@code Profile.worldSeed()}，0 也照样能排）
     */
    public static List<String> cheapest(Collection<String> chartIds, Set<String> unlocked, long worldSeed) {
        List<String> ids = new ArrayList<>();
        if (chartIds != null) {
            for (String id : chartIds) {
                if (id == null || id.isEmpty()) continue;
                if (unlocked != null && unlocked.contains(id)) continue;
                if (!ids.contains(id)) ids.add(id);
            }
        }
        ids.sort(order(worldSeed));
        return ids;
    }

    /** 排序器：档位 → 数量 → chartId（见类注释）。 */
    public static Comparator<String> order(long worldSeed) {
        return Comparator
                .comparingInt((String id) -> PriceTable.tierOfChartId(id, worldSeed).ordinal())
                .thenComparingInt(id -> PriceTable.of(id, worldSeed).count())
                .thenComparing(Comparator.naturalOrder());
    }

    /** 取最便宜的 {@code count} 首未解锁谱面（默认 {@link #COUNT}）。 */
    public static List<String> pick(Collection<String> chartIds, Set<String> unlocked, long worldSeed, int count) {
        List<String> cheapest = cheapest(chartIds, unlocked, worldSeed);
        int n = Math.min(Math.max(0, count), cheapest.size());
        return new ArrayList<>(cheapest.subList(0, n));
    }

    /**
     * 第一次打开平板时调用（幂等）。
     *
     * <p>顺序刻意是「先 unlock、后落标志」：万一在两步之间崩溃，最坏是白送一次，
     * 而不是标志已置真、谱面却没送到（那玩家就永久拿不到赠品了）。
     * {@code unlock} 本身幂等，重复调用不会重复写盘。
     *
     * @return 这次实际要送（并已解锁）的谱面 id；已经送过 / 没有候选时返回空表
     */
    public static List<String> giveIfNeeded(Profile profile, Collection<String> chartIds) {
        if (profile == null) return List.of();
        if (profile.tabletGiftGiven()) return List.of();
        long worldSeed = profile.worldSeed();
        List<String> picks = pick(chartIds, profile.unlocked(), worldSeed, COUNT);
        if (picks.isEmpty()) return List.of();       // 一个候选都没有：先不置标志，等有谱面了再送
        for (String id : picks) profile.unlock(id);
        profile.claimTabletGift();
        return picks;
    }

    /** 赠送提示的文本（{@code 新平板赠送：A#IN、B#HD}）。 */
    public static String describe(Collection<String> picks) {
        if (picks == null || picks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : new LinkedHashSet<>(picks)) {
            if (sb.length() > 0) sb.append('、');
            sb.append(PriceTable.songOf(id)).append(" · ").append(PriceTable.difficultyOf(id));
        }
        return sb.toString();
    }
}
