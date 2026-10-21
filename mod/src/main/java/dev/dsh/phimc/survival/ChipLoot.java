package dev.dsh.phimc.survival;

import dev.dsh.phimc.PhiMCRegistry;
import dev.dsh.phimc.item.ChipItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
//? if >=1.21 {
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableSource;
//?} else {
/*import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.fabricmc.fabric.api.loot.v2.LootTableSource;
*///?}
//? if >=1.21 {
import net.minecraft.inventory.LootableInventory;
//?} else {
/*import net.minecraft.block.entity.LootableContainerBlockEntity;
*///?}
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.entry.LootTableEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
//? if >=1.20.5 {
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
//?}
//? if >=1.21 {
import net.minecraft.registry.RegistryWrapper;
//?}
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 箱子战利品里的谱面芯片（S5）：**「打开箱子那一刻」才随机赋谱**。
 *
 * <h2>为什么不能在世界生成时就赋谱</h2>
 * 谱面是玩家自己放进 {@code config/phimc/songs/} 的，开图时可能一个都没有；
 * 而且同一个箱子在不同存档里、不同玩家打开时应该给出不同的结果。所以战利品表里
 * 只放**一张没有 chartId 的芯片**（{@code data/phimc/loot_table/chests/chart_chip*.json}），
 * 谱面 id 由本类在箱子真正被打开时写进 {@code minecraft:custom_data.chartId}。
 *
 * <h2>时序（为什么可靠）</h2>
 * 原版带战利品表的箱子是**惰性生成**的：{@code LootableInventory.replenish → generateLoot(player)}
 * 在箱子第一次被打开（屏幕处理器建立）时才滚表，而不是世界生成时。于是：
 * <ol>
 *   <li>玩家右键箱子 → 服务端处理交互（同一次交互里战利品已进箱子）；</li>
 *   <li>本类的 {@code END_SERVER_TICK} 在**该 tick 的最末尾**跑（Fabric 的
 *       {@code ServerTickEvents} 就在 {@code MinecraftServer.tick()} 收尾处触发），
 *       所以此时箱子内容一定已经生成；</li>
 *   <li>玩家把芯片拿出来的点击包至少是**下一个 tick** 才到服务端 ——
 *       等他拿到手时 chartId 早已写好，并且随容器同步一起发到客户端。</li>
 * </ol>
 * 赋谱只发生在**服务端权威侧**（单人 = 集成服务端），写进的是箱子/背包里那份
 * {@link ItemStack} 的组件，客户端只是收到同步结果 —— 所以不存在「客户端自己偷偷摇一次号」。
 * 写完后调 {@link ScreenHandler#sendContentUpdates()} 让已经打开的界面立刻刷新。
 *
 * <h2>会不会被「复制物品」绕过</h2>
 * <ul>
 *   <li>赋谱是**一次性、单向**的：只处理 {@code chartId} 为空（= 还没载入）的芯片，
 *       一旦写入就再也不会改（{@link ChipItem} 本身不提供清空/改写入口），
 *       所以复制出来的只是「同一张已定号的芯片」，没法重新摇。</li>
 *   <li>想重摇必须拿到一张**还没赋谱**的芯片，而战利品表产出的芯片在被拿起来之前
 *       就已经赋好谱了；唯一能造出空芯片的是创造模式 {@code /give}（管理员自己给的，
 *       本来就是调试物品）。开箱前拆箱子也没用：没被打开过的箱子根本没生成战利品。</li>
 *   <li>真被复制的是「已定号的芯片」→ 只影响数量，不影响「这张芯片是哪首」，
 *       和「复制一张谱面解锁卡」是同一件事，不是本机制能防的（物品复制本身是别的漏洞）。</li>
 * </ul>
 *
 * <h2>注入方式</h2>
 * 用 Fabric 的 {@link LootTableEvents#MODIFY} 把 {@code phimc:chests/chart_chip}
 * （普通箱子，1/16）或 {@code phimc:chests/chart_chip_rare}（要塞/古城/末地城这类
 * 深层结构，1/4）挂到原版所有 {@code minecraft:chests/*} 战利品表上。
 * 概率写在 JSON 里（{@code empty} 条目的 weight），调概率不用重新编译；
 * 两个 JSON 本身也是合法战利品表，{@code /loot give @s loot phimc:chests/chart_chip}
 * 可以直接拿来测。
 *
 * <p><b>多人限制</b>：和本 mod 其他物品一样只在客户端注册，所以注入/赋谱只在单人游戏
 * （集成服务端同进程）里生效；专用服务器没有这个 mod，自然也不会有芯片。
 */
public final class ChipLoot {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 普通箱子用的芯片表（{@code data/phimc/loot_table/chests/chart_chip.json}）。 */
    public static final Identifier COMMON_TABLE = Identifier.of(PhiMCRegistry.MOD_ID, "chests/chart_chip");
    /** 深层结构箱子用的芯片表（{@code .../chests/chart_chip_rare.json}）。 */
    public static final Identifier RARE_TABLE = Identifier.of(PhiMCRegistry.MOD_ID, "chests/chart_chip_rare");

    /**
     * 用「稀有表」的原版箱子（去掉 {@code chests/} 前缀）：都是要塞/古城/末地城/林地府邸这类
     * 深层或高价值的箱子。其余 {@code chests/*} 一律用普通表。
     */
    private static final Set<String> RICH_CHESTS = Set.of(
            "end_city_treasure",
            "ancient_city",
            "woodland_mansion",
            "stronghold_corridor",
            "stronghold_crossing",
            "stronghold_library",
            "buried_treasure",
            "shipwreck_treasure",
            "desert_pyramid",
            "jungle_temple",
            "bastion_treasure"
    );

    /** 谱面候选集（由 {@code PhiMCRegistry} 注入 = {@code Session.chartIds()}）。 */
    private static Supplier<List<String>> chartIds = List::of;

    private ChipLoot() {
    }

    /**
     * 注册战利品注入 + 开箱赋谱钩子。只能调一次（{@code PhiMCRegistry.register()} 里调）。
     *
     * @param ids 谱面 id 候选集（延迟求值：注册时 {@code Session} 还没建好）
     */
    public static void init(Supplier<List<String>> ids) {
        chartIds = ids == null ? List::of : ids;
        //? if >=1.21 {
        LootTableEvents.MODIFY.register(ChipLoot::modifyV3);
        //?} elif >=1.20.5 {
        /*// 1.20.5/1.20.6 的 v2 事件已经是三参：(RegistryKey, Builder, Source)
        LootTableEvents.MODIFY.register((key, builder, source) -> inject(key.getValue(), builder, source));
        *///?} else {
        /*LootTableEvents.MODIFY.register((resourceManager, lootManager, id, builder, source)
                -> inject(id, builder, source));
        *///?}
        ServerTickEvents.END_SERVER_TICK.register(ChipLoot::onEndServerTick);
        LOG.info("[PhiMC] 芯片战利品已注册：{} / {}", COMMON_TABLE, RARE_TABLE);
    }

    // ------------------------------------------------------------------
    // 1) 把芯片表挂到原版箱子战利品表上
    // ------------------------------------------------------------------

    //? if >=1.21 {
    /** 1.21+ 的 {@code LootTableEvents.MODIFY}（v3）：带注册表查找器，key 是 RegistryKey。 */
    private static void modifyV3(RegistryKey<LootTable> key, LootTable.Builder builder, LootTableSource source,
                                 RegistryWrapper.WrapperLookup registries) {
        inject(key.getValue(), builder, source);
    }
    //?}

    /**
     * 真正的注入逻辑：只改原版 {@code minecraft:chests/*}。
     *
     * <p>1.20~1.20.4 的 fabric loot v2 事件直接给 {@link Identifier}，1.20.5+ 从 {@code RegistryKey} 里取，
     * 两边都收拢到这里，逻辑只写一份。
     */
    private static void inject(Identifier id, LootTable.Builder builder, LootTableSource source) {
        if (source != LootTableSource.VANILLA) return;          // 只改原版表，不碰数据包/别的 mod
        if (!"minecraft".equals(id.getNamespace()) || !id.getPath().startsWith("chests/")) return;
        Identifier table = RICH_CHESTS.contains(id.getPath().substring("chests/".length()))
                ? RARE_TABLE
                : COMMON_TABLE;
        builder.pool(LootPool.builder()
                .rolls(ConstantLootNumberProvider.create(1))
                //? if >=1.20.5 {
                .with(LootTableEntry.builder(RegistryKey.of(RegistryKeys.LOOT_TABLE, table))));
                //?} else {
                /*.with(LootTableEntry.builder(table)));
                *///?}
    }

    // ------------------------------------------------------------------
    // 2) 开箱那一刻赋谱
    // ------------------------------------------------------------------

    private static void onEndServerTick(MinecraftServer server) {
        List<String> ids = chartIds == null ? null : chartIds.get();
        if (ids == null || ids.isEmpty()) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            stamp(player, ids);
        }
    }

    /**
     * 扫玩家当前打开的容器：把没有 chartId 的芯片写上一个随机谱面。
     *
     * <p>只扫 {@link LootableInventory}（带战利品表的容器）的槽位；玩家自己的背包
     * （{@link PlayerScreenHandler}）也会扫一遍 —— 那是兜底：万一芯片从别的路径
     * （漏斗、拆箱子、别的 mod）进了背包，也不会永远是一张死芯片。
     */
    private static void stamp(ServerPlayerEntity player, List<String> ids) {
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null) return;
        boolean playerHandler = handler instanceof PlayerScreenHandler;
        Set<String> used = new HashSet<>();
        boolean dirty = false;
        for (Slot slot : handler.slots) {
            //? if >=1.21 {
            if (!playerHandler && !(slot.inventory instanceof LootableInventory)) continue;
            //?} else {
            /*if (!playerHandler && !(slot.inventory instanceof LootableContainerBlockEntity)) continue;
            *///?}
            ItemStack stack = slot.getStack();
            if (!isUnstampedChip(stack)) continue;
            String pick = pick(player, ids, used);
            ChipItem.setChartId(stack, pick);
            used.add(pick);
            dirty = true;
        }
        if (dirty) {
            handler.sendContentUpdates();                       // 已打开的界面立刻看到谱面名
            if (!playerHandler) {
                LOG.info("[PhiMC] 开箱赋谱：{}", player.getName().getString());
            }
        }
    }

    /** 随机挑一个候选谱面 id（同一容器内尽量不重复）。 */
    private static String pick(ServerPlayerEntity player, List<String> ids, Set<String> used) {
        List<String> pool = new ArrayList<>(ids.size());
        for (String id : ids) if (!used.contains(id)) pool.add(id);
        if (pool.isEmpty()) pool.addAll(ids);
        //? if >=1.21.9 {
        var random = player.getEntityWorld() != null ? player.getEntityWorld().getRandom() : null;
        //?} else {
        /*var random = player.getWorld() != null ? player.getWorld().getRandom() : null;
        *///?}
        int index = random == null
                ? (int) (Math.random() * pool.size()) % pool.size()
                : random.nextInt(pool.size());
        return pool.get(index);
    }

    private static boolean isUnstampedChip(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && PhiMCRegistry.CHART_CHIP != null
                && stack.isOf(PhiMCRegistry.CHART_CHIP)
                && ChipItem.chartId(stack).isEmpty();
    }
}
