package dev.dsh.phimc.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * hold.png 三段切割的 headless 验收（不需要 Minecraft）。
 *
 * <p>输入是<b>用户自己的</b> Phigros/Phira 资源包 zip（不进仓库）；读包内 {@code info.yml} 的
 * {@code holdAtlas} 与 {@code hold.png} 的真实像素尺寸，然后：
 * <ol>
 *   <li>按 prpr {@code core/resource.rs:168-182} 的公式算「尾巴 / 身体 / 头」各自取的像素区域；</li>
 *   <li>用我们渲染实际调用的 {@link HoldLayout} 再算一遍，逐项对比（区域必须完全一致）；</li>
 *   <li>按 prpr {@code note.rs:324/342} 的 {@code hf.y} 公式算头/尾的绘制高度，
 *       和 {@link HoldLayout#capHalfHeight} / {@link HoldLayout#capDestHeight} 对比；</li>
 *   <li>把「改前（头/尾整块画在身体里面）」和「改后（整块贴在身体外侧）」的几何摆出来，
 *       说明长度差 = 头高 + 尾高。</li>
 * </ol>
 *
 * <p>用法：{@code java ... dev.dsh.phimc.core.HoldAtlasCheck <资源包.zip>}
 */
public final class HoldAtlasCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: HoldAtlasCheck <respack.zip>");
            return;
        }
        Path zip = Path.of(args[0]);
        String info;
        byte[] png;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            info = new String(read(zf, "info.yml"), StandardCharsets.UTF_8);
            png = read(zf, "hold.png");
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int texW = img.getWidth();
        int texH = img.getHeight();

        Matcher m = Pattern.compile("(?m)^\\s*holdAtlas\\s*:\\s*\\[?\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]?").matcher(info);
        int tailPx = 0;
        int headPx = 0;
        if (m.find()) {
            tailPx = Integer.parseInt(m.group(1));
            headPx = Integer.parseInt(m.group(2));
        }
        System.out.printf("资源包: %s%n", zip.getFileName());
        System.out.printf("hold.png: %d x %d px (%d bytes)%n", texW, texH, png.length);
        System.out.printf("info.yml holdAtlas = [%d, %d]%n", tailPx, headPx);
        System.out.printf("info.yml holdRepeat = %s, holdKeepHead = %s, holdCompact = %s%n",
                bool(info, "holdRepeat"), bool(info, "holdKeepHead"), bool(info, "holdCompact"));

        // ---- Phira 公式（照搬 resource.rs:168-182，像素坐标，0 = 图片顶行） ----
        System.out.println();
        System.out.println("== Phira 公式（resource.rs:168-182） ==");
        int pTailY = 0;
        int pTailH = tailPx;
        int pBodyY = tailPx;
        int pBodyH = texH - tailPx - headPx;
        int pHeadY = texH - headPx;
        int pHeadH = headPx;
        printRegion("tail", 0, pTailY, texW, pTailH);
        printRegion("body", 0, pBodyY, texW, pBodyH);
        printRegion("head", 0, pHeadY, texW, pHeadH);
        System.out.printf("hold_ratio = texH / texW = %d / %d = %.9f%n", texH, texW, (double) texH / texW);

        // ---- 我们的实现（渲染实际调用的那份代码） ----
        HoldLayout layout = HoldLayout.of(texW, texH, tailPx, headPx);
        System.out.println();
        System.out.println("== 我们的实现（HoldLayout，渲染实际调用） ==");
        printRegion("tail", 0, layout.tailSrcY(), texW, layout.tailPx());
        printRegion("body", 0, layout.bodySrcY(), texW, layout.bodyPx());
        printRegion("head", 0, layout.headSrcY(), texW, layout.headPx());

        boolean same = pTailY == layout.tailSrcY() && pTailH == layout.tailPx()
                && pBodyY == layout.bodySrcY() && pBodyH == layout.bodyPx()
                && pHeadY == layout.headSrcY() && pHeadH == layout.headPx();
        System.out.printf("源区域逐项一致: %s（tail/body/head 的 y/h 全等）%n", same ? "是" : "否");
        System.out.printf("覆盖检查: tail[0..%d) + body[%d..%d) + head[%d..%d) = [0..%d)，无重叠/无空洞: %s%n",
                layout.tailPx(), layout.bodySrcY(), layout.bodySrcY() + layout.bodyPx(),
                layout.headSrcY(), layout.headSrcY() + layout.headPx(), texH,
                layout.tailPx() + layout.bodyPx() + layout.headPx() == texH);

        // ---- 头/尾绘制高度：Phira 公式 vs 我们的 ----
        System.out.println();
        System.out.println("== 头/尾绘制高度（dest_size.y = 2 * hf.y，像素） ==");
        System.out.println("  屏幕宽  音符宽 noteW   Phira 2*hf.y(head)  我们的 capDestHeight(head)  |  tail");
        for (int w : new int[]{854, 1280, 1920}) {
            int noteW = Math.max(1, (int) Math.round(w * 0.13175));
            double scale = noteW / 2.0;
            double ratio = (double) texH / texW;
            double phHead = 2.0 * ((double) headPx / texH) * scale * ratio;
            double phTail = 2.0 * ((double) tailPx / texH) * scale * ratio;
            int ourHead = layout.capDestHeight(noteW, layout.headPx());
            int ourTail = layout.capDestHeight(noteW, layout.tailPx());
            System.out.printf("  %4d      %3d            %10.6f          %3d (Δ=%+.6f)   | %10.6f -> %d (Δ=%+.6f)%n",
                    w, noteW, phHead, ourHead, ourHead - phHead, phTail, ourTail, ourTail - phTail);
            // 简化式：capPx * noteW / texW，验证和 hf.y 公式代数等价
            double simple = (double) headPx * noteW / texW;
            if (Math.abs(simple - phHead) > 1e-9) {
                System.out.printf("    ✗ 简化式与 hf.y 公式不等: %.9f vs %.9f%n", simple, phHead);
            }
        }
        System.out.printf("body 平铺一圈高度（holdRepeat 时）= noteW * bodyPx / texW，1280 宽下 = %d px%n",
                layout.bodyTileHeight(Math.max(1, (int) Math.round(1280 * 0.13175))));

        // ---- 改前 / 改后的几何（局部坐标，+y = 屏幕向下，0 = 头端点） ----
        System.out.println();
        System.out.println("== 调整前后：头/尾在身体哪一侧（示例：身体长 L=100px，1280 宽） ==");
        int noteW = Math.max(1, (int) Math.round(1280 * 0.13175));
        int headH = layout.capDestHeight(noteW, layout.headPx());
        int tailH = layout.capDestHeight(noteW, layout.tailPx());
        int L = 100;
        System.out.println("改前(旧实现): 身体 y[-" + L + "..0]，头 y[-" + headH + "..0]（压在身体里），"
                + "尾 y[-" + L + ".." + (-(L) + tailH) + "]（压在身体里）");
        System.out.println("             → 可见总长 = L = " + L + "px，头/尾各吃掉 " + headH + " / " + tailH + "px");
        System.out.println("改后(Phira) : 身体 y[-" + L + "..0]，头 y[0.." + headH + "]（身体外侧），"
                + "尾 y[" + (-(L + tailH)) + ".." + (-L) + "]（身体外侧）");
        System.out.println("             → 可见总长 = L + headH + tailH = " + (L + headH + tailH) + "px");
    }

    private static void printRegion(String name, int x, int y, int w, int h) {
        System.out.printf("  %-4s: x=%d y=%d w=%d h=%d   (贴图行 [%d..%d))%n",
                name, x, y, w, h, y, y + h);
    }

    private static boolean bool(String info, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + "\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(info);
        return m.find() && m.group(1).equalsIgnoreCase("true");
    }

    private static byte[] read(ZipFile zf, String name) throws Exception {
        ZipEntry e = zf.getEntry(name);
        if (e == null) {
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry cand = it.nextElement();
                if (cand.getName().endsWith("/" + name)) {
                    e = cand;
                    break;
                }
            }
        }
        if (e == null) throw new IllegalStateException("zip 里没有 " + name);
        try (InputStream in = zf.getInputStream(e)) {
            return in.readAllBytes();
        }
    }
}
