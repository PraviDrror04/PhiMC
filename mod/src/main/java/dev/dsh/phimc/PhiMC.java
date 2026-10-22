package dev.dsh.phimc;

/**
 * 两个构建变体（jar）共用的「运行形态」标志。
 *
 * <h2>为什么要有它</h2>
 * 同一份源码树产出两个 jar：
 * <ul>
 *   <li>{@code phimc-<MC>.jar}（mod id {@code phimc}，入口 {@code PhiMCClient}）—— 完整版，
 *       物品 / 生存玩法 / 奖励全都在；</li>
 *   <li>{@code phimcplayer-<MC>.jar}（mod id {@code phimcplayer}，入口 {@code PhiMCLiteClient}）
 *       —— <b>打歌 only</b>：Shift+/ 打开曲库，选歌、打歌，成绩存本地，别的什么都没有。</li>
 * </ul>
 * 两个变体共用同一批界面 / 引擎代码，所以「现在是哪个变体」必须能被这些代码读到。
 * 入口 {@code PhiMCLiteClient} 在 {@code onInitializeClient()} 的<b>第一行</b>把
 * {@link #onlyMode} 置 {@code true}；完整版的入口不碰它，默认就是 {@code false}。
 *
 * <p><b>为什么不用「构建期常量」</b>：两个 jar 打的是<b>同一批 class</b>（见 {@code build.gradle}
 * 的 {@code liteJar}），所以只能靠运行时标志区分 —— 编译期常量在两个 jar 里会是同一个值。
 *
 * <p><b>它不是玩家可见的开关</b>：没有配置文件、没有界面能改它，只由各自入口在启动时设定；
 * 一次启动里写一次、之后只读，所以用 {@code volatile} 保证读线程立刻看到。
 *
 * <p>{@code onlyMode == true} 的收口点（每处都写清了「为什么」）：
 * <ul>
 *   <li>{@code SongSelectScreen}：隐藏「拷芯片」、隐藏商店 / 平板入口、副标题显示
 *       「纯客户端 · 成绩保存在本地」、全部谱面视为可玩（且<b>不读</b>解锁数据）；</li>
 *   <li>{@code Session.finish()}：<b>绝不</b>调用 {@code Rewards.grantOnFinish}；</li>
 *   <li>{@code PhiMCClient.openTablet()} / {@code TabletScreen.init()}：平板 / 商店这条链路
 *       在入口层直接断开（物品根本不存在，进去了就会 NPE）；</li>
 *   <li>{@code Session}：没有世界也没有服务器地址时（主菜单里直接打歌），成绩落
 *       {@code config/phimc/local/}。</li>
 * </ul>
 */
public final class PhiMC {

    /**
     * 当前进程是不是「打歌 only」变体（{@code phimcplayer} jar）。
     *
     * <p>由 {@code PhiMCLiteClient} 在启动时置 {@code true}；完整版永远是 {@code false}。
     * 判定 / 计分<b>一行都不读它</b> —— 它只影响界面入口与结算落盘。
     */
    public static volatile boolean onlyMode;

    private PhiMC() {
    }

    /** {@link #onlyMode} 的读取入口（语义更清楚，且不会误写）。 */
    public static boolean isOnlyMode() {
        return onlyMode;
    }
}
