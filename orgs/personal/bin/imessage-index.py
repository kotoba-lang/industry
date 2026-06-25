#!/usr/bin/env python3
"""iMessage/SMS (chat.db) → orgs/personal/comms/imessage/index.jsonl + facts/imessage.edn。
本文は text 列が空の場合 attributedBody (streamtyped NSAttributedString) をデコード。
Full Disk Access 必須。icloud-photos-sync 同様 cid 重複は無関係 (DB由来の派生索引)。
Usage: imessage-index.py [chat.db path]"""
import sqlite3, json, os, sys, shutil, tempfile
from collections import Counter

BASE = "/Users/junkawasaki/github/com-junkawasaki/orgs/personal"
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/Library/Messages/chat.db")
OUT_DIR = os.path.join(BASE, "comms/imessage")
OUT = os.path.join(OUT_DIR, "index.jsonl")
EDN = os.path.join(BASE, "facts/imessage.edn")
APPLE_EPOCH = 978307200  # 2001-01-01 → unix

def decode_attributed_body(data):
    """streamtyped NSAttributedString から本文を抽出 (+マーカー後の長さプレフィックス方式)。"""
    if not data:
        return None
    n = data.find(b"NSString")
    if n < 0:
        return None
    plus = data.find(b"\x2b", n)
    if plus < 0:
        return None
    i = plus + 1
    if i >= len(data):
        return None
    b0 = data[i]
    if b0 == 0x81:
        length = int.from_bytes(data[i+1:i+3], "little"); i += 3
    elif b0 == 0x82:
        length = int.from_bytes(data[i+1:i+5], "little"); i += 5
    else:
        length = b0; i += 1
    try:
        return data[i:i+length].decode("utf-8")
    except Exception:
        return data[i:i+length].decode("utf-8", "replace")

def main():
    # WAL を含めて読むため一時コピー
    tmp = tempfile.mktemp(suffix=".db")
    shutil.copy(SRC, tmp)
    con = sqlite3.connect(tmp)
    con.row_factory = sqlite3.Row

    # chat 名解決 (message → chat)
    chat_of = {}
    for r in con.execute("""SELECT cmj.message_id, c.chat_identifier, c.display_name, c.style
                            FROM chat_message_join cmj JOIN chat c ON cmj.chat_id=c.ROWID"""):
        chat_of[r["message_id"]] = (r["display_name"] or r["chat_identifier"], r["style"])

    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.lexists(OUT):
        os.remove(OUT)  # annex の read-only symlink 対策

    n_total = n_text = n_decoded = n_empty = 0
    by_contact = Counter(); by_service = Counter(); by_year = Counter()
    dmin = dmax = None
    rows = []
    for m in con.execute("""SELECT m.ROWID, m.text, m.attributedBody, m.is_from_me, m.service,
                                   m.date, m.cache_has_attachments, h.id AS handle
                            FROM message m LEFT JOIN handle h ON m.handle_id=h.ROWID"""):
        body = m["text"]
        if body and body.strip():
            n_text += 1
        else:
            body = decode_attributed_body(m["attributedBody"])
            if body and body.strip():
                n_decoded += 1
            else:
                n_empty += 1
        ts = (m["date"] / 1e9 + APPLE_EPOCH) if m["date"] else None
        import datetime
        date = datetime.datetime.fromtimestamp(ts).strftime("%Y-%m-%dT%H:%M:%S") if ts else None
        chat_name, style = chat_of.get(m["ROWID"], (m["handle"], None))
        contact = m["handle"] or chat_name
        rec = {
            "date": date,
            "from_me": bool(m["is_from_me"]),
            "contact": contact,
            "chat": chat_name,
            "group": style == 43,  # 43=group, 45=1:1
            "service": m["service"],
            "text": (body or "").strip() or None,
            "attachment": bool(m["cache_has_attachments"]),
        }
        rows.append(rec)
        n_total += 1
        if contact:
            by_contact[contact] += 1
        by_service[m["service"] or "?"] += 1
        if date:
            by_year[date[:4]] += 1
            if dmin is None or date < dmin: dmin = date
            if dmax is None or date > dmax: dmax = date

    rows.sort(key=lambda r: r["date"] or "")
    with open(OUT, "w") as w:
        for r in rows:
            w.write(json.dumps(r, ensure_ascii=False) + "\n")

    # サマリ EDN
    def es(s): return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'
    top = by_contact.most_common(15)
    lines = [
        ";; imessage.edn — iMessage/SMS 索引サマリ (imessage-index.py, Full Disk Access)。",
        ";; 本文は attributedBody(streamtyped) をデコード。全文索引: orgs/personal/comms/imessage/index.jsonl",
        "",
        "{:imessage/source \"~/Library/Messages/chat.db\"",
        f" :imessage/scanned-at {es(__import__('datetime').date.today().isoformat())}",
        f" :imessage/messages {n_total}",
        f" :imessage/body {{:text-col {n_text} :decoded {n_decoded} :empty {n_empty}}}",
        f" :imessage/date-range {{:from {es((dmin or '')[:10])} :to {es((dmax or '')[:10])}}}",
        " :imessage/by-service [" + " ".join(f"[{es(k)} {v}]" for k, v in by_service.most_common()) + "]",
        " :imessage/top-contacts [" + " ".join(f"[{es(k)} {v}]" for k, v in top) + "]}",
    ]
    with open(EDN, "w") as w:
        w.write("\n".join(lines) + "\n")

    print(f"iMessage: {n_total} msgs (text={n_text} decoded={n_decoded} empty={n_empty}) → {OUT}")
    print(f"  期間 {dmin} 〜 {dmax} / service={dict(by_service)}")
    print(f"  top: {top[:5]}")
    os.remove(tmp)

if __name__ == "__main__":
    main()
