package dev.dsh.phimc.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 曲绘（谱面包里的意象图）→ 动态贴图。
 *
 * <p><b>按需加载</b>：只有「要画的那一首」才解码（选曲界面 = 当前选中项，结算界面 = 当前曲目），
 * {@code init()} 里绝不批量解码。解码结果按谱面包路径缓存，缓存上限 {@link #MAX} 张，
 * 超了就 <b>销毁最久没用的贴图</b>，避免把一堆 2048×1080 的图（每张解码后约 8.8 MB）留在显存里。
 *
 * <p><b>注册方式照 {@code respack/ResPack.java:227-253}</b>：
 * {@code NativeImage} + {@link NativeImageBackedTexture} 注册到 {@code TextureManager}，
 * 用 {@code Identifier} 画；解码必须走 {@code NativeImage.read(ByteBuffer)} ——
 * {@code NativeImage.read(byte[])} 会把整张压缩图塞进 LWJGL 的 {@code MemoryStack}（默认 64 KB），
 * 真实曲绘（2.6 MB PNG）必然 {@code OutOfMemoryError: Out of stack space.}。
 *
 * <p>失败（包内没有图 / 解不开 / 格式不支持）一律返回 {@code null}，调用方画占位色块。
 * 失败结果也缓存，不会每帧重试。
 *
 * <p><b>格式</b>：不走后缀判断，走魔数（{@link CoverCodec}）。官方包的 {@code defaultImage.png}
 * 是真 PNG，直接进 {@code NativeImage.read}；RPE（.pez）包的曲绘是 JPEG（有时还叫 {@code .png}），
 * 必须先转成 PNG——{@code NativeImage.read} 的 stb_image 在 Minecraft 里只保留了 PNG 解码器。
 */
public final class CoverTextures {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 一张已注册的曲绘：贴图 id + 原始像素尺寸（画的时候要按它算比例，不能拉变形）。 */
    public record Cover(Identifier id, int width, int height) {
    }

    /**
     * 缓存上限。选曲界面只要 1 张（当前选中项），结算界面 1 张（当前曲目），
     * 两者常常是同一首（缓存命中），所以 2 张够用。
     */
    private static final int MAX = 2;

    /** key = 谱面包绝对路径；access-order LRU。 */
    private static final Map<String, Cover> CACHE = new LinkedHashMap<>(4, 0.75f, true);
    /** 已经确认「没有曲绘」的包，避免每帧重读 zip。 */
    private static final Set<String> FAILED = new LinkedHashSet<>();
    /**
     * 「当时还不知道曲绘叫什么名字」的包：info.txt 没写 {@code Picture:} 时，文件名只可能在谱面
     * {@code META.background} 里，而那是 {@link SongMeta} 后台读的。这类失败不并进 {@link #FAILED}，
     * 等 META 到位（{@link SongEntry#pictureName()} 不再是空串）后允许<b>重试一次</b>。
     */
    private static final Set<String> NO_PICTURE = new LinkedHashSet<>();
    /** 纹理 id 里的自增号：同一个包被挤出缓存后重新加载时换一个 id，不会撞上废纹理。 */
    private static int serial;

    private CoverTextures() {
    }

    /**
     * 取曲绘贴图，<b>必要时同步解码一次</b>（之后走缓存）。没有 / 解不开 → {@code null}。
     *
     * <p>只能在客户端线程（渲染线程）调用：注册贴图要碰 {@code TextureManager}。
     */
    public static Cover get(SongEntry entry) {
        if (entry == null || entry.path == null) return null;
        String key = entry.path.toAbsolutePath().toString();
        // info.txt 没写 Picture: 的包：META 还没读到时先记在 NO_PICTURE 里，等读到 background 再重试一次
        if (NO_PICTURE.contains(key)) {
            if (entry.pictureName().isEmpty()) return null;   // 还是不知道图名，等 SongMeta
            NO_PICTURE.remove(key);
        }
        if (FAILED.contains(key)) return null;
        Cover hit = CACHE.get(key);
        if (hit != null) return hit;
        try {
            byte[] raw = entry.cover();
            if (raw == null || raw.length == 0) {
                if (entry.pictureName().isEmpty()) NO_PICTURE.add(key);   // 名字还不知道 → 等 META
                else FAILED.add(key);                                    // 知道名字也找不到 → 真没有
                return null;
            }
            NativeImage image = readImage(raw);
            if (image == null) {
                FAILED.add(key);
                return null;
            }
            Identifier id = Identifier.of(PhiMCClient.MOD_ID,
                    "cover/" + Integer.toHexString(key.hashCode()) + "_" + (serial++));
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.getTextureManager().destroyTexture(id);       // 上一轮同一路径可能还挂着
            mc.getTextureManager().registerTexture(id, RenderCompat.newTexture(id, image));
            Cover cover = new Cover(id, image.getWidth(), image.getHeight());
            CACHE.put(key, cover);
            evict();
            return cover;
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 曲绘读取失败: {}", entry.path, t);
            FAILED.add(key);
            return null;
        }
    }

    /**
     * 把曲绘画进一个矩形框：<b>等比缩放、居中、不拉伸</b>；{@code cover == null} 时画占位色块。
     *
     * @param alpha 0~1，结算界面用 0.72 之类做「半透明」，1 = 原样
     */
    public static void draw(DrawContext ctx, Cover cover, int boxX, int boxY, int boxW, int boxH, float alpha) {
        if (boxW <= 0 || boxH <= 0) return;
        if (cover == null) {
            // 占位色块（加载失败 / 谱面包里本来就没有曲绘）
            int c = withAlpha(0xFF3A3A46, alpha);
            ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, c);
            ctx.fill(boxX, boxY, boxX + boxW, boxY + 1, withAlpha(0xFF5A5A6A, alpha));
            ctx.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, withAlpha(0xFF5A5A6A, alpha));
            ctx.fill(boxX, boxY, boxX + 1, boxY + boxH, withAlpha(0xFF5A5A6A, alpha));
            ctx.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, withAlpha(0xFF5A5A6A, alpha));
            return;
        }
        float a = Math.max(0f, Math.min(1f, alpha));
        if (a <= 0.004f) return;
        double s = Math.min(boxW / (double) cover.width(), boxH / (double) cover.height());
        int w = Math.max(1, (int) Math.round(cover.width() * s));
        int h = Math.max(1, (int) Math.round(cover.height() * s));
        int x = boxX + (boxW - w) / 2;
        int y = boxY + (boxH - h) / 2;
        // 淡出：1.21.1 用 setShaderColor、1.21.11 用 drawTexture 的颜色参数（见 RenderCompat）。
        RenderCompat.drawTexture(ctx, cover.id(), x, y, w, h, 0f, 0f,
                cover.width(), cover.height(), cover.width(), cover.height(), a, 1.0, 1.0, 1.0);
    }

    /** 丢掉全部曲绘贴图（换世界 / 退出时可选调用；平时靠 LRU 自己收敛）。 */
    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Cover c : CACHE.values()) {
            try {
                mc.getTextureManager().destroyTexture(c.id());
            } catch (Throwable ignored) {
            }
        }
        CACHE.clear();
        FAILED.clear();
        NO_PICTURE.clear();
    }

    /** 超过上限就销毁最久没用的那张（连同缓存项）。 */
    private static void evict() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Iterator<Map.Entry<String, Cover>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > MAX && it.hasNext()) {
            Cover old = it.next().getValue();
            try {
                mc.getTextureManager().destroyTexture(old.id());
            } catch (Throwable ignored) {
            }
            it.remove();
        }
    }

    /**
     * 曲绘字节 → {@link NativeImage}。
     *
     * <p><b>先判格式，再解码</b>（真 bug 的修法）：{@code NativeImage.read} 里的 stb_image
     * 被 Minecraft 裁成了「只认 PNG」，而 RPE（.pez）包的曲绘是 JPEG
     * （{@code 1939818528944948.jpg}，有的包还把 JPEG 起名叫 {@code .png}），
     * 直接喂进去必然失败 → 占位色块。所以：
     * <ul>
     *   <li>PNG 魔数 {@code 89 50 4E 47} → 原样进 {@code NativeImage.read}；</li>
     *   <li>JPEG 魔数 {@code FF D8} / 其它格式 → {@link CoverCodec#toPng} 用
     *       {@code ImageIO} 先转成 PNG 字节；</li>
     *   <li>转不出来（坏图）→ {@code null}，调用方画占位色块。</li>
     * </ul>
     * 格式判定与转码本身在 {@link CoverCodec} 里（纯 JDK，可离线自测）。
     *
     * <p><b>只能走 {@code read(ByteBuffer)}，不能走 {@code read(byte[])}</b>——原因见
     * {@code respack/ResPack.java:236-253}：{@code read(byte[])} 会把整段压缩数据复制到
     * LWJGL 的 {@code MemoryStack}（默认 64 KB），真实曲绘（Verruckt 的 defaultImage.png = 2.6 MB）
     * 一定炸 {@code OutOfMemoryError}。这里用 {@code memAlloc} 的直接缓冲绕开 MemoryStack，
     * 并且<b>无论成功失败都在 finally 里 memFree 释放</b>（JPEG 转码后会多一份 PNG 字节，
     * 那份是堆上的 byte[]，由 GC 管；直接缓冲只有这一个，必须自己释放）。
     */
    private static NativeImage readImage(byte[] bytes) throws Exception {
        CoverCodec.Format format = CoverCodec.detect(bytes);
        byte[] png = CoverCodec.toPng(bytes);
        if (png == null || png.length == 0) {
            LOG.warn("[PhiMC] 曲绘解码失败：格式 {} / 魔数 {} / {} 字节",
                    format.label(), CoverCodec.magicHex(bytes), bytes == null ? 0 : bytes.length);
            return null;
        }
        if (format != CoverCodec.Format.PNG) {
            LOG.info("[PhiMC] 曲绘转码：{}（魔数 {}）→ PNG {} 字节",
                    format.label(), CoverCodec.magicHex(bytes), png.length);
        }
        ByteBuffer buf = MemoryUtil.memAlloc(png.length);
        try {
            buf.put(png).flip();                 // position=0, limit=length
            return NativeImage.read(buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static int withAlpha(int argb, float alpha) {
        int a = (int) Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
