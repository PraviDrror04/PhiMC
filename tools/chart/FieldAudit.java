import dev.dsh.phimc.client.SongEntry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「字段使用率」全量审计：把 {@code prpr/src/parse/rpe.rs} 里 {@code RPEJudgeLine} / {@code RPENote} /
 * {@code RPEExtendedEvents} / {@code RPEEventLayer} 的<b>每一个字段</b>在曲库里出现的次数统计出来，
 * 作为「穷举表」里「影响哪些谱面」那一列的实测依据。
 *
 * <p>同时统计 PEC 的命令分布（{@code bp/n1..n4/#/&/cv/cp/cd/ca/cm/cr/cf}）以及
 * 「行内 {@code #}/{@code &}」与「独立行 {@code #}/{@code &}」两种写法的占比。
 *
 * <p>用法：{@code java -cp out/dev FieldAudit [目录]}
 */
public final class FieldAudit {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0]
                : "D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        List<SongEntry> songs = SongEntry.scan(dir);
        System.out.println("谱面包: " + songs.size());
        System.out.println();

        Map<String, Integer> charts = new LinkedHashMap<>();   // 字段 → 出现在几首谱里
        Map<String, Integer> counts = new LinkedHashMap<>();   // 字段 → 总出现次数
        int pecs = 0;
        Map<String, Integer> pecCmd = new LinkedHashMap<>();
        int pecInline = 0;
        int pecSeparate = 0;
        int pecNegAlpha = 0;

        for (SongEntry e : songs) {
            String name = e.path.getFileName().toString();
            String raw;
            try {
                raw = e.chartJson();
            } catch (Exception ex) {
                continue;
            }
            if (!raw.trim().startsWith("{")) {           // PEC 纯文本
                pecs++;
                String[] lines = raw.split("\\r?\\n");
                for (int i = 1; i < lines.length; i++) {
                    String s = lines[i].trim();
                    if (s.isEmpty()) continue;
                    String[] t = s.split("\\s+");
                    pecCmd.merge(t[0], 1, Integer::sum);
                    if (t[0].startsWith("n") && t[0].length() == 2) {
                        // 行内 "n1 line beat x above fake # <speed>" / "& <size>"
                        if (s.contains(" # ")) pecInline++;
                        if (s.contains(" & ")) pecInline++;
                    } else if (t[0].equals("#") || t[0].equals("&")) {
                        pecSeparate++;
                    } else if (t[0].equals("ca") || t[0].equals("cf")) {
                        double v = Double.parseDouble(t[t.length - 1]);
                        if (v < 0) pecNegAlpha++;
                    }
                }
                continue;
            }
            JsonObject root;
            try {
                root = JsonParser.parseString(raw).getAsJsonObject();
            } catch (Exception ex) {
                continue;
            }
            JsonArray jl = root.getAsJsonArray("judgeLineList");
            if (jl == null) continue;
            Map<String, Integer> localCount = new LinkedHashMap<>();
            for (JsonElement el : jl) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject l = el.getAsJsonObject();
                bump(localCount, "Texture", l.has("Texture") && !l.get("Texture").isJsonNull()
                        && !"line.png".equals(l.get("Texture").getAsString()) ? 1 : 0);
                bump(localCount, "attachUI", l.has("attachUI") && !l.get("attachUI").isJsonNull() ? 1 : 0);
                bump(localCount, "father", l.has("father") && !l.get("father").isJsonNull()
                        && l.get("father").getAsInt() >= 0 ? 1 : 0);
                bump(localCount, "rotateWithFather", bool(l, "rotateWithFather") ? 1 : 0);
                bump(localCount, "isCover!=1(showBelow)", l.has("isCover") && l.get("isCover").getAsInt() != 1 ? 1 : 0);
                bump(localCount, "zOrder!=0", l.has("zOrder") && l.get("zOrder").getAsInt() != 0 ? 1 : 0);
                JsonArray layers = l.getAsJsonArray("eventLayers");
                if (layers != null) {
                    for (JsonElement le : layers) {
                        if (le == null || !le.isJsonObject()) continue;
                        JsonObject o = le.getAsJsonObject();
                        for (String k : new String[]{"moveXEvents", "moveYEvents", "rotateEvents",
                                "alphaEvents", "speedEvents"}) {
                            bump(localCount, k, arrSize(o.get(k)) > 0 ? 1 : 0);
                        }
                    }
                }
                JsonObject ext = l.has("extended") && l.get("extended").isJsonObject()
                        ? l.getAsJsonObject("extended") : null;
                if (ext != null) {
                    for (String k : new String[]{"scaleXEvents", "scaleYEvents", "inclineEvents",
                            "colorEvents", "textEvents", "paintEvents", "gifEvents"}) {
                        bump(localCount, "ext." + k, arrSize(ext.get(k)) > 0 ? 1 : 0);
                    }
                    // incline 非零
                    if (arrSize(ext.get("inclineEvents")) > 0 && anyNonZero(ext.getAsJsonArray("inclineEvents"))) {
                        bump(localCount, "ext.inclineEvents(非零)", 1);
                    }
                }
                for (String k : new String[]{"alphaControl", "sizeControl", "posControl", "yControl"}) {
                    bump(localCount, k, arrSize(l.get(k)) > 0 ? 1 : 0);
                }
                JsonArray notes = l.getAsJsonArray("notes");
                if (notes != null) {
                    for (JsonElement ne : notes) {
                        if (ne == null || !ne.isJsonObject()) continue;
                        JsonObject n = ne.getAsJsonObject();
                        bump(localCount, "note.tint", n.has("tint") && !n.get("tint").isJsonNull() ? 1 : 0);
                        bump(localCount, "note.tintHitEffects",
                                n.has("tintHitEffects") && !n.get("tintHitEffects").isJsonNull() ? 1 : 0);
                        bump(localCount, "note.yOffset!=0",
                                n.has("yOffset") && Math.abs(n.get("yOffset").getAsDouble()) > 1e-9 ? 1 : 0);
                        bump(localCount, "note.speed!=1",
                                n.has("speed") && Math.abs(n.get("speed").getAsDouble() - 1) > 1e-9 ? 1 : 0);
                        bump(localCount, "note.size!=1",
                                n.has("size") && Math.abs(n.get("size").getAsDouble() - 1) > 1e-9 ? 1 : 0);
                        bump(localCount, "note.alpha!=255",
                                n.has("alpha") && n.get("alpha").getAsInt() != 255 ? 1 : 0);
                        bump(localCount, "note.visibleTime>0",
                                n.has("visibleTime") && n.get("visibleTime").getAsDouble() > 1e-9 ? 1 : 0);
                        bump(localCount, "note.judgeArea!=1",
                                n.has("judgeArea") && Math.abs(n.get("judgeArea").getAsDouble() - 1) > 1e-9 ? 1 : 0);
                        bump(localCount, "note.hitsound", n.has("hitsound") && !n.get("hitsound").isJsonNull() ? 1 : 0);
                        bump(localCount, "note.isFake", n.has("isFake") && n.get("isFake").getAsInt() != 0 ? 1 : 0);
                    }
                }
            }
            for (Map.Entry<String, Integer> en : localCount.entrySet()) {
                charts.merge(en.getKey(), 1, Integer::sum);
                counts.merge(en.getKey(), en.getValue(), Integer::sum);
            }
        }

        System.out.printf(Locale.ROOT, "%-34s %-8s %-10s%n", "字段 / 事件", "谱面数", "总条数(线或音符)");
        System.out.println("-".repeat(60));
        for (String k : counts.keySet()) {
            System.out.printf(Locale.ROOT, "%-34s %-8d %-10d%n", k, charts.getOrDefault(k, 0), counts.get(k));
        }
        System.out.println();
        System.out.printf(Locale.ROOT, "PEC 谱面 %d 个；命令分布 %s%n", pecs, pecCmd);
        System.out.printf(Locale.ROOT, "PEC 行内 #/& 出现 %d 次，独立行 #/& %d 次，负 alpha 值 %d 个%n",
                pecInline, pecSeparate, pecNegAlpha);
    }

    private static void bump(Map<String, Integer> m, String k, int v) {
        if (v > 0) m.merge(k, v, Integer::sum);
    }

    private static int arrSize(JsonElement el) {
        return el != null && el.isJsonArray() ? el.getAsJsonArray().size() : 0;
    }

    private static boolean bool(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean anyNonZero(JsonArray a) {
        for (JsonElement el : a) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            for (String k : new String[]{"start", "end"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()
                        && Math.abs(o.get(k).getAsDouble()) > 1e-9) return true;
            }
        }
        return false;
    }

    private FieldAudit() {
    }
}
