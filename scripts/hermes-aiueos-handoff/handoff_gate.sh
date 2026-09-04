#!/bin/bash
# handoff_gate.sh — AIUEOS K16 pure-Kotoba handoff (ADR-2609031030) の gate 状態 signal。
# 決定的・時刻自由。cron monitor として使い、gate 状態が変わった tick だけ agent を起こす。
# exit 0 常時。stdout が monitor 状態。
set -u

REPO="${HANDOFF_REPO:-kotoba-lang/aiueos}"
BRANCH="${HANDOFF_BRANCH:-codex/pure-native-k16}"
ADR_ID="adr-2609031030-aiueos-k16-pure-kotoba-physical-tcp-handoff"
N=$(nbb --classpath ".:scripts/nbb_compat" scripts/verify-west-pins.cljs --dir "$(pwd)" >/dev/null 2>&1 && echo OK || echo FAIL)

ghq() { gh "$@" 2>/dev/null; }

echo "=== experiment branch tip ($REPO@$BRANCH) ==="
ghq api "repos/$REPO/branches/$BRANCH" --jq '.name + " " + .commit.sha' 2>/dev/null || echo UNKNOWN

echo "=== evidence commit present? ==="
ghq api "repos/$REPO/commits/411b4756404c6a03c80c048725bc24b6bd400b71" --jq '.sha[0:7] + " " + (.commit.message | split("\n")[0])' 2>/dev/null || echo MISSING

echo "=== force-update check on experiment branch (must stay non-force) ==="
# 分からない時は UNKNOWN と出す。捏造しない。
echo UNKNOWN

echo "=== open PRs referencing the handoff ==="
ghq pr list --repo "$REPO" --state open --limit 20 --json number,title,headRefName \
  | python3 -c 'import json,sys
d = json.load(sys.stdin)
hits = [p for p in d if any(k in (p["title"]+p.get("headRefName","")) for k in ("pure-native-k16","handoff","checksum","N1","persistent-tcp","tls"))]
for p in hits: print("#%s %s | %s" % (p["number"], p["title"][:80], p.get("headRefName","")))
if not hits: print("none")'

echo "=== west pin advance sanity ==="
echo "verify-west-pins: $N"

echo "=== PXE artifact on Mac (K16 physical resume point) ==="
PXE_DIR="$HOME/Library/Application Support/AIUEOS/K16 PXE"
if [ -d "$PXE_DIR" ]; then
  ls "$PXE_DIR" | grep -v '^run-k16-pxe.sh$' | head -5
  for f in "$PXE_DIR"/*.efi; do
    [ -f "$f" ] && shasum -a 256 "$f" | awk '{print substr($1,1,16), $2}'
  done | head -3
  LOG="$HOME/Library/Logs/AIUEOS/k16-pxe.stdout.log"
  if [ -f "$LOG" ]; then
    echo "--- pxe log tail (markers only) ---"
    grep -E 'AIUEOS_NATIVE|TCP_OK|TFTP|HTTP' "$LOG" | tail -8
  else
    echo "pxe log absent"
  fi
else
  echo "PXE dir absent — physical gate cannot resume from this host"
fi

echo "=== exit matrix reminder (ADR-2609031030) ==="
echo "green: PXE/UEFI boot, ARP, TCP handshake (60 / 40,43,44,45,TCP_OK)"
echo "next gates: N1 checksum admission (93/96) -> N2 persistent stream -> N3 TLS 1.3 -> N4 HTTPS -> N5 enrollment/Murakumo -> N6 Qwen tok/s"
echo "rule: 捏造禁止 — wire marker/screen code の無い gate は green にしない。実験 branch の wholesale merge/rebase/root pin 前進禁止。"
