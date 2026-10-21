package dev.dsh.phimc.client;

import dev.dsh.phimc.core.Judgement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 每首曲子的最佳成绩（选曲界面显示用）。
 *
 * <p>字段与 Phira 的 {@code SimpleRecord} 对齐（{@code prpr/src/scene/game.rs:966-970}：
 * {@code score}、{@code accuracy}、{@code full_combo = max_combo == num_of_notes}）。
 * 存成一行一条的 TSV。
 *
 * <h2>按存档隔离</h2>
 * 以前固定在 {@code config/phimc/records.tsv}（整个实例共用，换存档就串），现在跟着世界走：
 * {@code saves/<世界名>/phimc/records.tsv}（路径由 {@code WorldStore} 给出），
 * 进世界时 {@link #open(Path)}、退世界时 {@link #open(Path)} 传 {@code null}（清空、不写盘）。
 *
 * <p>「没有世界」时 {@code file == null}：{@link #get} 永远返回 null、{@link #submit} 不落盘 ——
 * 与 {@code Profile} 的「只读不写」同一套口径。
 */
public final class Records {

    /** 一条最佳成绩。 */
    public record Rec(int score, double accuracy, int maxCombo, int total, boolean fullCombo) {
        /** 选曲界面上的段位字母（judge.rs:1035-1046 的 icon_index 阈值同源）。 */
        public String rank() {
            return Judgement.rank(score);
        }

        public String scoreText() {
            return String.format("%07d", score);
        }

        public String accText() {
            return String.format("%.2f%%", accuracy * 100.0);
        }
    }

    private Path file;
    private final Map<String, Rec> map = new HashMap<>();

    public Records(Path file) {
        open(file);
    }

    /** 当前成绩文件；{@code null} = 没有世界（不读不写）。 */
    public synchronized Path file() {
        return file;
    }

    /** 切到另一个世界的成绩文件（{@code null} = 清空并停止写盘）。 */
    public synchronized void open(Path file) {
        open(file, null);
    }

    /**
     * 切到另一个世界的成绩文件；文件还不存在时，可以用 {@code legacySeed} 播种一次。
     *
     * <p>{@code legacySeed} 只在「旧版单文件进度迁移到这个世界」时由 {@code Session} 传入：
     * 把老 {@code config/phimc/records.tsv} 复制成这个世界的成绩表（之后各世界互不影响）。
     * 拷贝后旧的全局文件保持原样（不动用户数据），它也不会再被播种第二次 —— 迁移标记在
     * {@code profile.json} 里，只触发一次。
     */
    public synchronized void open(Path file, Path legacySeed) {
        this.file = file;
        map.clear();
        if (file != null && !Files.isRegularFile(file)
                && legacySeed != null && Files.isRegularFile(legacySeed)) {
            try {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.copy(legacySeed, file);
            } catch (IOException ignored) {
                // 复制失败就当这个世界没有历史成绩（不影响启动）
            }
        }
        load();
    }

    public synchronized Rec get(String key) {
        return key == null ? null : map.get(key);
    }

    /**
     * 提交一局成绩：只有分数更高（或首次）才记录。
     *
     * @return true 表示刷新了最佳成绩
     */
    public synchronized boolean submit(String key, Rec rec) {
        if (key == null) return false;
        Rec old = map.get(key);
        if (old != null && old.score() >= rec.score()) return false;
        map.put(key, rec);
        save();
        return true;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length < 6) continue;
                try {
                    map.put(f[0], new Rec(Integer.parseInt(f[1]), Double.parseDouble(f[2]),
                            Integer.parseInt(f[3]), Integer.parseInt(f[4]), "1".equals(f[5])));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Rec> e : map.entrySet()) {
            Rec r = e.getValue();
            sb.append(e.getKey()).append('\t').append(r.score()).append('\t')
                    .append(String.format("%.6f", r.accuracy())).append('\t')
                    .append(r.maxCombo()).append('\t').append(r.total()).append('\t')
                    .append(r.fullCombo() ? '1' : '0').append('\n');
        }
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
