package dev.dsh.phimc.core.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 打击音的「每个音符一个独立 {@link Clip}」播放器（纯 Java，零 Minecraft 依赖）。
 *
 * <h2>为什么不是「一个 Clip 复用」</h2>
 * JavaSound 的 {@code Clip} 是一个<b>有状态的播放头</b>：对同一个实例
 * {@code setFramePosition(0) + start()} 会把还在响的那一遍<b>从头重启</b>，前一个音符的后半段就没了
 * ——这正是「连续打音符时前一个打击音被后一个打断」的原因。要叠着响，就必须是多个 Clip
 * （每个 Clip 自己一条 mixer line、自己的播放头）。
 *
 * <p>所以这里：<b>每次 {@link #play} 新建一个 Clip</b>，播完（{@link LineEvent.Type#STOP}）立刻
 * {@code close()} 把 line 还给混音器，避免占着 line 不放（默认混音器的 line 数是有限的，
 * 而音乐的 {@code SourceDataLine} 也要用）。密集段会同时存在多个 Clip，这是音游的正常行为。
 *
 * <h2>PCM 缓存</h2>
 * 解码（OGG / WAV → PCM）只做一次，{@link #put} 进来；每次 {@link #play} 直接用缓存的
 * {@link Pcm} 开新 Clip，不再重复解码。切资源包时 {@link #clear()} 会把缓存和还在响的 Clip 一起清掉。
 *
 * <p>没有音频设备 / line 用完时 {@link #play} 会抛 {@link LineUnavailableException}，
 * 由调用方决定是否往下一级音源兜底（绝不静默把已响的那一遍截断）。
 */
public final class HitSoundClips {

    /** 一段解码好的 PCM（{@code data} 的字节布局由 {@code format} 决定）。 */
    public record Pcm(AudioFormat format, byte[] data) {
        /** 时长（秒），给自测打印用。 */
        public double seconds() {
            int frameSize = Math.max(1, format.getFrameSize());
            return (double) data.length / frameSize / Math.max(1f, format.getFrameRate());
        }
    }

    /** key → PCM。key 由调用方定（资源包用「包名/槽位」）。 */
    private static final Map<String, Pcm> PCM = new ConcurrentHashMap<>();
    /** 还在响的 Clip（切包 / 退出时统一 close）。 */
    private static final Set<Clip> LIVE = ConcurrentHashMap.newKeySet();
    /** 同时存在的 Clip 峰值（自测证据）。 */
    private static final AtomicInteger PEAK = new AtomicInteger();

    private HitSoundClips() {
    }

    /** 缓存一段解码好的 PCM（重复 put 会覆盖）。 */
    public static void put(String key, AudioFormat format, byte[] data) {
        if (key == null || format == null || data == null || data.length == 0) return;
        PCM.put(key, new Pcm(format, data));
    }

    public static boolean has(String key) {
        return PCM.containsKey(key);
    }

    public static Pcm pcm(String key) {
        return PCM.get(key);
    }

    /** 当前有几个 Clip 在响。 */
    public static int live() {
        return LIVE.size();
    }

    /** 历史峰值（自测 / 日志用）。 */
    public static int peak() {
        return PEAK.get();
    }

    /**
     * 播一次。每次都新建 Clip，所以同时多次调用会<b>叠加</b>，不会互相打断。
     *
     * @param volume 0~1，按 {@code 20*log10(volume)} 设 MASTER_GAIN（和旧实现一致）
     * @param observer 可选的生命周期观察者（自测记录 START/STOP 时间用），在内部回收监听器之前注册；
     *                 生产代码传 {@code null}
     * @return 本次新建的 Clip（已 start）
     * @throws LineUnavailableException 没有可用的 mixer line
     */
    public static Clip play(String key, double volume, LineListener observer) throws LineUnavailableException {
        Pcm p = PCM.get(key);
        if (p == null) throw new IllegalArgumentException("没有缓存的 PCM: " + key);
        Clip clip = AudioSystem.getClip();
        try {
            clip.open(p.format(), p.data(), 0, p.data().length);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            closeQuietly(clip);
            throw e;
        }
        applyGain(clip, volume);
        if (observer != null) {
            try {
                clip.addLineListener(observer);
            } catch (Throwable ignored) {
            }
        }
        // 播完（STOP）或被人为关闭（CLOSE）就回收。LIVE.remove 返回 true 保证只 close 一次，
        // 也避免 close() 触发的 CLOSE 事件再次进来。
        clip.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP || ev.getType() == LineEvent.Type.CLOSE) {
                if (LIVE.remove(clip)) closeQuietly(clip);
            }
        });
        LIVE.add(clip);
        PEAK.accumulateAndGet(LIVE.size(), Math::max);
        clip.start();
        return clip;
    }

    public static Clip play(String key, double volume) throws LineUnavailableException {
        return play(key, volume, null);
    }

    /**
     * 预热 JavaSound：第一次 {@code getClip()/open()} 会初始化 mixer（实测 ~50ms），
     * 放在界面初始化 / 切包时做，别让它在第一个音符响起时才发生。
     */
    public static void warmup() {
        try {
            Clip clip = AudioSystem.getClip();
            AudioFormat fmt = new AudioFormat(44100f, 16, 2, true, false);
            byte[] silence = new byte[fmt.getFrameSize()];
            clip.open(fmt, silence, 0, silence.length);
            clip.close();
        } catch (Throwable ignored) {
        }
    }

    /** 切资源包 / 退出：清缓存并停掉还在响的。 */
    public static void clear() {
        PCM.clear();
        for (Clip c : LIVE) {
            if (LIVE.remove(c)) closeQuietly(c);
        }
        LIVE.clear();
    }

    /**
     * 打击音的<b>固定预留量</b>（线性倍数，0.5 = −6.02 dB）。
     *
     * <p>为什么需要它：打击音是<b>瞬态</b>（几个采样点就到峰值），音乐是<b>持续</b>信号。
     * 即使两路都按「满刻度 0 dB」放，瞬态峰值在听感上也会明显压过音乐。所以除了
     * {@code Profile.hitSoundVolume} 这个用户旋钮，这里再固定让出 6 dB 余量。
     *
     * <p>响度目标（把音乐当作 0 dB 参考）：
     * <ul>
     *   <li>默认 {@code hitSoundVolume = 0.35} ⇒ {@code 20*log10(0.35 * 0.5)} = <b>−15.1 dB</b>；
     *   <li>旋钮拉到 0.5 ⇒ <b>−12.0 dB</b>；
     *   <li>旋钮拉满 1.0 ⇒ {@code 20*log10(1.0 * 0.5)} = <b>−6.0 dB</b>（仍低于音乐，不会盖住）。
     * </ul>
     * 也就是说旋钮<b>永远不能把打击音推到和音乐同响度</b>，最响也比音乐低 6 dB。
     *
     * <p><b>平台差异</b>：{@code prpr} 的打击音和音乐过同一套 mixer（共用音量总线，
     * 且打击音素材本身录得偏低）；我们的打击音是<b>独立的 JavaSound 通路</b>
     * （{@code MASTER_GAIN} 直接对设备满刻度，不经过任何总线衰减），所以必须在代码里让出这段余量。
     * 不是照搬源码常数。
     */
    public static final double HIT_TRIM = 0.5;

    /** 把用户旋钮（0~1）换算成真正下发给 {@code MASTER_GAIN} 的线性倍数。 */
    public static double effectiveGain(double volume) {
        return Math.max(0.0, Math.min(1.0, volume)) * HIT_TRIM;
    }

    private static void applyGain(Clip clip, double volume) {
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            double v = effectiveGain(volume);
            float db = (float) (20.0 * Math.log10(Math.max(1e-3, v)));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        } catch (Throwable ignored) {
            // 没有 MASTER_GAIN 的混音器就按原音量播
        }
    }

    private static void closeQuietly(Clip clip) {
        try {
            clip.close();
        } catch (Throwable ignored) {
        }
    }
}
