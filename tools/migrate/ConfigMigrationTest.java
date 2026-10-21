import dev.dsh.phimc.client.ConfigDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 配置目录迁移的 headless 自测（不碰 Minecraft，只吃 {@link ConfigDir}）。
 *
 * <p>验证「改名后不丢用户数据」这条硬要求：
 * <pre>
 *   config/&lt;旧名&gt;/songs/*.pez + profile.json + records.tsv + respack/  →  config/phimc/ 同名同内容
 *   只有旧目录            → 整个目录 rename（MIGRATED_MOVE）
 *   新旧都有（安装器先建了新的）→ 非覆盖式合并（MERGED）
 *   已合并过（有标记）      → 跳过（ALREADY_NEW），用户删掉的谱面不会复活
 * </pre>
 *
 * <p>编译与运行（仓库根目录）：
 * <pre>
 *   javac -encoding UTF-8 -d out-migrate ^
 *       mod\src\main\java\dev\dsh\phimc\client\ConfigDir.java tools\migrate\ConfigMigrationTest.java
 *   java -cp out-migrate ConfigMigrationTest [沙箱根目录]
 * </pre>
 * 退出码 0 = 全部通过。
 */
public final class ConfigMigrationTest {

    private static int pass;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path sandbox = args.length > 0
                ? Paths.get(args[0])
                : Paths.get(System.getProperty("java.io.tmpdir"), "phimc-migrate-test");
        if (Files.exists(sandbox)) deleteTree(sandbox);
        Files.createDirectories(sandbox);

        System.out.println("== 沙箱: " + sandbox.toAbsolutePath() + " ==");
        System.out.println("   旧目录名 = " + ConfigDir.LEGACY_NAME + " / 新目录名 = " + ConfigDir.NAME);
        System.out.println();

        testFreshInstall(sandbox.resolve("case1-fresh"));
        testMigrateFullLibrary(sandbox.resolve("case2-migrate"));
        testMergeWhenBothExist(sandbox.resolve("case3-merge"));
        testNoResurrectionAfterMarker(sandbox.resolve("case4-marker"));
        testIdempotent(sandbox.resolve("case5-idempotent"));

