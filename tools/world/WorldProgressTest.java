import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.dsh.phimc.client.ConfigDir;
import dev.dsh.phimc.client.Records;
import dev.dsh.phimc.client.WorldStore;
import dev.dsh.phimc.survival.Profile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「玩法进度按存档隔离」的 headless 自测（不碰 Minecraft）。
 *
 * <p>复现的 bug：解锁 / 成绩 / 商店 / 平板赠礼以前全在 {@code config/phimc/profile.json}，
 * 整个实例共用，换个存档就串。现在 A 类进度落 {@code saves/<世界名>/phimc/}，B 类偏好留
 * {@code config/phimc/}。
 *
 * <p>这个测试用临时目录<b>模拟两个世界</b>（{@code saves/WorldA}、{@code saves/WorldB}），
 * 调用顺序与 {@code Session.onWorldJoin / onWorldLeave} 完全一致：
 * <pre>
 *   join : profile.attachWorld(WorldStore.profileFile(saveRoot))
 *          records.open(WorldStore.recordsFile(saveRoot), migrated ? 旧 records.tsv : null)
 *   leave: profile.detachWorld(); records.open(null)
 * </pre>
 * 覆盖：① 世界 A 的进度落在 A 的目录；② 切到 B 后 A 的三首/成绩/赠礼都不在（B 是独立进度，
 * 赠礼能再领）；③ 切回 A 全都在；④ 全局偏好两个世界一致；⑤ 旧版全局进度只迁移一次。
 *
 * <p>编译与运行（仓库根目录）：
 * <pre>
 *   javac -encoding UTF-8 -d out-world -cp "lib\gson-2.11.0.jar;lib\fabric-loader-0.20.0.jar" ^
 *       core\src\main\java\dev\dsh\phimc\core\Judgement.java ^
 *       mod\src\main\java\dev\dsh\phimc\survival\Profile.java ^
 *       mod\src\main\java\dev\dsh\phimc\survival\PriceTable.java ^
 *       mod\src\main\java\dev\dsh\phimc\survival\Shop.java ^
 *       mod\src\main\java\dev\dsh\phimc\client\ConfigDir.java ^
 *       mod\src\main\java\dev\dsh\phimc\client\WorldStore.java ^
 *       mod\src\main\java\dev\dsh\phimc\client\Records.java ^
 *       tools\world\WorldProgressTest.java
 *   java -cp "out-world;lib\gson-2.11.0.jar" WorldProgressTest [沙箱根目录]
 * </pre>
 * 退出码 0 = 全部通过。
 */
public final class WorldProgressTest {

    private static int pass;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path sandbox = args.length > 0
                ? Paths.get(args[0])
                : Paths.get(System.getProperty("java.io.tmpdir"), "phimc-world-progress-test");
        if (Files.exists(sandbox)) deleteTree(sandbox);
        Files.createDirectories(sandbox);
        System.out.println("== 沙箱: " + sandbox.toAbsolutePath() + " ==");
        System.out.println("   世界进度目录 = saves/<世界名>/" + WorldStore.DIR + "/");
        System.out.println();

        twoWorlds(sandbox.resolve("case1-two-worlds"));
        legacyMigration(sandbox.resolve("case2-legacy-migration"));

        System.out.println();
        System.out.println(pass + " 通过, " + failures.size() + " 失败");
        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // ① ② ③ ④ 两个世界来回切
    // ------------------------------------------------------------------

