#!/bin/bash
# x402-mktg tick — agent-discoverability: llms.txt must carry every catalog SKU.
set -euo pipefail
U='hermes-agent/1.0'
LL=$(mktemp); CAT=$(mktemp)
curl -sf -A "$U" https://x402.nexus/llms.txt -o "$LL" || { echo "UNMEASURED: llms.txt fetch failed"; exit 0; }
curl -sf -A "$U" https://x402.nexus/catalog -o "$CAT"

/usr/bin/python3 - "$LL" "$CAT" <<'EOF'
import sys, json, re
ll = open(sys.argv[1], encoding="utf-8", errors="replace").read()
items = json.load(open(sys.argv[2]))["items"]
missing = []
for it in items:
    seller, path = it["seller"], it.get("path-prefix", "")
    key = path.lstrip("/").rstrip("/")
    probe = key.split("/")[0] if key else seller
    if probe and probe not in ll and seller not in ll:
        missing.append(f"{seller}:{path}")
print(f"llms.txt {len(ll)} bytes; catalog {len(items)} SKU")
if missing:
    print("NOT DISCOVERABLE (absent from llms.txt):", ", ".join(missing))
else:
    print("ok: every seller/path represented in llms.txt")
# agent buying share, from stats cache-less fetch
import urllib.request
try:
    req = urllib.request.Request("https://x402.nexus/stats", headers={"User-Agent": "hermes-agent/1.0"})
    s = json.load(urllib.request.urlopen(req)).get("settlements", {})
    h = s.get("agent-hint", {})
    tot = h.get("agent",0)+h.get("human",0)+h.get("unknown",0)
    if tot:
        print(f"agent-hint: {h.get('agent',0)}/{tot} settlements by agents — target: keep >=80% agent share")
except Exception as e:
    print("UNMEASURED stats:", e)
EOF
