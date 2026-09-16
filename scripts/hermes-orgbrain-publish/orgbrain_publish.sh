#!/bin/bash
# orgbrain-publish — mechanical catalog sync bot (2026-09-16, owner direction
# "next": close the kyber-main → kyber.kotoba.cloud loop). No LLM: cron runs
# this with no_agent; empty stdout = silence, one line = the day's report.
#
# Flow: kyber main SHA vs published /org-data rev → (drift) rebuild the
# catalog with auto-discovery, regenerate the JS-port golden from the
# Clojure truth at that SHA, gate on the catalog test, open + self-merge
# the sync PR (diff must stay inside the catalog + golden test), then
# `npm run deploy` and verify the live host serves the new rev.
# Charter (ADR-2609142000): content gates stay in kyber's own suite; this
# bot only publishes what already merged to kyber main — it proposes nothing.
set -uo pipefail

REPO_DIR=/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-kotoba/app-kotoba-cloud
WT="$HOME/.gftd/worktrees/orgbrain-publish/app"
CANON=/Users/junkawasaki/github/com-junkawasaki/scripts/hermes-orgbrain-publish
KYBER=https://github.com/kotoba-lang/kyber
export KBB_HOME=$REPO_DIR/../kotoba
export KBB_ENGINE=$REPO_DIR/../org-babashka-nbb/cli.js
export PATH=$KBB_HOME/bin:$PATH
LOG=/tmp/orgbrain-publish-deploy.log

fail() { echo "orgbrain-publish FAILED: $*"; exit 1; }

# --- lock (cron is 2x/day; a hung deploy must not stack) ---
mkdir -p "$HOME/.gftd/worktrees/orgbrain-publish"
LOCK="$HOME/.gftd/worktrees/orgbrain-publish/.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  AGE=$(( $(date +%s) - $(stat -f %m "$LOCK" 2>/dev/null || echo 0) ))
  if [ "$AGE" -lt 5400 ]; then
    exit 0   # another tick is live (<90min)
  fi
  rmdir "$LOCK" 2>/dev/null || exit 0
  mkdir "$LOCK" || exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

# --- worktree bootstrap ---
[ -d "$WT" ] || { mkdir -p "$(dirname "$WT")"; git -C "$REPO_DIR" fetch -q kotoba-lang main || fail "fetch leaf"; \
  git -C "$REPO_DIR" worktree add --detach "$WT" kotoba-lang/main >/dev/null 2>&1 || fail "worktree add"; \
  ln -s "$REPO_DIR/node_modules" "$WT/node_modules"; }
cd "$WT" || fail "no worktree"
[ -e node_modules ] || ln -s "$REPO_DIR/node_modules" node_modules
git fetch -q kotoba-lang main || fail "fetch"
git checkout -q -B publish-main kotoba-lang/main || fail "checkout main"
git clean -qfd assets/orgbrain-catalog 2>/dev/null

