package dev.dsh.phimc.client;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;

/**
 * 曲绘字节的<b>格式判定 + JPEG→PNG 转码</b>。
 *
 * <h2>为什么需要它（真 bug）</h2>
 * 曲绘最后一定要进 {@code net.minecraft.client.texture.NativeImage#read(ByteBuffer)}，
 * 而 Minecraft 自带的 stb_image 是<b>裁剪过的</b>：只保留 PNG 解码器。
 * 官方 PGR 包的曲绘是 {@code defaultImage.png}（真 PNG），一直没事；
 * <b>RPE（.pez）包的曲绘是 JPEG</b>（{@code 1939818528944948.jpg}，有的包还把 JPEG 命名成
 * {@code .png}），{@code NativeImage.read} 会直接返回 null / 抛异常 →
 * {@code CoverTextures} 只能画占位色块。
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>PNG 魔数 {@code 89 50 4E 47} → 原样返回，一个字节都不动（官方包的快路径）；</li>
 *   <li>JPEG 魔数 {@code FF D8} → {@code ImageIO.read} 解成 {@link BufferedImage}，
 *       再用 {@code ImageIO.write(img, "png", ...)} 转成 PNG 字节；</li>
 *   <li>其它格式（BMP / GIF 等）也走 {@code ImageIO}——能解就转，解不了就返回 {@code null}，
 *       调用方画占位色块。</li>
 * </ol>
 *
 * <p><b>后缀不可信</b>：实测 4 个 .pez 里有 3 个的曲绘是 JPEG，其中「推活天使ビビアンちゃん」
 * 的条目名字叫 {@code .png} 但内容是 JPEG，所以这里只看魔数，绝不看 {@code .png/.jpg}。
 *
 * <p>本类<b>只用 JDK</b>（{@code javax.imageio} + {@code java.awt.image}，都不需要显示器），
 * 不碰 Minecraft 的类——所以它能被 {@code tools/cover/CoverDecodeCheck.java} 单独编译、
 * 离线跑真实的谱面包来验证（同一条代码路径，不是复制品）。
 */
public final class CoverCodec {

    /** 曲绘字节的真实格式（按魔数判定，不看后缀）。 */
    public enum Format {
        /** 空 / 太短，什么都不是。 */
        NONE,
        /** 真 PNG（{@code 89 50 4E 47}）。 */
        PNG,
        /** JPEG / JFIF（{@code FF D8}）。 */
        JPEG,
        /** WebP（{@code RIFF....WEBP}）——<b>在线页的曲绘全是这个</b>，JDK / stb_image 都不认。 */
        WEBP,
        /** 其它（BMP / GIF / WebP …），交给 ImageIO 试一把。 */
        OTHER;

        public String label() {
            return switch (this) {
                case NONE -> "空";
                case PNG -> "PNG";
                case JPEG -> "JPEG";
                case WEBP -> "WebP";
                case OTHER -> "其它";
            };
        }
    }

    private CoverCodec() {
    }

    // ------------------------------------------------------------------
    // 魔数
    // ------------------------------------------------------------------

    /**
     * 按魔数判定格式。<b>只看内容，不看文件名后缀</b>。
     *
     * @param raw 曲绘原始字节，可为 null
     */
    public static Format detect(byte[] raw) {
        if (raw == null || raw.length < 4) return Format.NONE;
        if (isPng(raw)) return Format.PNG;
        if (isJpeg(raw)) return Format.JPEG;
        if (isWebp(raw)) return Format.WEBP;
        return Format.OTHER;
    }

    /** PNG 魔数 {@code 89 50 4E 47}（后 4 字节是 {@code 0D 0A 1A 0A}，这里只判前 4 个）。 */
    public static boolean isPng(byte[] raw) {
        return raw != null && raw.length >= 4
                && (raw[0] & 0xFF) == 0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47;
    }

    /** JPEG 魔数 {@code FF D8}（SOI 标记；后面通常是 {@code FF E0} JFIF 或 {@code FF E1} EXIF）。 */
    public static boolean isJpeg(byte[] raw) {
        return raw != null && raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8;
    }

    /**
     * WebP 魔数：{@code "RIFF"} + 4 字节长度 + {@code "WEBP"}。
     *
     * <p>Phira 在线谱面库的曲绘 100% 是这种格式（实测 8/8，VP8 有损，2048×1080），
     * 而 JDK 的 {@code ImageIO} 和 Minecraft 的 {@code NativeImage}（stb_image）都<b>没有</b>
     * WebP 解码器 —— 所以 {@link #toPng} / {@link #toPngThumbnail} 会走纯 Java 的 JWebP
     * （{@code lib/webp-0.2.0.jar}，MPL-2.0）。
     */
    public static boolean isWebp(byte[] raw) {
        return raw != null && raw.length >= 12
                && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F'
                && raw[8] == 'W' && raw[9] == 'E' && raw[10] == 'B' && raw[11] == 'P';
    }

