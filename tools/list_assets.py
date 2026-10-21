"""只列名字、不落盘：快速摸清 bundle 里谱面/音频/曲绘的命名规律。"""
import os
import sys
import time

import UnityPy

src = sys.argv[1]
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 0

files = sorted((os.path.join(dp, f) for dp, _, fs in os.walk(src) for f in fs),
               key=os.path.getsize)
if limit:
    files = files[:limit]

t0 = time.time()
for i, p in enumerate(files):
    try:
        env = UnityPy.load(p)
    except Exception:
        continue
    hits = []
    for obj in env.objects:
        t = obj.type.name
        if t not in ("TextAsset", "AudioClip"):
            continue
        try:
            d = obj.read()
        except Exception:
            continue
        nm = getattr(d, "m_Name", "")
        if t == "TextAsset":
            s = d.m_Script
            if isinstance(s, bytes):
                s = s.decode("utf-8", "ignore")
            if "judgeLineList" in s:
                hits.append(f"CHART '{nm}' {len(s)}B")
        else:
            has = bool(getattr(d, "samples", None))
            hits.append(f"AUDIO '{nm}' samples={has}")
    if hits:
        print(f"{os.path.basename(p)} ({os.path.getsize(p)//1024}KB): " + " | ".join(hits), flush=True)
    if i and i % 300 == 0:
        print(f"... {i}/{len(files)} {time.time()-t0:.0f}s", flush=True)
print(f"done {len(files)} bundles in {time.time()-t0:.0f}s")
