import json, os, sys, zipfile, collections

PAY = r"C:\dsh-work\payload"
RUN = r"C:\dsh-work\phimc\mod\run\config\phimc\songs"

def chart_entry(z):
    names = [n for n in z.namelist() if n.lower().endswith('.json')]
    if len(names) == 1:
        return names[0]
    # 和 SongEntry.resolveChart 一样的口味：info.txt 里 Chart: 声明的那个
    if 'info.txt' in z.namelist():
        txt = z.read('info.txt').decode('utf-8', 'replace')
        for line in txt.splitlines():
            if ':' in line:
                k, v = line.split(':', 1)
                if k.strip().lower() == 'chart':
                    want = v.strip().replace('\\', '/')
                    for n in names:
                        if n.replace('\\', '/').endswith(want):
                            return n
    for n in names:
        if 'chart' in n.lower():
            return n
    return names[0] if names else None

def walk_notes(obj, out):
    """收集所有形如 note 的对象（含 tint / type / time 字段）。"""
    if isinstance(obj, dict):
        if 'type' in obj and ('startTime' in obj or 'time' in obj):
            out.append(obj)
        for v in obj.values():
            walk_notes(v, out)
    elif isinstance(obj, list):
        for v in obj:
            walk_notes(v, out)

def report(path):
    try:
        z = zipfile.ZipFile(path)
    except Exception as e:
        print(f"{os.path.basename(path)}: 打不开 {e}"); return
    name = chart_entry(z)
    raw = z.read(name)
    try:
        j = json.loads(raw)
    except Exception as e:
        print(f"{os.path.basename(path)}: JSON 解析失败 {e}"); return
    fmt = 'RPE' if 'judgeLineList' in j else ('PGR' if 'judgeLineList' in j or 'formatVersion' in j else '?')
    if 'formatVersion' in j and isinstance(j.get('formatVersion'), int) and j['formatVersion'] >= 3 and 'judgeLineList' in j:
        fmt = 'RPE'
    if 'offset' in j and 'judgeLineList' in j:
        fmt = 'PGR'
    notes = []
    walk_notes(j, notes)
    tints = collections.Counter()
    alphas = collections.Counter()
    n_tint = 0
    for n in notes:
        t = n.get('tint')
        if t is None:
            t = n.get('tint_hit_effects')
        if isinstance(t, list) and len(t) >= 3:
            n_tint += 1
            tints[tuple(t[:3])] += 1
        a = n.get('alpha')
        if isinstance(a, (int, float)):
            alphas[int(a)] += 1
    meta = j.get('META') or {}
    print(f"--- {os.path.basename(path)}")
    print(f"    谱面={name} 格式={fmt} 音符对象={len(notes)} 带 tint 的={n_tint}")
    if tints:
        top = tints.most_common(6)
        print(f"    tint 取值(前6): " + ", ".join(f"{k}×{v}" for k, v in top))
    if alphas:
        non255 = {k: v for k, v in alphas.items() if k != 255}
        print(f"    alpha<255 的音符数={sum(non255.values())} 分布(前6)={sorted(non255.items())[:6]}")
    if meta:
        print(f"    META.illustration={meta.get('illustration')!r}")

for d, label in ((PAY, 'payload'), (RUN, 'run/songs')):
    if not os.path.isdir(d):
        continue
    print(f"########## {label} ##########")
    for f in sorted(os.listdir(d)):
        if f.lower().endswith(('.zip', '.pez')):
            report(os.path.join(d, f))
