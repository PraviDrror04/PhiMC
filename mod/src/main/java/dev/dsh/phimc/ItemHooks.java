package dev.dsh.phimc;

import net.minecraft.item.ItemStack;

/**
 * 物品右键里的「客户端动作」桥。
 *
 * <h2>为什么需要这一层</h2>
 * 物品类（{@code item/ChartPlayerItem} / {@code TabletItem} / {@code ChipItem}）是**双端类**：
 * 同一个 {@code Item} 在客户端和专用服务端都会被加载。它们原来的
 * {@code use()} 里直接写 {@code PhiMCClient.xxx()} / {@code MinecraftClient.getInstance()}，
 * 于是「加载物品类」就等于「引用客户端类」—— 专用服务端一注册这些物品就会
 * {@code NoClassDefFoundError}。
 *
 * <p>现在物品类只调这里的静态方法（本类零 Minecraft 客户端依赖），真正的界面 / 提示动作
 * 由客户端入口 {@code PhiMCClient} 在启动时装进来（{@code client/ClientItemActions}）。
 * 服务端进程里没人装 → 这些方法<b>什么都不做</b>，但类加载是干净的。
 *
 * <p>{@code use()} 里仍然保留 {@code world.isClient()} 判断：单人游戏里一次右键会在
 * <b>两个</b>线程上各调一次（客户端预测 + 集成服务端），服务端那一次不能再开界面。
 */
public final class ItemHooks {

    /** 客户端动作（由 {@code client/ClientItemActions} 实现并安装）。 */
    public interface ClientActions {
        /** 谱面播放器右键；{@code sneak} = 潜行右键（重打上一首）。 */
        void openChartPlayer(boolean sneak);

        /** 平板右键（新平板赠品 + 打开选曲界面）。 */
        void openTablet();

        /** 平板潜行右键 = 重打上一首。 */
        void replayLast();

        /** 谱面芯片右键 = 把芯片里的谱面写进存档（客户端那份）。 */
        void useChartChip(ItemStack stack);
    }

    /** 没装（专用服务端 / 客户端入口还没跑）时为 {@code null}。 */
    private static volatile ClientActions actions;

    private ItemHooks() {
    }

    /** 客户端入口装动作（{@code PhiMCClient.onInitializeClient()} 里调一次）。 */
    public static void install(ClientActions a) {
        actions = a;
    }

    /** 有没有客户端动作（调试 / 排查用）。 */
    public static boolean installed() {
        return actions != null;
    }

    public static void openChartPlayer(boolean sneak) {
        ClientActions a = actions;
        if (a != null) a.openChartPlayer(sneak);
    }

    public static void openTablet() {
        ClientActions a = actions;
        if (a != null) a.openTablet();
    }

    public static void replayLast() {
        ClientActions a = actions;
        if (a != null) a.replayLast();
    }

    public static void useChartChip(ItemStack stack) {
        ClientActions a = actions;
        if (a != null) a.useChartChip(stack);
    }
}
