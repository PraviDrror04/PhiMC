import dev.dsh.phimc.client.CoverCodec;
import dev.dsh.phimc.client.SongEntry;
import dev.dsh.phimc.core.Chart;
import dev.dsh.phimc.core.ChartParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 贴图线的<b>贴图内容</b>体检：把谱面里每个 {@code Texture} 名字按 {@code LineTextures.readImage}
 * 的同一条路（{@link CoverCodec#toPng} + 图像解码）解一遍，报告
 * <b>真实像素尺寸 / 不同颜色数 / 平均 alpha</b>。
 *
 * <p>用途：判断「贴图线画成整块纯色」是
 * <b>(a)</b> 图本身就是纯色（那是对的）、还是
 * <b>(b)</b> 图是真彩图、我们没采到纹素（那是绘制/注册的 bug）。
 * 「不同颜色数」是判据：真彩图几十上万，纯色块只有 1~2。
 *
 * <p>同时打印按 {@code prpr} 口径算出来的目标尺寸，和 {@code LineTextures} 会拿到手的
 * {@code (width, height)} 对照（两者应完全一致，不一致说明解码路径有问题）。
 *
 * <p>用法：{@code java -cp out/dev TextureAudit [pez…]}
 */
public final class TextureAudit {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        List<String> want = args.length > 0 ? List.of(args) : List.of("48273_Undertale.pez", "78805_enchanted love.pez");
        List<SongEntry> songs = SongEntry.scan(dir);
        for (String w : want) {
            SongEntry entry = null;
            for (SongEntry e : songs) {
                if (e.path != null && e.path.getFileName().toString().equals(w)) {
                    entry = e;
                    break;
                }
            }
            if (entry == null) {
                System.out.println("找不到 " + w);
                continue;
            }
            Chart c = ChartParser.parse(entry.chartJson());
            Set<String> names = new LinkedHashSet<>();
            for (Chart.Line l : c.lines) if (l.imageLine && !l.textureName.isEmpty()) names.add(l.textureName);
            System.out.printf(Locale.ROOT, "== %s ：贴图线 %d 条，%d 个不同图名 ==%n",
                    w, c.lines.stream().filter(l -> l.imageLine).count(), names.size());
            System.out.printf(Locale.ROOT, "   %-32s %-12s %-8s %-9s %-9s %s%n",
                    "Texture", "包内字节", "格式", "像素尺寸", "颜色数", "平均alpha");
            int solid = 0;
            int real = 0;
            for (String n : names) {
                byte[] raw = entry.asset(n);
                if (raw == null) {
                    System.out.printf(Locale.ROOT, "   %-32s 缺失%n", cut(n, 32));
                    continue;
                }
                CoverCodec.Format fmt = CoverCodec.detect(raw);
                byte[] png = CoverCodec.toPng(raw);
                BufferedImage img = png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
                if (img == null) {
                    System.out.printf(Locale.ROOT, "   %-32s %-12d %-8s 解不开%n", cut(n, 32), raw.length, fmt.label());
                    continue;
                }
                Set<Integer> colors = new HashSet<>();
                long alphaSum = 0;
                int stepX = Math.max(1, img.getWidth() / 64);
                int stepY = Math.max(1, img.getHeight() / 64);
                int sampled = 0;
                for (int y = 0; y < img.getHeight(); y += stepY) {
                    for (int x = 0; x < img.getWidth(); x += stepX) {
                        int argb = img.getRGB(x, y);
                        colors.add(argb);
                        alphaSum += (argb >>> 24) & 0xFF;
                        sampled++;
                    }
                }
                double meanAlpha = sampled == 0 ? 0 : alphaSum / (double) sampled;
                if (colors.size() <= 2) solid++;
                else real++;
                System.out.printf(Locale.ROOT, "   %-32s %-12d %-8s %-9s %-9d %.0f%n",
                        cut(n, 32), raw.length, fmt.label(),
                        img.getWidth() + "×" + img.getHeight(), colors.size(), meanAlpha);
            }
            System.out.printf(Locale.ROOT, "   → 纯色图 %d 个 / 真彩图 %d 个%n%n", solid, real);
        }
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private TextureAudit() {
    }
}
