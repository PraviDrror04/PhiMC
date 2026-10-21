package dev.dsh.phimc.client;

import java.nio.file.Path;

/**
 * 单个世界存档里「玩法进度」的落点（{@code saves/<世界名>/phimc/}）。
 *
 * <h2>为什么进度要按世界隔离</h2>
 * {@code config/phimc/profile.json} 是<b>整个实例共用</b>的：解锁、成绩、商店货架放那里，
 * 换一个存档就全串了（A 世界解锁的 3 首，在 B 世界里也显示解锁）。所以把玩法进度挪到
 * 世界自己的目录里，客户端偏好（延迟、音量、材质包…）仍留在 {@code config/phimc/}。
 *
 * <p>目录名复用 {@link ConfigDir#NAME}（= {@code phimc}），这样「换产品名」时两处一起换，
 * 不会出现 {@code saves/<w>/mcphigros/} 这种半新半旧的东西。
 *
 * <p><b>纯 java.nio</b>：不 import 任何 Minecraft 类，headless 测试可以直接拿临时目录调用
 * （见 {@code tools/world/WorldProgressTest.java}）。真正「世界存档根目录」由
 * {@link WorldHooks} 从集成服务端取。
 */
public final class WorldStore {

    /** 世界进度的子目录名（与配置目录同名）。 */
    public static final String DIR = ConfigDir.NAME;

    private WorldStore() {
    }

    /**
     * {@code saves/<世界名>/phimc/}。
     *
     * @param worldSaveRoot 世界存档根目录（{@code MinecraftServer.getSavePath(WorldSavePath.ROOT)}）
     */
    public static Path progressDir(Path worldSaveRoot) {
        return worldSaveRoot.resolve(DIR);
    }

    /** 该世界的玩法进度（A 类：解锁 / 成绩 / 商店 / 赠礼 / 价格种子）。 */
    public static Path profileFile(Path worldSaveRoot) {
        return progressDir(worldSaveRoot).resolve("profile.json");
    }

    /** 该世界的「最佳成绩」TSV（选曲界面显示用）。 */
    public static Path recordsFile(Path worldSaveRoot) {
        return progressDir(worldSaveRoot).resolve("records.tsv");
    }
}
