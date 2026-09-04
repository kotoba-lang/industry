#!/bin/bash
# x402-growth tick — detect new/removed sellers in catalog, keep seller ledger, report growth cone.
set -euo pipefail
PROFILE_DIR="${HOME}/.hermes/profiles/x402-growth"
STATE_DIR="${PROFILE_DIR}/state"
LEDGER="${STATE_DIR}/sellers_ledger.json"
mkdir -p "$STATE_DIR"

SNAP=$(mktemp)
curl -sf -A 'hermes-agent/1.0' https://x402.nexus/catalog -o "$SNAP" || { echo "UNMEASURED: catalog fetch failed"; exit 0; }

/usr/bin/python3 - "$SNAP" "$LEDGER" <<'EOF'
import json, sys, os, collections
snap, ledger = sys.argv[1], sys.argv[2]
items = json.load(open(snap))["items"]
by = collections.Counter(it["seller"] for it in items)
now = dict(by)

prev = {}
if os.path.exists(ledger):
    try: prev = json.load(open(ledger))
    except Exception: prev = {}

added = sorted(set(now) - set(prev))
removed = sorted(set(prev) - set(now))
grown = {k: now[k] for k in now if k in prev and now[k] > prev[k]}

print(f"catalog: {len(items)} SKU / {len(now)} sellers (ledger had {len(prev)})")
print("sellers:", ", ".join(f"{k}({v})" for k, v in sorted(now.items())))
if added:   print("NEW SELLERS:", ", ".join(added))
if removed: print("REMOVED SELLERS:", ", ".join(removed))
if grown:   print("EXPANDED SKUs:", ", ".join(f"{k} +{v-prev[k]}" for k, v in grown.items()))
if not (added or removed or grown):
    print("no catalog movement — growth action for this tick: pick one unregistered surface from")
    print("  the orgs tree (an app with public API but no SKU) and draft its /apply registration")
    print("  pitch; candidates seen before: itonami, kotoba-lang SDK demos, giemon, torihiki.")
json.dump(now, open(ledger, "w"), indent=1)
EOF
