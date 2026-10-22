package dev.dsh.phimc.client;

import dev.dsh.phimc.ChartPool;
import dev.dsh.phimc.ItemHooks;
import dev.dsh.phimc.client.respack.ResPack;
import dev.dsh.phimc.survival.Profile;
import dev.dsh.phimc.survival.SurvivalEnv;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 客户端入口（{@code fabric.mod.json} 的 {@code "client"} 入口，专用服务端不加载）。
 *
 * <h2>入口拆分（双端模组）</h2>
 * 物品 / 打击音 / 创造页签 / 芯片战利品 / 大肥鱼彩蛋这些「纯服务端也需要的注册」
 * 已经挪到 {@code PhiMCMod}（{@code "main"} 入口），所以这里<b>不再</b>调
 * {@code PhiMCRegistry.register()}。这里只留客户端专属：
 * <ul>
 *   <li>配置目录迁移 + {@link Session}（曲库 / 音频 / 玩法进度）；</li>
 *   <li>键位（P）、HUD、Screen、材质包、在线库；</li>
 *   <li>把客户端专属的「能力」装给双端代码：{@link ItemHooks}（物品右键动作）、
 *       {@link SurvivalEnv#installLocalPlayer}（本机玩家 → 集成服务端玩家）、
 *       {@link ChartPool#setSource}（开箱赋谱的候选集）。</li>
 * </ul>
 * 客户端行为与改造前一致：键位、HUD、曲库、结算、奖励发放、开箱、在线库都是原路径。
 */
public final class PhiMCClient implements ClientModInitializer {

    public static final String MOD_ID = "phimc";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static Session session;
    private static KeyBinding keySelect;
    private static int songIndex;

    @Override
    public void onInitializeClient() {
        // 双端「能力」安装 ①：物品右键的客户端动作（物品类本身零客户端依赖，见 ItemHooks）
        ItemHooks.install(new ClientItemActions());
        // 双端「能力」安装 ②：「本机玩家」→ 集成服务端玩家（SurvivalEnv 不能碰 MinecraftClient）
        SurvivalEnv.installLocalPlayer(new ClientLocalPlayer());

        // 配置目录 = config/<mod id>。老用户改名前的 config 目录在这里一次性搬过来
        // （songs/ + profile.json + respack/ + records.tsv 一起走，规则见 ConfigDir）。
        Path configRoot = FabricLoader.getInstance().getConfigDir();
        ConfigDir.Result migrated = ConfigDir.migrateQuietly(configRoot);
        if (migrated == ConfigDir.Result.MIGRATED_MOVE
                || migrated == ConfigDir.Result.MIGRATED_COPY
                || migrated == ConfigDir.Result.MERGED) {
            // 旧目录名只在 ConfigDir 里出现一次；这条日志不重复它，方便「全仓搜索旧名」
            LOG.info("[PhiMC] 旧配置目录已迁移到 config/{}（{}）", ConfigDir.NAME, migrated);
        }
        Path songsDir = ConfigDir.modDir(configRoot).resolve("songs");
        session = new Session(songsDir);
        // 双端「能力」安装 ③：开箱赋谱的候选集 = 客户端曲库（动态来源，rescan 后立刻生效）
        ChartPool.setSource(session::chartIds);
        // 进/退世界时把玩法进度切到 saves/<世界名>/phimc/（见 WorldHooks / WorldStore）
        WorldHooks.register(session);
        LOG.info("[PhiMC] 谱面目录: {}（找到 {} 首）", songsDir, session.songs().size());

        //? if >=1.21.11 {
        // 1.21.11 的 KeyBinding 分类从「字符串」换成了 Category 记录（= 一个 Identifier，需要注册）
        keySelect = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phimc.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "main")), 0));
        //?} elif >=1.21.9 {
        /*// 1.21.9 起分类是 Category，但构造器还没有 categorySortOrder 那一位
        keySelect = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phimc.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))));
        *///?} else {
        /*keySelect = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phimc.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.phimc"));
        *///?}

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keySelect.wasPressed()) {
                openSongSelect(false);
            }
            // 游玩界面开着时判定由 PlayScreen.render 每帧驱动（见 Session.tick 的注释：
            // 20Hz 会把 Drag/Flick 的判定量化到 50ms）。这里只管「没有游玩界面」的兜底：
            // 世界内 HUD（HudRenderer）与后台状态推进，20Hz 够用，也保证不会和渲染帧重复驱动。
            if (!(client.currentScreen instanceof PlayScreen)) {
                session.tick();
            }
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen == null) {
                HudRenderer.render(ctx, session);
            }
        });

        // 调试：-Dphimc.autostart=true 时启动即播第一首，方便无人值守验证
        if (Boolean.getBoolean("phimc.autostart") && !session.songs().isEmpty()) {
            boolean ok = session.start(0);
            LOG.info("[PhiMC] autostart -> {} ({})", session.songs().get(0).display, ok ? "ok" : "FAILED");
            if (ok) {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> mc.setScreen(new PlayScreen(session)));
                LOG.info("[PhiMC] 已进入 PlayScreen（调试）");
            }
        }
        // 调试：-Dphimc.openscreen=true 进世界后自动打开选曲界面，验证 Screen 不崩
        if (Boolean.getBoolean("phimc.openscreen")) {
            LOG.info("[PhiMC] openscreen 调试已开启");
        }
    }

    /**
     * 选曲入口（<b>谱面播放器</b> {@code chart_player} / P 键 / 上下文都走这里）。
     * setScreen 必须在客户端主线程执行。
     *
     * <p><b>全部谱面视为已解锁</b>（{@code unlockAll = true}）：播放器是「自由练谱」入口，
     * 用户要求它不受生存解锁限制。注意这只是「能玩」，<b>不会</b>写 {@code Profile.unlocked}
     * ——从平板进来仍然是原来的解锁逻辑（{@link #openTablet()} 传 {@code false}）。
     *
     * @param autoStartLast true = 谱面物品<b>潜行</b>右键：直接重打上一首（没有上一首就退回曲库）。
     *                      这个开关以前只被存下来没人读，属于死参数；现在在这里真正生效。
     */
    public static void openSongSelect(boolean autoStartLast) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        if (autoStartLast && session != null && session.currentChartId() != null && session.startLast()) {
            // 播放器局：退出后还回「全部可玩」的曲库
            mc.execute(() -> mc.setScreen(new PlayScreen(session, true)));
            return;
        }
        mc.execute(() -> mc.setScreen(new SongSelectScreen(true)));
    }

    /**
     * 平板右键：<b>直接打开选曲界面</b>（全 mod 唯一的曲库界面，带曲绘 / 锁定状态 / 拷芯片）。
     *
     * <p>平板剩下的「商店」页在 {@link TabletScreen}，所以这里把 {@code backTo} 传成
     * {@code TabletScreen::new}：选曲界面的「关闭」按钮会变成「商店」，从商店再关闭才回游戏。
     *
     * <p><b>解锁按生存规则</b>（{@code unlockAll = false}）：只有 {@code profile.isUnlocked}
     * 的谱面能开始 —— 与谱面播放器那条入口的区别就在这里。
     *
     * <p><b>打歌 only 变体里这个方法不可达</b>（没有平板物品，也就没有右键）；
     * 这里再挡一道是为了「入口层断开」这条硬要求：那个变体里
     * {@code PhiMCRegistry.TABLET} 是 {@code null}，任何物品链路进来都会 NPE。
     */
    public static void openTablet() {
        if (dev.dsh.phimc.PhiMC.onlyMode) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> mc.setScreen(new SongSelectScreen(TabletScreen::new)));
    }

    /**
     * 平板潜行右键 = 重打上一首（设计文档 §2.1）。
     * 还没打过任何曲子时退回选曲界面。
     */
    public static void replayLast() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || session == null) return;
        if (session.currentChartId() == null || !session.startLast()) {
            openSongSelect(false);
            return;
        }
        mc.execute(() -> mc.setScreen(new PlayScreen(session)));
    }

    /** 生存玩法存档（{@code config/phimc/profile.json}）。 */
    public static dev.dsh.phimc.survival.Profile profile() {
        return session == null ? null : session.profile();
    }

    // ------------------------------------------------------------------
    // 材质包（用户自己的 Phira/Phigros 资源包 zip）
    // ------------------------------------------------------------------

    /** 上一次真正尝试应用的选择（{@code ""} = 不用），避免每次开界面都把纹理重载一遍。 */
    private static String appliedResPack;

    /**
     * 把 {@code profile.resPack} 里选中的材质包真正加载起来（切换时旧的纹理 / 打击音 Clip 一起释放）。
     *
     * <p>贴图要注册到 {@code TextureManager}，所以只能在客户端线程、且游戏资源管理器就绪之后调用；
     * 各界面在自己的 {@code init()} 里调 {@link #ensureResPack()}。设置页改完包直接调本方法。
     *
     * <p><b>顺带清掉 {@link SongMeta} 的缓存</b>：材质包本身不改变谱面 JSON，清它不是必需的，
     * 但这里是本 mod 唯一的「重新加载一切」钩子，而清完之后 {@code SongMeta.get} 会重新解析并
     * <b>重新回填</b> META 兜底（画师/曲师/谱师不会因为清了缓存而永久变成「未知」）。
     * 代价只有「切包的这一次」在后台重解析当前选中那一首。
     *
     * @param name {@code config/phimc/respack/} 下的 zip 文件名；{@code null} / 空串 = 不用材质包
     */
    public static void applyResPack(String name) {
        String want = name == null ? "" : name;
        if (want.equals(appliedResPack)) return;
        appliedResPack = want;
        HitSoundPlayer.clear();
        // 换包 = 「重新加载」语义；SongMeta.clear() 后下次 get 会重新解析 + 重新回填，
        // 不会出现「缓存清了、兜底也没了」的死状态（见 SongMeta.clear 的注释）。
        SongMeta.clear();
        try {
            ResPack pack = ResPack.select(want);
            if (want.isEmpty()) {
                LOG.info("[PhiMC] 材质包：不使用（音符 / 判定线用纯色矩形）");
            } else if (pack == null) {
                LOG.warn("[PhiMC] 材质包加载失败：{}（回退纯色矩形）", want);
            } else {
                LOG.info("[PhiMC] 材质包已应用：{}（{}）贴图 {} 张", pack.displayName(), want,
                        pack.slots().size());
            }
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 材质包加载异常：{}", want, t);
        }
        // 预解码 3 个打击音 + 预热 JavaSound mixer（~50ms 一次性），别留给第一个音符
        HitSoundPlayer.prepare();
    }

    /** 按存档里的选择应用材质包（幂等，界面 init 时调一次即可）。 */
    public static void ensureResPack() {
        Profile p = profile();
        if (p == null) return;
        applyResPack(p.resPack());
    }

    public static Session session() {
        return session;
    }

    /**
     * 把 Session 装进这个静态槽位 —— <b>只给 {@code phimcplayer}（打歌 only）入口用</b>。
     *
     * <p>为什么需要：全套界面（{@code SongSelectScreen} / {@code PlayScreen} /
     * {@code SettingsScreen}）都读 {@link #session()}。lite 变体为了「别重写整个客户端」，
     * 启动时自己建一份 {@link Session}（不注册物品、只注册打击音），然后<b>共享</b>这个槽位，
     * 这样界面代码一行都不用复制。
     *
     * <p>完整版入口不调用它（{@link #onInitializeClient()} 里自己建），所以行为不变。
     */
    public static void installSession(Session shared) {
        session = shared;
    }

    @SuppressWarnings("unused")
    private static void cycleNext() {
        if (session.songs().isEmpty()) return;
        songIndex = songIndex % session.songs().size();
        session.start(songIndex);
        songIndex++;
    }
}
