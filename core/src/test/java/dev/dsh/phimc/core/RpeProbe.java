package dev.dsh.phimc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * RPE 链路验收探针（headless，不需要 Minecraft）。四组数据，全部打印出来：
 *
 * <ol>
 *   <li><b>落屏率</b>：真实音符（排除 fake 与 {@code alpha == 0}）在自己判定时刻，
 *       屏幕坐标是否落在 {@code [0,1]×[0,1]}；另外单列「判定线也可见」的那部分。</li>
 *   <li><b>对照 Phira 公式</b>：随机抽 200 个音符，把本项目的求值结果与
 *       「照着 {@code prpr/src/parse/rpe.rs} 手算」的独立实现（自带 BPM 折秒、关键帧求值、
 *       缓动表、父链矩阵、速度积分）比对，报最大误差。</li>
 *   <li><b>父线验证</b>：合成一张 2 线谱（父线旋转 0°/30°/90°，子线挂在父线上），
 *       与手算值比对；再抽真实谱里带 father 的线与参考实现比对。</li>
 *   <li><b>RPEVersion &lt; 170 的速度语义</b>：同一份谱分别按「老语义（段内线性）」
 *       与「新语义（按缓动）」算一遍高度，报最大差异。</li>
 * </ol>
 *
 * <p>只用了 {@link Chart} 的「PGR 时代就有」的接口（{@code xAt/yAt/rotateAt/floorPositionAt}），
 * 所以这份探针既可以跑新代码，也可以直接跑旧 jar 做「修前 / 修后」对照
 * （新字段用反射读，读不到就当默认值）。
 */
public final class RpeProbe {

    /** prpr rpe.rs:24 的完整口径：10/45/HEIGHT_RATIO。 */
    private static final double RUST_SPEED_RATIO = 10.0 / 45.0 / 0.83175;

    /** 16:9。故意不引用 Chart.DEFAULT_ASPECT，这样这份探针也能在老 jar 上跑（修前/修后对照）。 */
    private static final double ASPECT16_9 = 16.0 / 9.0;

    private static final String[] DEFAULTS = {
            "D:\\.minecraft\\versions\\1.21.1-fabric-0.20.0\\config\\phimc\\songs\\Cyberneko Pulse (2).pez",
            "D:\\.minecraft\\versions\\1.21.1-fabric-0.20.0\\config\\phimc\\songs\\Hardsync.pez",
            "D:\\.minecraft\\versions\\1.21.1-fabric-0.20.0\\config\\phimc\\songs\\推活天使ビビアンちゃん .pez",
    };

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        if (args.length > 0) {
            for (String a : args) files.add(Path.of(a));
        } else {
            for (String d : DEFAULTS) files.add(Path.of(d));
        }
        String mode = System.getProperty("rpeprobe.mode", "all");

