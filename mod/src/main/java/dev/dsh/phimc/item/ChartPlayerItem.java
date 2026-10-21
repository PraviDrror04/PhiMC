package dev.dsh.phimc.item;

import dev.dsh.phimc.client.PhiMCClient;
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
 * <p>本 mod 是纯客户端 mod，物品只注册在客户端；单人游戏（集成服务端同进程）可正常使用，
 * 多人服务器里因为没有服务端注册，物品不会被发送。
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
            PhiMCClient.openSongSelect(user.isSneaking());
        }
        //? if >=1.21.2 {
        return ActionResult.SUCCESS;
        //?} else {
        /*return TypedActionResult.success(stack);
        *///?}
    }
}
