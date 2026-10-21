package dev.dsh.phimc.item;

import dev.dsh.phimc.client.PhiMCClient;
import dev.dsh.phimc.client.Session;
import dev.dsh.phimc.survival.Profile;
import dev.dsh.phimc.survival.SurvivalEnv;
import dev.dsh.phimc.survival.TabletGift;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
//? if >=1.21.2 {
import net.minecraft.util.ActionResult;
//?} else {
/*import net.minecraft.util.TypedActionResult;
*///?}
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 平板（{@code phimc:tablet}，设计文档 §2.1）。
 *
 * <p>右键<b>直接打开选曲界面</b>（{@code SongSelectScreen}：曲绘 + 锁定状态 + 拷芯片），
 * 它的「关闭」按钮会变成「商店」，回商店再关闭才回游戏；潜行右键 = 重打上一首。
 * 不可堆叠、耐久 200（耐久扣减属于 S4，这里只给到属性）。
 *
 * <p><b>新平板赠品（§5）</b>：右键「打开平板」这条路径上先走一次
 * {@link TabletGift#giveIfNeeded}（幂等，只送一次），把商店里最便宜的 2 首未解锁谱面
 * 直接解锁。放在物品的 {@code use()} 里而不是界面 {@code init()} 里，是为了不碰界面的代码；
 * 也保证「拿到平板后先右键一次」就一定能拿到赠品，哪怕界面还没画出来。
 *
 * <p><b>多人限制</b>：物品只在客户端注册（同 {@link ChartPlayerItem}），
 * 只有单人游戏（集成服务端同进程）可用。
 */
public class TabletItem extends Item {

    /** 合成出来的平板耐久（设计文档 §2.1）。 */
    public static final int MAX_DAMAGE = 200;

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    public TabletItem(Settings settings) {
        super(settings);
    }

    @Override
    //? if >=1.21.2 {
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
    //?} else {
    /*public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
    *///?}
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) {
            if (user.isSneaking()) {
                PhiMCClient.replayLast();
            } else {
                giveTabletGift();
                PhiMCClient.openTablet();
            }
        }
        //? if >=1.21.2 {
        return ActionResult.SUCCESS;
        //?} else {
        /*return TypedActionResult.success(stack);
        *///?}
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
}
