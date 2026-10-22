package dev.dsh.phimc.survival;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 玩法进度 + 客户端设置。**按存档（世界）隔离**，所以拆成两个文件：
 *
 * <h2>两类状态（这是本类唯一需要记住的划分）</h2>
 * <table border="1">
 *   <tr><th></th><th>字段</th><th>文件</th></tr>
 *   <tr>
 *     <td><b>A. 按存档</b>（每个世界独立）</td>
 *     <td>{@code worldSeed} / {@code unlocked} / {@code records} / {@code shopSeed} /
 *         {@code shopRefreshAt} / {@code tabletGiftGiven}</td>
 *     <td>{@code saves/<世界名>/phimc/profile.json}</td>
 *   </tr>
 *   <tr>
 *     <td><b>B. 全局</b>（客户端偏好，整个实例共用）</td>
 *     <td>{@code offsetMs} / {@code scrollSpeed} / {@code hitSound} / {@code hitSoundVolume} /
 *         {@code resPack} / {@code useKeyboard} / {@code autoplay}</td>
 *     <td>{@code config/phimc/profile.json}</td>
 *   </tr>
 * </table>
 *
 * <p>为什么要拆：本类以前只有 {@code config/phimc/profile.json} 一个文件、整个实例共用 ——
 * 在 A 世界解锁的谱面，进 B 世界照样是解锁的（用户报的真 bug）。现在「玩法进度」跟着世界走，
 * 「手感/音画偏好」跟着玩家走。
 *
 * <h2>没有世界时（主菜单 / 多人）</h2>
 * {@link #attachWorld} 没被调用时（{@code worldFile == null}）：
 * <ul>
 *   <li><b>读</b>：A 类字段从全局文件兜底读（老版本的单文件存档因此还能显示出来）；</li>
 *   <li><b>写</b>：只写 B 类，A 类一律不落盘（<b>只读不写</b>）—— 主菜单里不知道「现在算哪个世界」，
 *       把进度写进全局就会重新制造串档。全局文件里已有的 A 类字段会原样保留（不会被 B 类写盘冲掉）。</li>
 * </ul>
 *
 * <h2>一次性迁移（老版本 → 按世界）</h2>
 * 第一次进某个世界时，若该世界还没有自己的 {@code profile.json}、且全局文件里还有 A 类内容，
 * 就把这些内容写进该世界，并把全局文件改写成「只剩 B 类 + {@code "progressMigrated": true}」。
 * 标记置位之后，后面新开的世界<b>不会</b>再继承一次旧进度（否则每个新世界都白送一堆解锁）。
 *
 * <h2>其它</h2>
 * <p>价格不进存档：由 {@link PriceTable} 用 {@code worldSeed + chartId} 现算。
 * {@code acc} 存百分数（与设计文档示例的 {@code 98.42} 一致），{@code fc} 是全连。
 *
 * <p>JSON 兼容：所有字段「读不到就取默认」，老存档不会报错、也不会丢字段（未知字段在改写时会被丢掉，
 * 这是原有行为）。{@code 0} 用作 worldSeed / shopSeed 的「未初始化」哨兵值。
 *
 * <p>本类不 import 任何 Minecraft 类，可脱离游戏编译/单测
 * （见 {@code tools/survival/SurvivalSelfTest.java} 与 {@code tools/world/WorldProgressTest.java}）。
 */
public final class Profile {

    /** 一条谱面成绩。字段名与设计文档 §6 的 JSON 一一对应。 */
    public record Score(int score, double acc, boolean fc, int combo) {
        /** 段位字母（与 {@code Records.Rec.rank()} 同源阈值）。 */
        public String rank() {
            return dev.dsh.phimc.core.Judgement.rank(score);
        }

        public String scoreText() {
            return String.format("%07d", score);
        }

        public String accText() {
            return String.format("%.2f%%", acc);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 全局文件（B 类）。永不改变。 */
    private final Path globalFile;
    /** 世界文件（A 类）。{@code null} = 还没有世界（主菜单 / 多人）。 */
    private Path worldFile;

    /** 迁移标记（写在<b>全局</b>文件里）：true 表示旧版单文件里的进度已经被某个世界认领过。 */
    private boolean progressMigrated;

    // ---------------- A 类：按存档 ----------------
    private long worldSeed;
    private final Set<String> unlocked = new LinkedHashSet<>();
    private final Map<String, Score> records = new LinkedHashMap<>();
    private long shopSeed;
    private long shopRefreshAt;
    /**
     * 新平板赠品是否已经送过。**老存档没有这个字段** → 读出来是 {@code false} → 升级后第一次打开平板
     * 会补送一次（这是想要的行为：老玩家也不该被漏掉），送完立刻写盘，之后（这个世界里）永远不再送。
     */
    private boolean tabletGiftGiven;

    // ---------------- B 类：全局 ----------------
    /**
     * 配置 {@code use_keyboard}：prpr {@code config.rs:83} 的字段、{@code config.rs:123} 默认 {@code false}。
     * 键盘游玩现在恒开（见 {@code Session}），这个字段只为老存档兼容保留。
     */
    private boolean useKeyboard;

    /**
     * 自动游玩开关，默认 {@code false}。勾上后这一局的每个音符都由引擎在到点时自动判成 Perfect
     * （{@code Engine.autoplay(sec)}），玩家不用任何输入。
     *
     * <p>存在这里是为了<b>跨局保持</b>：选曲界面勾一次，之后每次开局都还是勾上的，直到再点一次。
     * 自动局<b>不写成绩、不发结算奖励</b>（见 {@code Session.finish()}），否则等于用 AUTO 刷记录。
     */
    private boolean autoplay;

    /** 打击音总开关，默认开。 */
    private boolean hitSound = true;
    /** 打击音音量 0~1，默认 0.35（prpr config.rs:125 {@code volume_sfx: 1.}，本 mod 默认调低）。 */
    private double hitSoundVolume = 0.35;

    /** 选中的 Phira 资源包文件名（{@code config/phimc/respack/} 下的 zip），空串 = 不用材质包。 */
    private String resPack = "";

    /** 偏移范围上限（毫秒），界面和存档都按这个夹。 */
    public static final double OFFSET_LIMIT_MS = 300;

    /** 延迟校准（毫秒，正值 = 谱面提前，范围 ±{@link #OFFSET_LIMIT_MS}）。默认 0；老存档缺字段读出来就是 0。 */
    private double offsetMs = 0;
    /**
     * 滚动速度（0.5~2.0，默认 1.0）。prpr 的 {@code config.speed}（config.rs:81，默认 1.0，
     * game.rs:670 滑杆范围 0.5~2.0）在源码里是「时间轴倍速」；本实现只把它作用在**视觉下落速度**上，
     * 判定窗口一个都不动（我们的 dt 判定没有乘它）。
     */
    private double scrollSpeed = 1.0;

    /**
     * 打开全局文件（B 类）。此时还没有世界：A 类字段是「只读兜底」，见类的说明。
     *
     * @param globalFile {@code config/phimc/profile.json}
     */
    public Profile(Path globalFile) {
        this.globalFile = globalFile;
        this.worldFile = null;
        load();
    }

    // ------------------------------------------------------------------
    // 世界切换（由 client/WorldHooks 在进/退世界时调用）
    // ------------------------------------------------------------------

    /** 当前实际承载 A 类进度的文件：有世界就是世界文件，否则全局文件（只读兜底）。 */
    public synchronized Path file() {
        return worldFile != null ? worldFile : globalFile;
    }

    /** 全局设置文件（{@code config/phimc/profile.json}）。 */
    public synchronized Path globalFile() {
        return globalFile;
    }

    /** 当前世界的进度文件；没有世界时 {@code null}。 */
    public synchronized Path worldFile() {
        return worldFile;
    }

    /** 现在有没有「当前世界」（没有 = 主菜单 / 多人 / 取不到存档目录）。 */
    public synchronized boolean hasWorld() {
        return worldFile != null;
    }

    /** 旧版单文件里的进度是否已经被某个世界认领过（迁移标记）。 */
    public synchronized boolean progressMigrated() {
        return progressMigrated;
    }

    /**
     * 切到某个世界的进度文件并重载（进世界时调用）。
     *
     * <p>顺带做一次性迁移：该世界还没有自己的 {@code profile.json}、全局里还留着旧版 A 类内容、
     * 且迁移标记还没置位 → 把旧进度写进这个世界，并把全局改写成「只剩 B 类 + 标记」。
     *
     * @param worldProfileFile {@code saves/<世界>/phimc/profile.json}
     * @return {@code true} 表示这次真的把旧版全局进度迁进了这个世界（调用方据此迁移 records.tsv）
     */
    public synchronized boolean attachWorld(Path worldProfileFile) {
        return attachWorld(worldProfileFile, true);
    }

    /**
     * {@link #attachWorld(Path)} 的完整版：可以<b>关掉旧版进度迁移</b>。
     *
     * <p>为什么需要：多人服务器 / 打歌 only 的本地目录（{@code config/phimc/servers/<地址>/}，
     * 见 {@code client/ServerStore}）都是「新出现的世界」，而旧版单文件进度只应该被
     * <b>一个人自己的存档</b>认领。若这里也允许迁移，第一个进过的服务器会把旧存档的解锁 / 成绩
     * 吃走，并且把 {@code progressMigrated} 永久置位，之后就再也迁不回真正的存档了。
     * 所以服务器一律 {@code migrateLegacy = false}：从零开始，不碰全局文件里的旧内容。
     *
     * @param migrateLegacy {@code false} = 该目录的文件还不存在时用空进度开始（并清掉从全局
     *                      兜底读出来的 A 类内容），绝不认领旧版单文件进度
     */
    public synchronized boolean attachWorld(Path worldProfileFile, boolean migrateLegacy) {
        this.worldFile = worldProfileFile;
        load();
        if (!migrateLegacy) {
            if (worldFile != null && !Files.isRegularFile(worldFile)) {
                // load() 在「文件不存在且还没迁移过」时会从全局兜底读 A 类，这里必须清掉：
                // 服务器目录的进度只能是它自己的
                clearProgress();
            }
            return false;
        }
        if (worldFile == null || Files.isRegularFile(worldFile)) {
            // 已经切过去了（或参数是 null）：load() 已经把该读的读进来，没有迁移可做
            return false;
        }
        if (progressMigrated || !hasProgress()) {
            // 旧进度已经被别的世界认领过（或本来就没有）→ 这个世界从零开始，不留全局兜底内容
            clearProgress();
            return false;
        }
        // 迁移：此刻内存里的 A 类字段正是从全局文件兜底读出来的
        saveWorld();
        progressMigrated = true;
        saveGlobal();            // 全局只剩 B 类 + 标记（旧 A 字段在这里被丢掉）
        return true;
    }

    /**
     * 退回「没有世界」（退世界时调用）：A 类状态清空、之后不再写盘。
     *
     * <p>必须清空：否则上一个世界的解锁会留在内存里，进下一个世界时被当成「旧版兜底进度」迁移过去。
     */
    public synchronized void detachWorld() {
        worldFile = null;
        clearProgress();
    }

    // ------------------------------------------------------------------
    // A 类：worldSeed
    // ------------------------------------------------------------------

    public long worldSeed() {
        return worldSeed;
    }

    public boolean hasWorldSeed() {
        return worldSeed != 0;
    }

    /**
     * 世界种子只在**这个世界第一次**写入：之后重进同一个世界价格不变；
     * 换世界就是另一份 profile，另一个种子（价格本来就应该跟着世界走）。
     *
     * @return true 表示这次真的写入了
     */
    public synchronized boolean ensureWorldSeed(long seed) {
        if (worldSeed != 0) return false;
        worldSeed = seed;
        save();
        return true;
    }

    // ------------------------------------------------------------------
    // A 类：解锁
    // ------------------------------------------------------------------

    /** 调试开关：config/phimc/unlockall.txt 存在时，所有谱面视为已解锁。 */
    private static Boolean devUnlockAll;

    private static boolean devUnlockAll() {
        if (devUnlockAll == null) {
            try {
                java.nio.file.Path f = dev.dsh.phimc.client.ConfigDir.modDir(
                                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir())
                        .resolve("unlockall.txt");
                devUnlockAll = java.nio.file.Files.exists(f);
            } catch (Throwable t) {
                devUnlockAll = Boolean.FALSE;
            }
        }
        return devUnlockAll.booleanValue();
    }

    public synchronized boolean isUnlocked(String chartId) {
        if (devUnlockAll()) return true;
        return chartId != null && unlocked.contains(chartId);
    }

    /** @return true 表示这次是新解锁（重复解锁不会重复写盘） */
    public synchronized boolean unlock(String chartId) {
        if (chartId == null || chartId.isEmpty()) return false;
        if (!unlocked.add(chartId)) return false;
        save();
        return true;
    }

    /** 已解锁谱面 id 的只读视图（插入顺序 = JSON 里的顺序，稳定）。 */
    public synchronized Set<String> unlocked() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(unlocked));
    }

    public synchronized int unlockedCount() {
        return unlocked.size();
    }

    // ------------------------------------------------------------------
    // A 类：成绩
    // ------------------------------------------------------------------

    /** 某谱面的成绩，没有则 null。 */
    public synchronized Score record(String chartId) {
        return chartId == null ? null : records.get(chartId);
    }

    /**
     * 记一局成绩：**只有分数更高（或首次）才覆盖**。
     * 与 {@code Records.submit} 的规则一致，但落在（当前世界的）profile.json 里。
     *
     * @return 覆盖后存档里的成绩（可能是旧的那条）
     */
    public synchronized Score record(String chartId, int score, double acc, boolean fc, int combo) {
        if (chartId == null) return null;
        Score old = records.get(chartId);
        if (old != null && old.score() >= score) return old;
        Score now = new Score(score, acc, fc, combo);
        records.put(chartId, now);
        save();
        return now;
    }

    public synchronized Map<String, Score> records() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(records));
    }

    // ------------------------------------------------------------------
    // A 类：商店
    // ------------------------------------------------------------------

    public synchronized long shopSeed() {
        return shopSeed;
    }

    public synchronized long shopRefreshAt() {
        return shopRefreshAt;
    }

    /** 货架是否到期：没生成过，或世界时间已过刷新点（{@link Shop#REFRESH_TICKS} 一天）。 */
    public synchronized boolean shopDue(long nowTicks) {
        return shopSeed == 0 || nowTicks >= shopRefreshAt;
    }

    /** 换一批货架并写入下一次刷新时间。 */
    public synchronized void refreshShop(long seed, long refreshAt) {
        this.shopSeed = seed;
        this.shopRefreshAt = refreshAt;
        save();
    }

    // ------------------------------------------------------------------
    // A 类：新平板赠品
    // ------------------------------------------------------------------

    /** 新平板赠品是否已经送过（见 {@link TabletGift}）。 */
    public synchronized boolean tabletGiftGiven() {
        return tabletGiftGiven;
    }

    /**
     * 把「赠品已送」标记落盘。
     *
     * @return true 表示这次是第一次置位（之前没送过）
     */
    public synchronized boolean claimTabletGift() {
        if (tabletGiftGiven) return false;
        tabletGiftGiven = true;
        save();
        return true;
    }

    // ------------------------------------------------------------------
    // B 类：配置
    // ------------------------------------------------------------------

    /** 键盘游玩开关（prpr {@code config.rs:83} {@code use_keyboard}，默认 {@code false}：{@code config.rs:123}）。 */
    public synchronized boolean useKeyboard() {
        return useKeyboard;
    }

    public synchronized void useKeyboard(boolean on) {
        if (useKeyboard == on) return;
        useKeyboard = on;
        save();
    }

    /**
     * 自动游玩开关（默认 {@code false}）。
     *
     * <p>这一局要不要自动判定，真正读的是 {@code Session.autoplay()}；它每次 {@code start()} 时
     * 从这里同步一次，所以「选曲界面勾上 → 开局」这条链是通的，跨世界重启也保持。
     */
    public synchronized boolean autoplay() {
        return autoplay;
    }

    public synchronized void autoplay(boolean on) {
        if (autoplay == on) return;
        autoplay = on;
        save();
    }

    /** 打击音开关（默认开）。 */
    public synchronized boolean hitSound() {
        return hitSound;
    }

    public synchronized void hitSound(boolean on) {
        if (hitSound == on) return;
        hitSound = on;
        save();
    }

    /** 打击音音量 0~1（prpr {@code volume_sfx}，默认 0.35）。 */
    public synchronized double hitSoundVolume() {
        return hitSoundVolume;
    }

    public synchronized void hitSoundVolume(double v) {
        double n = Math.max(0, Math.min(1, v));
        if (Math.abs(n - hitSoundVolume) < 1e-9) return;
        hitSoundVolume = n;
        save();
    }

    /** 选中的材质包文件名（respack/ 下的 zip），空串 = 不用材质包。 */
    public synchronized String resPack() {
        return resPack;
    }

    public synchronized void resPack(String name) {
        String n = name == null ? "" : name;
        if (n.equals(resPack)) return;
        resPack = n;
        save();
    }

    /**
     * 延迟校准（<b>毫秒</b>，范围 ±{@value #OFFSET_LIMIT_MS}，正值 = 谱面提前）。
     *
     * <p>单位选择：存<b>毫秒</b>（double，允许 0.5ms 这种细粒度），因为界面步进是 10ms、
     * 玩家心里的刻度也是毫秒；内部用时再 {@code /1000} 换成秒。
     *
     * <p>和谱面自带的 {@code Chart.offset}（谱面包里写死的，正数 = 谱面比音乐快）是<b>两个东西</b>：
     * 这个是玩家手动补偿（音画不同步 / 设备延迟），两个都生效，见 {@code Session#seconds()}。
     */
    public synchronized double offsetMs() {
        return offsetMs;
    }

    public synchronized void offsetMs(double ms) {
        double n = Math.max(-OFFSET_LIMIT_MS, Math.min(OFFSET_LIMIT_MS, ms));
        if (Math.abs(n - offsetMs) < 1e-9) return;
        offsetMs = n;
        save();
    }

    /** 滚动速度 0.5~2.0（默认 1.0，只影响视觉下落速度）。 */
    public synchronized double scrollSpeed() {
        return scrollSpeed;
    }

    public synchronized void scrollSpeed(double v) {
        double n = Math.max(0.5, Math.min(2.0, v));
        if (Math.abs(n - scrollSpeed) < 1e-9) return;
        scrollSpeed = n;
        save();
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /**
     * 全部重读：B 类读全局文件；A 类优先读世界文件，没有世界时从全局兜底读。
     *
     * <p>读之前先复位成默认值，所以「文件被删了再 load」= 空进度，不会留残影。
     */
    public synchronized void load() {
        resetSettings();
        clearProgress();
        JsonObject global = readObject(globalFile);
        if (global == null) global = new JsonObject();
        applySettings(global);
        progressMigrated = bool(global, "progressMigrated", false);

        if (worldFile != null && Files.isRegularFile(worldFile)) {
            JsonObject world = readObject(worldFile);
            if (world != null) applyProgress(world);
        } else if (!progressMigrated) {
            // 还没有世界（或该世界还没进度）：按旧版「单文件」语义从全局兜底读，迁移时要用
            applyProgress(global);
        }
    }

    /**
     * 落盘：B 类写全局；**有世界时** A 类写该世界的文件。
     *
     * <p>没有世界时不写 A 类（只读不写）—— 主菜单里不知道现在算哪个世界，写进全局就是串档。
     */
    public synchronized void save() {
        saveGlobal();
        if (worldFile != null) saveWorld();
    }

    /** 全局文件：B 类 + 迁移标记。 */
    private void saveGlobal() {
        if (globalFile == null) return;
        // 还没迁移：保留文件里已有的 A 类 / 未知字段（主菜单的只读兜底不能被设置写盘冲掉）。
        //
        // 这里刻意**不**再要求 `worldFile == null`：多人服务器 / 打歌 only 的进度文件
        // （config/phimc/servers/<地址>/profile.json）是「不认领旧进度」的那条路
        // （见 attachWorld(file,false)），旧版单文件里的 A 类内容这时还没被任何存档认领，
        // 如果因为「有 worldFile 了」就把它丢掉，用户先打一局多人、旧进度就永久没了。
        JsonObject o = !progressMigrated ? readObject(globalFile) : null;
        if (o == null) o = new JsonObject();
        o.addProperty("useKeyboard", useKeyboard);
        o.addProperty("autoplay", autoplay);
        o.addProperty("hitSound", hitSound);
        o.addProperty("hitSoundVolume", hitSoundVolume);
        o.addProperty("resPack", resPack);
        o.addProperty("offsetMs", offsetMs);
        o.addProperty("scrollSpeed", scrollSpeed);
        o.addProperty("progressMigrated", progressMigrated);
        write(globalFile, o);
    }

    /** 世界文件：只放 A 类（进度）。 */
    private void saveWorld() {
        if (worldFile == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("worldSeed", worldSeed);
        JsonArray arr = new JsonArray();
        for (String id : unlocked) arr.add(id);
        o.add("unlocked", arr);
        JsonObject recs = new JsonObject();
        for (Map.Entry<String, Score> e : records.entrySet()) {
            JsonObject r = new JsonObject();
            r.addProperty("score", e.getValue().score());
            r.addProperty("acc", e.getValue().acc());
            r.addProperty("fc", e.getValue().fc());
            r.addProperty("combo", e.getValue().combo());
            recs.add(e.getKey(), r);
        }
        o.add("records", recs);
        o.addProperty("shopSeed", shopSeed);
        o.addProperty("shopRefreshAt", shopRefreshAt);
        o.addProperty("tabletGiftGiven", tabletGiftGiven);
        write(worldFile, o);
    }

    /** 读一个 JSON 对象；文件不存在 / 坏 JSON / 不是对象都返回 {@code null}（不抛异常）。 */
    private static JsonObject readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return null;
            JsonElement root = JsonParser.parseString(text);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (IOException | RuntimeException ignored) {
            // 坏存档按空处理：价格/解锁都能重来，不该让游戏起不来
            return null;
        }
    }

    private static void write(Path file, JsonObject o) {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 读 B 类字段。 */
    private void applySettings(JsonObject o) {
        if (o.has("useKeyboard") && !o.get("useKeyboard").isJsonNull()) {
            useKeyboard = o.get("useKeyboard").getAsBoolean();
        }
        // 老存档没有 autoplay：缺字段 = false（默认关闭），不影响其它字段
        if (o.has("autoplay") && !o.get("autoplay").isJsonNull()) {
            autoplay = o.get("autoplay").getAsBoolean();
        }
        if (o.has("hitSound") && !o.get("hitSound").isJsonNull()) {
            hitSound = o.get("hitSound").getAsBoolean();
        }
        if (o.has("hitSoundVolume") && !o.get("hitSoundVolume").isJsonNull()) {
            hitSoundVolume = Math.max(0, Math.min(1, o.get("hitSoundVolume").getAsDouble()));
        }
        if (o.has("resPack") && !o.get("resPack").isJsonNull()) {
            resPack = o.get("resPack").getAsString();
        }
        if (o.has("offsetMs") && !o.get("offsetMs").isJsonNull()) {
            // 老存档缺字段 → 保持默认 0；有值也要夹到 ±OFFSET_LIMIT_MS（手改坏了的存档）
            offsetMs = Math.max(-OFFSET_LIMIT_MS, Math.min(OFFSET_LIMIT_MS,
                    o.get("offsetMs").getAsDouble()));
        }
        if (o.has("scrollSpeed") && !o.get("scrollSpeed").isJsonNull()) {
            scrollSpeed = Math.max(0.5, Math.min(2.0, o.get("scrollSpeed").getAsDouble()));
        }
    }

    /** 读 A 类字段。 */
    private void applyProgress(JsonObject o) {
        if (o.has("worldSeed") && !o.get("worldSeed").isJsonNull()) worldSeed = o.get("worldSeed").getAsLong();
        if (o.has("shopSeed") && !o.get("shopSeed").isJsonNull()) shopSeed = o.get("shopSeed").getAsLong();
        // 老存档没有 tabletGiftGiven：缺字段 = false（默认值），不影响其它字段
        if (o.has("tabletGiftGiven") && !o.get("tabletGiftGiven").isJsonNull()) {
            tabletGiftGiven = o.get("tabletGiftGiven").getAsBoolean();
        }
        if (o.has("shopRefreshAt") && !o.get("shopRefreshAt").isJsonNull()) {
            shopRefreshAt = o.get("shopRefreshAt").getAsLong();
        }
        if (o.has("unlocked") && o.get("unlocked").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("unlocked")) {
                if (e != null && !e.isJsonNull()) unlocked.add(e.getAsString());
            }
        }
        if (o.has("records") && o.get("records").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("records").entrySet()) {
                if (e.getValue() == null || !e.getValue().isJsonObject()) continue;
                JsonObject r = e.getValue().getAsJsonObject();
                records.put(e.getKey(), new Score(
                        r.has("score") ? r.get("score").getAsInt() : 0,
                        r.has("acc") ? r.get("acc").getAsDouble() : 0.0,
                        r.has("fc") && r.get("fc").getAsBoolean(),
                        r.has("combo") ? r.get("combo").getAsInt() : 0));
            }
        }
    }

    /** B 类字段复位成默认值。 */
    private void resetSettings() {
        useKeyboard = false;
        autoplay = false;
        hitSound = true;
        hitSoundVolume = 0.35;
        resPack = "";
        offsetMs = 0;
        scrollSpeed = 1.0;
    }

    /** A 类字段清空（不写盘）。 */
    private void clearProgress() {
        worldSeed = 0;
        unlocked.clear();
        records.clear();
        shopSeed = 0;
        shopRefreshAt = 0;
        tabletGiftGiven = false;
    }

    /** 内存里有没有 A 类内容（判断「旧版全局进度是否值得迁移」）。 */
    private boolean hasProgress() {
        return worldSeed != 0 || !unlocked.isEmpty() || !records.isEmpty()
                || shopSeed != 0 || shopRefreshAt != 0 || tabletGiftGiven;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsBoolean();
            } catch (RuntimeException ignored) {
                return def;
            }
        }
        return def;
    }

    @Override
    public synchronized String toString() {
        return "Profile{world=" + (worldFile == null ? "-" : worldFile) + ", seed=" + worldSeed
                + ", unlocked=" + unlocked.size() + ", records=" + records.size()
                + ", shopSeed=" + shopSeed + ", refreshAt=" + shopRefreshAt
                + ", migrated=" + progressMigrated + "}";
    }
}
