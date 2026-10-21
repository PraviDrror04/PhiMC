package dev.dsh.phimc.core;

/**
 * 主时钟抽象。
 *
 * <p>整个项目只有一条铁律：<b>音频播放位置就是时间基准</b>，不能用 MC 的 tick 或
 * System.nanoTime() 当主时钟。原因：MC 的 tick 是 20Hz 且会被 GC / 区块加载拖慢，
 * 用它做音游时钟会周期性漂移几十毫秒，手感直接崩掉。
 *
 * <p>具体实现由 MC 侧提供（用 LWJGL 开一个 OpenAL source 播 ogg，每帧用
 * {@code AL_SEC_OFFSET} 查播放位置）。core 里只保留接口和一个可手摇的测试实现。
 */
public interface Clock {

    /** 已播放的音频秒数（未减去谱面 offset）。 */
    double nowSeconds();

    /** 手动时钟：自测 / 自动播放用。 */
    final class Manual implements Clock {
        private double t;

        public Manual(double t) {
            this.t = t;
        }

        public void set(double t) {
            this.t = t;
        }

        public void advance(double dt) {
            this.t += dt;
        }

        @Override
        public double nowSeconds() {
            return t;
        }
    }

    /**
     * 把「音频秒数」换算成「谱面秒数」：{@code chartTime = audio - offset}。
     * 谱面 offset 为正表示谱面比音乐快，所以要从音频时间里减掉。
     */
    static double toChartTime(double audioSec, double chartOffset) {
        return audioSec - chartOffset;
    }
}
