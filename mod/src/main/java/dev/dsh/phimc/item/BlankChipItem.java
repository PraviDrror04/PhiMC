package dev.dsh.phimc.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
//? if >=1.21 {
import net.minecraft.item.tooltip.TooltipType;
//?} elif >=1.20.5 {
/*import net.minecraft.client.item.TooltipType;
*///?} else {
/*import net.minecraft.client.item.TooltipContext;
import net.minecraft.world.World;
*///?}
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 空芯片（{@code phimc:blank_chip}）：还没载入谱面的芯片。
 *
 * <p>合成：3 铁锭 + 1 红石 + 1 金粒（{@code data/phimc/recipe/blank_chip.json}）。
 * 在平板曲库里对一张**已解锁**谱面点「拷芯片」→ 本物品 −1、得到一张
 * {@link ChipItem}（谱面芯片）。除此之外它就是个普通物品，不能右键做任何事 ——
 * 空芯片不提供「自己随机一张」的入口，随机赋谱只发生在**打开箱子那一刻**
 * （{@code survival/ChipLoot}），否则开箱随机就没意义了。
 */
public class BlankChipItem extends Item {

    public BlankChipItem(Settings settings) {
        super(settings);
    }

    @Override
    //? if >=1.21.5 {
    public void appendTooltip(ItemStack stack, TooltipContext context,
                              net.minecraft.component.type.TooltipDisplayComponent display,
                              java.util.function.Consumer<Text> tooltip, TooltipType type) {
    //?} elif >=1.20.5 {
    /*public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
    *///?} else {
    /*public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
    *///?}
        //? if >=1.21.5 {
        tooltip.accept(Text.translatable("tooltip.phimc.blank_chip.hint").formatted(Formatting.GRAY));
        //?} else {
        /*tooltip.add(Text.translatable("tooltip.phimc.blank_chip.hint").formatted(Formatting.GRAY));
        *///?}
    }
}
