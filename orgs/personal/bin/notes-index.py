#!/usr/bin/env python3
"""Apple Notes (NoteStore.sqlite) → orgs/personal/notes/index.jsonl + facts/notes.edn。
title/folder/dates/snippet を抽出 (本文全文は gzip protobuf のため snippet をプレビューに使用)。
Full Disk Access 必須。Usage: notes-index.py [NoteStore.sqlite path]"""
import sqlite3, json, os, sys, shutil, tempfile, datetime
from collections import Counter

BASE = "/Users/junkawasaki/github/com-junkawasaki/orgs/personal"
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
    "~/Library/Group Containers/group.com.apple.notes/NoteStore.sqlite")
OUT_DIR = os.path.join(BASE, "notes")
OUT = os.path.join(OUT_DIR, "index.jsonl")
EDN = os.path.join(BASE, "facts/notes.edn")
APPLE_EPOCH = 978307200

def dt(ts):
    return datetime.datetime.fromtimestamp(ts + APPLE_EPOCH).strftime("%Y-%m-%dT%H:%M:%S") if ts else None

def main():
    tmp = tempfile.mktemp(suffix=".sqlite")
    shutil.copy(SRC, tmp)
    con = sqlite3.connect(tmp)
    con.row_factory = sqlite3.Row

    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.lexists(OUT):
        os.remove(OUT)

    rows = []
    by_folder = Counter()
    dmin = dmax = None
    for n in con.execute("""
        SELECT n.ZIDENTIFIER AS uuid, n.ZTITLE1 AS title, n.ZSNIPPET AS snippet,
               n.ZISPINNED AS pinned, f.ZTITLE2 AS folder,
               n.ZCREATIONDATE3 AS created, n.ZMODIFICATIONDATE1 AS modified
        FROM ZICCLOUDSYNCINGOBJECT n
        LEFT JOIN ZICCLOUDSYNCINGOBJECT f ON n.ZFOLDER = f.Z_PK
        WHERE n.ZTITLE1 IS NOT NULL
          AND (n.ZMARKEDFORDELETION IS NULL OR n.ZMARKEDFORDELETION = 0)"""):
        created, modified = dt(n["created"]), dt(n["modified"])
        folder = n["folder"] or "(未分類)"
        rows.append({
            "uuid": n["uuid"],
            "title": (n["title"] or "").strip(),
            "snippet": (n["snippet"] or "").strip() or None,
            "folder": folder,
            "pinned": bool(n["pinned"]),
            "created": created,
            "modified": modified,
        })
        by_folder[folder] += 1
        for d in (created, modified):
            if d:
                if dmin is None or d < dmin: dmin = d
                if dmax is None or d > dmax: dmax = d

    rows.sort(key=lambda r: r["modified"] or "")
    with open(OUT, "w") as w:
        for r in rows:
            w.write(json.dumps(r, ensure_ascii=False) + "\n")

    def es(s): return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'
    lines = [
        ";; notes.edn — Apple Notes 索引サマリ (notes-index.py, Full Disk Access)。",
        ";; title/folder/dates/snippet。全文プレビュー: orgs/personal/notes/index.jsonl",
        "",
        "{:notes/source \"NoteStore.sqlite\"",
        f" :notes/scanned-at {es(datetime.date.today().isoformat())}",
        f" :notes/count {len(rows)}",
        f" :notes/date-range {{:from {es((dmin or '')[:10])} :to {es((dmax or '')[:10])}}}",
        " :notes/by-folder [" + " ".join(f"[{es(k)} {v}]" for k, v in by_folder.most_common(15)) + "]}",
    ]
    with open(EDN, "w") as w:
        w.write("\n".join(lines) + "\n")

    print(f"Notes: {len(rows)} notes → {OUT}")
    print(f"  期間 {dmin} 〜 {dmax}")
    print(f"  folder上位: {by_folder.most_common(6)}")
    os.remove(tmp)

if __name__ == "__main__":
    main()
