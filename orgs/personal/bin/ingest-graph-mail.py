#!/usr/bin/env python3
"""ingest-graph-mail.py — content-addressed M365 mail ingest via Microsoft Graph.

Sibling of ingest-gmail-batch.py for Outlook/M365 accounts: lists messages in a
sliding window, fetches each as true MIME (/messages/{id}/$value) and stores it
as mail/messages/<cid>.eml (cid = sha256 of the raw RFC822 bytes), appending to
mail/messages/index.jsonl. Idempotent by cid and by Graph message id.

AUTH: bin/msgraph-auth.py (device-code flow, refresh token in Keychain), or set
GRAPH_ACCESS_TOKEN to override.

USAGE:
    python3 bin/ingest-graph-mail.py --account SLUG [window] [max]
    # window like 14d (default), max messages default 1000
"""
import sys, os, json, hashlib, subprocess, urllib.request, urllib.error, urllib.parse
from datetime import datetime, timedelta, timezone
from email.parser import BytesParser
from email.policy import default as default_policy

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # orgs/personal/
MSGDIR = os.path.join(BASE, "mail", "messages")
INDEX = os.path.join(MSGDIR, "index.jsonl")
API = "https://graph.microsoft.com/v1.0"


def token(account):
    t = os.environ.get("GRAPH_ACCESS_TOKEN")
    if t:
        return t.strip()
    out = subprocess.run([os.path.join(BASE, "bin", "msgraph-auth.py"), "token", account],
                         capture_output=True, text=True)
    if out.returncode == 0 and out.stdout.strip():
        return out.stdout.strip()
    sys.exit(f"no token for account {account}: {out.stderr.strip()}")


def api_get(url, tok, raw=False):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok})
    try:
        with urllib.request.urlopen(req) as r:
            return r.read() if raw else json.load(r)
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:300]
        if e.code in (401, 403):
            sys.exit(f"auth/scope error ({e.code}): {body}\nRe-login with Mail.Read scope.")
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


def list_ids(tok, window_days, cap):
    since = (datetime.now(timezone.utc) - timedelta(days=window_days)) \
        .strftime("%Y-%m-%dT%H:%M:%SZ")
    url = (f"{API}/me/messages?" + urllib.parse.urlencode({
        "$filter": f"receivedDateTime ge {since}",
        "$select": "id,conversationId",
        "$orderby": "receivedDateTime desc",
        "$top": 100,
    }))
    ids = []
    while url and len(ids) < cap:
        resp = api_get(url, tok)
        ids += [(m["id"], m.get("conversationId")) for m in resp.get("value", [])]
        url = resp.get("@odata.nextLink")
    return ids[:cap]


def main():
    argv = sys.argv[1:]
    if len(argv) < 2 or argv[0] != "--account":
        sys.exit(__doc__)
    account = argv[1]
    window = argv[2] if len(argv) > 2 else "14d"
    cap = int(argv[3]) if len(argv) > 3 else 1000
    days = int(window.rstrip("d"))
    tok = token(account)
    os.makedirs(MSGDIR, exist_ok=True)
    cids, mids = known()
    ids = list_ids(tok, days, cap)
    print("account=%s window=%dd matched %d message(s)" % (account, days, len(ids)))
    added = 0
    with open(INDEX, "a", encoding="utf-8") as idx:
        for mid, conv in ids:
            if mid in mids:
                continue
            raw = api_get(f"{API}/me/messages/{urllib.parse.quote(mid)}/$value", tok, raw=True)
            cid = hashlib.sha256(raw).hexdigest()
            # cid already known = same RFC822 bytes seen via another account/source:
            # keep the single .eml but still record this (account, message_id) sighting.
            if cid not in cids:
                with open(os.path.join(MSGDIR, cid + ".eml"), "wb") as fh:
                    fh.write(raw)
            em = BytesParser(policy=default_policy).parsebytes(raw)
            rec = {
                "cid": cid, "sha256": cid, "size": len(raw),
                "account": account,
                "message_id": mid,
                "rfc822_message_id": em.get("Message-ID"),
                "thread_id": conv,
                "date": em.get("Date"),
                "from": em.get("From"),
                "to": [a.strip() for a in (em.get("To") or "").split(",") if a.strip()],
                "cc": [a.strip() for a in (em.get("Cc") or "").split(",") if a.strip()],
                "subject": em.get("Subject"),
                "labels": [],
                "eml_path": "mail/messages/%s.eml" % cid,
                "source": "msgraph/mime",
            }
            idx.write(json.dumps(rec, ensure_ascii=False) + "\n")
            cids.add(cid); mids.add(mid); added += 1
            if added % 25 == 0:
                print("  ...%d ingested" % added)
    print("done: %d new .eml ingested -> %s" % (added, MSGDIR))


if __name__ == "__main__":
    main()
