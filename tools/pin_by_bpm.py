"""按给定的 BPM 在已导出的 1064 个谱面里定位曲目，凑齐 EZ/HD/IN/AT 四个难度并导出。

判定规则：
- bpm 落在目标 ±1.5 内；
- 同一首歌的 4 个难度时长彼此相差不超过 10 秒；
- 难度按物量（音符数）升序 = EZ < HD < IN < AT；
- 音频取「时长最接近且不短于谱面末尾」的那段（整首歌通常比最后一个音符长一点）。

用法：
    python pin_by_bpm.py <cache 目录> <输出目录> 178:ExoplanetaryMirage 165:HappyEnd 146:EntranceToTheChaos
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
        head = f.read(44)
    if head[:4] != b"RIFF":
        return -1.0
    rate = struct.unpack_from("<i", head, 24)[0]
    ch = struct.unpack_from("<h", head, 22)[0] or 1
    bits = struct.unpack_from("<h", head, 34)[0] or 16
    return (os.path.getsize(path) - 44) / (rate * ch * bits / 8)


def pick_group(cands):
    """从候选里挑出最像「同一首歌 4 难度」的一组。"""
    groups = []
    for c in cands:
        near = [x for x in cands if abs(x["duration"] - c["duration"]) <= 10.0]
        if len(near) >= 3:
            groups.append(near)
    if not groups:
        return []
    groups.sort(key=lambda g: (abs(len(g) - 4), max(x["duration"] for x in g) - min(x["duration"] for x in g)))
    group = groups[0]
    # 去重（同 bundle 只留一条）
    uniq, seen = [], set()
    for c in sorted(group, key=lambda c: c["notes"]):
        if c["bundle"] in seen:
            continue
        seen.add(c["bundle"])
        uniq.append(c)
    if len(uniq) > 4:
        # 取物量跨度合理、时长最紧凑的 4 条：按物量分位抽样
        idx = [0, len(uniq) // 3, 2 * len(uniq) // 3, len(uniq) - 1]
        uniq = [uniq[i] for i in sorted(set(idx))]
    return uniq


def main():
    cache, out = sys.argv[1], sys.argv[2]
    specs = []
    for token in sys.argv[3:]:
        bpm, _, name = token.partition(":")
        specs.append((float(bpm), name or token))

    manifest = json.load(open(os.path.join(cache, "manifest.json"), encoding="utf-8"))
    charts = manifest["charts"]
    audio = [(f, wav_seconds(os.path.join(cache, "audio", f)))
             for f in os.listdir(os.path.join(cache, "audio"))]
    audio = [(f, d) for f, d in audio if d > 30]
    audio.sort(key=lambda t: t[1])

    os.makedirs(out, exist_ok=True)
    for target, name in specs:
        cands = [c for c in charts if abs(c["bpm"] - target) <= 1.5 and c["duration"] > 20]
        cands.sort(key=lambda c: c["duration"])
        print(f"\n=== {name} (目标 bpm≈{target}) 候选 {len(cands)} 条 ===")
        for c in cands:
            print(f"   bpm={c['bpm']:>7} 物量={c['notes']:>5} 末尾={c['duration']:>7.1f}s  {c['asset']}  {c['bundle'][:8]}")

        group = pick_group(cands)
        if len(group) < 3:
            print("   ✗ 凑不齐 4 个难度，跳过")
            continue
        diffs = DIFFS[-len(group):] if len(group) < 4 else DIFFS
        song_dir = os.path.join(out, re.sub(r"[^0-9A-Za-z_]+", "_", name) or "song")
        os.makedirs(song_dir, exist_ok=True)
        for c, diff in zip(group, diffs):
            shutil.copy(os.path.join(cache, "charts", c["file"]),
                        os.path.join(song_dir, f"chart_{diff}.json"))
        dur = max(c["duration"] for c in group)
        best = min(audio, key=lambda t: abs(t[1] - dur) if t[1] >= dur - 3 else 999, default=None)
        if best:
            shutil.copy(os.path.join(cache, "audio", best[0]), os.path.join(song_dir, "music.wav"))
        print(f"   ✓ 选定 4 难度: {diffs}")
        for c, d in zip(group, diffs):
            print(f"      {d}: 物量 {c['notes']:>5}  bpm {c['bpm']}  末尾 {c['duration']:.1f}s")
        print(f"      音频: {best[0] if best else '无'} ({best[1]:.1f}s)" if best else "      音频: 无")
        json.dump({"song": name, "bpm": target,
                   "charts": {d: {"notes": c["notes"], "bundle": c["bundle"]} for d, c in zip(diffs, group)},
                   "audio": best[0] if best else None},
                  open(os.path.join(song_dir, "meta.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
