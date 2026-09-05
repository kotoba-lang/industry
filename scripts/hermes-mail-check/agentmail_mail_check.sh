#!/usr/bin/env bash
# agentmail mail-check — hourly scan of the 3 agentmail bot-profile inboxes.
# Rule source: ADR-2609031630 (bot creation includes cron organize) + skill agentmail-bot-profiles.
# Registry: 90-docs/business/agentmail-bot-profiles.json
# Credentials: Keychain service agentmail-inbox-key-<localpart> (inbox-scoped, NOT the org key).
#
# Output: one line per inbox with the NEW message count since the last run
# (state file keeps last seen count). Empty output = nothing changed.
set -u
STATE="$HOME/.hermes/profiles/itonami/cron/agentmail-mail-check.state.json"
REGISTRY="/Users/junkawasaki/github/com-junkawasaki/90-docs/business/agentmail-bot-profiles.json"

[ -f "$STATE" ] || echo '{}' > "$STATE"

python3 - "$REGISTRY" "$STATE" <<'EOF'
import json, subprocess, sys, urllib.request

reg = json.load(open(sys.argv[1]))
try:
    state = json.load(open(sys.argv[2]))
except Exception:
    state = {}

changed = False
for p in reg["profiles"]:
    addr = p["address"]
    svc = p["credential"]["keychain_service"]
    key = subprocess.run(["security", "find-generic-password", "-s", svc, "-w"],
                         capture_output=True, text=True).stdout.strip()
    if not key:
        print(f"UNKNOWN: {addr} keychain {svc} empty (key missing)")
        changed = True
        continue
    try:
        req = urllib.request.Request(
            f"https://api.agentmail.to/v0/inboxes/{addr}/messages?limit=20",
            headers={"Authorization": f"Bearer {key}"})
        with urllib.request.urlopen(req, timeout=20) as r:
            d = json.load(r)
        count = d.get("count", 0)
    except Exception as e:
        print(f"UNKNOWN: {addr} probe failed: {type(e).__name__}")
        changed = True
        continue
    prev = state.get(addr)
    if prev is None:
        state[addr] = count
        changed = True
    elif count != prev:
        print(f"MAIL: {addr} {prev} -> {count} messages")
        state[addr] = count
        changed = True

if changed:
    json.dump(state, open(sys.argv[2], "w"))
EOF
