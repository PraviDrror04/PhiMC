import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.client.SongMeta;

import java.nio.file.Path;
import java.util.List;

/**
 * <b>时序回归测试</b>：画师会不会在「打完一局 / 换资源包返回」之后变回「未知」。
 *
 * <h2>曾经的 bug（用户报的）</h2>
 * <ol>
 *   <li>{@link SongEntry} 是 {@code SongEntry.scan()} 每次重建的<b>新对象</b>；
 *       {@code SongEntry.meta}（META 兜底）是实例字段，重建就丢。</li>
 *   <li>{@code SongMeta.CACHE} 按包路径缓存，键没变、值还在；</li>
 *   <li>但 {@code SongMeta.get(entry)} 命中缓存时<b>直接 return</b>，没有
 *       {@code entry.fillFromMeta(...)} → 新对象永远填不上；</li>
 *   <li>{@code SongSelectScreen.init()} 每次都 {@code session.rescan()}（打完一局返回、
 *       从设置页回来、换资源包回来都走这条）→ 所有 SongEntry 换新 → 画师/曲师/谱师
 *       退回 info.txt 原值（RPE 的 info.txt 没有 Illustrator ⇒ 「未知」）。</li>
 * </ol>
 *
 * <h2>现在的契约（这个程序断言的就是它）</h2>
 * <ol>
 *   <li>首次：{@code scan → get}（触发一次后台解析）→ 画师 = {@code keckyx}；</li>
 *   <li><b>重建</b>：再 {@code scan}（模拟打完一局返回）→ 对<b>新对象不调 get</b>，
 *       直接读 {@code illustrator()} → 仍然是 {@code keckyx}（回填来自已有缓存，不解析）；</li>
 *   <li><b>缓存被清</b>（{@code SongMeta.clear()}，等价于「换资源包」那条最坏路径）→
 *       {@code scan} 后仍然是「未知」（保持惰性，不偷偷解析大 JSON）→ 调 {@code get} 之后
 *       恢复 {@code keckyx} → 再重建仍然是 {@code keckyx}。</li>
 * </ol>
 *
 * <p>用 {@code C:\dsh-work\payload\Cyberneko Pulse (2).pez} 做样本（它的画师只写在谱面
 * {@code META.illustration} 里，值是 {@code keckyx}）。
 *
 * <p>用法：{@code java -cp "out;gson;slf4j" MetaRescanTest C:\dsh-work\payload}
 */
public final class MetaRescanTest {

    private static final String SAMPLE = "Cyberneko";
    private static final String EXPECTED_ARTIST = "keckyx";
    private static final long WAIT_MS = 60_000;

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "C:\\dsh-work\\payload");
        System.out.println("样本目录：" + dir + "（找 " + SAMPLE + "*.pez，期望画师 " + EXPECTED_ARTIST + "）");

        // ---------- 1) 首次：scan → get（触发解析） ----------
        section("1) 首次 scan → SongMeta.get（触发后台解析）");
        List<SongEntry> first = SongEntry.scan(dir);
        SongEntry a = findSample(first);
        if (a == null) {
            System.out.println("  ✗ 没找到样本包 " + SAMPLE + "*.pez");
            System.exit(1);
        }
        long t0 = System.nanoTime();
        SongMeta.get(a);
        waitDone(a);
        System.out.printf("  解析耗时 %.0f ms，物量=%s%n",
                (System.nanoTime() - t0) / 1e6,
                SongMeta.get(a) == null ? "-" : SongMeta.get(a).notes());
        check("首次：画师 = " + EXPECTED_ARTIST, EXPECTED_ARTIST.equals(a.illustrator()), a.illustrator());

        // ---------- 2) 重建（打完一局返回）：只 scan，不 get ----------
        section("2) 再 scan（模拟打完一局返回）→ 对新对象【不调 get】直接读");
        long t1 = System.nanoTime();
        List<SongEntry> second = SongEntry.scan(dir);
        double scanMs = (System.nanoTime() - t1) / 1e6;
        SongEntry b = findSample(second);
        System.out.printf("  重建耗时 %.1f ms；新对象 isLoading=%s（说明没有偷偷解析）%n",
                scanMs, b == null ? "-" : SongMeta.isLoading(b));
        if (b == null) {
            System.out.println("  ✗ 第二次 scan 没找到样本");
            failed++;
        } else {
            check("重建后（不调 get）画师仍是 " + EXPECTED_ARTIST,
                    EXPECTED_ARTIST.equals(b.illustrator()), b.illustrator());
            check("重建后没有起后台解析（保持惰性）", !SongMeta.isLoading(b), "isLoading=" + SongMeta.isLoading(b));
            check("重建后是新对象（对照：确实换了实例）", a != b, a == b ? "同一个实例" : "不同实例");
        }

        // ---------- 3) 缓存被清（换资源包 / 手动 clear）：不能凭空有值，也不能永久丢 ----------
        section("3) SongMeta.clear()（模拟换资源包那条最坏路径）→ scan → get → 再 scan");
        SongMeta.clear();
        List<SongEntry> third = SongEntry.scan(dir);
        SongEntry c = findSample(third);
        if (c == null) {
            System.out.println("  ✗ 第三次 scan 没找到样本");
            failed++;
        } else {
            check("清缓存后 scan：先回到「未知」（保持惰性，不解析）", c.illustrator().isEmpty(), c.illustrator());
            SongMeta.get(c);
            waitDone(c);
            check("清缓存后 get：画师恢复 " + EXPECTED_ARTIST, EXPECTED_ARTIST.equals(c.illustrator()), c.illustrator());
            List<SongEntry> fourth = SongEntry.scan(dir);
            SongEntry d = findSample(fourth);
            check("再次重建（不调 get）：画师仍 " + EXPECTED_ARTIST,
                    d != null && EXPECTED_ARTIST.equals(d.illustrator()),
                    d == null ? "(没找到)" : d.illustrator());
        }

        System.out.println();
        System.out.println(failed == 0
                ? "结论: ✔ 时序断言全部通过（重建 / 清缓存 / 再重建，画师都不丢）"
                : "结论: ✗ " + failed + " 项失败 —— 画师又变回「未知」了");
        if (failed != 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 工具

    private static SongEntry findSample(List<SongEntry> songs) {
        for (SongEntry s : songs) {
            if (s.path.getFileName().toString().startsWith(SAMPLE)) return s;
        }
        return null;
    }

    /** 等后台解析结束（和界面「停在这一首上几百毫秒」等价）。 */
    private static void waitDone(SongEntry s) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (SongMeta.isLoading(s) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        SongMeta.get(s);
    }

    private static void check(String what, boolean ok, String actual) {
        if (ok) {
            System.out.println("  ✔ " + what);
        } else {
            failed++;
            System.out.println("  ✗ " + what + "（实得「" + actual + "」）");
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }
}
