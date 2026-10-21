package dev.dsh.phimc.core;

import dev.dsh.phimc.core.audio.AudioSource;
import dev.dsh.phimc.core.audio.WavSource;

/**
 * 音频时钟自测：验证「播放位置」确实随时间前进，且报出来的秒数与我们喂进去的时长一致。
 *
 * <p>没有声卡时会自动退回 {@link AudioSource.WallClock} 并照常断言，用于 CI/无头环境。
 */
public final class AudioClockTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("输出设备可用: " + WavSource.available());

        if (!WavSource.available()) {
            System.out.println("=> 无声卡，测试 WallClock 兜底路径");
            AudioSource.WallClock clock = new AudioSource.WallClock();
            clock.play(new AudioSource.Payload(new byte[0], "silence", "none"));
            Thread.sleep(400);
            double pos = clock.positionSeconds();
            System.out.printf("400ms 后位置 = %.3fs%n", pos);
            check("WallClock 前进量在 0.3~0.6s", pos > 0.3 && pos < 0.6);
            clock.stop();
            check("stop 后不再播放", !clock.isPlaying());
            report();
            return;
        }

        WavSource src = new WavSource();
        byte[] wav = WavSource.silenceWav(2.0, 44100);
        src.play(new AudioSource.Payload(wav, "silence-2s", "wav"));

        double last = -1;
        for (int i = 0; i < 6; i++) {
            Thread.sleep(200);
            double pos = src.positionSeconds();
            System.out.printf("t=%d00ms  pos=%.3fs  playing=%s  dur=%.2fs%n",
                    (i + 1) * 2, pos, src.isPlaying(), src.durationSeconds());
            check("位置单调不减", pos >= last - 1e-6);
            last = pos;
        }
        check("2s 后位置约 1.2~1.5s（允许设备缓冲差值）", last > 1.0 && last < 1.6);
        check("时长解析正确", Math.abs(src.durationSeconds() - 2.0) < 0.05);
        src.stop();
        report();
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("  ✗ " + what);
        }
    }

    private static void report() {
        if (failures == 0) {
            System.out.println("音频时钟检查通过 ✅");
        } else {
            System.out.println(failures + " 项失败 ❌");
            System.exit(1);
        }
    }
}
