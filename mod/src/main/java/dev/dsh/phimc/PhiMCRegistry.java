package dev.dsh.phimc;

import dev.dsh.phimc.item.BlankChipItem;
import dev.dsh.phimc.item.ChartPlayerItem;
import dev.dsh.phimc.item.ChipItem;
import dev.dsh.phimc.item.TabletItem;
import dev.dsh.phimc.egg.FusionEgg;
import dev.dsh.phimc.survival.ChipLoot;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

/**
 * 物品与创造模式页签的注册。<b>双端</b>：由 {@code PhiMCMod}（{@code "main"} 入口）在
 * 客户端与专用服务端各调一次，所以服主开的服里也有同一批物品 / 配方 / 战利品。
 *
 * <p>调用顺序：{@code PhiMCMod.onInitialize()} → {@link #register()}。客户端入口
 * （{@code PhiMCClient}）<b>不再</b>调用它，避免重复注册；{@link #register()} 自身也做了
 * 幂等保护。「打歌 only」变体（{@code phimcplayer}）的入口只调
 * {@link #registerHitSounds()}，物品永远不进注册表（见 {@code PhiMCLiteClient} 的说明）。
 *
 * <p>芯片相关的两个物品（{@code blank_chip} / {@code chart_chip}）都在这里注册，
 * 顺便把 {@link ChipLoot}（原版箱子战利品注入 + 打开箱子那一刻赋谱）挂上 ——
 * 它要在任何世界加载数据包**之前**注册好监听器，所以放在 {@link #register()} 里。
 * 候选集来源是 {@code ChartPool}：客户端装 {@code Session::chartIds}，专用服务端装
 * 启动时扫出来的快照（见 {@code ChartPool} 的类注释）。
 *
 * <p>两个彩蛋物品（{@code big_fish} / {@code phira}，拍视频用）也在这里注册；它们的
 * 融合逻辑不挂在物品上，而是服务端 tick 扫描（{@link FusionEgg}），同一次 {@code register()}
 * 里挂好监听器。
 *
 * <p><b>本类零客户端依赖</b>：物品类本身也已经去掉 {@code MinecraftClient}（客户端动作走
 * {@code ItemHooks}），所以 {@code javap} 在本类的常量池里看不到
 * {@code net/minecraft/client/}。
 */
public final class PhiMCRegistry {

    public static final String MOD_ID = "phimc";

    public static Item CHART_PLAYER;
    /** 生存玩法平板（设计文档 §2.1）。 */
    public static Item TABLET;
    /** 空芯片：可合成，还没载入谱面（§2.2）。 */
    public static Item BLANK_CHIP;
    /** 谱面芯片：{@code custom_data.chartId} 里存着一张谱面，载入后不可改写。 */
    public static Item CHART_CHIP;
    /**
     * 彩蛋物品「大肥鱼」（{@code phimc:big_fish}，拍视频用）。
     *
     * <p>它本身没有右键行为，只是融合材料之一：和 {@link #PHIRA} 同时掉在地上、
     * 相距 1.5 格内会触发闪电 + 融合成谱面播放器（见 {@code dev.dsh.phimc.egg.FusionEgg}）。
     */
    public static Item BIG_FISH;
    /** 彩蛋物品「Phira」（{@code phimc:phira}），融合的另一半。 */
    public static Item PHIRA;
    public static ItemGroup GROUP;

    /**
     * 打击音（{@code assets/phimc/sounds/hit/*.ogg} + {@code sounds.json}）。
     *
     * <p>这三段音频是用户提供的 Phigros 官方资源包里的 {@code click/drag/flick.ogg}，
     * 仓库里<b>只放这三个文件</b>（谱面/曲绘/PV 一律不进仓库）。用户自己的资源包
     * （{@code config/phimc/respack/*.zip}）里有同名音频时优先用包里的（见
     * {@code HitSoundPlayer}），这里是「没装资源包也能听到打击音」的内置兜底。
     */
    public static SoundEvent HIT_CLICK;
    public static SoundEvent HIT_DRAG;
    public static SoundEvent HIT_FLICK;

    private PhiMCRegistry() {
    }

