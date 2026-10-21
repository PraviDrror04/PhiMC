"""打印单个 bundle 里所有对象的名字，用来找「谱面属于哪首歌」的线索。"""
import sys

import UnityPy

for path in sys.argv[1:]:
    print(f"=== {path} ===")
    try:
        env = UnityPy.load(path)
    except Exception as e:
        print("  load failed:", e)
        continue
    for obj in env.objects:
        t = obj.type.name
        try:
            d = obj.read()
        except Exception:
            continue
        nm = getattr(d, "m_Name", "")
        extra = ""
        if t == "TextAsset":
            s = d.m_Script
            if isinstance(s, bytes):
                s = s.decode("utf-8", "ignore")
            extra = f" {len(s)}B"
        elif t == "AudioClip":
            extra = f" samples={'yes' if getattr(d, 'samples', None) else 'no'}"
        elif t == "Texture2D":
            extra = f" {getattr(d, 'm_Width', '?')}x{getattr(d, 'm_Height', '?')}"
        print(f"  {t:15s} '{nm}'{extra}")
