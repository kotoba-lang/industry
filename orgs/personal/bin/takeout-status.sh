#!/bin/bash
# takeout-status.sh — progress of the Takeout ingest (parts ingested, files on B2, disk, queue).
# Jobs/accounts come from accounts/registry.edn via bin/registry.
REPO=/Users/junkawasaki/github/com-junkawasaki
REGISTRY="$REPO/orgs/personal/bin/registry"
LEDGER="$HOME/.takeout-watcher/processed.log"
cd "$REPO" || exit 1

echo "== Takeout ingest status =="
printf "watcher: %s\n" "$(pgrep -f takeout-watcher.sh >/dev/null && echo running || echo STOPPED)"
printf "disk free: %sG\n\n" "$(df -g /System/Volumes/Data | tail -1 | awk '{print $4}')"

while IFS=$'\t' read -r prefix acct total note; do
  parts=$(grep -c "$prefix" "$LEDGER" 2>/dev/null)
  d=$(ls -d orgs/personal/takeout/$acct/* 2>/dev/null | head -1)
  if [ -n "$d" ]; then
    onb2=$(git annex find "$d" --in b2 2>/dev/null | wc -l | tr -d ' ')
  else onb2=0; fi
  [ -n "$note" ] && note="($note)"
  printf "%-16s parts ingested: %s/%s   files on B2: %s   %s\n" "$acct" "$parts" "$total" "$onb2" "$note"
done < <("$REGISTRY" takeout-jobs)

echo
echo "queue in ~/Downloads:"
ls -lah ~/Downloads/takeout-*.zip 2>/dev/null | awk '{print "  "$5"  "$NF}' || echo "  (none)"
echo
echo "recent activity:"; tail -3 "$HOME/.takeout-watcher/watcher.log" 2>/dev/null | sed 's/^/  /'
