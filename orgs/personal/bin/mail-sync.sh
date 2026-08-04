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
REG="$REPO/orgs/personal/bin/registry"
AUTH="$REPO/orgs/personal/bin/google-auth.py"
MSAUTH="$REPO/orgs/personal/bin/msgraph-auth.py"
MSGS=orgs/personal/mail/messages
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
      if python3 orgs/personal/bin/ingest-gmail-batch.py --account "$slug" "newer_than:$window" 2000 >>"$LOG" 2>&1; then
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
      if python3 orgs/personal/bin/ingest-graph-mail.py --account "$slug" "$window" 2000 >>"$LOG" 2>&1; then
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
  msg="personal(mail): periodic sync $(date +%F) ($synced account(s))"
  git commit -m "$msg" -- "$MSGS" >>"$LOG" 2>&1
  log "committed new mail pointers"

  # Land it. This step did not exist: the commit was made and nothing else
  # happened, so pointers piled up on whatever branch the shared checkout was
  # left on. Measured 2026-08-04: 21 sync commits (33 .eml pointers) stranded
  # on gap/adr-correct while the superproject drifted to 517 behind / 21 ahead
  # of main. Nothing was lost -- the blobs were already in B2 -- but nothing
  # landed either, and the gap grew every 15 minutes.
  branch="$(git rev-parse --abbrev-ref HEAD)"
  if [ "$branch" != "main" ]; then
    # Deliberately not "switch to main and commit there": this routine shares a
    # working tree with human and agent sessions, and moving their branch out
    # from under them is worse than not landing. Loud beats clever.
    log "WARN on branch '$branch', not main -- pointers are committed but NOT landed."
    log "WARN land them with: git push origin HEAD:refs/heads/routine/mail-sync-$(date +%F)"
  else
    rb="routine/mail-sync-$(date +%F)"
    if git push -q --force-with-lease origin "HEAD:refs/heads/$rb" >>"$LOG" 2>&1; then
      # Server-side merge, per CLAUDE.md: no local merge, no rebase, never a
      # push straight to main.
      if gh api repos/com-junkawasaki/root/merges \
           -f base=main -f "head=$rb" -f "commit_message=$msg" >>"$LOG" 2>&1; then
        log "landed $rb on main"
      else
        # A race or conflict is not worth failing the sync over: the commit and
        # the branch both exist, and the next run retries. Silence would be the
        # real problem, so say so.
        log "WARN merge of $rb failed -- branch is pushed; it will retry next run"
      fi
    else
      log "WARN push of $rb failed -- pointers remain committed locally; will retry"
    fi
  fi
fi
log "=== mail-sync done ($synced account(s) synced) ==="
