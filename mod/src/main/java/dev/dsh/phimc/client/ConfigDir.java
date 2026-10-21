package dev.dsh.phimc.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;

/**
 * {@code config/} 下的模组目录定位 + 「旧目录名 → {@link #NAME}」一次性迁移。
 *
 * <p><b>只依赖 java.nio</b>（不碰 Minecraft），所以可以 headless 自测：
 * {@code tools/migrate/ConfigMigrationTest.java} 造一个假的旧目录、调 {@link #migrate} 再断言结果。
 *
 * <p><b>为什么要迁移</b>：改名（旧产品名 → {@code phimc}）后配置目录也跟着换名，若不搬，
 * 老用户整个曲库就「凭空消失」了 —— {@code songs/} 里几十个谱面包、{@code profile.json}
 * （解锁 / 成绩 / 商店）、{@code respack/}、{@code records.tsv} 都在那个目录里。
 *
 * <p><b>三条不丢数据的规则</b>：
 * <ol>
 *   <li>新目录不存在 → 整个旧目录 rename（跨卷时退化成复制，复制成功也<b>不删源</b>）；</li>
 *   <li>新目录已存在（例如安装器先建了 {@code config/<新名>/songs} 并塞了内置谱面）→
 *       <b>合并</b>：只补新目录里没有的文件，<b>绝不覆盖</b>已有文件；</li>
 *   <li>合并过一次就写标记文件 {@link #MARKER}，之后不再合并 —— 否则用户在新目录里删掉的谱面
 *       会从旧目录「复活」。</li>
 * </ol>
 */
public final class ConfigDir {

    /** 新的模组目录名（= mod id = 资源命名空间）。 */
    public static final String NAME = "phimc";

    /**
     * 改名前的旧模组目录名。
     *
     * <p><b>这一处旧名必须保留</b>：迁移逻辑要按它定位老目录，删掉就等于让老用户丢谱面。
     * 全仓搜索旧名时这里是唯一的命中点，属预期（其余代码 / 文档 / 资源一律不含旧名）。
     */
    public static final String LEGACY_NAME = "mcphigros";

    /** 迁移完成标记（写在<b>新</b>目录里）：存在即表示旧目录已经处理过，不再重复合并。 */
    public static final String MARKER = ".legacy-migrated";

    /** {@link #migrate} 的返回结果（日志与自测都按它断言）。 */
    public enum Result {
        /** 没有旧目录、也没有新目录：全新安装。 */
        NO_LEGACY_DIR,
        /** 没有旧目录、新目录已存在：什么都不用做。 */
        ALREADY_NEW,
        /** 整个目录 rename 成功（同一卷上的正常路径）。 */
        MIGRATED_MOVE,
        /** rename 失败（跨卷 / 被占用）→ 退化成递归复制；<b>旧目录保留不删</b>。 */
        MIGRATED_COPY,
        /** 新旧目录都在 → 非覆盖式合并（新目录已有的文件一律保留），随后打标记。 */
        MERGED
    }

    private ConfigDir() {
    }

    /**
     * {@code config/<新名>/}：先跑一次迁移（幂等），再确保目录存在。
     *
     * @param configRoot 通常是 {@code FabricLoader.getInstance().getConfigDir()}
     */
    public static Path modDir(Path configRoot) {
        migrateQuietly(configRoot);
        Path d = configRoot.resolve(NAME);
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // 建不出来就交给调用方：拿到的路径依旧可用（真正写盘时才会报错）
        }
        return d;
    }

    /** 迁移但不抛异常（启动路径上不该因为 IO 问题让游戏起不来）。 */
    public static Result migrateQuietly(Path configRoot) {
        try {
            return migrate(configRoot);
        } catch (Throwable t) {
            return Result.NO_LEGACY_DIR;
        }
    }

    /**
     * 把 {@code config/<旧名>} 的内容搬到 {@code config/<新名>}（只做一次，见类的三条规则）。
     *
     * @return 这次实际做了什么
     * @throws IOException IO 错误（调用方通常用 {@link #migrateQuietly} 吞掉）
     */
    public static Result migrate(Path configRoot) throws IOException {
        Path newDir = configRoot.resolve(NAME);
        Path oldDir = configRoot.resolve(LEGACY_NAME);
        boolean newExists = Files.exists(newDir);
        if (!Files.isDirectory(oldDir)) return newExists ? Result.ALREADY_NEW : Result.NO_LEGACY_DIR;

        if (!newExists) {
            try {
                Files.move(oldDir, newDir, StandardCopyOption.ATOMIC_MOVE);
                markDone(newDir, oldDir, "move");
                return Result.MIGRATED_MOVE;
            } catch (IOException | UnsupportedOperationException atomicFailed) {
                // 跨卷 / 目录被别的进程占着（Windows 上 rename 会失败）→ 复制一份，源目录保留
            }
            copyTree(oldDir, newDir, true);
            markDone(newDir, oldDir, "copy");
            return Result.MIGRATED_COPY;
        }

        // 新旧都在：合并（不覆盖），并打标记，避免用户删掉的谱面下次启动又冒出来
        if (Files.exists(newDir.resolve(MARKER))) return Result.ALREADY_NEW;
        copyTree(oldDir, newDir, false);
        markDone(newDir, oldDir, "merge");
        return Result.MERGED;
    }

    /** 写标记文件（内容只是给人看的来源说明；写不进去也不影响迁移结果）。 */
    private static void markDone(Path newDir, Path oldDir, String how) {
        try {
            Files.writeString(newDir.resolve(MARKER),
                    LocalDateTime.now() + " " + how + " <- " + oldDir + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /**
     * 递归复制目录（保留相对结构；源目录不动）。
     *
     * @param overwrite true = 覆盖同名文件；false = <b>跳过</b>新目录里已有的文件（合并语义）
     */
    private static void copyTree(Path from, Path to, boolean overwrite) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                if (!overwrite && Files.exists(target)) return FileVisitResult.CONTINUE;
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