    /** 魔数的可打印形式（日志 / 自测用），例如 {@code 89 50 4E 47}。 */
    public static String magicHex(byte[] raw) {
        if (raw == null || raw.length == 0) return "-";
        int n = Math.min(4, raw.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", raw[i] & 0xFF));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 转码
    // ------------------------------------------------------------------

    /**
     * 任意格式的曲绘字节 → <b>PNG 字节</b>（{@code NativeImage.read} 唯一吃得下的格式）。
     *
     * <p>已经是 PNG 时直接返回入参（同一个数组，零拷贝）；JPEG / 其它格式用
     * {@link ImageIO} 解 → 重新编码成 PNG。
     *
     * @param raw 曲绘原始字节
     * @return PNG 字节；解不开 / 编码失败时返回 {@code null}（调用方走占位色块）
     */
    public static byte[] toPng(byte[] raw) {
        Format format = detect(raw);
        if (format == Format.NONE) return null;
        if (format == Format.PNG) return raw;                 // 快路径：一个字节都不动
        if (format == Format.WEBP) {                          // 在线曲绘：JWebP 解（无缩放）
            BufferedImage webp = decodeWebp(raw, 0);
            return webp == null ? null : writePng(webp);
        }

        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException | RuntimeException e) {
            return null;                                      // 坏图 / 不支持的编码
        }
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;

        // PNG 编码器对 TYPE_CUSTOM（CMYK JPEG、16bit、带奇怪调色板的图都会是它）支持不全，
        // 所以先直接写，写失败再拷进一张 TYPE_INT_ARGB 里重写。
        byte[] png = writePng(src);
        if (png != null) return png;

        BufferedImage argb = toArgb(src);
        return argb == null ? null : writePng(argb);
    }

    /**
     * 任意格式的曲绘字节 → <b>缩略图 PNG</b>（长边最多 {@code maxSide} 像素）。
     *
     * <p>在线页的曲绘是 URL 上的大图（几百 KB ~ 2 MB，解码后可能 2048×2048 ≈ 16 MB 显存），
     * 一行只画 20px，所以先在<b>后台线程</b>用 ImageIO 缩小再交给渲染线程注册贴图：
     * 20 张缩略图一共几百 KB 显存，也不会在渲染线程里跑 JPEG 解码。
     *
     * <p>解码 / 缩放都在这里（纯 JDK，headless 也能跑），失败返回 {@code null}。
     * 已经是 PNG 也照样重编码 —— 这里的目的是「变小」，不是「转格式」。
     *
     * @param raw     原始图片字节
     * @param maxSide 长边上限（≤0 时退化为 {@link #toPng}）
     */
    public static byte[] toPngThumbnail(byte[] raw, int maxSide) {
        if (maxSide <= 0) return toPng(raw);
        Format format = detect(raw);
        if (format == Format.NONE) return null;
        if (format == Format.WEBP) {
            // JWebP 支持「解码时就缩到目标尺寸」，省掉一次 2048×1080 的全尺寸拷贝
            BufferedImage webp = decodeWebp(raw, maxSide);
            return webp == null ? null : writePng(webp);
        }
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;

        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, maxSide / (double) Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));

        BufferedImage out;
        Graphics2D g = null;
        try {
            // TYPE_INT_ARGB：PNG 编码器对它支持最全（TYPE_CUSTOM 的 CMYK / 16bit 图会写失败）
            out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
            g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, tw, th, null);
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (g != null) g.dispose();
        }
        byte[] png = writePng(out);
        if (png != null) return png;
        // 极少数图（带奇怪色彩模型）在缩小时会抛，退回「先转 ARGB 再缩」
        BufferedImage argb = toArgb(src);
        if (argb == null) return null;
        try {
            out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
            g = out.createGraphics();
            g.drawImage(argb, 0, 0, tw, th, null);
            g.dispose();
            g = null;
            return writePng(out);
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (g != null) g.dispose();
        }
    }

    /**
     * WebP → {@link BufferedImage}（纯 Java，JWebP）。
     *
     * <p>Phira 的曲绘是 2048×1080 的 VP8 有损 WebP，全尺寸解码一次约 150ms、临时数组 8.8MB，
     * 所以缩略图这条路径<b>让解码器直接缩到 {@code maxSide}</b>
     * （{@link WebPImageLoadOptions#requestedWidth()} = {@code requestedHeight()} = maxSide、
     * 保持比例），解码出来就是 64px 级的数组，省内存也快得多。
     *
     * <p>{@code maxSide <= 0} 表示不缩放（给包内的真曲绘走 {@link #toPng} 用）。
     * 失败 / 没有 JWebP 类（例如离线自测工具没带那个 jar）一律返回 {@code null}。
     */
    private static BufferedImage decodeWebp(byte[] raw, int maxSide) {
        try {
            WebPImageLoadOptions options = maxSide > 0
                    ? new WebPImageLoadOptions(maxSide, maxSide, true, true)
                    : WebPImageLoadOptions.DEFAULT;
            WebPImage image = WebPImage.read(new ByteArrayInputStream(raw), options);
            int w = image.getWidth();
            int h = image.getHeight();
            if (w <= 0 || h <= 0) return null;
            int[] argb = image.getFirstFrame().getArgbArray();
            if (argb == null || argb.length < w * h) return null;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, argb, 0, w);
            return out;
        } catch (Throwable t) {
            // NoClassDefFoundError（没带 JWebP）/ WebPException（坏图）都当「解不开」
            return null;
        }
    }

    private static byte[] writePng(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        try {
            if (!ImageIO.write(img, "png", out)) return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return out.toByteArray();
    }

    /** 拷进 {@code TYPE_INT_ARGB}（再写 PNG 就一定成功）；拷贝失败返回 null。 */
    private static BufferedImage toArgb(BufferedImage src) {
        try {
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
