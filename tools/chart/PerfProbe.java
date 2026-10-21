import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 「卡死定位 + 每帧开销」测量工具（headless，纯 Java，不碰 Minecraft）。
 *
 * <p>对曲库里每一个谱面包，按 {@code Session.start()} 的真实顺序测：
 * <ol>
 *   <li><b>载入</b>：{@code SongEntry.chartJson()}（读包）+ {@code ChartParser.parse} + {@code Engine} 构造，
 *       并用采样线程记<b>解析期间峰值堆</b>（OOM / GC 卡顿的唯一可信证据）；
 *       {@code --load} 时额外测 {@code SongEntry.payload()}（音频整包读入）；</li>
 *   <li><b>结构统计</b>：判定线数 / 音符数 / 各轨道事件总数 / 单线最大事件数
 *       （= 用户说的「首帧渲染预估开销 判定线数 × 事件数」）；</li>
 *   <li><b>首帧</b>（冷 JIT，未预热）：{@code update()} + {@code visible()} + 「画所有判定线」+「画所有音符」；</li>
 *   <li><b>每帧</b>：按 60fps 扫完整时间轴，分四段计时，并给出<b>最差 1 秒窗口</b>（用户感知到的「卡一下」）。</li>
 * </ol>
 *
 * <p><b>口径</b>：{@code visible()} / 「画音符」用一个<b>从不判定</b>的引擎（{@code engineV}），
 * 拿到的是「几何上最多能画多少」的上界——确定性最好，修前修后可直接比；
 * {@code update()}（判定/Miss）用另一个引擎（{@code engineU}），走真实的人玩路径。
 *
 * <p>用法：
 * <pre>
 * java -cp "out/dev;lib/gson-2.11.0.jar;slf4j.jar" PerfProbe [目录] [--full] [--only 子串] [--load 子串]
 * </pre>
 */
public final class PerfProbe {

    private static final double ASPECT = 16.0 / 9.0;
    private static final double FPS = 60.0;
    private static double sink;

