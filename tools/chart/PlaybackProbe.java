import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * 「修前 / 修后」端到端验证：把曲库里每个谱面包的音乐按 {@code Session.toPlayable} 的逻辑处理一遍，
 * 再走 {@code WavSource.play} 里那一段 JavaSound 判定，看能不能拿到一条 PCM 流。
 *
 * <p>三条口径：
 * <ul>
 *   <li><b>修前</b>：原始字节直接给 {@code AudioSystem}（= 旧 {@code WavSource.play} 对非 MP3 的处理）；</li>
 *   <li><b>修后</b>：{@code OggS} 先经 {@code OggToWav.decode}（本文件内联一份同样的实现）解成 WAV；</li>
 *   <li>两种都打印结果 + 解码出的时长，并和谱面时长对比。</li>
 * </ul>
 * 顺带把每个包的打开方式（UTF-8 / GBK 回退）也打出来。
 *
 * <p>用法：{@code java -cp "out/dev;lwjgl..." PlaybackProbe <songs 目录>}
 */
public final class PlaybackProbe {

    private static final Charset[] CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            Charset.forName("Shift_JIS"),
            StandardCharsets.ISO_8859_1,
    };

    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0])
                : Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        List<Path> pkgs = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".pez") || n.endsWith(".zip")) pkgs.add(p);
            });
        }
        pkgs.sort(null);
        System.out.printf(Locale.ROOT, "%-40s %-9s %-10s %-22s %-22s %s%n",
                "谱面", "包打开", "音频", "修前(裸字节→AudioSystem)", "修后(转换→AudioSystem)", "时长");
        System.out.println("-".repeat(150));
        int beforeOk = 0;
        int afterOk = 0;
        int bad = 0;
        for (Path p : pkgs) {
            String name = p.getFileName().toString();
            ZipFile zf = null;
            String open;
            try {
                zf = new ZipFile(p.toFile());
                open = "UTF-8";
            } catch (Exception e) {
                open = "失败";
                for (Charset cs : CHARSETS) {
                    try {
                        zf = new ZipFile(p.toFile(), cs);
                        open = cs.name();
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (zf == null) {
                bad++;
                System.out.printf(Locale.ROOT, "%-40s %-9s 打开失败%n", cut(name, 40), "失败");
                continue;
            }
            final ZipFile zip = zf;
            try (zip) {
                ZipEntry audio = pick(zf, ".wav", ".mp3", ".ogg");
                if (audio == null) {
                    System.out.printf(Locale.ROOT, "%-40s %-9s 无音频%n", cut(name, 40), open);
                    continue;
                }
                byte[] raw = read(zf, audio);
                String kind = magic(raw) + "/" + ext(audio.getName());

                String b = before(raw);
                String a = after(raw);
                if (b.startsWith("OK")) beforeOk++;
                if (a.startsWith("OK")) afterOk++;
                System.out.printf(Locale.ROOT, "%-40s %-9s %-10s %-22s %-22s%n",
                        cut(name, 40), open, kind, b, a);
            }
        }
        System.out.println("-".repeat(150));
        System.out.printf(Locale.ROOT, "修前能播 %d / 修后能播 %d / 共 %d 个包%n", beforeOk, afterOk, pkgs.size());
    }

    /** 修前口径：原始字节直接给 AudioSystem（MP3 除外——旧代码里 MP3 走 JLayer，一直是好的）。 */
    private static String before(byte[] raw) {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xE0) == 0xE0) return "OK(MP3/JLayer)";
        if (raw.length >= 3 && raw[0] == 'I' && raw[1] == 'D' && raw[2] == '3') return "OK(MP3/JLayer)";
        try {
            AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw));
            in.close();
            return "OK";
        } catch (Exception e) {
            return "挂:" + e.getClass().getSimpleName();
        }
    }

    /** 修后口径：OggS 先解成 WAV（= Session.toPlayable + OggToWav.decode），再给 AudioSystem。 */
    private static String after(byte[] raw) {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xE0) == 0xE0) return "OK(MP3/JLayer)";
        if (raw.length >= 3 && raw[0] == 'I' && raw[1] == 'D' && raw[2] == '3') return "OK(MP3/JLayer)";
        byte[] data = raw;
        boolean converted = false;
        if (isOgg(raw)) {
            try {
                data = decodeOgg(raw);
                converted = true;
            } catch (Exception e) {
                return "解 OGG 失败:" + e.getMessage();
            }
        }
        try {
            AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
            AudioFormat f = in.getFormat();
            double sec = f.getFrameRate() > 0 ? in.getFrameLength() / f.getFrameRate() : -1;
            in.close();
            return (converted ? "OK(ogg→wav " : "OK(") + String.format(Locale.ROOT, "%.1fs)", sec);
        } catch (Exception e) {
            return "挂:" + e.getClass().getSimpleName();
        }
    }

    private static boolean isOgg(byte[] d) {
        return d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
    }

    /** 和 {@code OggToWav.decode} 同一份实现（按块循环，不信任 stream_length_in_samples）。 */
    private static byte[] decodeOgg(byte[] ogg) {
        ByteBuffer in = BufferUtils.createByteBuffer(ogg.length);
        in.put(ogg).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var err = stack.mallocInt(1);
            long h = STBVorbis.stb_vorbis_open_memory(in, err, null);
            if (h == 0L) throw new IllegalStateException("open err=" + err.get(0));
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(h, info);
                int ch = info.channels();
                int rate = info.sample_rate();
                int declared = STBVorbis.stb_vorbis_stream_length_in_samples(h);
                short[] pcm = new short[Math.max(8192, declared > 0 ? declared : 8192) * ch];
                int frames = 0;
                ShortBuffer chunk = BufferUtils.createShortBuffer(8192 * ch);
                while (true) {
                    chunk.position(0);
                    int n = STBVorbis.stb_vorbis_get_samples_short_interleaved(h, ch, chunk);
                    if (n <= 0) break;
                    int need = (frames + n) * ch;
                    if (need > pcm.length) pcm = Arrays.copyOf(pcm, Math.max(need, pcm.length * 2));
                    for (int i = 0, o = frames * ch; i < n * ch; i++) pcm[o + i] = chunk.get(i);
                    frames += n;
                }
                if (frames == 0) throw new IllegalStateException("0 帧");
                return wrapAsWav(pcm, frames * ch, rate, ch);
            } finally {
                STBVorbis.stb_vorbis_close(h);
            }
        }
    }

    private static byte[] wrapAsWav(short[] pcm, int shorts, int rate, int ch) {
        int dataLen = shorts * 2;
        byte[] out = new byte[44 + dataLen];
        ByteBuffer b = ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) ch);
        b.putInt(rate);
        b.putInt(rate * ch * 2);
        b.putShort((short) (ch * 2));
        b.putShort((short) 16);
        b.put("data".getBytes());
        b.putInt(dataLen);
        for (int i = 0; i < shorts; i++) b.putShort(pcm[i]);
        return out;
    }

    private static ZipEntry pick(ZipFile zf, String... suffixes) {
        ZipEntry best = null;
        for (var en = zf.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName().toLowerCase(Locale.ROOT);
            for (String s : suffixes) {
                if (n.endsWith(s) && (best == null || e.getSize() > best.getSize())) best = e;
            }
        }
        return best;
    }

    private static byte[] read(ZipFile zf, ZipEntry e) throws Exception {
        try (InputStream in = zf.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static String magic(byte[] d) {
        if (d.length < 4) return "?";
        String h = new String(d, 0, 4, StandardCharsets.ISO_8859_1);
        if (h.equals("OggS")) return "OggS";
        if (h.equals("RIFF")) return "WAV";
        if (h.startsWith("ID3")) return "MP3";
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return "MP3";
        return "?";
    }

    private static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private PlaybackProbe() {
    }
}
