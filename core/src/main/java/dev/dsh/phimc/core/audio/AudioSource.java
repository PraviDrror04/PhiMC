package dev.dsh.phimc.core.audio;

/**
 * 音频播放源。整个项目的「时间基准」从这里来。
 *
 * <p>为什么不用 {@code System.nanoTime()}：音游的判定必须和听到的声音对齐。
 * 墙钟会漂移（音频设备有自己的晶振、还有缓冲区延迟），几秒钟就能差出 30–80ms，
 * 正好是一个 Perfect 窗口的量级。所以位置必须问播放器本身要。
 *
 * <p>实现约定：{@link #positionSeconds()} 返回的是「已经真正从扬声器出来的声音」的秒数，
 * 而不是「已经写进缓冲区的字节数」，也<b>不是</b>「还能往缓冲区写多少字节」。
 * WAV 实现直接取 {@code SourceDataLine.getMicrosecondPosition()}（它本身就是已播位置），
 * <b>不再</b>去减 {@code line.available()}：available() 是缓冲区空闲空间，减它会让位置在
 * 缓冲区刚好空的瞬间向下跳一个缓冲区长度（16KB / 44.1k / mono ≈ 0.186s），
 * 暂停-继续的衔接处就会看到谱面往回退。
 *
 * <p>暂停契约（对齐 prpr/src/scene/game.rs:849-865）：
 * <ul>
 *   <li>{@link #pause()} 之后到 {@link #resume()} 之前，{@link #positionSeconds()}
 *       必须返回<b>同一个常量</b>（时间轴冻结，谱面停在原地，不判 Miss）；</li>
 *   <li>{@link #resume()} 之后，{@link #positionSeconds()} 必须从<b>暂停时的那个位置</b>
 *       继续往前走，绝不能回到 0 —— 回到 0 的表现就是「音乐接着播、谱面从 0 重放」。</li>
 * </ul>
 */
public interface AudioSource {

    void play(Payload payload) throws Exception;

    void stop();

    /**
     * 暂停 / 继续（Esc 暂停菜单用）。默认空实现：不支持暂停的实现让音频继续走，
     * 但时间轴由 {@code Session} 冻结，判定不会漂。
     */
    default void pause() {
    }

    default void resume() {
    }

    /**
     * 已播放秒数（已对齐到扬声器），未开始时为 0。
     *
     * <p>暂停期间必须是常量；继续之后必须从暂停点继续（单调、不回 0）。
     * 整段播完之后应当继续增长（靠它判定「一曲结束」）。
     */
    double positionSeconds();

    boolean isPlaying();

    /** 曲子总长（秒），未知返回 -1。 */
    double durationSeconds();

    /** 要播的东西：一段可解码的音频字节 + 名字（用于日志）。 */
    record Payload(byte[] data, String name, String formatHint) {
    }

    /**
     * 没有音频可用时的兜底：用墙钟假装在播放。
     * 只用于「谱面渲染调试」和「无声音环境自测」，不要用于正式游玩。
     */
    final class WallClock implements AudioSource {
        private long startNanos = -1;
        private boolean playing;
        private double duration = -1;
        /** 暂停时已累计的秒数；{@code startNanos < 0} 表示当前不在走。 */
        private double accumulated;

        @Override
        public void play(Payload payload) {
            accumulated = 0;
            startNanos = System.nanoTime();
            playing = true;
        }

        @Override
        public void stop() {
            playing = false;
            startNanos = -1;
            accumulated = 0;
        }

        @Override
        public void pause() {
            if (startNanos < 0) return;
            accumulated = positionSeconds();
            startNanos = -1;
        }

        @Override
        public void resume() {
            if (startNanos >= 0) return;
            startNanos = System.nanoTime();
        }

        @Override
        public double positionSeconds() {
            if (startNanos < 0) return accumulated;
            return accumulated + (System.nanoTime() - startNanos) / 1_000_000_000.0;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public double durationSeconds() {
            return duration;
        }

        public void setDuration(double d) {
            this.duration = d;
        }
    }
}
