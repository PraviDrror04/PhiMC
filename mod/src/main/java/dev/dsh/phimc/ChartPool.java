package dev.dsh.phimc;

import dev.dsh.phimc.client.SongEntry;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 全进程共用的「谱面 id 候选集」—— 开箱赋谱（{@code survival/ChipLoot}）的候选池。
 *
 * <h2>为什么不再直接读客户端 Session</h2>
 * 改造前 {@code ChipLoot.init(...)} 的候选集是 {@code client/Session#chartIds()}，
 * 而 {@code Session} 是客户端类（它建音频 / 界面状态）。专用服务端没有 Session，
 * 引用它就是 {@code NoClassDefFoundError}。所以候选集抽象成这里的「来源」：
 * <ul>
 *   <li><b>客户端</b>（含单人）：{@code PhiMCClient} 建好 {@code Session} 后装
 *       {@code session::chartIds} —— 还是同一个动态来源，玩家加/删谱面包、{@code rescan()}
 *       之后候选集立刻跟着变，行为与改造前一致；</li>
 *   <li><b>专用服务端</b>：{@link #scanServer()} 在服务端启动时扫一次
 *       {@code <运行目录>/config/phimc/songs}（以及服主可能直接放的 {@code <运行目录>/songs}），
 *       装成 {@link #scannedIds()}。</li>
 * </ul>
 *
 * <h2>为什么能复用 {@code client.SongEntry} 的扫描器</h2>
 * {@code SongEntry}（连它用到的 {@code SongMeta} / {@code ChartMeta}）实测<b>零 Minecraft 依赖</b>：
 * 只有 {@code java.nio} / {@code java.util.zip} / gson / slf4j。所以服务端直接复用同一份
 * 扫描逻辑，而不是在 {@code core} 里再写一个「解析器」——两份实现迟早会走偏
 * （info.txt 字段、RPE 包的文件名启发式、ZIP 编码回退都在这一个类里）。
 * 「没有客户端类」这件事用 {@code javap} 验过：这两个类的常量池里没有
 * {@code net/minecraft/} 的任何条目。
 */
public final class ChartPool {

    private static final Logger LOG = LoggerFactory.getLogger(PhiMCRegistry.MOD_ID);

    /** 候选集来源（延迟求值：客户端建 Session 之前是空的）。 */
    private static volatile Supplier<List<String>> source = List::of;

    /** 服务端扫描结果（不可变快照）。 */
    private static volatile List<String> scanned = List.of();

    /** 服务端扫描用的主目录（{@code config/phimc/songs}）。 */
    private static volatile Path scannedDir;

    private ChartPool() {
    }

    /** 装候选集来源（客户端：{@code session::chartIds}；服务端：{@link ChartPool#scannedIds}）。 */
    public static void setSource(Supplier<List<String>> supplier) {
        source = supplier == null ? List::of : supplier;
    }

    /** 当前候选集（永远是只读视图，可能为空，不会返回 {@code null}）。 */
    public static List<String> ids() {
        Supplier<List<String>> s = source;
        if (s == null) return List.of();
        List<String> v = s.get();
        return v == null ? List.of() : v;
    }

    /** 服务端扫描到的谱面 id（客户端不用；日志 / 排查用）。 */
    public static List<String> scannedIds() {
        return scanned;
    }

    /** 服务端扫描的主目录（没扫过时为 {@code null}）。 */
    public static Path scannedDir() {
        return scannedDir;
    }

    /**
     * 专用服务端启动时扫一次曲库（由 {@link PhiMCMod} 在 {@code SERVER_STARTED} 里调）。
     *
     * <p>扫两个位置并合并（按 chartId 去重）：{@code config/phimc/songs}（主目录，不存在就建出来，
     * 方便服主直接往里丢 {@code .zip} / {@code .pez}）和 {@code <运行目录>/songs}（服主可能
     * 把谱面包直接放在服务端根目录）。扫完把候选集来源切成这份快照。
     *
     * @return 找到的谱面数
     */
    public static int scanServer() {
        List<Path> dirs = serverSongsDirs();
        Map<String, Path> found = new LinkedHashMap<>();
        Path primary = primaryDir();
        for (Path dir : dirs) {
            if (dir == null) continue;
            if (!Files.isDirectory(dir)) {
                if (dir.equals(primary)) ensureDir(dir);
                continue;
            }
            List<SongEntry> songs = SongEntry.scan(dir);
            for (SongEntry e : songs) found.putIfAbsent(e.chartId(), dir);
            LOG.info("[PhiMC] 服务端扫描曲库 {} → {} 首", dir, songs.size());
        }
        List<String> ids = List.copyOf(found.keySet());
        scanned = ids;
        scannedDir = primary;
        setSource(ChartPool::scannedIds);
        LOG.info("[PhiMC] 找到 {} 首谱面（服务端曲库：{}）", ids.size(),
                primary == null ? "-" : primary);
        if (ids.isEmpty()) {
            LOG.info("[PhiMC] 曲库为空：把谱面包（.zip / .pez）放进 {} 后重启服务端",
                    primary == null ? "config/phimc/songs" : primary);
        }
        return ids.size();
    }

    /** 服务端会扫的目录（按优先级）：{@code config/phimc/songs} → {@code <运行目录>/songs}。 */
    public static List<Path> serverSongsDirs() {
        List<Path> out = new ArrayList<>(2);
        Path primary = primaryDir();
        if (primary != null) out.add(primary);
        try {
            Path alt = FabricLoader.getInstance().getGameDir().resolve("songs");
            if (!out.contains(alt)) out.add(alt);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** {@code <运行目录>/config/phimc/songs}；取不到运行目录时返回 {@code null}。 */
    private static Path primaryDir() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve(PhiMCRegistry.MOD_ID).resolve("songs");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            LOG.info("[PhiMC] 服务端曲库目录已创建：{}（把 .zip / .pez 谱面包放进去）", dir);
        } catch (IOException ignored) {
            // 建不出来就只记一条 warn：服务端照常起，只是没有谱面候选
            LOG.warn("[PhiMC] 服务端曲库目录无法创建：{}", dir);
        }
    }
}
