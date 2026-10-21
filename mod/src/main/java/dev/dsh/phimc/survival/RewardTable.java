package dev.dsh.phimc.survival;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 结算物资奖励（S5）：**按评级决定「抽几次」，每次从 {@link PriceTable} 的物品池里随机抽**。
 * 不参战、不改分、不 import 任何 Minecraft 类，可脱离游戏编译/单测。
 *
 * <h2>为什么要随机</h2>
 * 商店解锁谱面要的物品就是 {@link PriceTable#POOLS} 那三档。奖励如果是死表（永远铁锭 / 金锭），
 * 玩家想解锁某首需要「经验瓶 / 蜂蜜瓶」的谱面时就永远凑不齐，只能硬挖。所以奖励改成
 * **抽奖**，而且物品源**直接复用 {@code PriceTable.POOLS}**（不另抄一份），
 * 保证「奖励物品 ⊆ 解锁谱面能用的物品集合」这条性质在代码层面成立。
 *
 * <h2>规则表（本次改动后的完整数值）</h2>
 * <table border="1">
 *   <tr><th>评级</th><th>抽几次</th><th>说明</th></tr>
 *   <tr><td>F</td><td>0</td><td>不发任何东西（保持既有约定）</td></tr>
 *   <tr><td>C</td><td>1</td><td></td></tr>
 *   <tr><td>B</td><td>2</td><td></td></tr>
 *   <tr><td>A</td><td>2</td><td></td></tr>
 *   <tr><td>S</td><td>3</td><td></td></tr>
 *   <tr><td>V</td><td>4</td><td></td></tr>
 *   <tr><td>PHI</td><td>5</td><td></td></tr>
 * </table>
 *
 * <p><b>每一抽</b>：先按档位权重选档，再在该档物品池里均匀选一种，最后在该档数量区间里均匀取数量
 * （区间就是 {@link PriceTable#COUNT_RANGES}：EZ/HD 3–12、IN 2–8、AT 1–4）。
 * 各档权重 {@code {8, 4, 2}}（合计 14）：
 * <ul>
 *   <li>EZ/HD 池（铁/铜/红石/煤/玻璃板/面包）：**8/14 ≈ 57.1%** —— 低档最常见，保底能刷出解锁材料；</li>
 *   <li>IN 池（金/青金石/紫水晶/黏液球/蜂蜜瓶）：**4/14 ≈ 28.6%**；</li>
 *   <li>AT 池（钻石/石英/荧石粉/末影珍珠/经验瓶）：**2/14 ≈ 14.3%** —— 稀有，但不是拿不到。</li>
 * </ul>
 *
 * <p><b>同一抽里允许重复</b>：两次抽到铁锭就合成一条 {@code 铁锭×N}（见 {@link #merged}），
 * 发物品时也只塞一叠，不会把背包拆成一堆小堆。
 *
 * <h2>随机源</h2>
 * 用 {@link Random}（不是 {@code Math.random()}）：{@link #roll(String, Random)} 收一个
 * {@code Random}，测试传 seeded 的就完全可复现；游戏内由 {@link Rewards#grantOnFinish(String, int)}
 * 每次结算传一个**新** {@code Random}（= 每局都不同、不可预测）。刻意不做「同存档 / 同分数可复现」：
 * 那只会让玩家拿同一分数反复重打来刷同一批物品，随机反而更公平。
 *
 * <p>评级阈值一行没动：{@link #forScore(int)} 仍然走 {@code core.Judgement.rank(int)}。
 */
public final class RewardTable {

    /**
     * 一件奖励：物品注册名 + 数量。
     *
     * <p>字段与 {@link PriceTable.Price} 同形（都是 {@code 注册名 + 数量}），
     * 但刻意独立成类型：价格是「商店要你付什么」，奖励是「打完给你什么」，
     * 两者以后可能会朝不同方向演化（比如奖励加附魔），不要绑死。
     */
    public record Reward(String itemId, int count) {
        @Override
        public String toString() {
            return itemId + " x" + count;
        }
    }

    /** 评级表，顺序与 {@link #DRAWS} 一一对应（也是 {@code Judgement.rank} 的全部输出）。 */
    public static final String[] RANKS = {"F", "C", "B", "A", "S", "V", "PHI"};

    /** 每个评级抽几次，下标 = {@link #RANKS} 下标。F 恒 0。 */
    public static final int[] DRAWS = {0, 1, 2, 2, 3, 4, 5};

    /**
     * 三档池的抽样权重，下标 = {@link PriceTable.Tier#ordinal()}（EASY_HARD / IN / AT）。
     * 低档高、高档低：8 : 4 : 2（合计 14）。
     */
    public static final int[] TIER_WEIGHTS = {8, 4, 2};

    /** 权重合计（{@link #TIER_WEIGHTS} 的缓存和，抽样时用）。 */
    public static final int TIER_WEIGHT_TOTAL = 8 + 4 + 2;

    private RewardTable() {
    }

    // ------------------------------------------------------------------
    // 规则表
    // ------------------------------------------------------------------

    /** 评级 → 抽几次（{@code null} / 未知评级 / F 都是 0）。 */
    public static int draws(String rank) {
        int i = rankIndex(rank);
        return i < 0 ? 0 : DRAWS[i];
    }

    /** 评级在 {@link #RANKS} 里的下标（大小写不敏感），认不出返回 -1。 */
    public static int rankIndex(String rank) {
        String r = rank == null ? "" : rank.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < RANKS.length; i++) {
            if (RANKS[i].equals(r)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 抽样
    // ------------------------------------------------------------------

    /** 按 {@link #TIER_WEIGHTS} 加权选一个档位。 */
    public static PriceTable.Tier randomTier(Random rnd) {
        int roll = rnd.nextInt(TIER_WEIGHT_TOTAL);
        int acc = 0;
        PriceTable.Tier[] tiers = PriceTable.Tier.values();
        for (int i = 0; i < TIER_WEIGHTS.length && i < tiers.length; i++) {
            acc += TIER_WEIGHTS[i];
            if (roll < acc) return tiers[i];
        }
        return PriceTable.Tier.EASY_HARD;      // 权重表与 tiers 长度不一致时的兜底
    }

    /**
     * 抽一件：档位（加权）→ 该档物品池（均匀）→ 该档数量区间（均匀）。
     *
     * <p>物品池与数量区间**直接读 {@link PriceTable#POOLS} / {@link PriceTable#COUNT_RANGES}**，
     * 不复制常量 —— 商店池子改了，奖励自动跟着改。
     */
    public static Reward drawOne(Random rnd) {
        PriceTable.Tier tier = randomTier(rnd);
        String[] pool = PriceTable.POOLS[tier.ordinal()];
        int[] range = PriceTable.COUNT_RANGES[tier.ordinal()];
        String item = pool[rnd.nextInt(pool.length)];
        int count = range[0] + rnd.nextInt(range[1] - range[0] + 1);
        return new Reward(item, count);
    }

    /**
     * 按评级抽 {@link #draws} 次（可能重复物品，未合并）。
     *
     * <p>非 F 级保证**至少一件**：{@code draws >= 1} 且 {@link PriceTable#POOLS} 非空，
     * 万一（常量被改坏）抽空，这里兜底给一个低档物品，绝不让「非 F 级空手」。
     */
    public static List<Reward> roll(String rank, Random rnd) {
        int n = draws(rank);
        if (n <= 0) return List.of();
        List<Reward> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(drawOne(rnd));
        if (out.isEmpty()) out.add(new Reward(PriceTable.POOLS[0][0], PriceTable.COUNT_RANGES[0][0]));
        return out;
    }

    /** 同 {@link #roll(String, Random)}，但自己开一个未播种的 {@link Random}（游戏内/临时调用方便）。 */
    public static List<Reward> of(String rank) {
        return roll(rank, new Random());
    }

    /** 分数 → 奖励（评级走 {@code Judgement.rank(score)}，计分/评级公式一行没改）。 */
    public static List<Reward> rollForScore(int score, Random rnd) {
        return roll(dev.dsh.phimc.core.Judgement.rank(score), rnd);
    }

    /** 同 {@link #rollForScore(int, Random)}，自己开一个未播种的 {@link Random}。 */
    public static List<Reward> forScore(int score) {
        return rollForScore(score, new Random());
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    /**
     * 同名物品合并成一条（保持首次出现顺序），发物品和写摘要都用它。
     *
     * <p>例如 {@code [铁锭×3, 金锭×1, 铁锭×5]} → {@code [铁锭×8, 金锭×1]}。
     */
    public static List<Reward> merged(List<Reward> rewards) {
        if (rewards == null || rewards.isEmpty()) return List.of();
        Map<String, Integer> sum = new LinkedHashMap<>();
        for (Reward r : rewards) {
            if (r == null || r.itemId() == null) continue;
            sum.merge(r.itemId(), r.count(), Integer::sum);
        }
        List<Reward> out = new ArrayList<>(sum.size());
        for (Map.Entry<String, Integer> e : sum.entrySet()) {
            out.add(new Reward(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * 奖励短文本（{@code 金锭×2 + 铁锭×5}），结算界面那一行和动作条提示共用；
     * **同名物品聚合成一条**，空表返回 {@code ""}。
     */
    public static String summary(List<Reward> rewards) {
        List<Reward> merged = merged(rewards);
        if (merged.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Reward r : merged) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(shortName(r.itemId())).append('×').append(r.count());
        }
        return sb.toString();
    }

    /** 注册名 → 中文短名（只用于提示文本；认不出的就原样显示注册名）。 */
    public static String shortName(String itemId) {
        if (itemId == null) return "?";
        return switch (itemId) {
            // 低档池（EZ/HD）
            case "minecraft:iron_ingot" -> "铁锭";
            case "minecraft:copper_ingot" -> "铜锭";
            case "minecraft:redstone" -> "红石粉";
            case "minecraft:coal" -> "煤炭";
            case "minecraft:glass_pane" -> "玻璃板";
            case "minecraft:bread" -> "面包";
            // 中档池（IN）
            case "minecraft:gold_ingot" -> "金锭";
            case "minecraft:lapis_lazuli" -> "青金石";
            case "minecraft:amethyst_shard" -> "紫水晶碎片";
            case "minecraft:slime_ball" -> "黏液球";
            case "minecraft:honey_bottle" -> "蜂蜜瓶";
            // 高档池（AT）
            case "minecraft:diamond" -> "钻石";
            case "minecraft:quartz" -> "下界石英";
            case "minecraft:glowstone_dust" -> "荧石粉";
            case "minecraft:ender_pearl" -> "末影珍珠";
            case "minecraft:experience_bottle" -> "附魔之瓶";
            default -> itemId;
        };
    }
}
