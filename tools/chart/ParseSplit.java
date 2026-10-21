import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.RpeParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 解析各阶段的峰值堆拆分：读包 → JSON 树 → 我们的 Chart 模型。 */
public final class ParseSplit {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        String only = args.length > 0 ? args[0] : "big song";
        List<SongEntry> songs = SongEntry.scan(dir);
        for (SongEntry e : songs) {
            String n = e.path.getFileName().toString();
            if (!n.contains(only)) continue;
            System.out.println("== " + n + " ==");
            Runtime rt = Runtime.getRuntime();
            long base = used(rt);
            String json = e.chartJson();
            System.out.printf(Locale.ROOT, "  chartJson(): %d 字符, 已用堆 %+.1fMB%n",
                    json.length(), (used(rt) - base) / 1048576.0);

            long h0 = used(rt);
            Sampler s1 = new Sampler();
            s1.start();
            long t1 = System.nanoTime();
            JsonElement rootEl = JsonParser.parseString(json);
            s1.took = (System.nanoTime() - t1) / 1_000_000L;
            s1.finish();
            System.out.printf(Locale.ROOT, "  JsonParser 建树: %6dms, 峰值堆增量 %+.1fMB (树本身约 %.1fMB)%n",
                    s1.took, (s1.peak - h0) / 1048576.0, (used(rt) - h0) / 1048576.0);

            long h1 = used(rt);
            Sampler s2 = new Sampler();
            s2.start();
            long t2 = System.nanoTime();
            JsonObject root = rootEl.getAsJsonObject();
            var chart = RpeParser.parse(root);
            s2.took = (System.nanoTime() - t2) / 1_000_000L;
            s2.finish();
            System.out.printf(Locale.ROOT, "  RpeParser.parse: 建模型 %6dms, 峰值堆增量 %+.1fMB (模型驻留 %.1fMB)%n",
                    s2.took, (s2.peak - h1) / 1048576.0, (used(rt) - h1) / 1048576.0);
            System.out.printf(Locale.ROOT, "  线 %d, 音符 %d, 时长 %.1fs%n",
                    chart.lines.size(), chart.noteCount(), chart.duration);

            // 整条路（= Session.start）
            long h2 = used(rt);
            Sampler s3 = new Sampler();
            s3.start();
            long t3 = System.nanoTime();
            String j2 = e.chartJson();
            ChartParser.parse(j2);
            s3.took = (System.nanoTime() - t3) / 1_000_000L;
            s3.finish();
            System.out.printf(Locale.ROOT, "  端到端 chartJson+parse: %6dms, 峰值堆增量 %+.1fMB%n",
                    s3.took, (s3.peak - h2) / 1048576.0);
            System.out.printf(Locale.ROOT, "  当前已用堆 %.1fMB / 最大 %.0fMB%n",
                    used(rt) / 1048576.0, rt.maxMemory() / 1048576.0);
            break;
        }
    }

    private static long used(Runtime rt) {
        return rt.totalMemory() - rt.freeMemory();
    }

    private static final class Sampler extends Thread {
        volatile boolean running = true;
        volatile long peak;
        volatile long took;

        Sampler() {
            setDaemon(true);
        }

        @Override
        public void run() {
            Runtime rt = Runtime.getRuntime();
            while (running) {
                peak = Math.max(peak, used(rt));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }

        void finish() {
            running = false;
            try {
                join(50);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private ParseSplit() {
    }
}
