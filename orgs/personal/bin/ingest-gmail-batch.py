#!/usr/bin/env python3
"""ingest-gmail-batch.py — bulk content-addressed email ingest via the Gmail REST API.

Unlike ingest-eml.py (which consumes one MCP get_thread JSON at a time), this talks
to the Gmail API directly so large backfills do NOT pass through an LLM context.
It fetches messages matching a Gmail query as format=RAW (true RFC822) and stores
each as mail/messages/<cid>.eml (cid = sha256 of the raw bytes), updating
mail/messages/index.jsonl. Idempotent by cid and by Gmail message id.

AUTH (one-time): the token needs the gmail.readonly scope.
  NOTE: gcloud's DEFAULT ADC client is BLOCKED by Google from the Gmail restricted
  scope ("このアプリはブロックされます"). So `gcloud auth application-default login
  --scopes=...gmail.readonly` will NOT work. Two ways to get a usable token:
   (a) Create your OWN OAuth Desktop client in GCP (project com-junkawasaki-sip),
       enable the Gmail API, add yourself as a test user, then mint a token for it
       and export GMAIL_ACCESS_TOKEN.
   (b) Skip the API entirely and use bin/ingest-mbox.py on a Google Takeout mbox
       (no OAuth; recommended for large one-time backfills).
Then: GMAIL_ACCESS_TOKEN=ya29.... python3 bin/ingest-gmail-batch.py 'label:LingLing'

USAGE:
    python3 bin/ingest-gmail-batch.py [--account SLUG] '<gmail query>' [max]
    # e.g. 'label:LingLing'  '弁護士 OR 訴訟'  'after:2026/05/01 from:zelojapan.com'
    # --account: registry slug. Token is minted via bin/google-auth.py (Keychain
    # refresh token) unless GMAIL_ACCESS_TOKEN is set, and index records are
    # tagged with the account. Used by bin/mail-sync.sh for periodic sync.
"""
import sys, os, json, hashlib, base64, subprocess, urllib.request, urllib.error, urllib.parse
from email.parser import BytesParser
from email.policy import default as default_policy

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # orgs/personal/
MSGDIR = os.path.join(BASE, "mail", "messages")
INDEX = os.path.join(MSGDIR, "index.jsonl")
API = "https://gmail.googleapis.com/gmail/v1/users/me"


def token(account=None):
    t = os.environ.get("GMAIL_ACCESS_TOKEN")
    if t:
        return t.strip()
    if account:
        out = subprocess.run([os.path.join(BASE, "bin", "google-auth.py"), "token", account],
                             capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
        sys.exit(f"no token for account {account}: {out.stderr.strip()}")
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
    argv = sys.argv[1:]
    account = None
    if argv and argv[0] == "--account":
        account = argv[1]
        argv = argv[2:]
    if not argv:
        sys.exit(__doc__)
    query = argv[0]
    cap = int(argv[1]) if len(argv) > 1 else 1000
    tok = token(account)
    os.makedirs(MSGDIR, exist_ok=True)
    cids, mids = known()
    ids = list_ids(query, tok, cap)
    print("account=%s query=%r matched %d message(s)" % (account, query, len(ids)))
    added = 0
    with open(INDEX, "a", encoding="utf-8") as idx:
        for mid in ids:
            if mid in mids:
                continue
            msg = api_get("/messages/" + mid, tok, {"format": "raw"})
            raw = base64.urlsafe_b64decode(msg["raw"].encode())
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
