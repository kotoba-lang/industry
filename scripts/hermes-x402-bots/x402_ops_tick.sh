#!/bin/bash
# x402-ops tick — liveness of every advertised surface + SKU-count consistency.
set -euo pipefail
U='hermes-agent/1.0'
fail=0
check() { # url name
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -A "$U" --max-time 15 "$1") || code=000
  if [ "$code" != "200" ]; then echo "DOWN: $2 -> HTTP $code"; fail=1; else echo "ok: $2"; fi
}
check https://x402.nexus/health health
check https://x402.nexus/catalog catalog
check https://x402.nexus/stats stats
check https://x402.nexus/llms.txt llms.txt
check https://x402.nexus/apply apply
check https://x402.nexus/.well-known/x402 discovery

# spot-check one gateway path returns 402 (challenge) not 500
gw=$(curl -s -o /dev/null -w '%{http_code}' -A "$U" --max-time 15 'https://x402.nexus/gateway/murakumo/x402/v1/chat/completions')
case "$gw" in
  402|405|400) echo "ok: gateway challenge ($gw)" ;;
  000) echo "DOWN: gateway unreachable"; fail=1 ;;
  *) echo "WARN: gateway returned $gw (expected 402-class)" ;;
esac

# SKU count sanity: catalog vs stats agree
c1=$(curl -sf -A "$U" https://x402.nexus/catalog | /usr/bin/python3 -c 'import sys,json;print(json.load(sys.stdin)["count"])')
c2=$(curl -sf -A "$U" https://x402.nexus/stats | /usr/bin/python3 -c 'import sys,json;print(json.load(sys.stdin)["count"])')
[ "$c1" = "$c2" ] && echo "ok: catalog/stats SKU agree ($c1)" || { echo "MISMATCH: catalog=$c1 stats=$c2"; fail=1; }

exit $fail
