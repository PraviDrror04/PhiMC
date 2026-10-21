import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 把用户提供的原图缩成 Minecraft 物品贴图（默认 16×16）。
 *
 * <p>用法（JDK 25 单文件启动，不用先编译）：
 * <pre>
 *   java tools/icons/GenItemIcons.java &lt;源图.png&gt; &lt;目标.png&gt; [边长=16]
 * </pre>
 *
 * <p><b>为什么要自己算，而不是 getScaledInstance / drawImage</b>：
 * 1024×1024 → 16×16 是 64 倍降采样，任何「最近邻」都会丢掉大半像素（插画的细边、
 * 蓝发的白色发带都会消失）。这里做的是<b>面积平均</b>（box filter）：每个目标像素恰好
 * 覆盖 64×64 个源像素，按覆盖面积加权求平均，等价于把整块像素压成一个点。
 *
 * <p><b>alpha 怎么保</b>：颜色在<b>预乘 alpha</b> 的空间里加权平均，最后再除回去。
 * 直接对非预乘的 RGB 求平均，会让全透明像素里的脏颜色渗进边上（抠出来的角色轮廓
 * 会出现黑边/白边）。源图本来就是透明背景时，这个做法能保住边缘的半透明过渡。
 */
public final class GenItemIcons {

    private static final int DEFAULT_SIZE = 16;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法: java GenItemIcons.java <源图.png> <目标.png> [边长=16]");
            System.exit(2);
            return;
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        int size = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_SIZE;

        BufferedImage src = ImageIO.read(in);
        if (src == null) {
            System.err.println("读不出图片（不是 PNG/JPEG?）: " + in);
            System.exit(1);
            return;
        }

        BufferedImage dst = resizeArea(src, size);

        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        if (!ImageIO.write(dst, "png", out)) {
            System.err.println("没有 PNG writer，写不出来: " + out);
            System.exit(1);
            return;
        }

        System.out.printf("%s -> %s%n", in, out);
        System.out.printf("  源图 : %d×%d  %s%n", src.getWidth(), src.getHeight(), alphaSummary(src));
        System.out.printf("  目标 : %d×%d  %s  (%d 字节)%n",
                dst.getWidth(), dst.getHeight(), alphaSummary(dst), out.length());
    }

    /**
     * 面积平均缩小（任意比例都成立，1:1 时是恒等变换）。
     *
     * @return {@code size×size} 的 ARGB 图（即使源图没有 alpha，输出也带 alpha 通道）
     */
    static BufferedImage resizeArea(BufferedImage src, int size) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        for (int dy = 0; dy < size; dy++) {
            // 目标像素在源图上的纵向覆盖区间 [y0, y1)
            double y0 = dy * (double) sh / size;
            double y1 = (dy + 1) * (double) sh / size;
            for (int dx = 0; dx < size; dx++) {
                double x0 = dx * (double) sw / size;
                double x1 = (dx + 1) * (double) sw / size;

                double sumA = 0;      // Σ w·a
                double sumR = 0;      // Σ w·a·r
                double sumG = 0;
                double sumB = 0;
                double sumW = 0;      // Σ w（总面积，用来还原平均 alpha）

                int px0 = Math.max(0, (int) Math.floor(x0));
                int px1 = Math.min(sw - 1, (int) Math.ceil(x1) - 1);
                int py0 = Math.max(0, (int) Math.floor(y0));
                int py1 = Math.min(sh - 1, (int) Math.ceil(y1) - 1);

                for (int py = py0; py <= py1; py++) {
                    double oy = Math.min(y1, py + 1.0) - Math.max(y0, py);
                    if (oy <= 0) continue;
                    for (int px = px0; px <= px1; px++) {
                        double ox = Math.min(x1, px + 1.0) - Math.max(x0, px);
                        if (ox <= 0) continue;
                        double w = ox * oy;
                        int argb = src.getRGB(px, py);
                        int a = (argb >>> 24) & 0xFF;
                        double aw = w * a;     // 预乘：透明像素不贡献颜色
                        sumA += aw;
                        sumR += ((argb >> 16) & 0xFF) * aw;
                        sumG += ((argb >> 8) & 0xFF) * aw;
                        sumB += (argb & 0xFF) * aw;
                        sumW += w;
                    }
                }

                int r = 0, g = 0, b = 0, a = 0;
                if (sumW > 0) a = clamp((int) Math.round(sumA / sumW));
                if (sumA > 0) {
                    r = clamp((int) Math.round(sumR / sumA));
                    g = clamp((int) Math.round(sumG / sumA));
                    b = clamp((int) Math.round(sumB / sumA));
                }
                dst.setRGB(dx, dy, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return dst;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** 打印 alpha 概况：透明像素数 / 半透明像素数 / 最小最大 alpha。 */
    private static String alphaSummary(BufferedImage img) {
        int transparent = 0, translucent = 0, opaque = 0, min = 255, max = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (a == 0) transparent++;
                else if (a == 255) opaque++;
                else translucent++;
                if (a < min) min = a;
                if (a > max) max = a;
            }
        }
        return String.format("alpha 透明 %d / 半透明 %d / 不透明 %d（min %d, max %d）",
                transparent, translucent, opaque, min, max);
    }
}
