"""量化 #3：同一张贴图、同一批像素，Phira（GL_LINEAR）与我们（MC 默认 NEAREST）的差异。

① 公式层：texel * color（Phira）/ texel * (1,1,1,a)（我们）
② 采样层：dest 169x17（1280 宽窗口，note 宽 = 1280*0.13175 ≈ 169px，高 = 169*100/989 ≈ 17px）
   - GL_NEAREST：取最近 texel
   - GL_LINEAR（无 mipmap）：取 4 邻域双线性
"""
from PIL import Image

TINT_PHIRA = (255, 255, 255)      # PGR 谱面 Note.color = WHITE（pgr.rs:223）
ALPHA_PHIRA = 1.0                 # 未来音符 fade=1、note.alpha=1、ctrl=1、res.alpha=1


def sample_nearest(px, w, h, dw, dh):
    out = []
    for y in range(dh):
        sy = min(h - 1, int((y + 0.5) * h / dh))
        for x in range(dw):
            sx = min(w - 1, int((x + 0.5) * w / dw))
            out.append(px[sy * w + sx])
    return out


def sample_linear(px, w, h, dw, dh):
    out = []
    for y in range(dh):
        fy = min(h - 1.0, max(0.0, (y + 0.5) * h / dh - 0.5))
        y0 = int(fy)
        y1 = min(h - 1, y0 + 1)
        ty = fy - y0
        for x in range(dw):
            fx = min(w - 1.0, max(0.0, (x + 0.5) * w / dw - 0.5))
            x0 = int(fx)
            x1 = min(w - 1, x0 + 1)
            tx = fx - x0
            acc = [0.0] * 4
            for (yy, wy) in ((y0, 1 - ty), (y1, ty)):
                for (xx, wx) in ((x0, 1 - tx), (x1, tx)):
                    p = px[yy * w + xx]
                    wgt = wx * wy
                    for i in range(4):
                        acc[i] += p[i] * wgt
            out.append(tuple(acc))
    return out


def stats(name, px):
    n = len(px)
    mean = [sum(p[i] for p in px) / n for i in range(4)]
    white = sum(1 for p in px if p[0] >= 250 and p[1] >= 250 and p[2] >= 250 and p[3] >= 250)
    lum = [0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] for p in px if p[3] > 8]
    mean_lum = sum(lum) / len(lum) if lum else 0
    peak = max(lum) if lum else 0
    # 合成到黑底 / 亮底（MC 世界透出来）后的可见亮度
    def comp(bg):
        s = 0.0
        for p in px:
            a = p[3] / 255.0
            r = p[0] * a + bg * (1 - a)
            g = p[1] * a + bg * (1 - a)
            b = p[2] * a + bg * (1 - a)
            s += 0.299 * r + 0.587 * g + 0.114 * b
        return s / len(px)
    print(f"  {name}: meanRGBA=({mean[0]:.1f},{mean[1]:.1f},{mean[2]:.1f},{mean[3]:.1f}) "
          f"纯白像素={100*white/n:.1f}% 可见亮度均值={mean_lum:.1f} 峰值={peak:.1f} "
          f"合成黑底={comp(0):.1f} 合成灰底(110)={comp(110):.1f}")


for slot, path in [("click", r"C:\dsh-work\respack\click.png"),
                   ("drag", r"C:\dsh-work\respack\drag.png"),
                   ("flick", r"C:\dsh-work\respack\flick.png")]:
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = list(im.getdata())
    dw = 169
    dh = max(2, round(dw * h / w))
    print(f"=== {slot}.png {w}x{h} → 画到 {dw}x{dh}（缩放 {w/dw:.2f}x）")
    # ① 公式层：逐像素两个公式
    for (x, y) in [(w // 2, h // 2), (w // 4, h // 2)]:
        t = px[y * w + x]
        ph = tuple(round(t[i] * (TINT_PHIRA[i] if i < 3 else 255) / 255.0, 2) for i in range(3)) + (round(t[3] * ALPHA_PHIRA, 2),)
        ours = (t[0], t[1], t[2], t[3])   # 乘 (1,1,1,1.0)
        print(f"  pixel({x},{y}) texel={t}  Phira={ph}  ours={ours}  ΔRGB={tuple(round(ph[i]-ours[i],2) for i in range(3))}")
    stats("源图            ", px)
    stats("NEAREST(我们)   ", sample_nearest(px, w, h, dw, dh))
    stats("LINEAR (Phira)  ", sample_linear(px, w, h, dw, dh))
