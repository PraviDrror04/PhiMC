package dev.dsh.phimc.survival;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 商店货架（设计文档 §3.2 / §6）：每天（{@link #REFRESH_TICKS} tick）刷
 * {@link #SLOTS} 个**未解锁**谱面。
 *
 * <p>货架完全由 {@code shopSeed} 决定：先对「全部谱面 id」按字典序排序，
 * 再用 {@code new Random(shopSeed)} 做一次 Fisher–Yates 洗牌 —— 这给出一个
 * **稳定的优先队列**，然后按顺序取前 {@code slots} 个仍未解锁的。
 * 这样买走一个之后，后面的候选会原位补上，其余商品的位置不会乱跳，
 * 也不会因为「已解锁集合变了」而换一批货。
 *
 * <p>不 import 任何 Minecraft 类，可脱离游戏单测。
 */
public final class Shop {

    /** 每天刷几个。 */
    public static final int SLOTS = 6;

    /** 一天 = 24000 tick（MC 原版日长）。 */
    public static final long REFRESH_TICKS = 24000L;

    private Shop() {
    }

    /**
     * 洗牌出整条优先队列（全部谱面 id 的稳定乱序）。
     *
     * @param chartIds 世界里实际存在的全部谱面 id（顺序不重要，内部会排序）
     */
    public static List<String> order(long shopSeed, Collection<String> chartIds) {
        List<String> list = new ArrayList<>();
        if (chartIds != null) {
            for (String id : chartIds) {
                if (id != null && !id.isEmpty() && !list.contains(id)) list.add(id);
            }
        }
        list.sort(Comparator.naturalOrder());
        Random r = new Random(shopSeed);
        for (int i = list.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            String tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
        return list;
    }

    /**
     * 当前货架：{@link #order} 之后跳过已解锁的，取前 {@code slots} 个。
     *
     * @param unlocked 已解锁谱面 id（{@code null} 视为空）
     * @param slots    货架格数，传 {@link #SLOTS}
     */
    public static List<String> roll(long shopSeed, Collection<String> chartIds, Set<String> unlocked, int slots) {
        List<String> out = new ArrayList<>();
        int max = Math.max(0, slots);
        for (String id : order(shopSeed, chartIds)) {
            if (unlocked != null && unlocked.contains(id)) continue;
            out.add(id);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 货架 id 列表是否还完整地等于「重新 roll 一遍」的结果（调试/自测用）。 */
    public static boolean isConsistent(long shopSeed, Collection<String> chartIds, Set<String> unlocked,
                                       List<String> shelf) {
        return roll(shopSeed, chartIds, unlocked, SLOTS).equals(shelf);
    }
}
