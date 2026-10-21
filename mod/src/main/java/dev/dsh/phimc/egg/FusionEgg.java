package dev.dsh.phimc.egg;

import dev.dsh.phimc.PhiMCRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 彩蛋：<b>大肥鱼 + Phira 融合 → 谱面播放器</b>（拍视频用）。
 *
 * <p>触发条件：同一世界里有两个<b>掉落物</b>（{@link ItemEntity}）——一个是
 * {@code phimc:big_fish}、一个是 {@code phimc:phira}——且两者中心距离 {@code ≤ 1.5} 格。
 * 一旦凑齐：原地劈一道<b>装饰性</b>闪电、两个掉落物消失、同一位置掉出一个
 * {@code phimc:chart_player}（谱面播放器）掉落物。
 *
 * <p><b>为什么是掉落物、不是背包</b>：视频里要的是「两样东西扔地上 → 雷一劈 → 地上多出
 * 一个播放器」的画面，所以融合结果也是 {@link ItemEntity}，不是直接塞玩家背包。
 *
 * <p><b>为什么在服务端 tick 里扫</b>：掉落物的增删只有逻辑端算数 —— 客户端只能看到
 * 服务端同步过来的结果。本 mod 是 client-only 的（{@code fabric.mod.json} 里
 * {@code environment: "client"}），单人游戏里集成服务端与客户端同进程、共用同一份静态
 * {@code Registries}，所以 {@code ServerTickEvents} 在单人局里照常触发
 * （同一个用法在 {@code survival/ChipLoot} 里已经跑了很久：开箱赋谱就是靠
 * {@code END_SERVER_TICK}）；专用服务器根本不加载这个 mod，多人里也就没有这个彩蛋
 * （物品本身也一样不存在）。
 *
 * <p><b>每对只触发一次</b>：{@link #fuse} 先把两个掉落物 {@code discard()} 掉再生成播放器，
 * 下一 tick 扫描时它们已经 {@code isRemoved()}，不会重复配对；生成出来的又是播放器
 * （不是鱼 / 不是 Phira），不会被自己再吃一遍。扫描到的实体列表是当 tick 的快照，
 * 所以循环里也要逐个复查 {@code isRemoved()}。
 */
public final class FusionEgg {

    /** 配对距离（格）。两个掉落物的中心点距离 ≤ 这个值才算「同一处」。 */
    public static final double RADIUS = 1.5;

    private static final Logger LOG = LoggerFactory.getLogger(PhiMCRegistry.MOD_ID);

    /** 只挂一次事件（{@link PhiMCRegistry#register()} 理论上也只跑一次，这里再兜一层）。 */
    private static boolean registered;

    private FusionEgg() {
    }

    /** 挂 {@code END_WORLD_TICK}：每个已加载世界每 tick 扫一遍掉落物。 */
    public static void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(FusionEgg::onWorldTick);
        LOG.info("[PhiMC] 彩蛋已就绪：大肥鱼 + Phira 相距 {} 格内 → 谱面播放器", RADIUS);
    }

    /**
     * 每 tick（20Hz）扫一遍这个世界里已加载的掉落物。
     *
     * <p>先按类型分成两堆再两两比较（{@code O(n)} 挑 + {@code O(鱼×Phira)} 配对），
     * 而不是两两全比较；地上没有这两种物品里的任意一种就直接返回，常规游玩零开销。
     */
    private static void onWorldTick(ServerWorld world) {
        // 只在服务端做。END_WORLD_TICK 拿到的本来就是 ServerWorld，这行是「别在客户端
        // 算掉落物」的显式护栏（等价于 !world.isClient）。
        if (world.isClient()) return;

        List<ItemEntity> fish = new ArrayList<>();
        List<ItemEntity> phira = new ArrayList<>();
        for (ItemEntity item : world.getEntitiesByType(EntityType.ITEM, e -> true)) {
            if (item.isRemoved()) continue;
            ItemStack stack = item.getStack();
            if (stack.isOf(PhiMCRegistry.BIG_FISH)) fish.add(item);
            else if (stack.isOf(PhiMCRegistry.PHIRA)) phira.add(item);
        }
        if (fish.isEmpty() || phira.isEmpty()) return;   // 常态：直接走人

        final double r2 = RADIUS * RADIUS;
        for (ItemEntity f : fish) {
            if (f.isRemoved()) continue;
            for (ItemEntity p : phira) {
                if (p.isRemoved()) continue;
                if (f.squaredDistanceTo(p) > r2) continue;   // 不在一处，继续找
                fuse(world, f, p);
                break;                                       // 这条鱼已经用掉了
            }
        }
    }

    /**
     * 融合一对掉落物：闪电 → 移除两个掉落物 → 原地掉出谱面播放器。
     *
     * <p>「该位置」取两个掉落物中心点的<b>中点</b>（两者相距 ≤ 1.5 格，视觉上没有区别，
     * 但中点不会让人觉得东西被吸到某一侧）。
     */
    private static void fuse(ServerWorld world, ItemEntity fish, ItemEntity phira) {
        double x = (fish.getX() + phira.getX()) / 2.0;
        double y = (fish.getY() + phira.getY()) / 2.0;
        double z = (fish.getZ() + phira.getZ()) / 2.0;

        // 装饰性闪电：setCosmetic(true) = 只有画面和雷声，不点火、不劈伤玩家、不把方块变成火。
        // （原版行为也接受，但拍视频时把布景烧了很烦，这里选装饰版。）
        LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
        bolt.setCosmetic(true);
        bolt.refreshPositionAfterTeleport(x, y, z);
        world.spawnEntity(bolt);

        // 移除两个掉落物（整堆一起消失：一个 ItemEntity = 一次触发，所以不会连闪）
        fish.discard();
        phira.discard();

        // 同一位置掉出谱面播放器 —— 保持「掉在地上」的画面，不塞玩家背包
        ItemEntity result = new ItemEntity(world, x, y, z, new ItemStack(PhiMCRegistry.CHART_PLAYER));
        world.spawnEntity(result);

        LOG.info("[PhiMC] 大肥鱼 + Phira 融合 → 谱面播放器");
    }
}
