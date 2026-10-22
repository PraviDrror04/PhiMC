package dev.dsh.phimc.survival;

import dev.dsh.phimc.client.Records;
import dev.dsh.phimc.client.WorldStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * <b>专用服务端</b>的玩法存档（解锁 / 成绩 / 商店 / 赠礼）。
 *
 * <h2>存档落在哪</h2>
 * 与服务端「世界存档目录」同一个根，按世界隔离：
 * <pre>
 *   &lt;服务端运行目录&gt;/world/phimc/profile.json    // A 类：解锁 / 成绩 / 商店 / 世界种子
 *   &lt;服务端运行目录&gt;/world/phimc/records.tsv     // 最佳成绩
 * </pre>
 * 根目录是 {@code MinecraftServer#getSavePath(WorldSavePath.ROOT)}（专用的默认值是
 * {@code world/}，单人是 {@code saves/<世界名>/}），和客户端单人那条路是<b>同一个 API</b>，
 * 所以两边的目录结构完全一致，玩家把单人存档搬到服上也是同一个 {@code phimc/}。
 *
 * <h2>为什么只在专用服务端打开</h2>
 * 单人游戏里<b>客户端那份</b> {@code Session.profile}（{@code WorldHooks} 在 JOIN 时切到
 * {@code saves/<世界>/phimc/}）已经是权威的了。如果集成服务端也开一份同样的文件，就会有两个
 * {@code Profile} 实例轮流写同一个 {@code profile.json}，互相覆盖。所以这里用
 * {@code FabricLoader.getEnvironmentType() == SERVER} 把「专用服务端」和「单人」分开：
 * 单人 = 客户端 Session 那条路（行为与改造前逐字节一致），专用 = 这里。
 *
 * <h2>本类零 Minecraft 客户端依赖</h2>
 * 只碰 {@code MinecraftServer} / {@code WorldSavePath}（服务端类）；{@code ConfigDir} /
 * {@code Records} / {@code WorldStore} 虽然放在 {@code client} 包里，但都是纯
 * {@code java.nio}（{@code javap} 验过：常量池里没有 {@code net/minecraft/}），
 * 所以能在专用服务端直接复用同一套路径 / 读写逻辑。
 */
public final class ServerSurvival {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    private static volatile Profile profile;
    private static volatile Records records;
    private static volatile Path progressDir;

    private ServerSurvival() {
    }

    /** 专用服务端启动后调一次（{@link dev.dsh.phimc.PhiMCMod}）；重复调用是幂等的。 */
    public static void attach(MinecraftServer server) {
        if (server == null) return;
        Path root = saveRoot(server);
        if (root == null) {
            LOG.warn("[PhiMC] 服务端存档根目录取不到：玩法存档不落盘（曲库 / 战利品不受影响）");
            return;
        }
        try {
            // 全局文件传 null：服务端的「客户端偏好」（延迟 / 音量 / 材质包）没有意义，
            // 也不该在服主机器上生成一份带默认值的 config/phimc/profile.json。
            Profile p = new Profile(null);
            // false = 不认领旧版单文件进度：服务端只认自己存档目录里的进度
            p.attachWorld(WorldStore.profileFile(root), false);
            Records r = new Records(WorldStore.recordsFile(root));
            profile = p;
            records = r;
            progressDir = WorldStore.progressDir(root);

            long seed = 1L;
            ServerWorld over = server.getOverworld();
            if (over != null) seed = over.getSeed();
            boolean wrote = p.ensureWorldSeed(seed);
            LOG.info("[PhiMC] 服务端玩法存档：{}（世界种子 {}，{}）", progressDir, seed,
                    wrote ? "已写入" : "沿用存档");
            LOG.info("[PhiMC] 服务端成绩表：{}", WorldStore.recordsFile(root));
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 服务端玩法存档打开失败：{}", root, t);
        }
    }

    /** 服务端停止时调一次：清掉静态引用，别把上一个服务端的进度带到下一次启动。 */
    public static void detach() {
        profile = null;
        records = null;
        progressDir = null;
    }

    /** 服务端那份玩法存档；<b>单人 / 还没进服务端时是 {@code null}</b>（改走客户端 Session）。 */
    public static Profile profile() {
        return profile;
    }

    /** 服务端成绩表；同上。 */
    public static Records records() {
        return records;
    }

    /** 服务端玩法存档目录（{@code <世界>/phimc/}）；没打开时 {@code null}。 */
    public static Path progressDir() {
        return progressDir;
    }

    /** 专用服务端的玩法存档是不是已经打开。 */
    public static boolean active() {
        return profile != null;
    }

    private static Path saveRoot(MinecraftServer server) {
        try {
            Path root = server.getSavePath(WorldSavePath.ROOT);
            return root == null ? null : root.normalize();
        } catch (Throwable t) {
            return null;
        }
    }
}
