#!/bin/zsh
# Build -> deploy -> verify one actor at a time, so partial progress is live
# progress rather than a half-finished batch. If this is killed mid-run,
# everything before the cut is already deployed and answering.
#
# Builds are serialized by the repo's resource-guard (mandatory, and other
# sessions compete for the same lock), so this waits rather than forcing.

WS=$1; shift
ROOT=/Users/junkawasaki/github/com-junkawasaki
DISPATCH=https://itonami-fleet-dispatch.04-feasts-minded.workers.dev
SEED=$(KAGI_HOME=$HOME/.kagi timeout 60 $ROOT/orgs/kotoba-lang/kagi/bin/kagi get itonami-fleet-kotobase-seed)
[ -z "$SEED" ] && { echo "FATAL: no fleet seed from kagi"; exit 1; }

for cc in "$@"; do
  d=$WS/cloud-itonami-iso3166-$cc
  [ -d "$d" ] || { echo "$cc SKIP no worktree"; continue; }
  cd $d

  if [ ! -f dist/worker.js ]; then
    built=0
    for i in $(seq 1 40); do
      if node $ROOT/scripts/resource-guard.mjs run build -- \
           clojure -M:cljs -m shadow.cljs.devtools.cli release worker > /tmp/ship-$cc.log 2>&1; then
        built=1; break
      fi
      grep -q "already running" /tmp/ship-$cc.log || break
      sleep 12
    done
    [ "$built" = "1" ] || { echo "$cc BUILD_FAIL"; continue; }
  fi

  printf 'KOTOBASE_SECRET_KEY=%s\n' "$SEED" > .dev.vars; chmod 600 .dev.vars
  up=$(timeout 300 wrangler deploy --dispatch-namespace ai-gftd-repository-dispatch \
         --secrets-file .dev.vars 2>&1 | grep -c "Uploaded")
  shred -u .dev.vars 2>/dev/null || rm -f .dev.vars
  [ "$up" = "0" ] && { echo "$cc DEPLOY_FAIL"; continue; }

  code=$(curl -s -o /tmp/ship-$cc.json -w '%{http_code}' --max-time 35 \
           "$DISPATCH/cloud-itonami-iso3166-$cc/health")
  okv=$(python3 -c "import json;print(json.load(open('/tmp/ship-$cc.json')).get('ok'))" 2>/dev/null)
  if [ "$code" = "200" ] && [ "$okv" = "True" ]; then echo "$cc LIVE"; else echo "$cc UNHEALTHY $code/$okv"; fi
done
echo "--- pipeline finished ---"