        if (mode.equals("all") || mode.equals("screen")) {
            System.out.println("========== ① 落屏率（真实音符，判定时刻）==========");
            for (Path p : files) screenRate(p);
        }
        if (mode.equals("all") || mode.equals("ref")) {
            System.out.println();
            System.out.println("========== ② 对照 Phira 公式（随机 200 音符）==========");
            for (Path p : files) reference(p);
        }
        if (mode.equals("all") || mode.equals("parent")) {
            System.out.println();
            System.out.println("========== ③ 父判定线验证 ==========");
            syntheticParent();
            for (Path p : files) realParent(p);
        }
        if (mode.equals("all") || mode.equals("speed")) {
            System.out.println();
            System.out.println("========== ④ RPEVersion<170 速度语义差异 ==========");
            for (Path p : files) speedModeDiff(p);
        }
    }

    // ================================================================== ① 落屏率

    private static void screenRate(Path pez) throws Exception {
        JsonObject root = rawRoot(pez);
        Chart c = ChartParser.parse(root.toString());
        for (double aspect : new double[]{16.0 / 9.0, 4.0 / 3.0, 21.0 / 9.0}) {
            int real = 0, onScreen = 0, alphaZero = 0, fake = 0;
            int withMargin = 0, notParked = 0, notParkedOn = 0;
            int[] dist = new int[5];    // 0 屏内, 1 <=0.1, 2 0.1~1, 3 1~10, 4 >10
            for (Chart.Line line : c.lines) {
                for (Chart.Note n : line.notes) {
                    if (n.fake) {
                        fake++;
                        continue;
                    }
                    if (noteAlpha(n, n.time) <= 0.0) {
                        alphaZero++;
                        continue;
                    }
                    double[] xy = jarScreen(line, n, n.time, aspect);
                    real++;
                    if (inside(xy)) onScreen++;
                    // 音符身体本身有宽度（NOTE_W_FRAC≈0.13 屏宽），只要「半个音符」还在屏内就算看得见
                    if (inside(xy, HALF_NOTE_X, HALF_NOTE_Y)) withMargin++;
                    // 排除谱面自己停在 ±10 屏高之外的「哨兵线」（moveX/moveY = ±9999/±22221）
                    if (!parked(xy)) {
                        notParked++;
                        if (inside(xy)) notParkedOn++;
                    }
                    double d = overshoot(xy);
                    dist[d == 0 ? 0 : d <= 0.1 ? 1 : d <= 1 ? 2 : d <= 10 ? 3 : 4]++;
                }
            }
            System.out.printf(Locale.ROOT,
                    "  %-28s aspect=%4.2f  真实音符 %5d（fake %d、alpha=0 %d）%n"
                            + "        严格 [0,1] 落屏 %5d = %6.2f%%   留半个音符 %6.2f%%   "
                            + "排除哨兵线后 %6.2f%%%n",
                    pez.getFileName(), aspect, real, fake, alphaZero,
                    onScreen, percent(onScreen, real),
                    percent(withMargin, real), percent(notParkedOn, notParked));
            System.out.printf(Locale.ROOT,
                    "        屏外距离分布：<=0.1 屏高 %d 个、0.1~1 %d 个、1~10 %d 个、>10 %d 个（屏外共 %d）%n",
                    dist[1], dist[2], dist[3], dist[4],
                    dist[1] + dist[2] + dist[3] + dist[4]);
        }
    }

    // ================================================================== ② 参考实现对照

    private static void reference(Path pez) throws Exception {
        JsonObject root = rawRoot(pez);
        Chart c = ChartParser.parse(root.toString());
        Ref ref = new Ref(root);
        Random rnd = new Random(42210);
        double maxErr = 0, sumErr = 0;
        int n = 0, over = 0;
        List<String> worst = new ArrayList<>();
        int childSamples = 0;
        for (Chart.Line line : c.lines) {
            List<JsonObject> raws = ref.notesOf(line.index);
            if (raws.size() != line.notes.size()) {
                System.out.printf(Locale.ROOT, "  ⚠ line %d 音符数不一致：raw %d vs parsed %d%n",
                        line.index, raws.size(), line.notes.size());
                continue;
            }
            int parent = parentOf(line);
            for (int i = 0; i < raws.size(); i++) {
                // 分层抽样：带父线的音符抽 100 个，其它抽 100 个
                boolean child = parent >= 0;
                if (child) {
                    if (childSamples >= 100) continue;
                } else if (n - childSamples >= 100) {
                    continue;
                }
                if (rnd.nextInt(4) != 0) continue;   // 稀疏化后再抽
                Chart.Note note = line.notes.get(i);
                JsonObject raw = raws.get(i);
                double t = note.time;
                double[] jar = jarScreen(line, note, t, ASPECT16_9);
                double[] want = ref.noteScreen(line.index, raw, t, ASPECT16_9);
                double err = Math.max(Math.abs(jar[0] - want[0]), Math.abs(jar[1] - want[1]));
                maxErr = Math.max(maxErr, err);
                sumErr += err;
                n++;
                if (child) childSamples++;
                if (err > 1e-3) {
                    over++;
                    if (worst.size() < 3) {
                        worst.add(String.format(Locale.ROOT,
                                "line %d note@%.3fs: 本项目 (%.5f, %.5f) vs 手算 (%.5f, %.5f)",
                                line.index, t, jar[0], jar[1], want[0], want[1]));
                    }
                }
            }
        }
        // 再把「所有屏外音符」逐个与参考实现比一遍（这是最容易藏 bug 的那批）
        double offMax = 0;
        int offN = 0;
        for (Chart.Line line : c.lines) {
            List<JsonObject> raws = ref.notesOf(line.index);
            for (Chart.Note note : line.notes) {
                if (note.fake || noteAlpha(note, note.time) <= 0) continue;
                double[] jar = jarScreen(line, note, note.time, ASPECT16_9);
                if (inside(jar)) continue;
                double[] want = ref.noteScreen(line.index, raws.get(line.notes.indexOf(note)),
                        note.time, ASPECT16_9);
                offMax = Math.max(offMax, Math.max(Math.abs(jar[0] - want[0]), Math.abs(jar[1] - want[1])));
                offN++;
            }
        }
        System.out.printf(Locale.ROOT,
                "  %-28s 抽样 %d（其中带父线 %d）  最大误差 %.3e  平均 %.3e  超 1e-3 的 %d 个%n",
                pez.getFileName(), n, childSamples, maxErr, n == 0 ? 0 : sumErr / n, over);
        System.out.printf(Locale.ROOT,
                "  %-28s 屏外音符 %d 个与参考实现的最大误差 %.3e%n",
                "", offN, offMax);
        for (String w : worst) System.out.println("      " + w);
    }

    // ================================================================== ③ 父线

    /**
     * 合成谱：父线在 (0,0) 旋转 θ，子线挂在父线上、自身有位移与旋转。
     *
     * <p>手算（{@code line.rs:221-228}）：
     * <pre>
     * fetch_pos(child) = fetch_pos(parent) + R(fetch_rot(parent)) · now_translation(child)
     * fetch_rot(child) = child.rot + (rotateWithFather ? fetch_rot(parent) : 0)
     * </pre>
     * 所以子线位置完全由「父线旋转 + 子线自身位移」决定，与子线自转无关。
     */
    private static void syntheticParent() throws Exception {
        double childMoveX = 100.0;      // → 屏幕比例 0.5 + 100/1350
        double childMoveY = 100.0;      // → 屏幕比例 0.5 + 100/900
        double parentRot = 30.0;
        for (boolean rwf : new boolean[]{false, true}) {
            String json = "{\"BPMList\":[{\"bpm\":120.0,\"startTime\":[0,0,1]}],"
                    + "\"META\":{\"RPEVersion\":170,\"offset\":0,\"name\":\"synthetic\"},"
                    + "\"judgeLineList\":["
                    // 父线：位置居中、常转 30°
                    + "{\"Name\":\"parent\",\"Texture\":\"line.png\",\"father\":-1,\"isCover\":1,"
                    + " \"eventLayers\":[{\"moveXEvents\":[{\"start\":0.0,\"end\":0.0,\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}],"
                    + "   \"moveYEvents\":[{\"start\":0.0,\"end\":0.0,\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}],"
                    + "   \"rotateEvents\":[{\"start\":" + parentRot + ",\"end\":" + parentRot + ",\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}],"
                    + "   \"alphaEvents\":[{\"start\":255.0,\"end\":255.0,\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}]}],"
                    + " \"notes\":[]},"
                    // 子线：位移 (100,100)、自转 0
                    + "{\"Name\":\"child\",\"Texture\":\"line.png\",\"father\":0,\"isCover\":1,"
                    + " \"rotateWithFather\":" + rwf + ","
                    + " \"eventLayers\":[{\"moveXEvents\":[{\"start\":" + childMoveX + ",\"end\":" + childMoveX + ",\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}],"
                    + "   \"moveYEvents\":[{\"start\":" + childMoveY + ",\"end\":" + childMoveY + ",\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}],"
                    + "   \"rotateEvents\":[{\"start\":0.0,\"end\":0.0,\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}],"
                    + "   \"alphaEvents\":[{\"start\":255.0,\"end\":255.0,\"startTime\":[0,0,1],\"endTime\":[999,0,1],\"easingType\":1}]}],"
                    + " \"notes\":[{\"type\":1,\"above\":1,\"startTime\":[1,0,1],\"endTime\":[1,0,1],"
                    + "   \"positionX\":0.0,\"yOffset\":0.0,\"alpha\":255,\"size\":1.0,\"speed\":1.0,\"isFake\":0,"
                    + "   \"visibleTime\":999999,\"judgeArea\":1.0}]}]}";
            Chart c = ChartParser.parse(json);
            Chart.Line child = c.lines.get(1);
            Chart.Note note = child.notes.get(0);
            for (double aspect : new double[]{16.0 / 9.0, 4.0 / 3.0}) {
                // ---- 手算 ----
                double lx = (0.5 + childMoveX / 1350.0) * 2.0 - 1.0;
                double ly = ((0.5 + childMoveY / 900.0) * 2.0 - 1.0) / aspect;
                double th = Math.toRadians(-parentRot);   // rpe.rs:682：RPE 旋转乘 -1
                double wx = 0.0 + lx * Math.cos(th) - ly * Math.sin(th);
                double wy = 0.0 + lx * Math.sin(th) + ly * Math.cos(th);
                double handX = (wx + 1.0) / 2.0;
                double handY = 1.0 - (wy * aspect + 1.0) / 2.0;
                // ---- 本项目 ----
                double[] got = jarScreen(child, note, note.time, aspect);
                double rotGot = child.rotateAt(note.time);
                double rotWant = rwf ? -parentRot : 0.0;   // rpe.rs:682 乘 -1
                System.out.printf(Locale.ROOT,
                        "  rotateWithFather=%-5s aspect=%4.2f  子线位置 手算 (%.6f, %.6f) / 本项目 (%.6f, %.6f) "
                                + "Δ=%.2e ；角度 手算 %.2f° / 本项目 %.2f°%n",
                        rwf, aspect, handX, handY, got[0], got[1],
                        Math.max(Math.abs(handX - got[0]), Math.abs(handY - got[1])),
                        rotWant, rotGot);
            }
        }
    }

    /** 真实谱里带父线的例子：本项目 vs 参考实现。 */
    private static void realParent(Path pez) throws Exception {
        JsonObject root = rawRoot(pez);
        Chart c = ChartParser.parse(root.toString());
        Ref ref = new Ref(root);
        int shown = 0;
        for (Chart.Line line : c.lines) {
            if (parentOf(line) < 0) continue;
            if (line.notes.isEmpty()) continue;
            Chart.Note n = line.notes.get(line.notes.size() / 2);
            double[] jar = jarScreen(line, n, n.time, ASPECT16_9);
            double[] want = ref.noteScreen(line.index, ref.notesOf(line.index).get(line.notes.size() / 2),
                    n.time, ASPECT16_9);
            System.out.printf(Locale.ROOT,
                    "  %-26s line %3d father=%3d rwf=%-5s t=%7.2fs  本项目 (%.5f, %.5f)  手算 (%.5f, %.5f)  Δ=%.2e%n",
                    shown == 0 ? pez.getFileName() : "", line.index, parentOf(line),
                    String.valueOf(rotateWithFatherOf(line)), n.time,
                    jar[0], jar[1], want[0], want[1],
                    Math.max(Math.abs(jar[0] - want[0]), Math.abs(jar[1] - want[1])));
            if (++shown >= 3) break;
        }
        if (shown == 0) System.out.printf(Locale.ROOT, "  %-26s 没有带父线的判定线%n", pez.getFileName());
    }

    // ================================================================== ④ 速度语义

    /**
     * 同一份谱，按「老语义：速度段内线性」与「新语义：速度按缓动」各算一遍高度，
     * 比较所有音符高度与末时刻高度的最大差。
     */
    private static void speedModeDiff(Path pez) throws Exception {
        JsonObject root = rawRoot(pez);
        boolean modern = ((JsonObject) root.get("META")).get("RPEVersion").getAsInt() >= 170;
        Ref oldRef = new Ref(root, false);
        Ref newRef = new Ref(root, true);
        double maxDiff = 0;
        int nonLinearSpeedEvents = 0;
        for (JsonElement el : (JsonArray) root.get("judgeLineList")) {
            JsonObject line = (JsonObject) el;
            for (JsonElement le : (JsonArray) line.get("eventLayers")) {
                if (le == null || le.isJsonNull()) continue;
                JsonArray se = ((JsonObject) le).getAsJsonArray("speedEvents");
                if (se == null) continue;
                for (JsonElement e : se) {
                    int et = ((JsonObject) e).has("easingType")
                            ? ((JsonObject) e).get("easingType").getAsInt() : 1;
                    if (et > 2) nonLinearSpeedEvents++;
                }
            }
        }
        for (Chart.Line line : c0(root)) {
            for (Chart.Note n : line.notes) {
                double a = oldRef.height(line.index, n.time);
                double b = newRef.height(line.index, n.time);
                maxDiff = Math.max(maxDiff, Math.abs(a - b));
            }
        }
        System.out.printf(Locale.ROOT,
                "  %-28s RPEVersion 判定=%s，谱面里非线性的速度事件 %d 个；老/新语义高度最大差 %.3e%n",
                pez.getFileName(), modern ? ">=170（吃缓动）" : "<170（段内线性）",
                nonLinearSpeedEvents, maxDiff);
    }

    private static List<Chart.Line> c0(JsonObject root) {
        return ChartParser.parse(root.toString()).lines;
    }

    // ================================================================== 工具

    private static double percent(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    /** 半个音符的屏宽（PlayScreen.NOTE_W_FRAC = 0.13175）。 */
    private static final double HALF_NOTE_X = 0.13175 / 2.0;
    /** 半个音符的屏高：noteH = noteW * (100/998)，再换成屏高比例、除以 aspect。 */
    private static final double HALF_NOTE_Y = 0.13175 / 2.0 * (100.0 / 998.0);

    private static boolean inside(double[] xy) {
        return inside(xy, 0.0, 0.0);
    }

    private static boolean inside(double[] xy, double mx, double my) {
        return xy[0] >= -mx && xy[0] <= 1 + mx && xy[1] >= -my && xy[1] <= 1 + my;
    }

    /** 出界距离（屏高比例；屏内为 0）。 */
    private static double overshoot(double[] xy) {
        double d = 0;
        if (xy[0] < 0) d = Math.max(d, -xy[0]);
        if (xy[0] > 1) d = Math.max(d, xy[0] - 1);
        if (xy[1] < 0) d = Math.max(d, -xy[1]);
        if (xy[1] > 1) d = Math.max(d, xy[1] - 1);
        return d;
    }

    /** 出界超过 5 屏高：谱面自己把线停到很远的地方（哨兵坐标 ±9999 / ±22221）。 */
    private static boolean parked(double[] xy) {
        return xy[0] < -5 || xy[0] > 6 || xy[1] < -5 || xy[1] > 6;
    }

    /** 本项目口径的屏幕位置（复刻 {@code Engine.noteLocal} + {@code Engine.screenPos}，只比 @t 时刻）。 */
    static double[] jarScreen(Chart.Line line, Chart.Note n, double t, double aspect) {
        double rot = Math.toRadians(line.rotateAt(t));
        double c = Math.cos(rot), s = Math.sin(rot);
        double ox = lineX(line, t, aspect) * 2.0 - 1.0;
        double oy = (lineY(line, t, aspect) * 2.0 - 1.0) / aspect;
        double spd = n.type == Chart.Note.Type.HOLD ? 1.0 : n.speed;
        double dh = n.height - line.floorPositionAt(t);
        double ly = dh * spd / aspect;
        if (!n.above) ly = -ly;
        double lx = n.x * 0.1125;
        double wx = ox + lx * c - ly * s;
        double wy = oy + lx * s + ly * c;
        return new double[]{(wx + 1.0) / 2.0, 1.0 - (wy * aspect + 1.0) / 2.0};
    }

    // ---- 反射读新字段：老 jar 上没有就退默认值，这样同一份探针能跑「修前」

    private static Method mAlphaAt;
    private static Method mXAt2;
    private static Method mYAt2;
    private static java.lang.reflect.Field mParentIndex;
    private static java.lang.reflect.Field mRotateWithFather;
    private static boolean reflectTried;

    private static void initReflect() {
        if (reflectTried) return;
        reflectTried = true;
        try {
            mAlphaAt = Chart.Note.class.getMethod("alphaAt", double.class);
        } catch (Exception ignored) {
        }
        try {
            mXAt2 = Chart.Line.class.getMethod("xAt", double.class, double.class);
            mYAt2 = Chart.Line.class.getMethod("yAt", double.class, double.class);
        } catch (Exception ignored) {
        }
        try {
            mParentIndex = Chart.Line.class.getField("parentIndex");
        } catch (Exception ignored) {
        }
        try {
            mRotateWithFather = Chart.Line.class.getField("rotateWithFather");
        } catch (Exception ignored) {
        }
    }

    /** 带 aspect 的判定线位置（新代码有重载；老 jar 没有就退回单参版本）。 */
    private static double lineX(Chart.Line line, double t, double aspect) {
        initReflect();
        try {
            if (mXAt2 != null) return (Double) mXAt2.invoke(line, t, aspect);
        } catch (Exception ignored) {
        }
        return line.xAt(t);
    }

    private static double lineY(Chart.Line line, double t, double aspect) {
        initReflect();
        try {
            if (mYAt2 != null) return (Double) mYAt2.invoke(line, t, aspect);
        } catch (Exception ignored) {
        }
        return line.yAt(t);
    }

    private static double noteAlpha(Chart.Note n, double t) {
        initReflect();
        try {
            if (mAlphaAt != null) return (Double) mAlphaAt.invoke(n, t);
        } catch (Exception ignored) {
        }
        return 1.0;
    }

    private static int parentOf(Chart.Line line) {
        initReflect();
        try {
            if (mParentIndex != null) return (Integer) mParentIndex.get(line);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static boolean rotateWithFatherOf(Chart.Line line) {
        initReflect();
        try {
            if (mRotateWithFather != null) return (Boolean) mRotateWithFather.get(line);
        } catch (Exception ignored) {
        }
        return false;
    }

    private static JsonObject rawRoot(Path pez) throws Exception {
        try (ZipFile zf = new ZipFile(pez.toFile())) {
            ZipEntry hit = null;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (!z.isDirectory() && z.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    hit = z;
                    break;
                }
            }
            if (hit == null) throw new IllegalStateException("包里没有 json: " + pez);
            try (InputStream in = zf.getInputStream(hit)) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        }
    }

    // ================================================================== 独立参考实现

    /**
     * 照着 {@code prpr/src/parse/rpe.rs} 另写的一遍（关键帧语义 / 逐层求和 / 父链矩阵 /
     * 速度积分），用来和本项目的段式实现互相印证。故意不复用 {@link Chart} 的任何求值代码。
     */
    public static final class Ref {
        private final JsonArray lines;
        private final double[] rb;
        private final double[] rt;
        private final double[] rbpm;
        /** true = 170+ 的新速度语义（吃缓动）；false = 老语义（段内线性）。 */
        private final boolean modern;

        public Ref(JsonObject root) {
            this(root, ((JsonObject) root.get("META")).get("RPEVersion").getAsInt() >= 170);
        }

        public Ref(JsonObject root, boolean modern) {
            this.lines = (JsonArray) root.get("judgeLineList");
            this.modern = modern;
            JsonArray list = (JsonArray) root.get("BPMList");
            List<double[]> ranges = new ArrayList<>();
            for (JsonElement el : list) {
                JsonObject o = (JsonObject) el;
                double beat = triple(o.getAsJsonArray("startTime"));
                double bpm = o.get("bpm").getAsDouble();
                if (bpm > 0) ranges.add(new double[]{beat, bpm});
            }
            ranges.sort(Comparator.comparingDouble(a -> a[0]));
            int n = ranges.size();
            rb = new double[n];
            rt = new double[n];
            rbpm = new double[n];
            double time = 0;
            for (int i = 0; i < n; i++) {
                rb[i] = ranges.get(i)[0];
                rbpm[i] = ranges.get(i)[1];
                if (i > 0) time += (rb[i] - rb[i - 1]) * 60.0 / rbpm[i - 1];
                rt[i] = time;
            }
        }

        double triple(JsonArray a) {
            double whole = a.get(0).getAsDouble();
            double num = a.get(1).getAsDouble();
            double den = a.get(2).getAsDouble();
            return whole + (den == 0 ? 0 : num / den);
        }

        double time(JsonElement triple) {
            double beat = triple(triple.getAsJsonArray());
            int i = 0;
            while (i + 1 < rb.length && rb[i + 1] <= beat) i++;
            return rt[i] + (beat - rb[i]) * 60.0 / rbpm[i];
        }

        JsonObject line(int idx) {
            return lines.get(idx).getAsJsonObject();
        }

        List<JsonObject> notesOf(int idx) {
            JsonObject l = line(idx);
            List<JsonObject> out = new ArrayList<>();
            if (!l.has("notes") || l.get("notes").isJsonNull()) return out;
            for (JsonElement e : l.getAsJsonArray("notes")) out.add(e.getAsJsonObject());
            out.sort(Comparator.comparingDouble(o -> time(o.get("startTime"))));
            return out;
        }

        // ---------------- 缓动（tween.rs:99-113 抄一遍）

        static double f(int id, double x) {
            if (id <= 1) return id;
            if (id == 2) return x;
            int major = (id - 3) / 3;
            int minor = (id - 3) % 3;
            double v = minor == 0 ? in(major, x) : minor == 1 ? 1 - in(major, 1 - x)
                    : (x * 2 < 1 ? in(major, x * 2) / 2 : 1 - in(major, 2 - x * 2) / 2);
            return v;
        }

        static double in(int major, double x) {
            switch (major) {
                case 0: return 1 - Math.cos(x * Math.PI / 2);
                case 1: return x * x;
                case 2: return x * x * x;
                case 3: return Math.pow(x, 4);
                case 4: return Math.pow(x, 5);
                case 5: return Math.pow(2, 10 * (x - 1));
                case 6: return 1 - Math.sqrt(Math.max(0, 1 - x * x));
                case 7: return (1.70158 + 1) * x * x * x - 1.70158 * x * x;
                case 8: {
                    double c4 = 2 * Math.PI / 3;
                    return -Math.pow(2, 10 * x - 10) * Math.sin((x * 10 - 10.75) * c4);
                }
                default: {
                    double u = 1 - x;
                    double v;
                    if (u < 1 / 2.75) v = 7.5625 * u * u;
                    else if (u < 2 / 2.75) v = 7.5625 * Math.pow(u - 1.5 / 2.75, 2) + 0.75;
                    else if (u < 2.5 / 2.75) v = 7.5625 * Math.pow(u - 2.25 / 2.75, 2) + 0.9375;
                    else v = 7.5625 * Math.pow(u - 2.625 / 2.75, 2) + 0.984375;
                    return 1 - v;
                }
            }
        }

        static int tweenId(int easingType) {
            // 下标 = easingType；0 位不用（prpr 是 max(1) 之后再查表）: 1,2 线性，其余见 parse.rs:75-94
            int[] map = {2, 2, 4, 3, 7, 6, 5, 8, 10, 9, 13, 12, 11, 14, 16, 15, 19, 18,
                    22, 21, 25, 24, 23, 26, 28, 27, 31, 30, 32, 29};
            int e = Math.max(1, easingType);
            return e < map.length ? map[e] : 2;
        }

        /** 一条事件的缓动（含 easingLeft/Right 的 ClampedTween）。 */
        double ease(JsonObject e, double x) {
            if (e.has("bezier") && e.get("bezier").getAsInt() != 0) return x;   // 样本里没有
            int id = tweenId(e.has("easingType") ? e.get("easingType").getAsInt() : 1);
            double l = e.has("easingLeft") ? e.get("easingLeft").getAsDouble() : 0;
            double r = e.has("easingRight") ? e.get("easingRight").getAsDouble() : 1;
            l = Math.max(0, Math.min(1, l));
            r = Math.max(0, Math.min(1, r));
            if (id <= 2 || (Math.abs(l) < 1e-5 && Math.abs(r - 1) < 1e-5) || l >= r) return f(id, x);
            double y0 = f(id, l);
            double y1 = f(id, r);
            return (f(id, l + (r - l) * x) - y0) / (y1 - y0);
        }

        // ---------------- 事件求值（Anim 关键帧语义，逐层求和）

        /** {@code rpe.rs:306-330} parse_events + {@code anim.rs:113-121}：一层的值。 */
        public double layerValue(JsonArray events, double t) {
            List<double[]> kf = new ArrayList<>();      // {time, value, tweenId}
            List<JsonObject> evs = new ArrayList<>();
            for (JsonElement el : events) evs.add(el.getAsJsonObject());
            evs.sort(Comparator.comparingDouble(o -> time(o.get("startTime"))));
            for (JsonObject e : evs) {
                kf.add(new double[]{time(e.get("startTime")), e.get("start").getAsDouble(),
                        tweenId(e.has("easingType") ? e.get("easingType").getAsInt() : 1)});
                kf.add(new double[]{time(e.get("endTime")), e.get("end").getAsDouble(), 0});
            }
            if (kf.isEmpty()) return 0;
            if (kf.size() == 1) return kf.get(0)[1];
            int cursor = 0;
            while (cursor + 1 < kf.size() && kf.get(cursor + 1)[0] <= t) cursor++;
            if (cursor == kf.size() - 1) return kf.get(cursor)[1];
            double t0 = kf.get(cursor)[0];
            double t1 = kf.get(cursor + 1)[0];
            double v0 = kf.get(cursor)[1];
            double v1 = kf.get(cursor + 1)[1];
            int id = (int) kf.get(cursor)[2];
            if (t1 <= t0) return v1;
            double fr = (t - t0) / (t1 - t0);
            double y = id == 2 ? fr : (id <= 1 ? id : f(id, fr));
            return v0 + (v1 - v0) * y;
        }

        public double sum(JsonObject line, String key, double t) {
            double v = 0;
            JsonArray layers = (JsonArray) line.get("eventLayers");
            for (JsonElement le : layers) {
                if (le == null || le.isJsonNull()) continue;
                JsonArray arr = ((JsonObject) le).getAsJsonArray(key);
                if (arr != null && arr.size() > 0) v += layerValue(arr, t);
            }
            return v;
        }

        public double moveX(int idx, double t) {
            return 0.5 + sum(line(idx), "moveXEvents", t) / 1350.0;
        }

        public double moveY(int idx, double t) {
            return 0.5 + sum(line(idx), "moveYEvents", t) / 900.0;
        }

        public double rot(int idx, double t) {
            return -sum(line(idx), "rotateEvents", t);
        }

        int father(int idx) {
            JsonObject l = line(idx);
            return l.has("father") ? l.get("father").getAsInt() : -1;
        }

        boolean rwf(int idx) {
            JsonObject l = line(idx);
            return l.has("rotateWithFather") && l.get("rotateWithFather").getAsBoolean();
        }

        public double fetchRot(int idx, double t) {
            double r = rot(idx, t);
            if (rwf(idx)) {
                int p = father(idx);
                if (p >= 0) r += fetchRot(p, t);
            }
            return r;
        }

        public double[] fetchPos(int idx, double t, double aspect) {
            double lx = moveX(idx, t) * 2 - 1;
            double ly = (moveY(idx, t) * 2 - 1) / aspect;
            int p = father(idx);
            if (p < 0) return new double[]{lx, ly};
            double[] pp = fetchPos(p, t, aspect);
            double th = Math.toRadians(fetchRot(p, t));
            return new double[]{pp[0] + lx * Math.cos(th) - ly * Math.sin(th),
                    pp[1] + lx * Math.sin(th) + ly * Math.cos(th)};
        }

        // ---------------- 速度积分（prpr 高度，单位 = Phira 高度）

        /** 速度事件的「段」：{a, b, s0, s1, easingType}；空档用上一个速度补。 */
        private List<double[]> speedSegs(int idx) {
            List<double[]> out = new ArrayList<>();
            JsonObject l = line(idx);
            List<JsonObject> evs = new ArrayList<>();
            for (JsonElement le : (JsonArray) l.get("eventLayers")) {
                if (le == null || le.isJsonNull()) continue;
                JsonArray arr = ((JsonObject) le).getAsJsonArray("speedEvents");
                if (arr == null) continue;
                for (JsonElement e : arr) evs.add(e.getAsJsonObject());
            }
            evs.sort(Comparator.comparingDouble(o -> time(o.get("startTime"))));
            double cursor = Double.NaN;
            double prev = 0;
            for (JsonObject e : evs) {
                double a = time(e.get("startTime"));
                double b = Math.max(time(e.get("endTime")), a);
                double s0 = e.get("start").getAsDouble() * RUST_SPEED_RATIO;
                double s1 = e.get("end").getAsDouble() * RUST_SPEED_RATIO;
                int et = e.has("easingType") ? e.get("easingType").getAsInt() : 1;
                if (Double.isNaN(cursor)) cursor = a;
                if (a > cursor) out.add(new double[]{cursor, a, prev, prev, 1});
                if (b > a) out.add(new double[]{a, b, s0, s1, modern ? et : 1});
                cursor = Math.max(cursor, b);
                prev = s1;
            }
            return out;
        }

        /** {@code ∫ speed dt}，单位 = prpr 的高度。 */
        public double height(int idx, double t) {
            List<double[]> segs = speedSegs(idx);
            double h = 0;
            for (double[] s : segs) {
                if (t <= s[0]) return h;
                double hi = Math.min(t, s[1]);
                h += integral(s, s[0], hi);
                if (t <= s[1]) return h;
            }
            if (!segs.isEmpty()) {
                double[] last = segs.get(segs.size() - 1);
                h += last[3] * (t - last[1]);
            }
            return h;
        }

        private double integral(double[] seg, double a, double b) {
            double s0 = seg[2];
            double s1 = seg[3];
            int et = (int) seg[4];
            if (et <= 2 || b <= a) return (s0 + s1) / 2 * (b - a);
            // 缓动曲线：Simpson 32 段
            int n = 32;
            double h = (b - a) / n;
            double acc = 0;
            for (int i = 0; i < n; i++) {
                double xa = a + h * i;
                double xb = xa + h;
                double fa = speedAt(seg, xa);
                double fm = speedAt(seg, (xa + xb) / 2);
                double fb = speedAt(seg, xb);
                acc += (fa + 4 * fm + fb) / 6 * h;
            }
            return acc;
        }

        private double speedAt(double[] seg, double t) {
            double frac = (t - seg[0]) / (seg[1] - seg[0]);
            frac = Math.max(0, Math.min(1, frac));
            int id = tweenId((int) seg[4]);
            return seg[2] + (seg[3] - seg[2]) * f(id, frac);
        }

        // ---------------- 音符 → 屏幕

        double[] noteScreen(int idx, JsonObject note, double judgmentTime, double aspect) {
            JsonObject l = line(idx);
            double[] lp = fetchPos(idx, judgmentTime, aspect);
            double th = Math.toRadians(fetchRot(idx, judgmentTime));
            double posX = note.get("positionX").getAsDouble() / 675.0;    // rpe.rs:588
            int type = note.get("type").getAsInt();
            double speed = note.has("speed") ? note.get("speed").getAsDouble() : 1.0;
            double spd = type == 2 ? 1.0 : speed;
            double hn = height(idx, time(note.get("startTime")));
            double hNow = height(idx, judgmentTime);
            double yOff = (note.has("yOffset") ? note.get("yOffset").getAsDouble() : 0) * 2.0 / 900.0;
            double localY = (hn + yOff - hNow) * spd / aspect;
            boolean above = note.has("above") && note.get("above").getAsInt() == 1;
            if (!above) localY = -localY;
            double wx = lp[0] + posX * Math.cos(th) - localY * Math.sin(th);
            double wy = lp[1] + posX * Math.sin(th) + localY * Math.cos(th);
            return new double[]{(wx + 1) / 2, 1 - (wy * aspect + 1) / 2};
        }
    }
}
