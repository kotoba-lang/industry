#!/bin/bash
# yabai-data-catalog-sync — daily mechanical mirror of the three public
# /yabai-data catalogs (yabai / crypto / waterplum) into the kotoba-open-data
# R2 bucket + R2 Data Catalog (Iceberg ns yabai), for the yabai-data-catalog
# bot profile. no_agent cron bot.
#
# Flow: fetch the published index.json/relations.json from kotoba.cloud (the
# source of record) -> per-domain Parquet + raw snapshots -> wrangler --remote
# PUT with sha256 read-back -> Iceberg overwrite. Watchdog contract: empty
# stdout = silence; one line = the run report; "FAILED" = needs attention.
set -uo pipefail

REPO=/Users/junkawasaki/github/com-junkawasaki
CANON=$REPO/scripts/hermes-yabai-data-catalog
OUT=$HOME/.gftd/yabai-data-catalog
PY=$HOME/venvs/iceberg/bin/python
export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin

fail() { echo "yabai-data-catalog-sync FAILED: $*"; exit 1; }

TOKFILE=/tmp/.yabai-catalog-tok-$$
security find-generic-password -s gftd.cf -a API_TOKEN -w > "$TOKFILE" 2>/dev/null || fail "keychain gftd.cf/API_TOKEN"
export CLOUDFLARE_API_TOKEN="$(cat "$TOKFILE")"
rm -f "$TOKFILE"
[ -n "$CLOUDFLARE_API_TOKEN" ] || fail "empty keychain token"
export CF_CATALOG_TOKEN="$CLOUDFLARE_API_TOKEN"

mkdir -p "$OUT"
LOCK="$OUT/.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  AGE=$(( $(date +%s) - $(stat -f %m "$LOCK" 2>/dev/null || echo 0) ))
  if [ "$AGE" -lt 3600 ]; then exit 0; fi
  rmdir "$LOCK" 2>/dev/null || exit 0
  mkdir "$LOCK" || exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

[ -x "$PY" ] || fail "no pyarrow python at $PY"

OUT="$OUT" "$PY" "$CANON/collect_yabai_r2.py" > /tmp/yabai-collect.log 2>&1 || { tail -3 /tmp/yabai-collect.log; fail "collect"; }
OUT="$OUT" "$PY" "$CANON/publish_yabai_r2.py" > /tmp/yabai-publish.log 2>&1 || { tail -3 /tmp/yabai-publish.log; fail "r2 publish"; }
OUT="$OUT" "$PY" "$CANON/sync_yabai_iceberg.py" > /tmp/yabai-iceberg.log 2>&1 || { tail -3 /tmp/yabai-iceberg.log; fail "iceberg sync"; }

COLLECT=$(tail -1 /tmp/yabai-collect.log)
TABLES=$(tail -1 /tmp/yabai-iceberg.log)
echo "yabai-data-catalog-sync: $COLLECT; $TABLES; bucket=kotoba-open-data ns=yabai"
