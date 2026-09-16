#!/bin/sh
# Daily NVD dataset sync: incremental fetch (lastModified watermark) ->
# per-year parquet rebuild -> R2 publish (readback-verified) -> Iceberg
# table overwrite. Safe to re-run. Canonical copy lives in the superproject
# scripts/hermes-nvd-dataset/; cron jobs get a copy in ~/.hermes/scripts.
set -eu
# Never run alongside the detached full walk (double-writer races state.json)
if pgrep -f 'fetch_nvd.py full' >/dev/null 2>&1; then
  echo "SKIP: fetch_nvd full walk still running"; exit 0
fi
PY="$HOME/venvs/iceberg/bin/python"
DIR="$HOME/github/com-junkawasaki/scripts/hermes-nvd-dataset"
LOG="${NVD_SYNC_LOG:-$HOME/nvd-dataset/sync.log}"
mkdir -p "$HOME/nvd-dataset"
{
  echo "=== nvd-dataset-sync $(date -u +%FT%TZ)"
  "$PY" "$DIR/fetch_nvd.py" incr
  "$PY" "$DIR/publish_nvd_r2.py"
  "$PY" "$DIR/sync_nvd_iceberg.py"
  echo "=== sync complete $(date -u +%FT%TZ)"
} >>"$LOG" 2>&1
tail -3 "$LOG"