    public static void register() {
        // 幂等：客户端入口改造前也会调这里（现在是 PhiMCMod 的 main 入口统一调），
        // 再兜一层 —— Registry.register 同一个 id 注册两次会抛异常。
        if (CHART_PLAYER != null) return;
        CHART_PLAYER = registerItem("chart_player", ChartPlayerItem::new, new Item.Settings().maxCount(1));

        // 打击音：id 与 sounds.json 的键一一对应（phimc:hit_click 等）
        registerHitSounds();

        // 平板：不可堆叠（maxCount(1)），耐久 200（每首 −1 的扣减属于 S4）
        TABLET = registerItem("tablet", TabletItem::new,
                new Item.Settings().maxCount(1).maxDamage(TabletItem.MAX_DAMAGE));

        // 空芯片：可堆叠（一次多合成几个，拷谱时消耗 1 个）
        BLANK_CHIP = registerItem("blank_chip", BlankChipItem::new, new Item.Settings().maxCount(16));

        // 谱面芯片：不可堆叠 —— 每张的 chartId 组件不同，堆叠会让「一芯片一谱面」失效
        CHART_CHIP = registerItem("chart_chip", ChipItem::new, new Item.Settings().maxCount(1));

        // 彩蛋物品：只是融合材料，可堆叠 64、没有右键行为
        // （右键/融合逻辑一律不挂在物品类上，见 FusionEgg）
        BIG_FISH = registerItem("big_fish", Item::new, new Item.Settings().maxCount(64));
        PHIRA = registerItem("phira", Item::new, new Item.Settings().maxCount(64));

        GROUP = Registry.register(Registries.ITEM_GROUP,
                Identifier.of(MOD_ID, "main"),
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.phimc"))
                        .icon(() -> new ItemStack(CHART_PLAYER))
                        .entries((ctx, entries) -> {
                            entries.add(CHART_PLAYER);
                            entries.add(TABLET);
                            entries.add(BLANK_CHIP);
                            entries.add(CHART_CHIP);
                            // 彩蛋物放在页签最后：视频里直接拿，不影响常规物品顺序
                            entries.add(BIG_FISH);
                            entries.add(PHIRA);
                        })
                        .build());

        // 芯片战利品：把芯片挂到原版 chests/* 上 + 打开箱子那一刻赋谱（见 ChipLoot 的类注释）。
        // 候选集延迟求值：客户端此刻 Session 还没建好（客户端入口随后装 ChartPool 的来源），
        // 专用服务端则由 ChartPool.scanServer() 在服务端启动时装好快照。
        ChipLoot.init(ChartPool::ids);

        // 彩蛋：大肥鱼 + Phira 掉在一处 → 闪电 + 融合成谱面播放器（见 FusionEgg 的类注释）。
        // 这里只挂服务端 tick 监听；物品注册必须已经完成（FusionEgg 会读 BIG_FISH / PHIRA）。
        FusionEgg.register();

        org.slf4j.LoggerFactory.getLogger("phimc").info(
                "[PhiMC] 已注册物品 phimc:chart_player / phimc:tablet / "
                        + "phimc:blank_chip / phimc:chart_chip / "
                        + "phimc:big_fish / phimc:phira 与创造页签 phimc:main（客户端 / 专用服务端通用）");
    }

    /**
     * 只注册 3 个打击音 {@link SoundEvent}（{@code phimc:hit_click} / {@code hit_drag} / {@code hit_flick}）。
     *
     * <p><b>给谁用</b>：{@code phimcplayer}（打歌 only）变体的入口。那个变体一个物品都不注册
     * （见 {@code PhiMCLiteClient} 的类注释），但打击音是「听得见节奏」的必需品，而它只是
     * {@code Registries.SOUND_EVENT} 里的一条记录（不落到世界、不需要服务端），所以单独开一个口子。
     *
     * <p>幂等：{@link #register()} 已经调过一次时再调不会重复注册（{@code Registry.register}
     * 同一个 id 注册两次会抛异常），直接返回。
     */
    public static void registerHitSounds() {
        if (HIT_CLICK != null) return;
        HIT_CLICK = registerSound("hit_click");
        HIT_DRAG = registerSound("hit_drag");
        HIT_FLICK = registerSound("hit_flick");
    }

    /**
     * 注册一个物品。
     *
     * <p><b>1.21.9+ 的坑</b>：{@code Item} 构造器会 {@code Objects.requireNonNull(settings.registryKey)}，
     * 没设就直接 {@code NullPointerException: Item id not set}。所以必须在<b>构造之前</b>把
     * {@code Settings.registryKey} 设好。
     *
     * <p><b>为什么不能用反射</b>：发布出去的 jar 里 Minecraft 的方法名是 intermediary
     * （{@code Item.Settings.registryKey} 运行时叫 {@code method_63686}），按 yarn 名
     * {@code getMethod("registryKey", ...)} 永远抛 {@code NoSuchMethodException}，
     * 一被 {@code catch (Throwable)} 吞掉就变成「key 没设上 → 构造器 NPE」。
     * 这里用 Stonecutter 条件把<b>唯一一行</b>包住：低版本根本不编译这条语句，也不会运行。
     */
    private static Item registerItem(String name,
                                     java.util.function.Function<Item.Settings, Item> factory,
                                     Item.Settings settings) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, name));
        //? if >=1.21.9 {
        settings = settings.registryKey(key);
        //?}
        return Registry.register(Registries.ITEM, key, factory.apply(settings));
    }

    /** 注册一个打击音 {@link SoundEvent}（{@code assets/phimc/sounds.json} 里同名键才有音源）。 */
    private static SoundEvent registerSound(String path) {
        Identifier id = Identifier.of(MOD_ID, path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /**
     * 打击音槽位 → 已注册的 {@link SoundEvent}，未注册（没有走 {@link #register()}）时为 {@code null}。
     *
     * @param slot {@code click} / {@code drag} / {@code flick}（= {@code HitSoundPlayer.Kind.slot}）
     */
    public static SoundEvent hitSound(String slot) {
        if (slot == null) return null;
        return switch (slot) {
            case "click" -> HIT_CLICK;
            case "drag" -> HIT_DRAG;
            case "flick" -> HIT_FLICK;
            default -> null;
        };
    }

    public static ItemStack chartPlayer() {
        return new ItemStack(CHART_PLAYER);
    }

    public static ItemStack tablet() {
        return new ItemStack(TABLET);
    }
}