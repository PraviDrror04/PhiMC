"""把 bundle 目录里的谱面 JSON 和音频全部导出，并生成清单。

产物：
    <out>/charts/<bundleId>_Chart_EZ.json
    <out>/audio/<clipName>.ogg|wav
    <out>/manifest.json   {charts:[{bundle,asset,notes,bpm,duration}], audio:[{name,bytes}]}
"""
import json
import os
import re
import sys
import time

import UnityPy


def read_chart_meta(text: str):
    """从官方谱面 JSON 里粗略算出 BPM 与时长，用于给「谱面↔音频」配对。"""
    bpm = 0.0
    m = re.search(r'"bpm"\s*:\s*([0-9.]+)', text)
    if m:
        bpm = float(m.group(1))
    unit = 60.0 / (32.0 * bpm) if bpm else 0.0
    times = [float(x) for x in re.findall(r'"time"\s*:\s*([0-9.]+)', text)]
    holds = [float(x) for x in re.findall(r'"holdTime"\s*:\s*([0-9.]+)', text)]
    notes = len(times)
    dur = (max(times) + (max(holds) if holds else 0.0)) * unit if times else 0.0
    return bpm, notes, dur


def main() -> None:
    src = sys.argv[1]
    out = sys.argv[2]
    os.makedirs(os.path.join(out, "charts"), exist_ok=True)
    os.makedirs(os.path.join(out, "audio"), exist_ok=True)

    files = sorted((os.path.join(dp, f) for dp, _, fs in os.walk(src) for f in fs),
                   key=os.path.getsize)
    charts, audio = [], []
    t0 = time.time()
    for i, p in enumerate(files):
        if i % 250 == 0:
            print(f"[{i}/{len(files)}] {time.time()-t0:.0f}s charts={len(charts)} audio={len(audio)}", flush=True)
        bid = os.path.basename(p).split(".")[0]
        try:
            env = UnityPy.load(p)
        except Exception:
            continue
        for obj in env.objects:
            t = obj.type.name
            try:
                if t == "TextAsset":
                    d = obj.read()
                    s = d.m_Script
                    if isinstance(s, bytes):
                        s = s.decode("utf-8", "ignore")
                    if "judgeLineList" not in s:
                        continue
                    name = getattr(d, "m_Name", "chart")
                    fn = f"{bid}_{name}.json"
                    with open(os.path.join(out, "charts", fn), "w", encoding="utf-8") as f:
                        f.write(s)
                    bpm, notes, dur = read_chart_meta(s)
                    charts.append({"bundle": bid, "asset": name, "file": fn,
                                   "bpm": bpm, "notes": notes, "duration": round(dur, 2)})
                elif t == "AudioClip":
                    d = obj.read()
                    samples = getattr(d, "samples", None)
                    if not samples:
                        continue
                    data = b"".join(samples.values())
                    if len(data) < 4096:
                        continue
                    name = re.sub(r"[^0-9A-Za-z_\-.]+", "_", getattr(d, "m_Name", bid))[:60]
                    fn = f"{name}.wav" if data[:4] == b"RIFF" else f"{name}.wav"
                    with open(os.path.join(out, "audio", fn), "wb") as f:
                        f.write(data)
                    audio.append({"name": getattr(d, "m_Name", ""), "file": fn, "bytes": len(data)})
            except Exception:
                continue

    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump({"charts": charts, "audio": audio}, f, ensure_ascii=False, indent=1)
    print(f"done {time.time()-t0:.0f}s charts={len(charts)} audio={len(audio)}")


if __name__ == "__main__":
    main()
