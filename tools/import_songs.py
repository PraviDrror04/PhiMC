"""从 Phigros XAPK 里导入谱面，整理成 PhiMC 能读的 songs/ 目录。

思路：
1. 解包 XAPK 的分片（base / config.arm64_v8a / UnityDataAssetPack / UnityStreamingAssetsPack）；
2. 从 Addressables bundle 里导出所有谱面（TextAsset）和音频（AudioClip）；
3. 同一首歌的 4 个难度共享 bpm 与时长，所以用 (bpm, 时长) 聚类就能把谱面归到歌；
4. 音频按「时长最接近」配对；
5. 输出 songs/<name>/chart_ez.json ... music.wav

用法：
    python import_songs.py <XAPK 或已解包目录> <输出 songs 目录> [--only 关键词1,关键词2]
"""
import base64
import json
import os
import re
import shutil
import struct
import sys
import time

import UnityPy

DIFFS = ["EZ", "HD", "IN", "AT", "Legacy"]


# ---------- Addressables catalog：拿「曲名 → 谱面资源路径」 ----------

def read_keys(blob: bytes):
    count = struct.unpack_from("<i", blob, 0)[0]
    out, off = [], 4
    while len(out) < count and off + 5 <= len(blob):
        flag = blob[off]
        ln = struct.unpack_from("<i", blob, off + 1)[0]
        start = off
        off += 5
        if ln < 0 or off + ln > len(blob):
            break
        out.append((start, blob[off:off + ln].decode("utf-16-le" if flag == 1 else "utf-8", "ignore")))
        off += ln
    return out


def song_names(catalog_path: str):
    """从 catalog 里抽「Assets/Tracks/<曲目>.<曲师>.0/Chart_XX.json」→ 曲目名。"""
    names = set()
    try:
        obj = json.loads(open(catalog_path, "rb").read().decode("utf-8"))
        for _, key in read_keys(base64.b64decode(obj["m_KeyDataString"])):
            m = re.match(r"Assets/Tracks/(.+?)\.([^.]+)\.\d+/Chart_", key)
            if m:
                names.add(m.group(1))
    except Exception as e:
        print("catalog 解析失败:", e)
    return names


# ---------- 解包 ----------

def unpack(xapk: str, work: str) -> str:
    """返回 StreamingAssets 的 bundle 目录。已解包则直接复用。"""
    import zipfile

    bundles = os.path.join(work, "bundles")
    if os.path.isdir(bundles) and len(os.listdir(bundles)) > 1000:
        return bundles
    os.makedirs(work, exist_ok=True)
    print("解包 XAPK …")
    with zipfile.ZipFile(xapk) as z:
        for name in z.namelist():
            if name.endswith(".apk"):
                target = os.path.join(work, name)
                if not os.path.exists(target):
                    z.extract(name, work)
    print("解包 StreamingAssets …")
    with zipfile.ZipFile(os.path.join(work, "UnityStreamingAssetsPack.apk")) as z:
        z.extractall(work)
    src = os.path.join(work, "assets", "aa", "Android")
    if os.path.isdir(src):
        os.rename(src, bundles)
    return bundles


# ---------- 导出资源 ----------

def read_chart_meta(text: str):
    m = re.search(r'"bpm"\s*:\s*([0-9.]+)', text)
    bpm = float(m.group(1)) if m else 0.0
    unit = 60.0 / (32.0 * bpm) if bpm else 0.0
    times = [float(x) for x in re.findall(r'"time"\s*:\s*([0-9.]+)', text)]
    holds = [float(x) for x in re.findall(r'"holdTime"\s*:\s*([0-9.]+)', text)]
    dur = (max(times) + (max(holds) if holds else 0.0)) * unit if times else 0.0
    return bpm, len(times), dur


