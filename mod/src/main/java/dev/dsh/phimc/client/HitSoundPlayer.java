package dev.dsh.phimc.client;

import dev.dsh.phimc.PhiMCRegistry;
import dev.dsh.phimc.client.respack.ResPack;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.audio.HitSoundClips;
import dev.dsh.phimc.survival.Profile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 打击音。照 prpr judge.rs:37-69 + 797-861：
 * <ul>
 *   <li><b>什么时候播</b>：只有 Perfect / Good 播（judge.rs:858-860 的
 *       {@code if {...} { note.hitsound.play(res); }}，而 judge.rs:821-857 只对 Perfect/Good 返回 true）；
 *       Bad / Miss 不播。</li>
 *   <li><b>播哪个</b>：{@code HitSound::default_from_kind}（judge.rs:52-59）—— Click→click、
 *       Flick→flick、Drag→drag、Hold→click（Hold 头命中时 judge.rs:626/688 立刻播）。</li>
 *   <li><b>音量</b>：judge.rs:62-69 {@code play_sfx}，{@code volume_sfx <= 1e-2} 直接不播；
 *       这里对应设置页的「打击音音量」。</li>
 * </ul>
 *
 * <p><b>音源三级</b>：
 * <ol>
 *   <li>选中资源包里的 {@code click/drag/flick.ogg}（prpr resource.rs:257-287 {@code load_clip!}）——
 *       包内音频不在 MC 资源系统里，用 JavaSound 播放：解码借用 MC 自己的 OGG 解码器
 *       {@link OggAudioStream}，<b>每次命中新建一个 {@link javax.sound.sampled.Clip}</b>
 *       （{@link HitSoundClips}），播完 STOP 自动 close 回收 —— 同一种打击音可以同时响很多个，
 *       不会被下一音符打断。</li>
 *   <li>mod 内置的 3 个 ogg（{@code assets/phimc/sounds/hit/*.ogg} + {@code sounds.json}，
 *       {@link PhiMCRegistry#HIT_CLICK} 等 SoundEvent）——走 MC 的 {@code SoundManager}，
 *       没装资源包的玩家也能听到真正的 Phigros 打击音。这条通路<b>不截断</b>：
 *       {@link PositionedSoundInstance#master} 每次调用都新建实例 + {@code SoundInstance.createRandom()}
 *       种子，{@code SoundSystem.play} 每次都 {@code Channel.createSource()} 新开一个 OpenAL source，
 *       只 {@code sources.put(新实例, ...)}，<b>不会按 sound id / pitch 查旧实例并 stop</b>
 *       （见 report 的字节码证据：{@code SoundSystem.play} 全程没有对已存在实例的查询/停止）。
 *       唯一的限制是 OpenAL 静态 source 池用满时新音符会被丢弃（不是截断旧音符）。</li>
 *   <li>原版兜底（音符盒），只在上面两条都不可用时。</li>
 * </ol>
 */
public final class HitSoundPlayer {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 打击音种类 = prpr {@code HitSound}（judge.rs:28-35）去掉 None/Custom。 */
    public enum Kind {
        CLICK("click"),
        DRAG("drag"),
        FLICK("flick");

        final String slot;

        Kind(String slot) {
            this.slot = slot;
        }
    }

    /** 解码失败过的 key，避免每次命中都重试（PCM 本身缓存在 {@link HitSoundClips}）。 */
    private static final Map<String, Boolean> BROKEN = new HashMap<>();

    private HitSoundPlayer() {
    }

    /** 音符种类 → 打击音（judge.rs:52-59 {@code HitSound::default_from_kind}）。 */
    public static Kind kindOf(Chart.Note.Type type) {
        return switch (type) {
            case TAP, HOLD -> Kind.CLICK;
            case FLICK -> Kind.FLICK;
            case DRAG -> Kind.DRAG;
        };
    }

    /** 命中（Perfect / Good）时调用。 */
    public static void play(Chart.Note.Type type, Profile profile) {
        if (profile == null || !profile.hitSound()) return;
        double volume = profile.hitSoundVolume();
        if (volume <= 1e-2) return;               // judge.rs:62-65
        Kind kind = kindOf(type);
        // **三条通路互斥**：每一条成功都立刻 return，所以一次命中只会播一条。
        // 用 -Dphimc.hitsoundDebug=true 启动时，每次命中会在日志里打一行
        // `打击音 #N 通路=pack|bundled|vanilla kind=...`，行数 = 命中次数（可直接对账）。
        if (playFromPack(kind, volume)) {
            trace("pack", kind);
            return;
        }
        if (playBundled(kind, volume)) {
            trace("bundled", kind);
            return;
        }
        playVanilla(kind, volume);
        trace("vanilla", kind);
    }

    /** 调试开关（默认关）：-Dphimc.hitsoundDebug=true。 */
    private static final boolean DEBUG = Boolean.getBoolean("phimc.hitsoundDebug");
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    /** 一次命中走完了哪条通路（只有 {@link #DEBUG} 打开时才进日志）。 */
    private static void trace(String route, Kind kind) {
        if (!DEBUG) return;
        LOG.info("[PhiMC] 打击音 #{} 通路={} kind={}", SEQ.incrementAndGet(), route, kind.slot);
    }

    // ------------------------------------------------------------------
    // 资源包音源
    // ------------------------------------------------------------------

    private static boolean playFromPack(Kind kind, double volume) {
        ResPack pack = ResPack.current();
        if (pack == null) return false;
        String key = pack.fileName() + "/" + kind.slot;
        if (BROKEN.containsKey(key)) return false;
        if (!HitSoundClips.has(key)) {
            byte[] data = pack.sound(kind.slot);
            if (data == null) return false;
            HitSoundClips.Pcm pcm = decode(key, data);
            if (pcm == null) {
                BROKEN.put(key, Boolean.TRUE);
                return false;
            }
            HitSoundClips.put(key, pcm.format(), pcm.data());
        }
        try {
            // 每次新建 Clip：这一下和还没播完的那些互不影响（旧实现复用单实例 + setFramePosition(0)
            // + start()，会把上一声从头重启，就是「前一个打击音被后一个打断」的根因）。
            HitSoundClips.play(key, volume);
            return true;
        } catch (Throwable t) {
            // 没有可用 line 等运行时问题：不标记 BROKEN（下次还能试），交给下一级音源
            LOG.warn("[PhiMC] 资源包打击音播放失败: {} ({})", key, t.toString());
            return false;
        }
    }

    /** 切包/关包时把旧 PCM 和还在响的 Clip 释放掉（纹理那边由 ResPack 自己管）。 */
    public static synchronized void clear() {
        HitSoundClips.clear();
        BROKEN.clear();
    }

    /**
     * 预热 + 预解码：开/切资源包时把 3 个打击音解成 PCM 缓存好，并初始化 JavaSound mixer。
     *
     * <p>不这么做的话，第一次命中会在渲染线程上付「解码 + 第一次 {@code getClip()}」的一次性开销
     * （实测 mixer 初始化 ~50ms）。在界面初始化 / 设置页切包时做，玩家看不到。
     */
    public static void prepare() {
        HitSoundClips.warmup();
        ResPack pack = ResPack.current();
        if (pack == null) return;
        for (Kind kind : Kind.values()) {
            String key = pack.fileName() + "/" + kind.slot;
            if (HitSoundClips.has(key) || BROKEN.containsKey(key)) continue;
            byte[] data = pack.sound(kind.slot);
            if (data == null) continue;
            HitSoundClips.Pcm pcm = decode(key, data);
            if (pcm == null) {
                BROKEN.put(key, Boolean.TRUE);
            } else {
                HitSoundClips.put(key, pcm.format(), pcm.data());
            }
        }
    }

    private static HitSoundClips.Pcm decode(String key, byte[] data) {
        try {
            boolean ogg = data.length > 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S';
            if (ogg) {
                return decodeOgg(data);
            }
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))) {
                AudioFormat fmt = in.getFormat();
                byte[] pcm = in.readAllBytes();
                if (pcm.length == 0) return null;
                return new HitSoundClips.Pcm(fmt, pcm);
            }
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 资源包打击音解码失败: {}", key, t);
            return null;
        }
    }

    /**
     * 用 Minecraft 自带的 OGG 解码器（{@link OggAudioStream}）解成 float PCM，再存成 16-bit PCM。
     * 这样不需要给 mod 加 Vorbis 依赖。
     */
    private static HitSoundClips.Pcm decodeOgg(byte[] data) throws Exception {
        OggAudioStream stream = new OggAudioStream(new ByteArrayInputStream(data));
        AudioFormat src = stream.getFormat();
        int channels = Math.max(1, src.getChannels());
        float sampleRate = src.getSampleRate() > 0 ? src.getSampleRate() : 44100f;

        it.unimi.dsi.fastutil.floats.FloatArrayList samples = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        //? if >=1.20.5 {
        while (stream.read(samples::add)) {
            // read 每次从流里吐一块 float（交错），返回 false 表示读完
        }
        stream.close();

        int size = samples.size();
        size -= size % channels;                    // 保证按帧对齐（Clip.open 要求 framebuffer 整帧）
        byte[] pcm = new byte[size * 2];
        for (int i = 0; i < size; i++) {
            float v = Math.max(-1f, Math.min(1f, samples.getFloat(i)));
            short s = (short) Math.round(v * 32767.0);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        //?} else {
        /*// 1.20.1 的 OggAudioStream 没有 read(FloatConsumer)，只有 getBuffer(int)：
        // 它返回一块已经量化成 16-bit 的 PCM（交错），缓冲区是 native order（x86 上小端）。
        // 这里显式按 LITTLE_ENDIAN 读出 short，再按小端写回，和下面 PCM_SIGNED/bigEndian=false 对齐。
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        while (true) {
            java.nio.ByteBuffer chunk = stream.getBuffer(8192);
            if (chunk == null || !chunk.hasRemaining()) break;
            java.nio.ShortBuffer shorts = chunk.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            while (shorts.hasRemaining()) {
                short s = shorts.get();
                raw.write(s & 0xFF);
                raw.write((s >> 8) & 0xFF);
            }
        }
        stream.close();

        byte[] pcm = raw.toByteArray();
        int size = pcm.length / 2;
        size -= size % channels;                    // 保证按帧对齐（Clip.open 要求 framebuffer 整帧）
        if (size * 2 != pcm.length) {
            byte[] trimmed = new byte[size * 2];
            System.arraycopy(pcm, 0, trimmed, 0, trimmed.length);
            pcm = trimmed;
        }
        *///?}
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels,
                channels * 2, sampleRate, false);
        return new HitSoundClips.Pcm(fmt, pcm);
    }

    // ------------------------------------------------------------------
    // 内置音源（没装资源包时的兜底）
    // ------------------------------------------------------------------

    /**
     * 播 mod 自带的那 3 个 ogg（{@code assets/phimc/sounds/hit/*.ogg}）。
     *
     * <p>它们已经通过 {@code sounds.json} + {@link PhiMCRegistry} 注册进 MC 的声音系统，
     * 所以用 {@code SoundManager} + {@link PositionedSoundInstance#master} 播即可；
     * 音量就是设置页的「打击音音量」（还会再乘一次 MC 的主音量，这是 MC 的既有行为）。
     *
     * <p>同一种音的多个实例不会互相合并：{@code master(event, pitch, volume)} 每次都
     * {@code new PositionedSoundInstance(..., SoundInstance.createRandom(), ...)}，
     * {@code SoundSystem.play} 每次都新开 OpenAL source（详见类注释）。所以这里不需要
     * 改 pitch / seed，也不会 stop 上一个还没播完的实例。
     *
     * @return false 表示没注册 / 播不了，调用方继续往下兜底
     */
    private static boolean playBundled(Kind kind, double volume) {
        SoundEvent event;
        try {
            event = PhiMCRegistry.hitSound(kind.slot);
        } catch (Throwable t) {
            return false;                          // 注册表还没起来（理论不会走到）
        }
        if (event == null) return false;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return false;
            mc.getSoundManager().play(master(event, 1.0f, (float) HitSoundClips.effectiveGain(volume)));
            return true;
        } catch (Throwable t) {
            LOG.debug("[PhiMC] 内置打击音播放失败: {} ({})", kind.slot, t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 原版兜底
    // ------------------------------------------------------------------

    /**
     * 前面两条都不可用时的兜底（注册表没起来 / SoundManager 播不了）。原版里最接近「打击音」的是
     * 音符盒（短促、无音高拖尾）：click/drag 用 hat，flick 用 snare（更脆一点）。音量直接用设置里的值。
     */
    /**
     * {@code PositionedSoundInstance.master(event, pitch, volume)} 的跨版本封装：
     * 1.21.11 把 master 删了（SoundInstance 改成按 Identifier 构造），这里手写等价对象
     * （MASTER 分类、无衰减、相对坐标 = 和原来的 master 完全一致）。
     */
    private static PositionedSoundInstance master(SoundEvent event, float pitch, float volume) {
        //? if >=1.21.11 {
        return new PositionedSoundInstance(event.id(), net.minecraft.sound.SoundCategory.MASTER, volume, pitch,
                net.minecraft.client.sound.SoundInstance.createRandom(),
                false, 0, net.minecraft.client.sound.SoundInstance.AttenuationType.NONE,
                0.0, 0.0, 0.0, true);
        //?} else {
        /*return PositionedSoundInstance.master(event, pitch, volume);
        *///?}
    }
    private static void playVanilla(Kind kind, double volume) {
        try {
            SoundEvent event = switch (kind) {
                case CLICK, DRAG -> SoundEvents.BLOCK_NOTE_BLOCK_HAT.value();
                case FLICK -> SoundEvents.BLOCK_NOTE_BLOCK_SNARE.value();
            };
            float pitch = switch (kind) {
                case CLICK -> 1.0f;
                case DRAG -> 1.4f;
                case FLICK -> 1.2f;
            };
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            // 和资源包通路用同一个换算：音量旋钮 × 固定预留量（HIT_TRIM，−6 dB），
            // 保证三条通路（资源包 / 内置 ogg / 原版音效）的相对响度一致。
            mc.getSoundManager().play(master(event, pitch, (float) HitSoundClips.effectiveGain(volume)));
        } catch (Throwable t) {
            LOG.debug("[PhiMC] 原版打击音播放失败: {}", t.toString());
        }
    }
}
