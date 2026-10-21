import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 坏 ZIP 的 headless 诊断：为什么 {@code new ZipFile(f)} 抛
 * {@code ZipException: invalid CEN header (bad entry name or comment)}，以及有没有更宽松的读法。
 *
 * <p>对每个文件试四种打开方式：
 * <ol>
 *   <li>{@code ZipFile(File)} —— 生产代码现在用的（JDK 默认校验最严）；</li>
 *   <li>{@code ZipFile(File, Charset)}，分别 UTF-8 / GBK / ISO-8859-1；</li>
 *   <li>{@code ZipInputStream} —— 顺序读，不做 CEN 校验；</li>
 * </ol>
 * 并打印每个条目名（这样能看出到底是名字哪一段不合法）。
 */
public final class ZipProbe {

    public static void main(String[] args) throws Exception {
        for (String a : args) probe(Path.of(a));
    }

    private static void probe(Path p) {
        System.out.printf(Locale.ROOT, "== %s (%d 字节) ==%n", p.getFileName(), p.toFile().length());
        try {
            try (ZipFile zf = new ZipFile(p.toFile())) {
                System.out.println("   ① ZipFile(File): OK, " + zf.size() + " 条目");
                dump(zf);
            }
        } catch (Exception e) {
            System.out.println("   ① ZipFile(File): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        for (Charset cs : new Charset[]{StandardCharsets.UTF_8, Charset.forName("GBK"),
                StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII}) {
            try {
                try (ZipFile zf = new ZipFile(p.toFile(), cs)) {
                    System.out.printf(Locale.ROOT, "   ② ZipFile(%s): OK, %d 条目%n", cs.name(), zf.size());
                    dump(zf);
                }
            } catch (Exception e) {
                System.out.printf(Locale.ROOT, "   ② ZipFile(%s): %s: %s%n", cs.name(),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(p)))) {
            List<String> names = new ArrayList<>();
            ZipEntry e;
            int total = 0;
            long bytes = 0;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName() + " (" + e.getSize() + "B)");
                byte[] buf = new byte[65536];
                int n;
                while ((n = zis.read(buf)) > 0) bytes += n;
                total++;
            }
            System.out.printf(Locale.ROOT, "   ③ ZipInputStream: OK, %d 条目, 解出 %d 字节%n", total, bytes);
            for (String n : names) System.out.println("        " + n);
        } catch (Exception e) {
            System.out.println("   ③ ZipInputStream: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println();
    }

    private static void dump(ZipFile zf) {
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            System.out.printf(Locale.ROOT, "        %-52s %9dB  压缩法=%d%n",
                    e.getName(), e.getSize(), e.getMethod());
        }
    }

    private ZipProbe() {
    }
}
