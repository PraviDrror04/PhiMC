import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;
import dev.dsh.phimc.core.Engine;
import dev.dsh.phimc.core.Judgement;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全库 Hold 渲染完整性审计：对每个 Hold 音符，在 {@code [time, time+hold]} 上按 0.1s 采样，
 * 检查它是否<b>每一刻都在 {@link Engine#visible} 里且身体长度 > 0</b>。
 *
 * <p>任何「{@code time <= t < endTime} 却不在绘制列表里」的点都是渲染 bug（与判定无关：
 * 这里全程<b>不按键</b>，hold 只是未判定状态，本来就该一直画着）。
 *
 * <p>用法：{@code java -cp out/dev HoldAudit [目录] [最多打印几个例子]}
 */
public final class HoldAudit {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        // args[0] = held（模拟一直按住）| 其它 = 不按键
        boolean held = args.length > 0 && args[0].equals("held");
        int examples = 12;
        double aspect = 16.0 / 9.0;
        System.out.printf(Locale.ROOT, "模式：%s%n", held ? "一直按住（keyDown 到尾判定）" : "不按键");
        int holds = 0;
        int chartsWithBad = 0;
        long samplesTotal = 0;
        long samplesMissing = 0;
        Map<String, Integer> causes = new HashMap<>();
        int printed = 0;
        for (SongEntry e : SongEntry.scan(dir)) {
            Chart c;
            try {
                c = ChartParser.parse(e.chartJson());
            } catch (Throwable t) {
                continue;
            }
            Engine engine = new Engine(c, new Judgement.Config());
            boolean bad = false;
            for (Chart.Line line : c.lines) {
                for (Chart.Note n : line.notes) {
                    if (n.type != Chart.Note.Type.HOLD || n.hold <= 1e-6) continue;
                    holds++;
                    double end = n.endTime();
                    boolean down = false;
                    for (double t = n.time; t < end - 1e-9; t += 0.1) {
                        if (held && !down) {
                            engine.keyDown(t);          // 按下，之后不松
                            down = true;
                        }
                        samplesTotal++;
                        List<Engine.Visible> vis = engine.visible(t, aspect);
                        Engine.Visible hit = null;
                        for (Engine.Visible v : vis) {
                            if (v.state().note == n) {
                                hit = v;
                                break;
                            }
                        }
                        if (hit == null) {
                            samplesMissing++;
                            bad = true;
                            String why;
                            double a = line.alphaAt(t);
                            if (a < 0.0) why = "线 alpha<0（源码也整条不画）";
                            else if (a <= 0.01) why = "线 alpha≈0";
                            else if (n.alphaAt(t) <= 0.001) why = "音符 alpha=0(visibleTime)";
                            else why = "其它（头尾都在视口外等）";
                            causes.merge(why, 1, Integer::sum);
                            if (printed < examples) {
                                printed++;
                                System.out.printf(Locale.ROOT,
                                        "  %s 线%d note(t=%.2f hold=%.2f) t=%.2f 缺失：%s%n",
                                        e.path.getFileName(), c.lines.indexOf(line), n.time, n.hold, t, why);
                            }
                            break;   // 这首谱记一次就够
                        }
                    }
                    if (held && down) engine.keyUp(end);
                }
            }
            if (bad) chartsWithBad++;
        }
        System.out.printf(Locale.ROOT, "%nHold 总数 %d；采样点 %d，缺失 %d（%.4f%%）；出问题的谱 %d 首%n",
                holds, samplesTotal, samplesMissing,
                samplesTotal == 0 ? 0 : samplesMissing * 100.0 / samplesTotal, chartsWithBad);
        causes.forEach((k, v) -> System.out.printf(Locale.ROOT, "   %-28s %d%n", k, v));
    }

    private HoldAudit() {
    }
}
