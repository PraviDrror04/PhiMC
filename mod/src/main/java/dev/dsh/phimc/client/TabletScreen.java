package dev.dsh.phimc.client;

import dev.dsh.phimc.survival.PriceTable;
import dev.dsh.phimc.survival.Profile;
import dev.dsh.phimc.survival.Shop;
import dev.dsh.phimc.survival.SurvivalEnv;
import dev.dsh.phimc.survival.Wallet;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 平板的<b>商店页</b>（设计文档 §3）：Esc 关闭。
 *
 * <h2>曲库已经不在这个界面里了</h2>
 * 用户反馈「从平板打开是老 ui（删去此 ui）」：这里原来自己画了一套纯文字的曲库列表
 * （分组标题 + 每行「难度」按钮 + 成绩列 + 「拷芯片」按钮），和 {@link SongSelectScreen}
 * （带曲绘 / 锁定状态 / 拷芯片）重复，两条入口给两套界面。
 * 现在：
 * <ul>
 *   <li>平板右键 = {@code PhiMCClient.openTablet()} 直接开 {@link SongSelectScreen}；</li>
 *   <li>本界面只剩商店，页签「曲库」不再是本地页，而是
 *       {@code client.setScreen(new SongSelectScreen(TabletScreen::new))}（回来还落在商店）；</li>
 *   <li>旧的曲库渲染 / 行按钮 / 拷芯片代码（{@code Line}、{@code libraryLines}、
 *       {@code renderLibrary}、{@code startChart}、{@code copyToChip}）已整段删除，没有死代码。</li>
 * </ul>
 *
 * <h2>布局常量（本机没有可交互客户端，界面只能靠这些常量核对）</h2>
 * <pre>
 * 面板宽 PANEL_W = 380，水平居中：cx-190 .. cx+190
 * y=6            面板顶
 * y=12           标题（居中）
 * y=26           页签按钮（高 18）：曲库 [cx-96, 90]，商店 [cx+6, 90]
 * y=48           说明行（灰色小字：货架数 / 种子 / 刷新倒计时）
 * y=64           TOP，内容区第一行
 * ROW_H = 22     行高（按钮高 18，行内居中偏移 2）
 * 商店行：整行 [cx-186, ROW_W=190]；右侧列从 cx+8 起（价格图标 → ×数量 → 买得起/还差）
 * 底部 FOOTER_H = 26：上一页 / 下一页 / 设置 / 关闭（70×18，间距 10）
 * 每页行数 = min(8, (height - TOP - 44) / ROW_H)，随窗口高度自适应
 * </pre>
 *
 * <p>商店：{@link Shop#roll} 从 {@code shopSeed} 洗出的稳定优先队列里取 6 个未解锁谱面；
 * 点击购买 → {@link Wallet#pay} 扣物品 → {@link Profile#unlock} → 重建列表。
 */
public class TabletScreen extends Screen {

    // ---------------- 布局常量（见类注释） ----------------
    private static final int PANEL_W = 380;
    private static final int TOP = 64;
    private static final int ROW_H = 22;
    /** 商店行的整行按钮宽。 */
    private static final int ROW_W = 190;
    private static final int BTN_H = 18;
    private static final int MAX_ROWS = 8;
    private static final int FOOTER_H = 26;

    // ---------------- 颜色 ----------------
    private static final int C_PANEL = 0xC0101014;
    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_GRAY = 0xFF8A8A8A;
    private static final int C_FC = 0xFFB4E1FF;
    private static final int C_OK = 0xFF7CFC8A;
    private static final int C_NO = 0xFFFF7070;

    private int page;
    /** 底部提示（购买结果 / 买不起的原因）。 */
    private String notice = "";
    /** 当前货架（谱面 id，按货架顺序）。 */
    private List<String> shelf = List.of();
    private int rowsPerPage = MAX_ROWS;
    /** init 里算好的说明行（种子 / 刷新倒计时）。 */
    private String pendingHint = "";

    public TabletScreen() {
        super(Text.literal("PhiMC 平板 · 商店"));
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // 打歌 only（phimcplayer）：物品根本不存在（PhiMCRegistry 没注册），这个界面任何一处
        // 走到物品 / 背包都会有 NPE 风险，所以在入口层直接断开 —— 打开即关闭，回到游戏。
        if (dev.dsh.phimc.PhiMC.onlyMode) {
            close();
            return;
        }
        Session session = PhiMCClient.session();
        Profile profile = session == null ? null : session.profile();
        if (profile == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("关闭（没有曲目）"), b -> close())
                    .dimensions(width / 2 - 80, TOP + 20, 160, 20).build());
            return;
        }
        // 存档里选的材质包（贴图要注册到 TextureManager，只能在客户端线程 / 界面 init 时做）
        PhiMCClient.ensureResPack();

        ensureSeed(profile);
        long now = SurvivalEnv.nowTicks();
        rowsPerPage = Math.max(1, Math.min(MAX_ROWS, (height - TOP - 44) / ROW_H));

        // 货架
        shelf = Shop.roll(profile.shopSeed(), session.chartIds(), profile.unlocked(), Shop.SLOTS);

        int cx = width / 2;

        // ---- 页签 ----
        // 「曲库」不再是本地页：直接开新的选曲界面，返回时回到本界面（商店）
        addDrawableChild(ButtonWidget.builder(Text.literal("曲库"), b -> {
            if (client == null) return;
            client.execute(() -> client.setScreen(new SongSelectScreen(TabletScreen::new)));
        }).dimensions(cx - 96, 26, 90, BTN_H).build());

        // 「商店」就是本页，自身变灰
        addDrawableChild(ButtonWidget.builder(Text.literal("商店"), b -> clearAndInit())
                .dimensions(cx + 6, 26, 90, BTN_H).build()).active = false;

        // ---- 内容（商店货架） ----
        int pages = Math.max(1, (shelf.size() + rowsPerPage - 1) / rowsPerPage);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < shelf.size() && i < rowsPerPage; i++) {
            final String chartId = shelf.get(i);
            int y = rowY(i);
            String label = shortText(displayName(chartId), 26);
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> buy(profile, chartId))
                    .dimensions(cx - 186, y, ROW_W, BTN_H).build());
        }

        // ---- 底部 ----
        // 四个等宽槽位（70×18，间距 10）：上一页 / 下一页 / 设置 / 关闭，整体在面板内居中
        int bottom = height - FOOTER_H;
        int bw = 70;
        if (page > 0) {
            addDrawableChild(ButtonWidget.builder(Text.literal("← 上一页"), b -> {
                page--;
                clearAndInit();
            }).dimensions(cx - 156, bottom, bw, BTN_H).build());
        }
        if (pages > 1 && page < pages - 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("下一页 →"), b -> {
                page++;
                clearAndInit();
            }).dimensions(cx - 76, bottom, bw, BTN_H).build());
        }
        // 设置入口：来源界面 = 本界面，返回时回到平板（setScreen 必须在客户端主线程，走 client.execute）
        addDrawableChild(ButtonWidget.builder(Text.literal("设置"), b -> {
            if (client == null) return;
            client.execute(() -> client.setScreen(new SettingsScreen(TabletScreen.this)));
        }).dimensions(cx + 4, bottom, bw, BTN_H).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> close())
                .dimensions(cx + 84, bottom, bw, BTN_H).build());

        // 世界种子/货架时间的提示每帧重算，这里只做一次刷新检查（构造时已刷新）
        this.pendingHint = hintText(profile, now);
    }

    /** 派生世界种子（只在存档里没有时写一次），并检查货架是否该每日刷新。 */
    private static long ensureSeed(Profile profile) {
        long seed = profile.worldSeed();
        if (seed == 0) {
            profile.ensureWorldSeed(SurvivalEnv.currentWorldSeed());
            seed = profile.worldSeed();
        }
        long now = SurvivalEnv.nowTicks();
        if (profile.shopDue(now)) {
            profile.refreshShop(SurvivalEnv.nextShopSeed(seed, now), now + Shop.REFRESH_TICKS);
        }
        return seed;
    }

    private String hintText(Profile profile, long now) {
        long left = Math.max(0, profile.shopRefreshAt() - now);
        return "货架 " + shelf.size() + "/" + Shop.SLOTS + " · 种子 " + profile.shopSeed()
                + " · " + left + " tick 后刷新 · 已解锁 " + profile.unlockedCount() + " 个谱面";
    }

    private static int rowY(int i) {
        return TOP + i * ROW_H;
    }

    /** 按字符数截断（防止长曲名盖住右侧列）。 */
    private static String shortText(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 谱面 id → 显示名（{@code 曲目 · 难度}）。 */
    private static String displayName(String chartId) {
        return PriceTable.songOf(chartId) + " · " + PriceTable.difficultyOf(chartId);
    }

    // ------------------------------------------------------------------
    // 购买
    // ------------------------------------------------------------------

    /** 点击购买：检查 → 扣物品 → 解锁 → 刷新列表（设计文档 §3.2）。 */
    private void buy(Profile profile, String chartId) {
        if (client == null) return;
        long worldSeed = profile.worldSeed();
        PriceTable.Price price = PriceTable.of(chartId, worldSeed);
        int have = Wallet.countFor(client.player, price);
        if (have < price.count()) {
            notice = "买不起：" + displayName(chartId) + " 还差 " + (price.count() - have)
                    + " 个 " + Wallet.itemName(price);
            return;
        }
        if (!Wallet.pay(client.player, price)) {
            notice = "扣物品失败（多人服务器？见 mod 说明）";
            return;
        }
        boolean added = profile.unlock(chartId);
        notice = (added ? "已解锁 " : "已拥有 ") + displayName(chartId)
                + "（" + Wallet.itemName(price) + " ×" + price.count() + "）";
        clearAndInit();
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
        // 面板底
        ctx.fill(cx - PANEL_W / 2, 6, cx + PANEL_W / 2, Math.max(28, height - 6), C_PANEL);
        super.render(ctx, mouseX, mouseY, delta);

        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 12, C_WHITE);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(pendingHint), cx, 48, C_GRAY);

        Session session = PhiMCClient.session();
        Profile profile = session == null ? null : session.profile();
        if (profile == null) return;

        renderShop(ctx, profile, cx);

        if (!notice.isEmpty()) {
            ctx.drawText(textRenderer, shortText(notice, 62), cx - 186, height - FOOTER_H - 14, C_FC, false);
        }
    }

    private void renderShop(DrawContext ctx, Profile profile, int cx) {
        if (shelf.isEmpty()) {
            Session session = PhiMCClient.session();
            String msg = session == null || session.songs().isEmpty()
                    ? "songs/ 目录里还没有谱面"
                    : "没有可售的谱面了（全部已解锁）";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), cx, TOP + 8, C_GRAY);
            return;
        }
        long worldSeed = profile.worldSeed();
        for (int i = 0; i < shelf.size() && i < rowsPerPage; i++) {
            String chartId = shelf.get(i);
            int y = rowY(i);
            PriceTable.Price price = PriceTable.of(chartId, worldSeed);
            Item item = Wallet.resolve(price);
            boolean afford = client != null && Wallet.canAfford(client.player, price);

            // 价格：物品图标 + ×数量
            int iconX = cx + 8;
            int iconY = y + 1;
            if (item != null) {
                ctx.drawItem(new ItemStack(item), iconX, iconY);
            } else {
                ctx.drawText(textRenderer, "?", iconX + 4, y + 5, C_NO, false);
            }
            ctx.drawText(textRenderer, "×" + price.count(), iconX + 20, y + 5, C_WHITE, false);

            // 买得起 / 差多少
            String tag;
            int color;
            if (afford) {
                tag = "买得起";
                color = C_OK;
            } else {
                int have = Wallet.countFor(client.player, price);
                tag = "还差 " + Math.max(0, price.count() - have);
                color = C_NO;
            }
            ctx.drawText(textRenderer, tag, cx + 62, y + 5, color, false);
        }
    }

    @Override
    public boolean shouldPause() {
        // 与 SongSelectScreen 一致：不暂停世界（生存里打开平板世界继续跑）
        return false;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(null);
    }
}
