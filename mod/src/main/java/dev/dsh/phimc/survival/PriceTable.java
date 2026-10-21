package dev.dsh.phimc.survival;

/**
 * 谱面定价表（设计文档 {@code docs/DESIGN-survival.md} §4）。
 *
 * <p>价格是**纯函数**：{@code of(chartId, worldSeed)} 只依赖这两个参数，
 * 同一世界里同一谱面的价格永远固定，**不写进存档**（改存档也改不了价）。
 * 因此本类刻意不 import 任何 Minecraft 类 —— 它可以脱离游戏单独编译/单测
 * （见 {@code tools/survival/SurvivalSelfTest.java}）。物品用注册名（如
 * {@code minecraft:iron_ingot}）表示，渲染层再用 {@code Registries.ITEM} 解析成
 * {@code ItemStack}。
 *
 * <p>同曲不同难度**没有任何折扣**：{@code Verruckt#EZ} 与 {@code Verruckt#IN}
 * 的种子完全独立（设计文档 §4 / §8.1）。
 */
public final class PriceTable {

    /** 一个谱面的价格：物品注册名 + 数量。{@code toString()} 便于单测打印。 */
    public record Price(String itemId, int count) {
        @Override
        public String toString() {
            return itemId + " x" + count;
        }
    }

    /** 物品池分档（设计文档 §4 的表格）。 */
    public enum Tier {
        /** EZ / HD（以及识别不出难度的谱面）。 */
        EASY_HARD,
        /** IN。 */
        IN,
        /** AT。 */
        AT
    }

    /**
     * 三档物品池，顺序即抽取顺序（{@code Random.nextInt(pool.length)}）。
     * 与设计文档 §4 表格一一对应：
     * <ul>
     *   <li>EZ/HD：铁锭、铜锭、红石粉、煤炭、玻璃板、面包</li>
     *   <li>IN：金锭、青金石、紫水晶碎片、黏液球、蜂蜜瓶</li>
     *   <li>AT：钻石、下界石英、荧石粉、末影珍珠、附魔之瓶（经验瓶）</li>
     * </ul>
     */
    public static final String[][] POOLS = {
            {
                    "minecraft:iron_ingot",
                    "minecraft:copper_ingot",
                    "minecraft:redstone",
                    "minecraft:coal",
                    "minecraft:glass_pane",
                    "minecraft:bread"
            },
            {
                    "minecraft:gold_ingot",
                    "minecraft:lapis_lazuli",
                    "minecraft:amethyst_shard",
                    "minecraft:slime_ball",
                    "minecraft:honey_bottle"
            },
            {
                    "minecraft:diamond",
                    // 下界石英的物品注册名是 minecraft:quartz（1.21 里没有 nether_quartz 这个物品）
                    "minecraft:quartz",
                    "minecraft:glowstone_dust",
                    "minecraft:ender_pearl",
                    "minecraft:experience_bottle"
            }
    };

    /** 各档数量区间（闭区间），设计文档 §4：「3–12」「2–8」「1–4」。 */
    public static final int[][] COUNT_RANGES = {
            {3, 12},
            {2, 8},
            {1, 4}
    };

    /** 谱面 id 里「曲目」与「难度」的分隔符：{@code Verruckt#IN}。 */
    public static final char SEP = '#';

    private PriceTable() {
    }

    // ------------------------------------------------------------------
    // 谱面 id 解析
    // ------------------------------------------------------------------

    /**
     * 取 {@code chartId} 的难度部分（{@code #} 之后）。
     * 没有 {@code #} 时返回整个字符串。
     */
    public static String difficultyOf(String chartId) {
        if (chartId == null) return "";
        int i = chartId.lastIndexOf(SEP);
        return i < 0 ? chartId : chartId.substring(i + 1);
    }

    /** 取 {@code chartId} 的曲目部分（{@code #} 之前）。 */
    public static String songOf(String chartId) {
        if (chartId == null) return "";
        int i = chartId.lastIndexOf(SEP);
        return i < 0 ? chartId : chartId.substring(0, i);
    }

    /**
     * 由难度串判定分档。真实谱包的 {@code info.txt} 里写的是
     * {@code Level: IN Lv.?}，所以这里做包含匹配而不是全等匹配：
     * 先看 AT，再看 IN，再看 EZ/HD；识别不出就当 EZ/HD（最便宜的一档）。
     */
    public static Tier tierOfDifficulty(String difficulty) {
        String d = difficulty == null ? "" : difficulty.toUpperCase(java.util.Locale.ROOT);
        if (d.contains("AT")) return Tier.AT;
        if (d.contains("IN")) return Tier.IN;
        // EZ / HD，或任何未知难度
        return Tier.EASY_HARD;
    }

    /** 由谱面 id（{@code 曲目#难度}）判定分档。 */
    /**
     * 价格档位**与难度无关**：由 chartId + worldSeed 随机决定。
     * （用户要求：不分难度定价，每个谱面的价位各自随机，EZ 也可能要求钻石、AT 也可能只要面包。）
     */
    public static Tier tierOfChartId(String chartId) {
        return tierOfChartId(chartId, 0L);
    }

    public static Tier tierOfChartId(String chartId, long worldSeed) {
        long h = hash(chartId, worldSeed ^ 0x5DEECE66DL);
        int idx = (int) Math.floorMod(h >>> 17, POOLS.length);
        return Tier.values()[idx];
    }

    // ------------------------------------------------------------------
    // 定价
    // ------------------------------------------------------------------

    /**
     * {@code hash(chartId, worldSeed)}：设计文档 §4 的种子函数。
     *
     * <p>实现：FNV-1a 64（对 chartId 的 UTF-16 code unit）+ worldSeed 先混一次，
     * 最后做一次 splitmix64 的收尾（保证低位也用上）。纯函数、跨 JVM 稳定，
     * 不依赖 {@code String.hashCode()} 之外的任何环境状态。
     */
    public static long hash(String chartId, long worldSeed) {
        String id = chartId == null ? "" : chartId;
        long h = 0xCBF29CE484222325L ^ (worldSeed * 0x9E3779B97F4A7C15L);
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x100000001B3L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /**
     * 一个谱面的价格。设计文档 §4 的伪代码：
     * <pre>
     * Random r = new Random(hash(chartId, worldSeed));
     * Item item = pool.pick(r);          // r.nextInt(pool.length)
     * int count = pool.countFor(item, r); // 数量区间内均匀
     * </pre>
     */
    public static Price of(String chartId, long worldSeed) {
        return of(chartId, worldSeed, tierOfChartId(chartId, worldSeed));
    }

    /** 显式指定分档的重载（单测/调试用；正常路径用 {@link #of(String, long)}）。 */
    public static Price of(String chartId, long worldSeed, Tier tier) {
        Tier t = tier == null ? Tier.EASY_HARD : tier;
        java.util.Random r = new java.util.Random(hash(chartId, worldSeed));
        String[] pool = POOLS[t.ordinal()];
        int[] range = COUNT_RANGES[t.ordinal()];
        String item = pool[r.nextInt(pool.length)];
        int count = range[0] + r.nextInt(range[1] - range[0] + 1);
        return new Price(item, count);
    }
}
