#!/usr/bin/env python3
"""ingest-eml.py — content-addressed (cid) raw-email ingest for the warehouse.

Reads a Gmail thread JSON (as returned by the MCP get_thread FULL_CONTENT tool)
from a file or stdin, synthesizes one RFC822 .eml per message, content-addresses
it (cid = sha256 hex of the .eml bytes), writes:

    mail/messages/<cid>.eml                 # raw message (git-annexed -> B2/IPFS)
    mail/messages/index.jsonl               # one line per message (cid <-> meta)

Idempotent: a message whose cid already exists in the index is skipped.

Usage:
    python3 bin/ingest-eml.py thread.json
    cat thread.json | python3 bin/ingest-eml.py -
"""
import sys, os, json, hashlib
from email.message import EmailMessage
from email.utils import format_datetime, parsedate_to_datetime

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # orgs/personal/
MSGDIR = os.path.join(BASE, "mail", "messages")
INDEX = os.path.join(MSGDIR, "index.jsonl")


def load_index_cids():
    cids = set()
    if os.path.exists(INDEX):
        with open(INDEX) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    cids.add(json.loads(line).get("cid"))
                except Exception:
                    pass
    return cids


def build_eml(msg, thread_id):
    em = EmailMessage()
    em["Message-ID"] = "<%s@mail.gmail.com>" % msg.get("id", "")
    em["X-Gmail-Thread-Id"] = thread_id
    em["From"] = msg.get("sender", "")
    if msg.get("toRecipients"):
        em["To"] = ", ".join(msg["toRecipients"])
    if msg.get("ccRecipients"):
        em["Cc"] = ", ".join(msg["ccRecipients"])
    em["Subject"] = msg.get("subject", "")
    if msg.get("date"):
        try:
            em["Date"] = format_datetime(parsedate_to_datetime(msg["date"]))
        except Exception:
            em["Date"] = msg["date"]
    if msg.get("labelIds"):
        em["X-Gmail-Labels"] = ", ".join(msg["labelIds"])
    body = msg.get("plaintextBody") or msg.get("snippet") or ""
    em.set_content(body)
    if msg.get("htmlBody"):
        em.add_alternative(msg["htmlBody"], subtype="html")
    return em.as_bytes()


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "-"
    raw = sys.stdin.read() if src == "-" else open(src, encoding="utf-8").read()
    thread = json.loads(raw)
    tid = thread.get("id", "")
    os.makedirs(MSGDIR, exist_ok=True)
    seen = load_index_cids()
    added = 0
    with open(INDEX, "a", encoding="utf-8") as idx:
        for m in thread.get("messages", []):
            eml = build_eml(m, tid)
            cid = hashlib.sha256(eml).hexdigest()
            if cid in seen:
                continue
            with open(os.path.join(MSGDIR, cid + ".eml"), "wb") as fh:
                fh.write(eml)
            rec = {
                "cid": cid,
                "sha256": cid,
                "size": len(eml),
                "message_id": m.get("id"),
                "thread_id": tid,
                "date": m.get("date"),
                "from": m.get("sender"),
                "to": m.get("toRecipients", []),
                "cc": m.get("ccRecipients", []),
                "subject": m.get("subject"),
                "labels": m.get("labelIds", []),
                "eml_path": "mail/messages/%s.eml" % cid,
            }
            idx.write(json.dumps(rec, ensure_ascii=False) + "\n")
            seen.add(cid)
            added += 1
    print("ingested %d new message(s); index: %s" % (added, INDEX))


if __name__ == "__main__":
    main()
