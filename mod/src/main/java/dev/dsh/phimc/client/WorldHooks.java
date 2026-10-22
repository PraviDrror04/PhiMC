package dev.dsh.phimc.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
 * <h2>多人服务器（拿不到存档根目录）</h2>
 * 以前这种情况直接传 {@code null}，等于「玩法进度只读、成绩不落盘」—— 多人打歌的成绩一退就没了。
 * 现在改成<b>按服务器地址落盘</b>：
 * <pre>
 *   handler.getConnection().getAddress()   // 例：play.example.com/1.2.3.4:25565（SocketAddress）
 *     → ServerStore.safeName(...)          // 例：play.example.com_1.2.3.4_25565（: / 等 → _）
 *     → config/phimc/servers/&lt;那个名字&gt;/{profile.json,records.tsv}
 * </pre>
 * 单人世界仍然是 {@code saves/<世界名>/phimc/}，两者互不影响。
 *
 * <p><b>退世界</b>：{@code DISCONNECT} 里 {@code session.onWorldLeave()} →
 * {@code Profile.detachWorld()} 把 A 类进度清空、{@code Records} 关掉写入；
 * 打歌 only 变体（{@code PhiMC.onlyMode}）则回到 {@code config/phimc/servers/local/}，
 * 因为那个变体可以在主菜单直接打歌，成绩不能丢。
 */
public final class WorldHooks {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    private WorldHooks() {
    }

    /** 在客户端入口注册事件（{@code PhiMCClient} / {@code PhiMCLiteClient} 各调一次）。 */
    public static void register(Session session) {
        if (session == null) return;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Path root = saveRoot(client);
            if (root != null) {
                session.onWorldJoin(root);
                LOG.info("[PhiMC] 玩法进度目录: {}", WorldStore.progressDir(root));
                return;
            }
            // 多人（或取不到集成服务端）：按服务器地址落盘
            String address = serverAddress(handler);
            session.onServerJoin(address);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> session.onWorldLeave());
    }

    /**
     * 当前世界存档根目录；拿不到返回 {@code null}。
     *
     * <p>多人游戏里 {@code getServer()} 是 null（服务端在别人机器上），没有「本地存档目录」
     * 可写，所以这里也返回 null（那种情况走 {@link #serverAddress} 那条路）。
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

    /**
     * 当前连接的服务器地址（{@code host:port}）；单人 / 取不到返回 {@code null}。
     *
     * <p>取的是 {@code ClientPlayNetworkHandler.getConnection().getAddress()}（需求点名的那个来源）。
     * 它在各版本都是 {@code SocketAddress}，多人时实际是 {@code InetSocketAddress}
     * （{@code client/ServerAddress} 里的 host + port），所以这里优先用它拼成
     * {@code host:port}；万一不是 {@code InetSocketAddress}（理论不会）就用它的 {@code toString()}。
     *
     * <p>整段包在 {@code try/catch Throwable} 里：{@code getConnection()} 在断开过程中可能是 null，
     * 这里绝不能因为「取个地址」把客户端打崩。
     */
    public static String serverAddress(ClientPlayNetworkHandler handler) {
        if (handler == null) return null;
        try {
            SocketAddress socket = handler.getConnection() == null ? null
                    : handler.getConnection().getAddress();
            if (socket == null) return null;
            if (socket instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                if (host == null || host.isEmpty()) return null;
                return host + ":" + inet.getPort();
            }
            String text = socket.toString();
            return text.isBlank() ? null : text;
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 取服务器地址失败，按「没有地址」处理", t);
            return null;
        }
    }
}
