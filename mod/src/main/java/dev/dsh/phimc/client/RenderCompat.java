package dev.dsh.phimc.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * GUI 渲染的跨版本兼容层（1.21.1 ↔ 1.21.11）。
 *
 * <p>1.21.11 换了 GUI 渲染 API：
 * <ul>
 *   <li>贴图必须显式给 {@code RenderPipeline}（{@code RenderPipelines.GUI_TEXTURED}）；</li>
 *   <li>染色/淡出从「{@code setShaderColor} + drawTexture」改成 {@code drawTexture(..., int color)}
 *       的颜色参数（{@code setShaderColor} 已删除）；</li>
 *   <li>GUI 矩阵从 {@code MatrixStack}（3D，带 z）改成 {@code Matrix3x2fStack}
 *       （{@code pushMatrix/popMatrix}、{@code translate(x,y)}、{@code rotate(rad)}、{@code scale(sx,sy)}）；</li>
 *   <li>{@code NativeImageBackedTexture(NativeImage)} 改成 {@code (Supplier&lt;String&gt; 名字, NativeImage)}。</li>
 * </ul>
 *
 * <p>这里把「同一件事」包成签名不变的静态方法，调用点只写一份（不用每个 draw 都套 {@code //? if}）。
 * 1.21.1 那条分支走的是原来的 setShaderColor + drawTexture 组合，渲染结果与改动前逐字节等价。
 */
public final class RenderCompat {

    /** 不染色（= 白色 × 原 alpha）。 */
    public static final int NO_TINT = -1;

    private RenderCompat() {
    }

    /**
     * 把贴图里的一块区域（u/v/regionW/regionH 都是**纹理像素**）拉伸到 {@code (x, y, w, h)}。
     *
     * @param argb 染色 + 淡出（{@link #NO_TINT} = 原样）
     */
    public static void drawTexture(DrawContext ctx, Identifier id, int x, int y, int w, int h,
                                   float u, float v, int regionW, int regionH,
                                   int texW, int texH, int argb) {
        //? if >=1.21.6 {
        ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                x, y, u, v, w, h, regionW, regionH, texW, texH, argb);
        //?} elif >=1.21.2 {
        /*// 1.21.2 ~ 1.21.5：第一个参数是 Function<Identifier, RenderLayer>（还没换成 RenderPipeline）
        ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, id,
                x, y, u, v, w, h, regionW, regionH, texW, texH, argb);
        *///?} else {
        /*if (argb != NO_TINT) {
            ctx.setShaderColor(((argb >> 16) & 0xFF) / 255f,
                    ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f,
                    ((argb >>> 24) & 0xFF) / 255f);
        }
        ctx.drawTexture(id, x, y, w, h, u, v, regionW, regionH, texW, texH);
        if (argb != NO_TINT) ctx.setShaderColor(1f, 1f, 1f, 1f);
        *///?}
    }

    /** 同 {@link #drawTexture}，颜色用「alpha + RGB 倍率」给（和原来 setShaderColor 的参数一一对应）。 */
    public static void drawTexture(DrawContext ctx, Identifier id, int x, int y, int w, int h,
                                   float u, float v, int regionW, int regionH,
                                   int texW, int texH, double alpha, double r, double g, double b) {
        drawTexture(ctx, id, x, y, w, h, u, v, regionW, regionH, texW, texH,
                tintOf(alpha, r, g, b));
    }

    /**
     * 把整张贴图画到 {@code (x,y,w,h)}，<b>UV 显式写死成 0→1</b>。
     *
     * <p>和 {@link #drawTexture} 的区别：那条走「u/v + 区域像素 + 贴图像素」的参数化路径
     * （MC 内部再算 {@code u1=(u+0)/texW}、{@code u2=(u+uW)/texW}）；这里把
     * <b>uWidth/vHeight 也传成目标宽高</b>——于是 MC 内部算出来的是
     * {@code u2 = (0 + w) / w = 1}、{@code v2 = (0 + h) / h = 1}，
     * <b>与贴图真实像素数无关</b>。不管图是 100×100 还是 6000×4000，
     * 采样范围永远是整张贴图，不可能「只采到一个纹素 → 整块纯色」。
     *
     * <p>三代 API 分支和 {@link #drawTexture} 完全一致（Stonecutter 基线是<b>最高版本</b>，
     * 所以 {@code >=1.21.6} 那一支在源码里是<b>不注释</b>的，其余分支才用 {@code /*...*}{@code //?} 包起来）：
     * <ul>
     *   <li><b>≥1.21.6</b>：{@code drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, uW, vH, texW, texH, argb)}，
     *       颜色是最后一个参数，混合状态由 RenderPipeline 自己带，<b>不能再调 {@code RenderSystem.enableBlend}</b>
     *       （该方法在 1.21.6+ 已移除）。</li>
     *   <li><b>1.21.2 ~ 1.21.5</b>：{@code drawTexture(RenderLayer::getGuiTextured, ...)}，同样带尾参颜色。</li>
     *   <li><b>≤1.21.1</b>：{@code setShaderColor} + <b>显式 {@code RenderSystem.enableBlend()} +
     *       {@code defaultBlendFunc()}</b> + 归一化 UV 重载 {@code drawTexture(id,x,y,u,v,w,h,texW,texH)}。
     *       这一代的 {@code DrawContext.drawTexturedQuad} <b>不碰混合状态</b>（反编译确认：
     *       只 setShaderTexture + setShader + Tessellator.begin + drawWithGlobalProgram），
     *       而 {@code ctx.fill} 那条会调 {@code enableBlend}；辉光图（平均 alpha 4%~52%）
     *       在 blend 关着时整块按 RGB 铺出来，就是「一整块纯色」。</li>
     * </ul>
     */
    public static void drawTextureFull(DrawContext ctx, Identifier id, int x, int y, int w, int h,
                                       double alpha, double r, double g, double b) {
        if (w <= 0 || h <= 0) return;
        int argb = tintOf(alpha, r, g, b);
        //? if >=1.21.6 {
        ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                x, y, 0f, 0f, w, h, w, h, w, h, argb);
        //?} elif >=1.21.2 {
        /*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, id,
                x, y, 0f, 0f, w, h, w, h, w, h, argb);
        *///?} else {
        /*if (argb != NO_TINT) {
            ctx.setShaderColor(((argb >> 16) & 0xFF) / 255f,
                    ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f,
                    ((argb >>> 24) & 0xFF) / 255f);
        }
        // 这一代必须自己开混合（prpr 是 macroquad，混合一直开着，见 line.rs:257-290）
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        // 归一化 UV 重载：u=v=0，texW/texH 取目标宽高 ⇒ UV 恒为 0→1
        ctx.drawTexture(id, x, y, 0f, 0f, w, h, w, h);
        if (argb != NO_TINT) ctx.setShaderColor(1f, 1f, 1f, 1f);
        *///?}
    }

    /** (alpha, r, g, b) → ARGB；全 1 时返回 {@link #NO_TINT}（省掉一次状态切换）。 */
    public static int tintOf(double alpha, double r, double g, double b) {
        double a = clamp01(alpha), rr = clamp01(r), gg = clamp01(g), bb = clamp01(b);
        if (a >= 0.999 && rr >= 0.999 && gg >= 0.999 && bb >= 0.999) return NO_TINT;
        int ai = (int) Math.round(a * 255.0);
        int ri = (int) Math.round(rr * 255.0);
        int gi = (int) Math.round(gg * 255.0);
        int bi = (int) Math.round(bb * 255.0);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 新建一张「由 NativeImage 驱动」的动态贴图（曲绘 / 资源包贴图都走这里）。 */
    public static NativeImageBackedTexture newTexture(Identifier id, NativeImage image) {
        //? if >=1.21.5 {
        return new NativeImageBackedTexture(() -> id.toString(), image);
        //?} else {
        /*return new NativeImageBackedTexture(image);
        *///?}
    }

    /**
     * 同 {@link #newTexture}，但用双线性过滤（GL_LINEAR，不生成 mipmap）——
     * 资源包贴图普遍是大图缩很小，点采样会「挑单像素」显得比 Phira 更亮更刺。
     *
     * <p>1.21.11 把 {@code AbstractTexture.setFilter} 换成了 GpuSampler，只能在构造后改
     * {@code sampler} 字段（protected，所以这里用一个私有子类）。
     */
    public static NativeImageBackedTexture newTextureLinear(Identifier id, NativeImage image) {
        //? if >=1.21.11 {
        return new LinearTexture(() -> id.toString(), image);
        //?} else {
        /*NativeImageBackedTexture texture = newTexture(id, image);
        texture.setFilter(true, false);
        return texture;
        *///?}
    }

    //? if >=1.21.11 {
    /** 1.21.11：把 sampler 换成 LINEAR/LINEAR（REPEAT，关 mipmap），和 Phira 的 GL_LINEAR 一致。 */
    private static final class LinearTexture extends NativeImageBackedTexture {
        LinearTexture(java.util.function.Supplier<String> name, NativeImage image) {
            super(name, image);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().get(
                    com.mojang.blaze3d.textures.AddressMode.REPEAT,
                    com.mojang.blaze3d.textures.AddressMode.REPEAT,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    com.mojang.blaze3d.textures.FilterMode.LINEAR,
                    false);
        }
    }
    //?}

    /** GUI 变换（1.21.1 是 MatrixStack，1.21.11 是 Matrix3x2fStack，这里是两者的公共子集）。 */
    public static Transform transform(DrawContext ctx) {
        return new Transform(ctx);
    }

    /** push/pop/translate/rotate/scale 的薄封装；每个方法都自带版本分支。 */
    public static final class Transform {
        private final DrawContext ctx;

        private Transform(DrawContext ctx) {
            this.ctx = ctx;
        }

        public Transform push() {
            //? if >=1.21.6 {
            ctx.getMatrices().pushMatrix();
            //?} else {
            /*ctx.getMatrices().push();
            *///?}
            return this;
        }

        public Transform pop() {
            //? if >=1.21.6 {
            ctx.getMatrices().popMatrix();
            //?} else {
            /*ctx.getMatrices().pop();
            *///?}
            return this;
        }

        public Transform translate(float x, float y) {
            //? if >=1.21.6 {
            ctx.getMatrices().translate(x, y);
            //?} else {
            /*ctx.getMatrices().translate(x, y, 0f);
            *///?}
            return this;
        }

        /** 绕原点旋转 {@code radians}（与原来 {@code multiply(new Quaternionf().rotateZ(rad))} 同向）。 */
        public Transform rotate(float radians) {
            //? if >=1.21.6 {
            ctx.getMatrices().rotate(radians);
            //?} else {
            /*ctx.getMatrices().multiply(new org.joml.Quaternionf().rotateZ(radians));
            *///?}
            return this;
        }

        public Transform scale(float sx, float sy) {
            //? if >=1.21.6 {
            ctx.getMatrices().scale(sx, sy);
            //?} else {
            /*ctx.getMatrices().scale(sx, sy, 1f);
            *///?}
            return this;
        }
    }
}