    private static void twoWorlds(Path instance) throws Exception {
        section("① 世界 A：解锁 3 首 + 成绩 + 平板赠礼 → 落在 A 的目录");
        Path configDir = instance.resolve("config").resolve(ConfigDir.NAME);
        Path saves = instance.resolve("saves");
        Files.createDirectories(configDir);
        Path worldA = saves.resolve("WorldA");
        Path worldB = saves.resolve("WorldB");

        Client c = new Client(configDir);
        check(!c.profile.hasWorld(), "还没进世界：hasWorld() = false");
        check(!Files.exists(configDir.resolve("profile.json")), "构造 Profile 不写盘（只读兜底）");

        check(c.join(worldA) == false, "进 A：没有旧进度可迁移 → attachWorld 返回 false");
        c.profile.ensureWorldSeed(777L);
        check(c.profile.unlock("Verruckt#EZ") && c.profile.unlock("Verruckt#HD")
                && c.profile.unlock("Verruckt#IN"), "A 解锁 3 首");
        c.profile.record("Verruckt#IN", 950_000, 97.54, false, 800);
        c.profile.record("Verruckt#HD", 990_000, 99.51, true, 900);
        c.records.submit("a.zip", new Records.Rec(950_000, 0.9754, 800, 1200, false));
        check(c.profile.claimTabletGift(), "A 领到平板赠礼");

        Path aProfile = WorldStore.profileFile(worldA);
        Path aRecords = WorldStore.recordsFile(worldA);
        check(Files.isRegularFile(aProfile), "saves/WorldA/phimc/profile.json 已生成: " + rel(instance, aProfile));
        check(Files.isRegularFile(aRecords), "saves/WorldA/phimc/records.tsv 已生成: " + rel(instance, aRecords));
        String aJson = Files.readString(aProfile, StandardCharsets.UTF_8);
        check(aJson.contains("Verruckt#EZ") && aJson.contains("Verruckt#IN"), "A 文件里有 3 首解锁");
        check(aJson.contains("\"worldSeed\": 777"), "A 文件里有本世界的 worldSeed 777");
        check(aJson.contains("\"tabletGiftGiven\": true"), "A 文件里赠礼标记为 true");
        check(!aJson.contains("offsetMs"), "A 文件里没有 B 类偏好（offsetMs 属全局）");
        String aRecordsText = Files.readString(aRecords, StandardCharsets.UTF_8);
        check(aRecordsText.contains("a.zip") && aRecordsText.contains("950000"), "A 的 records.tsv 有 a.zip 950000");

        section("④ 全局偏好（B 类）写进 config/phimc/profile.json");
        c.profile.offsetMs(50);
        Path global = configDir.resolve("profile.json");
        check(Files.isRegularFile(global), "config/phimc/profile.json 已生成");
        String globalJson = Files.readString(global, StandardCharsets.UTF_8);
        check(globalJson.contains("\"offsetMs\": 50.0"), "全局文件里有 offsetMs = 50");
        check(!globalJson.contains("\"unlocked\""), "全局文件里没有解锁（A 类不进全局）");
        check(!globalJson.contains("Verruckt"), "全局文件里没有谱面 id");
        check(Math.abs(c.profile.hitSoundVolume() - 0.35) < 1e-9, "A 里 hitSoundVolume = 0.35");

        byte[] aSnapshot = Files.readAllBytes(aProfile);

        section("② 切到世界 B：进度独立（解锁空 / 无成绩 / 赠礼可再领）");
        c.leave();
        check(!c.profile.hasWorld(), "退世界后 hasWorld() = false");
        check(c.profile.unlockedCount() == 0, "退世界后内存里解锁已清空（不会带进下一个世界）");
        check(c.records.get("a.zip") == null, "退世界后成绩已清空");
        c.join(worldB);
        check(c.profile.unlockedCount() == 0, "B：解锁 0 首（A 的 3 首没有串过来）");
        check(!c.profile.isUnlocked("Verruckt#IN"), "B：Verruckt#IN 是锁着的");
        check(c.profile.records().isEmpty(), "B：没有成绩");
        check(c.records.get("a.zip") == null, "B：records.tsv 里没有 A 的成绩");
        check(c.profile.record("Verruckt#IN") == null, "B：Verruckt#IN 没有最佳成绩");
        check(!c.profile.tabletGiftGiven(), "B：赠礼标记是 false");
        check(c.profile.claimTabletGift(), "B：赠礼可以再领一次（B 是独立进度）");
        check(c.profile.unlock("Spasmodic#AT"), "B：解锁一首自己的谱面");
        check(Math.abs(c.profile.offsetMs() - 50) < 1e-9, "B：offsetMs 仍是 50（全局偏好跟着玩家）");
        check(Math.abs(c.profile.hitSoundVolume() - 0.35) < 1e-9, "B：hitSoundVolume 仍是 0.35");
        String bJson = Files.readString(WorldStore.profileFile(worldB), StandardCharsets.UTF_8);
        check(bJson.contains("Spasmodic#AT") && !bJson.contains("Verruckt"), "B 文件里只有 B 自己的解锁");
        check(!bJson.contains("offsetMs"), "B 文件里没有 B 类偏好");

        section("③ 切回世界 A：3 首解锁 / 成绩 / 赠礼都在");
        c.leave();
        c.join(worldA);
        check(c.profile.unlockedCount() == 3, "A：解锁数回到 3");
        check(c.profile.isUnlocked("Verruckt#EZ") && c.profile.isUnlocked("Verruckt#HD")
                && c.profile.isUnlocked("Verruckt#IN"), "A：三首逐首都在");
        check(!c.profile.isUnlocked("Spasmodic#AT"), "A：B 解锁的 Spasmodic#AT 没有串过来");
        Profile.Score s = c.profile.record("Verruckt#IN");
        check(s != null && s.score() == 950_000 && Math.abs(s.acc() - 97.54) < 1e-9,
                "A：Verruckt#IN 成绩 950000 / 97.54% 原样还在: " + s);
        check(c.profile.worldSeed() == 777L, "A：worldSeed 仍是 777");
        check(c.profile.tabletGiftGiven(), "A：赠礼标记仍是 true");
        Records.Rec rec = c.records.get("a.zip");
        check(rec != null && rec.score() == 950_000, "A：records.tsv 里 a.zip 还在");
        check(java.util.Arrays.equals(aSnapshot, Files.readAllBytes(aProfile)),
                "A 的文件内容与离开时逐字节一致（B 的活动没碰到 A）");

        // 重登（新对象）也要读到同样的东西
        Client fresh = new Client(configDir);
        check(Math.abs(fresh.profile.offsetMs() - 50) < 1e-9, "重启后全局偏好 offsetMs = 50");
        fresh.join(worldA);
        check(fresh.profile.unlockedCount() == 3, "重启 + 进 A：解锁仍是 3");
        check(fresh.records.get("a.zip") != null, "重启 + 进 A：records.tsv 仍读到 a.zip");
        fresh.leave();
        fresh.join(worldB);
        check(fresh.profile.unlockedCount() == 1 && fresh.profile.isUnlocked("Spasmodic#AT"),
                "重启 + 进 B：仍是 B 自己的 1 首");
        fresh.leave();

        System.out.println();
        System.out.println("  文件树：");
        tree(instance, instance);
    }

