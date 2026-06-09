#!/usr/bin/env python3
"""ingest-gmail-batch.py — bulk content-addressed email ingest via the Gmail REST API.

Unlike ingest-eml.py (which consumes one MCP get_thread JSON at a time), this talks
to the Gmail API directly so large backfills do NOT pass through an LLM context.
It fetches messages matching a Gmail query as format=RAW (true RFC822) and stores
each as mail/messages/<cid>.eml (cid = sha256 of the raw bytes), updating
mail/messages/index.jsonl. Idempotent by cid and by Gmail message id.

AUTH (one-time): the token needs the gmail.readonly scope. Easiest:
    gcloud auth application-default login \
        --scopes=https://www.googleapis.com/auth/gmail.readonly,openid
then this script picks up the token automatically. Or pass a bearer token via
    GMAIL_ACCESS_TOKEN=ya29.... python3 bin/ingest-gmail-batch.py 'label:LingLing'

USAGE:
    python3 bin/ingest-gmail-batch.py '<gmail query>' [max]
    # e.g. 'label:LingLing'  '弁護士 OR 訴訟'  'after:2026/05/01 from:zelojapan.com'
"""
import sys, os, json, hashlib, base64, subprocess, urllib.request, urllib.error, urllib.parse
from email.parser import BytesParser
from email.policy import default as default_policy

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # personal/
MSGDIR = os.path.join(BASE, "mail", "messages")
INDEX = os.path.join(MSGDIR, "index.jsonl")
API = "https://gmail.googleapis.com/gmail/v1/users/me"


def token():
    t = os.environ.get("GMAIL_ACCESS_TOKEN")
    if t:
        return t.strip()
    for cmd in (["gcloud", "auth", "application-default", "print-access-token"],
                ["gcloud", "auth", "print-access-token"]):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True)
            if out.returncode == 0 and out.stdout.strip():
                return out.stdout.strip()
        except FileNotFoundError:
            pass
    sys.exit("no token: set GMAIL_ACCESS_TOKEN or run "
             "`gcloud auth application-default login --scopes=https://www.googleapis.com/auth/gmail.readonly,openid`")


def api_get(path, tok, params=None):
    url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:300]
        if e.code in (401, 403):
            sys.exit("auth/scope error (%d): %s\nRe-auth with gmail.readonly scope." % (e.code, body))
        raise


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
                    cids.add(r.get("cid")); mids.add(r.get("message_id"))
                except Exception:
                    pass
    return cids, mids


def list_ids(query, tok, cap):
    ids, page = [], None
    while len(ids) < cap:
        params = {"q": query, "maxResults": min(500, cap - len(ids))}
        if page:
            params["pageToken"] = page
        resp = api_get("/messages", tok, params)
        ids += [m["id"] for m in resp.get("messages", [])]
        page = resp.get("nextPageToken")
        if not page:
            break
    return ids[:cap]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    query = sys.argv[1]
    cap = int(sys.argv[2]) if len(sys.argv) > 2 else 1000
    tok = token()
    os.makedirs(MSGDIR, exist_ok=True)
    cids, mids = known()
    ids = list_ids(query, tok, cap)
    print("query=%r matched %d message(s)" % (query, len(ids)))
    added = 0
    with open(INDEX, "a", encoding="utf-8") as idx:
        for mid in ids:
            if mid in mids:
                continue
            msg = api_get("/messages/" + mid, tok, {"format": "raw"})
            raw = base64.urlsafe_b64decode(msg["raw"].encode())
            cid = hashlib.sha256(raw).hexdigest()
            if cid in cids:
                continue
            with open(os.path.join(MSGDIR, cid + ".eml"), "wb") as fh:
                fh.write(raw)
            em = BytesParser(policy=default_policy).parsebytes(raw)
            rec = {
                "cid": cid, "sha256": cid, "size": len(raw),
                "message_id": mid,
                "rfc822_message_id": em.get("Message-ID"),
                "thread_id": msg.get("threadId"),
                "date": em.get("Date"),
                "from": em.get("From"),
                "to": [a.strip() for a in (em.get("To") or "").split(",") if a.strip()],
                "cc": [a.strip() for a in (em.get("Cc") or "").split(",") if a.strip()],
                "subject": em.get("Subject"),
                "labels": msg.get("labelIds", []),
                "eml_path": "mail/messages/%s.eml" % cid,
                "source": "gmail-api/raw",
            }
            idx.write(json.dumps(rec, ensure_ascii=False) + "\n")
            cids.add(cid); mids.add(mid); added += 1
            if added % 25 == 0:
                print("  ...%d ingested" % added)
    print("done: %d new .eml ingested -> %s" % (added, MSGDIR))
    print("next: git annex add mail/messages && git annex copy --to b2 mail/messages")


if __name__ == "__main__":
    main()
