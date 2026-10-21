import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

/**
 * OGG 音频路径的 headless 复现工具。
 *
 * <p>目的：确认「谱面包里的 .ogg 音乐」为什么会 {@code UnsupportedAudioFileException}，
 * 并验证修法（把 OGG 先解成 PCM/WAV 再交给 {@code WavSource}）确实可行。
 *
 * <p>三件事：
 * <ol>
 *   <li>把原始 OGG 字节直接交给 {@code AudioSystem}（= 修前 {@code WavSource.play} 的行为）——
 *       预期抛「Stream of unsupported format」；</li>
 *   <li>用 stb_vorbis 解成 PCM（= {@code OggToWav.decode} 的行为），打印
 *       channels / sample_rate / stream_length / 实得样本数；</li>
 *   <li>把 PCM 包成 WAV 再交给 {@code AudioSystem}——预期成功，并打印时长。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 * java -cp "out/dev;lwjgl.jar;lwjgl-stb.jar;lwjgl-natives.jar;stb-natives.jar" OggProbe &lt;ogg 文件&gt; …
 * </pre>
 */
public final class OggProbe {

    public static void main(String[] args) throws Exception {
        for (String a : args) probe(Path.of(a));
    }

    private static void probe(Path file) throws Exception {
        byte[] raw = Files.readAllBytes(file);
        System.out.printf(Locale.ROOT, "== %s (%d 字节) ==%n", file.getFileName(), raw.length);
        System.out.printf(Locale.ROOT, "   魔数: %s%n", magic(raw));

        // ---- ① 修前行为：原始 OGG 直接丢给 AudioSystem ----
        try {
            AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw));
            System.out.printf(Locale.ROOT, "   ① 裸 OGG → AudioSystem: 成功 %s ← 意外%n", in.getFormat());
            in.close();
        } catch (Exception e) {
            System.out.printf(Locale.ROOT, "   ① 裸 OGG → AudioSystem: %s: %s  ← 修前就是这么挂的%n",
                    e.getClass().getSimpleName(), e.getMessage());
        }

        // ---- ② stb_vorbis 解码 ----
        ByteBuffer in = BufferUtils.createByteBuffer(raw.length);
        in.put(raw).flip();
        int channels;
        int rate;
        int declared;
        ShortBuffer pcm;
        int got;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var err = stack.mallocInt(1);
            long handle = STBVorbis.stb_vorbis_open_memory(in, err, null);
            if (handle == 0L) {
                System.out.printf(Locale.ROOT, "   ② stb_vorbis 打开失败 err=%d%n", err.get(0));
                return;
            }
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);
                channels = info.channels();
                rate = info.sample_rate();
                declared = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
                pcm = BufferUtils.createShortBuffer(Math.max(0, declared) * channels);
                got = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, pcm);
            } finally {
                STBVorbis.stb_vorbis_close(handle);
            }
        }
        System.out.printf(Locale.ROOT,
                "   ② stb_vorbis: %d 声道 / %d Hz / stream_length=%d 帧 / get_samples 实得 %d 帧%n",
                channels, rate, declared, got);
        double seconds = rate > 0 ? (double) got / rate : 0;
        System.out.printf(Locale.ROOT, "      → 时长 %.3fs%n", seconds);

        // ---- ③ PCM 包成 WAV 再交给 AudioSystem ----
        byte[] wav = wrapAsWav(pcm, got * channels, rate, channels);
        System.out.printf(Locale.ROOT, "   ③ 包成 WAV: %d 字节%n", wav.length);
        try {
            AudioInputStream in2 = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
            AudioFormat f = in2.getFormat();
            System.out.printf(Locale.ROOT,
                    "   ③ WAV → AudioSystem: 成功 %s / %.2f 帧每秒 / %.3fs%n",
                    f, f.getFrameRate(), in2.getFrameLength() / f.getFrameRate());
            in2.close();
        } catch (Exception e) {
            System.out.printf(Locale.ROOT, "   ③ WAV → AudioSystem: %s: %s%n",
                    e.getClass().getSimpleName(), e.getMessage());
        }
        System.out.println();
    }

    private static String magic(byte[] d) {
        if (d.length < 4) return "太短";
        String h = new String(d, 0, Math.min(4, d.length));
        return switch (h) {
            case "OggS" -> "OggS（Ogg 容器）";
            case "RIFF" -> "RIFF（WAV）";
            case "ID3\u0003" -> "ID3（MP3）";
            default -> h + "（其他）";
        };
    }

    /** 和 {@code OggToWav.wrapAsWav} 完全一致（复制过来，保证测的就是生产代码的口径）。 */
    private static byte[] wrapAsWav(ShortBuffer pcm, int samples, int rate, int channels) {
        int dataLen = samples * 2;
        byte[] out = new byte[44 + dataLen];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
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
        pcm.position(0);
        for (int i = 0; i < samples; i++) {
            b.putShort(pcm.get(i));
        }
        return out;
    }

    private OggProbe() {
    }
}
