package dev.dsh.phimc.client;

import dev.dsh.phimc.PhiMC;
import dev.dsh.phimc.PhiMCRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 「打歌 only」变体（{@code phimcplayer} / PhiMC Player）的客户端入口。
 *
 * <h2>它只做一件事</h2>
 * {@code V} → 曲库 → 选歌 → 打歌 → 成绩存本地。除此之外什么都没有：
 * <ul>
 *   <li><b>不注册任何物品</b>（关键：这里<b>不调用</b> {@link PhiMCRegistry#register()}，
 *       只调用 {@link PhiMCRegistry#registerHitSounds()} 注册 3 个打击音 SoundEvent）；</li>
 *   <li><b>不读任何配方 / 战利品</b>（{@code ChipLoot} / {@code FusionEgg} 都挂在
 *       {@code PhiMCRegistry.register()} 里，这里没走，所以它们连监听器都不会注册）；</li>
 *   <li><b>不发任何奖励</b>（{@code PhiMC.onlyMode = true} ⇒ {@code Session.finish()} 不调
 *       {@code Rewards.grantOnFinish}；{@code Rewards} 自己还有一条「没有集成服务端玩家就不发」的硬规则）；</li>
 *   <li><b>不碰世界</b>（没有方块 / 实体 / 服务端 tick 监听）。</li>
 * </ul>
 *
 * <h2>为什么它和 {@link PhiMCClient} 有重复代码</h2>
 * 两个入口干的事只有「注册了什么」不一样（物品 vs 什么都没有），曲库 / HUD / tick 这些
 * 必须一致；把它们抽成公共基类会让 {@link PhiMCClient} 的启动顺序多一层间接，
 * 而这个仓库的启动顺序是有讲究的（{@code ConfigDir} 迁移 → {@code Session} →
 * {@code WorldHooks} → 键位 → tick）。所以这里按「与 {@code PhiMCClient} 一致的部分照抄」，
 * 只在差异处加注释 —— 改一边时记得看另一边（两处都写了这条提醒）。
 */
public final class PhiMCLiteClient implements ClientModInitializer {

    /** 本变体的 mod id（{@code fabric.mod.json} 里同名；与完整版 {@code phimc} 是两个 mod）。 */
    public static final String MOD_ID = "phimcplayer";

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Session session;
    /** {@code V}：唯一的入口键（默认 {@code GLFW_KEY_V}，单键，无需修饰键）。 */
    private static KeyBinding keyPlayer;

    @Override
    public void onInitializeClient() {
        // ① 第一件事：置「打歌 only」全局标志。后面所有 UI / 结算的收口都读它，
        //    所以必须在建 Session / 注册界面之前就设好。
        PhiMC.onlyMode = true;

        // ② **只**注册打击音（不是物品）。绝不调用 PhiMCRegistry.register()：
        //    那个方法会注册 6 个物品 + 创造页签 + 芯片战利品注入 + 大肥鱼融合，全都不属于本变体。
        //    打击音只是 SoundEvent（Resources 里的注册表，不落到世界里），没有它就没法跟着节奏打。
        PhiMCRegistry.registerHitSounds();

        // ③ 配置目录 = config/phimc（两个变体共用曲库 / 材质包 / 成绩 —— 用户从完整版换到打歌版
        //    不该重新导一遍谱面）。老目录名的一次性迁移同样照跑（幂等，见 ConfigDir）。
        Path configRoot = FabricLoader.getInstance().getConfigDir();
        ConfigDir.Result migrated = ConfigDir.migrateQuietly(configRoot);
        if (migrated != ConfigDir.Result.NO_LEGACY_DIR && migrated != ConfigDir.Result.ALREADY_NEW) {
            LOG.info("[PhiMC Player] 旧配置目录已迁移到 config/{}（{}）", ConfigDir.NAME, migrated);
        }
        Path songsDir = ConfigDir.modDir(configRoot).resolve("songs");
        session = new Session(songsDir);
        // 既有界面（SongSelectScreen / PlayScreen / SettingsScreen）统一读 PhiMCClient.session()，
        // 这里把同一个 Session 装进去，避免为了 lite 复制一整套界面代码。
        PhiMCClient.installSession(session);
        // 进/退世界（含多人服务器）时切换成绩落点：saves/<世界>/phimc/ 或 config/phimc/servers/<地址>/
        WorldHooks.register(session);

        Path recordsFile = session.records().file();
        LOG.info("[PhiMC Player] 打歌 only · 谱面目录: {}（找到 {} 首）· 成绩: {}",
                songsDir, session.songs().size(),
                recordsFile == null ? ServerStore.serverDir(session.modDir(), null) : recordsFile);

        // ④ 键位：V（玩家要求，单键）。分类沿用完整版的 category.phimc。
        //? if >=1.21.11 {
        keyPlayer = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phimc.player", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "main")), 0));
        //?} elif >=1.21.9 {
        /*keyPlayer = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phimc.player", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))));
        *///?} else {
        /*keyPlayer = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phimc.player", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.phimc"));
        *///?}

        // ⑤ tick：处理键位 + 兜底推进 Session（与 PhiMCClient 完全一致）。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyPlayer.wasPressed()) {
                // 必须按住 Shift：/ 是聊天命令键，裸按会和「打开聊天并输入 /」打架
                openPlayer();
            }
            // 游玩界面开着时判定由 PlayScreen.render 每帧驱动（20Hz 会把 Drag/Flick 量化到 50ms）
            if (!(client.currentScreen instanceof PlayScreen)) {
                session.tick();
            }
        });

        // ⑥ 世界内 HUD（和完整版同一套渲染；没在打歌时它自己直接 return）
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen == null) {
                HudRenderer.render(ctx, session);
            }
        });

        // ⑦ 调试开关与完整版同名（-Dphimc.autostart=true），方便无人值守验证两个 jar 行为一致
        if (Boolean.getBoolean("phimc.autostart") && !session.songs().isEmpty()) {
            boolean ok = session.start(0);
            LOG.info("[PhiMC Player] autostart -> {} ({})", session.songs().get(0).display, ok ? "ok" : "FAILED");
            if (ok) {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> mc.setScreen(new PlayScreen(session, true)));
            }
        }
    }

    /**
     * 打开曲库（<b>播放器模式</b>：全部谱面可玩，不读解锁数据）。
     *
     * <p>和完整版 {@link PhiMCClient#openSongSelect(boolean)} 的唯一区别：这里没有
     * 「潜行右键重打上一首」那条物品入口，所以只有这一种形态。
     */
    public static void openPlayer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        // setScreen 必须在客户端主线程执行
        mc.execute(() -> mc.setScreen(new SongSelectScreen(true)));
    }

    /**
     * 是否按住 Shift。
     *
     * <p>{@code Screen.hasShiftDown()} 是 1.20 ~ 1.21.8 的写法；
     * <b>1.21.9 起</b>它从 {@code Screen} 上被删掉了，同一个语义变成
     * {@code InputUtil.isKeyPressed(Window, key)}（实测：1.21.9 / 1.21.10 / 1.21.11 都是 Window 重载，
     * 1.21.8 及以前是 {@code (long handle, int key)}）。所以这里按版本分支，行为完全一致
     * （左右 Shift 都算）。
     */
    private static boolean shiftDown(MinecraftClient client) {
        //? if >=1.21.9 {
        return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
        //?} else {
        /*return net.minecraft.client.gui.screen.Screen.hasShiftDown();
        *///?}
    }

    /** 本变体自己的 Session（{@link PhiMCClient#session()} 也是同一个对象）。 */
    public static Session session() {
        return session;
    }

    /** 键位提示用的显示名（调试 / 设置界面里如果要从这里取）。 */
    public static Text keyHint() {
        return Text.translatable("key.phimc.player");
    }
}