        System.out.println();
        System.out.println("通过 " + pass + " 项断言，失败 " + failures.size() + " 项");
        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("  × " + f);
            System.out.println("结果: FAIL");
            System.exit(1);
        }
        System.out.println("结果: PASS");
    }

    /** 全新安装：连旧目录都没有 → NO_LEGACY_DIR，且新目录会被建出来。 */
    private static void testFreshInstall(Path cfg) throws IOException {
        section("case1 全新安装（无旧目录）");
        Files.createDirectories(cfg);
        check("无旧目录时返回 NO_LEGACY_DIR", ConfigDir.migrate(cfg) == ConfigDir.Result.NO_LEGACY_DIR);
        Path dir = ConfigDir.modDir(cfg);
        check("modDir 指向 config/" + ConfigDir.NAME, dir.equals(cfg.resolve(ConfigDir.NAME)));
        check("modDir 已建出目录", Files.isDirectory(dir));
    }

    /** 老用户升级：整个曲库（46 个谱面 + 存档 + 资源包 + 成绩）必须完整搬过去。 */
    private static void testMigrateFullLibrary(Path cfg) throws IOException {
        section("case2 老用户升级（旧曲库整体迁移）");
        Path oldDir = cfg.resolve(ConfigDir.LEGACY_NAME);
        Files.createDirectories(oldDir.resolve("songs"));
        Files.createDirectories(oldDir.resolve("respack"));
        Files.createDirectories(oldDir.resolve("_phi_work"));
        // 46 个谱面包（对应用户实际曲库规模）+ 1 张占位说明
        for (int i = 0; i < 46; i++) {
            Files.writeString(oldDir.resolve("songs").resolve("song" + i + ".pez"),
                    "PEZ-" + i, StandardCharsets.UTF_8);
        }
        Files.writeString(oldDir.resolve("songs").resolve("x.pez"), "PEZ-X", StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("songs").resolve("_把谱面包(.zip)放这里.txt"), "占位",
                StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("profile.json"), "{\"unlocked\":[\"a\",\"b\"],\"coins\":1234}",
                StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("records.tsv"), "id\tscore\nx\t1000000\n", StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("respack").resolve("official.zip"), "ZIPDATA",
                StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("_phi_work").resolve("tmp.bin"), "TMP", StandardCharsets.UTF_8);

        ConfigDir.Result r = ConfigDir.migrate(cfg);
        System.out.println("   迁移结果: " + r);
        check("返回 MIGRATED_MOVE", r == ConfigDir.Result.MIGRATED_MOVE
                || r == ConfigDir.Result.MIGRATED_COPY);

        Path newDir = cfg.resolve(ConfigDir.NAME);
        check("config/" + ConfigDir.NAME + " 出现", Files.isDirectory(newDir));
        check("config/" + ConfigDir.NAME + "/songs/x.pez 出现",
                Files.isRegularFile(newDir.resolve("songs").resolve("x.pez")));
        check("x.pez 内容一致", "PEZ-X".equals(Files.readString(newDir.resolve("songs").resolve("x.pez"))));
        long pez = Files.list(newDir.resolve("songs"))
                .filter(p -> p.getFileName().toString().endsWith(".pez")).count();
        check("songs/ 里 47 个 .pez 全在（46 + x）", pez == 47);
        check("占位说明也在", Files.isRegularFile(newDir.resolve("songs").resolve("_把谱面包(.zip)放这里.txt")));
        check("profile.json 内容一致",
                Files.readString(newDir.resolve("profile.json")).contains("\"coins\":1234"));
        check("records.tsv 内容一致",
                Files.readString(newDir.resolve("records.tsv")).contains("1000000"));
        check("respack/official.zip 在", Files.isRegularFile(newDir.resolve("respack").resolve("official.zip")));
        check("_phi_work/tmp.bin 在", Files.isRegularFile(newDir.resolve("_phi_work").resolve("tmp.bin")));
        check("旧目录已消失（rename 语义）", !Files.exists(oldDir));
        check("写了迁移标记 " + ConfigDir.MARKER, Files.exists(newDir.resolve(ConfigDir.MARKER)));
    }

    /** 新旧目录都在（安装器先建了 config/<新名>/songs 并塞了内置谱面）→ 合并不覆盖。 */
    private static void testMergeWhenBothExist(Path cfg) throws IOException {
        section("case3 新旧目录都在（安装器先建了新目录）");
        Path oldDir = cfg.resolve(ConfigDir.LEGACY_NAME);
        Path newDir = cfg.resolve(ConfigDir.NAME);
        Files.createDirectories(oldDir.resolve("songs"));
        Files.createDirectories(newDir.resolve("songs"));
        Files.writeString(oldDir.resolve("songs").resolve("legacy.pez"), "LEGACY", StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("songs").resolve("same.pez"), "OLD-VERSION", StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("profile.json"), "{\"coins\":1}", StandardCharsets.UTF_8);
        Files.writeString(newDir.resolve("songs").resolve("mine.pez"), "MINE", StandardCharsets.UTF_8);
        Files.writeString(newDir.resolve("songs").resolve("same.pez"), "NEW-VERSION", StandardCharsets.UTF_8);

        check("返回 MERGED", ConfigDir.migrate(cfg) == ConfigDir.Result.MERGED);
        check("旧曲库的 legacy.pez 补进来了",
                "LEGACY".equals(Files.readString(newDir.resolve("songs").resolve("legacy.pez"))));
        check("新目录已有的 mine.pez 没被删",
                "MINE".equals(Files.readString(newDir.resolve("songs").resolve("mine.pez"))));
        check("同名文件保留新目录的版本（不覆盖）",
                "NEW-VERSION".equals(Files.readString(newDir.resolve("songs").resolve("same.pez"))));
        check("profile.json 也补进来了", Files.isRegularFile(newDir.resolve("profile.json")));
        check("旧目录仍在（用户数据不丢）",
                "LEGACY".equals(Files.readString(oldDir.resolve("songs").resolve("legacy.pez"))));
        check("写了合并标记", Files.exists(newDir.resolve(ConfigDir.MARKER)));
    }

    /** 打过标记后不再合并：用户在新目录里删掉的谱面不会被旧目录“复活”。 */
    private static void testNoResurrectionAfterMarker(Path cfg) throws IOException {
        section("case4 打过标记后不再合并（删掉的谱面不复活）");
        Path oldDir = cfg.resolve(ConfigDir.LEGACY_NAME);
        Path newDir = cfg.resolve(ConfigDir.NAME);
        Files.createDirectories(oldDir.resolve("songs"));
        Files.createDirectories(newDir.resolve("songs"));
        Files.writeString(oldDir.resolve("songs").resolve("keep.pez"), "KEEP", StandardCharsets.UTF_8);
        Files.writeString(oldDir.resolve("songs").resolve("deleted.pez"), "DELETED", StandardCharsets.UTF_8);
        ConfigDir.migrate(cfg);                       // 第一次：合并 + 打标记
        Files.deleteIfExists(newDir.resolve("songs").resolve("deleted.pez"));
        check("用户删掉之后确实没了", !Files.exists(newDir.resolve("songs").resolve("deleted.pez")));
        check("再次调用返回 ALREADY_NEW", ConfigDir.migrate(cfg) == ConfigDir.Result.ALREADY_NEW);
        check("删掉的谱面没有被“复活”", !Files.exists(newDir.resolve("songs").resolve("deleted.pez")));
    }

    /** 迁移后重复调用：幂等，不产生第二份。 */
    private static void testIdempotent(Path cfg) throws IOException {
        section("case5 重复调用幂等");
        Path oldDir = cfg.resolve(ConfigDir.LEGACY_NAME);
        Files.createDirectories(oldDir.resolve("songs"));
        Files.writeString(oldDir.resolve("songs").resolve("one.pez"), "ONE", StandardCharsets.UTF_8);
        ConfigDir.migrate(cfg);
        check("第一次之后新目录就位", Files.isRegularFile(
                cfg.resolve(ConfigDir.NAME).resolve("songs").resolve("one.pez")));
        check("第二次返回 ALREADY_NEW", ConfigDir.migrate(cfg) == ConfigDir.Result.ALREADY_NEW);
        check("谱面没被复制成两份", Files.list(cfg.resolve(ConfigDir.NAME).resolve("songs"))
                .filter(p -> p.getFileName().toString().equals("one.pez")).count() == 1);
    }

    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println("-- " + title);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("   √ " + what);
        } else {
            failures.add(what);
            System.out.println("   × " + what);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        });
    }
}
