package dev.dsh.phimc.item;

import dev.dsh.phimc.ItemHooks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
//? if >=1.21.2 {
import net.minecraft.util.ActionResult;
//?} else {
/*import net.minecraft.util.TypedActionResult;
*///?}
import net.minecraft.world.World;

/**
 * 平板（{@code phimc:tablet}，设计文档 §2.1）。
 *
 * <p>右键<b>直接打开选曲界面</b>（{@code SongSelectScreen}：曲绘 + 锁定状态 + 拷芯片），
 * 它的「关闭」按钮会变成「商店」，回商店再关闭才回游戏；潜行右键 = 重打上一首。
 * 不可堆叠、耐久 200（耐久扣减属于 S4，这里只给到属性）。
 *
 * <p><b>新平板赠品（§5）</b>：右键「打开平板」这条路径上先走一次
 * {@code TabletGift.giveIfNeeded}（幂等，只送一次），把商店里最便宜的 2 首未解锁谱面
 * 直接解锁。这段逻辑现在在 {@code client/ClientItemActions}（客户端专属）；
 * 物品类只调 {@link ItemHooks#openTablet()} / {@link ItemHooks#replayLast()}。
 *
 * <p><b>双端物品</b>：客户端与专用服务端都会注册；本类常量池里没有
 * {@code net.minecraft.client.*}，专用服务端加载它是安全的。
 */
public class TabletItem extends Item {

    /** 合成出来的平板耐久（设计文档 §2.1）。 */
    public static final int MAX_DAMAGE = 200;

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
                ItemHooks.replayLast();
            } else {
                ItemHooks.openTablet();
            }
        }
        //? if >=1.21.2 {
        return ActionResult.SUCCESS;
        //?} else {
        /*return TypedActionResult.success(stack);
        *///?}
    }
}
