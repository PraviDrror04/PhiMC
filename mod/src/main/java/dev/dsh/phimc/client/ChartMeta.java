package dev.dsh.phimc.client;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.util.Locale;

/**
 * 从谱面 JSON 的 {@code META} 里读「info.txt 里没有的那几个字段」——<b>流式</b>读，不建整棵树。
 *
 * <h2>为什么需要它</h2>
 * RPE（{@code .pez}）的 {@code info.txt} <b>没有 Illustrator 字段</b>，画师名只写在谱面 JSON 的
 * {@code META.illustration} 里。实测 4 个真实 pez：
 * <pre>
 * Cyberneko Pulse (2).pez  info.txt: Name/Song/Picture/Chart/Level/Composer/Charter…（无 Illustrator）
 *                          谱面 META: { "background": "9691407376462442.jpg", "illustration": "keckyx", … }
 * </pre>
 * 所以「画师」以前永远显示「未知」。
 *
 * <h2>成本控制（关键）</h2>
 * 谱面 JSON 有 10～14 MB，{@link com.google.gson.JsonParser#parseString} 会建出几百 MB 的对象图。
 * 这里用 {@link JsonReader} 手工 lex：
 * <ul>
 *   <li>只把 {@code META} 那个小对象读进内存，其它成员一律 {@code skipValue()}（不分配对象）；</li>
 *   <li>RPE 把 {@code META} 放在 {@code judgeLineList} <b>之前</b>，所以读到就 {@code break}，
 *       <b>根本不会 lex 后面十几 MB 的音符数组</b>（实测 &lt; 3 ms，见 {@code tools/ui/SongEntryMetaCheck.java}）；</li>
 *   <li>调用方只在后台线程（{@link SongMeta}）里调它，渲染线程不碰。</li>
 * </ul>
 *
 * <h2>人名 vs 文件名（踩过的坑）</h2>
 * <table border="1">
 *   <tr><th>META 键</th><th>含义</th><th>落到哪里</th></tr>
 *   <tr><td>{@code illustration} / {@code illustrator} / {@code cover} / {@code pictureBy} /
 *           {@code imageBy} / {@code illustBy}</td><td><b>画师名</b>（人名）</td>
 *       <td>{@link #illustrator()} → {@code SongEntry.illustrator()}</td></tr>
 *   <tr><td>{@code background} / {@code picture} / {@code image}</td><td><b>曲绘图文件名</b></td>
 *       <td>{@link #picture()} → {@code SongEntry.pictureName()}</td></tr>
 * </table>
 * 两组合法键没有任何交集，所以「拿人名当文件名找图」这种事在结构上就不可能发生。
 */
public final class ChartMeta {

    /** 什么都读不到时的值（全空串）。 */
    public static final ChartMeta EMPTY = new ChartMeta("", "", "", "", "");

    private final String composer;
    private final String charter;
    private final String illustrator;
    private final String picture;
    private final String level;

    private ChartMeta(String composer, String charter, String illustrator, String picture, String level) {
        this.composer = composer;
        this.charter = charter;
        this.illustrator = illustrator;
        this.picture = picture;
        this.level = level;
    }

    /** 曲师（{@code META.composer}），没有就是空串。 */
    public String composer() {
        return composer;
    }

    /** 谱师（{@code META.charter}），没有就是空串。 */
    public String charter() {
        return charter;
    }

    /** 画师（{@code META.illustration} 等别名），没有就是空串。**人名，不是文件名**。 */
    public String illustrator() {
        return illustrator;
    }

    /** 曲绘图文件名（{@code META.background} 等别名），没有就是空串。**文件名，不是人名**。 */
    public String picture() {
        return picture;
    }

    /** 难度原文（{@code META.level}，例如 {@code AT Lv.17}），没有就是空串。 */
    public String level() {
        return level;
    }

    /** 一个字段都没读到（= 没有 META，或 META 里没这些键）。 */
    public boolean isEmpty() {
        return composer.isEmpty() && charter.isEmpty() && illustrator.isEmpty()
                && picture.isEmpty() && level.isEmpty();
    }

    @Override
    public String toString() {
        return "ChartMeta{composer=" + composer + ", charter=" + charter
                + ", illustrator=" + illustrator + ", picture=" + picture + ", level=" + level + "}";
    }

    // ------------------------------------------------------------------
    // 流式扫描
    // ------------------------------------------------------------------

    /**
     * 扫一遍谱面 JSON 的 {@code META}：没有 {@code META}（官方 PGR 格式）就返回 {@link #EMPTY}。
     *
     * <p>永远不抛异常：JSON 坏了 / 结构不对就返回已经读到的部分。
     *
     * @param json 谱面 JSON 全文（调用方已经读进内存了；这里不会再复制它）
     */
    public static ChartMeta scan(String json) {
        if (json == null || json.isEmpty()) return EMPTY;
        String composer = "";
        String charter = "";
        String illustrator = "";
        String picture = "";
        String level = "";
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.setLenient(true);
            if (r.peek() != JsonToken.BEGIN_OBJECT) return EMPTY;
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (!name.equalsIgnoreCase("META") || r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue();                      // 判定线 / 音符数组：只 lex，不建对象
                    continue;
                }
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName().toLowerCase(Locale.ROOT);
                    switch (key) {
                        // 曲师
                        case "composer", "songby", "song_by" -> composer = str(r);
                        // 谱师
                        case "charter", "chartby", "chart_by" -> charter = str(r);
                        // 画师（人名）：cover 也是一些包的写法
                        case "illustration", "illustrator", "cover",
                             "pictureby", "imageby", "illustby" -> illustrator = str(r);
                        // 曲绘文件名（绝不吃 illustration / illustrator）
                        case "background", "picture", "image" -> picture = str(r);
                        // 难度原文
                        case "level", "difficulty" -> level = str(r);
                        default -> r.skipValue();
                    }
                }
                r.endObject();
                // META 一般在文件最前面（RPE 的顺序：BPMList → META → judgeLineList），
                // 读到就收工，后面十几 MB 的音符数组一个字节都不 lex。
                break;
            }
        } catch (Throwable ignored) {
            // JSON 截断 / 类型怪：把已经读到的部分交出去
        }
        return new ChartMeta(composer, charter, illustrator, picture, level);
    }

    /** 读一个标量并 trim（RPE 常写 {@code "DJ Myosuke "} 这种带尾空格的）；非标量当空串。 */
    private static String str(JsonReader r) {
        try {
            JsonToken t = r.peek();
            if (t == JsonToken.STRING || t == JsonToken.NUMBER || t == JsonToken.BOOLEAN) {
                String v = r.nextString();
                return v == null ? "" : v.trim();
            }
            r.skipValue();
        } catch (Throwable ignored) {
        }
        return "";
    }
}
