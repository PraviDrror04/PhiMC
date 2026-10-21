package dev.dsh.phimc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 官方 Phigros 谱面解析器。对字段名做了别名兼容（不同版本 / 不同导出工具命名略有差异）。
 *
 * <p>两条和 Phira 对齐的口径：
 * <ol>
 *   <li><b>音符高度来自判定线速度事件的积分</b>，不是 JSON 的 {@code floorPosition}
 *       （{@code prpr/src/parse/pgr.rs:213-217}）；</li>
 *   <li><b>速度事件的 value 原样使用</b>，不做「0 / 负数 / 999 视为 1」的兜底
 *       （{@code pgr.rs:96-100} 里 {@code pos += (end-start)*r*it.value}；负速度是 Phigros 的
 *       反向滚动，999 是导出工具的瞬移哨兵，两者都是谱面语义的一部分）。</li>
 * </ol>
 */
public final class ChartParser {

    private ChartParser() {
    }

    public static Chart parse(Path file) throws IOException {
        // PEC 是纯文本（首行是偏移毫秒数），不能直接丢给 JSON 解析器：先偷看开头 4KB 判断，
        // 是 PEC 就整份读进来交给 PecParser（一个 .pec 只有一百多 KB，读整份没问题）。
        String head = peek(file, 4096);
        if (PecParser.looksLikePec(head)) {
            return PecParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r);
        }
    }

    /**
     * 解析谱面文本：<b>自动识别 PEC / RPE / PGR</b>。
     *
     * <p>这是运行时（{@code SongEntry.chartJson()} → {@code Session.start()}）走的那条路，
     * 也是「解析失败清单」工具走的那条路。
     *
     * <p><b>格式识别不再建对象树</b>（平台优化）：{@link RpeParser#looksLikeRpe(String)} 用
     * {@code JsonReader} 只扫顶层键名，命中就直接走 {@link RpeParser} 的流式解析，
     * 跳过 {@code JsonParser.parseString} 那棵中间树。{@code 77769_big song}（46.7MB / 306343 事件）
     * 的树约 526MB、解析期峰值堆 977MB，是「进游戏瞬间卡死」的主因；
     * {@code prpr} 那边是 serde 直接反序列化成最终结构体，没有中间树，所以这属于平台优化。
     * 只有官方 PGR（本来就小，最大 12MB）仍旧走建树那条路。
     */
    public static Chart parse(String source) {
        if (PecParser.looksLikePec(source)) {
            return PecParser.parse(source);
        }
        if (RpeParser.looksLikeRpe(source)) {
            return RpeParser.parse(source);
        }
        return parse(JsonParser.parseString(source));
    }

    /** 读文件开头 {@code max} 个字符（只用于判断格式，不解析）。 */
    private static String peek(Path file, int max) throws IOException {
        StringBuilder sb = new StringBuilder(max);
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buf = new char[512];
            while (sb.length() < max) {
                int n = r.read(buf);
                if (n < 0) break;
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    public static Chart parse(Reader r) throws IOException {
        return parse(JsonParser.parseReader(r));
    }

    private static Chart parse(JsonElement rootEl) {
        skippedLines = 0;
        skippedNotes = 0;
        if (rootEl == null || !rootEl.isJsonObject()) {
            throw new IllegalArgumentException("谱面 JSON 根节点不是对象（可能是数组 / 空文件）");
        }
        JsonObject root = rootEl.getAsJsonObject();
        // ---- 格式自动识别 ----
        // 官方 PGR：有 formatVersion（+ judgeLineList）。
        // RPE（Re:PhiEdit，.pez 里那种）：没有 formatVersion，但有 BPMList / META。
        // 顺序不能反：PGR 的判定线里也可能出现叫 "BPMList" 的东西的概率为零，但
        // "formatVersion" 一定是 PGR（RPE 从不写这个字段）。
        if (RpeParser.looksLikeRpe(root)) {
            return RpeParser.parse(root);
        }
        // formatVersion 缺失 / 不是数字时：
        //   ① 有的导出工具写的是 "format"（同一个含义）→ 先读它；
        //   ② 两个都没有 → 按 1（老格式）处理，**不因为版本号奇怪就整体失败**。
        int version = intOf(root, "formatVersion", intOf(root, "format", 1));
        double offset = dblOf(root, "offset", 0.0);

        JsonArray arr = arrOf(root, "judgeLineList");
        List<Chart.Line> lines = new ArrayList<>();
        if (arr != null) {
            int idx = 0;
            for (JsonElement e : arr) {
                // 坏数据（null / 不是对象 / 单条解析抛异常）只跳过这一条判定线，继续解析别的
                if (e == null || !e.isJsonObject()) continue;
                try {
                    lines.add(parseLine(e.getAsJsonObject(), idx));
                } catch (RuntimeException bad) {
                    // 核心是纯 Java、没有 slf4j：把坏线记进「上次的坏数据条数」，诊断工具会打印
                    skippedLines++;
                }
                idx++;
            }
        }
        return new Chart(version, offset, lines);
    }

    /** 上一次 {@link #parse} 跳过的坏判定线 / 坏音符条数（诊断用；每次 parse 开头清零）。 */
    private static volatile int skippedLines;
    private static volatile int skippedNotes;

    /** 上一次解析跳过了多少条坏数据（判定线 + 音符），headless 清单工具会打印。 */
    public static int lastSkippedData() {
        return skippedLines + skippedNotes;
    }

    private static Chart.Line parseLine(JsonObject o, int index) {
        double bpm = dblOf(o, "bpm", 120.0);
        double unit = 60.0 / (32.0 * (bpm <= 0 ? 120.0 : bpm));

        // 速度事件必须先读：音符高度要用它的积分（pgr.rs:233-235 的顺序也是先 speed 后 notes）
        List<Chart.Ev> speed = parseSpeed(arrOf(o, "speedEvents"), unit);
        List<Chart.Ev> move = parseMove(arrOf(o, "judgeLineMoveEvents"), unit);
        List<Chart.Ev> moveX = new ArrayList<>();
        List<Chart.Ev> moveY = new ArrayList<>();
        for (Chart.Ev e : move) {
            moveX.add(new Chart.Ev(e.t0, e.t1, e.v0, e.v1, e.v0b, e.v1b, 0));
            moveY.add(new Chart.Ev(e.t0, e.t1, e.v0b, e.v1b, 0, 0, 0));
        }
        List<Chart.Ev> rot = parseRotate(arrOf(o, "judgeLineRotateEvents"), unit);
        List<Chart.Ev> alpha = parseRotate(arrOf(o, "judgeLineDisappearEvents"), unit);

        List<Chart.Note> notes = new ArrayList<>();
        JsonArray above = arrOf(o, "notesAbove");
        JsonArray below = arrOf(o, "notesBelow");
        if (above != null || below != null) {
            readNotes(above, true, unit, speed, notes);
            readNotes(below, false, unit, speed, notes);
        } else {
            // 少数导出工具只用 notes 一个数组。只有 notesAbove / notesBelow 都不存在时才读，
            // 否则同一份音符会被读两遍（列表重复 → 谱面音符翻倍、渲染重影）。
            readNotes(arrOf(o, "notes"), true, unit, speed, notes);
        }
        notes.sort((a, b) -> Double.compare(a.time, b.time));   // 整个列表只排一次

        // PGR 没有「事件层」概念：每条属性只给一层轨道（Chart.Line 求值时按层求和）
        return new Chart.Line(index, bpm, notes, speed,
                List.of(moveX), List.of(moveY), List.of(rot), List.of(alpha));
    }

    /**
     * 读音符。高度按 {@link Chart#heightAt} 取判定线曲线在 {@code note.time} 处的值
     * （{@code pgr.rs:213-217}），hold 的结束高度同理（{@code pgr.rs:194-199}，渲染时按需求值）。
     */
    private static void readNotes(JsonArray arr, boolean above, double unit,
                                  List<Chart.Ev> speed, List<Chart.Note> out) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) {      // 数组里混了 null / 字符串：跳过
                skippedNotes++;
                continue;
            }
            JsonObject n = el.getAsJsonObject();
            int typeCode = intOf(n, "type", 1);
            // 类型容错：0 / 5 / 未知值都不整体失败。
            // 0 在导出工具里通常是「占位」，不是可打音符 → 跳过；其它未知值按 TAP 处理
            //（宁可多一个能打的音符，也不要整张谱面打不开）。
            Chart.Note.Type type;
            switch (typeCode) {
                case 0 -> {
                    skippedNotes++;
                    continue;
                }
                case 2 -> type = Chart.Note.Type.DRAG;
                case 3 -> type = Chart.Note.Type.HOLD;
                case 4 -> type = Chart.Note.Type.FLICK;
                default -> type = Chart.Note.Type.TAP;
            }
            // startTime/endTime 兼容：数字 / 数组 / 对象（见 timeOf）
            double time = timeOf(n, "time", 0) * unit;
            double hold = timeOf(n, "holdTime", 0) * unit;
            double x = dblOf(n, "positionX", 0);
            // pgr.rs:213：hold 的 speed 恒为 1，其它音符取谱面值
            double speedVal = type == Chart.Note.Type.HOLD ? 1.0 : dblOf(n, "speed", 1.0);
            double height = Chart.heightAt(speed, time);
            boolean fake = boolOf(n, "isFake", false);
            out.add(new Chart.Note(type, time, hold, x, speedVal, height, above, fake));
        }
    }

    /**
     * speedEvents → 线性事件。只保留 (startTime, endTime, value)，value 原样保留：
     * Phira 直接用它积分（{@code pgr.rs:98}），负数代表反向滚动、999 代表瞬移，都是谱面语义。
     */
    private static List<Chart.Ev> parseSpeed(JsonArray arr, double unit) {
        List<Chart.Ev> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            double t0 = timeOf(e, "startTime", 0) * unit;
            double t1 = timeOf(e, "endTime", 0) * unit;
            if (!validRange(t0, t1)) continue;                       // startTime > endTime：丢弃（同 prpr validate_events）
            double v = dblOf(e, "value", 10.0);
            if (!Double.isFinite(v)) v = 0.0;                        // JSON 不可能给 NaN/Inf，这里只是兜底
            double floor = dblOf(e, "floorPosition", 0);
            out.add(new Chart.Ev(t0, t1, v, v, v, v, floor));
        }
        out.sort((a, b) -> Double.compare(a.t0, b.t0));
        return out;
    }

    /** move / rotate / alpha 共用：start,end 是主值，start2,end2 是次值（move 的 y）。 */
    private static List<Chart.Ev> parseMove(JsonArray arr, double unit) {
        List<Chart.Ev> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            double t0 = timeOf(e, "startTime", 0) * unit;
            double t1 = timeOf(e, "endTime", 0) * unit;
            double v0 = dblOf(e, "start", 0);
            double v1 = dblOf(e, "end", 0);
            double v0b = dblOf(e, "start2", 0);
            double v1b = dblOf(e, "end2", 0);
            if (!validRange(t0, t1)) continue;
            out.add(new Chart.Ev(t0, t1, v0, v1, v0b, v1b, 0));
        }
        out.sort((a, b) -> Double.compare(a.t0, b.t0));
        return out;
    }

    private static List<Chart.Ev> parseRotate(JsonArray arr, double unit) {
        List<Chart.Ev> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            double t0 = timeOf(e, "startTime", 0) * unit;
            double t1 = timeOf(e, "endTime", 0) * unit;
            double v0 = dblOf(e, "start", 0);
            double v1 = dblOf(e, "end", 0);
            if (!validRange(t0, t1)) continue;
            out.add(new Chart.Ev(t0, t1, v0, v1, 0, 0, 0));
        }
        out.sort((a, b) -> Double.compare(a.t0, b.t0));
        return out;
    }

    // ---- 小工具：Gson 的 get() 对缺字段返回 null，这里统一兜底 ----

    /**
     * 事件时间区间合法性：{@code startTime > endTime} 的事件在谱面里没有意义
     * （{@code Ev.lerp} 会退化成返回终值、累计高度会算错），按 prpr 的 validate_events 直接丢弃。
     */
    private static boolean validRange(double t0, double t1) {
        return Double.isFinite(t0) && Double.isFinite(t1) && t0 <= t1;
    }

    private static JsonArray arrOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static double dblOf(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            return def;
        }
    }

    /**
     * 时间字段容错：{@code startTime} / {@code endTime} / {@code time} 这些字段在不同导出工具里
     * 可能是<b>数字</b>、<b>数组</b>（{@code [start, end]} 或 RPE 的 {@code [beat, beat2, tween]}）、
     * 或者<b>对象</b>（{@code {"value": 0}} / {@code {"time": 0}} / {@code {"beat": 0}}）。
     *
     * <p>取第一个能读出来的数值；都读不出来就用默认值（<b>不抛异常、不整体失败</b>）。
     */
    private static double timeOf(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        return timeOf(e, def);
    }

    private static double timeOf(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) return def;
        if (e.isJsonPrimitive()) {
            try {
                return e.getAsDouble();
            } catch (RuntimeException ex) {
                return def;
            }
        }
        if (e.isJsonArray()) {
            for (JsonElement it : e.getAsJsonArray()) {
                double v = timeOf(it, Double.NaN);
                if (!Double.isNaN(v)) return v;
            }
            return def;
        }
        if (e.isJsonObject()) {
            JsonObject obj = e.getAsJsonObject();
            for (String k : new String[]{"value", "time", "beat", "start", "0"}) {
                JsonElement it = obj.get(k);
                if (it == null || it.isJsonNull()) continue;
                double v = timeOf(it, Double.NaN);
                if (!Double.isNaN(v)) return v;
            }
            return def;
        }
        return def;
    }

    private static int intOf(JsonObject o, String key, int def) {
        return (int) dblOf(o, key, def);
    }

    private static boolean boolOf(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return def;
        }
    }
}
