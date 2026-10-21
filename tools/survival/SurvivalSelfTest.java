package dev.dsh.phimc.survival;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 生存玩法（S2）纯 Java 自测：{@link PriceTable} + {@link Profile} + {@link Shop}。
 *
 * <p>不依赖 Minecraft：直接
 * <pre>
 * javac -encoding UTF-8 -d out -cp lib/gson-2.11.0.jar \
 *       core/src/main/java/dev/dsh/phimc/core/Judgement.java \
 *       mod/src/main/java/dev/dsh/phimc/survival/{PriceTable,Profile,Shop}.java \
 *       tools/survival/SurvivalSelfTest.java
 * java -cp "out;lib/gson-2.11.0.jar" dev.dsh.phimc.survival.SurvivalSelfTest
 * </pre>
 */
public final class SurvivalSelfTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "C:\\dsh-work\\survival-selftest");
        Files.createDirectories(work);
        priceTableTests();
        profileTests(work.resolve("profile.json"));
        shopTests();
        System.out.println();
        System.out.println(failed == 0 ? "=== 全部通过 ===" : "=== 失败 " + failed + " 项 ===");
        if (failed != 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void priceTableTests() {
        long worldSeed = 88172645463325252L;
        section("S2-1 PriceTable.of(chartId, worldSeed=" + worldSeed + ")");
        String[] ids = {
                "Verruckt#EZ",
                "Verruckt#HD",
                "Verruckt#IN",
                "Verruckt#AT",
                // 真实谱包 info.txt 写的是 "Level: IN Lv.?"，规范化后是 IN；
                // 这里额外验证「未规范化」的难度串也能落进 IN 档
                "玩具狂奏曲終焉#IN Lv.?"
        };
        for (String id : ids) {
            PriceTable.Price p = PriceTable.of(id, worldSeed);
            System.out.printf("  %-28s tier=%-10s hash=%016x  ->  %s x%d%n",
                    id, PriceTable.tierOfChartId(id), PriceTable.hash(id, worldSeed), p.itemId(), p.count());
        }

        // 稳定性：同一 chartId 反复算 5 次必须完全一致
        boolean stable = true;
        for (String id : ids) {
            PriceTable.Price first = PriceTable.of(id, worldSeed);
            for (int i = 0; i < 5; i++) {
                PriceTable.Price again = PriceTable.of(id, worldSeed);
                if (!again.equals(first)) {
                    stable = false;
                    System.out.println("  ✗ 不稳定: " + id + " " + first + " vs " + again);
                }
            }
        }
        check(stable, "同 chartId + 同 worldSeed 结果稳定（各算 6 次）");

        // 同曲不同难度互不相关（无折扣、无关联）
        PriceTable.Price ez = PriceTable.of("Verruckt#EZ", worldSeed);
        PriceTable.Price hd = PriceTable.of("Verruckt#HD", worldSeed);
        PriceTable.Price in = PriceTable.of("Verruckt#IN", worldSeed);
        PriceTable.Price at = PriceTable.of("Verruckt#AT", worldSeed);
        System.out.printf("  同曲四难度: EZ=%s HD=%s IN=%s AT=%s%n", ez, hd, in, at);
        check(PriceTable.hash("Verruckt#EZ", worldSeed) != PriceTable.hash("Verruckt#IN", worldSeed),
                "同曲不同难度的种子不同");
        check(!(ez.equals(hd) && hd.equals(in) && in.equals(at)), "同曲不同难度不是同一个价格");
        check(poolContains("Verruckt#EZ", ez, worldSeed) && poolContains("Verruckt#AT", at, worldSeed),
                "价格物品落在对应难度的池里");

        // 跨世界：不同 worldSeed 应该给出不同价格（允许极少数碰撞，统计 200 个谱面）
        int diff = 0;
        for (int i = 0; i < 200; i++) {
            String id = "Song" + i + "#IN";
            if (!PriceTable.of(id, worldSeed).equals(PriceTable.of(id, worldSeed + 1))) diff++;
        }
        System.out.printf("  worldSeed 变化影响: 200 个随机谱面里 %d 个价格变了%n", diff);
        check(diff > 100, "worldSeed 参与定价（>50% 变化）");

        // 数量范围（每档 500 个样本）
        for (PriceTable.Tier tier : PriceTable.Tier.values()) {
            int[] range = PriceTable.COUNT_RANGES[tier.ordinal()];
            boolean ok = true;
            for (int i = 0; i < 500; i++) {
                PriceTable.Price p = PriceTable.of("RangeSong" + i + "#X", worldSeed + i, tier);
                if (p.count() < range[0] || p.count() > range[1]) ok = false;
                boolean inPool = false;
                for (String itemId : PriceTable.POOLS[tier.ordinal()]) {
                    if (itemId.equals(p.itemId())) inPool = true;
                }
                if (!inPool) ok = false;
            }
            check(ok, tier + " 档：物品与数量都落在 [" + range[0] + ".." + range[1] + "] 池内");
        }

        // 难度解析：真实谱包的 "IN Lv.?" / 文件名
        System.out.println("  难度解析: 'IN Lv.?' -> " + PriceTable.tierOfDifficulty("IN Lv.?")
                + " / 'AT Lv.16' -> " + PriceTable.tierOfDifficulty("AT Lv.16")
                + " / 'HD' -> " + PriceTable.tierOfDifficulty("HD")
                + " / '' -> " + PriceTable.tierOfDifficulty(""));
        check(PriceTable.tierOfDifficulty("IN Lv.?") == PriceTable.Tier.IN, "'IN Lv.?' 判为 IN 档");
        check(PriceTable.tierOfDifficulty("AT Lv.16") == PriceTable.Tier.AT, "'AT Lv.16' 判为 AT 档");
        check(PriceTable.tierOfDifficulty("HD") == PriceTable.Tier.EASY_HARD, "'HD' 判为 EZ/HD 档");
    }

    /**
     * 价格物品是否落在「定价时用的那个档位」的物品池里。
     *
     * <p>必须带 worldSeed：{@code PriceTable.of(id, worldSeed)} 的档位是
     * {@code tierOfChartId(id, worldSeed)} 现算的（价格与难度无关，由 chartId + worldSeed 决定），
     * 用 {@code tierOfChartId(id)}（= seed 0）去比必然对不上 —— 这条断言以前一直是误报。
     */
    private static boolean poolContains(String chartId, PriceTable.Price p, long worldSeed) {
        String[] pool = PriceTable.POOLS[PriceTable.tierOfChartId(chartId, worldSeed).ordinal()];
        for (String id : pool) if (id.equals(p.itemId())) return true;
        return false;
    }

    // ------------------------------------------------------------------

    private static void profileTests(Path file) throws Exception {
        Files.deleteIfExists(file);
        // 玩法进度（A 类）现在按世界隔离：这里模拟「已经进了某个世界」，
        // 全局文件只放客户端偏好（B 类）。不 attachWorld 的话 A 类是只读的（见 Profile 类注释）。
        Path world = file.resolveSibling("world").resolve("phimc").resolve("profile.json");
        Files.deleteIfExists(world);
        section("S2-2 Profile 读写（全局 " + file + " + 世界 " + world + "）");

        Profile p = new Profile(file);
        p.attachWorld(world);
        check(p.worldSeed() == 0 && p.unlockedCount() == 0, "空存档：worldSeed=0 / 解锁 0");

        check(p.ensureWorldSeed(88172645463325252L), "首次 ensureWorldSeed 写入");
        check(!p.ensureWorldSeed(42L), "第二次 ensureWorldSeed 不覆盖");
        check(p.worldSeed() == 88172645463325252L, "worldSeed 保持为 88172645463325252");

        check(p.unlock("Verruckt#IN"), "解锁 Verruckt#IN");
        check(!p.unlock("Verruckt#IN"), "重复解锁返回 false");
        check(p.isUnlocked("Verruckt#IN"), "isUnlocked(Verruckt#IN) = true");
        check(!p.isUnlocked("Verruckt#AT"), "未解锁的谱面 isUnlocked = false");

        Profile.Score s1 = p.record("Verruckt#IN", 987300, 98.42, true, 1200);
        System.out.println("  首次成绩: " + s1);
        Profile.Score lower = p.record("Verruckt#IN", 500000, 60.0, false, 100);
        System.out.println("  低分提交后: " + lower);
        check(lower.score() == 987300, "低分不覆盖（仍是 987300）");
        check(lower.fc() && lower.combo() == 1200, "低分提交也没改 fc/combo");

        Profile.Score higher = p.record("Verruckt#IN", 995000, 99.10, false, 1300);
        System.out.println("  高分提交后: " + higher);
        check(higher.score() == 995000 && higher.acc() == 99.10 && !higher.fc() && higher.combo() == 1300,
                "更高分覆盖，acc/fc/combo 一起换掉");

        p.record("玩具狂奏曲終焉#IN", 1000000, 100.0, true, 1500);
        check(p.records().size() == 2, "两条成绩");

        // 商店字段
        check(p.shopDue(0), "shopSeed=0 时货架到期");
        p.refreshShop(1734567890L, 24000L);
        check(!p.shopDue(23999), "23999 tick 时不到期");
        check(p.shopDue(24000), "24000 tick（一天）时到期");

        // 自动游玩开关（选曲界面的勾选按钮写的就是它，必须跨存档保持）
        check(!p.autoplay(), "autoplay 默认 false");
        p.autoplay(true);
        check(p.autoplay(), "autoplay(true) 立刻生效");
        check(Files.readString(file, StandardCharsets.UTF_8).contains("\"autoplay\": true"),
                "autoplay=true 已写进 profile.json");

        String json = Files.readString(file, StandardCharsets.UTF_8);
        System.out.println("  --- 全局 config/phimc/profile.json（B 类：偏好）---");
        for (String line : json.split("\\R")) System.out.println("  " + line);
        String worldJson = Files.readString(world, StandardCharsets.UTF_8);
        System.out.println("  --- 世界 saves/<世界>/phimc/profile.json（A 类：进度）---");
        for (String line : worldJson.split("\\R")) System.out.println("  " + line);

        Profile reloaded = new Profile(file);
        reloaded.attachWorld(world);
        System.out.println("  重载: " + reloaded);
        check(reloaded.worldSeed() == p.worldSeed(), "重载 worldSeed 一致");
        check(reloaded.unlocked().equals(p.unlocked()), "重载 unlocked 一致（顺序也一致）");
        check(reloaded.records().equals(p.records()), "重载 records 一致");
        check(reloaded.shopSeed() == p.shopSeed() && reloaded.shopRefreshAt() == p.shopRefreshAt(),
                "重载 shopSeed / shopRefreshAt 一致");
        check(reloaded.autoplay(), "重载后 autoplay 仍为 true（跨局保持）");
        reloaded.autoplay(false);
        check(!new Profile(file).autoplay(), "关掉自动游玩后落盘为 false（不残留）");
        Profile.Score r = reloaded.record("Verruckt#IN");
        check(r != null && r.score() == 995000 && Math.abs(r.acc() - 99.10) < 1e-9 && !r.fc() && r.combo() == 1300,
                "重载后 Verruckt#IN 成绩逐字段一致: " + r);

        // 坏存档不崩
        Path bad = file.resolveSibling("profile-bad.json");
        Files.writeString(bad, "{ this is not json", StandardCharsets.UTF_8);
        Profile broken = new Profile(bad);
        check(broken.worldSeed() == 0 && broken.unlockedCount() == 0, "坏 JSON 退化成空存档（不抛异常）");
        Files.deleteIfExists(bad);
    }

    // ------------------------------------------------------------------

    private static void shopTests() {
        section("S3 Shop 货架（shopSeed 决定，买走一个后续补位）");
        List<String> charts = new ArrayList<>();
        for (int i = 0; i < 9; i++) charts.add("Song" + (char) ('A' + i) + "#" + (i % 3 == 0 ? "IN" : i % 3 == 1 ? "HD" : "AT"));
        long shopSeed = 456L;
        Set<String> unlocked = new LinkedHashSet<>();

        List<String> shelf = Shop.roll(shopSeed, charts, unlocked, Shop.SLOTS);
        System.out.println("  货架(6): " + shelf);
        check(shelf.size() == Shop.SLOTS, "首批货架 6 个");
        check(Shop.roll(shopSeed, charts, unlocked, Shop.SLOTS).equals(shelf), "同 shopSeed 重算结果一致");
        List<String> other = Shop.roll(shopSeed + 1, charts, unlocked, Shop.SLOTS);
        System.out.println("  换种子(7): " + other);
        check(!other.equals(shelf), "换 shopSeed 换货");

        // 买走第一个：其余位置保持不动，后面补一个
        String bought = shelf.get(0);
        unlocked.add(bought);
        List<String> after = Shop.roll(shopSeed, charts, unlocked, Shop.SLOTS);
        System.out.println("  买走 " + bought + " 后: " + after);
        check(!after.contains(bought), "已解锁的谱面不再上架");
        check(after.size() == Shop.SLOTS, "买走后仍然补满 6 个");
        List<String> rest = new ArrayList<>(shelf.subList(1, shelf.size()));
        check(after.subList(0, rest.size()).equals(rest), "其余 5 个位置不变（不打乱）");

        // 全部解锁：货架清空
        unlocked.addAll(charts);
        check(Shop.roll(shopSeed, charts, unlocked, Shop.SLOTS).isEmpty(), "全部解锁后货架为空");
    }

    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "✓" : "✗") + " " + what);
        if (!ok) failed++;
    }
}
