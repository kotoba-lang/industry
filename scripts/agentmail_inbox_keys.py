#!/usr/bin/env python3
"""Create one inbox-scoped AgentMail API key per inbox and store it in the
macOS Keychain. Modeled on scripts/agentmail-inbox-create.cljs rules:
 - the key is returned ONCE; never printed
 - `security add-generic-password -w` with no value reads the secret TWICE
   from stdin — feed it twice or the stored password is silently EMPTY
 - verify the stored value round-trips before declaring success

usage: python3 agentmail_inbox_keys.py <inbox1> <inbox2> ...
Requires the org key at kagi item AGENTMAIL_API_KEY.
Keychain service per inbox: agentmail-inbox-key-<localpart>
"""
import json, subprocess, sys, urllib.request

INBOXES = sys.argv[1:]
if not INBOXES:
    print("usage: agentmail_inbox_keys.py <inbox> [...]")
    sys.exit(2)

def kagi_get(item):
    out = subprocess.run(
        ["orgs/kotoba-lang/kagi/bin/kagi", "get", item],
        capture_output=True, text=True,
        env={"KAGI_HOME": "/Users/junkawasaki/.kagi", "HOME": "/Users/junkawasaki", "PATH": "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin"},
        cwd="/Users/junkawasaki/github/com-junkawasaki",
    )
    v = out.stdout.strip()
    if not v or "no such item" in v:
        raise SystemExit(f"kagi item missing: {item}")
    return v

ORG_KEY = kagi_get("AGENTMAIL_API_KEY")

def api(method, path, body=None):
    req = urllib.request.Request(
        f"https://api.agentmail.to/v0{path}", method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {ORG_KEY}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def keychain_put_twice(service, secret):
    # -w with no value reads the secret twice from stdin (entry + confirmation)
    p = subprocess.run(
        ["security", "add-generic-password", "-a", "agentmail", "-s", service, "-U", "-w"],
        input=f"{secret}\n{secret}\n", capture_output=True, text=True,
    )
    return p.returncode

def keychain_get(service):
    p = subprocess.run(
        ["security", "find-generic-password", "-s", service, "-w"],
        capture_output=True, text=True,
    )
    return p.stdout.strip() if p.returncode == 0 else None

for inbox in INBOXES:
    local = inbox.split("@")[0]
    service = f"agentmail-inbox-key-{local}"
    existing = keychain_get(service)
    if existing:
        print(f"{inbox}: key already stored ({service}, {len(existing)} chars) — skip create")
        continue
    d = api("POST", f"/inboxes/{inbox}/api-keys", {"name": f"{local}-bot-key"})
    key = d.get("api_key") or d.get("apiKey")
    if not key:
        print(f"{inbox}: NO KEY in response: {json.dumps(d)[:200]}")
        continue
    rc = keychain_put_twice(service, key)
    stored = keychain_get(service)
    if rc == 0 and stored == key:
        print(f"{inbox}: created + verified in Keychain ({service}, {len(key)} chars)")
    else:
        print(f"{inbox}: KEYCHAIN WRITE FAILED rc={rc} stored={'yes' if stored else 'no'} — INBOX KEY UNRECOVERABLE: {d.get('api_key_id','?')}")
