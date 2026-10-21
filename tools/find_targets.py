"""给定 catalog 里的曲目路径关键词，找出它们的 4 个难度谱面和对应音频，导出成 songs/ 目录。

思路：
1. 用 map_charts 的解析结果，拿到「谱面资源路径 -> 候选 bundle」；
2. 只保留真实存在的 bundle 文件，逐个打开确认里面确实是 Chart_XX；
3. 用「同曲 4 个难度 bpm 相同、时长接近」做一致性验证；
4. 音频按最接近时长配对（谱面时长比整首歌短，所以取最接近且不早于谱面结尾的那段）。

用法: python find_targets.py <catalog.json> <bundles 目录> <cache 目录> <输出 songs 目录> 关键词1 关键词2 ...
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

DIFF_ORDER = ["EZ", "HD", "IN", "AT", "Legacy"]


def wav_seconds(path: str) -> float:
    with open(path, "rb") as f:
        head = f.read(44)
    if head[:4] != b"RIFF":
        return -1.0
    rate = struct.unpack_from("<i", head, 24)[0]
    ch = struct.unpack_from("<h", head, 22)[0] or 1
    bits = struct.unpack_from("<h", head, 34)[0] or 16
    return (os.path.getsize(path) - 44) / (rate * ch * bits / 8)


def chart_meta(text: str):
    m = re.search(r'"bpm"\s*:\s*([0-9.]+)', text)
    bpm = float(m.group(1)) if m else 0.0
    unit = 60.0 / (32.0 * bpm) if bpm else 0.0
    times = [float(x) for x in re.findall(r'"time"\s*:\s*([0-9.]+)', text)]
    holds = [float(x) for x in re.findall(r'"holdTime"\s*:\s*([0-9.]+)', text)]
    dur = (max(times) + (max(holds) if holds else 0.0)) * unit if times else 0.0
    return bpm, len(times), dur


def load_chart(path: str):
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


def main() -> None:
    catalog, bundles, cache, out = sys.argv[1:5]
    words = [w.lower() for w in sys.argv[5:]]

    mapping, internal, entries = build(catalog)
    targets = {k: v for k, v in mapping.items()
               if "/Chart_" in k and any(w in k.lower() for w in words)}
    print(f"命中 {len(targets)} 条谱面路径")

    # 展平成 (歌名, 难度) -> 候选 bundle
    songs = {}
    for key, cand in targets.items():
        m = re.match(r"Assets/Tracks/(.+?)\.([^.]+)\.\d+/Chart_(\w+)\.json", key)
        if not m:
            continue
        song, artist, diff = m.group(1), m.group(2), m.group(3)
        songs.setdefault((song, artist), {})[diff] = [c for c in cand if c.endswith(".bundle")]

    audio_files = [(f, wav_seconds(os.path.join(cache, "audio", f)))
                   for f in os.listdir(os.path.join(cache, "audio"))]
    audio_files = [(f, d) for f, d in audio_files if d > 30]

    os.makedirs(out, exist_ok=True)
    for (song, artist), diffs in songs.items():
        rows = []
        for diff, cands in diffs.items():
            for c in cands[:3]:
                p = os.path.join(bundles, os.path.basename(c))
                if not os.path.exists(p):
                    continue
                text = load_chart(p)
                if not text:
                    continue
                bpm, notes, dur = chart_meta(text)
                rows.append((diff, c, bpm, notes, dur, text))
                break  # 每个难度取第一个能读出来的候选

        if len(rows) < 3:
            print(f"  ✗ {song}: 只找到 {len(rows)} 个难度，跳过")
            continue

        # 一致性检查：bpm 应当全相同
        bpms = {round(r[2], 3) for r in rows}
        if len(bpms) > 1:
            print(f"  ! {song}: bpm 不一致 {bpms}，可能有一条映射是错的")
        dur = max(r[4] for r in rows)
        best = min(audio_files, key=lambda t: abs(t[1] - dur), default=None)
        print(f"  ✓ {song} ({artist}) 难度={[r[0] for r in rows]} bpm={sorted(bpms)} "
              f"谱面末尾={dur:.1f}s 音频={best[0] if best else '无'} ({best[1]:.1f}s)" if best else "")

        safe = re.sub(r"[^0-9A-Za-z_\u4e00-\u9fff]+", "_", song)[:40] or "song"
        d = os.path.join(out, safe)
        os.makedirs(d, exist_ok=True)
        for diff, c, bpm, notes, dur2, text in rows:
            open(os.path.join(d, f"chart_{diff}.json"), "w", encoding="utf-8").write(text)
        if best and abs(best[1] - dur) < 40:
            shutil.copy(os.path.join(cache, "audio", best[0]), os.path.join(d, "music.wav"))
        json.dump({"song": song, "artist": artist,
                   "charts": {r[0]: {"bundle": r[1], "notes": r[3], "duration": round(r[4], 2)} for r in rows}},
                  open(os.path.join(d, "meta.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print("完成 ->", out)


if __name__ == "__main__":
    main()
