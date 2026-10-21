package dev.dsh.phimc.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判定线的<b>贴图</b>（RPE 的 {@code Texture: xxx.png}）→ 动态贴图。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code prpr} 里只有 {@code Texture == "line.png"} 的判定线才画成一条横线
 * （{@code core/line.rs:248-253}）；其它贴图线画的是<b>那张图本身</b>
 * （{@code line.rs:254-290} 的 {@code JudgeLineKind::Texture}）。
 * 我们以前对所有非 {@code line.png} 的线都「不画」（为了不出现横贯屏幕的假亮条），
 * 于是这批「特效谱」的画面就没了 —— 曲库里实测：
 * <ul>
 *   <li>{@code 78805_enchanted love}：287 条线里 <b>250 条</b>是贴图线（heart / Circle / 字母 …）；</li>
 *   <li>{@code 48273_Undertale}：177 条线里 <b>129 条</b>（spear / bone / flowey / sans …）。</li>
 * </ul>
 * 这些线就是特效谱的全部画面，不画等于没跑起来。
 *
 * <h2>画法（照 {@code line.rs:254-272}）</h2>
 * <pre>
 * let hf = vec2(texture.width(), texture.height());        // 像素尺寸
 * draw_texture_ex(tex, -hf.x/2., -hf.y/2., color,
 *     DrawTextureParams { dest_size: Some(hf), flip_y: true, .. })
 * </pre>
 * 也就是：在以判定线原点为中心、已按线角度旋转、已按 {@code scaleX/scaleY} 缩放的坐标系里，
 * 画一个<b>尺寸 = 图片像素数 × scale</b> 的矩形（居中）。
 * {@code scale} 的单位换算见 {@code rpe.rs:695-696}：非 {@code line.png} 的 factor = {@code 2/RPE_WIDTH}，
 * 也就是「1 像素 = 2/1350 个世界单位」，而 1 世界单位 = 半个屏宽 ⇒
 * {@code 像素尺寸 = 图片像素 * scale * 屏宽/2}。
 *
 * <h2>缓存</h2>
 * <p>和 {@link CoverTextures} 一个套路：{@code NativeImage} + {@code NativeImageBackedTexture} 注册到
 * {@code TextureManager}，按「谱面包 + 文件名」缓存；换曲目时整批销毁（{@link #beginFor}）。
 * 上限 {@link #MAX}，超了淘汰最久没用的。失败也缓存，不会每帧重读 zip。
 */
public final class LineTextures {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");

    /** 一张已注册的贴图：id + 原始像素尺寸。 */
    public record Tex(Identifier id, int width, int height) {
    }

    /**
     * 同时保留多少张贴图。
     *
     * <p><b>故意设得很大</b>：贴图线是「每帧都要画」的，中途淘汰任何一张都会导致它下一帧又被
     * 重新解一次 zip + 重新解码（曲线上等于每帧重解上百张图，直接卡死）。
     * 曲库里图名最多的是 {@code enchanted love}（约 103 个），单张大多是几十 KB 的小图，
     * 全留也就几十 MB。真正的回收时机是「换曲目」（{@link #beginFor}）——那时整批销毁。
     * 这里的上限只是防止某个离谱谱面把显存吃光的保险丝。
     */
    private static final int MAX = 512;

    /**
     * <b>每帧最多新建几张</b>（平台优化，{@code prpr} 没有这个概念——它开谱前就把图全载进显存了）。
     *
     * <p>为什么要限：解码一张 PNG（{@code NativeImage.read} 走 stb_image）要几毫秒，
     * {@code enchanted love} 有 100 个不同的图名。若在第一次进谱的那一帧全解，
     * 就是一次性 100 × 几 ms ≈ 几百毫秒的卡顿——用户体感又是「一打开就卡」。
     * 按每帧几张慢慢来，代价是**头几帧少画几条装饰线**（肉眼几乎不可见），
     * 但没有任何一帧会超预算。失败的名字不进预算（它们本来就不用再试）。
     */
    private static final int NEW_PER_FRAME = 6;

    /** 本帧还剩几次「新建贴图」的额度。 */
    private static int budget = NEW_PER_FRAME;

    /** 每帧开头调一次（{@code PlayScreen.render}）。 */
    public static void beginFrame() {
        budget = NEW_PER_FRAME;
    }


    /** key = 谱面包路径 + "\u0000" + 文件名（小写）；access-order LRU。 */
    private static final Map<String, Tex> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    /** 确认取不到的名字（重复请求不再重读 zip）。 */
    private static final Set<String> FAILED = new LinkedHashSet<>();
    /** 当前贴图集合属于哪个谱面包；换曲目时整批换掉。 */
    private static String sessionKey = "";
    private static int serial;

    private LineTextures() {
    }

    /**
     * 切到某一首曲子：包变了就把上一首的贴图全部销毁。
     *
     * <p>只能在渲染线程调用（要碰 {@code TextureManager}）。
     */
    public static void beginFor(SongEntry entry) {
        String key = entry == null || entry.path == null ? "" : entry.path.toAbsolutePath().toString();
        if (key.equals(sessionKey)) return;
        clear();
        sessionKey = key;
    }

    /** 丢掉全部贴图（换曲目 / 退出游玩界面）。 */
    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Tex t : CACHE.values()) {
            try {
                mc.getTextureManager().destroyTexture(t.id());
            } catch (Throwable ignored) {
            }
        }
        CACHE.clear();
        FAILED.clear();
    }

    /**
     * 取某一张贴图（必要时同步解码一次）。取不到返回 {@code null}（调用方就跳过这条线）。
     *
     * <p>只能在渲染线程调用。
     */
    public static Tex get(SongEntry entry, String name) {
        if (entry == null || name == null || name.isEmpty()) return null;
        String key = sessionKey + "\u0000" + name.toLowerCase(java.util.Locale.ROOT);
        Tex hit = CACHE.get(key);
        if (hit != null) return hit;
        if (FAILED.contains(key)) return null;
        // 本帧的新建额度用完了 → 这条线这一帧先不画，下一帧再解（见 NEW_PER_FRAME）
        if (budget <= 0) return null;
        budget--;
        try {
            byte[] raw = entry.asset(name);
            if (raw == null || raw.length == 0) {
                FAILED.add(key);
                return null;
            }
            NativeImage image = readImage(raw);
            if (image == null) {
                FAILED.add(key);
                return null;
            }
            Identifier id = Identifier.of(PhiMCClient.MOD_ID,
                    "linetex/" + Integer.toHexString(key.hashCode()) + "_" + (serial++));
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.getTextureManager().destroyTexture(id);
            // **和曲绘完全相同的那条注册路径**（RenderCompat.newTexture + registerTexture）。
            // 之前这里用的是 newTextureLinear —— 1.21.1 分支会额外调
            // AbstractTexture.setFilter(true, false)，那是本文件里唯一一处「曲绘没验证过」的差异。
            // 先退回已验证路径排除变量；双线性过滤的收益（大图缩小时更干净）等绘制确认无误再单独加。
            mc.getTextureManager().registerTexture(id, RenderCompat.newTexture(id, image));
            Tex tex = new Tex(id, image.getWidth(), image.getHeight());
            CACHE.put(key, tex);
            evict();
            // 每张贴图只打一行日志：图名 / 解码尺寸 / 像素格式 / 字节数 / **alpha 统计** / 标识符。
            // alpha 统计是关键：这些特效图大多是「大面积全透明 + 中间一点光」，
            // 如果 MC 解出来 alpha 被吃掉（平均 alpha 接近 255、透明像素数为 0），
            // 那画出来必然是一整块纯色 —— 一眼就能和「图本来就这样」区分开。
            LOG.info("[PhiMC] 判定线贴图 {} → {}×{}，格式 {}，{} 字节，像素{}，id={}",
                    name, image.getWidth(), image.getHeight(), image.getFormat(),
                    raw.length, alphaStats(image), id);
            return tex;
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 判定线贴图读取失败: {} / {}", name, t.toString());
            FAILED.add(key);
            return null;
        }
    }

    /**
     * MC 自己解出来的那张图的 alpha 统计（抽样 64×64 个点）：
     * {@code 平均alpha/透明像素占比/不同颜色数}。
     *
     * <p>用 {@link NativeImage#getColor(int,int)} 读，也就是**真正被上传到 GL 的那份像素**，
     * 不是 ImageIO 那份 —— 两者不一致就说明问题出在解码环节。
     */
    private static String alphaStats(NativeImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return "[空图]";
        int stepX = Math.max(1, w / 64);
        int stepY = Math.max(1, h / 64);
        long sum = 0;
        int n = 0;
        int zero = 0;
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                // NativeImage 的取色方法在 **1.21.2** 换代：getColor(int,int) 变成 private，
                // 公开的是 getColorArgb(int,int)（逐版本 javap 核对过：
                //   1.20 ~ 1.21.1 → getColor public、无 getColorArgb
                //   1.21.2 ~ 1.21.11 → getColor private、getColorArgb public）
                // Stonecutter 的基线是最高版本，所以「新 API」那一支不注释，「老 API」用 /*...*///? 包起来。
                //? if >=1.21.2 {
                int argb = image.getColorArgb(x, y);
                //?} else {
                /*int argb = image.getColor(x, y);
                *///?}
                int a = (argb >>> 24) & 0xFF;
                sum += a;
                n++;
                if (a == 0) zero++;
                colors.add(argb);
            }
        }
        if (n == 0) return "[空图]";
        return String.format(java.util.Locale.ROOT, "[平均alpha=%.0f 透明点=%.0f%% 色数=%d]",
                sum / (double) n, zero * 100.0 / n, colors.size());
    }

    /** 和 {@link CoverTextures} 同一套解码：只看魔数（{@code .png} 也可能装着 JPEG）。 */
    private static NativeImage readImage(byte[] bytes) throws Exception {
        byte[] png = CoverCodec.toPng(bytes);
        if (png == null || png.length == 0) return null;
        ByteBuffer buf = MemoryUtil.memAlloc(png.length);
        try {
            buf.put(png).flip();
            return NativeImage.read(buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static void evict() {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<String> keys = new ArrayList<>(CACHE.keySet());
        int drop = CACHE.size() - MAX;
        for (int i = 0; i < drop && i < keys.size(); i++) {
            Tex old = CACHE.remove(keys.get(i));
            if (old == null) continue;
            try {
                mc.getTextureManager().destroyTexture(old.id());
            } catch (Throwable ignored) {
            }
        }
    }
}
