package dev.dsh.phimc.client.respack;

import dev.dsh.phimc.client.ConfigDir;
import dev.dsh.phimc.client.RenderCompat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 用户自己的 Phira/Phigros 资源包（zip）读取器。
 *
 * <p><b>不打包任何资源包内容</b>：只在运行时读 {@code config/phimc/respack/*.zip}，
 * 用户自己放进去的包才有内容可读（版权）。
 *
 * <p>包结构照 prpr {@code core/resource.rs:209-299}（{@code ResourcePack::load}）：
 * <ul>
 *   <li>{@code info.yml}：{@code name} / {@code author} / {@code hitFx: [cols, rows]} /
 *       {@code holdAtlas: [tailPx, headPx]}（{@code holdAtlasMH} 同理）/ 可选的
 *       {@code holdRepeat}、{@code holdKeepHead}、{@code holdCompact}、{@code hitFxScale}、
 *       {@code colorPerfect}/{@code colorGood}；</li>
 *   <li>贴图：{@code click.png}、{@code hold.png}、{@code flick.png}、{@code drag.png}
 *       （+ {@code _mh} 变体，本 mod 不用）、{@code hit_fx.png}；</li>
 *   <li>打击音：{@code click/drag/flick} + {@code .ogg}/{@code .wav}/{@code .mp3}
 *       （resource.rs:257-287 {@code load_clip!}）。</li>
 * </ul>
 *
 * <p>贴图注册成动态纹理（{@code NativeImage} → {@link NativeImageBackedTexture}），渲染时用
 * {@code DrawContext.drawTexture} 画；贴图缺哪张，哪张就退回纯色矩形（见 {@code PlayScreen}）。
 */
public final class ResPack {

    private static final Logger LOG = LoggerFactory.getLogger("phimc");
    /** 纹理 id 前缀：{@code phimc:respack/...}。 */
    private static final String NS = "phimc";
    /** 贴图槽位（= 文件名去扩展名）。 */
    public static final String[] SLOTS = {"click", "drag", "flick", "hold", "line"};
    /** 资源包目录名（config/phimc/respack）。 */
    private static final String DIR_NAME = "respack";

    /** 一张已注册的纹理：id + 原始像素尺寸（画的时候要按它算比例）。 */
    public record Tex(Identifier id, int width, int height) {
        public float aspect() {
            return height <= 0 ? 1f : (float) height / (float) width;
        }
    }

    private static ResPack current;

    private final String fileName;
    private final Map<String, Tex> textures = new HashMap<>();
    private final Map<String, byte[]> sounds = new HashMap<>();
    private String name = "";
    private String author = "";
    /**
     * hold.png 里「尾巴」「头」各占多少像素（info.yml 的 {@code holdAtlas: [a, b]}）。
     *
     * <p>归属照 prpr {@code core/resource.rs:168-182}：<b>a = tailPx（贴图顶部那段）、
     * b = headPx（贴图底部那段）</b>，中间是身体 —— 不是「头部在前」。官方包
     * {@code holdAtlas: [50, 50]} 两个数相等看不出区别，但别的包会不同。
     */
    private int holdTailPx;
    private int holdHeadPx;
    /**
     * info.yml 的三个 hold 开关，照 prpr {@code ResPackInfo}（{@code resource.rs:77-82}，缺省全是 false）：
     * {@code holdRepeat}（身体平铺而不是拉伸）、{@code holdKeepHead}（命中后头不消失）、
     * {@code holdCompact}（头/尾以端点为中点，而不是整块挂在端点外侧）。
     */
    private boolean holdRepeat;
    private boolean holdKeepHead;
    private boolean holdCompact;
    private int hitFxCols = 1;
    private int hitFxRows = 1;

    private ResPack(String fileName) {
        this.fileName = fileName;
    }

    // ------------------------------------------------------------------
    // 目录 / 列表
    // ------------------------------------------------------------------

    /** {@code config/phimc/respack/}（不存在就建）。 */
    public static Path dir() {
        Path d = ConfigDir.modDir(FabricLoader.getInstance().getConfigDir()).resolve(DIR_NAME);
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
        }
        return d;
    }

    /** 目录里所有 zip 的文件名（排序，稳定）。 */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        Path d = dir();
        if (!Files.isDirectory(d)) return out;
        try (var stream = Files.list(d)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (IOException e) {
            LOG.warn("[PhiMC] 读 respack 目录失败: {}", d, e);
        }
        return out;
    }

    /** 当前生效的资源包，没有就 null（纯色矩形）。 */
    public static synchronized ResPack current() {
        return current;
    }

    /**
     * 选中 / 切换资源包；{@code fileName} 为 null 或空串表示不用资源包。
     *
     * @return 真正加载成功的包，失败/清空时为 null
     */
    public static synchronized ResPack select(String fileName) {
        unload();
        if (fileName == null || fileName.isBlank()) return null;
        Path zip = dir().resolve(fileName);
        if (!Files.isRegularFile(zip)) {
            LOG.warn("[PhiMC] 资源包不存在: {}", zip);
            return null;
        }
        ResPack pack = load(zip, fileName);
        current = pack;
        return pack;
    }

    /** 丢掉当前包（连同已注册的纹理）。 */
    public static synchronized void unload() {
        if (current != null) {
            current.destroy();
            current = null;
        }
    }

    public String fileName() {
        return fileName;
    }

    public String displayName() {
        return name == null || name.isBlank() ? fileName : name;
    }

    public String author() {
        return author == null ? "" : author;
    }

    public int holdTailPx() {
        return holdTailPx;
    }

    public int holdHeadPx() {
        return holdHeadPx;
    }

    /** {@code holdRepeat}：身体按贴图自然比例平铺（false = 拉伸）。 */
    public boolean holdRepeat() {
        return holdRepeat;
    }

    /** {@code holdKeepHead}：命中后仍画头（false = 命中后只留身体 + 尾巴）。 */
    public boolean holdKeepHead() {
        return holdKeepHead;
    }

    /** {@code holdCompact}：头/尾以端点为中点（false = 整块贴在端点外侧）。 */
    public boolean holdCompact() {
        return holdCompact;
    }

    public int hitFxCols() {
        return hitFxCols;
    }

    public int hitFxRows() {
        return hitFxRows;
    }

    /** 取贴图，没有就 null。 */
    public Tex tex(String slot) {
        return textures.get(slot);
    }

    /** 取打击音的原始字节（ogg/wav），没有就 null。 */
    public byte[] sound(String slot) {
        return sounds.get(slot);
    }

    public List<String> slots() {
        return Collections.unmodifiableList(new ArrayList<>(textures.keySet()));
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private static ResPack load(Path zip, String fileName) {
        ResPack pack = new ResPack(fileName);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Map<String, String> info = parseInfoYml(readEntry(zf, "info.yml"));
            pack.name = info.getOrDefault("name", fileName);
            pack.author = info.getOrDefault("author", "");
            int[] atlas = intPair(info.get("holdAtlas"));
            pack.holdTailPx = atlas[0];
            pack.holdHeadPx = atlas[1];
            pack.holdRepeat = bool(info, "holdRepeat");
            pack.holdKeepHead = bool(info, "holdKeepHead");
            pack.holdCompact = bool(info, "holdCompact");
            int[] hit = intPair(info.get("hitFx"));
            pack.hitFxCols = Math.max(1, hit[0]);
            pack.hitFxRows = Math.max(1, hit[1]);

            for (String slot : SLOTS) {
                byte[] png = readEntryOrNull(zf, slot + ".png");
                if (png == null) continue;
                try {
                    pack.register(slot, png);
                } catch (Throwable t) {
                    LOG.warn("[PhiMC] 资源包贴图 {} 读取失败: {}", slot, t.toString());
                }
            }
            for (String slot : new String[]{"click", "drag", "flick"}) {
                byte[] snd = readEntryOrNull(zf, slot + ".ogg");
                if (snd == null) snd = readEntryOrNull(zf, slot + ".wav");
                if (snd != null) pack.sounds.put(slot, snd);
            }
            LOG.info("[PhiMC] 资源包已加载: {} ({}) 贴图={} 打击音={} holdAtlas=[{},{}] repeat={} keepHead={} compact={}",
                    pack.displayName(), fileName, pack.textures.keySet(), pack.sounds.keySet(),
                    pack.holdTailPx, pack.holdHeadPx, pack.holdRepeat, pack.holdKeepHead, pack.holdCompact);
            return pack;
        } catch (Throwable t) {
            LOG.warn("[PhiMC] 资源包加载失败: {}", zip, t);
            pack.destroy();
            return null;
        }
    }

    private void register(String slot, byte[] png) throws IOException {
        NativeImage image = readImage(png);
        Identifier id = Identifier.of(NS, "respack/" + slot);
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getTextureManager().destroyTexture(id);
        // 双线性过滤（GL_LINEAR，不生成 mipmap）＋ 1.21.11 的 GpuSampler 差异都封在 RenderCompat 里。
        // **为什么要 LINEAR**：照 prpr resource.rs:212-213 的 `SafeTexture::from(image).with_filter(GL_LINEAR)`。
        // MC 的 `NativeImageBackedTexture` 默认是点采样（GL_NEAREST）；资源包贴图普遍是
        // 989×100 这种大图、画到屏幕上只有 ~169×17（5.85 倍缩小），点采样会挑单个 texel：
        // 实测 flick.png「纯白像素占比」31.2%（NEAREST）vs 28.7%（LINEAR），可见亮度均值
        // 214.2 vs 202.7（+5.7%），白色核心被硬边保留 → 观感「比 Phira 亮/刺」。
        NativeImageBackedTexture texture = RenderCompat.newTextureLinear(id, image);
        mc.getTextureManager().registerTexture(id, texture);
        textures.put(slot, new Tex(id, image.getWidth(), image.getHeight()));
    }

    /**
     * PNG 字节 → {@link NativeImage}。
     *
     * <p><b>只能走 {@code read(ByteBuffer)}，不能走 {@code read(byte[])}</b>：
     * {@code NativeImage.read(byte[])} 会把整个压缩 PNG 复制到 LWJGL 的 {@code MemoryStack}
     * （默认只有 64 KB），超过就抛 {@code OutOfMemoryError: Out of stack space.}。
     * 真实资源包的 {@code hold.png} 基本都超过 64 KB（Phigros 官方包 989×2000 = 162 KB），
     * 走 byte[] 重载的话 hold 贴图必然加载失败、只能退回纯色矩形。
     * 用 {@code memAlloc} 的直接缓冲绕开 MemoryStack，解完立刻释放。
     */
    private static NativeImage readImage(byte[] png) throws IOException {
        ByteBuffer buf = MemoryUtil.memAlloc(png.length);
        try {
            buf.put(png).flip();                 // position=0, limit=length
            return NativeImage.read(buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private void destroy() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Tex t : textures.values()) {
            try {
                mc.getTextureManager().destroyTexture(t.id());
            } catch (Throwable ignored) {
            }
        }
        textures.clear();
        sounds.clear();
    }

    private static byte[] readEntryOrNull(ZipFile zf, String entryName) {
        ZipEntry e = zf.getEntry(entryName);
        if (e == null) {
            // 有些包会把所有东西塞进一层子目录：兜底按后缀找唯一一个
            String suffix = "/" + entryName;
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry cand = it.nextElement();
                if (cand.getName().endsWith(suffix)) {
                    e = cand;
                    break;
                }
            }
        }
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zf, String entryName) throws IOException {
        byte[] b = readEntryOrNull(zf, entryName);
        if (b == null) throw new IOException("Missing " + entryName);
        return b;
    }

    /**
     * 极简 info.yml 解析（只认 ResourcePack::load 用到的那几个键）。
     *
     * <p>不引 YAML 库：值只有「字符串 / 布尔 / 一对整数 / 一个数」，按行 {@code key: value} 切就够了。
     */
    static Map<String, String> parseInfoYml(byte[] raw) {
        Map<String, String> out = new HashMap<>();
        String text = new String(raw, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("-")) continue;
            int colon = s.indexOf(':');
            if (colon <= 0) continue;
            String key = s.substring(0, colon).trim();
            String val = s.substring(colon + 1).trim();
            // 去行尾注释（只在没有引号时处理，够用了）
            if (!val.startsWith("\"") && !val.startsWith("'")) {
                int hash = val.indexOf(" #");
                if (hash > 0) val = val.substring(0, hash).trim();
            }
            out.put(key, unquote(val));
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2
                && ((v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
                || (v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\''))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** {@code "true"} / {@code "false"}（YAML 里也可能写成 {@code "True"}）；缺省 = false。 */
    static boolean bool(Map<String, String> info, String key) {
        String v = info.get(key);
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    /** {@code "[50, 50]"} / {@code "50,50"} → {@code {50, 50}}；解析不了就是 {@code {0, 0}}。 */
    static int[] intPair(String v) {
        if (v == null) return new int[]{0, 0};
        String s = v.replace('[', ' ').replace(']', ' ').trim();
        String[] parts = s.split(",");
        try {
            int a = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0;
            int b = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return new int[]{Math.max(0, a), Math.max(0, b)};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }
}
