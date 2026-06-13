#!/bin/bash
# mail-sync.sh — periodic incremental mail ingest for every account with
# mail.sync=true in accounts/registry.edn (run daily via launchd, or manually).
#
# Per account: mint an access token (Keychain refresh token via google-auth.py),
# ingest a sliding window (mail.window, e.g. newer_than:14d) with
# ingest-gmail-batch.py — idempotent, so window overlap is harmless.
# Then: annex add -> copy --to b2 -> git commit pointers. index.jsonl is annexed,
# so it is get/unlock-ed before appending and re-locked by the annex add.
# Accounts without a refresh token are skipped with a warning (bootstrap:
# google-auth.py login <slug>).
set -uo pipefail
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:$PATH"  # launchd の最小 PATH 対策(git-annex/datalad)

REPO=/Users/junkawasaki/github/com-junkawasaki
REG="$REPO/personal/bin/registry"
AUTH="$REPO/personal/bin/google-auth.py"
MSAUTH="$REPO/personal/bin/msgraph-auth.py"
MSGS=personal/mail/messages
LOGDIR="$HOME/.mail-sync"
LOG="$LOGDIR/sync.log"
mkdir -p "$LOGDIR"

PRESET=/opt/homebrew/Cellar/gnupg/2.4.8/libexec/gpg-preset-passphrase
KG_PRIMARY=07C2382AE713776AE86663BAD0CB4AA0C4843A5D
KG_SUB=9B9892A6C39ADD70A20C76E38B75235614E0D091

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

preset_pass(){
  local p; p=$(security find-generic-password -s "gpg:personal-data" -w 2>/dev/null) || { log "WARN: keychain read failed"; return 1; }
  printf '%s' "$p" | "$PRESET" --preset "$KG_PRIMARY" >/dev/null 2>&1
  printf '%s' "$p" | "$PRESET" --preset "$KG_SUB"     >/dev/null 2>&1
  unset p
}

cd "$REPO" || exit 1
log "=== mail-sync start ==="
preset_pass

# index.jsonl is annexed (PII): fetch content if absent, unlock for append.
git annex get "$MSGS/index.jsonl"    >>"$LOG" 2>&1
git annex unlock "$MSGS/index.jsonl" >>"$LOG" 2>&1

synced=0
while IFS=$'\t' read -r slug provider window; do
  case "$provider" in
    google)
      if ! "$AUTH" token "$slug" >/dev/null 2>&1; then
        log "SKIP $slug: no token (bootstrap: google-auth.py login $slug)"
        continue
      fi
      log "sync $slug (newer_than:$window)"
      if python3 personal/bin/ingest-gmail-batch.py --account "$slug" "newer_than:$window" 2000 >>"$LOG" 2>&1; then
        synced=$((synced+1))
      else
        log "FAIL $slug (see $LOG)"
      fi
      ;;
    microsoft)
      if ! "$MSAUTH" token "$slug" >/dev/null 2>&1; then
        log "SKIP $slug: no token (bootstrap: msgraph-auth.py login $slug)"
        continue
      fi
      log "sync $slug (graph, $window)"
      if python3 personal/bin/ingest-graph-mail.py --account "$slug" "$window" 2000 >>"$LOG" 2>&1; then
        synced=$((synced+1))
      else
        log "FAIL $slug (see $LOG)"
      fi
      ;;
    *) log "SKIP $slug: provider '$provider' not implemented" ;;
  esac
done < <("$REG" mail-accounts)

# Seal: annex (re-locks index.jsonl), encrypt-copy to B2, commit pointers.
git annex add "$MSGS"               >>"$LOG" 2>&1
git annex copy --to b2 -J4 "$MSGS"  >>"$LOG" 2>&1
if ! git diff --cached --quiet -- "$MSGS"; then
  git commit -m "personal(mail): periodic sync $(date +%F) ($synced account(s))" -- "$MSGS" >>"$LOG" 2>&1
  log "committed new mail pointers"
fi
log "=== mail-sync done ($synced account(s) synced) ==="
