#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一堆谱面包（官方 .zip / RPE .pez）清洗成"只留必要文件"的干净副本，输出到 <out-dir>：

  * 只保留 info.txt 里点名的 Chart / Song / Picture 三个文件（找不到就退化成"唯一一个 json / 音频 / 图片"）；
  * 丢掉包里面被误塞进去的**嵌套谱面包**（.pez/.zip）——它们会让整个 zip 里混进非 UTF-8 文件名，
    Java 的 ZipFile 读中央目录时会抛异常，结果整张谱面在游戏里消失；
  * 丢掉重复音轨（很多官方包同时放了 music.wav 和 music_#0.wav，内容完全一样）。

用法：
    python tools/repack_charts.py "D:/.../config/phimc/songs" setup/payload
"""
import hashlib
import json
import os
import sys
import zipfile


def _find(names, want):
    if not want:
        return None
    for n in names:
        if n == want:
            return n
    low = want.lower()
    for n in names:
        if n.lower() == low:
            return n
    return None


def plan(src):
    """算出这个包该保留哪些条目。"""
    with zipfile.ZipFile(src) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        infos = [n for n in names if n.lower().endswith((".txt", ".yml"))]
        info_txt = z.read(infos[0]).decode("utf-8", "replace") if infos else ""
        kv = {}
        for line in info_txt.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            k, v = line.split(":", 1)
            kv[k.strip().lower()] = v.strip()

    keep = []
    chart = _find(names, kv.get("chart"))
    song = _find(names, kv.get("song"))
    pic = _find(names, kv.get("picture"))
    if not song:
        auds = [n for n in names if n.lower().endswith((".wav", ".mp3", ".ogg"))]
        song = auds[0] if auds else None
    if not chart:
        js = [n for n in names if n.lower().endswith(".json")]
        chart = js[0] if js else None
    if not pic:
        pics = [n for n in names if n.lower().endswith((".png", ".jpg", ".jpeg", ".webp"))]
        pic = pics[0] if pics else None
    for n in (infos[0] if infos else None, chart, song, pic):
        if n and n not in keep:
            keep.append(n)
    dropped = [n for n in names if n not in keep]
    return keep, dropped


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    total = 0
    rows = []
    for name in sorted(os.listdir(src_dir)):
        if not name.lower().endswith((".zip", ".pez")):
            continue
        src = os.path.join(src_dir, name)
        keep, dropped = plan(src)
        if not keep:
            print("!! 跳过（读不出内容）: " + name)
            continue
        out = os.path.join(out_dir, name)
        with zipfile.ZipFile(src) as z, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zo:
            for n in keep:
                zo.writestr(n, z.read(n))
        size = os.path.getsize(out)
        total += size
        rows.append({"name": name, "bytes": size, "kept": keep, "dropped": dropped})
        print("%-40s %7.1f MB  丢 %d 个：%s" % (
            name[:40], size / 1048576.0, len(dropped),
            ", ".join(d[:28] for d in dropped[:4]) + (" …" if len(dropped) > 4 else "")))
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    print("\n共 %d 张，合计 %.1f MB → %s" % (len(rows), total / 1048576.0, out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
