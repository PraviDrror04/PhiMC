package dev.dsh.phimc.survival;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;

/**
 * 生存玩法要用的「游戏环境」—— <b>双端门面</b>：世界种子、世界时间、权威背包、存档根目录。
 *
 * <h2>为什么这里一个 {@code MinecraftClient} 都没有</h2>
 * 改造前本类（以及 {@code Rewards} / {@code Wallet} / {@code ChipLoot}）直接
 * {@code MinecraftClient.getInstance().getServer()}：那是客户端类，专用服务端加载到
 * 这个方法就会 {@code NoClassDefFoundError}。现在改成：
 * <ul>
 *   <li><b>服务端对象</b>：{@link #bindServer(MinecraftServer)} 在
 *       {@code ServerLifecycleEvents.SERVER_STARTED} 里绑定（单人集成服务端同样会触发，
 *       所以「单人」和「专用服务端」走同一条路）；</li>
 *   <li><b>「本机玩家」</b>：{@link #installLocalPlayer(LocalPlayer)} 由客户端入口装一个桥
 *       （{@code client/ClientLocalPlayer}，读 {@code MinecraftClient.player} 的 UUID）。
 *       专用服务端没人装 → {@link #serverPlayer()} 返回 {@code null}，这正是「没有本机玩家」
 *       的正确语义（发奖励那条硬规则据此判断）；</li>
 *   <li><b>世界时间</b>：优先服务端主世界 tick（与客户端 tick 计数同源），服务端不在时
 *       退回客户端世界时间（多人服务器里就靠这个，行为与改造前一致）。</li>
 * </ul>
 *
 * <p>本类的常量池里只有 {@code net.minecraft.server.*} / {@code net.minecraft.util.WorldSavePath}
 * 和 fabric-loader，没有 {@code net.minecraft.client.*}。
 */
public final class SurvivalEnv {

    /**
     * 「本机玩家」桥：单人 / 客户端由 {@code client/ClientLocalPlayer} 实现，
     * 专用服务端不安装（那里没有「本机玩家」这个概念）。
     */
    public interface LocalPlayer {
        /**
         * 本进程里能拿到的服务端（客户端 = 集成服务端，多人 / 主菜单为 {@code null}）。
         *
         * <p>这是 {@link #server()} 的兜底来源：正常情况下服务端已经由
         * {@code SERVER_STARTED} 绑定，这里再给一条客户端自己的取法，
         * 保证单人路径与改造前（{@code MinecraftClient.getInstance().getServer()}）完全等价。
         */
        MinecraftServer server();

        /** 这个进程里的本机玩家对应的服务端实体；取不到返回 {@code null}。 */
        ServerPlayerEntity serverPlayer(MinecraftServer server);

        /** 客户端世界时间（tick）；取不到返回负数。 */
        long worldTime();
    }

    private static volatile MinecraftServer server;
    private static volatile LocalPlayer local;

    private SurvivalEnv() {
    }

    /** 服务端启动（单人集成服务端也会）时绑定。 */
    public static void bindServer(MinecraftServer s) {
        server = s;
    }

    /** 服务端停止时解绑（只解绑自己那一个，避免竞态把新服务端抹掉）。 */
    public static void unbindServer(MinecraftServer s) {
        if (server == s) server = null;
    }

    /** 装「本机玩家」桥（客户端入口调一次）。 */
    public static void installLocalPlayer(LocalPlayer p) {
        local = p;
    }

    public static void clearLocalPlayer() {
        local = null;
    }

    /**
     * 当前服务端：优先用 {@code SERVER_STARTED} 绑定的那台；没有时问「本机玩家」桥
     * （客户端 = 集成服务端）。主菜单 / 多人客户端 / 服务端还没起来时为 {@code null}。
     */
    public static MinecraftServer server() {
        MinecraftServer s = server;
        if (s != null) return s;
        LocalPlayer p = local;
        if (p == null) return null;
        try {
            return p.server();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这个进程是不是专用服务端（客户端入口从不调这里，报告里用来区分日志语义）。 */
    public static boolean dedicated() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
    }

    /**
     * 「权威服务端玩家」：单人 = 集成服务端里与本机客户端同 UUID 的玩家；
     * 专用服务端 = {@code null}（服务端没有「本机玩家」，谁触发谁自己带 {@code ServerPlayerEntity}）。
     */
    public static ServerPlayerEntity serverPlayer() {
        MinecraftServer s = server();
        LocalPlayer p = local;
        if (s == null || p == null) return null;
        try {
            return p.serverPlayer(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前世界存档根目录（{@code MinecraftServer#getSavePath(WorldSavePath.ROOT)}）。
     *
     * <p>单人 = {@code saves/<世界名>/}；专用服务端 = 服主的 {@code world/}。
     */
    public static Path saveRoot() {
        MinecraftServer s = server();
        if (s == null) return null;
        try {
            Path root = s.getSavePath(WorldSavePath.ROOT);
            return root == null ? null : root.normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主世界的权威种子（拿不到时用固定常量 1，价格仍然稳定）。 */
    public static long currentWorldSeed() {
        try {
            MinecraftServer s = server();
            if (s != null) {
                ServerWorld over = s.getOverworld();
                if (over != null) return over.getSeed();
            }
        } catch (Throwable ignored) {
        }
        return 1L;
    }

    /** 世界总时间（tick）。{@code shopRefreshAt} 用它比较。 */
    public static long nowTicks() {
        MinecraftServer s = server();
        if (s != null) {
            try {
                ServerWorld over = s.getOverworld();
                if (over != null) return over.getTime();
            } catch (Throwable ignored) {
            }
        }
        LocalPlayer p = local;
        if (p != null) {
            try {
                long t = p.worldTime();
                if (t >= 0) return t;
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    /** 新货架种子：由 worldSeed + 当前时间派生，保证同一天刷新结果可复现。 */
    public static long nextShopSeed(long worldSeed, long nowTicks) {
        return new java.util.Random(worldSeed * 0x9E3779B97F4A7C15L + nowTicks).nextLong();
    }

    /** 是否单人游戏（有集成服务端、且不是专用服务端）。 */
    public static boolean singleplayer() {
        return server() != null && !dedicated();
    }
}
