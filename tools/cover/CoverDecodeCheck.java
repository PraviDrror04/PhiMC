import dev.dsh.phimc.client.CoverCodec;
import net.minecraft.client.texture.NativeImage;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 曲绘解码自测（真 bug 的复现 + 修法验证）。
 *
 * <p>它<b>用真代码</b>：格式判定 / JPEG→PNG 转码调 {@code dev.dsh.phimc.client.CoverCodec}
 * （就是 {@code CoverTextures.readImage} 里那一行），解码调真的
 * {@code net.minecraft.client.texture.NativeImage#read(ByteBuffer)}（Minecraft 自带的、
 * 被裁成「只认 PNG」的 stb_image）。所以：
 * <ul>
 *   <li>「修复前」一列 = 直接把原始字节喂给 {@code NativeImage.read} → JPEG 包必然失败（复现 bug）；</li>
 *   <li>「修复后」一列 = 先 {@code CoverCodec.toPng} 再喂 → 必须全部成功（验证修法）。</li>
 * </ul>
 *
 * <p>用法（需要 Minecraft + LWJGL 的 jar 在 classpath 上，见 {@code docs/REPORT-ui-lock-cover.md}）：
 * <pre>
 * java -cp "&lt;out&gt;;&lt;minecraft-merged-named.jar&gt;;&lt;lwjgl*.jar&gt;;&lt;lwjgl-stb*.jar&gt;;&lt;natives&gt;;&lt;slf4j-api.jar&gt;" CoverDecodeCheck C:\dsh-work\payload
 * </pre>
 * 不带参数时扫 {@code C:\dsh-work\payload}。
 */
public final class CoverDecodeCheck {

    private static final String DEFAULT_DIR = "C:\\dsh-work\\payload";

    public static void main(String[] args) throws Exception {
        boolean trace = false;
        List<String> roots = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--trace")) trace = true;
            else roots.add(a);
        }
        if (roots.isEmpty()) roots.add(DEFAULT_DIR);
        traceFailures = trace;
        List<Path> packages = collect(roots);
        System.out.printf("扫到 %d 个谱面包%n%n", packages.size());
        System.out.println("谱面包 / 曲绘条目                        魔数        判定   修复前 NativeImage   修复后 尺寸");
        System.out.println("--------------------------------------------------------------------------------------------");

        int total = 0;
        int withCover = 0;
        int beforeOk = 0;
        int afterOk = 0;
        int pezCount = 0;
        int jpegCount = 0;
        List<String> failures = new ArrayList<>();

        for (Path p : packages) {
            total++;
            boolean isPez = p.toString().toLowerCase(Locale.ROOT).endsWith(".pez");
            if (isPez) pezCount++;
            String name = p.getFileName().toString();
            try (ZipFile zf = new ZipFile(p.toFile())) {
                ZipEntry img = resolveImage(zf);
                if (img == null) {
                    System.out.printf("%-42s %s%n", name, "（包里没有曲绘 → 占位色块，不算失败）");
                    continue;
                }
                byte[] raw;
                try (InputStream in = zf.getInputStream(img)) {
                    raw = in.readAllBytes();
                }
                CoverCodec.Format format = CoverCodec.detect(raw);
                withCover++;
                if (format == CoverCodec.Format.JPEG) jpegCount++;

                // A) 修复前：原样喂给 NativeImage.read（Minecraft 的 stb_image 只认 PNG）
                Probe before = probe(raw);
                if (before.ok) beforeOk++;

                // B) 修复后：先 CoverCodec.toPng（PNG 原样返回，JPEG/其它转码），再喂
                byte[] png = CoverCodec.toPng(raw);
                Probe after = png == null ? new Probe(false, 0, 0, "转码失败") : probe(png);
                if (after.ok) afterOk++;
                else failures.add(name + " -> " + after.error);

                System.out.printf("%-42s %-11s %-6s %-19s %-12s%n",
                        shorten(name, 42) + " / " + shorten(img.getName(), 0),
                        CoverCodec.magicHex(raw),
                        format.label(),
                        before.ok ? "OK " + before.w + "×" + before.h : "失败（" + trim(before.error) + "）",
                        after.ok ? "OK " + after.w + "×" + after.h : "失败（" + trim(after.error) + "）");
            }
        }

        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.printf("合计 %d 个包（有曲绘 %d 个；其中 .pez %d 个，曲绘是 JPEG 的 %d 个）%n",
                total, withCover, pezCount, jpegCount);
        System.out.printf("修复前（裸 NativeImage.read）成功 %d/%d —— JPEG 全部失败，就是用户看到的占位色块%n",
                beforeOk, withCover);
        System.out.printf("修复后（CoverCodec.toPng → NativeImage.read）成功 %d/%d%n", afterOk, withCover);
        if (afterOk == withCover && failures.isEmpty()) {
            System.out.println("结论: ✔ 有曲绘的包全部解码成功");
        } else {
            System.out.println("结论: ✗ 失败 " + failures.size() + " 个：" + failures);
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 解码探针

    private record Probe(boolean ok, int w, int h, String error) {
    }

    /** {@code --trace}：把第一次失败的完整堆栈打出来（排查「类初始化失败」用）。 */
    private static boolean traceFailures;
    private static boolean traced;

    /** 真的调 {@code NativeImage.read(ByteBuffer)}（直接缓冲，用完 memFree）。 */
    private static Probe probe(byte[] bytes) {
        NativeImage image = null;
        ByteBuffer buf = null;
        try {
            buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            image = NativeImage.read(buf);
            if (image == null) return new Probe(false, 0, 0, "read 返回 null");
            return new Probe(true, image.getWidth(), image.getHeight(), "");
        } catch (Throwable t) {
            if (traceFailures && !traced) {
                traced = true;
                System.out.println("--- 第一次失败的完整堆栈（--trace） ---");
                t.printStackTrace(System.out);
                System.out.println("--- 堆栈结束 ---");
            }
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            return new Probe(false, 0, 0, msg);
        } finally {
            if (image != null) image.close();
            if (buf != null) MemoryUtil.memFree(buf);
        }
    }

    // ---------------------------------------------------------------- 包内定位曲绘（照 SongEntry.cover 的顺序）

    private static ZipEntry resolveImage(ZipFile zf) {
        String declared = pictureFromInfo(zf);
        ZipEntry e = named(zf, declared);
        if (e != null) return e;
        e = uniqueImage(zf);
        if (e != null) return e;
        for (String key : new String[]{"illustration", "defaultimage", "cover", "image"}) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (z.isDirectory()) continue;
                String low = z.getName().toLowerCase(Locale.ROOT);
                if (isImage(low) && low.contains(key)) return z;
            }
        }
        return null;
    }

    private static String pictureFromInfo(ZipFile zf) {
        ZipEntry info = zf.getEntry("info.txt");
        if (info == null) info = zf.getEntry("info.yml");
        if (info == null) return "";
        try (InputStream in = zf.getInputStream(info)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String t = line.trim();
                int c = t.indexOf(':');
                if (c <= 0) continue;
                String k = t.substring(0, c).trim().toLowerCase(Locale.ROOT);
                if (k.equals("picture") || k.equals("illustration") || k.equals("image") || k.equals("cover")) {
                    return t.substring(c + 1).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static ZipEntry named(ZipFile zf, String name) {
        if (name == null || name.isBlank()) return null;
        String want = name.trim().replace('\\', '/');
        String base = want.contains("/") ? want.substring(want.lastIndexOf('/') + 1) : want;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry z = en.nextElement();
            if (z.isDirectory()) continue;
            String n = z.getName().replace('\\', '/');
            String b = n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
            if (n.equalsIgnoreCase(want) || b.equalsIgnoreCase(base)) return z;
        }
        return null;
    }

    private static ZipEntry uniqueImage(ZipFile zf) {
        ZipEntry hit = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry z = en.nextElement();
            if (z.isDirectory() || !isImage(z.getName().toLowerCase(Locale.ROOT))) continue;
            if (hit != null) return null;
            hit = z;
        }
        return hit;
    }

    private static boolean isImage(String low) {
        return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg")
                || low.endsWith(".bmp") || low.endsWith(".gif");
    }

    // ---------------------------------------------------------------- 参数

    private static List<Path> collect(List<String> roots) throws Exception {
        List<Path> out = new ArrayList<>();
        for (String r : roots) {
            Path p = Path.of(r);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(Files::isRegularFile)
                            .filter(f -> {
                                String low = f.toString().toLowerCase(Locale.ROOT);
                                return low.endsWith(".pez") || low.endsWith(".zip");
                            })
                            .sorted()
                            .forEach(out::add);
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private static String shorten(String s, int max) {
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String trim(String s) {
        if (s == null) return "?";
        return s.length() <= 30 ? s : s.substring(0, 29) + "…";
    }
}
