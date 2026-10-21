package dev.dsh.phimc.client;

import dev.dsh.phimc.client.respack.ResPack;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.survival.Profile;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：打击音 / 打击音音量 / <b>判定偏移</b> / 材质包，Esc 或「返回」回到来源界面。
 *
 * <p><b>没有「键盘 开/关」这一项</b>：键盘游玩恒开（见 {@code Session#setUseKeyboard}），
 * {@code Profile.useKeyboard} 字段只为存档兼容保留，界面不再暴露。
 *
 * <h2>布局常量（本机没有可交互客户端，界面只能靠这些常量核对）</h2>
 * <pre>
 * 面板宽 PANEL_W = 400，水平居中：cx-200 .. cx+200
 * y=6            面板顶
 * y=12           标题（居中）
 * y=30           说明行（灰色：改一项存一次）
 * y=44           说明行（灰色：存档路径）
 * TOP = 58       第一行
 * ROW_H = 28     行高（按钮高 18，行内垂直居中 +5）
 * 左侧文字列 dx  = cx - 188（PANEL_W/2 - 12）
 * 右侧控件列 dx  = cx + 10
 * 行 0 打击音     右侧 [开]/[关] 90×18
 * 行 1 音量       右侧 [-] 28 / 「80%」文字 / [+] 28 / [试听] 60
 * 行 2 判定偏移   右侧 [-] 28 / 「+50ms」文字 / [+] 28 / [重置] 60（步进 10ms，范围 ±300ms）
 * 行 3 偏移说明   灰色小字：「＋ = 谱面提前 …；－ = 谱面延后 …」
 * 行 4 材质包     右侧 [文件名] 92 / [↻] 26 / [打开文件夹] 58
 * 行 5/6         材质包现状 / 目录提示（灰色小字）
 * 底部 FOOTER_H = 28：[返回] 80×18，Esc 同效
 * </pre>
 *
 * <p><b>偏移的符号</b>：{@code +50ms} = <b>谱面提前</b> 50ms（同一个音频位置对应的谱面时间更大，
 * 音符更早到判定线）；{@code -50ms} = 谱面延后。和谱面包自带的 {@code Chart.offset} 是两个独立的东西，
 * 两个都生效（见 {@code Session#seconds()}）。
 *
 * <p><b>持久化</b>：所有控件直接写 {@link Profile}，而 {@code Profile} 的每个 setter 内部
 * 都会立刻 {@code save()} 到 {@code config/phimc/profile.json}（重进游戏保持）。
 * 材质包换完还会立刻调 {@link PhiMCClient#applyResPack(String)} 把贴图 / 打击音换掉。
 */
public class SettingsScreen extends Screen {

    // ---------------- 布局常量（见类注释） ----------------
    private static final int PANEL_W = 400;
    private static final int TOP = 58;
    private static final int ROW_H = 28;
    private static final int BTN_H = 18;
    private static final int FOOTER_H = 28;
    /** 音量步进 10%。 */
    private static final double VOLUME_STEP = 0.1;
    /** 判定偏移步进：10ms（需求：-300ms … +300ms，步进 10）。 */
    private static final double OFFSET_STEP_MS = 10;

    // ---------------- 颜色 ----------------
    private static final int C_PANEL = 0xC0101014;
    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_GRAY = 0xFF8A8A8A;
    private static final int C_ACCENT = 0xFFB4E1FF;

    /** 来源界面（返回时回到它），可为 null（回到游戏）。 */
    private final Screen parent;
    /** 底部提示（音量试听 / 切包结果）。 */
    private String notice = "";
    /** 材质包候选，下标 0 恒为「不使用」（= 空串）。 */
    private List<String> packOptions = List.of("");

    public SettingsScreen(Screen parent) {
        super(Text.literal("PhiMC 设置"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        Session session = PhiMCClient.session();
        Profile profile = session == null ? null : session.profile();
        if (profile == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("返回（没有存档）"), b -> close())
                    .dimensions(width / 2 - 80, TOP + 20, 160, 20).build());
            return;
        }
        // 存档里选的材质包先应用一次（幂等；贴图要注册到 TextureManager，只能在客户端线程做）
        PhiMCClient.ensureResPack();

        int cx = width / 2;
        packOptions = buildPackOptions();

        // ---- 行 0：打击音 开 / 关 ----
        addDrawableChild(ButtonWidget.builder(onOffText(profile.hitSound()), b -> {
            boolean on = !profile.hitSound();
            profile.hitSound(on);                       // 内部立刻 save()
            if (on) {
                preview(profile);
            } else {
                notice = "打击音已关闭（Bad/Miss 本来就不播）";
            }
            clearAndInit();
        }).dimensions(cx + 60, rowY(0), 90, BTN_H).build());

        // ---- 行 1：音量 - / 百分比 / + / 试听 ----
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
            profile.hitSoundVolume(step(profile.hitSoundVolume(), -VOLUME_STEP));
            preview(profile);
            clearAndInit();
        }).dimensions(cx + 10, rowY(1), 28, BTN_H).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
            profile.hitSoundVolume(step(profile.hitSoundVolume(), VOLUME_STEP));
            preview(profile);
            clearAndInit();
        }).dimensions(cx + 82, rowY(1), 28, BTN_H).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("试听"), b -> preview(profile))
                .dimensions(cx + 120, rowY(1), 60, BTN_H).build());

        // ---- 行 2：判定偏移 - / 值 / + / 重置（步进 10ms，范围 ±300ms，存 profile.offsetMs） ----
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
            profile.offsetMs(stepOffset(profile.offsetMs(), -OFFSET_STEP_MS));
            notice = "偏移：" + offsetText(profile.offsetMs()) + "（" + offsetHint(profile.offsetMs()) + "）";
            clearAndInit();
        }).dimensions(cx + 10, rowY(2), 28, BTN_H).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
            profile.offsetMs(stepOffset(profile.offsetMs(), OFFSET_STEP_MS));
            notice = "偏移：" + offsetText(profile.offsetMs()) + "（" + offsetHint(profile.offsetMs()) + "）";
            clearAndInit();
        }).dimensions(cx + 82, rowY(2), 28, BTN_H).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("重置"), b -> {
            profile.offsetMs(0);
            notice = "偏移已重置为 0ms";
            clearAndInit();
        }).dimensions(cx + 120, rowY(2), 60, BTN_H).build());

        // ---- 行 4：材质包（点一下换下一个，含「不使用」）+ 刷新 + 打开文件夹 ----
        addDrawableChild(ButtonWidget.builder(Text.literal(packLabel(profile.resPack())), b -> {
            String next = nextPack(profile.resPack());
            profile.resPack(next);                      // 内部立刻 save()
            PhiMCClient.applyResPack(next);         // 贴图 / 打击音立刻换
            notice = next.isEmpty() ? "材质包：不使用（纯色矩形）" : "材质包：" + next;
            clearAndInit();
        }).dimensions(cx + 10, rowY(4), 92, BTN_H).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("↻"), b -> {
            notice = "材质包列表已刷新（" + Math.max(0, buildPackOptions().size() - 1) + " 个 zip）";
            clearAndInit();
        }).dimensions(cx + 106, rowY(4), 26, BTN_H).build());

        // 「打开文件夹」：系统文件管理器打开 config/phimc/respack/（不存在就建），
        // 让用户不用记路径就能把 .zip 丢进去。Util.getOperatingSystem().open(File)
        // 在 1.20 ~ 1.21.11 全系可用（mappings: method_672），不需要 Stonecutter 分支。
        addDrawableChild(ButtonWidget.builder(Text.literal("打开文件夹"), b -> openResPackFolder())
                .dimensions(cx + 136, rowY(4), 58, BTN_H).build());

        // ---- 键盘游玩 开/关：已删（键盘恒开，Profile.useKeyboard 只为存档兼容保留） ----

        // ---- 底部：返回 ----
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> close())
                .dimensions(cx - 40, height - FOOTER_H, 80, BTN_H).build());
    }

    private static int rowY(int i) {
        return TOP + i * ROW_H;
    }

    /** 开关按钮的文案（按钮上只显示「开」/「关」，当前值在 render 的左侧文字里也看不到，所以靠按钮文字本身区分）。 */
    private static Text onOffText(boolean on) {
        return Text.literal(on ? "开" : "关");
    }

    /** 材质包候选：下标 0 = 不使用（空串），后面是 {@code respack/} 里的 zip（排序稳定）。 */
    private static List<String> buildPackOptions() {
        List<String> out = new ArrayList<>();
        out.add("");
        try {
            out.addAll(ResPack.list());
        } catch (Throwable ignored) {
            // 目录读不了就只有「不使用」
        }
        return out;
    }

    /** 当前选择 → 显示名（文件太长时截断，「不使用」显示成中文）。按钮现在只有 92 宽，所以截到 9 个字。 */
    private static String packLabel(String current) {
        if (current == null || current.isBlank()) return "不使用";
        return shortText(current, 9);
    }

    /**
     * 用系统文件管理器打开 {@code config/phimc/respack/}。
     *
     * <p>路径不硬编码：{@link ResPack#dir()} 内部走 {@code ConfigDir.modDir(configDir)} +
     * {@code ResPack.DIR_NAME}（{@code "respack"}），而且它自己会
     * {@code Files.createDirectories} —— 所以「一个 zip 都还没放、目录也不存在」时点下去也能打开。
     *
     * <p>用 {@code Util.getOperatingSystem().open(File)}（Yarn {@code method_672}）：跨 Windows / macOS
     * / Linux，1.20 ~ 1.21.11 映射一致，不需要 Stonecutter 分支，也不引 {@code java.awt.Desktop}
     * （部分 JRE 是 headless，Desktop 会抛 UnsupportedOperationException）。
     */
    private void openResPackFolder() {
        final Path dir;
        try {
            dir = ResPack.dir();
        } catch (Throwable t) {
            notice = "取资源包目录失败：" + t;
            return;
        }
        try {
            Util.getOperatingSystem().open(dir.toFile());
            notice = "已打开资源包目录：" + dir;
        } catch (Throwable t) {
            notice = "打开失败（" + t + "）：" + dir;
        }
    }

    /** 点一下换下一个（在「不使用」+ 目录列表里循环）。 */
    private String nextPack(String current) {
        if (packOptions.isEmpty()) return "";
        String cur = current == null ? "" : current;
        int idx = packOptions.indexOf(cur);
        if (idx < 0) return "";
        return packOptions.get((idx + 1) % packOptions.size());
    }

    /** 音量步进并夹到 0~1（保留两位小数，避免 0.8999999 这种显示）。 */
    private static double step(double v, double delta) {
        double n = Math.max(0, Math.min(1, v + delta));
        return Math.round(n * 100.0) / 100.0;
    }

    /**
     * 偏移步进：按 10ms 走，夹到 ±{@link Profile#OFFSET_LIMIT_MS}。
     *
     * <p>先四舍五入到 10 的整数倍再加减，这样「手改过的 12.5ms」点一下也会落回 10/20 的刻度。
     */
    private static double stepOffset(double ms, double delta) {
        double cur = Math.round(ms / OFFSET_STEP_MS) * OFFSET_STEP_MS;
        double n = cur + delta;
        double limit = Profile.OFFSET_LIMIT_MS;
        return Math.max(-limit, Math.min(limit, n));
    }

    /** 偏移显示：{@code +50ms} / {@code -30ms} / {@code 0ms}。 */
    private static String offsetText(double ms) {
        long v = Math.round(ms);
        return (v > 0 ? "+" : "") + v + "ms";
    }

    /** 偏移的一句话语义（底部提示用）。 */
    private static String offsetHint(double ms) {
        if (Math.abs(ms) < 1e-9) return "不补偿";
        return ms > 0 ? "谱面提前 " + Math.round(ms) + "ms" : "谱面延后 " + Math.round(-ms) + "ms";
    }

    /** 试听：用当前设置播一下 Tap 的打击音，顺便验证音源（资源包 / 内置）有没有接上。 */
    private void preview(Profile profile) {
        if (!profile.hitSound()) {
            notice = "打击音是关的，先打开才能试听";
            return;
        }
        if (profile.hitSoundVolume() <= 0.01) {
            notice = "音量是 0%，加一点才听得见";
            return;
        }
        HitSoundPlayer.play(Chart.Note.Type.TAP, profile);
        ResPack pack = ResPack.current();
        String from = pack != null && pack.sound("click") != null ? "资源包" : "内置";
        notice = "试听 click（" + from + "）· 音量 " + percent(profile.hitSoundVolume());
    }

    private static String percent(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String shortText(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 1.20.2+ 框架会在 render() 前自动调 renderBackground；1.20/1.20.1 不会，补一次保持观感一致
        //? if <1.20.2 {
        /*this.renderBackground(ctx);
        *///?}
        int cx = width / 2;
        ctx.fill(cx - PANEL_W / 2, 6, cx + PANEL_W / 2, Math.max(28, height - 6), C_PANEL);
        super.render(ctx, mouseX, mouseY, delta);

        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 12, C_WHITE);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("改动立刻保存到 config/phimc/profile.json"),
                cx, 30, C_GRAY);

        Session session = PhiMCClient.session();
        Profile profile = session == null ? null : session.profile();
        if (profile == null) return;

        int labelX = cx - PANEL_W / 2 + 12;

        // ---- 行 0：打击音 ----
        ctx.drawText(textRenderer, "打击音", labelX, rowY(0) + 5, C_WHITE, false);

        // ---- 行 1：音量（- / 百分比 / + / 试听） ----
        ctx.drawText(textRenderer, "打击音音量", labelX, rowY(1) + 5, C_WHITE, false);
        String pct = percent(profile.hitSoundVolume());
        ctx.drawText(textRenderer, pct, cx + 60 - textRenderer.getWidth(pct) / 2, rowY(1) + 5,
                profile.hitSound() ? C_ACCENT : C_GRAY, false);

        // ---- 行 2：判定偏移（- / 值 / + / 重置） ----
        ctx.drawText(textRenderer, "判定偏移", labelX, rowY(2) + 5, C_WHITE, false);
        String off = offsetText(profile.offsetMs());
        ctx.drawText(textRenderer, off, cx + 60 - textRenderer.getWidth(off) / 2, rowY(2) + 5,
                Math.abs(profile.offsetMs()) < 1e-9 ? C_GRAY : C_ACCENT, false);

        // ---- 行 3：偏移的符号语义（必须写在界面上，否则玩家不知道该加还是该减） ----
        ctx.drawText(textRenderer,
                "＋ = 谱面提前（音符更早到判定线）；－ = 谱面延后；步进 "
                        + (int) OFFSET_STEP_MS + "ms，范围 ±" + (int) Profile.OFFSET_LIMIT_MS + "ms",
                labelX, rowY(3) + 5, C_GRAY, false);

        // ---- 行 4：材质包 ----
        ctx.drawText(textRenderer, "材质包", labelX, rowY(4) + 5, C_WHITE, false);

        // ---- 行 5/6：材质包现状 + 目录说明 ----
        ResPack pack = ResPack.current();
        String packNow;
        int packColor;
        if (pack == null) {
            packNow = "当前：不使用材质包（音符 / 判定线用纯色矩形）";
            packColor = C_GRAY;
        } else {
            String author = pack.author().isBlank() ? "" : " · " + pack.author();
            packNow = "当前：" + pack.displayName() + author
                    + " · 贴图 " + pack.slots().size() + " 张"
                    + " · 贴图槽 " + String.join("/", ResPack.SLOTS);
            packColor = C_ACCENT;
        }
        ctx.drawText(textRenderer, shortText(packNow, 40), labelX, rowY(5) + 5, packColor, false);
        // 「列出」= 候选来自 ResPack.list()：这里报个数，点材质包按钮在「不使用」+ 列表里逐个切换；
        // 新包不会自己冒出来 —— 路径提示 + 「打开文件夹」按钮就是给这一步用的
        int packs = Math.max(0, packOptions.size() - 1);
        ctx.drawText(textRenderer,
                "respack 目录 " + packs + " 个 zip · 点文件名切换，或点【打开文件夹】放新包",
                labelX, rowY(6) + 5, C_GRAY, false);

        if (!notice.isEmpty()) {
            ctx.drawText(textRenderer, shortText(notice, 40), labelX, height - FOOTER_H - 14, C_ACCENT, false);
        }
    }

    @Override
    public boolean shouldPause() {
        // 与 TabletScreen / SongSelectScreen 一致：不暂停世界
        return false;
    }

    @Override
    public void close() {
        back();
    }

    /** 回到来源界面（{@code setScreen} 必须在客户端主线程执行，见任务里的渲染线程约束）。 */
    private void back() {
        if (client == null) return;
        final Screen to = parent;
        client.execute(() -> client.setScreen(to));
    }
}
