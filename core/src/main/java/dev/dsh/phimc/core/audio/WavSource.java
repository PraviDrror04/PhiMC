package dev.dsh.phimc.core.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;

/**
 * WAV 播放实现。这是整个项目唯一的「时间基准」。
 *
 * <p>输入可以是 WAV，也可以是 MP3（RPE 的 .pez 里音乐就是 {@code <id>.mp3}）：
 * 后者先用 {@link Mp3ToWav}（JLayer，纯 Java）解成 PCM，之后的路径完全一样，
 * 所以「时间基准只有一处实现」这个前提没有被打破。
 *
 * <p>暂停/继续不用 {@code SourceDataLine.stop()/start()} —— 不少声卡上继续之后
 * {@code getMicrosecondPosition()} 会回跳。这里把整段 PCM 留在内存，继续时重新开一条 line，
 * 从暂停的字节偏移继续喂数据，并把「已经过去的那段时长」记在 {@link #baseOffset} 里加回去。
 *
 * <p><b>位置口径（和 prpr 的 {@code Music} 对齐）：</b>{@code positionSeconds()} 返回的是
 * 「已经真正从设备播出去的秒数」，实现上直接取 {@code SourceDataLine.getMicrosecondPosition()}：
 * 这个值本身就是「播放位置」（Windows/ALSA 实现都按已播帧计数），既不是写入量也不是剩余量，
 * <b>因此不需要再减去缓冲区占用量</b>。早期版本减了 {@code line.available()}（那是「还能写多少
 * 字节」的空闲空间），于是每次缓冲区刚好空的瞬间位置会向下跳一个缓冲区长度
 * （mono 44.1k/16bit + 16KB 缓冲 = 0.186s），在暂停-继续的衔接处表现为「谱面往回退」。
 *
 * <p>暂停语义（prpr/src/scene/game.rs:849-865 的 {@code Scene::pause}/{@code resume}）：
 * <ol>
 *   <li>{@code pause()}：先取位置冻结（<b>必须在 line.stop() 之前</b>，stop 之后设备位置就没了），
 *       再停外设；{@link #positionSeconds()} 在整个暂停期间返回同一个常量；</li>
 *   <li>{@code resume()}：从冻结位置重开设备，{@link #baseOffset} = 冻结位置，
 *       所以继续后位置从暂停点接着走，绝不回到 0（否则谱面会从 0 重放）。</li>
 * </ol>
 *
 * <p>整段播完之后位置继续用墙钟往前走（见 {@link #endNanos}），否则位置会永远停在
 * {@code durationSeconds()}，上层「等到曲子结束」的条件永远不成立（结算界面出不来）。
 * 对应 prpr 里音乐结束后时间轴仍由 {@code TimeManager} 推进（prpr/src/time.rs:86-88）。
 */
public final class WavSource implements AudioSource {

    /** 设备缓冲区字节数（16KB ≈ 93ms @44.1k/mono、46ms @44.1k/stereo）。 */
    private static final int BUFFER_BYTES = 4096 * 4;
    /** 每次写入设备的块大小。 */
    private static final int FEED_CHUNK = 4096;

    private SourceDataLine line;
    private Thread feeder;
    private volatile boolean playing;
    private volatile boolean paused;
    /** 暂停时冻结的位置（{@code < 0} 表示不在暂停态）。 */
    private volatile double frozen = -1;
    /** 当前这条 line 的起点秒数（= 上次 play(0) 或 resume(frozen)）。 */
    private volatile double baseOffset = 0;
    private volatile double durationSeconds = -1;
    /** 整段 PCM 播完的时刻（{@link System#nanoTime()}）；0 表示还没播完。 */
    private volatile long endNanos = 0;
    /** 最近一次设备操作失败的描述（诊断用，正常运行保持 null）。 */
    private volatile String lastError;

    private volatile byte[] pcm;
    private AudioFormat format;

