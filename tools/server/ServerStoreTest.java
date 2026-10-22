import dev.dsh.phimc.client.ConfigDir;
import dev.dsh.phimc.client.Records;
import dev.dsh.phimc.client.ServerStore;
import dev.dsh.phimc.survival.Profile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「多人成绩按服务器落盘」的 headless 自测（<b>不碰 Minecraft</b>）。
 *
 * <p>复现的问题：单人进度跟着世界存档走（{@code saves/<世界名>/phimc/}），但多人服务器
 * 没有本地存档（{@code MinecraftClient.getServer() == null}），以前这种情况直接退化成
 * 「只读、不落盘」—— 多人打歌的成绩一退就没了。
 *
 * <p>这个测试<b>模拟客户端地址</b>（不做任何网络请求），走一遍
 * {@code WorldHooks.serverAddress()} 拿到的那个字符串 → {@link ServerStore} 的目录映射，
 * 再按 {@code Session.onServerJoin} 的调用顺序断言文件真的落在
 * {@code config/phimc/servers/<地址>/} 下。
 *
 * <p>覆盖：
 * <ol>
 *   <li>地址 → 目录名：{@code :} / {@code /} 都变成 {@code _}；unix 的 {@code /1.2.3.4:25565} 形式也是；</li>
 *   <li>两个服务器互不干扰（各写各的 {@code profile.json} / {@code records.tsv}）；</li>
 *   <li>同一服务器重进读到自己的成绩；</li>
 *   <li>旧版单文件进度<b>不会</b>被服务器认领（{@code attachWorld(...,false)} 的语义）；</li>
 *   <li>取不到地址时（打歌 only 的主菜单兜底）落 {@code servers/local/}；</li>
 *   <li>完整版语义不变：取不到地址时仍然「只读、不写盘」。</li>
 * </ol>
 *
 * <p>编译与运行（仓库根目录）：
 * <pre>
 *   javac -encoding UTF-8 -d out-server -cp "lib\gson-2.11.0.jar" ^
 *       core\src\main\java\dev\dsh\phimc\core\Judgement.java ^
 *       mod\src\main\java\dev\dsh\phimc\survival\Profile.java ^
 *       mod\src\main\java\dev\dsh\phimc\survival\PriceTable.java ^
 *       mod\src\main\java\dev\dsh\phimc\survival\Shop.java ^
 *       mod\src\main\java\dev\dsh\phimc\client\ConfigDir.java ^
 *       mod\src\main\java\dev\dsh\phimc\client\ServerStore.java ^
 *       mod\src\main\java\dev\dsh\phimc\client\Records.java ^
 *       tools\server\ServerStoreTest.java
 *   java -cp "out-server;lib\gson-2.11.0.jar" ServerStoreTest [沙箱根目录]
 * </pre>
 * 退出码 0 = 全部通过。
 */
public final class ServerStoreTest {

    private static int pass;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path sandbox = args.length > 0
                ? Paths.get(args[0])
                : Paths.get(System.getProperty("java.io.tmpdir"), "phimc-server-store-test");
        if (Files.exists(sandbox)) deleteTree(sandbox);
        Files.createDirectories(sandbox);
        System.out.println("== 沙箱: " + sandbox.toAbsolutePath() + " ==");
        System.out.println("   多人成绩目录 = config/" + ConfigDir.NAME + "/" + ServerStore.DIR + "/<地址>/");
        System.out.println();

        nameMapping();
        twoServers(sandbox.resolve("case2-two-servers"));
        noAddress(sandbox.resolve("case3-no-address"));

        System.out.println();
        System.out.println(pass + " 通过, " + failures.size() + " 失败");
        if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // ① 地址 → 目录名（需求点名的 `:` `/` → `_`）
    // ------------------------------------------------------------------

