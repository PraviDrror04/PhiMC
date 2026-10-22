package dev.dsh.phimc.item;

import dev.dsh.phimc.ItemHooks;
import dev.dsh.phimc.PhiMCRegistry;
import dev.dsh.phimc.survival.Profile;
import dev.dsh.phimc.survival.ServerSurvival;
import dev.dsh.phimc.survival.SurvivalEnv;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
//? if >=1.21 {
import net.minecraft.item.tooltip.TooltipType;
//?} elif >=1.20.5 {
/*import net.minecraft.client.item.TooltipType;
*///?}
//? if >=1.20.5 {
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
//?} else {
/*import net.minecraft.client.item.TooltipContext;
*///?}
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
//? if >=1.21.2 {
import net.minecraft.util.ActionResult;
//?} else {
/*import net.minecraft.util.TypedActionResult;
*///?}
import net.minecraft.world.World;

import java.util.List;

/**
 * 谱面芯片（{@code phimc:chart_chip}）：**一张芯片只载一张谱面，载入后不可改写**。
 *
 * <h2>数据（1.21.1 组件 / NBT）</h2>
 * 1.21 的物品 NBT 已经收进组件系统，这里用官方给的「自定义数据」组件
 * {@code minecraft:custom_data}（{@link DataComponentTypes#CUSTOM_DATA}），
 * 值是 {@link NbtCompound}，键 {@code chartId}：
 * <pre>{@code
 * { id: "phimc:chart_chip", count: 1,
 *   components: { "minecraft:custom_data": { chartId: "Verruckt#IN" } } }
 * }</pre>
 * 空芯片（还没载入谱面）就是**没有 chartId 这个键**（组件缺失 / 空 compound），
 * 战利品表里给出来的正是这个状态，等箱子被打开时才由 {@code survival/ChipLoot} 写入。
 * 用 {@code custom_data} 而不是自注册 {@code DataComponentType}，是因为前者零注册、
 * 能被原版战利品表/命令/记分板原样带出来，也不怕以后组件语义变。
 *
 * <h2>拷贝谱面到芯片（界面用的 API）</h2>
 * {@link #carveHeld(PlayerEntity, Profile, String)}：把一张**已解锁**谱面写进玩家背包里的
 * 空芯片，空芯片 −1、得到 1 张载好谱面的芯片。绝不提供「清空 / 改写」——
 * {@link #setChartId(ItemStack, String)} 是包内可见的底层入口，只有本类和
 * {@code ChipLoot}（开箱赋谱）与 {@code carve} 会用它。
 *
 * <h2>界面入口（已实现）</h2>
 * 选曲界面 {@code client/SongSelectScreen} 的每个<b>已解锁</b>列表行右侧挂了「拷芯片」按钮
 * （未解锁的行不生成按钮），点击直接调这里并把 {@link CarveResult} 翻成底部提示。
 * 旧的平板曲库列表（{@code TabletScreen} 里的那套）已按用户要求删除，全 mod 只有这一个入口。
 *
 * <p><b>双端物品</b>：客户端与专用服务端都会注册（{@code PhiMCMod} 的 main 入口）。本类常量池里
 * <b>没有</b> {@code net.minecraft.client.*}：界面动作走 {@link ItemHooks}，服务端掉落 / 开箱赋谱
 * 走 {@code survival/ChipLoot}，解锁写盘分客户端 / 服务端两条（见 {@link #use} 的说明）。
 */
public class ChipItem extends Item {

    /** 组件里放谱面 id 的键（{@code minecraft:custom_data.chartId}）。 */
    public static final String CHART_ID = "chartId";

    public ChipItem(Settings settings) {
        super(settings);
    }

    // ------------------------------------------------------------------
    // 组件读写（唯一的「真源」就是 ItemStack 的 custom_data）
    // ------------------------------------------------------------------

