import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dsh.phimc.client.SongEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 谱面「字段使用率」扫描：为「特效谱观感差异表」提供<b>本曲库的实测数据</b>
 * （哪些 extended 字段真的被用到、用到多少条线、zOrder 是否和下标一致、
 *  text/paint 线是不是也带音符 —— 决定我们敢不敢不画）。
 */
public final class FieldProbe {

    public static void main(String[] args) {
        Path dir = Path.of("D:/.minecraft/versions/1.21.1-fabric-0.20.0/config/phimc/songs");
        List<SongEntry> songs = SongEntry.scan(dir);
        System.out.println("谱面包: " + songs.size());
        for (SongEntry e : songs) {
            String name = e.path.getFileName().toString();
            String json;
            try {
                json = e.chartJson();
            } catch (Exception ex) {
                continue;
            }
            if (!json.trim().startsWith("{")) continue;   // PEC 跳过
            JsonObject root;
            try {
                root = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception ex) {
                continue;
            }
            JsonArray lines = root.getAsJsonArray("judgeLineList");
            if (lines == null) continue;
            int n = lines.size();
            int color = 0, incline = 0, text = 0, paint = 0, gif = 0;
            int nonPng = 0, attach = 0, zNonIdx = 0, negAlpha = 0;
            int textWithNotes = 0, paintWithNotes = 0, inclineNonZero = 0;
            int scale = 0;
            List<Integer> zs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                JsonElement el = lines.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject l = el.getAsJsonObject();
                JsonObject ext = l.has("extended") && l.get("extended").isJsonObject()
                        ? l.getAsJsonObject("extended") : null;
                boolean hasNotes = l.has("notes") && l.get("notes").isJsonArray() && l.getAsJsonArray("notes").size() > 0;
                if (ext != null) {
                    if (!empty(ext, "colorEvents")) color++;
                    if (!empty(ext, "inclineEvents")) {
                        incline++;
                        if (anyNonZero(ext.getAsJsonArray("inclineEvents"))) inclineNonZero++;
                    }
                    if (!empty(ext, "textEvents")) {
                        text++;
                        if (hasNotes) textWithNotes++;
                    }
                    if (!empty(ext, "paintEvents")) {
                        paint++;
                        if (hasNotes) paintWithNotes++;
                    }
                    if (!empty(ext, "gifEvents")) gif++;
                    if (!empty(ext, "scaleXEvents") || !empty(ext, "scaleYEvents")) scale++;
                }
                if (l.has("Texture")) {
                    String t = l.get("Texture").isJsonNull() ? "" : l.get("Texture").getAsString();
                    if (!t.isEmpty() && !t.equals("line.png")) nonPng++;
                }
                if (l.has("attachUI") && !l.get("attachUI").isJsonNull()) attach++;
                int z = l.has("zOrder") && !l.get("zOrder").isJsonNull() ? l.get("zOrder").getAsInt() : 0;
                zs.add(z);
                if (z != 0) zNonIdx++;
                if (l.has("eventLayers") && l.get("eventLayers").isJsonArray()) {
                    for (JsonElement le : l.getAsJsonArray("eventLayers")) {
                        if (le == null || !le.isJsonObject()) continue;
                        JsonArray ae = le.getAsJsonObject().has("alphaEvents")
                                ? le.getAsJsonObject().getAsJsonArray("alphaEvents") : null;
                        if (ae == null) continue;
                        for (JsonElement ae1 : ae) {
                            JsonObject o = ae1.getAsJsonObject();
                            if (o.has("start") && o.get("start").isJsonPrimitive()
                                    && o.get("start").getAsDouble() < 0) negAlpha++;
                        }
                    }
                }
            }
            boolean zSorted = isNonDecreasing(zs);
            System.out.printf(Locale.ROOT,
                    "%-40s 线%4d | color%4d incline%4d(非零%4d) text%3d(带音符%2d) paint%2d(带音符%2d) gif%2d"
                            + " | 非line.png%3d attachUI%3d scale%4d | zOrder≠0 %4d 已按z升序=%b | 负alpha %d%n",
                    cut(name, 40), n, color, incline, inclineNonZero, text, textWithNotes, paint, paintWithNotes, gif,
                    nonPng, attach, scale, zNonIdx, zSorted, negAlpha);
        }
    }

    private static boolean empty(JsonObject ext, String key) {
        JsonElement e = ext.get(key);
        return e == null || e.isJsonNull() || !e.isJsonArray() || e.getAsJsonArray().isEmpty();
    }

    private static boolean anyNonZero(JsonArray a) {
        for (JsonElement el : a) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("start") && o.get("start").isJsonPrimitive() && Math.abs(o.get("start").getAsDouble()) > 1e-9) return true;
            if (o.has("end") && o.get("end").isJsonPrimitive() && Math.abs(o.get("end").getAsDouble()) > 1e-9) return true;
        }
        return false;
    }

    private static boolean isNonDecreasing(List<Integer> a) {
        for (int i = 1; i < a.size(); i++) if (a.get(i) < a.get(i - 1)) return false;
        return true;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, Math.max(0, n - 1)) + "…";
    }

    private FieldProbe() {
    }
}
