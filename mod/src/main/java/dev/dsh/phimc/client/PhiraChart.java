package dev.dsh.phimc.client;

import java.util.List;
import java.util.Locale;

/**
 * Phira 在线谱面库里的一条谱面（<b>纯数据</b>，gson 直接反序列化）。
 *
 * <p>字段名与 Phira 的 {@code GET /chart} 响应一一对应（见 {@code docs/REPORT-phira-online.md}），
 * 所以这里<b>不加 {@code @SerializedName}</b>——改字段名就等于改接口契约。拿不到的字段一律
 * 可能是 {@code null}（接口偶尔缺 {@code composer} / {@code illustrator}），所有显示走本类的
 * 取值方法，不要在界面上直接摸字段。
 *
 * <p><b>只放服务端给的值</b>：本地是否已下载、下载进度这些状态在
 * {@link PhiraDownloader} 里，不往这个对象上挂（它是从 60 秒缓存里共享出来的，
 * 改它会串味）。
 *
 * <h2>字段口径（实测 api.phira.cn）</h2>
 * <ul>
 *   <li>{@code rating} 是 <b>0~1</b> 的小数（不是 5 分制），界面上保留两位小数显示；</li>
 *   <li>{@code difficulty} 是难度数值（{@code 16.7}），{@code level} 是原文
 *       （{@code "IN Lv.15"} / {@code "AT"}，有的谱面只有段位没等级）；</li>
 *   <li>{@code stable} / {@code reviewed} 是我们过滤的依据：<b>只显示两者都为真</b>；</li>
 *   <li>{@code file} / {@code illustration} 是 CDN 绝对 URL（{@code https://phira.5wyxi.com/files/<id>}），
 *       但接口也可能只给相对路径，{@link PhiraApi} 两种都认。</li>
 * </ul>
 */
public final class PhiraChart {

    /** 谱面 id（下载文件名的前缀，也是「已下载」判重的 key）。 */
    public long id;
    public String name;
    public String level;
    public double difficulty;
    public String charter;
    public String composer;
    public String illustrator;
    /** 曲绘 URL（缩略图就下它）。 */
    public String illustration;
    public String preview;
    /** 谱面包 URL（{@code .pez} 的 zip 本体）。 */
    public String file;
    public double rating;
    public int ratingCount;
    public boolean stable;
    public boolean reviewed;
    public List<String> tags;
    public long uploader;
    public String created;
    public String updated;

    /** gson 用。 */
    public PhiraChart() {
    }

    /** 曲名（用 {@link #display()} 统一取，别直接读可能为 null 的字段）。 */
    public String title() {
        return name == null || name.isBlank() ? "(未命名)" : name;
    }

    /** 难度原文；接口给空串时退成难度数值（{@code 16.7}），再没有就 {@code "-"}。 */
    public String levelText() {
        if (level != null && !level.isBlank()) return level;
        if (difficulty > 0) return String.format(Locale.ROOT, "%.1f", difficulty);
        return "-";
    }

    /** 评分（0~1）两位小数；没有评分时 {@code "-"}。 */
    public String ratingText() {
        if (rating <= 0) return "-";
        return String.format(Locale.ROOT, "%.2f", rating);
    }

    public String charterText() {
        return charter == null || charter.isBlank() ? "未知" : charter;
    }

    public String composerText() {
        return composer == null || composer.isBlank() ? "未知" : composer;
    }

    public String illustratorText() {
        return illustrator == null || illustrator.isBlank() ? "未知" : illustrator;
    }

    public String illustrationUrl() {
        return illustration == null ? "" : illustration;
    }

    public String fileUrl() {
        return file == null ? "" : file;
    }

    /** 列表里那一行：{@code 曲名 · 难度 · 评分 · 谱师} 的「斜杠」版（界面自己排颜色，这里给纯文本兜底）。 */
    public String display() {
        return title() + " · " + levelText() + " · 评分 " + ratingText() + " · " + charterText();
    }

    /** 谱面包 URL 里的文件 id（相对路径 {@code /files/<id>} 或绝对 URL 都认；认不出返回空串）。 */
    public String fileId() {
        String url = fileUrl();
        if (url.isEmpty()) return "";
        int cut = Math.max(url.lastIndexOf('/'), url.lastIndexOf('?'));
        String tail = cut >= 0 && cut + 1 < url.length() ? url.substring(cut + 1) : url;
        int q = tail.indexOf('?');
        return q > 0 ? tail.substring(0, q) : tail;
    }

    /** 只显示「稳定 + 已审核」的谱面（需求 1）。服务端参数只是省带宽，这道闸门才是准的。 */
    public boolean isPublicStable() {
        return stable && reviewed;
    }
}
