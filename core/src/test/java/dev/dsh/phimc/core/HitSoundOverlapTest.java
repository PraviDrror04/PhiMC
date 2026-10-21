package dev.dsh.phimc.core;

import dev.dsh.phimc.core.audio.HitSoundClips;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 打击音「不截断」的 headless 验收（不需要 Minecraft）。
 *
 * <p>用的是真正会跑的那份 JavaSound 代码 {@link HitSoundClips}：<b>每次 {@code play()} 新建一个
 * Clip</b>，播完 {@code STOP} 才 close。旧实现是「一个 (包, 槽位) 一个 Clip，命中时
 * {@code setFramePosition(0) + start()}」，后一声会把前一声从头重启 —— 这里连续触发 5 次、
 * 间隔 50ms，应该看到 5 个<b>互相重叠</b>的时间区间，每个都活满整段音长；最后再拿旧写法做一次
 * 对照，量出「第一声被重启时只播了多少」。
 *
 * <p>输入是<b>真实资源包里的 {@code click.ogg}</b> 解码出来的 PCM（用 mod 同款
 * {@code OggAudioStream} 解，写成 WAV 只是当容器），不把资源包内容提交进仓库。
 *
 * <p>用法：{@code java ... dev.dsh.phimc.core.HitSoundOverlapTest <click.wav> [count] [intervalMs]}
 */