    // ------------------------------------------------------------------
    // ⑤ 旧版全局进度迁移（只迁一次）
    // ------------------------------------------------------------------

    private static void legacyMigration(Path instance) throws Exception {
        section("⑤ 迁移：旧版全局 profile.json 的 A 类字段 → 第一个进入的世界");
        Path configDir = instance.resolve("config").resolve(ConfigDir.NAME);
        Path saves = instance.resolve("saves");
        Files.createDirectories(configDir);
        Path worldC = saves.resolve("WorldC");
        Path worldD = saves.resolve("WorldD");

        // 造一份「旧版本」的单文件存档：A + B 混在一起，没有 progressMigrated
        String legacy = "{\n"
                + "  \"worldSeed\": 88172645463325252,\n"
                + "  \"unlocked\": [\"Legacy#EZ\", \"Legacy#HD\"],\n"
                + "  \"records\": { \"Legacy#IN\": { \"score\": 880000, \"acc\": 88.0, \"fc\": false, \"combo\": 500 } },\n"
                + "  \"shopSeed\": 456,\n"
                + "  \"shopRefreshAt\": 789,\n"
                + "  \"tabletGiftGiven\": true,\n"
                + "  \"useKeyboard\": false,\n"
                + "  \"autoplay\": false,\n"
                + "  \"hitSound\": false,\n"
                + "  \"hitSoundVolume\": 0.35,\n"
                + "  \"resPack\": \"\",\n"
                + "  \"offsetMs\": 50.0,\n"
                + "  \"scrollSpeed\": 1.0\n"
                + "}\n";
        Files.writeString(configDir.resolve("profile.json"), legacy, StandardCharsets.UTF_8);
        Files.writeString(configDir.resolve("records.tsv"),
                "legacy.zip\t880000\t0.880000\t500\t1200\t0\n", StandardCharsets.UTF_8);

        Client c = new Client(configDir);
        check(!c.profile.hasWorld(), "没进世界：仍按旧版单文件兜底读");
        check(c.profile.isUnlocked("Legacy#EZ") && c.profile.worldSeed() == 88172645463325252L,
                "没进世界时能读到旧进度（只读兜底）");
        check(!Files.exists(configDir.resolve("saves")), "没进世界时不会建任何 saves 目录");

        boolean migrated = c.join(worldC);
        check(migrated, "进 WorldC 触发迁移（attachWorld 返回 true）");
        Path cProfile = WorldStore.profileFile(worldC);
        Path cRecords = WorldStore.recordsFile(worldC);
        check(Files.isRegularFile(cProfile), "saves/WorldC/phimc/profile.json 已生成");
        String cJson = Files.readString(cProfile, StandardCharsets.UTF_8);
        check(cJson.contains("Legacy#EZ") && cJson.contains("Legacy#HD"), "C 文件里迁到了 2 首解锁");
        check(cJson.contains("\"worldSeed\": 88172645463325252"), "C 文件里有旧 worldSeed");
        check(cJson.contains("\"shopSeed\": 456") && cJson.contains("\"shopRefreshAt\": 789"),
                "C 文件里有旧货架种子 / 刷新时间");
        check(cJson.contains("\"tabletGiftGiven\": true"), "C 文件里有旧赠礼标记");
        check(cJson.contains("Legacy#IN"), "C 文件里有旧成绩");
        check(!cJson.contains("offsetMs"), "C 文件里没有 B 类偏好");
        check(Files.isRegularFile(cRecords), "旧 records.tsv 被播种成 WorldC/phimc/records.tsv");
        check(c.records.get("legacy.zip") != null, "WorldC 的成绩里读到 legacy.zip");

        String global = Files.readString(configDir.resolve("profile.json"), StandardCharsets.UTF_8);
        System.out.println("  --- 迁移后的全局 profile.json ---");
        for (String line : global.split("\\R")) System.out.println("    " + line);
        check(!global.contains("\"unlocked\"") && !global.contains("Legacy#"), "全局文件已不含 A 类字段");
        check(!global.contains("\"tabletGiftGiven\"") && !global.contains("\"shopSeed\""),
                "全局文件已不含赠礼 / 货架字段");
        check(global.contains("\"progressMigrated\": true"), "全局文件里有迁移标记 progressMigrated: true");
        check(global.contains("\"offsetMs\": 50.0") && global.contains("\"hitSound\": false"),
                "全局文件保留 B 类偏好（offsetMs 50 / hitSound false）");
        check(Math.abs(c.profile.offsetMs() - 50) < 1e-9, "迁移后内存里的 offsetMs 仍是 50");

        section("⑤ 迁移只做一次：新世界 WorldD 不继承旧进度");
        c.leave();
        check(c.join(worldD) == false, "进 WorldD 不再迁移（attachWorld 返回 false）");
        check(c.profile.unlockedCount() == 0, "WorldD：解锁 0 首（旧进度没被复制第二份）");
        check(c.profile.records().isEmpty(), "WorldD：没有成绩");
        check(!c.profile.tabletGiftGiven(), "WorldD：赠礼标记 false（可以正常领一次）");
        check(!Files.exists(WorldStore.recordsFile(worldD)), "WorldD 没有从旧 records.tsv 播种");

        section("⑤ 切回 WorldC：迁移过去的进度原样还在");
        c.leave();
        c.join(worldC);
        check(c.profile.unlockedCount() == 2, "WorldC：解锁仍是 2 首");
        check(c.profile.worldSeed() == 88172645463325252L, "WorldC：worldSeed 仍是旧值");
        check(c.records.get("legacy.zip") != null, "WorldC：成绩仍在");
        c.leave();

        System.out.println();
        System.out.println("  文件树：");
        tree(instance, instance);
    }