def harvest(bundles: str, cache: str):
    charts_dir = os.path.join(cache, "charts")
    audio_dir = os.path.join(cache, "audio")
    os.makedirs(charts_dir, exist_ok=True)
    os.makedirs(audio_dir, exist_ok=True)
    manifest = os.path.join(cache, "manifest.json")
    if os.path.exists(manifest):
        return json.load(open(manifest, encoding="utf-8"))

    files = sorted((os.path.join(dp, f) for dp, _, fs in os.walk(bundles) for f in fs),
                   key=os.path.getsize)
    charts, audio = [], []
    t0 = time.time()
    for i, p in enumerate(files):
        if i % 300 == 0:
            print(f"  [{i}/{len(files)}] {time.time()-t0:.0f}s charts={len(charts)} audio={len(audio)}")
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
                    name = getattr(d, "m_Name", "Chart")
                    fn = f"{bid}_{name}.json"
                    open(os.path.join(charts_dir, fn), "w", encoding="utf-8").write(s)
                    bpm, notes, dur = read_chart_meta(s)
                    charts.append({"bundle": bid, "asset": name, "file": fn,
                                   "bpm": round(bpm, 3), "notes": notes, "duration": round(dur, 2)})
                elif t == "AudioClip":
                    d = obj.read()
                    samples = getattr(d, "samples", None)
                    if not samples:
                        continue
                    data = b"".join(samples.values())
                    if len(data) < 100_000:
                        continue
                    fn = f"{re.sub(r'[^0-9A-Za-z_]+', '_', getattr(d, 'm_Name', bid))[:40]}_{bid[:8]}.wav"
                    open(os.path.join(audio_dir, fn), "wb").write(data)
                    audio.append({"file": fn, "bytes": len(data), "bundle": bid})
            except Exception:
                continue
    json.dump({"charts": charts, "audio": audio}, open(manifest, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    return {"charts": charts, "audio": audio}


# ---------- 聚类 + 落盘 ----------

def wav_seconds(path: str) -> float:
    with open(path, "rb") as f:
        head = f.read(44)
    if head[:4] != b"RIFF":
        return -1
    rate = struct.unpack_from("<i", head, 24)[0]
    ch = struct.unpack_from("<h", head, 22)[0] or 1
    bits = struct.unpack_from("<h", head, 34)[0] or 16
    size = os.path.getsize(path) - 44
    return size / (rate * ch * bits / 8)


def main() -> None:
    xapk = sys.argv[1]
    out = sys.argv[2]
    only = []
    if "--only" in sys.argv:
        only = [w.strip().lower() for w in sys.argv[sys.argv.index("--only") + 1].split(",")]

    work = os.path.join(os.path.dirname(os.path.abspath(out)), "_phi_work")
    bundles = unpack(xapk, work)
    cache = os.path.join(work, "cache")
    data = harvest(bundles, cache)

    # 1) 按 (bpm, 时长) 聚类：同一首歌的 4 个难度 bpm 和时长一致
    groups = {}
    for c in data["charts"]:
        if c["duration"] <= 0:
            continue
        groups.setdefault((c["bpm"], round(c["duration"])), []).append(c)

    # 2) 音频按时长配对
    audio = [(a, wav_seconds(os.path.join(cache, "audio", a["file"]))) for a in data["audio"]]

    # 3) 曲名：命中 catalog 里的曲名（按 bpm/时长无法直接对应，这里只做展示）
    names = song_names(os.path.join(work, "assets", "aa", "catalog.json")) if os.path.isdir(os.path.join(work, "assets")) else set()

    os.makedirs(out, exist_ok=True)
    made = 0
    for (bpm, dur), items in sorted(groups.items()):
        if len(items) < 3:
            continue
        # 难度排序：音符数越多难度越高
        items.sort(key=lambda c: c["notes"])
        label = f"bpm{int(bpm)}_{int(dur)}s"
        if only and not any(w in label.lower() for w in only):
            # --only 目前按关键词过滤未知曲名，先保留全部再人工筛选
            pass
        best_audio = min(audio, key=lambda t: abs(t[1] - dur), default=None)
        if best_audio is None or abs(best_audio[1] - dur) > 2.5:
            continue
        song_dir = os.path.join(out, label)
        os.makedirs(song_dir, exist_ok=True)
        for i, c in enumerate(items[:4]):
            shutil.copy(os.path.join(cache, "charts", c["file"]),
                        os.path.join(song_dir, f"chart_{DIFFS[i]}.json"))
        shutil.copy(os.path.join(cache, "audio", best_audio[0]["file"]),
                    os.path.join(song_dir, "music.wav"))
        made += 1

    print(f"导入完成：{made} 首 -> {out}")
    print(f"catalog 里可识别的曲名数：{len(names)}")


if __name__ == "__main__":
    main()
