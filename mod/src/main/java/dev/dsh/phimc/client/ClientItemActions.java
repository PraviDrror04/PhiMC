package dev.dsh.phimc.client;

import dev.dsh.phimc.ItemHooks;
import dev.dsh.phimc.PhiMC;
import dev.dsh.phimc.item.ChipItem;
import dev.dsh.phimc.survival.Profile;
import dev.dsh.phimc.survival.SurvivalEnv;
import dev.dsh.phimc.survival.TabletGift;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 物品右键的<b>客户端实现</b>（由 {@code PhiMCClient} 装进 {@link ItemHooks}）。
 *
 * <h2>为什么代码从这里来</h2>
 * 改造前这些动作直接写在三个物品类的 {@code use()} 里，而物品类是双端类 —— 专用服务端
 * 一加载物品就会连带加载 {@code MinecraftClient}。现在物品类只调 {@link ItemHooks} 的静态方法，
 * 真正的界面 / 提示 / 赠品逻辑全部收在这里（只在客户端进程里存在的类）。
 *
 * <p>行为与改造前<b>逐行等价</b>：谱面播放器右键开曲库、平板右键先送赠品再开曲库、
 * 平板潜行右键重打上一首、芯片右键写客户端存档并弹动作条提示。
 */
public final class ClientItemActions implements ItemHooks.ClientActions {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    @Override
    public void openChartPlayer(boolean sneak) {
        PhiMCClient.openSongSelect(sneak);
    }

    @Override
    public void openTablet() {
        if (PhiMC.onlyMode) return;
        giveTabletGift();
        PhiMCClient.openTablet();
    }

    @Override
    public void replayLast() {
        PhiMCClient.replayLast();
    }

    @Override
    public void useChartChip(ItemStack stack) {
        String id = ChipItem.chartId(stack);
        Profile profile = PhiMCClient.profile();
        if (id.isEmpty()) {
            notice(Text.translatable("message.phimc.chip.empty"));
        } else if (profile == null) {
            notice(Text.translatable("message.phimc.chip.no_profile"));
        } else if (profile.unlock(id)) {
            notice(Text.translatable("message.phimc.chip.unlocked", id));
        } else {
            notice(Text.translatable("message.phimc.chip.already", id));
        }
    }

    /**
     * 第一次打开平板：送 2 首（见 {@link TabletGift}）。
     *
     * <p>先确定世界种子 —— 赠品排序用的是 {@code PriceTable.of(chartId, worldSeed)}，
     * 种子没定下来之前算出来的「最便宜」可能和之后商店显示的价格不一致。
     */
    private static void giveTabletGift() {
        Profile profile = PhiMCClient.profile();
        Session session = PhiMCClient.session();
        if (profile == null || session == null) return;
        if (!profile.hasWorldSeed()) profile.ensureWorldSeed(SurvivalEnv.currentWorldSeed());
        List<String> picks = TabletGift.giveIfNeeded(profile, session.chartIds());
        if (picks.isEmpty()) return;
        String text = "新平板赠送：" + TabletGift.describe(picks) + "（已解锁）";
        // 本地聊天栏显示：赠品写的是客户端自己的 profile.json，用 sendMessage 会被当成聊天包发到服务端
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal(text));
        LOG.info("[PhiMC] 新平板赠送：{}", picks);
    }

    /**
     * 客户端提示（动作条）。解锁写的是客户端自己的 {@code profile.json}，所以提示也在本地显示，
     * 不用 {@code sendMessage}（那会把内容当成聊天包发给服务端）。
     */
    private static void notice(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.inGameHud != null) mc.inGameHud.setOverlayMessage(text, false);
    }
}
