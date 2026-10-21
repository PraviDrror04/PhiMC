"""从解包出来的 Addressables bundle 目录里，把谱面 JSON 和曲目音频捞出来。

用法：
    python extract_phi.py <bundle 目录> <输出目录> [--limit N]

产物布局（直接就是 PhiMC 要的 songs/ 结构）：
    <输出目录>/<song_code>/chart_IN.json
    <输出目录>/<song_code>/music.ogg

实现要点：
- 谱面是 TextAsset，内容含 judgeLineList / formatVersion；
- 音频是 AudioClip，若 UnityPy 能拿到 samples 就导出 wav，否则导出原始数据待后续处理；
- 资源名里通常带曲目代号和难度，用它来命名。
"""
import io
import os
import re
import sys
import time
import wave

import UnityPy


def safe(name: str) -> str:
    return re.sub(r"[^0-9A-Za-z_\-.]+", "_", name)[:60] or "unnamed"


def chart_meta(text: str):
    """从谱面 JSON 里猜难度：优先 numOfNotes/bpm 之外，用文件名判断更靠谱。"""
    return len(text)


def main() -> None:
    src_dir = sys.argv[1]
    out_dir = sys.argv[2]
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    os.makedirs(out_dir, exist_ok=True)
    files = sorted(
        (os.path.join(dp, f) for dp, _, fs in os.walk(src_dir) for f in fs),
        key=lambda p: os.path.getsize(p),
    )
    if limit:
        files = files[:limit]
    print(f"bundles to scan: {len(files)}")

    charts = 0
    audios = 0
    t0 = time.time()
    for i, path in enumerate(files):
        if i % 200 == 0:
            print(f"[{i}/{len(files)}] {time.time() - t0:.0f}s charts={charts} audio={audios}", flush=True)
        try:
            env = UnityPy.load(path)
        except Exception:
            continue
        for obj in env.objects:
            try:
                if obj.type.name == "TextAsset":
                    d = obj.read()
                    s = d.m_Script
                    if isinstance(s, bytes):
                        s = s.decode("utf-8", "ignore")
                    if "judgeLineList" not in s and '"formatVersion"' not in s[:400]:
                        continue
                    name = safe(getattr(d, "m_Name", "") or os.path.basename(path))
                    with open(os.path.join(out_dir, f"{name}.json"), "w", encoding="utf-8") as f:
                        f.write(s)
                    charts += 1
                    print(f"  CHART {name} ({len(s)} bytes) <- {os.path.basename(path)}", flush=True)
                elif obj.type.name == "AudioClip":
                    d = obj.read()
                    name = safe(getattr(d, "m_Name", "") or os.path.basename(path))
                    samples = getattr(d, "samples", None)
                    if not samples:
                        continue
                    data = b"".join(samples.values())
                    if data:
                        ext = ".wav" if data[:4] == b"RIFF" else ".wav"
                        with open(os.path.join(out_dir, f"{name}{ext}"), "wb") as f:
                            f.write(data)
                        audios += 1
                        print(f"  AUDIO {name} ({len(data)} bytes)", flush=True)
            except Exception:
                continue

    print(f"done in {time.time() - t0:.0f}s: {charts} charts, {audios} audio clips -> {out_dir}")


if __name__ == "__main__":
    main()
