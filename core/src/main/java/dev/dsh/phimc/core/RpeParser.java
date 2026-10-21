package dev.dsh.phimc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RPE（Re:PhiEdit）谱面解析器：<b>.pez / .json</b> 里的 RPE 格式 → 本项目的 {@link Chart}。
 *
 * <p>唯一依据是 {@code prpr/src/parse/rpe.rs}（GPL 参考实现），本文件里的 {@code rpe.rs:NN}
 * 都是指那份源码的行号；另外引用的 {@code core.rs} / {@code core/line.rs} /
 * {@code core/note.rs} / {@code core/object.rs} / {@code core/anim.rs} / {@code core/tween.rs}
 * 都在 {@code prpr/src/} 下。
 *
 * <h2>和官方 PGR 格式的差别（也是为什么必须单独写一个解析器）</h2>
 * <ul>
 *   <li><b>没有 {@code formatVersion}</b>：靠 {@code BPMList} / {@code META} 认出来
 *       （{@link ChartParser#parse(JsonElement)} 里做自动识别）。</li>
 *   <li><b>时间不是「1/32 拍」而是三元组 {@code [整数拍, 分子, 分母]} = i + n/d</b>
 *       （{@code core.rs:88-101} 的 {@code Triple}），要按 {@code BPMList} 折成秒
 *       （{@code core.rs:112-150}）。</li>
 *   <li><b>事件分「事件层」{@code eventLayers}</b>：同一条线可以有多层，
 *       各层的值是<b>相加</b>的（{@code anim.rs:27-33}）。</li>
 *   <li><b>缓动是数字编号</b> {@code easingType} → {@code RPE_TWEEN_MAP}（{@code parse.rs:75-94}）
 *       → {@code TWEEN_FUNCTIONS}（{@code tween.rs:99-113}）；另有 {@code bezier} 贝塞尔。</li>
 *   <li><b>判定线可以挂在父线上</b>（{@code father} / {@code rotateWithFather}，
 *       {@code line.rs:211-228}），合成时要按宽高比做世界坐标变换（见 {@link Chart.Line}）。</li>
 *   <li><b>单位</b>：{@code RPE_WIDTH=1350}、{@code RPE_HEIGHT=900}
 *       （{@code rpe.rs:22-23}），速度事件还要乘 {@code SPEED_RATIO = 10/45/HEIGHT_RATIO}
 *       （{@code rpe.rs:24}）。</li>
 * </ul>
 *
 * <h2>本项目怎么映射到 {@link Chart}</h2>
 * <ul>
 *   <li><b>速度</b>：{@link Chart#heightAt} 已经负责除 {@code HEIGHT_RATIO}，所以事件值只放
 *       {@code 谱面速度 * 10/45}，音符高度 = {@code heightAt(note.time)}，与 PGR 那条路径同源。</li>
 *   <li><b>位置</b>：{@code moveX ∈ [-675, 675]} → 屏幕比例 {@code 0.5 + moveX/1350}；
 *       {@code moveY ∈ [-450, 450]}（画布中心制）→ {@code 0.5 + moveY/900}；
 *       旋转乘 {@code -1}（{@code rpe.rs:682}）；透明度 {@code /255}（{@code rpe.rs:680}）。</li>
 *   <li><b>缓动</b>：位置/旋转/透明度<b>不折线化</b>，直接把缓动函数挂在
 *       {@link Chart.Ev#tween} 上，求值口径与 {@code anim.rs:113-121} 完全一致。
 *       只有「速度」还在段内折线化（16 段），因为 {@link Chart#heightAt} 只能吃常量斜率，
 *       理由见 {@link #speedSegments}。</li>
 * </ul>
 */
public final class RpeParser {

    /** {@code rpe.rs:22} {@code RPE_WIDTH}：RPE 画布宽（positionX ∈ [-675, 675]）。 */
    public static final double RPE_WIDTH = 1350.0;
    /** {@code rpe.rs:23} {@code RPE_HEIGHT}：RPE 画布高（moveY ∈ [-450, 450]）。 */
    public static final double RPE_HEIGHT = 900.0;

    /**
     * {@code rpe.rs:24} {@code SPEED_RATIO = 10/45/HEIGHT_RATIO}。
     * 这里只取 {@code 10/45}：{@code /HEIGHT_RATIO} 那一步由 {@link Chart#heightAt} 统一做。
     */
    static final double SPEED_RATIO = 10.0 / 45.0;

    /** 判定线移动 X 的一半画布宽：{@code moveX / 675} = Phira 世界坐标。 */
    private static final double HALF_WIDTH = RPE_WIDTH / 2.0;

    /** 事件比较 / 退化段判定的容差（对齐 prpr 的 {@code EPS}）。 */
    private static final double EPS = 1e-5;

    /**
     * 速度事件的折线粒度（秒/块）。非线性缓动的速度<u>段内</u>会被切成约
     * {@code 段长 / 0.02s} 块（8..64 块）、每块取梯形平均速度，所以块边界上的累计高度是精确的，
     * 只有块内是弦线。这么做的原因：{@link Chart#heightAt} 的 keyframe 模型只表达
     * 「段内常量斜率」，而 prpr 那边是给速度积分单独造了一个 {@code SpeedIntegralTween}
     * （{@code rpe.rs:212-292}）。
     */
    private static final double SPEED_PIECE_SECONDS = 0.02;

    /** 同一格子里有 2 层以上的非线性缓动时（很少见）的折线段数。 */
    private static final int SPLIT_PIECES = 8;

    private RpeParser() {
    }

    // ==================================================================== 入口

    /**
     * 格式识别：RPE 没有 {@code formatVersion}，但有 {@code BPMList}（事件/音符的时间基准）
     * 和 {@code META}（曲目信息）。
     */
    public static boolean looksLikeRpe(JsonObject root) {
        return root != null && !root.has("formatVersion")
                && (root.has("BPMList") || root.has("META"));
    }

    /**
     * 同上，但<b>不建对象树</b>：用 {@link JsonReader} 只扫顶层键名（每个值都 {@code skipValue}）。
     *
     * <p>为什么需要它：{@link ChartParser#parse(String)} 以前为了格式识别会先把整份 JSON 建成
     * Gson 对象树再交给这里——{@code 77769_big song} 的树本身约 526MB。
     * 流式判断只需要「有没有 {@code BPMList} / {@code META}、有没有 {@code formatVersion}」，
     * 扫一遍键名就够，代价可以忽略。
     */
    public static boolean looksLikeRpe(String json) {
        boolean bpm = false;
        boolean meta = false;
        boolean version = false;
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if ("BPMList".equals(name)) bpm = true;
                else if ("META".equals(name)) meta = true;
                else if ("formatVersion".equals(name)) version = true;
                r.skipValue();
            }
            r.endObject();
        } catch (IOException | RuntimeException e) {
            return false;                       // 不是合法 JSON 对象 → 交给老路径报错
        }
        return !version && (bpm || meta);
    }

    public static Chart parse(String json) {
        return parseStreaming(json);
    }

    /**
     * <b>流式解析</b>（平台优化，<b>源码无此项</b>）：用 {@link com.google.gson.stream.JsonReader}
     * 扫一遍，把 {@code judgeLineList} <b>逐个元素</b>读成小 {@link JsonObject} 并立刻转换成
     * {@link Chart.Line}，转完就丢。
     *
     * <p><b>为什么需要它（实测数据）</b>：{@code 77769_big song} 是 46.7MB / 306343 个事件的 RPE 谱。
     * 用 {@code JsonParser.parseString} 先建完整对象树时，解析期峰值堆 <b>977MB</b>
     * （Gson 树约 526MB + 原串约 285MB + 我们的模型约 155MB），在 Minecraft 默认 2G 堆里
     * 就是「进游戏瞬间卡死」——一次 1GB 的分配 + 反复 Full GC。
     * 改成流式后峰值 <b>468MB</b>、解析 1245ms → 929ms。
     *
     * <p>{@code prpr} 那边不存在这个问题：serde 直接反序列化成最终结构体，
     * 没有中间树（{@code parse/rpe.rs} 的 {@code RPEChart} 就是最终模型）。
     * 所以这是 <b>「源码无此项，理由是 Java 侧 Gson 必须建树而 Rust 不需要」</b>的平台优化，
     * 语义完全不变（同一份 {@link #parseLine}）。
     *
     * <p><b>键顺序</b>：音符/事件折秒要用 {@code BPMList}，速度语义要用 {@code META.RPEVersion}，
     * 所以必须在遇到 {@code judgeLineList} <b>之前</b>拿到它们。RPE 的 JSON 是 serde 结构体序列化，
     * 实测 20 个包都是 {@code BPMList, META, judgeLineList} 这个顺序；万一不是，
     * 这里会退回两遍扫描（{@link #parseTwoPass}），行为完全一致，只是慢一点。
     */
    private static Chart parseStreaming(String json) {
        JsonObject meta = new JsonObject();
        JsonArray bpmList = null;
        List<Chart.Line> lines = new ArrayList<>();
        boolean[] late = {false};                       // judgeLineList 出现在 BPMList/META 之前
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                switch (name) {
                    case "BPMList" -> {
                        JsonElement el = JsonParser.parseReader(r);
                        if (el != null && el.isJsonArray()) bpmList = el.getAsJsonArray();
                    }
                    case "META" -> {
                        JsonElement el = JsonParser.parseReader(r);
                        if (el != null && el.isJsonObject()) meta = el.getAsJsonObject();
                    }
                    case "judgeLineList" -> {
                        if (bpmList == null || meta.size() == 0) {
                            late[0] = true;
                            r.skipValue();
                            continue;
                        }
                        Beats beats = new Beats(bpmList);
                        int ver = intOf(meta, "RPEVersion", 160);
                        double base = beats.baseBpm();
                        boolean modern = ver >= 170;
                        r.beginArray();
                        while (r.hasNext()) {
                            JsonElement el = JsonParser.parseReader(r);
                            if (el == null || !el.isJsonObject()) continue;
                            lines.add(parseLine(el.getAsJsonObject(), lines.size(), beats, base, modern));
                        }
                        r.endArray();
                    }
                    default -> r.skipValue();
                }
            }
            r.endObject();
        } catch (IOException | RuntimeException e) {
            // 流式解析失败（不是 JSON 对象 / 语法错）→ 退回整树解析，让老的报错路径照旧工作
            return parse(JsonParser.parseString(json).getAsJsonObject());
        }
        if (late[0]) return parseTwoPass(json);
        return finish(meta, intOf(meta, "RPEVersion", 160), lines);
    }

    /** 备用路径：{@code judgeLineList} 出现在 {@code BPMList}/{@code META} 之前时的两遍扫描。 */
    private static Chart parseTwoPass(String json) {
        JsonObject meta = new JsonObject();
        JsonArray bpmList = null;
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if ("judgeLineList".equals(name)) {
                    r.skipValue();
                    continue;
                }
                JsonElement el = JsonParser.parseReader(r);
                if ("META".equals(name) && el != null && el.isJsonObject()) {
                    meta = el.getAsJsonObject();
                } else if ("BPMList".equals(name) && el != null && el.isJsonArray()) {
                    bpmList = el.getAsJsonArray();
                }
            }
            r.endObject();
        } catch (IOException | RuntimeException e) {
            return parse(JsonParser.parseString(json).getAsJsonObject());
        }
        Beats beats = new Beats(bpmList);
        int rpeVersion = intOf(meta, "RPEVersion", 160);
        boolean modernSpeed = rpeVersion >= 170;
        double baseBpm = beats.baseBpm();
        List<Chart.Line> lines = new ArrayList<>();
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (!"judgeLineList".equals(name)) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext()) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el == null || !el.isJsonObject()) continue;
                    lines.add(parseLine(el.getAsJsonObject(), lines.size(), beats, baseBpm, modernSpeed));
                }
                r.endArray();
            }
            r.endObject();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("RPE 谱面流式解析失败：" + e.getMessage(), e);
        }
        return finish(meta, rpeVersion, lines);
    }

    public static Chart parse(JsonObject root) {
        JsonObject meta = objOf(root, "META");
        int rpeVersion = intOf(meta, "RPEVersion", 160);
        boolean modernSpeed = rpeVersion >= 170;
        Beats beats = new Beats(arrOf(root, "BPMList"));
        double baseBpm = beats.baseBpm();
        List<Chart.Line> lines = new ArrayList<>();
        JsonArray judgeLines = arrOf(root, "judgeLineList");
        if (judgeLines != null) {
            for (JsonElement el : judgeLines) {
                if (el == null || !el.isJsonObject()) continue;
                lines.add(parseLine(el.getAsJsonObject(), lines.size(), beats, baseBpm, modernSpeed));
            }
        }
        return finish(meta, rpeVersion, lines);
    }

    /** {@code rpe.rs:933}：Chart 的 offset 是 {@code META.offset}（毫秒）→ 秒，正数表示谱面比音乐快。 */
    private static Chart finish(JsonObject meta, int rpeVersion, List<Chart.Line> lines) {
        double offset = dblOf(meta, "offset", 0.0) / 1000.0;
        return new Chart("rpe", rpeVersion, offset, lines);
    }

    private static Chart.Line parseLine(JsonObject line, int index, Beats beats,
                                        double baseBpm, boolean modernSpeed) {
        JsonArray layers = arrOf(line, "eventLayers");

        // 速度事件先算：音符高度要用它的积分（顺序与 rpe.rs:671-676 一致）
        List<Chart.Ev> speed = speedTrack(layers, beats, modernSpeed);
        // moveX：raw ∈ [-675,675]（画布中心 = 0）→ 屏宽比例 0.5 + raw/1350
        // （rpe.rs:683 的 2/RPE_WIDTH 是世界坐标口径，世界 1 = 半个屏宽）
        List<List<Chart.Ev>> moveX = track(layers, "moveXEvents", beats, 1.0 / RPE_WIDTH, 0.5);
        // moveY：raw ∈ [-450,450]（画布中心 = 0，样本里 moveY 中位数就是 0）→ 屏高比例 0.5 + raw/900。
        // rpe.rs:684 的 2/RPE_HEIGHT 在 Phira 里还要过一道 object.rs:55 的 `tr.y /= aspect`，
        // 换算回来正好是 0.5 + raw/900。
        List<List<Chart.Ev>> moveY = track(layers, "moveYEvents", beats, 1.0 / RPE_HEIGHT, 0.5);
        // 旋转：rpe.rs:682 乘 -1（方向相反）；我们的字段本身就是「度」
        List<List<Chart.Ev>> rotate = track(layers, "rotateEvents", beats, -1.0, 0.0);
        // 透明度：0..255（有的写 256）→ 0..1（rpe.rs:680）
        List<List<Chart.Ev>> alpha = track(layers, "alphaEvents", beats, 1.0 / 255.0, 0.0);
        // rpe.rs:665-667（events_with_factor）：一条 alpha 事件都没有 → AnimFloat::fixed(0.0)，
        // 也就是这条线**永远不可见**。以前这里留空、alphaAt 回退到 1.0，于是所有没写 alpha 的线
        // 都变成一条恒亮、横贯屏幕的判定线（用户报的「神秘的横向线」主要就是它）。
        if (alpha.isEmpty()) {
            alpha = List.of(List.of(new Chart.Ev(0.0, 1e9, 0.0, 0.0, 0.0, 0.0, 0.0)));
        }

        // rpe.rs:176-178 / 821：父线、是否叠加父线旋转、判定线下面要不要画
        int parent = intOf(line, "father", -1);
        boolean rotateWithFather = boolOf(line, "rotateWithFather", false);
        boolean showBelow = intOf(line, "isCover", 1) != 1;

        // ---- 线的「画不画 / 多长 / 多粗」----------------------------------------
        // rpe.rs:740-806：只有 Texture == "line.png" 才是普通判定线；attachUI 的线被
        // chart.rs:33-38 从判定线列表里摘掉，改成 UI 元素（我们不做 UI 层 → 不画）。
        String texture = strOf(line, "Texture", "line.png");
        boolean isLinePng = texture.isEmpty() || texture.equals("line.png");
        int attachUi = attachUiOf(line);
        JsonObject ext = objOf(line, "extended");
        boolean hasTextEv = ext != null && arrOf(ext, "textEvents") != null && !arrOf(ext, "textEvents").isEmpty();
        boolean hasPaintEv = ext != null && arrOf(ext, "paintEvents") != null && !arrOf(ext, "paintEvents").isEmpty();
        // rpe.rs:695-724 的 factor / default：图片线的 scale 单位是「像素 → 世界单位」
        double scaleFactor = isLinePng ? 1.0 : 2.0 / RPE_WIDTH;
        // rpe.rs:704-717：line.png 且「没有 textEvents」且「没有 attachUI」时，scaleX **再乘 0.5**。
        // 注意源码的条件里**没有** paintEvents（paint 线也吃这个 0.5），别自己加条件。
        double sxFactor = scaleFactor
                * (isLinePng && !hasTextEv && attachUi == 0 ? 0.5 : 1.0);
        // rpe.rs:697-724：scale 的默认值 = line.png ? 1 : factor（`parse()` 的 default 参数）
        double scaleDefault = isLinePng ? 1.0 : scaleFactor;
        List<Chart.Ev> scaleX = extTrack(ext, "scaleXEvents", beats, sxFactor, scaleDefault, true);
        List<Chart.Ev> scaleY = extTrack(ext, "scaleYEvents", beats, scaleFactor, scaleDefault, true);
        // rpe.rs:734-738：incline，默认 0（`parse_events(r, events, Some(0.), ...)`）
        List<Chart.Ev> incline = extTrack(ext, "inclineEvents", beats, 1.0, 0.0, true);

        // rpe.rs:727-731 的 CtrlObject：alpha / size / pos / y 四条「按高度求值」的轨道
        List<Chart.Ev> ctrlAlpha = ctrlTrack(arrOf(line, "alphaControl"), "alpha");
        List<Chart.Ev> ctrlSize = ctrlTrack(arrOf(line, "sizeControl"), "size");
        List<Chart.Ev> ctrlPos = ctrlTrack(arrOf(line, "posControl"), "pos");
        List<Chart.Ev> ctrlY = ctrlTrack(arrOf(line, "yControl"), "y");

        // ---- rpe.rs:740-810 的 JudgeLineKind / color / z_index ----
        // kind 决定 Phira 到底画什么：line.png + 无 text/paint = 一条判定线；有 textEvents 画文字；
        // 有 paintEvents 画全屏绘制；其它贴图（含 gif）画图本身。后三种我们都不画成横条。
        boolean hasGifEv = ext != null && arrOf(ext, "gifEvents") != null && !arrOf(ext, "gifEvents").isEmpty();
        int kind = !isLinePng || hasGifEv ? Chart.Line.KIND_TEXTURE
                : hasPaintEv ? Chart.Line.KIND_PAINT
                : hasTextEv ? Chart.Line.KIND_TEXT
                : Chart.Line.KIND_NORMAL;
        // rpe.rs:820 z_order：只影响绘制分层（chart.rs:56），判定不读
        int zIndex = intOf(line, "zOrder", 0);
        // rpe.rs:806-810 extended.colorEvents：三个通道共用同一个事件数组（RGBColor → rgba(r,g,b,255)）
        List<Chart.Ev> colorR = List.of();
        List<Chart.Ev> colorG = List.of();
        List<Chart.Ev> colorB = List.of();
        if (ext != null && arrOf(ext, "colorEvents") != null && !arrOf(ext, "colorEvents").isEmpty()) {
            JsonArray ce = arrOf(ext, "colorEvents");
            colorR = colorChannel(ce, beats, 0);
            colorG = colorChannel(ce, beats, 1);
            colorB = colorChannel(ce, beats, 2);
        }

        // rpe.rs:746-748 extended.textEvents：JudgeLineKind::Text，值是**字符串**（打字机效果）
        List<Chart.TextEv> text = textTrack(ext == null ? null : arrOf(ext, "textEvents"), beats);

        List<Chart.Note> notes = new ArrayList<>();
        JsonArray arr = arrOf(line, "notes");
        if (arr != null) readNotes(arr, beats, speed, notes);

        return new Chart.Line(index, baseBpm, notes, speed, moveX, moveY, rotate, alpha,
                parent, rotateWithFather, showBelow, ctrlAlpha, ctrlSize, ctrlPos, ctrlY,
                attachUi, !isLinePng, scaleX, scaleY, zIndex, kind, colorR, colorG, colorB,
                isLinePng ? "" : texture, text, incline);
    }

    /**
     * {@code extended.textEvents} → {@link Chart.TextEv} 序列。
     *
     * <p>和 {@link #segments} 完全同一套模型（{@code rpe.rs:306-330} 的 {@code parse_events}）：
     * 按 {@code startTime} 排序 → 空档保持上一段终值 → 段内挂缓动。
     * <b>唯一的区别是值域</b>：这里是字符串，段内插值走 {@link Chart#tweenText}
     * （{@code tween.rs:582-631}，即打字机效果）。
     *
     * <p>起点文本取「上一条事件的终点文本」（源码 {@code kfs.last().value}）。
     * 第一条事件之前用它的起点文本（源码在 {@code time < kf[0].time} 时会按 String 的 tween
     * 反向外推，结果是空串或前几个字；我们取起点文本，更不容易闪）。
     */
    private static List<Chart.TextEv> textTrack(JsonArray events, Beats beats) {
        if (events == null || events.isEmpty()) return List.of();
        List<JsonObject> sorted = sortedByStart(events, beats);
        List<Chart.TextEv> out = new ArrayList<>();
        double cursor = Double.NaN;
        String last = "";
        boolean first = true;
        for (JsonObject e : sorted) {
            double t0 = beats.toSeconds(beatsOf(e.get("startTime")));
            double t1 = beats.toSeconds(beatsOf(e.get("endTime")));
            if (t1 < t0) t1 = t0;
            String v0 = strOrEmpty(e.get("start"));
            String v1 = strOrEmpty(e.get("end"));
            double from = Double.isNaN(cursor) ? t0 : Math.max(t0, cursor);
            if (!Double.isNaN(cursor) && from > cursor + EPS) {
                // 空档：保持上一段的文本（和数值轨道一样）
                out.add(new Chart.TextEv(cursor, from, last, last, null));
            }
            if (t1 > from + EPS) {
                out.add(new Chart.TextEv(from, t1, first ? v0 : last, v1, tweenOf(e)));
            }
            first = false;
            cursor = Double.isNaN(cursor) ? t1 : Math.max(cursor, t1);
            last = v1;
        }
        if (out.isEmpty() && !sorted.isEmpty()) {
            // 全是零长事件（start == end）：直接给一个常量文本
            String v = strOrEmpty(sorted.get(sorted.size() - 1).get("end"));
            if (!v.isEmpty()) out.add(new Chart.TextEv(0.0, 1e9, v, v, null));
        }
        return out;
    }

    /** JSON 里取字符串；缺字段 / 不是字符串 → 空串。 */
    private static String strOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        try {
            return el.getAsString();
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * {@code extended.colorEvents} 的某一个通道（{@code rpe.rs:806-810} +
     * {@code rpe.rs:122-128} 的 {@code RGBColor → Color::from_rgba(r,g,b,255)}）。
     *
     * <p>JSON 里 {@code start}/{@code end} 是三元组 {@code [r,g,b]}（0..255），
     * 所以三个通道各建一条轨道，缓动完全相同（{@code Color} 的 {@code Tweenable} 逐通道插值）。
     * 值除 255 进 0..1，和 {@link Chart.Line#colorAt} 的口径一致。
     */
    private static List<Chart.Ev> colorChannel(JsonArray events, Beats beats, int channel) {
        List<JsonObject> sorted = sortedByStart(events, beats);
        List<Chart.Ev> out = new ArrayList<>();
        double cursor = Double.NaN;
        double last = 0.0;
        for (JsonObject e : sorted) {
            double t0 = beats.toSeconds(beatsOf(e.get("startTime")));
            double t1 = beats.toSeconds(beatsOf(e.get("endTime")));
            if (t1 < t0) t1 = t0;
            double v0 = channelOf(e.get("start"), channel) / 255.0;
            double v1 = channelOf(e.get("end"), channel) / 255.0;
            double from = Double.isNaN(cursor) ? t0 : Math.max(t0, cursor);
            if (!Double.isNaN(cursor) && from > cursor + EPS) {
                out.add(new Chart.Ev(cursor, from, last, last, 0, 0, 0));
            }
            if (t1 > from + EPS) {
                out.add(new Chart.Ev(from, t1, v0, v1, 0, 0, 0, tweenOf(e)));
            }
            cursor = Double.isNaN(cursor) ? t1 : Math.max(cursor, t1);
            last = v1;
        }
        return tail(out, cursor, last);
    }

    /** {@code [r,g,b]} 里取第 {@code i} 个分量；缺字段 / 不是数组时按 255（白）。 */
    private static double channelOf(JsonElement el, int i) {
        if (el == null || !el.isJsonArray()) return 255.0;
        JsonArray a = el.getAsJsonArray();
        if (i >= a.size()) return 255.0;
        try {
            return a.get(i).getAsDouble();
        } catch (Exception ex) {
            return 255.0;
        }
    }

    /**
     * {@code extended.scaleXEvents / scaleYEvents / inclineEvents}：单个数组（不是按事件层），
     * {@code rpe.rs:687-694} 的 {@code parse()} + {@code parse_events} 的 {@code default} 参数。
     *
     * <p>有事件 → 每个值乘 {@code factor}（{@code rpe.rs:692} 的 {@code map_value(|v| v * factor)}）。
     *
     * <p><b>{@code default} 的语义</b>（{@code rpe.rs:316-320}）：只有传了 {@code Some(default)} 的
     * 轨道（{@code scaleX/scaleY/incline/paint/text}）才会在「第一条事件不在第 0 拍」时补一个
     * {@code (0, default)} 的<b>保持</b>关键帧（tween 0）。也就是说第一条事件之前取的是默认值，
     * 而不是拿第一条事件往外插值。以前没补，{@code sec < 第一条事件时刻} 时会被
     * {@link Chart.Ev#valueAt} 的「首段外推」算出一个不同的值。
     */
    private static List<Chart.Ev> extTrack(JsonObject ext, String key, Beats beats, double factor,
                                           double def, boolean hasDefault) {
        if (ext == null) return List.of();
        JsonArray arr = arrOf(ext, key);
        if (arr == null || arr.isEmpty()) return List.of();
        List<Chart.Ev> segs = segments(arr, beats);
        if (segs.isEmpty()) return List.of();
        List<Chart.Ev> out = new ArrayList<>(segs.size() + 1);
        if (hasDefault && !segs.isEmpty() && segs.get(0).t0 > 1e-9) {
            out.add(new Chart.Ev(0.0, segs.get(0).t0, def, def, 0, 0, 0));
        }
        out.addAll(toEvents(segs, factor, 0.0));
        return out;
    }

    // ==================================================================== 音符

    /**
     * 音符。类型编号和官方 PGR <b>不一样</b>：{@code 1=Tap, 2=Hold, 3=Flick, 4=Drag}
     * （{@code rpe.rs:539-551}），别套 {@link ChartParser} 里那套。
     *
     * <p>可视字段全部照 {@code rpe.rs:576-605}：
     * <ul>
     *   <li>{@code alpha}（0..255，&gt;=255 视为 1）与 {@code visibleTime}
     *       → {@link Chart.Note#alpha}/{@link Chart.Note#visibleFrom}；</li>
     *   <li>{@code size} → {@link Chart.Note#size}；{@code judgeArea} → {@link Chart.Note#judgeArea}；</li>
     *   <li>{@code yOffset} 是「垂直于判定线的额外偏移」，prpr 把它放在
     *       {@code translation.y = yOffset * 2 / RPE_HEIGHT * speed}（{@code rpe.rs:538}）。
     *       我们这边 {@code Note.height} 与 {@code speed} 同源，所以把它烘进高度：
     *       {@code height += 2 * yOffset / RPE_HEIGHT} —— 之后 {@code Δheight * speed / aspect}
     *       与 prpr 的 {@code translation.y/aspect + base} 完全一致。</li>
     * </ul>
     */
    private static void readNotes(JsonArray arr, Beats beats, List<Chart.Ev> speed,
                                  List<Chart.Note> out) {
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject n = el.getAsJsonObject();
            Chart.Note.Type type = switch (intOf(n, "type", 1)) {
                case 1 -> Chart.Note.Type.TAP;
                case 2 -> Chart.Note.Type.HOLD;
                case 3 -> Chart.Note.Type.FLICK;
                case 4 -> Chart.Note.Type.DRAG;
                default -> null;                       // rpe.rs:551 对未知类型直接报错，这里跳过
            };
            if (type == null) continue;

            double time = beats.toSeconds(beatsOf(n.get("startTime")));
            double end = type == Chart.Note.Type.HOLD
                    ? beats.toSeconds(beatsOf(n.get("endTime"))) : time;
            double hold = Math.max(0.0, end - time);
            // positionX ∈ [-675, 675] → 我们的 x 单位（0.05625 屏宽 = 0.1125 世界单位）
            double x = dblOf(n, "positionX", 0.0) / (HALF_WIDTH * Engine.X_WORLD_PER_UNIT);
            double noteSpeed = dblOf(n, "speed", 1.0);
            // yOffset：额外垂直偏移，烘进高度（见方法注释）
            double yOffset = dblOf(n, "yOffset", 0.0) * 2.0 / RPE_HEIGHT;
            // 高度 = 判定线高度曲线在 note.time 处的值（rpe.rs:536-537 `height.set_time(time)`）
            double height = Chart.heightAt(speed, time) + yOffset;

            // rpe.rs:578-587：alpha 与 visibleTime
            double alphaRaw = dblOf(n, "alpha", 255.0);
            double alpha = alphaRaw >= 255.0 ? 1.0 : Math.max(0.0, alphaRaw) / 255.0;
            double visibleTime = dblOf(n, "visibleTime", 0.0);
            // 源码是 `visible_time >= time`（拿时长和绝对时间比，prpr 原样如此）：
            // 成立 → 静态透明度；不成立 → (time - visible_time) 之前全透明，之后跳到 alpha
            double visibleFrom = visibleTime >= time
                    ? Double.NEGATIVE_INFINITY : time - visibleTime;

            double size = dblOf(n, "size", 1.0);
            // rpe.rs:604：judgeArea 直接除在横向判定距离上（judge.rs:581-586），缺省 1
            double judgeArea = dblOf(n, "judgeArea", 1.0);

            boolean above = intOf(n, "above", 1) == 1;  // rpe.rs:601 `above == 1`
            boolean fake = intOf(n, "isFake", 0) != 0;  // rpe.rs:603
            // rpe.rs:597 `color: note.tint.map_or(WHITE, |[r,g,b]| Color::from_rgba(r,g,b,255))`
            // 缺 tint 就是白（这也是全部 4 个 pez 实测的样子）；有 tint 就原样乘在贴图上。
            double[] tint = tintOf(n);
            // 最后一个 false = 不做 speed<=0 的兜底：RPE 里 speed=0 是「音符贴在判定线上」
            // （样本里 Cyberneko 有 826 个），改掉它会把这批音符甩到屏幕外。
            out.add(new Chart.Note(type, time, hold, x, noteSpeed, height, above, fake,
                    false, alpha, visibleFrom, size, judgeArea, tint[0], tint[1], tint[2]));
        }
        out.sort((a, b) -> Double.compare(a.time, b.time));
    }

    /**
     * 音符自带颜色 {@code tint: [r,g,b]}（0..255，缺省 = 白）。
     *
     * <p>照 {@code rpe.rs:597}：{@code Color::from_rgba(r, g, b, 255)}，alpha 恒 255，
     * 所以这里只取三元组并归一化到 0~1；值缺失 / 不是数组 / 长度不足都当白。
     */
    private static double[] tintOf(JsonObject n) {
        JsonElement el = n.get("tint");
        if (el == null || !el.isJsonArray()) return new double[]{1.0, 1.0, 1.0};
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 3) return new double[]{1.0, 1.0, 1.0};
        double r = a.get(0).getAsDouble() / 255.0;
        double g = a.get(1).getAsDouble() / 255.0;
        double b = a.get(2).getAsDouble() / 255.0;
        return new double[]{
                Math.max(0.0, Math.min(1.0, r)),
                Math.max(0.0, Math.min(1.0, g)),
                Math.max(0.0, Math.min(1.0, b)),
        };
    }

    // ==================================================================== 事件

    /**
     * 位置 / 旋转 / 透明度轨道：<b>每条事件层一条</b>，求值时相加
     * （{@code anim.rs:27-33}；见 {@link Chart.Line#layerValue}）。
     *
     * <p>为什么不在解析时就把层合并成一条：两条带缓动的曲线之和不是任何单一缓动，
     * 合并必然要折线化（有误差）。而样本里每条线都有 2 层在动、推活的 moveX 有 90% 是缓动，
     * 所以改成「保留分层、求值求和」——与 prpr 的 {@code Anim::next} 链完全等价，且没有误差。
     *
     * <p>{@code base}（{@code moveX}/{@code moveY} 的 0.5 中心偏移）只加在第一条非空轨道上，
     * 否则有几层就加几次。
     */
    private static List<List<Chart.Ev>> track(JsonArray layers, String key, Beats beats,
                                              double scale, double base) {
        List<List<Chart.Ev>> out = new ArrayList<>();
        for (JsonArray events : layerEvents(layers, key)) {
            List<Chart.Ev> segs = segments(events, beats);
            if (!segs.isEmpty()) out.add(toEvents(segs, scale, 0.0));
        }
        if (!out.isEmpty() && base != 0.0) out.set(0, toEvents(out.get(0), 1.0, base));
        return out;
    }

    /**
     * 速度轨道。返回值的单位是「PGR speedEvent 的值」：{@code RPE速度 * 10/45}，
     * 这样 {@link Chart#heightAt} 里的 {@code /HEIGHT_RATIO} 一除，正好等于
     * {@code rpe.rs:24} 的 {@code SPEED_RATIO} 口径。
     */
    private static List<Chart.Ev> speedTrack(JsonArray layers, Beats beats, boolean modern) {
        List<List<Chart.Ev>> perLayer = new ArrayList<>();
        for (JsonArray events : layerEvents(layers, "speedEvents")) {
            List<Chart.Ev> segs = speedSegments(events, beats, modern);
            if (!segs.isEmpty()) perLayer.add(segs);
        }
        return toEvents(merge(perLayer), 1.0, 0.0);
    }

    /**
     * 速度事件的离散化。
     *
     * <p>两条源码分支，逐行核对过：
     * <ul>
     *   <li><b>170+</b>（{@code rpe.rs:332-413} {@code parse_speed_events}）：段内速度
     *       <b>按缓动曲线走</b>（{@code rpe.rs:392-395} 用 {@code SpeedIntegralTween}，
     *       其效果就是 {@code speed(t) = start + (end-start) * ease(t)}）。</li>
     *   <li><b>&lt;170</b>（{@code rpe.rs:415-490} {@code parse_speed_events_legacy}）：
     *       速度关键帧固定用 {@code StaticTween::get_rc(2)}（线性，{@code rpe.rs:429-430}），
     *       也就是<b>段内线性、完全忽略 easingType</b>，然后按梯形法积分
     *       （{@code rpe.rs:483}）。所以老版本只是「不做缓动」，不是别的语义。</li>
     * </ul>
     *
     * <p>两边都只需要「各采样点的速度」，区别只在采样函数；段内再按
     * 段内按 {@link #SPEED_PIECE_SECONDS} 切块、每块取梯形平均，块边界的累计高度与 prpr 一致。
     */
    private static List<Chart.Ev> speedSegments(JsonArray events, Beats beats, boolean modern) {
        List<JsonObject> sorted = sortedByStart(events, beats);
        List<Chart.Ev> out = new ArrayList<>();
        double cursor = Double.NaN;
        double last = 0.0;
        for (JsonObject e : sorted) {
            double t0 = beats.toSeconds(beatsOf(e.get("startTime")));
            double t1 = beats.toSeconds(beatsOf(e.get("endTime")));
            if (t1 < t0) t1 = t0;
            // rpe.rs:341-344：s0/s1 都乘 SPEED_RATIO
            double s0 = dblOf(e, "start", 0.0) * SPEED_RATIO;
            double s1 = dblOf(e, "end", 0.0) * SPEED_RATIO;
            // 老语义：段内线性（easingType 不参与）；新语义：按缓动曲线
            Chart.Tween curve = modern ? tweenOf(e) : null;

            double from = Double.isNaN(cursor) ? t0 : Math.max(t0, cursor);
            if (!Double.isNaN(cursor) && from > cursor + EPS) {
                out.add(new Chart.Ev(cursor, from, last, last, 0, 0, 0));   // 空档：保持上一段速度
            }
            if (t1 > from + EPS) {
                double span = t1 - from;
                // 线性段一段就够（积分精确）；有缓动就切块，块边界上累计高度精确
                int pieces = curve == null ? 1
                        : Math.max(8, Math.min(64, (int) Math.ceil(span / SPEED_PIECE_SECONDS)));
                for (int p = 0; p < pieces; p++) {
                    double xa = (double) p / pieces;
                    double xb = (double) (p + 1) / pieces;
                    double va = s0 + (s1 - s0) * ease(curve, xa);
                    double vb = s0 + (s1 - s0) * ease(curve, xb);
                    // 这一段用「平均速度」当常量，高度照样按 ∫v dt 累积
                    double v = (va + vb) / 2.0;
                    out.add(new Chart.Ev(from + span * xa, from + span * xb, v, v, 0, 0, 0));
                }
            }
            cursor = Double.isNaN(cursor) ? t1 : Math.max(cursor, t1);
            last = s1;
        }
        return tail(out, cursor, last);
    }

    /**
     * 把事件数组变成「连续、有序、不重叠」的段（值原样，缓动挂在 Ev 上）。
     *
     * <p>{@code cursor} 只前进不后退：RPE 里存在 {@code startTime > endTime} 的负时长事件
     * （样本里就有），prpr 那边它们退化成「在 max(start,end) 处跳到 end 值」
     * （{@code anim.rs:113-121} 的 cursor 语义），我们按 {@code t1 = t0} 处理后
     * 再让 cursor 保持单调，结果一致。
     */
    private static List<Chart.Ev> segments(JsonArray events, Beats beats) {
        List<JsonObject> sorted = sortedByStart(events, beats);
        List<Chart.Ev> out = new ArrayList<>();
        double cursor = Double.NaN;
        double last = 0.0;
        for (JsonObject e : sorted) {
            double t0 = beats.toSeconds(beatsOf(e.get("startTime")));
            double t1 = beats.toSeconds(beatsOf(e.get("endTime")));
            if (t1 < t0) t1 = t0;
            double v0 = dblOf(e, "start", 0.0);
            double v1 = dblOf(e, "end", 0.0);

            double from = Double.isNaN(cursor) ? t0 : Math.max(t0, cursor);
            if (!Double.isNaN(cursor) && from > cursor + EPS) {
                // rpe.rs:327 的 end 关键帧用 tween 0（Hold）：空档里保持上一个值
                out.add(new Chart.Ev(cursor, from, last, last, 0, 0, 0));
            }
            if (t1 > from + EPS) {
                out.add(new Chart.Ev(from, t1, v0, v1, 0, 0, 0, tweenOf(e)));
            }
            cursor = Double.isNaN(cursor) ? t1 : Math.max(cursor, t1);
            last = v1;
        }
        return tail(out, cursor, last);
    }

    /**
     * 补一条尾巴。原因：RPE 的「哨兵事件」（{@code start == end}，例如
     * {@code startTime == endTime == [22210,0,1]}、值 22221）在 prpr 里是最后一批关键帧，
     * 之后的取值就一直停在那个值上（{@code anim.rs:113-115} 的
     * {@code cursor == keyframes.len()-1} 分支）。我们这边零时长事件不产生段，
     * 所以要显式把「末尾值」延长出去，否则最后一条线会停在旧位置、留在屏幕上。
     */
    private static List<Chart.Ev> tail(List<Chart.Ev> out, double cursor, double last) {
        if (Double.isNaN(cursor)) return out;
        if (!out.isEmpty() && out.get(out.size() - 1).v1 == last
                && out.get(out.size() - 1).t1 == cursor) {
            return out;
        }
        out.add(new Chart.Ev(cursor, cursor + 1e6, last, last, 0, 0, 0));
        return out;
    }

    /** 按 {@code startTime} 的拍号排序（{@code rpe.rs:343}）。 */
    private static List<JsonObject> sortedByStart(JsonArray events, Beats beats) {
        List<JsonObject> out = new ArrayList<>();
        if (events == null) return out;
        for (JsonElement el : events) {
            if (el != null && el.isJsonObject()) out.add(el.getAsJsonObject());
        }
        out.sort((a, b) -> Double.compare(beats.toSeconds(beatsOf(a.get("startTime"))),
                beats.toSeconds(beatsOf(b.get("startTime")))));
        return out;
    }

    /** 取 {@code eventLayers[i].<key>}，只保留非空数组（{@code eventLayers} 里可能有 null）。 */
    private static List<JsonArray> layerEvents(JsonArray layers, String key) {
        List<JsonArray> out = new ArrayList<>();
        if (layers == null) return out;
        for (JsonElement el : layers) {
            if (el == null || !el.isJsonObject()) continue;
            JsonArray a = arrOf(el.getAsJsonObject(), key);
            if (a != null && a.size() > 0) out.add(a);
        }
        return out;
    }

    // ------------------------------------------------ 控制器（alpha/size/pos/y）

    /**
     * {@code rpe.rs:610-636} {@code parse_ctrl_events}：控制器事件的自变量是
     * {@code x}（不是时间，是「音符到线的高度」），上界常写成 9999999。
     *
     * <p>两个细节照抄源码：
     * <ol>
     *   <li>「只有 2 个事件、第一条 {@code easing == 1} 且值 ≈ 1」视为恒 1，
     *       直接返回空表（{@code rpe.rs:612} 的短路）；三个样本的控制器全是这个形态，
     *       所以这里返回空表后 {@link Chart.Line#ctrlAt} 直接给 1，一分钱开销都没有。</li>
     *   <li>缓动是<b>错位</b>的：区间 {@code [x_i, x_{i+1}]} 用的是事件 <b>i+1</b> 的
     *       {@code easing}（{@code rpe.rs:619-624} 的 {@code skip(1).chain([0])}）。</li>
     * </ol>
     */
    private static List<Chart.Ev> ctrlTrack(JsonArray ctrl, String key) {
        if (ctrl == null || ctrl.isEmpty()) return List.of();
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement el : ctrl) {
            if (el != null && el.isJsonObject()) list.add(el.getAsJsonObject());
        }
        if (list.isEmpty()) return List.of();
        if (list.size() == 2 && intOf(list.get(0), "easing", 0) == 1
                && Math.abs(dblOf(list.get(0), key, 1.0) - 1.0) < 1e-4) {
            return List.of();                       // rpe.rs:612：等价于恒 1
        }
        list.sort(Comparator.comparingDouble(o -> dblOf(o, "x", 0.0)));
        List<Chart.Ev> out = new ArrayList<>();
        for (int i = 0; i + 1 < list.size(); i++) {
            double x0 = dblOf(list.get(i), "x", 0.0);
            double x1 = dblOf(list.get(i + 1), "x", 0.0);
            if (x1 - x0 <= EPS) continue;
            int id = tweenId(Math.max(1, intOf(list.get(i + 1), "easing", 0)));
            out.add(new Chart.Ev(x0, x1,
                    dblOf(list.get(i), key, 1.0), dblOf(list.get(i + 1), key, 1.0),
                    0, 0, 0, id == 2 ? null : x -> tween(id, x)));
        }
        if (out.isEmpty()) {
            // 只有一个事件（或全是退化段）：prpr 那边就是一个关键帧 ⇒ 常量
            double v = dblOf(list.get(0), key, 1.0);
            if (Math.abs(v - 1.0) > 1e-9) out.add(new Chart.Ev(-1e7, 1e7, v, v, 0, 0, 0));
        }
        return out;
    }

    // ------------------------------------------------ 多事件层求和

    /**
     * 多个事件层相加（{@code anim.rs:27-33}）。
     *
     * <p>每层都已经离散成「连续、有序、不重叠」的段，所以在<b>所有层断点的并集</b>上切成格子。
     * 每个格子里：
     * <ul>
     *   <li>如果只有 0 或 1 层带非线性缓动（其余是常量/线性），结果仍能用同一个缓动精确表达
     *       （常量平移不改变曲线形状）；</li>
     *   <li>如果 ≥2 层都带非线性缓动，两条曲线之和不再是单一缓动，这一段按
     *       {@link #SPLIT_PIECES} 折线化（三个样本里每首只有 1 条线出现这种情况）。</li>
     * </ul>
     */
    private static List<Chart.Ev> merge(List<List<Chart.Ev>> tracks) {
        if (tracks.isEmpty()) return List.of();
        if (tracks.size() == 1) return tracks.get(0);

        List<Double> points = new ArrayList<>();
        for (List<Chart.Ev> t : tracks) {
            for (Chart.Ev s : t) {
                points.add(s.t0);
                points.add(s.t1);
            }
        }
        points.sort(null);
        List<Double> uniq = new ArrayList<>(points.size());
        for (double p : points) {
            if (uniq.isEmpty() || p - uniq.get(uniq.size() - 1) > EPS) uniq.add(p);
        }

        List<Chart.Ev> out = new ArrayList<>(Math.max(0, uniq.size() - 1));
        for (int i = 0; i + 1 < uniq.size(); i++) {
            double a = uniq.get(i);
            double b = uniq.get(i + 1);
            if (b - a <= EPS) continue;
            mergeCell(tracks, a, b, out);
        }
        return out;
    }

    private static void mergeCell(List<List<Chart.Ev>> tracks, double a, double b, List<Chart.Ev> out) {
        double mid = (a + b) / 2.0;
        double va = 0.0;
        double vb = 0.0;
        Chart.Tween eased = null;
        int moving = 0;
        for (List<Chart.Ev> t : tracks) {
            va += Chart.Ev.valueAt(t, a, 0.0);
            vb += Chart.Ev.valueAt(t, b, 0.0);
            Chart.Ev seg = Chart.Ev.segmentAt(t, mid);
            if (seg != null && seg.v1 != seg.v0) {
                // 这一层在格子里是「在动」的：它的曲线形状决定了合并后的形状
                moving++;
                if (seg.tween != null) eased = seg.tween;
            }
        }
        if (moving == 0) {
            out.add(new Chart.Ev(a, b, va, vb, 0, 0, 0));          // 全常量
            return;
        }
        if (moving == 1) {
            // 只有一层在动：其余是常量，平移不改变曲线形状 ⇒ 可以精确表达
            out.add(new Chart.Ev(a, b, va, vb, 0, 0, 0, eased));
            return;
        }
        // ≥2 层同时在动：和不再是一条单一曲线，只能折线化
        for (int p = 0; p < SPLIT_PIECES; p++) {
            double xa = a + (b - a) * p / SPLIT_PIECES;
            double xb = a + (b - a) * (p + 1) / SPLIT_PIECES;
            out.add(new Chart.Ev(xa, xb, valueSum(tracks, xa), valueSum(tracks, xb), 0, 0, 0));
        }
    }

    private static double valueSum(List<List<Chart.Ev>> tracks, double t) {
        double v = 0.0;
        for (List<Chart.Ev> track : tracks) v += Chart.Ev.valueAt(track, t, 0.0);
        return v;
    }

    /** 段 → {@link Chart.Ev}，顺便做 {@code v * scale + base} 的映射（仿射变换保缓动形状）。 */
    private static List<Chart.Ev> toEvents(List<Chart.Ev> segs, double scale, double base) {
        List<Chart.Ev> out = new ArrayList<>(segs.size());
        for (Chart.Ev s : segs) {
            out.add(new Chart.Ev(s.t0, s.t1, s.v0 * scale + base, s.v1 * scale + base,
                    0, 0, 0, s.tween));
        }
        return out;
    }

    // ==================================================================== 缓动

    /**
     * 一条事件的缓动函数，对应 {@code rpe.rs:89-100} 的 {@code RPEEvent::tween}：
     * 贝塞尔优先 → 线性/无夹取时直接用 → 否则套 {@code ClampedTween}。
     *
     * @return {@code null} 表示线性（这样 {@link Chart.Ev#lerp} 走最便宜的路径）
     */
    private static Chart.Tween tweenOf(JsonObject e) {
        if (intOf(e, "bezier", 0) != 0) {
            // rpe.rs:93-94：bezier != 0 时完全用贝塞尔，忽略 easingType
            double[] p = bezierPointsOf(e);
            return bezier(p[0], p[1], p[2], p[3]);
        }
        int id = tweenId(intOf(e, "easingType", 1));
        double left = clamp01(dblOf(e, "easingLeft", 0.0));
        double right = clamp01(dblOf(e, "easingRight", 1.0));
        // rpe.rs:95-99
        if (id <= 2 || (left < EPS && Math.abs(right - 1.0) < EPS) || left >= right) {
            return id == 2 ? null : x -> tween(id, x);
        }
        double y0 = tween(id, left);
        double y1 = tween(id, right);
        double denom = y1 - y0;
        if (Math.abs(denom) < 1e-8) return null;
        return x -> (tween(id, left + (right - left) * x) - y0) / denom;
    }

    private static double ease(Chart.Tween tween, double x) {
        return tween == null ? x : tween.y(x);
    }

    /**
     * RPE 的 {@code easingType}（1..29）→ Phira 的 {@code TweenId}
     * （{@code parse.rs:75-94} 的 {@code RPE_TWEEN_MAP}，越界一律当线性）。
     *
     * <p><b>这张表是一位对一位的</b>：{@code RPE_TWEEN_MAP[1] = 2}（线性）、
     * {@code [2] = e(Sine,Out) = 4}、{@code [3] = e(Sine,In) = 3}、{@code [4] = e(Quad,Out) = 7} …
     * 也就是说 <b>RPE 的 {@code easingType = 2} 是 SineOut，不是线性</b>；
     * 整张表从 2 开始和「1=线性，2=SineOut，3=SineIn，4=QuadOut…」这个 RPE 原版枚举对齐
     * （{@code EasingCheck} 会照着从源码文本展开出来的表逐项断言）。
     */
    static int tweenId(int easingType) {
        return switch (Math.max(1, easingType)) {
            case 1 -> 2;      // Linear
            case 2 -> 4;      // Sine Out
            case 3 -> 3;      // Sine In
            case 4 -> 7;      // Quad Out
            case 5 -> 6;      // Quad In
            case 6 -> 5;      // Sine InOut
            case 7 -> 8;      // Quad InOut
            case 8 -> 10;     // Cubic Out
            case 9 -> 9;      // Cubic In
            case 10 -> 13;    // Quart Out
            case 11 -> 12;    // Quart In
            case 12 -> 11;    // Cubic InOut
            case 13 -> 14;    // Quart InOut
            case 14 -> 16;    // Quint Out
            case 15 -> 15;    // Quint In
            case 16 -> 19;    // Expo Out
            case 17 -> 18;    // Expo In
            case 18 -> 22;    // Circ Out
            case 19 -> 21;    // Circ In
            case 20 -> 25;    // Back Out
            case 21 -> 24;    // Back In
            case 22 -> 23;    // Circ InOut
            case 23 -> 26;    // Back InOut
            case 24 -> 28;    // Elastic Out
            case 25 -> 27;    // Elastic In
            case 26 -> 31;    // Bounce Out
            case 27 -> 30;    // Bounce In
            case 28 -> 32;    // Bounce InOut
            case 29 -> 29;    // Elastic InOut
            default -> 2;     // 越界（rpe.rs:90 的 unwrap_or(RPE_TWEEN_MAP[0]) = 线性）
        };
    }

    /** {@code RPE_TWEEN_MAP} 的 30 个 TweenId，下标 = RPE 的 {@code easingType}（0 位与 1 位都是线性）。 */
    static int[] easingTable() {
        int[] t = new int[30];
        for (int k = 0; k < t.length; k++) t[k] = tweenId(k);
        return t;
    }

    /**
     * {@code tween.rs:99-113} 的 {@code TWEEN_FUNCTIONS[33]}：
     * 0/1 = 常 0/常 1，2 = 线性，3..32 = (Sine,Quad,Cubic,Quart,Quint,Expo,Circ,Back,Elastic,Bounce)
     * × (In, Out, InOut) 依次排列；Out 由 {@code f2!} 定义成 {@code 1 - f(1-x)}，
     * InOut 由 {@code f3!} 定义。
     */
    static double tween(int id, double x) {
        if (id == 0) return 0.0;
        if (id == 1) return 1.0;
        if (id == 2) return x;
        if (id < 3 || id > 32) return x;
        int major = (id - 3) / 3;
        int minor = (id - 3) % 3;
        return switch (minor) {
            case 0 -> base(major, x);
            case 1 -> 1.0 - base(major, 1.0 - x);
            default -> x * 2.0 < 1.0
                    ? base(major, x * 2.0) / 2.0
                    : 1.0 - base(major, 2.0 - x * 2.0) / 2.0;
        };
    }

    /** {@code tween.rs:34-97} 的 10 个基函数（都是 In 形态）。 */
    private static double base(int major, double x) {
        switch (major) {
            case 0:   // sine
                return 1.0 - Math.cos(x * Math.PI / 2.0);
            case 1:   // quad
                return x * x;
            case 2:   // cubic
                return x * x * x;
            case 3:   // quart
                return x * x * x * x;
            case 4:   // quint
                return x * x * x * x * x;
            case 5:   // expo
                return Math.pow(2.0, 10.0 * (x - 1.0));
            case 6:   // circ
                return 1.0 - Math.sqrt(Math.max(0.0, 1.0 - x * x));
            case 7: { // back
                final double c1 = 1.70158;
                final double c3 = c1 + 1.0;
                return (c3 * x - c1) * x * x;
            }
            case 8: { // elastic
                final double c4 = 2.0 * Math.PI / 3.0;
                return -Math.pow(2.0, 10.0 * x - 10.0) * Math.sin((x * 10.0 - 10.75) * c4);
            }
            default: { // bounce
                final double n1 = 7.5625;
                final double d1 = 2.75;
                double u = 1.0 - x;
                double v;
                if (u < 1.0 / d1) {
                    v = n1 * u * u;
                } else if (u < 2.0 / d1) {
                    double t = u - 1.5 / d1;
                    v = n1 * t * t + 0.75;
                } else if (u < 2.5 / d1) {
                    double t = u - 2.25 / d1;
                    v = n1 * t * t + 0.9375;
                } else {
                    double t = u - 2.625 / d1;
                    v = n1 * t * t + 0.984375;
                }
                return 1.0 - v;
            }
        }
    }

    /**
     * 三次贝塞尔缓动（{@code tween.rs:417-511}，等价 CSS {@code cubic-bezier}）：
     * 先在 x 上解出参数 {@code t}，再取 y。
     */
    private static Chart.Tween bezier(double x1, double y1, double x2, double y2) {
        return x -> {
            if (x <= 0) return 0.0;
            if (x >= 1) return 1.0;
            double t = solveT(x1, x2, x);
            return sample(y1, y2, t);
        };
    }

    private static double sample(double a1, double a2, double t) {
        double a = (a1 - a2) * 3.0 + 1.0;
        double b = a2 * 3.0 - a1 * 6.0;
        double c = a1 * 3.0;
        return ((a * t + b) * t + c) * t;
    }

    private static double slope(double a1, double a2, double t) {
        double a = (a1 - a2) * 3.0 + 1.0;
        double b = a2 * 3.0 - a1 * 6.0;
        double c = a1 * 3.0;
        return (a * 3.0 * t + b * 2.0) * t + c;
    }

    private static double solveT(double x1, double x2, double x) {
        // 牛顿迭代 4 次；残差大就二分（tween.rs:460-502）
        double t = x;
        for (int i = 0; i < 4; i++) {
            double d = slope(x1, x2, t);
            if (d <= 1e-7) break;
            t -= (sample(x1, x2, t) - x) / d;
            t = Math.max(0.0, Math.min(1.0, t));
        }
        if (Math.abs(sample(x1, x2, t) - x) <= 1e-7) return t;
        double lo = 0.0;
        double hi = 1.0;
        double mid = 0.5;
        for (int i = 0; i < 30; i++) {
            mid = (lo + hi) / 2.0;
            double diff = sample(x1, x2, mid) - x;
            if (Math.abs(diff) <= 1e-7) break;
            if (diff > 0) hi = mid;
            else lo = mid;
        }
        return mid;
    }

    private static double[] bezierPointsOf(JsonObject e) {
        JsonArray a = arrOf(e, "bezierPoints");
        if (a == null || a.size() < 4) return new double[]{0, 0, 0, 0};
        return new double[]{numAt(a, 0), numAt(a, 1), numAt(a, 2), numAt(a, 3)};
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ==================================================================== 时间轴

    /**
     * {@code BPMList} → {@code beats → 秒}（{@code core.rs:112-150}）。
     *
     * <p>语义：第 i 段从 {@code beats[i]} 起、按 {@code bpm[i]} 走，段内
     * {@code 秒 = 该段起点秒 + (beats - 段起点拍) * 60 / bpm}。和官方 PGR 的
     * 「每个判定线自带 bpm」完全不同，所以必须单独折。
     */
    static final class Beats {
        private final double[] beats;
        private final double[] times;
        private final double[] bpms;

        Beats(JsonObject root) {
            this(arrOf(root, "BPMList"));
        }

        Beats(JsonArray list) {
            List<double[]> ranges = new ArrayList<>();
            if (list != null) {
                for (JsonElement el : list) {
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    double beat = beatsOf(o.get("startTime"));
                    double bpm = dblOf(o, "bpm", 120.0);
                    if (!Double.isFinite(beat) || !Double.isFinite(bpm) || bpm <= 0) continue;
                    ranges.add(new double[]{beat, bpm});
                }
            }
            if (ranges.isEmpty()) ranges.add(new double[]{0, 120.0});
            ranges.sort((a, b) -> Double.compare(a[0], b[0]));

            // 去重（同一拍出现两次时保留第一个，避免段长 0 造成除零）
            List<double[]> uniq = new ArrayList<>(ranges.size());
            for (double[] r : ranges) {
                if (!uniq.isEmpty() && Math.abs(r[0] - uniq.get(uniq.size() - 1)[0]) < 1e-9) continue;
                uniq.add(r);
            }
            int n = uniq.size();
            beats = new double[n];
            times = new double[n];
            bpms = new double[n];
            double time = 0.0;
            for (int i = 0; i < n; i++) {
                beats[i] = uniq.get(i)[0];
                bpms[i] = uniq.get(i)[1];
                if (i > 0) time += (beats[i] - beats[i - 1]) * 60.0 / bpms[i - 1];
                times[i] = time;
            }
        }

        /** 第一个 BPM（界面显示 / {@link Chart.Line#bpm} 用）。 */
        double baseBpm() {
            return bpms[0];
        }

        /** BPM 段数（诊断用）。 */
        int size() {
            return beats.length;
        }

        double toSeconds(double beat) {
            if (!Double.isFinite(beat)) return 0.0;
            int lo = 0;
            int hi = beats.length - 1;
            int idx = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (beats[mid] <= beat) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return times[idx] + (beat - beats[idx]) * 60.0 / bpms[idx];
        }
    }

    /** {@code [i, n, d]} → {@code i + n/d}（{@code core.rs:97-101}）。缺字段当 0。 */
    static double beatsOf(JsonElement el) {
        if (el == null || !el.isJsonArray()) return 0.0;
        JsonArray a = el.getAsJsonArray();
        double whole = numAt(a, 0);
        double num = numAt(a, 1);
        double den = numAt(a, 2);
        if (den == 0) den = 1;
        return whole + num / den;
    }

    // ==================================================================== 小工具

    private static JsonObject objOf(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arrOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static double numAt(JsonArray a, int i) {
        if (a == null || i >= a.size()) return 0.0;
        try {
            return a.get(i).getAsDouble();
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private static double dblOf(JsonObject o, String key, double def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            return def;
        }
    }

    private static int intOf(JsonObject o, String key, int def) {
        return (int) dblOf(o, key, def);
    }

    /**
     * {@code attachUI}：RPE 把它写成 <b>全小写字符串</b>，也可能写成数字或 null。
     *
     * <p>映射表就是 {@code core/line.rs:13-24} 的 {@code UIElement}：
     * <pre>
     * #[derive(Deserialize)] #[serde(rename_all = "lowercase")]
     * pub enum UIElement { Pause = 1, ComboNumber = 2, Combo = 3, Score = 4, Bar = 5, Name = 6, Level = 7 }
     * </pre>
     * 所以字符串是 {@code "pause" / "combonumber" / "combo" / "score" / "bar" / "name" / "level"}。
     * <b>返回值就是 {@code UIElement} 的数值</b>（0 = 没有 attachUI，不是 UI 元素）。
     *
     * <p>实测曲库：score×12、combonumber×12、combo×12、level×10、name×10、pause×9、bar×6
     * （7 种全都有）。以前一律返回 1（当成 pause），于是 {@link Chart#attachUiLines} 里
     * 所有元素都会指向最后一条 attachUI 线 —— 这次按源码逐种对上了。
     */
    private static int attachUiOf(JsonObject line) {
        if (line == null) return 0;
        JsonElement e = line.get("attachUI");
        if (e == null || e.isJsonNull()) return 0;
        if (e.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                int v = (int) p.getAsDouble();
                return (v >= 1 && v <= 7) ? v : (v == 0 ? 0 : 1);
            }
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
            if (p.isString()) {
                String s = p.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                return switch (s) {
                    case "", "null" -> 0;
                    case "pause" -> 1;
                    case "combonumber" -> 2;
                    case "combo" -> 3;
                    case "score" -> 4;
                    case "bar" -> 5;
                    case "name" -> 6;
                    case "level" -> 7;
                    // 源码是 serde 的封闭枚举：未知字符串会让整份谱面反序列化失败。
                    // 我们选择「当成 pause」继续跑（比整谱失败好），并保持「是 UI 元素」这个语义。
                    default -> 1;
                };
            }
        }
        return 1;
    }

    /** 字符串字段（{@code Texture} 这类）；缺省/不是字符串时用 {@code def}。 */
    private static String strOf(JsonObject o, String key, String def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return def;
        }
    }

    private static boolean boolOf(JsonObject o, String key, boolean def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return def;
        }
    }
}
