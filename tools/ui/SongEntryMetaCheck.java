import dev.dsh.phimc.client.ChartMeta;
import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.client.SongMeta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 「曲师 / 谱师 / 画师 / 曲绘图文件名」的字段自测：<b>修前 vs 修后</b>。
 *
 * <p><b>修前</b> = {@code info.txt} 的原值（{@code SongEntry.composer} 等字段）。
 * <b>修后</b> = {@code SongEntry.composer() / charter() / illustrator() / pictureName()} ——
 * info.txt 缺的字段由谱面 {@code META} 兜底（{@link ChartMeta} + {@link SongMeta} 后台线程）。
 *
 * <p>RPE（.pez）的 {@code info.txt} 没有 Illustrator 字段，所以修前画师一律「未知」，
 * 修后应该出现真人名（Cyberneko Pulse 必须是 {@code keckyx}）。
 *
 * <p>只依赖 JDK + gson + slf4j（{@code SongEntry}/{@code SongMeta}/{@code ChartMeta} 都不碰
 * Minecraft 的类），可以离线跑真谱包。命令见 {@code docs/REPORT-meta-fallback.md}。
 *
 * <p>用法：{@code java -cp "out;gson;slf4j" SongEntryMetaCheck C:\dsh-work\payload}
 */
public final class SongEntryMetaCheck {

    /** META 后台读取的等待上限（毫秒/首）。 */
    private static final long WAIT_MS = 60_000;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "C:\\dsh-work\\payload");
        // 列表初始化成本：SongEntry.scan 只开 zip 读 info.txt（几百字节），**不碰谱面 JSON**
        long scan0 = System.nanoTime();
        List<SongEntry> songs = SongEntry.scan(dir);
        double scanMs = (System.nanoTime() - scan0) / 1e6;
        System.out.printf("扫到 %d 个谱面（%s），SongEntry.scan 耗时 %.1f ms（只读 info.txt，不解析谱面 JSON）%n%n",
                songs.size(), dir, scanMs);

        System.out.printf("%-28s %-19s %-20s %-20s %-20s %s%n",
                "谱面包", "曲名", "曲师（修前→修后）", "谱师（修前→修后）", "画师（修前→修后）", "曲绘图文件名");
        System.out.println("-".repeat(160));

        int fixedIllust = 0;
        int fixedComposer = 0;
        int fixedCharter = 0;
        int fixedPicture = 0;
        int unknownAfter = 0;

        for (SongEntry s : songs) {
            // 惰性读 META：模拟界面「选中这一首 → 后台读 → 等它回来」
            loadMeta(s);
            String name = s.path.getFileName().toString();
            String composer = pair(s.composer, s.composer());
            String charter = pair(s.charter, s.charter());
            String illust = pair(s.illustrator, s.illustrator());
            String picture = pair(s.picture, s.pictureName());
            if (!s.composer.equals(s.composer())) fixedComposer++;
            if (!s.charter.equals(s.charter())) fixedCharter++;
            if (!s.illustrator.equals(s.illustrator())) fixedIllust++;
            if (!s.picture.equals(s.pictureName())) fixedPicture++;
            if (s.illustrator().isBlank()) unknownAfter++;
            System.out.printf("%-28s %-19s %-20s %-20s %-20s %s%n",
                    cut(name, 28), cut(s.title(), 19), cut(composer, 20),
                    cut(charter, 20), cut(illust, 20), cut(picture, 34));
        }

        System.out.println("-".repeat(160));
        System.out.printf("%d 首里：曲师被 META 补上 %d、谱师 %d、画师 %d、曲绘文件名 %d；补完仍无画师 %d 首%n",
                songs.size(), fixedComposer, fixedCharter, fixedIllust, fixedPicture, unknownAfter);

        // 人名 / 文件名 不许互相污染（之前踩过的坑）：画师不能长得像文件名，图名也不能等于画师
        int dirty = 0;
        for (SongEntry s : songs) {
            String artist = s.illustrator();
            String pic = s.pictureName();
            boolean artistLooksLikeFile = artist.toLowerCase(java.util.Locale.ROOT)
                    .matches(".*\\.(png|jpe?g|bmp|gif|webp)$") || artist.contains("/") || artist.contains("\\");
            boolean swapped = !artist.isEmpty() && artist.equals(pic);
            if (artistLooksLikeFile || swapped) {
                dirty++;
                System.out.printf("  ✗ 人名/文件名串了：%s → 画师「%s」 图名「%s」%n",
                        s.path.getFileName(), artist, pic);
            }
        }
        System.out.println(dirty == 0
                ? "  ✔ 人名 / 文件名没有互相污染（画师不长得像文件名，图名 ≠ 画师）"
                : "  ✗ " + dirty + " 处人名/文件名串了");

        // META 扫描成本：分别量「RPE（META 在文件前部，读到就停）」和「官方 PGR（压根没有 META，
        // 只能整份 lex 一遍）」两种最坏情况，证明这条后台线程的成本可以接受。
        measureScan(songs, true, "RPE(.pez，有 META)");
        measureScan(songs, false, "官方 PGR(.zip，无 META，最坏情况)");
    }

    private static void measureScan(List<SongEntry> songs, boolean pez, String label) throws Exception {
        SongEntry biggest = null;
        long biggestSize = -1;
        for (SongEntry s : songs) {
            boolean isPez = s.path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pez");
            if (isPez != pez) continue;
            try {
                long size = Files.size(s.path);
                if (size > biggestSize) {
                    biggestSize = size;
                    biggest = s;
                }
            } catch (Exception ignored) {
            }
        }
        if (biggest == null) return;
        String json = biggest.chartJson();
        long t0 = System.nanoTime();
        ChartMeta meta = ChartMeta.scan(json);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%nMETA 流式扫描 %s：%s（谱面 JSON %.1f MB）→ %.2f ms%n",
                label, biggest.path.getFileName(), json.length() / 1048576.0, ms);
        System.out.println("  读到：" + meta);
        System.out.println("  （有 META 时读到就 break，不 lex 后面十几 MB 的 judgeLineList；"
                + "没有 META 时整份 skipValue，不建对象）");
    }

    /** 触发 {@link SongMeta} 的后台解析并等它结束（相当于界面在那一首上停留几百毫秒）。 */
    private static void loadMeta(SongEntry s) throws Exception {
        SongMeta.get(s);
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (SongMeta.isLoading(s) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        SongMeta.get(s);                 // 再取一次（成功/失败都落定了）
    }

    /** 「修前→修后」：值没变就只打印一个；变了就打印 {@code 旧→新}（旧为空时写「未知」）。 */
    private static String pair(String raw, String effective) {
        String a = raw == null ? "" : raw;
        String b = effective == null ? "" : effective;
        if (a.equals(b)) return b.isEmpty() ? "未知" : b;
        return (a.isEmpty() ? "未知" : a) + " → " + (b.isEmpty() ? "未知" : b);
    }

    /** 中日韩字符按 2 列算，只为了对齐。 */
    private static String cut(String s, int max) {
        if (s == null) return "-";
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = c < 0x2E80 ? 1 : 2;
            if (w + cw > max - 1) {
                sb.append('…');
                break;
            }
            sb.append(c);
            w += cw;
        }
        return sb.toString();
    }
}