SHA=$(git ls-remote "$KYBER" refs/heads/main | awk '{print $1}')
[ ${#SHA} -eq 40 ] || fail "kyber sha lookup"
PUB=$(node -p "JSON.parse(require('fs').readFileSync('assets/orgbrain-catalog/index.json','utf8')).source.rev") || fail "published rev read"

# --- selftest mode: prove discovery+golden against a synthetic 3rd process ---
if [ "${ORGBRAIN_SELFTEST:-}" = "1" ]; then
  TMPK=$(mktemp -d)
  gh api "repos/kotoba-lang/kyber/tarball/$SHA" | tar xz -C "$TMPK" 2>/dev/null || fail "selftest kyber tarball"
  KDIR=$(ls -d "$TMPK"/* | head -1)
  node -e '
    const fs=require("fs");
    const base=fs.readFileSync(process.argv[1]+"/docs/orgbrain/onboarding-offboarding.bpmn.edn","utf8");
    fs.writeFileSync(process.argv[1]+"/docs/orgbrain/finance-close.bpmn.edn",
      base.replace(/onboarding-offboarding/g,"finance-close")
          .replace(/:orgbrain\.bpmn\/name "[^"]*"/, ":orgbrain.bpmn/name \"月次決算プロセス\""));
  ' "$KDIR" || fail "selftest fixture"
  ORGBRAIN_REV=$SHA ORGBRAIN_GENERATED_AT=2026-01-01T00:00:00.000Z \
    node scripts/build-orgbrain-data.mjs --dir="$KDIR" || fail "selftest build"
  node -e '
    const i=JSON.parse(require("fs").readFileSync("assets/orgbrain-catalog/index.json","utf8"));
    const n=i.processes.length; if(n!==3) {console.error("expected 3 processes, got",n);process.exit(1);}
    console.log("selftest discovery: 3 processes =", i.processes.map(p=>p.id).join(","));
  ' || fail "selftest discovery"
  node test/orgbrain-catalog.mjs || fail "selftest catalog test"
  git checkout -q -- assets/orgbrain-catalog 2>/dev/null
  git clean -qfd assets/orgbrain-catalog
  rm -rf "$TMPK"
  echo "orgbrain-publish SELFTEST green (synthetic 3rd process published + gated)"
  exit 0
fi

# --- drift? ---
if [ "$SHA" = "$PUB" ]; then
  exit 0   # silent no-op
fi
BR="agent/orgbrain-sync-${SHA:0:12}"
gh pr list --repo cloud-kotoba/app-kotoba-cloud --head "$BR" --state open --json number 2>/dev/null | grep -q '"number"' \
  && { echo "orgbrain-publish: sync PR already open for ${SHA:0:12} — waiting on gates"; exit 0; }

# --- rebuild catalog at the new rev (auto-discovery) ---
ORGBRAIN_REV=$SHA node scripts/build-orgbrain-data.mjs || fail "catalog build at ${SHA:0:12}"

# --- regenerate the JS-port golden from the Clojure truth at that SHA ---
TMPK=$(mktemp -d)
trap 'rmdir "$LOCK" 2>/dev/null; rm -rf "$TMPK"' EXIT
gh api "repos/kotoba-lang/kyber/tarball/$SHA" | tar xz -C "$TMPK" 2>/dev/null || fail "kyber tarball"
KDIR=$(ls -d "$TMPK"/* | head -1)
cp "$CANON/golden_emit.cljk" "$KDIR/golden_emit.cljk" || fail "golden_emit copy"
(cd "$KDIR" && kbb --backend sci --classpath . golden_emit.cljk) > "$TMPK/report.edn" \
  || fail "kbb golden emit at ${SHA:0:12}"
node scripts/orgbrain-golden.mjs "$TMPK/report.edn" "$WT" || fail "golden rewrite"

# --- gate + scope guard ---
node test/orgbrain-catalog.mjs || fail "catalog tests red at ${SHA:0:12} (kyber content or schema drift — needs a human)"
STRAY=$(git diff --name-only publish-main | grep -Ev '^(assets/orgbrain-catalog/|test/orgbrain-catalog\.mjs$)' || true)
[ -z "$STRAY" ] || fail "unexpected diff outside the catalog: $STRAY"
git diff --quiet || {
  git add -A assets/orgbrain-catalog test/orgbrain-catalog.mjs
  git commit -q -m "orgbrain: catalog sync to kyber@${SHA}"
  git push -qf kotoba-lang "$BR" || fail "push $BR"
  gh pr create --repo cloud-kotoba/app-kotoba-cloud --head "$BR" --base main \
    --title "orgbrain: catalog sync kyber@${SHA:0:12}" \
    --body "機械同期 (orgbrain-publish bot)。kyber main ${SHA} を /org-data へ。自動発見プロセス一覧と golden はその SHA の Clojure 真値から再生成。catalog test green 確認済み。diff は assets/orgbrain-catalog/ + test/orgbrain-catalog.mjs のみ。" >/dev/null \
    || fail "PR create"
  # mechanical self-merge (ops-bots discipline): the gate IS the test run above
  sleep 3
  gh pr merge "$BR" --repo cloud-kotoba/app-kotoba-cloud --merge --admin >/dev/null 2>&1 || gh pr merge "$BR" --repo cloud-kotoba/app-kotoba-cloud --merge || fail "PR merge"
  git fetch -q kotoba-lang main && git checkout -q -B publish-main kotoba-lang/main || fail "pull merged main"
}

# --- deploy: the skill-proven stage order from a clean checkout. npm run
# build MUST precede the gates, and package.json's `npm test` is skipped:
# the JVM clojure suite is baseline-red on fresh checkouts (origin-selector /
# abi assertions; skill kotoba-cloud-deploy: gate = zero NEW failures vs a
# baseline, not a green run) so an && chain stalls there forever. The stages
# below are the ones that gate DATA deploys. ---
N=$(gh api "repos/kotoba-lang/kyber/git/trees/$SHA?recursive=1" --jq '.tree[] | select(.path|endswith(".bpmn.edn")) | .path' 2>/dev/null | grep -c . )
[ "$N" -ge 2 ] || fail "process count at $SHA"
{ npm run build && npm run audit:uiux && npm run test:worker && npx wrangler deploy --env=""; } > "$LOG" 2>&1 \
  || { tail -5 "$LOG"; fail "deploy chain (log $LOG)"; }
VER=$(grep -oE 'Version ID:\s+[0-9a-f-]{36}' "$LOG" | tail -1 | awk '{print $3}')
for i in 1 2 3 4 5 6; do
  sleep 10
  LIVE=$(curl -sf "https://kyber.kotoba.cloud/org-data/index.json?_=$i" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{const j=JSON.parse(d);console.log(j.source.rev, j.processes.length)})' 2>/dev/null) || true
  [ "$LIVE" = "$SHA $N" ] && break
done
[ "$LIVE" = "$SHA $N" ] || fail "live verify (want '$SHA $N', got '$LIVE')"
echo "orgbrain-publish: kyber@${SHA:0:12} live on kyber.kotoba.cloud — ${N} processes, version ${VER:-?}"
