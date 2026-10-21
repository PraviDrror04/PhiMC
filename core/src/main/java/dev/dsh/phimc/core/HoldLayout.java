package dev.dsh.phimc.core;

/**
 * {@code hold.png} 的三段切割 / 尺寸换算，**逐行照 prpr {@code core/resource.rs:142-183} +
 * {@code core/note.rs:270-357}**。
 *
 * <p>资源包的 {@code info.yml} 里 {@code holdAtlas: [a, b]} 是「贴图里头部 / 尾部各占多少<b>像素</b>」，
 * 但两个数的归属很容易记反，这里按源码定死（不可改）：
 * <ul>
 *   <li>{@code atlas[0]} = {@code hold_tail_rect()} 的高度，取贴图**顶部**那 {@code atlas[0]} 行；</li>
 *   <li>{@code atlas[1]} = {@code hold_head_rect()} 的高度，取贴图**底部**那 {@code atlas[1]} 行；</li>
 *   <li>中间剩下的 {@code texH - atlas[0] - atlas[1]} 行是身体（{@code hold_body_rect()}）。</li>
 * </ul>
 * 依据：{@code resource.rs:168-182} 的 rect 用的是「归一化且 y 向上」的 UV，{@code note.rs:79} 的
 * {@code flip_y = true} 把 v=0（图片第一行）映射到屏幕上方 —— 所以 {@code hold_tail_rect} 的
 * {@code y = 0} 是图片顶行（远端尾巴），{@code hold_head_rect} 的 {@code y = 1 - b/H} 是图片底行
 * （靠判定线那一端的头）。真实 Phigros 官方包 {@code hold.png} 也是这个画法：头（收窄的一端）在图片底部。
 *
 * <h2>头 / 尾的尺寸（Phira 的 {@code hold_ratio}）</h2>
 * {@code note.rs:291} 的 {@code ratio = style.hold_ratio() = texH / texW}；
 * {@code note.rs:324/342} 的 {@code hf.y = r.h / r.w * scale * ratio}，其中
 * {@code r.h = capPx / texH}、{@code r.w = 1}、{@code scale = 音符半宽 = noteW / 2}，所以
 * <pre>
 *   hf.y        = capPx / texH * (noteW / 2) * (texH / texW) = capPx * noteW / (2 * texW)
 *   dest_size.y = 2 * hf.y                                  = capPx * noteW / texW
 * </pre>
 * 也就是说：<b>整张贴图的宽度缩放到 noteW 时，头/尾的高度自然跟着缩放</b> ——
 * 这就是「保持贴图宽高比」的写法，{@link #capDestHeight(int, int)} 直接按这个公式算。
 *
 * <p>纯 Java、零 Minecraft 依赖：渲染（{@code PlayScreen}）和 headless 验收
 * （{@code core/src/test/.../HoldAtlasCheck.java}）用的是同一份代码。
 */
public record HoldLayout(int texW, int texH, int tailPx, int headPx) {

    /** 夹取到合法范围（贴图尺寸异常 / info.yml 没写 holdAtlas 时不让调用方崩）。 */
    public static HoldLayout of(int texW, int texH, int holdTailPx, int holdHeadPx) {
        int w = Math.max(0, texW);
        int h = Math.max(0, texH);
        int tail = Math.max(0, Math.min(holdTailPx, h));
        int head = Math.max(0, Math.min(holdHeadPx, h - tail));
        return new HoldLayout(w, h, tail, head);
    }

    /** 身体占的像素行数（贴图中间那段）。 */
    public int bodyPx() {
        return Math.max(0, texH - tailPx - headPx);
    }

    /** 身体源区域的第一行（贴图坐标，0 = 顶行）。对应 Phira {@code hold_body_rect().y}。 */
    public int bodySrcY() {
        return tailPx;
    }

    /** 尾巴源区域的第一行：贴图顶行。对应 Phira {@code hold_tail_rect().y = 0}。 */
    public int tailSrcY() {
        return 0;
    }

    /** 头源区域的第一行：贴图倒数 {@code headPx} 行。对应 Phira {@code hold_head_rect().y}。 */
    public int headSrcY() {
        return texH - headPx;
    }

    /** Phira {@code NoteStyle::hold_ratio()}：{@code texH / texW}。 */
    public double holdRatio() {
        return texW <= 0 ? 1.0 : (double) texH / (double) texW;
    }

    /**
     * Phira {@code note.rs:324} 的 {@code hf.y}（头/尾**半个**高度，世界单位），
     * 公式原样保留，方便和 {@link #capDestHeight(int, int)} 交叉验证。
     */
    public double capHalfHeight(int noteW, int capPx) {
        if (texH <= 0 || capPx <= 0) return 0.0;
        double rH = (double) capPx / (double) texH;   // r.h（归一化高度）
        double rW = 1.0;                              // r.w
        double scale = noteW / 2.0;                   // note.rs:210 的 scale（半宽）
        return rH / rW * scale * holdRatio();
    }

    /** 头/尾的绘制高度（像素）= Phira 的 {@code (hf * 2).y}。tile 太小时至少 1px。 */
    public int capDestHeight(int noteW, int capPx) {
        if (capPx <= 0) return 0;
        return Math.max(1, (int) Math.round(capHalfHeight(noteW, capPx) * 2.0));
    }

    /**
     * {@code holdRepeat: true} 时身体「一圈」的高度（像素）：身体纹理按自然比例平铺，
     * 即整张宽 {texW} 缩放到 noteW 时，{@link #bodyPx()} 行对应的屏幕高度。
     * 对应 Phira {@code resource.rs:237-254}（把身体裁出来设 Repeat）+
     * {@code note.rs:307-315}（source 高度 {@code (top-bottom)/scale/2*width/height}）。
     */
    public int bodyTileHeight(int noteW) {
        if (texW <= 0 || bodyPx() <= 0) return 0;
        return Math.max(1, (int) Math.round((double) noteW * bodyPx() / texW));
    }

    /** info.yml 没给 holdAtlas（两段都是 0）时只能整张拉伸。 */
    public boolean hasAtlas() {
        return tailPx > 0 || headPx > 0;
    }
}
