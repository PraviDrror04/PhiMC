"""把 1064 个谱面按「同一首歌」聚类，再按给定时长挑出目标曲目。

判定一组的条件：
- bpm 完全相同；
- 四条时长彼此相差 ≤ 6 秒；
- 恰好包含 EZ / HD / IN / AT（不重复、不含 Legacy/Fake）；
- 物量严格递增（EZ < HD < IN < AT）。

用法: python cluster_songs.py <cache> <输出目录> 178:exo 165:HappyEnd 146:Entrance
"""
import json
import os
import re
import shutil
import struct
import sys

DIFFS = ["EZ", "HD", "IN", "AT"]


def wav_seconds(path):
    with open(path, "rb") as f:
        h = f.read(44)
    if h[:4] != b"RIFF":
        return -1.0
    rate = struct.unpack_from("<i", h, 24)[0]
    ch = struct.unpack_from("<h", h, 22)[0] or 1
    bits = struct.unpack_from("<h", h, 34)[0] or 16
    return (os.path.getsize(path) - 44) / (rate * ch * bits / 8)


def groups_of(charts):
    """所有满足「四条同曲」的组合，返回 [(bpm, {diff: chart})]"""
    out = []
    by_bpm = {}
    for c in charts:
        by_bpm.setdefault(round(c["bpm"], 1), []).append(c)
    for bpm, items in by_bpm.items():
        # 以每条谱面的时长为中心，±6s 取窗口
        for center in items:
            win = [c for c in items if abs(c["duration"] - center["duration"]) <= 6.0]
            diffs = {}
            ok = True
            for c in win:
                d = c["asset"].replace("Chart_", "")
                if d not in DIFFS:
                    continue
                if d in diffs:
                    ok = False
                    break
                diffs[d] = c
            if not ok or len(diffs) < 3:
                continue
            seq = [diffs[d]["notes"] for d in DIFFS if d in diffs]
            if any(seq[i] >= seq[i + 1] for i in range(len(seq) - 1)):
                continue
            out.append((bpm, dict(diffs)))
    # 去重
    uniq, seen = [], set()
    for bpm, d in out:
        key = (bpm, tuple(sorted((k, v["bundle"]) for k, v in d.items())))
        if key in seen:
            continue
        seen.add(key)
        uniq.append((bpm, d))
    return uniq


def main():
    cache, out = sys.argv[1], sys.argv[2]
    targets = []
    for tok in sys.argv[3:]:
        dur, _, name = tok.partition(":")
        targets.append((float(dur), name or tok))

    manifest = json.load(open(os.path.join(cache, "manifest.json"), encoding="utf-8"))
    charts = [c for c in manifest["charts"] if c["duration"] > 20]
    audio = [(f, wav_seconds(os.path.join(cache, "audio", f)))
             for f in os.listdir(os.path.join(cache, "audio"))]
    audio = [a for a in audio if a[1] > 30]
    all_groups = groups_of(charts)
    print(f"共找到 {len(all_groups)} 组「四条同曲」候选")

    os.makedirs(out, exist_ok=True)
    for target, name in targets:
        # 组内最长时长最接近目标者优先
        cand = []
        for bpm, d in all_groups:
            end = max(c["duration"] for c in d.values())
            cand.append((abs(end - target), bpm, d, end))
        cand.sort(key=lambda t: t[0])
        print(f"\n=== {name} 目标≈{target}s ===")
        for err, bpm, d, end in cand[:3]:
            notes = {k: v["notes"] for k, v in d.items()}
            print(f"   bpm={bpm} 末尾={end:.1f}s 差{err:.1f}s 难度={sorted(d)} 物量={notes}")
        err, bpm, d, end = cand[0]
        song_dir = os.path.join(out, re.sub(r"[^0-9A-Za-z_]+", "_", name) or "song")
        os.makedirs(song_dir, exist_ok=True)
        for diff in DIFFS:
            if diff in d:
                shutil.copy(os.path.join(cache, "charts", d[diff]["file"]),
                            os.path.join(song_dir, f"chart_{diff}.json"))
        best_audio = min(audio, key=lambda t: abs(t[1] - target), default=None)
        if best_audio and abs(best_audio[1] - target) < 20:
            shutil.copy(os.path.join(cache, "audio", best_audio[0]),
                        os.path.join(song_dir, "music.wav"))
            print(f"   ✓ 采用 bpm={bpm} 音频={best_audio[0]} ({best_audio[1]:.1f}s)")
        else:
            print(f"   ✓ 采用 bpm={bpm}（无匹配音频，静音）")
        json.dump({"song": name, "bpm": bpm, "endSeconds": round(end, 1),
                   "charts": {k: v["notes"] for k, v in d.items()},
                   "audio": best_audio[0] if best_audio else None},
                  open(os.path.join(song_dir, "meta.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
