package dev.dsh.phimc.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 进 / 退世界时切换「玩法进度」：把 {@link Session} 的 {@code Profile} 与 {@code Records}
 * 切到当前世界的目录（{@code saves/<世界名>/phimc/}），退世界再切回「没有世界」的只读兜底。
 *
 * <h2>世界存档路径怎么取</h2>
 * 单人游戏里客户端进程内就有一台集成服务端：
 * <pre>
 * MinecraftClient.getInstance().getServer()      // 集成服务端（多人时返回 null）
 *   .getSavePath(WorldSavePath.ROOT)            // = saves/&lt;世界文件夹名&gt;
 * </pre>
 * {@code getSavePath} 是 {@code MinecraftServer} 的公开方法，1.20 ~ 1.21.11 全系都在；
 * {@code WorldSavePath.ROOT} 的 relative path 是 {@code "."}，所以拿到的就是存档根目录
 * （不是「世界显示名」——重命名过的世界，文件夹名和显示名可以不同，用 API 才不会拼错）。
 *
 * <p><b>退世界</b>：{@code DISCONNECT} 里 {@code session.onWorldLeave()} →
 * {@code Profile.detachWorld()} 把 A 类进度清空、{@code Records} 关掉写入；
 * 之后主菜单里即使有设置写入（B 类），也不会把上一个世界的进度带进下一个世界。
 *
 * <p><b>多人 / 取不到集成服务端</b>：{@code getServer()} 为 null 或调用抛异常时传 null，
 * 等价于「没有世界」——玩法进度退化成只读兜底，不会写坏任何文件（见 {@code Profile}）。
 */
public final class WorldHooks {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    private WorldHooks() {
    }

    /** 在客户端入口注册事件（{@code PhiMCClient.onInitializeClient} 里调一次）。 */
    public static void register(Session session) {
        if (session == null) return;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Path root = saveRoot(client);
            session.onWorldJoin(root);
            if (root != null) {
                LOG.info("[PhiMC] 玩法进度目录: {}", WorldStore.progressDir(root));
            } else {
                LOG.info("[PhiMC] 没有本地世界存档（多人 / 取不到集成服务端）：玩法进度只读");
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> session.onWorldLeave());
    }

    /**
     * 当前世界存档根目录；拿不到返回 {@code null}。
     *
     * <p>多人游戏里 {@code getServer()} 是 null（服务端在别人机器上），没有「本地存档目录」
     * 可写，所以这里也返回 null。
     */
    public static Path saveRoot(MinecraftClient client) {
        if (client == null) return null;
        MinecraftServer server = client.getServer();
        if (server == null) return null;
        try {
            Path root = server.getSavePath(WorldSavePath.ROOT);
            return root == null ? null : root.normalize();
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 取世界存档目录失败，玩法进度按「没有世界」处理", t);
            return null;
        }
    }
}
