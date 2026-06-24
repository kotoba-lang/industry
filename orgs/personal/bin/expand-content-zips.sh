#!/bin/bash
# expand-content-zips.sh — 既に annex 済みの「アーカイブ内容物 zip」について、
# 原本 zip を保持したまま隣に <name>.zip.extracted/ を併置して annex -> B2 -> drop する
# 一回限りのバックフィル。watcher の expand_zips() 導入(2026-06-11)以前の ingest 分が対象。
# 冪等: .extracted が存在(または git 管理済み)ならスキップ。
set -uo pipefail

REPO=/Users/junkawasaki/github/com-junkawasaki
LOG="$HOME/.takeout-drive-pull/expand-backfill.log"
PRESET=/opt/homebrew/Cellar/gnupg/2.4.8/libexec/gpg-preset-passphrase
KG_PRIMARY=07C2382AE713776AE86663BAD0CB4AA0C4843A5D
KG_SUB=9B9892A6C39ADD70A20C76E38B75235614E0D091
MIN_FREE_GB=12

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
free_gb(){ df -g /System/Volumes/Data | tail -1 | awk '{print $4}'; }
preset_pass(){
  local p; p=$(security find-generic-password -s "gpg:personal-data" -w 2>/dev/null) || return 1
  printf '%s' "$p" | "$PRESET" --preset "$KG_PRIMARY" >/dev/null 2>&1
  printf '%s' "$p" | "$PRESET" --preset "$KG_SUB"     >/dev/null 2>&1
  unset p
}

cd "$REPO" || exit 1
preset_pass

DONE=0; FAIL=0; SKIP=0
while IFS= read -r -d '' z; do
  rel="${z#./}"
  if [ -e "$rel.extracted" ]; then SKIP=$((SKIP+1)); continue; fi
  while [ "$(free_gb)" -lt "$MIN_FREE_GB" ]; do log "wait disk ($(free_gb)G)"; sleep 120; done
  preset_pass
  log "BACKFILL: $rel"
  if ! git annex get "$rel" >>"$LOG" 2>&1; then log "GET FAIL: $rel"; FAIL=$((FAIL+1)); continue; fi
  if ditto -x -k "$rel" "$rel.extracted" >>"$LOG" 2>&1; then
    git annex add "$rel.extracted"              >>"$LOG" 2>&1
    git annex copy --to b2 -J4 "$rel.extracted" >>"$LOG" 2>&1
    git annex drop --in b2 "$rel.extracted"     >>"$LOG" 2>&1
    DONE=$((DONE+1))
    log "OK: $rel.extracted"
  else
    rm -rf "$rel.extracted"
    log "EXPAND FAIL (kept original only): $rel"
    FAIL=$((FAIL+1))
  fi
  git annex drop --in b2 "$rel" >>"$LOG" 2>&1   # 原本 zip 本体は B2 にあるので local drop
done < <(find ./personal/takeout -type l -name '*.zip' ! -path '*.zip.extracted/*' -print0 2>/dev/null)

log "=== backfill done: expanded=$DONE skip=$SKIP fail=$FAIL ==="
