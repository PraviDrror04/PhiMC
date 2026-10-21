"""解析 Addressables catalog，把「谱面资源地址 → bundle 文件」映射出来。

实测到的格式（Phigros 4.0.1 / Addressables 1.29）：

    m_KeyDataString    : int32 count; 每条 = 1B 编码标志 + int32 字节长度 + 内容
                         （标志 0 = UTF-8 的 bundle key，1 = UTF-16LE 的资源地址）
    m_BucketDataString : int32 count; 每条 = int32 keyOffset（= 该 key 在 KeyDataString
                         里的字节偏移，不是下标！）+ int32 n + int32 * n（entry 下标）
    m_EntryDataString  : int32 count; 每条 = 6 个 int32，第 0 个 = m_InternalIds 下标

用法: python map_charts.py <catalog.json> [关键词...]
"""
import base64
import json
import struct
import sys


def read_keys(blob: bytes):
    """返回 [(记录起始偏移, 字符串), ...]"""
    count = struct.unpack_from("<i", blob, 0)[0]
    out = []
    off = 4
    while len(out) < count and off + 5 <= len(blob):
        flag = blob[off]
        ln = struct.unpack_from("<i", blob, off + 1)[0]
        start = off
        off += 5
        if ln < 0 or off + ln > len(blob):
            break
        raw = blob[off:off + ln]
        out.append((start, raw.decode("utf-16-le" if flag == 1 else "utf-8", "ignore")))
        off += ln
    return out


def read_buckets(blob: bytes):
    count = struct.unpack_from("<i", blob, 0)[0]
    off = 4
    buckets = []
    for _ in range(count):
        key_off = struct.unpack_from("<i", blob, off)[0]
        n = struct.unpack_from("<i", blob, off + 4)[0]
        off += 8
        entries = list(struct.unpack_from(f"<{n}i", blob, off))
        off += 4 * n
        buckets.append((key_off, entries))
    return buckets


def read_entries(blob: bytes, stride: int = 6):
    count = struct.unpack_from("<i", blob, 0)[0]
    off = 4
    out = []
    for _ in range(count):
        out.append(list(struct.unpack_from(f"<{stride}i", blob, off)))
        off += 4 * stride
    return out


def build(catalog_path: str):
    obj = json.loads(open(catalog_path, "rb").read().decode("utf-8"))
    pairs = read_keys(base64.b64decode(obj["m_KeyDataString"]))
    by_offset = {off: s for off, s in pairs}
    buckets = read_buckets(base64.b64decode(obj["m_BucketDataString"]))
    entries = read_entries(base64.b64decode(obj["m_EntryDataString"]))
    internal = [s.split("}/")[-1] for s in obj["m_InternalIds"]]

    mapping = {}
    for key_off, entry_indices in buckets:
        key = by_offset.get(key_off)
        if key is None:
            continue
        for ei in entry_indices:
            if 0 <= ei < len(entries):
                iid = entries[ei][0]
                if 0 <= iid < len(internal):
                    mapping.setdefault(key, []).append(internal[iid])
    return mapping, internal, entries


def main() -> None:
    mapping, internal, entries = build(sys.argv[1])
    words = [w.lower() for w in sys.argv[2:]]
    charts = {k: v for k, v in mapping.items() if "/Chart_" in k}
    print(f"keys mapped={len(mapping)} internalIds={len(internal)} entries={len(entries)} chartKeys={len(charts)}")
    for key, bundles in sorted(charts.items()):
        if words and not any(w in key.lower() for w in words):
            continue
        print(f"  {key}  ->  {bundles[0]}")


if __name__ == "__main__":
    main()
