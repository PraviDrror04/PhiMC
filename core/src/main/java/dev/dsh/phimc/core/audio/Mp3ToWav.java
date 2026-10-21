package dev.dsh.phimc.core.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * MP3 → PCM（16bit 小端，交错）解码。RPE 谱面包（.pez）里的音乐统一是 {@code <id>.mp3}，
 * 而 JavaSound 自带能解的只有 WAV/AIFF/AU，所以必须先解一道再交给 {@link WavSource}。
 *
 * <p>用 <b>JLayer</b>（{@code javazoom:jlayer}，纯 Java，LGPL，jar 放在 {@code lib/}，
 * 构建时打进 mod jar）。不依赖 ffmpeg / 本机任何外部程序：谱面来自玩家，不能指望他们装转码器。
 *
 * <p>解出来的 PCM 直接给 {@link WavSource} 用，不绕 WAV 容器——一首 4 分钟的歌 PCM 有 40MB，
 * 多拷一份不值当。
 */
public final class Mp3ToWav {

    private Mp3ToWav() {
    }

    /** 解码结果：交错 16bit PCM。 */
    public record Pcm(byte[] data, int sampleRate, int channels) {
        /** 时长（秒）；数据异常时返回 -1。 */
        public double seconds() {
            if (sampleRate <= 0 || channels <= 0) return -1;
            return data.length / (double) (sampleRate * channels * 2);
        }
    }

    /**
     * 是不是 MP3：有 ID3 头，或者第一个字节是帧同步 {@code 0xFF 0xEx}。
     * （只看魔数，不看扩展名——.pez 里的文件名是随机 id，但后缀还是靠谱的。）
     */
    public static boolean isMp3(byte[] data) {
        if (data == null || data.length < 4) return false;
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') return true;
        return (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0;
    }

    /** 解码整段 MP3。失败抛 {@link IllegalStateException}（上层可以退回墙钟）。 */
    public static Pcm decode(byte[] mp3) {
        if (mp3 == null || mp3.length == 0) throw new IllegalArgumentException("空的 MP3 数据");
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(mp3.length * 6);
        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder decoder = new Decoder();
        int channels = 0;
        int sampleRate = 0;
        int frames = 0;
        try {
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                try {
                    SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (channels == 0) {
                        channels = out.getChannelCount();
                        sampleRate = out.getSampleFrequency();
                        if (channels <= 0 || sampleRate <= 0) {
                            throw new IllegalStateException("MP3 头异常: " + channels + "ch/" + sampleRate + "Hz");
                        }
                    }
                    short[] buf = out.getBuffer();
                    int n = out.getBufferLength();
                    byte[] tmp = new byte[n * 2];
                    for (int i = 0; i < n; i++) {
                        short s = buf[i];
                        tmp[i * 2] = (byte) (s & 0xFF);
                        tmp[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                    pcm.write(tmp, 0, tmp.length);
                    frames++;
                } finally {
                    bitstream.closeFrame();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MP3 解码失败（已解 " + frames + " 帧）: " + e, e);
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
            }
        }
        if (frames == 0) throw new IllegalStateException("MP3 里没有解出任何帧");
        return new Pcm(pcm.toByteArray(), sampleRate, channels);
    }

    /** 把解出来的 PCM 包成标准 44 字节头的 WAV（自测 / 落盘用）。 */
    public static byte[] toWav(Pcm pcm) {
        int dataLen = pcm.data().length;
        byte[] out = new byte[44 + dataLen];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);                       // PCM
        b.putShort((short) pcm.channels());
        b.putInt(pcm.sampleRate());
        b.putInt(pcm.sampleRate() * pcm.channels() * 2);
        b.putShort((short) (pcm.channels() * 2));
        b.putShort((short) 16);
        b.put("data".getBytes());
        b.putInt(dataLen);
        System.arraycopy(pcm.data(), 0, out, 44, dataLen);
        return out;
    }

    /** MP3 字节 → WAV 字节（一次性，给不方便用 {@link Pcm} 的调用方）。 */
    public static byte[] toWav(byte[] mp3) {
        return toWav(decode(mp3));
    }

    /** 空实现的可用性检查（JLayer 是纯 Java，正常都有）。 */
    public static boolean available() {
        try {
            Class.forName("javazoom.jl.decoder.Decoder");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
