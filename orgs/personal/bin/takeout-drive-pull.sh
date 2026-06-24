#!/bin/bash
# takeout-drive-pull.sh — pull Takeout parts that were exported "to Drive"
# (ADR-0008 系。passkey 再認証問題の回避経路).
#
#   Drive の Takeout フォルダ -> rclone で ~/Downloads に1個ずつ落とす
#   -> takeout-watcher.sh が展開 -> git annex add -> copy --to b2 -> drop
#
# zip 以外の同梱 loose ファイル（.mbox/.duckdb/.mp4 等、Takeout が zip 外に
# 出した >2GB 品）は personal/takeout/<acct>/<date>/drive-loose/ に置いて
# このスクリプト自身が annex -> b2 -> drop する。
# .vmdk 等 SKIP_GB 超は内蔵ディスクに載らないためスキップして報告のみ。
#
# usage: takeout-drive-pull.sh <rclone-remote> <drive-folder-id> <account> <date>
set -uo pipefail

REMOTE="${1:?rclone remote (e.g. gjun784:)}"
FOLDER_ID="${2:?drive folder id}"
ACCT="${3:?account slug}"
DATE="${4:?YYYY-MM-DD}"

REPO=/Users/junkawasaki/github/com-junkawasaki
DL="$HOME/Downloads"
STATEDIR="$HOME/.takeout-drive-pull"
PULLED="$STATEDIR/pulled.log"          # title 単位（Drive 側重複は1回だけ）
WSTATE="$HOME/.takeout-watcher/processed.log"
LOG="$STATEDIR/pull.log"
mkdir -p "$STATEDIR"; touch "$PULLED"
MIN_FREE_GB=18                          # 落とす前に必要な空き（zip+展開分）
SKIP_GB=40                              # これ以上の単一ファイルは内蔵に載らない
MAX_QUEUE=2                             # ~/Downloads に滞留させる zip の最大数

PRESET=/opt/homebrew/Cellar/gnupg/2.4.8/libexec/gpg-preset-passphrase
KG_PRIMARY=07C2382AE713776AE86663BAD0CB4AA0C4843A5D
KG_SUB=9B9892A6C39ADD70A20C76E38B75235614E0D091

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
free_gb(){ df -g /System/Volumes/Data | tail -1 | awk '{print $4}'; }
preset_pass(){
  local p; p=$(security find-generic-password -s "gpg:personal-data" -w 2>/dev/null) || return 1
  printf '%s' "$p" | "$PRESET" --preset "$KG_PRIMARY" >/dev/null 2>&1
  printf '%s' "$p" | "$PRESET" --preset "$KG_SUB"     >/dev/null 2>&1
  unset p
}

wait_disk(){ # $1 = needed GB
  local need="$1"
  while [ "$(free_gb)" -lt "$need" ]; do
    log "wait: disk $(free_gb)G < ${need}G (watcher draining...)"; sleep 120
  done
}
wait_queue(){
  while [ "$(ls "$DL"/takeout-*.zip 2>/dev/null | wc -l | tr -d ' ')" -ge "$MAX_QUEUE" ]; do
    sleep 60
  done
}

annex_loose(){ # $1 = repo-relative path
  cd "$REPO" || return 1
  preset_pass
  git annex add "$1"                 >>"$LOG" 2>&1 &&
  git annex copy --to b2 -J8 "$1"    >>"$LOG" 2>&1 &&
  git annex drop --in b2 "$1"        >>"$LOG" 2>&1
}

# ---- inventory: "size<TAB>name" を size 昇順で ----
inventory(){
  rclone lsjson "$REMOTE" --drive-root-folder-id "$FOLDER_ID" --files-only --no-modtime 2>>"$LOG" |
  python3 -c '
import json,sys
seen=set()
rows=[]
for f in json.load(sys.stdin):
    n=f["Name"]
    if n in seen: continue   # Drive 側の同名重複は1つだけ
    seen.add(n)
    rows.append((f["Size"],n))
for s,n in sorted(rows):
    print(f"{s}\t{n}")
'
}

log "=== pull start: $REMOTE folder=$FOLDER_ID acct=$ACCT date=$DATE ==="
TOTAL=0; DONE=0; SKIPPED=0
while IFS=$'\t' read -r size name; do
  TOTAL=$((TOTAL+1))
  gb=$(( size / 1073741824 ))
  if grep -qxF "$name" "$PULLED"; then DONE=$((DONE+1)); continue; fi
  if grep -qxF "$name" "$WSTATE" 2>/dev/null; then
    echo "$name" >>"$PULLED"; DONE=$((DONE+1)); continue
  fi
  if [ "$gb" -ge "$SKIP_GB" ]; then
    log "SKIP(too big ${gb}G): $name"; SKIPPED=$((SKIPPED+1)); continue
  fi

  wait_disk $(( gb + MIN_FREE_GB ))

  case "$name" in
    takeout-*.zip)
      wait_queue
      log "PULL zip ${gb}G: $name"
      if rclone copy "$REMOTE" --drive-root-folder-id "$FOLDER_ID" \
           --include "$name" "$DL" --transfers 2 >>"$LOG" 2>&1; then
        echo "$name" >>"$PULLED"; DONE=$((DONE+1))
        log "QUEUED for watcher: $name"
      else
        log "PULL FAIL: $name"
      fi
      ;;
    *)
      dest="personal/takeout/$ACCT/$DATE/drive-loose"
      mkdir -p "$REPO/$dest"
      log "PULL loose ${gb}G: $name"
      if rclone copy "$REMOTE" --drive-root-folder-id "$FOLDER_ID" \
           --include "$name" "$REPO/$dest" --transfers 2 >>"$LOG" 2>&1; then
        if annex_loose "$dest/$name"; then
          echo "$name" >>"$PULLED"; DONE=$((DONE+1))
          log "ANNEXED->B2: $name"
        else
          log "ANNEX FAIL: $name"
        fi
      else
        log "PULL FAIL: $name"
      fi
      ;;
  esac
done < <(inventory)

log "=== pull pass done: pulled/known=$DONE/$TOTAL skipped(too-big)=$SKIPPED ==="
# zip は watcher が非同期に処理するため、最終確認は takeout-status.sh で。
