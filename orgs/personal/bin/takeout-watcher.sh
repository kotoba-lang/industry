#!/bin/bash
# takeout-watcher.sh — ingest Google Takeout zip parts into the personal warehouse.
# For each completed takeout-*.zip in ~/Downloads whose job-timestamp prefix is
# registered in accounts/registry.edn ([takeout."<prefix>"]):
#   extract (ditto, Unicode-safe) -> delete zip -> git annex add -> copy --to b2 (-J8) -> drop local.
# Routes to orgs/personal/takeout/<account>/<date>/. The registry is re-read every pass,
# so new Takeout jobs added to registry.edn are picked up without a restart.
# Idempotent via a processed-ledger; re-presets the GPG passphrase from Keychain each pass.
set -uo pipefail

REPO=/Users/junkawasaki/github/com-junkawasaki
REGISTRY="$REPO/orgs/personal/bin/registry"
DL="$HOME/Downloads"
STATEDIR="$HOME/.takeout-watcher"
STATE="$STATEDIR/processed.log"
LOG="$STATEDIR/watcher.log"
mkdir -p "$STATEDIR"; touch "$STATE"

PRESET=/opt/homebrew/Cellar/gnupg/2.4.8/libexec/gpg-preset-passphrase
KG_PRIMARY=07C2382AE713776AE86663BAD0CB4AA0C4843A5D
KG_SUB=9B9892A6C39ADD70A20C76E38B75235614E0D091
JOBS=8
MIN_FREE_GB=6

log(){ echo "[$(date '+%F %T')] $*" >>"$LOG"; }

preset_pass(){
  local p; p=$(security find-generic-password -s "gpg:personal-data" -w 2>/dev/null) || { log "WARN: keychain read failed"; return 1; }
  printf '%s' "$p" | "$PRESET" --preset "$KG_PRIMARY" >/dev/null 2>&1
  printf '%s' "$p" | "$PRESET" --preset "$KG_SUB"     >/dev/null 2>&1
  unset p
}

free_gb(){ df -g /System/Volumes/Data | tail -1 | awk '{print $4}'; }

stable(){ # path -> 0 if download complete (no .crdownload, size stable 4s)
  [ -e "$1.crdownload" ] && return 1
  local a b; a=$(stat -f%z "$1" 2>/dev/null) || return 1
  sleep 4; b=$(stat -f%z "$1" 2>/dev/null) || return 1
  [ "$a" = "$b" ] && [ "$a" -gt 0 ]
}

norm(){ basename "$1" | sed -E 's/ \([0-9]+\)\.zip$/.zip/'; }  # strip " (1)" dup suffix


expand_zips(){ # $1 = extracted dest dir — 内容物 zip の隣に .extracted/ を併置（原本は保持）
  # -type f: 未 annex の新規展開ファイルのみ対象（annex 済みは symlink なので対象外）
  local z
  while IFS= read -r -d '' z; do
    [ -e "$z.extracted" ] && continue
    if ditto -x -k "$z" "$z.extracted" >>"$LOG" 2>&1; then
      log "EXPANDED: $(basename "$z")"
    else
      rm -rf "$z.extracted"
      log "EXPAND FAIL (kept original only): $(basename "$z")"
    fi
  done < <(find "$1" -type f -name '*.zip' ! -path '*.zip.extracted/*' -print0 2>/dev/null)
}

process(){
  local zip="$1" acct="$2" base nm prefix date dest
  base=$(basename "$zip"); nm=$(norm "$zip")
  grep -qxF "$nm" "$STATE" && { rm -f "$zip"; return 0; }   # already ingested -> just remove dup
  prefix=$(grep -oE 'takeout-[0-9]{8}T[0-9]{6}Z' <<<"$base") || { log "SKIP (no prefix): $base"; return 1; }
  date=$(sed -E 's/takeout-([0-9]{4})([0-9]{2})([0-9]{2}).*/\1-\2-\3/' <<<"$prefix")
  dest="orgs/personal/takeout/$acct/$date"
  log "START $base -> $dest ($(free_gb)G free)"
  mkdir -p "$REPO/$dest"
  if ! ditto -x -k "$zip" "$REPO/$dest" >>"$LOG" 2>&1; then log "EXTRACT FAIL $base"; echo "!! EXTRACT FAIL $base"; return 1; fi
  rm -f "$zip"                       # free the zip immediately after extract
  expand_zips "$REPO/$dest"          # 内容物 zip は原本+展開コピー併置 (ADR-0008 追記)
  cd "$REPO" || return 1
  preset_pass
  git annex add "$dest"                >>"$LOG" 2>&1
  git annex copy --to b2 -J"$JOBS" "$dest" >>"$LOG" 2>&1
  git annex drop --in b2 "$dest"       >>"$LOG" 2>&1   # drop local copies verified on b2
  echo "$nm" >>"$STATE"
  log "DONE $base ($(free_gb)G free)"; echo "OK $base ($acct) [$(free_gb)G free]"
}

log "=== watcher start (pid $$) ==="
while true; do
  preset_pass
  shopt -s nullglob
  while IFS=$'\t' read -r prefix acct _parts _note; do
    for zip in "$DL/$prefix"-*.zip; do
      stable "$zip" || continue
      process "$zip" "$acct"
    done
  done < <("$REGISTRY" takeout-jobs 2>>"$LOG")
  shopt -u nullglob
  sleep 30
done
