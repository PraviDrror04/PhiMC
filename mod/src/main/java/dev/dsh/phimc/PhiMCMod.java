package dev.dsh.phimc;

import dev.dsh.phimc.survival.ServerSurvival;
import dev.dsh.phimc.survival.SurvivalEnv;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双端入口（{@code fabric.mod.json} 的 {@code "main"}）。
 *
 * <h2>为什么要这个类</h2>
 * 以前 {@code fabric.mod.json} 写的是 {@code "environment": "client"}，专用服务端会
 * <b>整个跳过</b>这个模组：物品 / 音效 / 战利品注入 / 开箱赋谱 / 大肥鱼彩蛋在服主开的服里
 * 一样都不存在。现在环境改成 {@code "*"}，这个入口在<b>客户端和专用服务端都</b>会跑，
 * 只做「纯服务端也需要的注册」：
 * <ul>
 *   <li>物品 / 打击音 / 创造页签（{@link PhiMCRegistry#register()}）；</li>
 *   <li>芯片战利品注入 + 开箱赋谱（{@code survival/ChipLoot}，由注册方法挂上）；</li>
 *   <li>大肥鱼 + Phira 融合的服务端 tick 监听（{@code egg/FusionEgg}，同样由注册方法挂上）；</li>
 *   <li>服务端生命周期：进服务端时把 {@link SurvivalEnv} 绑上 {@code MinecraftServer}，
 *       专用服务端额外扫一次曲库（{@link ChartPool#scanServer()}）并打开服务端存档
 *       （{@link ServerSurvival#attach}）。</li>
 * </ul>
 *
 * <h2>硬约束：这个类（以及它直接/间接碰到的类）不许引用任何客户端类</h2>
 * 专用服务端进程里根本没有 {@code net.minecraft.client.*}（{@code MinecraftClient} /
 * {@code Screen} / {@code DrawContext} …），一旦这个入口的调用链上出现对它们的引用，
 * 服主开服时就是 {@code NoClassDefFoundError}。所以这里只碰：
 * <ul>
 *   <li>{@code PhiMCRegistry} / 物品类 / {@code ChipLoot} / {@code FusionEgg}（纯服务端 API）；</li>
 *   <li>{@link SurvivalEnv}（已去掉 {@code MinecraftClient}，只剩 {@code MinecraftServer}）；</li>
 *   <li>{@link ChartPool}（用 {@code SongEntry} 的纯 {@code java.nio} 扫描器，零 MC 依赖）；</li>
 *   <li>{@link ServerSurvival}（{@code Profile} / {@code Records} / {@code WorldStore}，都是纯 {@code java.nio}）。</li>
 * </ul>
 * 客户端专属的东西（键位 / HUD / Screen / Session / 音频 / 在线库 / 材质包）全部留在
 * {@code client/PhiMCClient}，那是 {@code "client"} 入口，专用服务端不会加载它。
 *
 * <p><b>「打歌 only」变体（{@code phimcplayer}）不受影响</b>：那个 jar 用自己的
 * {@code lite-resources/fabric.mod.json}，里面没有 {@code "main"} 入口，也仍然是
 * {@code "environment": "client"} —— 它依旧一个物品都不注册。
 */
public final class PhiMCMod implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(PhiMCRegistry.MOD_ID);

    @Override
    public void onInitialize() {
        EnvType env = FabricLoader.getInstance().getEnvironmentType();
        LOG.info("[PhiMC] 模组已加载（main 入口）· 环境 {}", env);

        // ① 物品 / 打击音 / 创造页签 / 芯片战利品 / 大肥鱼融合 —— 客户端与专用服务端都要。
        //    这里只注册「世界侧」的东西；客户端入口不再重复调用（register() 自身也幂等）。
        PhiMCRegistry.register();

        // ② 服务端生命周期。单人游戏里集成服务端也会走到这里（同一个进程），
        //    所以 SurvivalEnv 的绑定对单人 / 专用服务端是同一条路。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SurvivalEnv.bindServer(server);
            if (env == EnvType.SERVER) {
                // 专用服务端：客户端不在这个进程里，谱面 id 只能自己扫（config/phimc/songs）。
                ChartPool.scanServer();
                // 服务端存档：<服务端存档目录>/world/phimc/（按世界隔离，见 ServerSurvival）
                ServerSurvival.attach(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ServerSurvival.detach();
            SurvivalEnv.unbindServer(server);
        });
    }
}
