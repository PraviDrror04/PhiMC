from PIL import Image
import collections, sys

for p in [r"C:\dsh-work\respack\click.png", r"C:\dsh-work\respack\drag.png", r"C:\dsh-work\respack\flick.png"]:
    im = Image.open(p).convert("RGBA")
    w, h = im.size
    px = list(im.getdata())
    print(f"=== {p} {w}x{h} ({w*h} px)")
    # 主色统计：按 alpha 加权
    tot = sum(1 for *_, a in px)
    opaque = [t for t in px if t[3] > 200]
    semi = [t for t in px if 20 < t[3] <= 200]
    clear = [t for t in px if t[3] <= 20]
    print(f"    alpha>200: {len(opaque)} ({100*len(opaque)/tot:.1f}%)   20<a<=200: {len(semi)} ({100*len(semi)/tot:.1f}%)   a<=20: {len(clear)} ({100*len(clear)/tot:.1f}%)")
    def avg(rows):
        if not rows: return None
        n = len(rows)
        return tuple(round(sum(r[i] for r in rows) / n, 1) for i in range(4))
    print(f"    mean RGBA (全部)      = {avg(px)}")
    print(f"    mean RGBA (alpha>200) = {avg(opaque)}")
    # 最亮 / 最不亮的实心像素
    if opaque:
        br = max(opaque, key=lambda t: t[0]+t[1]+t[2])
        dk = min(opaque, key=lambda t: t[0]+t[1]+t[2])
        print(f"    最亮实心像素={br}  最暗实心像素={dk}")
    cnt = collections.Counter(px).most_common(5)
    print(f"    最常见像素(前5)={cnt}")
    # 中心行中点的像素（"同一像素"量化用）
    for (x, y) in [(w//2, h//2), (w//4, h//2), (w//2, h//4)]:
        print(f"    pixel({x},{y}) = {px[y*w+x]}")
