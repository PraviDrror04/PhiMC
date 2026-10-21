import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 文字轨道两件事的自测：
 *
 * <p><b>一、{@code Chart.tweenText} 与 {@code prpr/src/core/tween.rs:582-631} 逐条对齐</b>
 * （期望值是照 Rust 源码手算的，不是拿我们的实现反推的）：
 * <pre>
 * 情况                                  Rust 源码                          t=0     t=0.5    t=1
 * "" → "abc"                            chars[..round(t*3)]                ""      "ab"     "abc"
 * "abc" → ""                            递归 tween("", "abc", 1-t)         "abc"   "ab"     ""
 * "a" → "abc"                           floor((3-1)*t)+1 个字               "a"     "ab"     "abc"
 * "abc" → "a"                           round((3-1)*(1-t))+1 个字           "abc"   "ab"     "a"
 * "君" → "が"（无公共前缀）              else x                              "君"    "君"     "君"
 * "0%P%" → "100%P%"                     数字插值，整数 → %.0f                "0"     "50"     "100"
 * "1.5%P%" → "2.5%P%"                   数字插值，非整数 → %.3f（端点仍是原串）  "1.5"   "2.000"  "2.5"
 * </pre>
 *
 * <p><b>二、真实谱面的文字轨道</b>：对带打字机效果的线，按时间采样打印「此刻显示什么」，
 * 确认逐字出现/消失的行为正常，并给出这条线的位置（= 判定线原点，{@code line.rs:295-296}）。
 *
 * <p>用法：{@code java -cp out/dev TextTweenCheck [pez…]}
 */
public final class TextTweenCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("== 一、tweenText 与 tween.rs:582-631 对照 ==");
        System.out.printf(Locale.ROOT, "%-26s %-10s %-10s %-10s %-10s %s%n",
                "用例", "t=0", "t=0.5", "t=1", "期望(t=0.5)", "结果");
        check("空 → abc", "", "abc", "", "ab", "abc");
        check("abc → 空", "abc", "", "abc", "ab", "");
        check("a → abc", "a", "abc", "a", "ab", "abc");
        check("abc → a", "abc", "a", "abc", "ab", "a");
        check("君 → が（无公共前缀）", "君", "が", "君", "君", "君");
        check("0%P% → 100%P%", "0%P%", "100%P%", "0", "50", "100");
        check("1.5%P% → 2.5%P%", "1.5%P%", "2.5%P%", "1.5", "2.000", "2.5");
        check("空 → 空", "", "", "", "", "");
        System.out.printf(Locale.ROOT, "%n%s%n%n", failures == 0 ? "全部通过 ✅" : ("失败 " + failures + " 项 ❌"));

        System.out.println("== 二、真实谱面的文字轨道（打字机采样）==");
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        String only = args.length > 0 ? args[0] : null;
        List<SongEntry> songs = SongEntry.scan(dir);
        for (SongEntry e : songs) {
            String name = e.path.getFileName().toString();
            if (only != null && !name.contains(only)) continue;
            Chart c = ChartParser.parse(e.chartJson());
            Chart.Line pick = null;
            for (Chart.Line l : c.lines) {
                if (l.text.isEmpty()) continue;
                // 挑一条「同一时间段内文本在变」的线（打字机），没有就挑事件最多的
                if (pick == null || l.text.size() > pick.text.size()) pick = l;
            }
            if (pick == null) continue;
            Chart.TextEv ev = null;
            for (Chart.TextEv t : pick.text) {
                if (!t.v0.equals(t.v1) && t.t1 > t.t0) {
                    ev = t;
                    break;
                }
            }
            System.out.printf(Locale.ROOT, "-- %s ：文字线 %d 条，取线 %d（%d 个事件）%n",
                    name, countText(c), pick.index, pick.text.size());
            if (ev == null) {
                String s = pick.textAt(pick.text.get(0).t0);
                System.out.printf(Locale.ROOT, "   没有「文本会变」的段，整段都是 %s%n%n", q(s));
                continue;
            }
            System.out.printf(Locale.ROOT, "   一个变化段 [%.2fs → %.2fs]：%s → %s%n",
                    ev.t0, ev.t1, q(ev.v0), q(ev.v1));
            for (int i = 0; i <= 10; i++) {
                double t = ev.t0 + (ev.t1 - ev.t0) * i / 10.0;
                double x = pick.xAt(t);
                double y = pick.yAt(t);
                System.out.printf(Locale.ROOT, "     t=%7.2f  %-24s  @(%.3f, %.3f)  rot=%.1f°%n",
                        t, q(pick.textAt(t)), x, y, pick.rotateAt(t));
            }
            System.out.println();
        }
    }

    private static int countText(Chart c) {
        int n = 0;
        for (Chart.Line l : c.lines) if (!l.text.isEmpty()) n++;
        return n;
    }

    private static void check(String name, String x, String y, String e0, String e05, String e1) {
        String a0 = Chart.tweenText(x, y, 0.0);
        String a05 = Chart.tweenText(x, y, 0.5);
        String a1 = Chart.tweenText(x, y, 1.0);
        boolean ok = a0.equals(e0) && a05.equals(e05) && a1.equals(e1);
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "%-26s %-10s %-10s %-10s %-10s %s%n",
                name, q(a0), q(a05), q(a1), q(e05), ok ? "✔" : "✗ 期望 " + q(e05));
    }

    private static String q(String s) {
        if (s == null) return "null";
        return "「" + s.replace("\n", "\\n") + "」";
    }

    private TextTweenCheck() {
    }
}
