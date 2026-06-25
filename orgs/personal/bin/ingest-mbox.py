#!/usr/bin/env python3
"""ingest-mbox.py — content-addressed email ingest from a Google Takeout mbox.

Robust bulk path that needs NO OAuth (gcloud's default client is blocked from the
Gmail restricted scope). Export mail from https://takeout.google.com (Mail -> .mbox),
then point this script at the .mbox. Each message is stored as
mail/messages/<cid>.eml (cid = sha256 of the raw RFC822 bytes) with an index entry.
Idempotent by cid and RFC822 Message-ID.

USAGE:
    python3 bin/ingest-mbox.py /path/to/All\\ mail\\ Including\\ Spam\\ and\\ Trash.mbox [max]
    # optionally filter by a substring that must appear in From/To/Cc/Subject:
    FILTER='zelojapan|LingLing|訴訟|弁護士|税' python3 bin/ingest-mbox.py mail.mbox
"""
import sys, os, re, json, hashlib, mailbox

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # personal/
MSGDIR = os.path.join(BASE, "mail", "messages")
INDEX = os.path.join(MSGDIR, "index.jsonl")


def known():
    cids, mids = set(), set()
    if os.path.exists(INDEX):
        with open(INDEX) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = json.loads(line)
                    cids.add(r.get("cid"))
                    mids.add(r.get("rfc822_message_id") or r.get("message_id"))
                except Exception:
                    pass
    return cids, mids


def split_addrs(v):
    return [a.strip() for a in (v or "").replace("\n", " ").split(",") if a.strip()]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    cap = int(sys.argv[2]) if len(sys.argv) > 2 else 10 ** 9
    filt = os.environ.get("FILTER")
    rx = re.compile(filt, re.I) if filt else None
    os.makedirs(MSGDIR, exist_ok=True)
    cids, mids = known()
    box = mailbox.mbox(path)
    added = scanned = 0
    with open(INDEX, "a", encoding="utf-8") as idx:
        for key in box.iterkeys():
            if added >= cap:
                break
            scanned += 1
            msg = box.get_message(key)
            mid = msg.get("Message-ID")
            if mid and mid in mids:
                continue
            if rx:
                hay = " ".join(filter(None, [msg.get("From"), msg.get("To"),
                                             msg.get("Cc"), msg.get("Subject")]))
                if not rx.search(hay):
                    continue
            raw = msg.as_bytes()
            cid = hashlib.sha256(raw).hexdigest()
            if cid in cids:
                continue
            with open(os.path.join(MSGDIR, cid + ".eml"), "wb") as fh:
                fh.write(raw)
            rec = {
                "cid": cid, "sha256": cid, "size": len(raw),
                "message_id": cid,                       # local id = cid
                "rfc822_message_id": mid,
                "thread_id": msg.get("X-GM-THRID"),
                "date": msg.get("Date"),
                "from": msg.get("From"),
                "to": split_addrs(msg.get("To")),
                "cc": split_addrs(msg.get("Cc")),
                "subject": msg.get("Subject"),
                "labels": split_addrs(msg.get("X-Gmail-Labels")),
                "eml_path": "mail/messages/%s.eml" % cid,
                "source": "takeout-mbox",
            }
            idx.write(json.dumps(rec, ensure_ascii=False) + "\n")
            cids.add(cid)
            if mid:
                mids.add(mid)
            added += 1
            if added % 100 == 0:
                print("  ...%d ingested (%d scanned)" % (added, scanned))
    print("done: %d new .eml ingested from %d scanned -> %s" % (added, scanned, MSGDIR))
    print("next: git annex add mail/messages && git annex copy --to b2 mail/messages")


if __name__ == "__main__":
    main()
