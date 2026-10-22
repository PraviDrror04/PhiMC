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
 * 谱面播放器：右键打开选曲界面，潜行右键直接开上次那首。
 *
 * <p><b>双端物品</b>：物品本身在客户端与专用服务端都注册（{@code PhiMCMod} 的 main 入口），
 * 所以多人服务器里服务端认得出它。右键的界面动作是客户端专属的，走
 * {@link ItemHooks#openChartPlayer(boolean)}（客户端入口装的 {@code ClientItemActions}）；
 * 服务端那一侧只判断 {@code world.isClient() == false} 直接返回，所以本类常量池里
 * <b>没有</b> {@code net.minecraft.client.*}，专用服务端加载它不会
 * {@code NoClassDefFoundError}。
 */
public class ChartPlayerItem extends Item {

    public ChartPlayerItem(Settings settings) {
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
            ItemHooks.openChartPlayer(user.isSneaking());
        }
        //? if >=1.21.2 {
        return ActionResult.SUCCESS;
        //?} else {
        /*return TypedActionResult.success(stack);
        *///?}
    }
}
