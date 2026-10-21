package dev.dsh.phimc.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在线页的<b>曲绘缩略图</b>：异步下载 + LRU 缓存，渲染线程一帧都不等网络。
 *
 * <h2>两段式（为什么这么拆）</h2>
 * <ol>
 *   <li><b>后台线程</b>（{@code phimc-phira-cover-*}，2 条 daemon）：
 *       {@link PhiraApi#fetchBytes} 下载原图（几十 ~ 几百 KB 的 jpg/png）→
 *       {@link CoverCodec#toPngThumbnail} 用 ImageIO <b>缩到 64px 并转 PNG</b> →
 *       结果放进 {@link #READY}（只有字节，没有 GL 操作）。</li>
 *   <li><b>渲染线程</b>（{@link #get}）：从 {@link #READY} 取走那份小 PNG，
 *       {@code NativeImage.read} + {@code TextureManager.registerTexture} 注册成贴图。
 *       每帧最多建 {@link #BUILD_PER_FRAME} 张（{@link #beginFrame} 重置预算），
 *       剩下的留到下一帧 —— 一页 20 张也不会把某一帧撑爆。</li>
 * </ol>
 *
 * <p>下载放在后台、缩放在后台、只有「注册贴图」这一小步留在渲染线程，
 * 是 1.21.x 的硬约束（{@code TextureManager} 只能主线程碰）。GPU 上传的是 64×64 小图
 * （约 16 KB/张），不像 {@link CoverTextures} 那样整张 2048×1080 塞显存。
 *
 * <h2>缓存</h2>
 * 按曲绘 URL 缓存，上限 {@link #MAX} 张（一页最多 20 行 + 预取余量），access-order LRU，
 * 挤出时 {@code destroyTexture}。失败（没图 / 解不开 / 网络错）记进 {@link #FAILED}，
 * 不重试、不每帧重下；画占位色块。
 *
 * <p>所有 {@link #get} / {@link #draw} / {@link #beginFrame} 都<b>只能在渲染线程调</b>；
 * {@link #FAILED} / {@link #READY} / {@link #INFLIGHT} 是跨线程的并发容器。
 */
public final class PhiraCovers {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 缩略图长边像素（画到界面上的行内尺寸是 20px，2 倍超采样够清楚）。 */
    private static final int THUMB = 64;
    /** 缓存上限：一页最多 20 行，留 4 张余量。 */
    private static final int MAX = 24;
    /** 每帧最多注册几张新贴图（把 GPU 上传摊到多帧）。 */
    private static final int BUILD_PER_FRAME = 2;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, daemonFactory());

    /** key = 曲绘 URL。**只在渲染线程读写**；access-order LRU。 */
    private static final Map<String, Cover> CACHE = new LinkedHashMap<>(32, 0.75f, true);
    /** 已经失败过的 URL（两个线程都会写，用并发 set）。 */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    /** 正在下载的 URL（去重，避免同一张图排队十次）。 */
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    /** 后台线程下好 + 缩好的 PNG 字节，等渲染线程取走注册。 */
    private static final Map<String, byte[]> READY = new ConcurrentHashMap<>();
    /** 贴图 id 里的自增号（同一 URL 被挤出缓存后重新加载不会撞上废贴图）。 */
    private static int serial;
    /** 本帧还剩几次注册预算。 */
    private static int budget;

    private PhiraCovers() {
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "phimc-phira-cover-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** 已注册的缩略图贴图：贴图 id + 像素尺寸（画的时候等比缩放，不拉变形）。 */
    public record Cover(Identifier id, int width, int height) {
    }

    /** 每帧开头调一次：重置「本帧最多注册 2 张」的预算。 */
    public static void beginFrame() {
        budget = BUILD_PER_FRAME;
    }

    /**
     * 取缩略图；没有就<b>顺手发起一次后台下载</b>（本次返回 {@code null}，调用方画占位块）。
     *
     * <p>渲染线程专用，<b>从不阻塞</b>：只有命中缓存 / 后台已经下好时才有返回值。
     */
    public static Cover get(String url) {
        if (url == null || url.isBlank()) return null;
        Cover hit = CACHE.get(url);
        if (hit != null) return hit;
        if (FAILED.contains(url)) return null;
        byte[] png = READY.remove(url);
        if (png != null) {
            if (budget <= 0) {
                READY.put(url, png);          // 本帧预算用完了，下一帧再注册
                return null;
            }
            budget--;
            Cover built = build(url, png);
            if (built != null) return built;
            FAILED.add(url);
            return null;
        }
        request(url);
        return null;
    }

    /**
     * 画一张缩略图：等比缩放居中；{@code cover == null} 时画占位块（浅色边框 + 深色底）。
     *
     * @param box 正方形边长（行内是 20）
     */
    public static void draw(DrawContext ctx, String url, int x, int y, int box) {
        if (box <= 0) return;
        Cover cover = get(url);
        if (cover == null) {
            ctx.fill(x, y, x + box, y + box, 0xFF2E2E38);
            ctx.fill(x, y, x + box, y + 1, 0xFF4A4A5A);
            ctx.fill(x, y + box - 1, x + box, y + box, 0xFF4A4A5A);
            ctx.fill(x, y, x + 1, y + box, 0xFF4A4A5A);
            ctx.fill(x + box - 1, y, x + box, y + box, 0xFF4A4A5A);
            return;
        }
        double s = Math.min(box / (double) cover.width(), box / (double) cover.height());
        int w = Math.max(1, (int) Math.round(cover.width() * s));
        int h = Math.max(1, (int) Math.round(cover.height() * s));
        int dx = x + (box - w) / 2;
        int dy = y + (box - h) / 2;
        RenderCompat.drawTexture(ctx, cover.id(), dx, dy, w, h, 0f, 0f,
                cover.width(), cover.height(), cover.width(), cover.height(), RenderCompat.NO_TINT);
    }

    /** 丢掉全部缩略图贴图（换世界 / 退出时可选；平时靠 LRU 收敛）。 */
    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Cover c : CACHE.values()) {
            try {
                mc.getTextureManager().destroyTexture(c.id());
            } catch (Throwable ignored) {
            }
        }
        CACHE.clear();
        READY.clear();
        FAILED.clear();
    }

    private static void request(String url) {
        if (!INFLIGHT.add(url)) return;
        POOL.execute(() -> {
            try {
                byte[] raw = PhiraApi.fetchBytes(url);
                if (raw == null || raw.length == 0) {
                    FAILED.add(url);
                    return;
                }
                byte[] png = CoverCodec.toPngThumbnail(raw, THUMB);
                if (png == null || png.length == 0) {
                    FAILED.add(url);
                    return;
                }
                READY.put(url, png);
            } catch (Throwable t) {
                FAILED.add(url);
                LOG.warn("[PhiMC] Phira 曲绘缩略图失败: {}", url, t);
            } finally {
                INFLIGHT.remove(url);
            }
        });
    }

    /** 渲染线程：小 PNG 字节 → 贴图（失败返回 null）。 */
    private static Cover build(String url, byte[] png) {
        ByteBuffer buf = MemoryUtil.memAlloc(png.length);
        NativeImage image = null;
        try {
            buf.put(png).flip();
            image = NativeImage.read(buf);
            if (image == null) return null;
            Identifier id = Identifier.of(PhiMCClient.MOD_ID,
                    "phira/" + Integer.toHexString(url.hashCode()) + "_" + (serial++));
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.getTextureManager().destroyTexture(id);
            mc.getTextureManager().registerTexture(id, RenderCompat.newTexture(id, image));
            Cover cover = new Cover(id, image.getWidth(), image.getHeight());
            CACHE.put(url, cover);
            evict();
            return cover;
        } catch (Throwable t) {
            LOG.warn("[PhiMC] Phira 曲绘注册失败: {}", url, t);
            return null;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

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
}
