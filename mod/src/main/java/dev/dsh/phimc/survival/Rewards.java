package dev.dsh.phimc.survival;

import dev.dsh.phimc.core.Judgement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 结算奖励的落地层：让 {@link RewardTable} 按评级**随机抽**出一批物品，再真正塞进玩家背包。
 *
 * <p>奖励不再是死表：抽几次由评级决定，每次从 {@link PriceTable#POOLS} 的三档池里按权重抽，
 * 所以「打歌掉的材料」一定能覆盖「解锁谱面要的材料」（两个集合是同一个来源）。
 * 抽奖规则与权重见 {@link RewardTable} 的类注释。
 *
 * <p><b>在哪调用</b>：{@code client/Session.finish()}（一局结束、结算成绩的那一处）
 * 调 {@link #grantOnFinish(String, int)}。那是唯一的「结算」入口，所以奖励也只发一次 ——
 * {@code Session.finish()} 自带 {@code if (finished) return;} 幂等保护，
 * {@code restart()} 会把 {@code finished} 复位，重打一局 = 新的一次结算（新发一次）。
 *
 * <p><b>线程 / 权威性</b>：{@code finish()} 跑在**客户端线程**上，而单人游戏的背包属于
 * 集成服务端（另一个线程）。所以单人时把发放任务 {@code server.execute(...)} 丢回服务端线程，
 * 由服务端改背包、发提示，客户端只是收到同步结果；没有集成服务端（专用服务器 —— 本 mod
 * 本来也不支持）时才退化成直接改客户端背包。这条比 {@link Wallet#pay} 的「两边都改」更严格，
 * 因为它不需要镜像：服务端改完之后原版同步会自己把结果推给客户端。
 *
 * <p><b>不改计分/判定</b>：本类只读 {@code engine.score()}，评级由 {@link Judgement#rank} 算，
 * 判定与计分公式一行没动。
 */
public final class Rewards {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /**
     * 最近一次结算的奖励（结算界面 {@code PlayScreen.drawResult} 读它）。
     *
     * @param chartId 谱面 id（日志/排查用）
     * @param rank    评级（F/C/B/A/S/V/PHI）
     * @param summary 奖励摘要（{@code 金锭×1 + 红石粉×2}），F 级为空串
     */
    public record LastReward(String chartId, String rank, String summary) {
        /** 这一档有没有实物奖励（F 级没有）。 */
        public boolean isEmpty() {
            return summary == null || summary.isEmpty();
        }

        /** 结算界面那一行的文字：{@code 奖励：金锭×1 + 红石粉×2} / {@code 奖励：无}。 */
        public String text() {
            return isEmpty() ? "奖励：无" : "奖励：" + summary;
        }
    }

    /**
     * 最近一次结算的奖励。{@code volatile}：由 {@code Session.finish()}（客户端线程）在结算那一下写入，
     * 结算界面（同一线程）随后读取。真正「发物品」可能晚一 tick 在服务端线程完成 ——
     * 界面文案与实际发放解耦，界面永远显示这一局应得的奖励。
     */
    private static volatile LastReward lastReward;

    private Rewards() {
    }

    /** 最近一次结算奖励，还没结算过时返回 {@code null}。 */
    public static LastReward lastReward() {
        return lastReward;
    }

    /** 结算界面用的一行文字（永不 null）：{@code 奖励：...} / {@code 奖励：无}。 */
    public static String lastRewardText() {
        LastReward r = lastReward;
        return r == null ? "奖励：无" : r.text();
    }

    /**
     * 结算入口（{@code Session.finish()} 调用）：按分数评级抽物资并发给玩家。
     *
     * <p>随机源是**每次结算新开的一个未播种 {@link Random}**：每局结果都不同、不可预测。
     * 刻意不做「同存档 / 同分数可复现」—— 那等于让玩家拿同一个分数反复重打来刷同一批物品。
     * 需要可复现的场合（压测 / 自测）用 {@link #grantOnFinish(String, int, Random)} 传 seeded 的。
     *
     * @param chartId 本局谱面 id（只用于日志；{@code null} 也照发）
     * @param score   本局分数（只读）
     * @return 本次抽到的奖励清单（F 级为空表）—— 发放本身可能是下一 tick 在服务端线程完成的
     */
    public static List<RewardTable.Reward> grantOnFinish(String chartId, int score) {
        return grantOnFinish(chartId, score, new java.util.Random());
    }

    /**
     * 带随机源的结算入口（压测用）。
     *
     * @param random 抽奖用的随机源；传 {@code new Random(seed)} 就是完全可复现的一局
     */
    public static List<RewardTable.Reward> grantOnFinish(String chartId, int score, java.util.Random random) {
        String rank = Judgement.rank(score);
        java.util.Random rnd = random == null ? new java.util.Random() : random;
        // 随机抽：抽几次由评级决定，每次从 PriceTable 的三档池里按权重抽物品 + 区间内抽数量
        List<RewardTable.Reward> rewards = RewardTable.roll(rank, rnd);
        // 先把「这一局给了什么」记下来：结算界面紧接着就要画，而发物品可能下一 tick 才在服务端线程做
        lastReward = new LastReward(chartId, rank, RewardTable.summary(rewards));
        if (rewards.isEmpty()) {
            LOG.info("[PhiMC] 结算奖励：{} 评级 {}（无奖励）", chartId, rank);
            return rewards;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        MinecraftServer server = mc.getServer();
        ServerPlayerEntity serverPlayer = SurvivalEnv.serverPlayer();
        if (server != null && serverPlayer != null) {
            // 单人：背包是集成服务端的，回服务端线程改
            server.execute(() -> giveAndNotify(serverPlayer, chartId, rank, rewards));
        } else {
            giveAndNotify(mc.player, chartId, rank, rewards);
        }
        return rewards;
    }

    private static void giveAndNotify(PlayerEntity player, String chartId, String rank,
                                      List<RewardTable.Reward> rewards) {
        if (player == null) {
            LOG.warn("[PhiMC] 结算奖励：{} 评级 {} 但拿不到玩家实体，本次不发", chartId, rank);
            return;
        }
        try {
            int count = give(player, rewards);
            String text = "评级 " + rank + "：奖励 " + RewardTable.summary(rewards)
                    + "（共 " + count + " 件，装不下的掉在脚下）";
            player.sendMessage(Text.literal(text), true);
            LOG.info("[PhiMC] 结算奖励：{} 评级 {} -> {}（{} 件）",
                    chartId, rank, RewardTable.summary(rewards), count);
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 结算奖励发放失败：{}", chartId, t);
        }
    }

    /**
     * 把一档奖励发到 {@code player} 的背包里；装不下的**整件**掉在脚下
     * （{@code insertStack} 会尽力塞，塞不进去的部分留在 stack 里，这里原样丢出去）。
     *
     * @return 实际发出去的物品总件数（含掉在地上的）
     */
    public static int give(PlayerEntity player, List<RewardTable.Reward> rewards) {
        if (player == null || rewards == null || rewards.isEmpty()) return 0;
        int given = 0;
        PlayerInventory inv = player.getInventory();
        // 先按物品合并：同一局抽到两次铁锭就塞一叠，而不是两个小堆
        for (RewardTable.Reward r : RewardTable.merged(rewards)) {
            Item item = item(r.itemId());
            if (item == null || r.count() <= 0) {
                LOG.warn("[PhiMC] 奖励物品无法解析：{}", r.itemId());
                continue;
            }
            ItemStack stack = new ItemStack(item, r.count());
            given += r.count();
            if (!inv.insertStack(stack) && !stack.isEmpty()) {
                player.dropItem(stack, false);          // 装不下：剩下的掉脚下
            }
        }
        return given;
    }

    /** 注册名 → 物品（{@code minecraft:iron_ingot} 这种），解析失败返回 null。 */
    private static Item item(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        //? if >=1.21.2 {
        return Registries.ITEM.getOptionalValue(id).orElse(null);
        //?} else {
        /*return Registries.ITEM.getOrEmpty(id).orElse(null);
        *///?}
    }
}