    // ------------------------------------------------------------------
    // 模拟客户端（与 Session.onWorldJoin / onWorldLeave 同一套调用顺序）
    // ------------------------------------------------------------------

    private static final class Client {
        final Path configDir;
        final Profile profile;
        final Records records;

        Client(Path configDir) {
            this.configDir = configDir;
            this.profile = new Profile(configDir.resolve("profile.json"));
            this.records = new Records(null);
        }

        /** = Session.onWorldJoin：切世界进度 + （迁移时）用旧 records.tsv 播种。 */
        boolean join(Path saveRoot) {
            boolean migrated = profile.attachWorld(WorldStore.profileFile(saveRoot));
            records.open(WorldStore.recordsFile(saveRoot),
                    migrated ? configDir.resolve("records.tsv") : null);
            return migrated;
        }

        /** = Session.onWorldLeave. */
        void leave() {
            profile.detachWorld();
            records.open(null);
        }
    }

    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            failures.add(what);
            System.out.println("  ✗ " + what);
        }
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static void tree(Path root, Path dir) throws Exception {
        List<Path> all = new ArrayList<>();
        try (var s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).forEach(all::add);
        }
        for (Path p : all) {
            String text = "";
            if (p.getFileName().toString().endsWith(".json")) {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                if (JsonParser.parseString(json).isJsonObject()) {
                    var o = JsonParser.parseString(json).getAsJsonObject();
                    List<String> keys = new ArrayList<>(o.keySet());
                    text = "  keys=" + keys;
                }
            }
            System.out.printf("    %-52s %6d B%s%n", rel(root, p), Files.size(p), text);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        List<Path> all = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(all::add);
        }
        for (Path p : all) Files.deleteIfExists(p);
    }

    @SuppressWarnings("unused")
    private static String pretty(Object o) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }
}
