package dev.dsh.phimc.client;

import dev.dsh.phimc.survival.SurvivalEnv;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * {@link SurvivalEnv.LocalPlayer} 的客户端实现：把「本机客户端玩家」翻译成「集成服务端玩家」。
 *
 * <p>为什么需要它：{@link SurvivalEnv} 不能引用 {@code MinecraftClient}（专用服务端没有这个类），
 * 但单人游戏里「权威背包」在集成服务端那一侧，必须知道本机客户端是哪个玩家（按 UUID 查）。
 * 这个类只在客户端进程里被加载（由 {@code PhiMCClient} 安装），专用服务端永远不会碰到它。
 *
 * <p>多人服务器里 {@code MinecraftClient.getServer()} 是 {@code null}，所以
 * {@link SurvivalEnv#serverPlayer()} 会返回 {@code null} —— 与改造前「没有集成服务端玩家」
 * 的判定完全一致。
 */
public final class ClientLocalPlayer implements SurvivalEnv.LocalPlayer {

    @Override
    public MinecraftServer server() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null ? null : mc.getServer();
    }

    @Override
    public ServerPlayerEntity serverPlayer(MinecraftServer server) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || server == null) return null;
        ClientPlayerEntity p = mc.player;
        if (p == null) return null;
        return server.getPlayerManager().getPlayer(p.getUuid());
    }

    @Override
    public long worldTime() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return -1L;
        ClientWorld w = mc.world;
        return w == null ? -1L : w.getTime();
    }
}