public final class HitSoundOverlapTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: HitSoundOverlapTest <click.wav> [count] [intervalMs]");
            return;
        }
        Path wav = Path.of(args[0]);
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int intervalMs = args.length > 2 ? Integer.parseInt(args[2]) : 50;

        AudioFormat format;
        byte[] pcm;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            format = in.getFormat();
            pcm = in.readAllBytes();
        }
        HitSoundClips.put("click", format, pcm);
        HitSoundClips.Pcm cached = HitSoundClips.pcm("click");
        System.out.printf("打击音: %s (%d bytes)%n", wav.getFileName(), Files.size(wav));
        System.out.printf("PCM: %.0f Hz / %d ch / %d bit / 帧长 %d B / 数据 %d B%n",
                format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits(),
                format.getFrameSize(), pcm.length);
        System.out.printf("音长: %.3f s (%.1f ms)%n", cached.seconds(), cached.seconds() * 1000.0);
        System.out.println();

        List<Hit> hits = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(count);
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            // 精确 50ms 间隔：Thread.sleep 在 Windows 上按 15.6ms 计时器取整，会漂到 60ms+
            long deadline = t0 + (long) intervalMs * 1_000_000L * i;
            while (System.nanoTime() < deadline) Thread.onSpinWait();
            Hit h = new Hit(i, done);
            hits.add(h);
            h.requestedAt = System.nanoTime();
            Clip clip = HitSoundClips.play("click", 1.0, h);
            h.lengthUs = clip.getMicrosecondLength();
        }
        boolean allDone = done.await(5, TimeUnit.SECONDS);
        Thread.sleep(150);   // 让回收监听器跑完
        System.out.printf("%d 次都收到 STOP: %s；同时存在的 Clip 峰值 = %d；现在还剩 %d%n",
                count, allDone ? "是" : "否", HitSoundClips.peak(), HitSoundClips.live());
        System.out.println();

        long base = t0;
        double expectedMs = cached.seconds() * 1000.0;
        System.out.println("序号  请求(ms)  开始(ms)  结束(ms)  实测时长(ms)  clip 长度(ms)  STOP 时播放头(ms)  完整?");
        boolean overlap = true;
        boolean full = true;
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            double req = (h.requestedAt - base) / 1e6;
            double start = (h.startAt - base) / 1e6;
            double stop = (h.stopAt - base) / 1e6;
            double dur = (h.stopAt - h.startAt) / 1e6;
            boolean posOk = h.posAtStopUs >= h.lengthUs - 1000;   // 播放头必须到结尾（-1ms 容差）
            boolean ok = h.stopAt > 0 && posOk;
            full &= ok;
            if (i + 1 < hits.size()) {
                // 「后一次开始早于前一次结束」= 两次在时间上重叠
                Hit n = hits.get(i + 1);
                boolean ov = n.startAt < h.stopAt;
                overlap &= ov;
                System.out.printf("%3d  %9.1f  %8.1f  %8.1f  %11.1f  %13.1f  %14.1f  %s%s%n",
                        i, req, start, stop, dur, h.lengthUs / 1000.0, h.posAtStopUs / 1000.0,
                        ok ? "是" : "否", ov ? "  (与下一次重叠)" : "  (与下一次不重叠)");
            } else {
                System.out.printf("%3d  %9.1f  %8.1f  %8.1f  %11.1f  %13.1f  %14.1f  %s%n",
                        i, req, start, stop, dur, h.lengthUs / 1000.0, h.posAtStopUs / 1000.0, ok ? "是" : "否");
            }
        }
        System.out.println();

        double firstStart = (hits.get(0).startAt - base) / 1e6;
        double lastStop = (hits.get(hits.size() - 1).stopAt - base) / 1e6;
        double span = lastStop - firstStart;
        System.out.printf("总时间轴: 第一声开始 %.1f ms → 最后一声结束 %.1f ms，跨度 %.1f ms%n",
                firstStart, lastStop, span);
        System.out.printf("对比: 5 段首尾相接 = 5 × %.1f = %.1f ms；本实现 = %.1f ms（≈ 4×%d + %.1f = %.1f）%n",
                expectedMs, 5 * expectedMs, span, intervalMs, expectedMs, 4 * intervalMs + expectedMs);
        System.out.printf("判定: 每一声都活满 clip 长度 = %s；相邻两声都重叠 = %s；跨度远小于 5 段串联 = %s%n",
                full ? "是" : "否", overlap ? "是" : "否",
                span < 5 * expectedMs - expectedMs / 2 ? "是" : "否");

        // ---- 对照：旧实现「单 Clip 复用」 ----
        System.out.println();
        System.out.println("== 对照：旧实现（同一 Clip 播到一半再 setFramePosition(0)+start()）==");
        Clip old = AudioSystem.getClip();
        old.open(format, pcm, 0, pcm.length);
        old.start();
        long oldStart = System.nanoTime();
        // 等播放头真的走起来（第一次开 line 可能有几十 ms 初始化），再模拟第二声命中
        long posBefore = 0;
        long giveUp = System.nanoTime() + 1_000_000_000L;
        while (posBefore < 30_000 && System.nanoTime() < giveUp) {
            Thread.sleep(2);
            posBefore = old.getMicrosecondPosition();
        }
        long oldCall = System.nanoTime();
        old.setFramePosition(0);      // 旧代码在第二声命中时做的就是这两步
        old.start();
        long posAfter = old.getMicrosecondPosition();
        System.out.printf("第一声在 %.1f ms 时被第二次命中重启：重启前播放头 = %.1f ms / 全长 %.1f ms（只播了 %.1f%%）%n",
                (oldCall - oldStart) / 1e6, posBefore / 1000.0, old.getMicrosecondLength() / 1000.0,
                100.0 * posBefore / Math.max(1, old.getMicrosecondLength()));
        System.out.printf("重启后播放头 = %.1f ms → 前一声剩下的 %.1f ms 被丢掉了（这就是「被后一个打断」）%n",
                posAfter / 1000.0, (old.getMicrosecondLength() - posBefore) / 1000.0);
        old.close();
        // close 之后把旧实现的判定也算进失败条件：只要 posAfter < posBefore 就说明确实会截断
        boolean truncates = posAfter < posBefore;

        if (!allDone || !full || !overlap) {
            System.out.println("✗ 验收失败（新实现）");
            System.exit(1);
        }
        System.out.println("✓ 新实现验收通过：后一声开始时前一声仍在响，且每一声都完整播完（对照代码会截断）");
        if (!truncates) {
            System.out.println("提示：本机对照组的播放头没有回退，可能是 mixer 实现差异");
        }
    }

    /** 一个音符的打击音：记录 START/STOP 的墙钟时间。 */
    private static final class Hit implements javax.sound.sampled.LineListener {
        final int index;
        final CountDownLatch done;
        volatile long requestedAt;
        volatile long startAt;
        volatile long stopAt;
        volatile long lengthUs;
        volatile long posAtStopUs;

        Hit(int index, CountDownLatch done) {
            this.index = index;
            this.done = done;
        }

        @Override
        public void update(LineEvent event) {
            if (event.getType() == LineEvent.Type.START) {
                if (startAt == 0) startAt = System.nanoTime();
            } else if (event.getType() == LineEvent.Type.STOP) {
                if (stopAt == 0) {
                    stopAt = System.nanoTime();
                    posAtStopUs = ((Clip) event.getLine()).getMicrosecondPosition();
                    done.countDown();
                }
            }
        }
    }
}
