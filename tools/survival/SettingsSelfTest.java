package dev.dsh.phimc.survival;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置页（打击音 / 打击音音量 / 材质包 / 键盘游玩）的 {@code profile.json} 往返自测。
 *
 * <p>只依赖 {@link Profile} 本身（不碰 Minecraft）：每个设置项都改一遍 → 重新 new 一个
 * {@code Profile} 读同一份文件 → 逐字段比对，证明「改完立刻 save、重进游戏保持」。
 *
 * <pre>
 * javac -encoding UTF-8 -d out -cp "lib\gson-2.11.0.jar;lib\fabric-loader-0.20.0.jar" ^
 *       core/src/main/java/dev/dsh/phimc/core/Judgement.java ^
 *       mod/src/main/java/dev/dsh/phimc/survival/Profile.java ^
 *       tools/survival/SettingsSelfTest.java
 * java "-Dstdout.encoding=UTF-8" -cp "out;lib\gson-2.11.0.jar" dev.dsh.phimc.survival.SettingsSelfTest
 * </pre>
 */
public final class SettingsSelfTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "C:\\dsh-work\\settings-selftest");
        Files.createDirectories(work);
        Path file = work.resolve("profile.json");
        Files.deleteIfExists(file);

        defaults(file);
        roundTrip(file);
        overwrite(file);
        clamping(work.resolve("clamp.json"));
        backwardCompatible(work.resolve("old.json"));
        nullResPack(work.resolve("null.json"));

        System.out.println();
        System.out.println(failed == 0 ? "=== 全部通过 ===" : "=== 失败 " + failed + " 项 ===");
        if (failed != 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    /** ① 新存档（文件不存在）时的默认值：打击音开、音量 100%、无材质包、键盘关。 */
    private static void defaults(Path file) {
        section("① 默认值（profile.json 不存在）");
        Profile p = new Profile(file);
        check(p.hitSound(), "hitSound 默认 true");
        check(Math.abs(p.hitSoundVolume() - 0.35) < 1e-9, "hitSoundVolume 默认 0.35");
        check(p.resPack().isEmpty(), "resPack 默认 \"\"（不用材质包）");
        check(!p.useKeyboard(), "useKeyboard 默认 false");
        check(!Files.exists(file), "只读不写：构造时不会凭空生成 profile.json");
    }

    /** ② 改设置 → 每项 setter 立刻落盘 → 重载逐字段一致。 */
    private static void roundTrip(Path file) throws Exception {
        section("② 改设置 + 重载（" + file + "）");
        Profile p = new Profile(file);
        p.hitSound(false);
        p.hitSoundVolume(0.35);
        p.resPack("Phigros官方资源包.zip");
        p.useKeyboard(true);
        check(Files.isRegularFile(file), "改完一项就已经写过盘（文件存在）");

        String json = Files.readString(file, StandardCharsets.UTF_8);
        System.out.println("  --- profile.json（只列设置字段）---");
        for (String line : json.split("\\R")) {
            if (line.contains("hitSound") || line.contains("resPack") || line.contains("useKeyboard")) {
                System.out.println("    " + line.trim());
            }
        }
        check(json.contains("\"hitSound\": false"), "JSON 里有 \"hitSound\": false");
        check(json.contains("\"hitSoundVolume\": 0.35"), "JSON 里有 \"hitSoundVolume\": 0.35");
        check(json.contains("\"resPack\": \"Phigros官方资源包.zip\""), "JSON 里有 \"resPack\": \"Phigros官方资源包.zip\"");
        check(json.contains("\"useKeyboard\": true"), "JSON 里有 \"useKeyboard\": true");

        Profile r = new Profile(file);
        check(!r.hitSound(), "重载 hitSound = false");
        check(Math.abs(r.hitSoundVolume() - 0.35) < 1e-9, "重载 hitSoundVolume = 0.35");
        check(r.resPack().equals("Phigros官方资源包.zip"), "重载 resPack = Phigros官方资源包.zip");
        check(r.useKeyboard(), "重载 useKeyboard = true");

        // 再改回去 → 再重载（覆盖写也要对）
        r.hitSound(true);
        r.hitSoundVolume(1.0);
        r.resPack("");
        r.useKeyboard(false);
        Profile r2 = new Profile(file);
        check(r2.hitSound(), "改回后重载 hitSound = true");
        check(Math.abs(r2.hitSoundVolume() - 1.0) < 1e-9, "改回后重载 hitSoundVolume = 1.0");
        check(r2.resPack().isEmpty(), "改回后重载 resPack = \"\"");
        check(!r2.useKeyboard(), "改回后重载 useKeyboard = false");
    }

    /** ③ 同值 setter 不重复写盘（Profile 里的 early-return），但值仍然正确。 */
    private static void overwrite(Path file) throws Exception {
        section("③ 幂等：同值重复设置");
        Profile p = new Profile(file);
        p.hitSoundVolume(0.6);
        String first = Files.readString(file, StandardCharsets.UTF_8);
        p.hitSoundVolume(0.6);                       // 同值 → 内部直接 return，不写盘
        String second = Files.readString(file, StandardCharsets.UTF_8);
        check(first.equals(second), "同值 setHitSoundVolume 不重写文件（内容一样）");
        check(Math.abs(new Profile(file).hitSoundVolume() - 0.6) < 1e-9, "重载 hitSoundVolume = 0.6");
    }

    /** ④ 音量夹取到 0~1（滑条 / -+ 按钮再乱点也不会写出越界值）。 */
    private static void clamping(Path file) throws Exception {
        section("④ 夹取（" + file + "）");
        Profile p = new Profile(file);
        p.hitSoundVolume(1.5);
        check(Math.abs(p.hitSoundVolume() - 1.0) < 1e-9, "1.5 夹成 1.0");
        p.hitSoundVolume(-0.5);
        check(Math.abs(p.hitSoundVolume() - 0.0) < 1e-9, "-0.5 夹成 0.0");
        Profile r = new Profile(file);
        check(Math.abs(r.hitSoundVolume() - 0.0) < 1e-9, "夹取后的 0.0 也落盘了");

        // 0% 时 HitSoundPlayer 会直接不播（judge.rs:62-65 的 volume_sfx <= 1e-2）
        check(r.hitSoundVolume() <= 1e-2, "0.0 落在「不播」阈值内（≤1e-2）");
    }

    /** ⑤ 老存档没有这些键时按默认值读（不崩、不丢别的字段）。 */
    private static void backwardCompatible(Path file) throws Exception {
        section("⑤ 老存档（没有设置字段）");
        Files.writeString(file, "{\n"
                + "  \"worldSeed\": 123,\n"
                + "  \"unlocked\": [\"Verruckt#IN\"],\n"
                + "  \"shopSeed\": 456,\n"
                + "  \"shopRefreshAt\": 789\n"
                + "}\n", StandardCharsets.UTF_8);
        Profile p = new Profile(file);
        check(p.worldSeed() == 123, "worldSeed 读出来 123");
        check(p.isUnlocked("Verruckt#IN"), "unlocked 读出来 Verruckt#IN");
        check(p.hitSound() && Math.abs(p.hitSoundVolume() - 0.35) < 1e-9 && p.resPack().isEmpty()
                && !p.useKeyboard(), "缺字段时四项设置都取默认值");
        p.hitSound(false);                            // 写回：老字段必须还在
        String json = Files.readString(file, StandardCharsets.UTF_8);
        check(json.contains("\"worldSeed\": 123") && json.contains("Verruckt#IN"),
                "写回后老字段（worldSeed / unlocked）还在");
    }

    /** ⑥ resPack 传 null 归一成空串（设置页「不使用」走这条路）。 */
    private static void nullResPack(Path file) throws Exception {
        section("⑥ resPack(null) → 空串");
        Profile p = new Profile(file);
        p.resPack("a.zip");
        p.resPack(null);
        check(p.resPack().isEmpty(), "resPack(null) 归一成 \"\"");
        check(new Profile(file).resPack().isEmpty(), "空串也落盘了");
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