    private static void nameMapping() {
        section("① 地址 → 文件名安全串（`:` 与 `/` → `_`）");

        check("127.0.0.1_25565".equals(ServerStore.safeName("127.0.0.1:25565")),
                "127.0.0.1:25565 → 127.0.0.1_25565");
        check("play.example.com_25565".equals(ServerStore.safeName("play.example.com:25565")),
                "play.example.com:25565 → play.example.com_25565");
        // getAddress().toString() 在 unix 上是 "/1.2.3.4:25565"，在某些实现上是 "host/1.2.3.4:25565"
        check("_1.2.3.4_25565".equals(ServerStore.safeName("/1.2.3.4:25565")),
                "/1.2.3.4:25565 → _1.2.3.4_25565（getAddress().toString() 的形态）");
        check("mc.example.com_1.2.3.4_25565".equals(ServerStore.safeName("mc.example.com/1.2.3.4:25565")),
                "mc.example.com/1.2.3.4:25565 → mc.example.com_1.2.3.4_25565");
        // Windows 非法字符全换掉
        check("a_b_c_d_e_f_g_h_i".equals(ServerStore.safeName("a/b:c*d?e\"f<g>h|i")),
                "`/ : * ? \" < > |` 全部 → _");
        check(ServerStore.LOCAL.equals(ServerStore.safeName(null)), "null → " + ServerStore.LOCAL);
        check(ServerStore.LOCAL.equals(ServerStore.safeName("   ")), "空白 → " + ServerStore.LOCAL);
        // 非空但全是非法字符：依旧是「每个字符换一个 _」，不会退化成空串（目录名必须稳定可复现）
        check("___".equals(ServerStore.safeName("///")), "/// → ___（逐字符替换，不塌缩）");
        // Windows 保留设备名
        check("_con".equals(ServerStore.safeName("con")), "Windows 保留名 con → _con");
        check("_NUL".equals(ServerStore.safeName("NUL")), "Windows 保留名 NUL → _NUL（大小写保留）");
        // 幂等 + 长度
        String once = ServerStore.safeName("hypixel.net:25565");
        check(ServerStore.safeName(once).equals(once), "幂等：safeName(safeName(x)) == safeName(x)");
        String longAddr = "x".repeat(400) + ":25565";
        check(ServerStore.safeName(longAddr).length() <= 96, "超长地址被截断到 ≤96 字符");
    }

    // ------------------------------------------------------------------
    // ② 两个服务器 + 重登 + 不认领旧进度
    // ------------------------------------------------------------------

    private static void twoServers(Path instance) throws Exception {
        section("② 模拟两个服务器：各写各的 config/phimc/servers/<地址>/");
        Path modDir = instance.resolve("config").resolve(ConfigDir.NAME);
        Files.createDirectories(modDir);

        // 旧版单文件进度：用来断言「服务器不会把它认领走」
        Files.writeString(modDir.resolve("profile.json"),
                "{\"unlocked\":[\"Legacy#EZ\"],\"worldSeed\":123}\n", StandardCharsets.UTF_8);
        Files.writeString(modDir.resolve("records.tsv"),
                "legacy.zip\t900000\t0.900000\t700\t1200\t0\n", StandardCharsets.UTF_8);

        String addrA = "play.example.com:25565";     // = getAddress() 拼出来的 host:port
        String addrB = "127.0.0.1:25566";

        Client a = new Client(modDir);
        a.joinServer(addrA);
        a.profile.record("FreedomDive#IN", 980_000, 98.12, true, 1234);
        a.records.submit("freedomdive.pez", new Records.Rec(980_000, 0.9812, 1234, 1250, true));

        Path aDir = ServerStore.serverDir(modDir, addrA);
        Path aProfile = ServerStore.profileFile(modDir, addrA);
        Path aRecords = ServerStore.recordsFile(modDir, addrA);
        check(aDir.equals(modDir.resolve(ServerStore.DIR).resolve("play.example.com_25565")),
                "目录 = config/phimc/servers/play.example.com_25565: " + rel(instance, aDir));
        check(Files.isRegularFile(aProfile), "profile.json 已生成: " + rel(instance, aProfile));
        check(Files.isRegularFile(aRecords), "records.tsv 已生成: " + rel(instance, aRecords));
        String aJson = Files.readString(aProfile, StandardCharsets.UTF_8);
        check(aJson.contains("FreedomDive#IN"), "A 服务器 profile.json 里有这次解锁 / 成绩");
        check(!aJson.contains("Legacy#EZ"), "A 服务器**没有**认领旧版单文件进度（没有 Legacy#EZ）");
        String aTsv = Files.readString(aRecords, StandardCharsets.UTF_8);
        check(aTsv.contains("freedomdive.pez") && aTsv.contains("980000"), "A 服务器 records.tsv 有本局成绩");
        check(!aTsv.contains("legacy.zip"), "A 服务器 records.tsv 没有旧成绩（没被播种）");
        check(Files.isRegularFile(modDir.resolve("profile.json")), "全局 config/phimc/profile.json 原样还在");

        byte[] aSnapshot = Files.readAllBytes(aProfile);

        section("② 第二个服务器：完全独立");
        a.leave();
        Client b = new Client(modDir);
        b.joinServer(addrB);
        check(b.profile.unlockedCount() == 0, "B 服务器：解锁 0 首（A 的没有串过来）");
        check(b.records.get("freedomdive.pez") == null, "B 服务器：没有 A 的成绩");
        b.profile.record("Spasmodic#AT", 910_000, 91.0, false, 800);
        b.records.submit("spasmodic.zip", new Records.Rec(910_000, 0.9100, 800, 1000, false));
        Path bDir = ServerStore.serverDir(modDir, addrB);
        check(Files.isRegularFile(ServerStore.profileFile(modDir, addrB)), "B 服务器 profile.json 已生成");
        check(!ServerStore.profileFile(modDir, addrB).equals(aProfile), "B 的落点和 A 不同: " + rel(instance, bDir));
        check(java.util.Arrays.equals(aSnapshot, Files.readAllBytes(aProfile)),
                "B 的活动没碰到 A 的文件（逐字节一致）");
        // 全局文件也没被服务器写盘冲掉 A 类字段（迁移标记仍是 false）
        String global = Files.readString(modDir.resolve("profile.json"), StandardCharsets.UTF_8);
        check(global.contains("Legacy#EZ"), "全局文件里的旧 A 类字段没被服务器目录吃掉");

        section("② 重登同一服务器：读到自己的成绩");
        Client again = new Client(modDir);
        again.joinServer(addrA);
        Profile.Score s = again.profile.record("FreedomDive#IN");
        check(s != null && s.score() == 980_000, "A 服务器重进：profile.json 里成绩还是 980000");
        Records.Rec rec = again.records.get("freedomdive.pez");
        check(rec != null && rec.score() == 980_000, "A 服务器重进：records.tsv 里成绩还在");
        check(again.profile.record("Spasmodic#AT") == null, "A 服务器里没有 B 的成绩（互不干扰）");
        again.leave();
    }

