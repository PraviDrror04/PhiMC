package dev.dsh.phimc.client;

import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.Engine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * 世界内 HUD 层（不接管鼠标；正式游玩界面是 {@link PlayScreen}）。
 *
 * <p>尺寸/颜色/单位与 PlayScreen 保持一致：
 * <ul>
 *   <li>音符宽 = 屏宽 × 0.13175（prpr/src/core.rs:15）</li>
 *   <li>音符高 = 宽 × 贴图高宽比（click.png 998×100）</li>
 *   <li>判定线粗 = 屏宽 × 0.005（line.rs:252 的 0.01 世界单位 × 半屏宽）</li>
 *   <li>判定线颜色 = FC/AP 指示器（game.rs:1029-1037），不是单独的文字/图标</li>
 * </ul>
 */
public final class HudRenderer {

    private static final int HIT_LINE_Y_FRAC = 82;              // v1：判定线固定在屏幕高度的 82%
    private static final double NOTE_W_FRAC = 0.13175;          // prpr/src/core.rs:15
    private static final double NOTE_H_RATIO = 100.0 / 998.0;   // assets/respack/click.png
    private static final double LINE_W_FRAC = 0.005;            // prpr line.rs:252

    private HudRenderer() {
    }

    public static void render(DrawContext ctx, Session session) {
        if (session == null || !session.playing() || session.engine() == null) return;
        Engine engine = session.engine();
        Chart chart = session.chart();
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        int lineY = h * HIT_LINE_Y_FRAC / 100;

        // 判定线（v1 不处理旋转/位移事件，先固定水平）；颜色 = FC/AP 指示器
        double alpha = chart.lines.isEmpty() ? 1.0 : chart.lines.get(0).alphaAt(session.seconds());
        int lineThick = Math.max(1, (int) Math.round(w * LINE_W_FRAC));
        int lineColor = session.judgeLineColor();
        double baseA = ((lineColor >>> 24) & 0xFF) / 255.0;   // 判定色自带 alpha（0xe1ffec9f 的 α=0.882）
        ctx.fill(0, lineY, w, lineY + lineThick, withAlpha(lineColor, alpha * baseA));

        int noteW = (int) Math.round(w * NOTE_W_FRAC);
        int noteH = Math.max(2, (int) Math.round(noteW * NOTE_H_RATIO));

        double t = session.seconds();
        List<Engine.Visible> visible = engine.visible(t, w / (double) h);
        for (Engine.Visible v : visible) {
            // yFrac：单位 = 屏高比例，正 = 判定线之上（与 PlayScreen 同一约定）
            int y = lineY - (int) Math.round(v.yFrac() * h);
            if (y < -h || y > h * 2) continue;          // 屏幕外的不画
            int x = (int) (v.xFrac() * w);
            if (x < -noteW || x > w + noteW) continue;
            int color = withAlpha(colorOf(v.state().note.type), v.alpha());
            ctx.fill(x - noteW / 2, y - noteH / 2, x + noteW / 2, y + noteH / 2, color);
        }

        // 分数 / 连击
        // 与 PlayScreen 保持一致：不显示判定文字（源码游戏内 HUD 只有 score/combo/曲名/难度/acc，
        // game.rs:428-532）。
        String score = String.format("%07d  %s", engine.score(), engine.rank());
        String combo = engine.combo() + " COMBO";
        ctx.drawText(mc.textRenderer, score, 8, 8, 0xFFFFFFFF, true);
        ctx.drawText(mc.textRenderer, combo, 8, 20, 0xFFFFE082, true);
        ctx.drawText(mc.textRenderer,
                String.format("%.1fs / %.1fs", t, chart.duration),
                w - 90, 8, 0xFFAAAAAA, true);
    }

    /** tap / hold = 蓝，drag = 黄，flick = 红（与 PlayScreen 统一）。 */
    private static int colorOf(Chart.Note.Type type) {
        return switch (type) {
            case TAP, HOLD -> 0xFF3AA6FF;
            case DRAG -> 0xFFFFD166;
            case FLICK -> 0xFFFF4D6D;
        };
    }

    /** 把 float alpha(0~1) 合进 ARGB。 */
    private static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
