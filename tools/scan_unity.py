"""扫描 Unity 资源包，找出 Phigros 谱面（TextAsset 里含 judgeLineList 的 JSON）和音频。

用法：
    python scan_unity.py <apk 或 .unity3d 或目录>

只做「侦察」：打印对象类型直方图、谱面候选名、AudioClip 候选名，不落盘。
"""
import sys
import time
from collections import Counter

import UnityPy

def main() -> None:
    path = sys.argv[1]
    t0 = time.time()
    env = UnityPy.load(path)
    print(f"loaded {path} in {time.time() - t0:.1f}s")

    types = Counter(o.type.name for o in env.objects)
    print("object types:", types.most_common(15))

    charts = []
    clips = []
    t1 = time.time()
    for o in env.objects:
        name = o.type.name
        if name == "TextAsset":
            try:
                d = o.read()
            except Exception:
                continue
            s = d.m_Script
            if isinstance(s, bytes):
                try:
                    s = s.decode("utf-8")
                except Exception:
                    continue
            head = s[:400]
            if "judgeLineList" in s or '"formatVersion"' in head:
                charts.append((getattr(d, "m_Name", "?"), len(s)))
        elif name == "AudioClip":
            try:
                d = o.read()
                clips.append(getattr(d, "m_Name", "?"))
            except Exception:
                pass

    print(f"scanned in {time.time() - t1:.1f}s")
    print(f"charts: {len(charts)}")
    for n, ln in sorted(charts)[:60]:
        print(f"  {n}  ({ln} bytes)")
    print(f"audio clips: {len(clips)}")
    for n in sorted(set(clips))[:40]:
        print(f"  {n}")


if __name__ == "__main__":
    main()