    // ------------------------------------------------------------------
    // ③ 取不到地址：lite 落 local/，完整版仍然只读
    // ------------------------------------------------------------------

    private static void noAddress(Path instance) throws Exception {
        section("③ 取不到服务器地址（打歌 only 的主菜单兜底 → servers/local/）");
        Path modDir = instance.resolve("config").resolve(ConfigDir.NAME);
        Files.createDirectories(modDir);

        Client lite = new Client(modDir);
        lite.joinServer(null);
        lite.records.submit("main-menu.pez", new Records.Rec(950_000, 0.95, 900, 1000, false));
        Path localDir = modDir.resolve(ServerStore.DIR).resolve(ServerStore.LOCAL);
        check(localDir.equals(ServerStore.serverDir(modDir, null)), "目录 = config/phimc/servers/local");
        check(Files.isRegularFile(localDir.resolve("records.tsv")),
                "local/records.tsv 已生成: " + rel(instance, localDir.resolve("records.tsv")));
        check(Files.readString(localDir.resolve("records.tsv"), StandardCharsets.UTF_8).contains("main-menu.pez"),
                "local/records.tsv 里有主菜单那一局的成绩");

        section("③ 完整版语义不变：没有世界也没有地址 → 只读、不写盘");
        Path modDir2 = instance.resolve("config2").resolve(ConfigDir.NAME);
        Files.createDirectories(modDir2);
        Client full = new Client(modDir2);
        full.onlyMode = false;
        full.joinServer(null);
        full.records.submit("nope.pez", new Records.Rec(1, 0.0, 0, 1, false));
        check(!Files.exists(modDir2.resolve(ServerStore.DIR).resolve(ServerStore.LOCAL).resolve("records.tsv")),
                "完整版没有生成 local/records.tsv（口径：只读兜底）");
        check(!Files.exists(modDir2.resolve("profile.json")), "完整版没有生成 profile.json");

        System.out.println();
        System.out.println("  文件树：");
        tree(instance, instance);
    }

    // ------------------------------------------------------------------
    // 模拟客户端（= Session.onServerJoin / onWorldLeave 的同一套调用顺序）
    // ------------------------------------------------------------------

    private static final class Client {
        final Path modDir;
        final Profile profile;
        final Records records;
        /** 对应 PhiMC.onlyMode；本测试不 import 那个类（它只依赖 MC 之外的东西，但保持零耦合）。 */
        boolean onlyMode = true;

        Client(Path modDir) {
            this.modDir = modDir;
            this.profile = new Profile(modDir.resolve("profile.json"));
            this.records = new Records(null);
        }

        /** = Session.onServerJoin(address)：服务器目录（地址为 null 时按 onlyMode 决定 local 或只读）。 */
        void joinServer(String address) {
            if (address == null || address.isBlank()) {
                if (onlyMode) {
                    profile.attachWorld(ServerStore.profileFile(modDir, null), false);
                    records.open(ServerStore.recordsFile(modDir, null), null);
                    return;
                }
                profile.detachWorld();
                records.open(null);
                return;
            }
            profile.attachWorld(ServerStore.profileFile(modDir, address), false);
            records.open(ServerStore.recordsFile(modDir, address), null);
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
            System.out.printf("    %-64s %6d B%n", rel(root, p), Files.size(p));
        }
    }

    private static void deleteTree(Path root) throws Exception {
        List<Path> all = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(all::add);
        }
        for (Path p : all) Files.deleteIfExists(p);
    }
}