    public static void main(String[] args) {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        boolean full = false;
        String only = null;
        String loadOnly = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--full" -> full = true;
                case "--only" -> only = args[++i];
                case "--load" -> loadOnly = args[++i];
                default -> dir = Path.of(args[i]);
            }
        }
        warmup();
        List<SongEntry> songs = SongEntry.scan(dir);
        System.out.println("曲库: " + dir);
        System.out.println("谱面包: " + songs.size() + (only == null ? "" : "（过滤 " + only + "）"));
        System.out.println();
        for (SongEntry e : songs) {
            String n = e.path.getFileName().toString();
            if (only != null && !n.contains(only) && !e.display.contains(only)) continue;
            try {
                probe(e, full, loadOnly != null && (n.contains(loadOnly) || e.display.contains(loadOnly)));
            } catch (Throwable t) {
                System.out.printf(Locale.ROOT, "%-44s  失败: %s: %s%n",
                        cut(n, 44), t.getClass().getSimpleName(), t.getMessage());
            }
        }
        if (sink == 42.0) System.out.print("");
    }

    private static void warmup() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,\"speedEvents\":[")
                .append("{\"startTime\":0,\"endTime\":32,\"value\":1}],\"notesAbove\":[{\"type\":1,\"time\":2,\"positionX\":0.5,\"holdTime\":0}],")
                .append("\"notesBelow\":[],\"numOfNotes\":1,\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],")
                .append("\"judgeLineRotateEvents\":[],\"judgeLineDisappearEvents\":[]}]}");
        for (int i = 0; i < 30; i++) {
            Chart c = ChartParser.parse(sb.toString());
            Engine en = new Engine(c, new Judgement.Config());
            for (int f = 0; f < 60; f++) {
                en.autoplay(f / FPS);
                en.visible(f / FPS, ASPECT);
                drawLines(c, f / FPS);
                drawNotes(en.visible(f / FPS, ASPECT), f / FPS);
            }
        }
    }

    private static void probe(SongEntry e, boolean full, boolean loadDetail) {
        String name = e.path.getFileName().toString();
        // ---------- 1) 载入（顺序 = Session.start） ----------
        Runtime rt = Runtime.getRuntime();
        long heap0 = used(rt);
        Sampler sampler = new Sampler();
        sampler.start();

        long t0 = System.nanoTime();
        String json;
        try {
            json = e.chartJson();
        } catch (Throwable t) {
            sampler.finish();
            System.out.printf(Locale.ROOT, "%-44s  读取失败: %s%n", cut(name, 44), t);
            return;
        }
        long readMs = (System.nanoTime() - t0) / 1_000_000L;
        long jsonChars = json.length();
        long t1 = System.nanoTime();
        Chart chart = ChartParser.parse(json);
        long parseMs = (System.nanoTime() - t1) / 1_000_000L;
        sampler.finish();
        long peak = sampler.peak;
        json = null;   // 和 Session 一样：解析完就不再持有原串

        // ---------- 2) 结构统计 ----------
        int lines = chart.lines.size();
        int notes = chart.noteCount();
        long speedEv = 0, moveEv = 0, rotEv = 0, alphaEv = 0, scaleEv = 0, ctrlEv = 0;
        long maxTrack = 0;
        int maxTrackLine = -1;
        int barLines = 0;
        long totalEvents = 0;
        int holds = 0;
        for (Chart.Line l : chart.lines) {
            speedEv += l.speed.size();
            long m = (long) sumLayers(l.moveX) + sumLayers(l.moveY);
            moveEv += m;
            long r = sumLayers(l.rotate);
            rotEv += r;
            long a = sumLayers(l.alpha);
            alphaEv += a;
            long s = (long) l.scaleX.size() + l.scaleY.size();
            scaleEv += s;
            long c = (long) l.ctrlAlpha.size() + l.ctrlSize.size() + l.ctrlPos.size() + l.ctrlY.size();
            ctrlEv += c;
            long lineMax = Math.max(l.speed.size(),
                    Math.max(Math.max(sumLayers(l.moveX), sumLayers(l.moveY)),
                            Math.max(sumLayers(l.rotate), sumLayers(l.alpha))));
            if (lineMax > maxTrack) {
                maxTrack = lineMax;
                maxTrackLine = l.index;
            }
            totalEvents += l.speed.size() + m + r + a + s + c;
            if (l.drawsAsBar()) barLines++;
            for (Chart.Note n : l.notes) if (n.type == Chart.Note.Type.HOLD) holds++;
        }
        double dur = chart.duration;

        // ---------- 3) 两个引擎：一个只画（不判定）、一个只判定 ----------
        long f0 = System.nanoTime();
        Engine engineV = new Engine(chart, new Judgement.Config());   // 几何上界（从不判定）
        Engine engineU = new Engine(chart, new Judgement.Config());   // 真实判定路径
        engineU.reset();
        long engineMs = (System.nanoTime() - f0) / 1_000_000L;

        // ---------- 4) 首帧（冷） ----------
        long f1 = System.nanoTime();
        List<Engine.Visible> firstVisibleList = engineV.visible(0.0, ASPECT);
        long firstVisibleMs = (System.nanoTime() - f1) / 1_000_000L;
        long f2 = System.nanoTime();
        drawLines(chart, 0.0);
        long firstLineMs = (System.nanoTime() - f2) / 1_000_000L;
        long f3 = System.nanoTime();
        drawNotes(firstVisibleList, 0.0);
        long firstNoteMs = (System.nanoTime() - f3) / 1_000_000L;
        long f4 = System.nanoTime();
        engineU.update(0.0);
        long firstUpdMs = (System.nanoTime() - f4) / 1_000_000L;

        // ---------- 5) 每帧（60fps 全时间轴） ----------
        long totalFrames = Math.max(1L, (long) Math.ceil(dur * FPS));
        long stride = 1;
        if (!full && totalFrames > 3000) stride = Math.max(1, totalFrames / 3000);
        int n = (int) (totalFrames / stride);
        double[] updMs = new double[n];
        double[] lineMs = new double[n];
        double[] visMs = new double[n];
        double[] noteMs = new double[n];
        double[] tot = new double[n];
        int visCountMax = 0;
        long visCountSum = 0;
        for (int i = 0; i < n; i++) {
            double t = (i * stride) / FPS;
            long a0 = System.nanoTime();
            engineU.update(t);
            long a1 = System.nanoTime();
            drawLines(chart, t);
            long a2 = System.nanoTime();
            List<Engine.Visible> vs = engineV.visible(t, ASPECT);
            long a3 = System.nanoTime();
            drawNotes(vs, t);
            long a4 = System.nanoTime();
            updMs[i] = (a1 - a0) / 1e6;
            lineMs[i] = (a2 - a1) / 1e6;
            visMs[i] = (a3 - a2) / 1e6;
            noteMs[i] = (a4 - a3) / 1e6;
            tot[i] = (a4 - a0) / 1e6;
            visCountSum += vs.size();
            visCountMax = Math.max(visCountMax, vs.size());
        }
        int worstIdx = 0;
        for (int i = 1; i < n; i++) if (tot[i] > tot[worstIdx]) worstIdx = i;
        double worstT = (worstIdx * stride) / FPS;
        int win = (int) Math.max(1, Math.round(60.0 / stride));
        double worstWin = 0;
        double worstWinT = 0;
        for (int i = 0; i + win <= n; i++) {
            double s = 0;
            for (int k = 0; k < win; k++) s += tot[i + k];
            if (s > worstWin) {
                worstWin = s;
                worstWinT = (i * stride) / FPS;
            }
        }

        long heapAfter = used(rt) - heap0;
        System.out.printf(Locale.ROOT,
                "%-44s [%s] %6.1fs  线%4d(画%4d) 音符%6d(hold%5d) 事件%8d 单线max%5d(#%d)  JSON %6.2fMB%n",
                cut(name, 44), chart.format, dur, lines, barLines, notes, holds, totalEvents, maxTrack, maxTrackLine,
                jsonChars / 1048576.0);
        System.out.printf(Locale.ROOT,
                "    载入: 读%4dms + 解析%5dms + Engine%4dms  ⇒ 冷启动 %5dms | 峰值堆 %6.1fMB (解析后净增 %6.1fMB, 最大堆 %6.0fMB)%n",
                readMs, parseMs, engineMs, readMs + parseMs + engineMs,
                peak / 1048576.0, heapAfter / 1048576.0, rt.maxMemory() / 1048576.0);
        System.out.printf(Locale.ROOT,
                "    首帧: visible %4dms(%d个) + 画线 %4dms + 画音符 %4dms + update %4dms ⇒ 合计 %5dms%n",
                firstVisibleMs, firstVisibleList.size(), firstLineMs, firstNoteMs, firstUpdMs,
                firstVisibleMs + firstLineMs + firstNoteMs + firstUpdMs);
        System.out.printf(Locale.ROOT,
                "    每帧: update avg %6.3f | 画线 avg %6.3f | visible avg %6.3f | 画音符 avg %6.3f (ms)%n",
                avg(updMs), avg(lineMs), avg(visMs), avg(noteMs));
        System.out.printf(Locale.ROOT,
                "    总计: avg %6.3f ms/帧  p99 %6.2f  max %6.2f (t=%.2fs)  最差1s %7.1fms @%.1fs"
                        + "  可见音符 avg %.1f max %d  帧数 %d%n",
                avg(tot), pct(tot, 0.99), tot[worstIdx], worstT, worstWin, worstWinT,
                n == 0 ? 0 : (double) visCountSum / n, visCountMax, n);
        System.out.printf(Locale.ROOT,
                "    事件分布: speed %d | move %d | rotate %d | alpha %d | scale %d | ctrl %d%n",
                speedEv, moveEv, rotEv, alphaEv, scaleEv, ctrlEv);
        if (loadDetail) {
            long p0 = System.nanoTime();
            SongEntry.Audio a;
            try {
                a = e.payload();
            } catch (Exception ex) {
                a = null;
            }
            long payloadMs = (System.nanoTime() - p0) / 1_000_000L;
            System.out.printf(Locale.ROOT, "    音频: %s %d 字节，整包读取 %dms%n",
                    a == null ? "无" : a.name(), a == null ? 0 : a.data().length, payloadMs);
        }
        System.out.println();
    }

    private static long used(Runtime rt) {
        return rt.totalMemory() - rt.freeMemory();
    }

    /** 采样线程：解析期间每 2ms 记一次已用堆，取最大值。 */
    private static final class Sampler extends Thread {
        volatile boolean running = true;
        volatile long peak;

        Sampler() {
            setDaemon(true);
            setName("heap-sampler");
        }

        @Override
        public void run() {
            Runtime rt = Runtime.getRuntime();
            while (running) {
                peak = Math.max(peak, used(rt));
                try {
                    Thread.sleep(2);
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

    /** 复刻 {@code PlayScreen.render()} 里那段「画所有判定线」的求值开销（只求值，不提交 GPU）。 */
    private static void drawLines(Chart chart, double t) {
        double acc = 0;
        for (Chart.Line line : chart.lines) {
            if (!line.drawsAsBar()) continue;
            double alpha = line.alphaAt(t);
            if (alpha <= 0.01) continue;
            double sx = line.scaleXAt(t);
            double sy = line.scaleYAt(t);
            if (Math.abs(sx) <= 1e-4 || Math.abs(sy) <= 1e-4) continue;
            acc += line.xAt(t, ASPECT) + line.yAt(t, ASPECT) + line.rotateAt(t);
        }
        sink += acc;
    }

    /** 复刻 {@code PlayScreen.render()} 里那段「画所有音符」的求值开销。 */
    private static void drawNotes(List<Engine.Visible> vs, double t) {
        double acc = 0;
        for (Engine.Visible v : vs) {
            Chart.Line line = v.state().line;
            acc += line.xAt(t, ASPECT) + line.yAt(t, ASPECT) + line.rotateAt(t);
            if (v.state().note.type == Chart.Note.Type.HOLD) {
                acc += line.floorPositionAt(v.state().note.time + v.state().note.hold);
            }
        }
        sink += acc;
    }

    private static int sumLayers(List<List<Chart.Ev>> layers) {
        int s = 0;
        for (List<Chart.Ev> l : layers) s += l.size();
        return s;
    }

    private static double avg(double[] a) {
        if (a.length == 0) return 0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static double pct(double[] a, double p) {
        if (a.length == 0) return 0;
        double[] c = a.clone();
        Arrays.sort(c);
        int i = Math.min(c.length - 1, (int) Math.round(p * (c.length - 1)));
        return c[i];
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, Math.max(0, n - 1)) + "…";
    }

    private PerfProbe() {
    }
}
