package dev.dsh.phimc.client;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 「按服务器隔离」的成绩 / 进度落点：{@code config/phimc/servers/<地址>/}。
 *
 * <h2>为什么需要它</h2>
 * 单人游戏的玩法进度跟着世界存档走（{@code saves/<世界名>/phimc/}，见 {@link WorldStore}）。
 * <b>多人游戏没有本地存档</b>：{@code MinecraftClient.getServer()} 是 {@code null}（服务端在别人机器上），
 * 以前这种情况直接退化成「只读、不落盘」，于是多人打歌的成绩一退出就没了。
 * 现在改成按<b>服务器地址</b>落盘，同一个服务器重进还是同一份成绩，换服务器互不干扰。
 *
 * <h2>目录名怎么来</h2>
 * 地址取自 {@code ClientPlayNetworkHandler.getConnection().getAddress()}（{@link WorldHooks#serverAddress}），
 * 形如 {@code play.example.com:25565}。它<b>不能直接当目录名</b>：
 * Windows 不允许 {@code : / \ * ? " < > |}，Linux 只禁 {@code /} 与 NUL，
 * 所以统一过一遍 {@link #safeName(String)}：<b>只保留字母 / 数字 / {@code . - _}，其余一律换成 {@code _}</b>
 * （{@code :} 与 {@code /} 因此变成 {@code _}，这正是需求里点名的那两个）。
 *
 * <p>例：
 * <pre>
 *   127.0.0.1:25565              → config/phimc/servers/127.0.0.1_25565/
 *   mc.example.com:25565         → config/phimc/servers/mc.example.com_25565/
 *   /1.2.3.4:25565               → config/phimc/servers/_1.2.3.4_25565/
 *   null / ""（取不到地址）      → config/phimc/servers/local/   （打歌 only 的主菜单兜底）
 * </pre>
 *
 * <p><b>纯 java.nio，不 import 任何 Minecraft 类</b>：可以脱离游戏编译 / 自测
 * （{@code tools/server/ServerStoreTest.java} 就是拿假地址直接断言落点的）。
 */
public final class ServerStore {

    /** 子目录名（{@code config/phimc/servers/}）。 */
    public static final String DIR = "servers";

    /**
     * 取不到服务器地址时的目录名。
     *
     * <p>打歌 only 变体可以在<b>主菜单</b>直接打歌（没有世界、也没有服务器地址），
     * 那种情况成绩落 {@code config/phimc/servers/local/}，退出游戏再进来还在。
     */
    public static final String LOCAL = "local";

    /** 目录名长度上限（Windows 单段 255 字节，留足余量；超长地址会截断）。 */
    private static final int MAX_LEN = 96;

    /** Windows 的保留设备名：即使域名正好叫 con / nul 也不能当目录名。 */
    private static final String[] RESERVED = {
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    };

    private ServerStore() {
    }

    /** {@code config/phimc/servers/}。 */
    public static Path serversRoot(Path modDir) {
        return modDir.resolve(DIR);
    }

    /**
     * 某个服务器（或 {@code null} = 本地兜底）的目录。
     *
     * @param modDir {@code config/phimc/}（= {@code Session.songsDir()} 的父目录）
     */
    public static Path serverDir(Path modDir, String address) {
        return serversRoot(modDir).resolve(safeName(address));
    }

    /** 该服务器的进度文件（解锁 / 成绩 / 商店，A 类；B 类偏好仍在 {@code config/phimc/profile.json}）。 */
    public static Path profileFile(Path modDir, String address) {
        return serverDir(modDir, address).resolve("profile.json");
    }

    /** 该服务器的最佳成绩表（选曲界面显示用）。 */
    public static Path recordsFile(Path modDir, String address) {
        return serverDir(modDir, address).resolve("records.tsv");
    }

    /**
     * 地址 → 文件名安全串（幂等：{@code safeName(safeName(x)).equals(safeName(x))}）。
     *
     * <ul>
     *   <li>{@code null} / 空白 → {@link #LOCAL}；</li>
     *   <li>只保留 {@code [A-Za-z0-9._-]}，其余字符（含 {@code : / \ * ? " < > |} 与空白）→ {@code _}；</li>
     *   <li>截断到 {@link #MAX_LEN} 字符；</li>
     *   <li>结果是 Windows 保留设备名（{@code con} / {@code nul} / {@code com1} …）时前面补 {@code _}；</li>
     *   <li>结果是空串（地址全是非法字符）→ {@link #LOCAL}。</li>
     * </ul>
     */
    public static String safeName(String address) {
        if (address == null || address.isBlank()) return LOCAL;
        String trimmed = address.trim();
        StringBuilder sb = new StringBuilder(Math.min(trimmed.length(), MAX_LEN));
        for (int i = 0; i < trimmed.length() && sb.length() < MAX_LEN; i++) {
            char c = trimmed.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            sb.append(keep ? c : '_');
        }
        String name = sb.toString();
        if (name.isEmpty()) return LOCAL;
        if (isReserved(name)) return "_" + name;
        return name;
    }

    private static boolean isReserved(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String r : RESERVED) {
            if (r.equals(lower)) return true;
        }
        return false;
    }
}