    /** 芯片里的谱面 id；不是芯片 / 没载入谱面时返回 {@code ""}（永远不返回 null）。 */
    public static String chartId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        //? if >=1.20.5 {
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null) return "";
        NbtCompound nbt = custom.copyNbt();
        //?} else {
        /*NbtCompound nbt = stack.getNbt();
        *///?}
        if (nbt == null || !nbt.contains(CHART_ID)) return "";
        //? if >=1.21.5 {
        String id = nbt.getString(CHART_ID).orElse("");
        //?} else {
        /*String id = nbt.getString(CHART_ID);
        *///?}
        return id == null ? "" : id.trim();
    }

    /** 这张芯片是否已经载了谱面。 */
    public static boolean hasChart(ItemStack stack) {
        return !chartId(stack).isEmpty();
    }

    /**
     * 写入谱面 id（**不可逆**：本 mod 不提供清空入口）。
     *
     * <p>给 {@link #carveHeld}、{@code survival/ChipLoot}（开箱赋谱）和调试命令用；
     * 界面不要调它，走 {@link #carveHeld}。
     */
    public static void setChartId(ItemStack stack, String chartId) {
        if (stack == null || stack.isEmpty()) return;
        String id = chartId == null ? "" : chartId.trim();
        //? if >=1.20.5 {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putString(CHART_ID, id));
        //?} else {
        /*stack.getOrCreateNbt().putString(CHART_ID, id);
        *///?}
    }

    /** 新建一张载好谱面的芯片。 */
    public static ItemStack ofChart(String chartId) {
        ItemStack stack = new ItemStack(PhiMCRegistry.CHART_CHIP);
        setChartId(stack, chartId);
        return stack;
    }

    /** 新建一张空芯片。 */
    public static ItemStack blank() {
        return new ItemStack(PhiMCRegistry.BLANK_CHIP);
    }

    // ------------------------------------------------------------------
    // 「拷到芯片」——界面唯一入口
    // ------------------------------------------------------------------

    /** {@link #carveHeld} 的结果。{@link #message()} 可直接当界面提示用。 */
    public enum CarveResult {
        OK("已拷到芯片"),
        NO_BLANK_CHIP("背包里没有空芯片"),
        NOT_UNLOCKED("这张谱面还没解锁"),
        INVALID_CHART("谱面 id 无效"),
        NO_PROFILE("存档还没准备好");

        private final String message;

        CarveResult(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /**
     * 把 {@code chartId} 拷进玩家背包里的空芯片。**只对已解锁的谱面生效**。
     *
     * <p>查找顺序：主手 → 副手 → 其余背包格（主手优先，符合「手上拿空芯片」的直觉）。
     * 「背包里没有」时返回 {@link CarveResult#NO_BLANK_CHIP}，不会凭空造芯片。
     *
     * <p>单人游戏里背包以**集成服务端**为准（否则下一次同步会把客户端的改动盖回去，
     * 与 {@code Wallet#pay} 同一套口径），改完再把同一格镜像回客户端，让界面立刻看到。
     *
     * @param clientPlayer 玩家（界面里的 {@code client.player}）
     * @param profile      存档（{@code PhiMCClient.session().profile()}）
     * @param chartId      要拷的谱面（{@code Verruckt#IN}）
     */
    public static CarveResult carveHeld(PlayerEntity clientPlayer, Profile profile, String chartId) {
        if (clientPlayer == null || profile == null) return CarveResult.NO_PROFILE;
        String id = chartId == null ? "" : chartId.trim();
        if (id.isEmpty()) return CarveResult.INVALID_CHART;
        if (!profile.isUnlocked(id)) return CarveResult.NOT_UNLOCKED;
        if (PhiMCRegistry.BLANK_CHIP == null || PhiMCRegistry.CHART_CHIP == null) {
            return CarveResult.NO_BLANK_CHIP;
        }

        ServerPlayerEntity server = SurvivalEnv.serverPlayer();
        PlayerInventory inv = server != null ? server.getInventory() : clientPlayer.getInventory();
        int slot = findBlank(inv);
        if (slot < 0 && server != null) {
            // 服务端还没有（刚拿到 / 刚同步过来），退一步看客户端背包
            inv = clientPlayer.getInventory();
            slot = findBlank(inv);
            server = null;                      // 这次只能改客户端
        }
        if (slot < 0) return CarveResult.NO_BLANK_CHIP;

        ItemStack inSlot = inv.getStack(slot);
        ItemStack chip = ofChart(id);
        if (inSlot.getCount() <= 1) {
            inv.setStack(slot, chip);
            // 把同一格镜像到客户端背包（单人时两边槽位下标一致）。
            // 只在客户端那格确实是同一个空芯片时才镜像，避免背包不同步时误删别的物品。
            if (server != null && isBlank(clientPlayer.getInventory().getStack(slot))) {
                clientPlayer.getInventory().setStack(slot, chip.copy());
            }
        } else {
            // 一叠空芯片：只消耗 1 个，多出来的那张塞背包 / 掉脚下
            inSlot.decrement(1);
            ItemStack extra = chip.copy();
            if (!inv.insertStack(extra) && !extra.isEmpty()) {
                PlayerEntity owner = server != null ? server : clientPlayer;
                owner.dropItem(extra, false);
            }
            PlayerInventory clientInv = clientPlayer.getInventory();
            ItemStack clientStack = clientInv.getStack(slot);
            if (server != null && isBlank(clientStack) && clientStack.getCount() > 1) {
                clientStack.decrement(1);       // 客户端只镜像「减 1」，多出来的那张等同步补
            }
        }
        return CarveResult.OK;
    }

    /** 背包里第一个空芯片的下标（主手 → 副手 → 其余格子；跳过盔甲格），没有返回 -1。 */
    private static int findBlank(PlayerInventory inv) {
        if (inv == null) return -1;
        //? if >=1.21.5 {
        int selected = inv.getSelectedSlot();
        //?} else {
        /*int selected = inv.selectedSlot;
        *///?}
        if (isBlank(inv.getStack(selected))) return selected;   // 主手
        int offhand = PlayerInventory.MAIN_SIZE + 4;                            // 36+4 = 副手
        if (offhand < inv.size() && isBlank(inv.getStack(offhand))) return offhand;
        for (int i = 0; i < inv.size(); i++) {
            if (i >= PlayerInventory.MAIN_SIZE && i < offhand) continue;        // 盔甲
            if (isBlank(inv.getStack(i))) return i;
        }
        return -1;
    }

    private static boolean isBlank(ItemStack stack) {
        return stack != null && !stack.isEmpty() && PhiMCRegistry.BLANK_CHIP != null
                && stack.isOf(PhiMCRegistry.BLANK_CHIP);
    }

    // ------------------------------------------------------------------
    // 右键 = 把这一个谱面写进存档（芯片不消耗）
    // ------------------------------------------------------------------

    /**
     * 右键：解锁这张芯片里的谱面，**芯片本身不消失**（可以拿去别的存档再解锁一次）。
     * 已经解锁时只提示「已解锁」，不会报错、也不会消耗。
     *
     * <h2>双端各写各的存档（这是刻意的）</h2>
     * <ul>
     *   <li><b>客户端</b>（含多人服务器里的自己）：走 {@link ItemHooks#useChartChip(ItemStack)}，
     *       解锁写进客户端自己的 {@code profile.json}（{@code config/phimc/servers/<地址>/}
     *       或单人 {@code saves/<世界>/phimc/}），提示弹在本地动作条 —— 与改造前一致；</li>
     *   <li><b>专用服务端</b>：走下面的服务端分支，把解锁写进<b>服主的世界存档</b>
     *       （{@code world/phimc/profile.json}，见 {@link ServerSurvival}），提示作为动作条包发给
     *       该玩家。单人游戏里 {@link ServerSurvival#profile()} 是 {@code null}（权威在客户端
     *       Session），所以这一段直接跳过，行为不变。</li>
     * </ul>
     */
    @Override
    //? if >=1.21.2 {
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
    //?} else {
    /*public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
    *///?}
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) {
            ItemHooks.useChartChip(stack);
        } else if (user instanceof ServerPlayerEntity player) {
            // 服务端：只有「专用服务端」才有服务端存档（单人那份归客户端 Session 管）
            Profile serverProfile = ServerSurvival.profile();
            if (serverProfile != null) {
                String id = chartId(stack);
                if (id.isEmpty()) {
                    player.sendMessage(Text.translatable("message.phimc.chip.empty"), true);
                } else if (serverProfile.unlock(id)) {
                    player.sendMessage(Text.translatable("message.phimc.chip.unlocked", id), true);
                } else {
                    player.sendMessage(Text.translatable("message.phimc.chip.already", id), true);
                }
            }
        }
        //? if >=1.21.2 {
        return ActionResult.SUCCESS;
        //?} else {
        /*return TypedActionResult.success(stack);
        *///?}
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
        // 先攒成 List，再按版本交给 List#addAll 或 Consumer#accept（1.21.11 的 tooltip 是 Consumer）
        List<Text> lines = new java.util.ArrayList<>();
        String id = chartId(stack);
        if (id.isEmpty()) {
            lines.add(Text.translatable("tooltip.phimc.chart_chip.empty").formatted(Formatting.GRAY));
        } else {
            lines.add(Text.translatable("tooltip.phimc.chart_chip.chart", Text.literal(id))
                    .formatted(Formatting.AQUA));
        }
        lines.add(Text.translatable("tooltip.phimc.chart_chip.hint").formatted(Formatting.DARK_GRAY));
        //? if >=1.21.5 {
        lines.forEach(tooltip::accept);
        //?} else {
        /*tooltip.addAll(lines);
        *///?}
    }
}
