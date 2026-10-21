import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;

import java.util.List;
import java.util.Locale;

/**
 * Hold 渲染时间线体检：一条 {@code time=10s, hold=2s} 的 Hold，全程按住，
 * 在 t = 9.0 … 12.4 上逐点打印
 * <b>是否还在 {@link Engine#visible} 里 / 头的屏高偏移 / 身体长度 / alpha / 状态位</b>。
 *
 * <p>「身体长度」用 {@code PlayScreen} 里那段一模一样的公式算：
 * <pre>
 * tailFromLine = (floorPositionAt(time+hold) - floorPositionAt(t)) * 0.5 * h
 * dy0          = 头相对判定线的像素偏移（按住时 = 0）
 * tailLen      = max(0, tailFromLine - |dy0|)
 * </pre>
 * 这样能直接看出「身体是在尾判定之前就变 0，还是正好在 t=12s 归零」。
 *
 * <p>用法：{@code java -cp out/dev HoldProbe}
 */
public final class HoldProbe {

    /** BPM 120 ⇒ 1 拍 = 0.5s；beat 20 = 10s，beat 24 = 12s。 */
    private static final String JSON = """
            {
              "BPMList": [{"bpm": 120, "startTime": [0, 0, 1]}],
              "META": {"offset": 0, "RPEVersion": 160},
              "judgeLineList": [{
                "Name": "probe", "Texture": "line.png", "father": -1, "rotateWithFather": false,
                "eventLayers": [{"speedEvents": [
                  {"start": 100, "end": 100, "startTime": [0,0,1], "endTime": [0,0,1], "easingType": 1}
                ]}],
                "notes": [{
                  "type": 2, "above": 1,
                  "startTime": [20, 0, 1], "endTime": [24, 0, 1],
                  "positionX": 0, "yOffset": 0, "alpha": 255,
                  "size": 1, "speed": 1, "isFake": 0, "visibleTime": 9999
                }],
                "isCover": 1, "zOrder": 0
              }]
            }
            """;

    public static void main(String[] args) {
        Chart chart = ChartParser.parse(JSON);
        Chart.Line line = chart.lines.get(0);
        Chart.Note note = line.notes.get(0);
        System.out.printf(Locale.ROOT, "谱面：%s，note.time=%.2fs hold=%.2fs endTime=%.2fs，%d 条线%n",
                chart.format, note.time, note.hold, note.endTime(), chart.lines.size());

        Engine engine = new Engine(chart, new Judgement.Config());
        int w = 1920;
        int h = 1080;
        double aspect = (double) w / h;
        boolean pressed = false;

        System.out.println();
        System.out.printf(Locale.ROOT, "%-7s %-7s %-8s %-8s %-16s %-5s %-5s %s%n",
                "t", "在绘制里", "头本地Y", "身末本地Y", "视口范围", "旧规则", "新规则", "说明");
        for (int i = 0; i <= 34; i++) {
            double t = 9.0 + i * 0.1;
            engine.update(t);                                   // judge.rs:703-728 的维持逻辑
            if (!pressed && t >= note.time) {
                engine.keyDown(t);                              // 按下，之后一直不松
                pressed = true;
            }
            List<Engine.Visible> vis = engine.visible(t, aspect);
            Engine.Visible hit = null;
            for (Engine.Visible v : vis) {
                if (v.state().note == note) {
                    hit = v;
                    break;
                }
            }
            Engine.NoteState st = hit != null ? hit.state()
                    : (engine.activeHolds().isEmpty() ? null : engine.activeHolds().get(0));

            // ---- 和 PlayScreen 完全一致的几何 ----
            double nowH = line.floorPositionAt(t);
            double endH = line.floorPositionAt(note.time + note.hold);
            double dy0 = 0;
            boolean hold = true;
            if (hold) {
                if (t >= note.time) {
                    dy0 = 0;                                    // 按住时头钉在判定线上
                } else {
                    dy0 = -(note.height - nowH) * Chart.HEIGHT_TO_SCREEN * h;
                }
            }
            double tailFromLine = (endH - nowH) * Chart.HEIGHT_TO_SCREEN * h;
            double tailLen = Math.max(0.0, tailFromLine - Math.abs(dy0));

            // 视口剔除用的本地坐标（Engine.visible 的单位：世界单位，正 = 判定线上方）
            double floorNow = line.floorPositionAt(t);
            double headLocalY = (note.height - floorNow) / aspect;      // 本例 spd = cY = 1
            double tailLocalY = (endH - floorNow) / aspect;
            if (!note.above) {
                headLocalY = -headLocalY;
                tailLocalY = -tailLocalY;
            }
            double lo = Math.min(headLocalY, tailLocalY);
            double hi = Math.max(headLocalY, tailLocalY);
            // 视口本地范围（整屏 ±1 除以 aspect，和 Engine.viewportLocalBounds 同口径的保守近似）
            double boundMax = 1.0 / aspect;
            double boundMin = -1.0 / aspect;

            boolean detail = Math.abs(t - 10.0) < 0.01 || Math.abs(t - 10.5) < 0.01
                    || Math.abs(t - 11.0) < 0.01 || Math.abs(t - 11.5) < 0.01
                    || Math.abs(t - 12.0) < 0.01 || Math.abs(t - 12.2) < 0.01
                    || Math.abs(t - 9.5) < 0.01;
            if (!detail && i % 5 != 0) continue;
            // 旧规则 = 只判两个端点是否落在视口内；新规则 = 整段与视口区间相交
            boolean oldRule = headLocalY <= boundMax && headLocalY >= boundMin
                    || (tailLocalY <= boundMax && tailLocalY >= boundMin);
            boolean newRule = !(hi < boundMin || lo > boundMax);
            System.out.printf(Locale.ROOT,
                    "%-7.2f %-7s %-8.3f %-8.3f [%6.3f,%6.3f] %-5s %-5s %s%n",
                    t, hit != null ? "是" : "否", headLocalY, tailLocalY, boundMin, boundMax,
                    oldRule ? "画" : "剔", newRule ? "画" : "剔",
                    (oldRule != newRule) ? "← 旧规则在这里闪断" : "");
        }

        System.out.printf(Locale.ROOT,
                "%n状态：activeHolds=%d keyDownCount=%d 分数=%d miss=%d%n",
                engine.activeHolds().size(), engine.keyDownCount(), engine.score(), engine.miss());
    }

    private HoldProbe() {
    }
}
