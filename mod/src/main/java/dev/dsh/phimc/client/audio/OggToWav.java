package dev.dsh.phimc.client.audio;

import dev.dsh.phimc.core.audio.AudioSource;
import dev.dsh.phimc.core.audio.WavSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 把 OGG/Vorbis 解成 PCM，再包成 WAV 交给 {@link WavSource} 播放。
 *
 * <p>这样做的理由：音乐播放只需要一条路径（JavaSound 的 {@code SourceDataLine}），
 * 时钟也只有一处实现（{@code getMicrosecondPosition()}），不用同时维护 OpenAL 和 JavaSound 两套。
 * OGG 解码用 LWJGL 自带的 stb_vorbis —— 它随 Minecraft 一起分发，不需要额外依赖。
 *
 * <h2>为什么必须有这一步（实测）</h2>
 * <p>JavaSound 不认 Ogg 容器。修前 {@code WavSource.play} 只特判了 MP3，其余一律丢给
 * {@code AudioSystem.getAudioInputStream}，于是谱面包里凡是 {@code .ogg} 的音乐都会抛
 * {@code UnsupportedAudioFileException: Stream of unsupported format} → 时钟永远停在
 * {@code -0.15s}（谱面偏移）→ 玩家看到的就是「进游戏后一动不动、跑不了」。
 *
 * <p>曲库里实测 4 首是 OGG：{@code 14143_「僕は…」}、{@code 39417_Sphalerite}、
 * {@code 48273_Undertale}、{@code 78805_enchanted love}。
 *
 * <h2>解码方式</h2>
 * <p>不信任 {@code stb_vorbis_stream_length_in_samples}：有些转码器写出的 Ogg 首页 granule
 * 是垃圾值（实测 {@code 「僕は…」} 的首个 page granule = {@code 0xE5FC000000000000}），
 * 按它分配缓冲就会解出 0 帧。这里改成<b>按块循环解码直到读不出东西</b>，容量按需增长，
 * 这样长度声明对不对都不影响结果。
 */
public final class OggToWav {

    /** 一次读多少帧（stb_vorbis 的读取单位是帧）。 */
    private static final int CHUNK_FRAMES = 8192;

    private OggToWav() {
    }

    /** 这段字节是不是 Ogg 容器（{@code OggS} 魔数）。 */
    public static boolean isOgg(byte[] data) {
        return data != null && data.length >= 4
                && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S';
    }

    /**
     * OGG 字节 → 完整 WAV 字节（PCM_SIGNED 16bit 小端，声道/采样率跟源文件一致）。
     *
     * @throws IllegalStateException stb_vorbis 打不开或解出 0 帧（Opus、损坏文件等）
     */
    public static byte[] decode(byte[] ogg) {
        ByteBuffer in = BufferUtils.createByteBuffer(ogg.length);
        in.put(ogg).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var err = stack.mallocInt(1);
            long handle = STBVorbis.stb_vorbis_open_memory(in, err, null);
            if (handle == 0L) {
                throw new IllegalStateException("stb_vorbis 打开失败, err=" + err.get(0));
            }
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);
                int channels = info.channels();
                int rate = info.sample_rate();
                if (channels <= 0 || rate <= 0) {
                    throw new IllegalStateException("stb_vorbis 读出的格式不合法: " + channels + "ch/" + rate + "Hz");
                }
                // 只当「初始容量」用，真实帧数以下面的循环为准
                int declared = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                short[] pcm = new short[Math.max(CHUNK_FRAMES, declared > 0 ? declared : CHUNK_FRAMES) * channels];
                int frames = 0;
                ShortBuffer chunk = BufferUtils.createShortBuffer(CHUNK_FRAMES * channels);
                while (true) {
                    chunk.position(0);
                    int n = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, chunk);
                    if (n <= 0) break;
                    int need = (frames + n) * channels;
                    if (need > pcm.length) {
                        pcm = Arrays.copyOf(pcm, Math.max(need, pcm.length * 2));
                    }
                    for (int i = 0, o = frames * channels; i < n * channels; i++) {
                        pcm[o + i] = chunk.get(i);
                    }
                    frames += n;
                }
                if (frames == 0) {
                    throw new IllegalStateException("stb_vorbis 解出 0 帧（文件可能不是 Vorbis，而是 Opus / 损坏）");
                }
                return wrapAsWav(pcm, frames * channels, rate, channels);
            } finally {
                STBVorbis.stb_vorbis_close(handle);
            }
        }
    }

    /** 从歌曲目录里找音频：music.wav / *.ogg / music.mp3（目录形态的谱面包用）。 */
    public static AudioSource.Payload loadPayload(Path songDir) throws Exception {
        if (Files.isDirectory(songDir)) {
            try (var s = Files.list(songDir)) {
                var files = s.filter(Files::isRegularFile).toList();
                for (Path p : files) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.equals("music.wav") || n.endsWith(".wav")) {
                        return new AudioSource.Payload(Files.readAllBytes(p), p.getFileName().toString(), "wav");
                    }
                }
                for (Path p : files) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.equals("music.ogg") || n.endsWith(".ogg")) {
                        return new AudioSource.Payload(decode(Files.readAllBytes(p)), p.getFileName().toString(), "ogg->wav");
                    }
                }
            }
        }
        return null;
    }

    private static byte[] wrapAsWav(short[] pcm, int shorts, int rate, int channels) {
        int dataLen = shorts * 2;
        byte[] out = new byte[44 + dataLen];
        ByteBuffer b = ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) channels);
        b.putInt(rate);
        b.putInt(rate * channels * 2);
        b.putShort((short) (channels * 2));
        b.putShort((short) 16);
        b.put("data".getBytes());
        b.putInt(dataLen);
        for (int i = 0; i < shorts; i++) {
            b.putShort(pcm[i]);
        }
        return out;
    }
}
