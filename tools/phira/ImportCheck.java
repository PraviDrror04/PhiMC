import dev.dsh.phimc.client.SongEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 下载 → 自动导入 的离线验证：把 Phira 下下来的 {@code .pez} 扔进一个 songs 目录，
 * 用<b>真代码</b>（{@link SongEntry#scan}）扫一遍，看能不能识别成一首可玩的谱面。
 *
 * <p>验证的链路正是界面上下载完成后的那条：{@code PhiraDownloader} 落盘
 * {@code songs/{id}_{曲名}.pez} → {@code Session.rescan()} → {@link SongEntry#scan} →
 * 本地页签出现这一首（并在 {@code start()} 时能解析谱面 / 音频）。
 *
 * <p>用法：{@code java -cp "<out>;gson.jar;slf4j-api.jar" ImportCheck <songsDir>}
 */
public final class ImportCheck {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "C:\\dsh-work\\phira-check\\songs");
        System.out.println("songs 目录：" + dir);
        if (!Files.isDirectory(dir)) {
            System.out.println("目录不存在");
            System.exit(1);
        }

        List<SongEntry> songs = SongEntry.scan(dir);
        System.out.println("扫描到 " + songs.size() + " 首：");
        int failures = 0;
        for (SongEntry e : songs) {
            System.out.println("  " + e.path.getFileName() + " → " + e.display);
            System.out.println("    曲名「" + e.title() + "」难度「" + e.levelText() + "」段位「" + e.grade + "」");
            System.out.println("    谱师「" + e.charter() + "」曲师「" + e.composer() + "」画师「" + e.illustrator() + "」");
            try {
                String json = e.chartJson();
                System.out.println("    谱面 JSON：" + json.length() + " 字符");
                if (json.isBlank()) {
                    System.out.println("    ✗ 谱面 JSON 是空的");
                    failures++;
                }
                SongEntry.Audio audio = e.payload();
                System.out.println("    音频：" + (audio == null ? "无" : audio.name() + " " + audio.data().length + " 字节"));
                if (audio == null) {
                    System.out.println("    ✗ 没有音频，开局会静音 / 失败");
                    failures++;
                }
                byte[] cover = e.cover();
                System.out.println("    曲绘：" + (cover == null ? "无" : cover.length + " 字节"));
            } catch (Exception ex) {
                System.out.println("    ✗ 读取失败：" + ex);
                failures++;
            }
        }
        if (songs.isEmpty()) {
            System.out.println("  ✗ 一首都没扫到（下载的文件没被识别成谱面）");
            failures++;
        }
        System.out.println(failures == 0 ? "全部通过 ✓" : "有 " + failures + " 项失败 ✗");
        if (failures != 0) System.exit(1);
    }
}
