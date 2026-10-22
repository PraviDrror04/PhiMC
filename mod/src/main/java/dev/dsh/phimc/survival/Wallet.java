package dev.dsh.phimc.survival;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 购买扣物品：{@code PlayerInventory} 检查 + 扣除。
 *
 * <p>单人游戏里客户端背包只是服务端的副本，直接改会被下一次同步覆盖，
 * 所以优先改集成服务端玩家的背包（权威），同时把同一数量从客户端背包里扣掉，
 * 让界面立刻生效。
 *
 * <p><b>专用服务端</b>：服务端侧调进来时 {@code player} 本身就是
 * {@link ServerPlayerEntity}（权威背包），直接用它的；客户端侧（界面上那个
 * {@code ClientPlayerEntity}）没有本机服务端玩家，只能改客户端背包 ——
 * 这与改造前「纯客户端 mod」的口径一致，真正的服务端扣款要等结果/购买包（未实现）。
 *
 * <p><b>本类零客户端依赖</b>：不引用 {@code MinecraftClient}，权威背包统一由
 * {@link #authoritative(PlayerEntity)} 从 {@link SurvivalEnv} 取。
 */
public final class Wallet {

    private Wallet() {
    }

    /**
     * 「权威背包」：服务端玩家 → 自己；单人 → 集成服务端里同 UUID 的玩家；
     * 都没有（多人客户端 / 主菜单）→ 调用方自己那个（客户端背包，只是本地镜像）。
     */
    private static PlayerInventory authoritative(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity sp) return sp.getInventory();
        ServerPlayerEntity serverPlayer = SurvivalEnv.serverPlayer();
        if (serverPlayer != null) return serverPlayer.getInventory();
        return player == null ? null : player.getInventory();
    }

    /** 价格里的物品注册名 → {@code Item}，解析失败返回 null。 */
    public static Item resolve(PriceTable.Price price) {
        if (price == null) return null;
        Identifier id = Identifier.tryParse(price.itemId());
        if (id == null) return null;
        //? if >=1.21.2 {
        return Registries.ITEM.getOptionalValue(id).orElse(null);
        //?} else {
        /*return Registries.ITEM.getOrEmpty(id).orElse(null);
        *///?}
    }

    /** {@code stack} 是否算作 {@code item}（只按物品比，不看 NBT/组件）。 */
    private static boolean matches(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.isOf(item);
    }

    /** 背包里某物品的总数。 */
    public static int count(PlayerInventory inv, Item item) {
        if (inv == null || item == null) return 0;
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (matches(s, item)) n += s.getCount();
        }
        return n;
    }

    /**
     * 扣物品，最多扣到「背包里实际有的数量」为止。
     *
     * @return 实际扣掉的数量
     */
    public static int remove(PlayerInventory inv, Item item, int amount) {
        if (inv == null || item == null || amount <= 0) return 0;
        int left = amount;
        for (int i = 0; i < inv.size() && left > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!matches(s, item)) continue;
            int take = Math.min(left, s.getCount());
            s.decrement(take);
            if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
            left -= take;
        }
        return amount - left;
    }

    /** 玩家（客户端或服务端实体）背包里某物品总数。 */
    public static int count(PlayerEntity player, Item item) {
        return player == null ? 0 : count(player.getInventory(), item);
    }

    /**
     * 试着付钱：够就扣、返回 true；不够一个都不扣、返回 false。
     * 见类注释的单人 / 多人 / 专用服务端差异。
     */
    public static boolean pay(PlayerEntity clientPlayer, PriceTable.Price price) {
        if (price == null) return false;
        Item item = resolve(price);
        if (item == null || price.count() <= 0) return false;

        PlayerInventory clientInv = clientPlayer == null ? null : clientPlayer.getInventory();
        PlayerInventory authInv = authoritative(clientPlayer);
        if (authInv == null) return false;

        if (authInv == clientInv) {
            // 没有权威背包（多人客户端 / 主菜单 / 服务端直接传入自己）：只能动这一份
            if (count(authInv, item) < price.count()) return false;
            return remove(authInv, item, price.count()) == price.count();
        }

        // 1) 权威背包（单人 = 集成服务端玩家）为准
        if (count(authInv, item) < price.count()) return false;
        int taken = remove(authInv, item, price.count());
        // 2) 客户端背包同步扣一次（数量不一定完全一致，扣到能扣的为止，不做负数）
        if (clientInv != null) remove(clientInv, item, taken);
        return taken == price.count();
    }

    /** 是否买得起（只读检查，不扣任何东西）。 */
    public static boolean canAfford(PlayerEntity clientPlayer, PriceTable.Price price) {
        if (price == null) return false;
        return countFor(clientPlayer, price) >= price.count();
    }

    /**
     * 与 {@link #pay} 同一口径的持有数量：单人时看服务端背包（权威），
     * 否则看调用方自己的背包。界面上的「买得起 / 还差 N」都用它。
     */
    public static int countFor(PlayerEntity clientPlayer, PriceTable.Price price) {
        Item item = resolve(price);
        if (item == null) return 0;
        return count(authoritative(clientPlayer), item);
    }

    /** 背包里某物品的名字（提示「还差 X 个 Y」用）。 */
    public static String itemName(PriceTable.Price price) {
        Item item = resolve(price);
        if (item == null) return price == null ? "?" : price.itemId();
        return item.getName(new ItemStack(item)).getString();
    }
}
