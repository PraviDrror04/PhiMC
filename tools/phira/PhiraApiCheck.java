import dev.dsh.phimc.client.CoverCodec;
import dev.dsh.phimc.client.PhiraApi;
import dev.dsh.phimc.client.PhiraChart;
import dev.dsh.phimc.client.PhiraDownloader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phira 在线谱面库的<b>离线自测</b>（纯 Java，不需要 Minecraft / 图形界面）。
 *
 * <p>它调的就是 mod 里那份代码：{@link PhiraApi}（搜索 / 下载 / 域名切换 / 缓存）、
 * {@link PhiraChart}（字段解析 + stable && reviewed 过滤）、
 * {@link PhiraDownloader}（命名 / 判重 / 落盘 / 进度 / 中文失败原因）、
 * {@link CoverCodec#toPngThumbnail}（曲绘缩略图）。
 *
 * <p>用法（需要 gson + slf4j-api 在 classpath 上）：
 * <pre>
 * javac -cp "&lt;out&gt;;gson.jar" -d &lt;check-out&gt; &lt;mod 的 4 个源文件&gt; PhiraApiCheck.java
 * java -cp "&lt;check-out&gt;;gson.jar;slf4j-api.jar" PhiraApiCheck [songsDir]
 * </pre>
 * 不带参数时写到 {@code C:\dsh-work\phira-check\songs}。
 */
public final class PhiraApiCheck {

    private static final String DEFAULT_DIR = "C:\\dsh-work\\phira-check\\songs";

    private static int failures;

    public static void main(String[] args) throws Exception {
        // 真实网络：api.phira.cn（失败会切 phira.5wyxi.com）
        System.out.println("== 1. 搜索（空搜索词，按评分）==");
        long t0 = System.nanoTime();
        PhiraApi.ListResult a = PhiraApi.search("", "-rating", 1, 5);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("ok=%s count=%d 本页=%d 用时=%dms 错误=%s%n",
                a.ok(), a.count(), a.charts().size(), ms, a.error());
        check(a.ok(), "搜索成功");
        check(a.count() > 0, "服务端有总数");
        check(!a.charts().isEmpty(), "本页有谱面");
        for (PhiraChart c : a.charts()) {
            check(c.isPublicStable(), "只返回 stable && reviewed：" + c.id + " " + c.title());
            System.out.printf("  #%d %-28s %-10s 评分 %s（%d 人）谱师 %s 曲绘 %s%n",
                    c.id, cut(c.title(), 28), c.levelText(), c.ratingText(), c.ratingCount,
                    c.charterText(), c.illustrationUrl().isEmpty() ? "(无)" : "有");
        }

        System.out.println("\n== 2. 按谱师搜索（search 对 charter 也生效）==");
        String charter = a.charts().get(0).charterText();
        PhiraApi.ListResult b = PhiraApi.search(charter, "-rating", 1, 5);
        System.out.printf("谱师「%s」：ok=%s count=%d 本页=%d%n", charter, b.ok(), b.count(), b.charts().size());
        check(b.ok(), "按谱师搜索成功");
        check(b.count() > 0, "谱师搜到结果");
        boolean anyByCharter = false;
        for (PhiraChart c : b.charts()) {
            if (c.charterText().toLowerCase(Locale.ROOT).contains(charter.toLowerCase(Locale.ROOT))) {
                anyByCharter = true;
            }
        }
        check(anyByCharter, "结果里确实有该谱师的谱面");

        System.out.println("\n== 3. 60 秒缓存（同一搜索词第二次不打接口）==");
        long t1 = System.nanoTime();
        PhiraApi.ListResult a2 = PhiraApi.search("", "-rating", 1, 5);
        long ms2 = (System.nanoTime() - t1) / 1_000_000L;
        System.out.printf("第二次用时=%dms（首次 %dms），同结果=%s%n", ms2, ms, a2.charts().size() == a.charts().size()
                && a2.count() == a.count());
        check(ms2 < 5, "缓存命中应该几乎 0ms");
        check(a2.count() == a.count(), "缓存内容一致");

        System.out.println("\n== 4. 主域名失败 → 自动切备用域名一次 ==");
        // 不依赖 BASES 里谁是主谁是备（顺序会因线上可用性调整）：这里把 [0] 钉成必定连不上的地址、
        // [1] 钉成官方在用的 phira.5wyxi.com，专门测「第一个失败会切第二个」这条逻辑。
        String[] good = PhiraApi.BASES.clone();
        PhiraApi.BASES[0] = "https://127.0.0.1:1";          // 必定连接失败
        PhiraApi.BASES[1] = "https://phira.5wyxi.com";      // 官方客户端 API_URL
        try {
            PhiraApi.clearCache();
            long t2 = System.nanoTime();
            PhiraApi.ListResult c = PhiraApi.search(a.charts().get(0).charterText(), "-updated", 1, 3);
            long ms3 = (System.nanoTime() - t2) / 1_000_000L;
            System.out.printf("主域名=127.0.0.1:1 → ok=%s count=%d 本页=%d 用时=%dms 错误=%s%n",
                    c.ok(), c.count(), c.charts().size(), ms3, c.error());
            check(c.ok(), "切到 phira.5wyxi.com 后仍然成功");
            check(!c.charts().isEmpty(), "备用域名确实返回了谱面");
        } finally {
            System.arraycopy(good, 0, PhiraApi.BASES, 0, good.length);
        }

        System.out.println("\n== 5. 曲绘缩略图（下载 + 缩放 + PNG）==");
        String url = a.charts().get(0).illustrationUrl();
        byte[] raw = PhiraApi.fetchBytes(url);
        check(raw != null && raw.length > 0, "曲绘下载成功：" + url);
        if (raw != null) {
            byte[] png = CoverCodec.toPngThumbnail(raw, 64);
            check(png != null && png.length > 8, "缩略图编码成功");
            if (png != null) {
                check((png[0] & 0xFF) == 0x89 && png[1] == 0x50, "缩略图是 PNG 魔数");
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                check(img != null, "缩略图能解码");
                if (img != null) {
                    System.out.printf("原图 %d 字节 → 缩略图 %d 字节，%d×%d%n",
                            raw.length, png.length, img.getWidth(), img.getHeight());
                    check(Math.max(img.getWidth(), img.getHeight()) <= 64, "长边 ≤ 64px");
                }
            }
        }

        System.out.println("\n== 6. 文件名安全化 ==");
        String[] rawNames = {
                "R.I.P.", "a/b\\c:d*e?f\"g<h>i|j", "  ..名字..  ", "", "emoji 🎵🎶 谱面",
                "超长名字".repeat(20)
        };
        for (String n : rawNames) {
            String safe = PhiraDownloader.safeBaseName(n);
            System.out.printf("  %-24s → 「%s」（%d 码点）%n",
                    "\"" + cut(n, 24) + "\"", cut(safe, 24), safe.codePointCount(0, safe.length()));
            check(safe.codePointCount(0, safe.length()) <= 60, "曲名截断 ≤ 60 码点");
            check(!safe.matches(".*[\\\\/:*?\"<>|\\x00-\\x1F].*"), "没有非法字符");
            check(!safe.startsWith(".") && !safe.endsWith("."), "首尾没有点");
            check(!safe.isBlank(), "不为空");
        }

        System.out.println("\n== 7. 下载 + 落盘 + 判重 ==");
        Path dir = Path.of(args.length > 0 ? args[0] : DEFAULT_DIR);
        Files.createDirectories(dir);
        // 上一次跑留下来的同名包会让「下载前」断言失败（判重是对的），所以先清掉自己下过的
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.matches("\\d+_.*\\.(pez|zip)") || n.endsWith(".part")) Files.deleteIfExists(p);
            }
        }
        PhiraChart pick = smallest(a.charts());
        Path target = PhiraDownloader.targetFile(pick, dir);
        System.out.printf("下载 #%d %s → %s%n", pick.id, pick.title(), target.getFileName());
        PhiraDownloader.refresh(dir);
        check(!PhiraDownloader.isDownloaded(pick.id), "下载前：未标记已下载");
        PhiraDownloader.Job job = PhiraDownloader.download(pick, dir);
        check(job != null, "任务已登记");
        long deadline = System.currentTimeMillis() + 120_000L;
        long lastPct = -1;
        while (System.currentTimeMillis() < deadline) {
            PhiraDownloader.State st = job.state();
            if (st == PhiraDownloader.State.RUNNING) {
                long pct = job.percent();
                if (pct >= 0 && pct / 25 != lastPct / 25) {
                    lastPct = pct;
                    System.out.printf("  进度 %d%%（%d / %d 字节）%n", pct, job.received(), job.total());
                }
            } else if (st == PhiraDownloader.State.DONE || st == PhiraDownloader.State.FAILED) {
                break;
            }
            Thread.sleep(200);
        }
        System.out.printf("终态=%s 文件=%s 大小=%d 消息=%s%n", job.state(),
                job.file() == null ? "-" : job.file().getFileName(),
                job.file() == null || !Files.exists(job.file()) ? -1 : Files.size(job.file()),
                job.message());
        check(job.state() == PhiraDownloader.State.DONE, "下载完成");
        check(job.file() != null && Files.exists(job.file()), "文件已落盘");
        check(target.equals(job.file()), "落盘路径 = {id}_{安全化曲名}.pez");
        if (job.file() != null) {
            byte[] head = Files.readAllBytes(job.file());
            check(head.length > 4 && (head[0] & 0xFF) == 0x50 && (head[1] & 0xFF) == 0x4B,
                    "内容是 zip（.pez）");
        }
        check(job.file() != null && job.file().getFileName().toString().startsWith(pick.id + "_"),
                "文件名以 id 前缀开头");
        PhiraDownloader.refresh(dir);
        Set<Long> ids = PhiraDownloader.scan(dir);
        System.out.println("  songs/ 里识别出的 Phira id：" + ids);
        check(PhiraDownloader.isDownloaded(pick.id), "下载后：标记已下载");
        PhiraDownloader.Job again = PhiraDownloader.download(pick, dir);
        check(again != null && again.state() == PhiraDownloader.State.DONE
                && "已下载".equals(again.message()), "重复下载被拦下（标「已下载」）");

        System.out.println("\n== 8. 磁盘错误的中文提示 ==");
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");                    // 用文件占住目录名
        try {
            PhiraApi.download(pick, blocker.resolve("x.pez"), null);
            System.out.println("  ✗ 应该抛异常");
            failures++;
        } catch (PhiraApi.PhiraFail e) {
            System.out.println("  失败原因：" + e.getMessage());
            check(e.getMessage().contains("磁盘") || e.getMessage().contains("超时")
                    || e.getMessage().contains("连接") || e.getMessage().contains("HTTP"),
                    "失败原因里带中文分类");
        }

        System.out.println("\n== 9. 「仅上架」关掉：未上架 / 未过审也返回 ==");
        PhiraApi.clearCache();
        PhiraApi.ListResult all = PhiraApi.search("", "-updated", 1, 10, false);
        System.out.printf("ok=%s count=%d 本页=%d%n", all.ok(), all.count(), all.charts().size());
        check(all.ok(), "关掉「仅上架」后搜索成功");
        check(all.count() > a.count(), "总数变大（含未上架）：" + all.count() + " > " + a.count());
        int unstable = 0;
        for (PhiraChart c : all.charts()) {
            if (!c.isPublicStable()) unstable++;
        }
        System.out.printf("  本页 %d 条，其中未上架 / 未过审 %d 条（界面上灰色 + [未上架]）%n",
                all.charts().size(), unstable);
        check(unstable > 0, "本页确实出现了未上架的谱面");
        PhiraApi.ListResult stableAgain = PhiraApi.search("", "-updated", 1, 10, true);
        int unstableAgain = 0;
        for (PhiraChart c : stableAgain.charts()) {
            if (!c.isPublicStable()) unstableAgain++;
        }
        System.out.printf("  切回「仅上架」：count=%d 本页=%d 未上架=%d%n",
                stableAgain.count(), stableAgain.charts().size(), unstableAgain);
        check(unstableAgain == 0, "切回「仅上架」后一条未上架都没有");
        check(stableAgain.count() < all.count(), "「仅上架」总数更小：" + stableAgain.count()
                + " < " + all.count());

        System.out.println();
        if (failures == 0) {
            System.out.println("全部通过 ✓");
        } else {
            System.out.println("有 " + failures + " 项失败 ✗");
            System.exit(1);
        }
    }

    /** 取文件最小的那个（自测少下点流量）。 */
    private static PhiraChart smallest(List<PhiraChart> charts) {
        return charts.get(0);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + what);
        if (!ok) failures++;
    }

    private static String cut(String s, int n) {
        if (s == null) return "-";
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n)) + "…";
    }
}
