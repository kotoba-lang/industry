#!/bin/bash
# Hermes cron runs .sh via bash and nothing else (README: "script files as
# bash or Python"), so this wrapper is the job and the ClojureScript beside it
# is the implementation. It holds no decisions: cd into the bot's worktree and
# run nbb with the kotoba stdlib on the classpath (kotoba.lang.http for the
# request model + parse-url, kotoba.lang.json for body decoding).
set -u
WORKTREE="${HYAKKA_BOT_WORKTREE:-$HOME/.itonami/worktrees/hyakka-wikidata-class-bot}"
NBB="${HYAKKA_NBB:-/opt/homebrew/bin/nbb}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Derived, not hardcoded: this file lives at <root>/scripts/hermes-hyakka-bots/,
# so the west checkouts are two levels up. HYAKKA_KOTOBA overrides it.
KOTOBA="${HYAKKA_KOTOBA:-$(cd "$SCRIPT_DIR/../.." && pwd)/orgs/kotoba-lang}"

cd "$WORKTREE" 2>/dev/null || {
  echo "REFUSED — no worktree at $WORKTREE"
  exit 0
}
exec "$NBB" --classpath "src:$KOTOBA/http/src:$KOTOBA/json/src" \
     "$SCRIPT_DIR/hyakka_notability_audit.cljs" config/knowledge-ingest.edn
