#!/usr/bin/env python3
"""osxphotos query --json の出力 → 軽量 index.jsonl (icloud-photos-sync.bb から呼ばれる)。
Usage: photos-index.py <osxphotos.json> <out.jsonl>
大容量(~500MB)JSON を確実に処理するため bb ではなく Python 側で変換する。"""
import json, sys, os

src, out = sys.argv[1], sys.argv[2]
assets = json.load(open(src))

# annex の read-only symlink を上書きするため、既存リンクは削除して新規実体を書く
if os.path.lexists(out):
    os.remove(out)

rows = []
for a in assets:
    rows.append({
        "uuid": a.get("uuid"),
        "name": a.get("original_filename"),
        "date": (a.get("date") or "")[:19],
        "kind": "movie" if a.get("ismovie") else "photo",
        "fav": bool(a.get("favorite")),
        "size": a.get("original_filesize") or 0,
        "missing": bool(a.get("ismissing")),
        "albums": a.get("albums") or [],
        "persons": [p for p in (a.get("persons") or []) if p != "_UNKNOWN_"],
        "lat": a.get("latitude"),
        "lon": a.get("longitude"),
    })
rows.sort(key=lambda r: (r["date"], r["uuid"] or ""))
with open(out, "w") as w:
    for r in rows:
        w.write(json.dumps(r, ensure_ascii=False) + "\n")
print(f"index: {len(rows)} assets")
