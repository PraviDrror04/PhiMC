"""用「一条已知谱面」反推整首歌的 4 个难度。

思路（绕开坏掉的 catalog entry 表）：
1. catalog 能给出部分「曲目路径 -> bundle」的候选；
2. 从候选中只要能读出任意一条谱面，就拿到了这首歌的 bpm 与时长；
3. 同一首歌的 4 个难度 bpm 完全相同、时长一致 —— 用这个特征在全部 1064 个谱面里
   把同一首歌的其余难度找回来（音符数升序即 EZ/HD/IN/AT）；
4. 音频按最接近的时长配对（谱面末尾比整首歌短，取最近的即可）。
"""
import json
import os
import re
import shutil
import struct
import sys

import UnityPy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from map_charts import build  # noqa: E402

DIFFS = ["EZ", "HD", "IN", "AT"]


def wav_seconds(path):
    with open(path, "rb") as f:
        head = f.read(44)
    if head[:4] != b"RIFF":
        return -1.0
    rate = struct.unpack_from("<i", head, 24)[0]
    ch = struct.unpack_from("<h", head, 22)[0] or 1
    bits = struct.unpack_from("<h", head, 34)[0] or 16
    return (os.path.getsize(path) - 44) / (rate * ch * bits / 8)


def chart_meta(text):
    m = re.search(r'"bpm"\s*:\s*([0-9.]+)', text)
    bpm = float(m.group(1)) if m else 0.0
    unit = 60.0 / (32.0 * bpm) if bpm else 0.0
    times = [float(x) for x in re.findall(r'"time"\s*:\s*([0-9.]+)', text)]
    holds = [float(x) for x in re.findall(r'"holdTime"\s*:\s*([0-9.]+)', text)]
    dur = (max(times) + (max(holds) if holds else 0.0)) * unit if times else 0.0
    return bpm, len(times), dur


def read_chart(path):
    try:
        env = UnityPy.load(path)
    except Exception:
        return None
    for obj in env.objects:
        if obj.type.name != "TextAsset":
            continue
        d = obj.read()
        s = d.m_Script
        if isinstance(s, bytes):
            s = s.decode("utf-8", "ignore")
        if "judgeLineList" in s:
            return s
    return None


def main():
    catalog, bundles, cache, out = sys.argv[1:5]
    words = [w.lower() for w in sys.argv[5:]]

    mapping, _, _ = build(catalog)
    manifest = json.load(open(os.path.join(cache, "manifest.json"), encoding="utf-8"))
    by_file = {c["file"]: c for c in manifest["charts"]}

    # 1) 每条目标谱面路径 -> 可读出的谱面
    seeds = {}
    for key, cands in mapping.items():
        if "/Chart_" not in key or not any(w in key.lower() for w in words):
            continue
        m = re.match(r"Assets/Tracks/(.+?)\.([^.]+)\.\d+/Chart_(\w+)\.json", key)
        if not m:
            continue
        song, artist, diff = m.group(1), m.group(2), m.group(3)
        for c in cands:
            p = os.path.join(bundles, os.path.basename(c))
            if not os.path.exists(p):
                continue
            text = read_chart(p)
            if not text:
                continue
            bpm, notes, dur = chart_meta(text)
            if bpm <= 0 or dur <= 0:
                continue
            seeds.setdefault((song, artist), (bpm, dur, diff, text))
            break

    audio = [(f, wav_seconds(os.path.join(cache, "audio", f)))
             for f in os.listdir(os.path.join(cache, "audio"))]
    audio = [(f, d) for f, d in audio if d > 30]

    os.makedirs(out, exist_ok=True)
    for (song, artist), (bpm, dur, seed_diff, _t) in seeds.items():
        # 2) 在全量谱面里按 (bpm, 时长) 找同一首歌
        cluster = []
        for c in manifest["charts"]:
            if abs(c["bpm"] - bpm) > 0.01:
                continue
            if abs(c["duration"] - dur) > 8.0:
                continue
            cluster.append(c)
        cluster.sort(key=lambda c: c["notes"])
        # 同 bpm 的歌可能撞车：只保留「时长彼此相差不超过 10s」的那一组
        if len(cluster) > 4:
            best_group = None
            for i in range(len(cluster)):
                group = [c for c in cluster if abs(c["duration"] - cluster[i]["duration"]) <= 10.0]
                if best_group is None or len(group) > len(best_group):
                    best_group = group
            cluster = best_group or cluster
        if len(cluster) < 3:
            print(f"  ✗ {song}: 只聚到 {len(cluster)} 个难度")
            continue
        diffs = DIFFS[-len(cluster):] if len(cluster) < 4 else DIFFS
        safe = re.sub(r"[^0-9A-Za-z_\u4e00-\u9fff]+", "_", song)[:40] or "song"
        d = os.path.join(out, safe)
        os.makedirs(d, exist_ok=True)
        for c, diff in zip(cluster, diffs):
            shutil.copy(os.path.join(cache, "charts", c["file"]),
                        os.path.join(d, f"chart_{diff}.json"))
        best = min(audio, key=lambda t: abs(t[1] - dur), default=None)
        if best and abs(best[1] - dur) < 45:
            shutil.copy(os.path.join(cache, "audio", best[0]), os.path.join(d, "music.wav"))
        print(f"  ✓ {song} ({artist}) bpm={bpm} 末尾={dur:.1f}s 难度={diffs} "
              f"物量={[c['notes'] for c in cluster]} 音频={best[0] if best else '无'} "
              f"({best[1]:.1f}s)" if best else f"  ✓ {song}")
        json.dump({"song": song, "artist": artist, "bpm": bpm,
                   "charts": {d2: {"notes": c["notes"], "file": c["file"]}
                              for d2, c in zip(diffs, cluster)}},
                  open(os.path.join(d, "meta.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
    print("完成 ->", out)


if __name__ == "__main__":
    main()
