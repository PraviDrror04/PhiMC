"""按时长定位曲目：在同 bpm、时长接近的谱面里凑齐 EZ/HD/IN/AT，并配上时长最接近的音频。

用法：
    python pin_by_duration.py <cache 目录> <输出目录> 178:exo 165:HappyEnd 146:Entrance
"""
import json
import os
import re
import shutil
import struct
import sys
from collections import defaultdict

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


def main():
    cache, out = sys.argv[1], sys.argv[2]
    specs = []
    for token in sys.argv[3:]:
        dur, _, name = token.partition(":")
        specs.append((float(dur), name or token))

    manifest = json.load(open(os.path.join(cache, "manifest.json"), encoding="utf-8"))
    charts = [c for c in manifest["charts"] if c["duration"] > 20]
    audio = [(f, wav_seconds(os.path.join(cache, "audio", f)))
             for f in os.listdir(os.path.join(cache, "audio"))]
    audio = [(f, d) for f, d in audio if d > 20]

    os.makedirs(out, exist_ok=True)
    for target, name in specs:
        print(f"\n=== {name}  目标时长≈{target}s ===")
        # 先看最接近目标时长的音频
        near_audio = sorted(audio, key=lambda t: abs(t[1] - target))[:3]
        for f, d in near_audio:
            print(f"   音频候选: {f}  {d:.1f}s  (差 {abs(d-target):.1f}s)")

        # 谱面：按 bpm 分组，再在组内找时长接近且四条齐全的
        by_bpm = defaultdict(list)
        for c in charts:
            if abs(c["duration"] - target) <= 12:
                by_bpm[round(c["bpm"], 1)].append(c)

        best = None
        for bpm, group in by_bpm.items():
            names = {c["asset"] for c in group}
            if len(group) < 3:
                continue
            group.sort(key=lambda c: c["duration"])
            # 取时长最接近目标的那簇（跨度的 60% 内）
            span = max(c["duration"] for c in group) - min(c["duration"] for c in group)
            core = [c for c in group if abs(c["duration"] - target) <= max(4.0, span / 2)]
            if len(core) < 3:
                continue
            score = (4 - min(len(core), 4)) * 10 + span
            if best is None or score < best[0]:
                best = (score, bpm, core, names)

        if not best:
            print("   ✗ 找不到谱面")
            continue
        _, bpm, core, names = best
        core.sort(key=lambda c: c["notes"])
        # 去掉 Legacy 这类额外难度，保留最像 EZ/HD/IN/AT 的四条
        def rank(c):
            order = {"Chart_EZ": 0, "Chart_HD": 1, "Chart_IN": 2, "Chart_AT": 3}
            return order.get(c["asset"], 9)
        core = [c for c in core if c["asset"] in ("Chart_EZ", "Chart_HD", "Chart_IN", "Chart_AT")]
        core.sort(key=rank)
        if len(core) < 3:
            print(f"   ✗ bpm={bpm} 组里只有 {len(core)} 个标准难度")
            continue

        song_dir = os.path.join(out, re.sub(r"[^0-9A-Za-z_]+", "_", name) or "song")
        os.makedirs(song_dir, exist_ok=True)
        for c in core:
            diff = c["asset"].split("_")[1]
            shutil.copy(os.path.join(cache, "charts", c["file"]),
                        os.path.join(song_dir, f"chart_{diff}.json"))
        best_audio = near_audio[0]
        if abs(best_audio[1] - target) <= 8:
            shutil.copy(os.path.join(cache, "audio", best_audio[0]),
                        os.path.join(song_dir, "music.wav"))
        print(f"   ✓ bpm={bpm} 难度={[c['asset'].split('_')[1] for c in core]}")
        for c in core:
            print(f"      {c['asset']:12s} 物量 {c['notes']:>5}  末尾 {c['duration']:>7.1f}s  {c['bundle'][:8]}")
        print(f"      音频 {best_audio[0]} ({best_audio[1]:.1f}s)")
        json.dump({"song": name, "bpm": bpm,
                   "charts": {c["asset"].split("_")[1]: {"notes": c["notes"], "duration": c["duration"]}
                              for c in core},
                   "audio": best_audio[0], "audioSeconds": round(best_audio[1], 1)},
                  open(os.path.join(song_dir, "meta.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
