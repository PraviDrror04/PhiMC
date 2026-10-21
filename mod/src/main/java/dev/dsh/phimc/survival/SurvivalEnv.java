package dev.dsh.phimc.survival;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/**
 * 生存玩法要用的「游戏环境」：世界种子、世界时间、权威背包。
 *
 * <p><b>多人限制</b>：本 mod 的物品只注册在客户端（{@code PhiMCRegistry}），
 * 服务器端没有对应注册，所以只有**单人游戏（集成服务端同进程）**能用。
 * 单人时集成服务端的世界是权威的，这里优先拿服务端的数据；拿不到
 * （专用服务器 / 还没进世界）时退化成客户端数据或常量，功能仍可用但
 * 世界种子不再代表真实世界。
 */
public final class SurvivalEnv {

    private SurvivalEnv() {
    }

    /** 单人游戏里集成服务端的玩家实体，取不到返回 null。 */
    public static ServerPlayerEntity serverPlayer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        MinecraftServer server = mc.getServer();
        if (server == null || mc.player == null) return null;
        UUID id = mc.player.getUuid();
        return server.getPlayerManager().getPlayer(id);
    }

    /** 单人游戏里主世界的权威种子。 */
    public static long currentWorldSeed() {
        try {
            MinecraftServer server = MinecraftClient.getInstance().getServer();
            if (server != null) {
                ServerWorld over = server.getOverworld();
                if (over != null) return over.getSeed();
            }
        } catch (Throwable ignored) {
        }
        // 多人 / 没进世界：拿不到种子，用一个固定常量（价格仍然稳定，只是不跟着世界走）
        return 1L;
    }

    /** 世界总时间（tick）。{@code shopRefreshAt} 用它比较。 */
    public static long nowTicks() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? 0L : mc.world.getTime();
    }

    /** 新货架种子：由 worldSeed + 当前时间派生，保证同一天刷新结果可复现。 */
    public static long nextShopSeed(long worldSeed, long nowTicks) {
        return new java.util.Random(worldSeed * 0x9E3779B97F4A7C15L + nowTicks).nextLong();
    }

    /** 是否单人游戏（多人下所有背包/解锁操作都只在本机生效）。 */
    public static boolean singleplayer() {
        return MinecraftClient.getInstance().getServer() != null;
    }
}