    @Override
    public void play(AudioSource.Payload payload) throws Exception {
        stop();
        byte[] raw = payload.data();
        if (Mp3ToWav.isMp3(raw)) {
            // RPE 的 .pez 里音乐是 <id>.mp3：JavaSound 解不了，先用 JLayer 转成 PCM。
            // 这条路径不经过 WAV 容器，省掉一份 40MB 的拷贝（PCM 直接就是 SourceDataLine 要的格式）。
            Mp3ToWav.Pcm decoded = Mp3ToWav.decode(raw);
            AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    decoded.sampleRate(), 16, decoded.channels(),
                    decoded.channels() * 2, decoded.sampleRate(), false);
            start(decoded.data(), fmt);
            return;
        }
        // Ogg 容器没有任何 JavaSound provider 认，直接丢进来只会得到
        // 「UnsupportedAudioFileException: Stream of unsupported format」这种没信息量的报错。
        // 调用方（Session）必须先用 OggToWav 解成 WAV；这里给一句能定位的话。
        if (raw.length >= 4 && raw[0] == 'O' && raw[1] == 'g' && raw[2] == 'g' && raw[3] == 'S') {
            throw new IllegalArgumentException(
                    "OGG 音频需要先经 OggToWav.decode() 解成 WAV（JavaSound 不支持 Ogg 容器）");
        }
        AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw));
        AudioFormat src = in.getFormat();
        AudioFormat pcmFmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                src.getSampleRate(), 16, src.getChannels(),
                src.getChannels() * 2, src.getSampleRate(), false);
        AudioInputStream converted = AudioSystem.getAudioInputStream(pcmFmt, in);
        byte[] pcmBytes = converted.readAllBytes();
        converted.close();
        start(pcmBytes, pcmFmt);
    }

    /** 装好 PCM / 格式，算出时长，从 0 开始播。 */
    private void start(byte[] pcmBytes, AudioFormat pcmFmt) throws Exception {
        pcm = pcmBytes;
        format = pcmFmt;
        int frameSize = pcmFmt.getFrameSize();
        double rate = pcmFmt.getFrameRate();
        durationSeconds = rate > 0 ? pcm.length / (frameSize * rate) : -1;
        paused = false;
        frozen = -1;
        playing = true;
        startFrom(0);
    }

    /**
     * 从 {@code offsetSec} 处重开一条 line 继续播。
     *
     * <p>{@link #baseOffset} 在动设备之前就写好：即使 {@code open()} 抛异常或者卡住，
     * 上层读到的位置也停在暂停点，不会出现「回到 0」的窗口。
     */
    private void startFrom(double offsetSec) throws Exception {
        double from = Math.max(0, offsetSec);
        if (durationSeconds > 0) from = Math.min(from, durationSeconds);
        // ① 先钉住时间基准
        baseOffset = from;
        endNanos = 0;
        closeLine();

        int frameSize = format.getFrameSize();
        double rate = format.getFrameRate();
        long frame = Math.round(from * rate);
        long byteOffset = frame * frameSize;
        if (byteOffset >= pcm.length) {
            // 已经在结尾（暂停在曲子最后一个采样上）：没有东西可喂，直接按「播完」处理
            endNanos = System.nanoTime();
            return;
        }

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(info);
        opened.open(format, BUFFER_BYTES);
        opened.start();
        // ② 设备真的开了才挂上去
        line = opened;

        final long start = byteOffset;
        final byte[] data = pcm;
        // 关键：闭包里用局部变量而不是字段 line。否则 resume() 换了新 line 之后，
        // 上一条残留的喂数据线程会继续往「新 line」里写旧位置的数据（时间轴乱跳）。
        final SourceDataLine myLine = opened;
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[FEED_CHUNK];
                int pos = (int) start;
                try {
                    while (pos < data.length) {
                        // 被暂停 / 被 stop / 被下一次 startFrom 取代 → 立刻退出，别碰新设备
                        if (!playing || paused || line != myLine) return;
                        int n = Math.min(buf.length, data.length - pos);
                        System.arraycopy(data, pos, buf, 0, n);
                        myLine.write(buf, 0, n);
                        pos += n;
                    }
                    if (playing && !paused && line == myLine) {
                        myLine.drain();
                        if (playing && !paused && line == myLine) {
                            // 整段播完：位置改由墙钟继续推进（见 positionSeconds）
                            endNanos = System.nanoTime();
                            line = null;
                            try { myLine.close(); } catch (Exception ignored) { }
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }, "phimc-audio-feeder");
        t.setDaemon(true);
        feeder = t;
        t.start();
    }

    private void closeLine() {
        SourceDataLine old = line;
        line = null;
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void stop() {
        playing = false;
        paused = false;
        frozen = -1;
        closeLine();
        // stop 之后位置回到 0（旧实现留着上一次的 baseOffset，stop 完还报 2.75s）
        baseOffset = 0;
        endNanos = 0;
    }

    @Override
    public void pause() {
        if (!playing || paused) return;
        // 顺序必须是「先冻结、后停设备」：line.stop()/close() 之后设备位置会丢，
        // 之后再取只会拿到 0 或 baseOffset，谱面就会停在错误的位置。
        double pos = positionSeconds();
        if (durationSeconds > 0 && pos > durationSeconds) pos = durationSeconds;
        frozen = Math.max(0, pos);
        paused = true;
        SourceDataLine l = line;
        if (l != null) {
            try { l.stop(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void resume() {
        if (!paused) return;
        double from = frozen < 0 ? baseOffset : frozen;
        paused = false;
        frozen = -1;
        try {
            startFrom(from);
            lastError = null;
        } catch (Exception e) {
            // 设备打不开也要把时间轴钉在暂停点（退化：静音但谱面继续走），绝不回到 0
            lastError = "resume: " + e;
            closeLine();
            baseOffset = from;
            endNanos = 0;
        }
    }

    @Override
    public double positionSeconds() {
        if (frozen >= 0) return frozen;
        if (endNanos != 0) {
            // 播完之后继续走：上层靠 t > duration 判定「一曲结束」
            return durationSeconds + (System.nanoTime() - endNanos) / 1_000_000_000.0;
        }
        SourceDataLine l = line;
        if (l == null) return baseOffset;
        // getMicrosecondPosition() 已经是「播出去的」位置，不要再减 available()
        return Math.max(0, baseOffset + l.getMicrosecondPosition() / 1_000_000.0);
    }

    @Override
    public boolean isPlaying() {
        return playing && !paused && endNanos == 0 && line != null && line.isRunning();
    }

    @Override
    public double durationSeconds() {
        return durationSeconds;
    }

    /** 最近一次设备操作失败的原因（null = 正常）。 */
    public String lastError() {
        return lastError;
    }

    public static boolean available() {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class,
                    new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2, 4, 44100, false));
            return AudioSystem.isLineSupported(info);
        } catch (Throwable t) {
            return false;
        }
    }

    public static byte[] silenceWav(double seconds, float sampleRate) {
        int frames = (int) (seconds * sampleRate);
        int dataLen = frames * 2;
        byte[] out = new byte[44 + dataLen];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt((int) sampleRate);
        b.putInt((int) sampleRate * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes());
        b.putInt(dataLen);
        return out;
    }

    public static AudioSource.Payload payloadFromFile(java.nio.file.Path wav) throws java.io.IOException {
        return new AudioSource.Payload(java.nio.file.Files.readAllBytes(wav), wav.getFileName().toString(), "wav");
    }
}
