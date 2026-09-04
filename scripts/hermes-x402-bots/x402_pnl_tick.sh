#!/bin/bash
# x402-pnl tick — measure GMV + settlement census, diff vs previous, propose fee ADR when earned enough.
set -euo pipefail
PROFILE_DIR="${HOME}/.hermes/profiles/x402-pnl"
STATE_DIR="${PROFILE_DIR}/state"
mkdir -p "$STATE_DIR"
PREV="${STATE_DIR}/pnl_prev.json"

SNAP=$(mktemp)
curl -sf -A 'hermes-agent/1.0' https://x402.nexus/stats -o "$SNAP" || { echo "UNMEASURED: stats fetch failed"; exit 0; }
CAT=$(mktemp)
curl -sf -A 'hermes-agent/1.0' https://x402.nexus/catalog -o "$CAT" || true

/usr/bin/python3 - "$SNAP" "$CAT" "$PREV" <<'EOF'
import json, sys, collections, os
snap, cat, prev = sys.argv[1], sys.argv[2], sys.argv[3]
st = json.load(open(snap))
s = st.get("settlements", {})
cnt = s.get("count", 0); usd = s.get("usd-total", 0)
sku = st.get("count", 0)
direct = s.get("sources", {}).get("direct", {})
dag = s.get("sources", {}).get("direct-agent", {})
hints = s.get("agent-hint", {})

diff = ""
if os.path.exists(prev):
    p = json.load(open(prev))
    d_cnt = cnt - p.get("count", 0); d_usd = round(usd - p.get("usd-total", 0), 6)
    if d_cnt or d_usd:
        diff = f"  DELTA: +{d_cnt} settlements / +${d_usd} since last tick"
else:
    diff = "  (first tick — baseline recorded)"

print(f"x402.nexus PnL: {cnt} settlements / ${usd} USDC cumulative | catalog {sku} SKU")
print(f"  agent-hint: agent={hints.get('agent',0)} human={hints.get('human',0)} unknown={hints.get('unknown',0)}")
print(f"  direct: gmv=${direct.get('service-gmv-usd',0)} wallets={direct.get('paying-wallets',0)} repeat={direct.get('repeat-rate',0):.2f}")
print(f"  direct-agent: gmv=${dag.get('service-gmv-usd',0)} wallets={dag.get('paying-wallets',0)} repeat={dag.get('repeat-rate',0):.2f}")
print(diff)
# fee proposal gate: propose once GMV crosses $10 cumulative
if usd >= 10:
    print("  PROPOSAL: cumulative GMV >= $10 — draft ADR for facilitator fee (2-5% on settle) or self-SKU.")
EOF

if [ ! -f "$PREV" ] || ! cmp -s "$SNAP" "$PREV"; then
  cp "$SNAP" "$PREV"
fi
