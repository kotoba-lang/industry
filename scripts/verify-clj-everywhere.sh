#!/usr/bin/env bash
# Verify the kotoba-lang migration gates: CLJ/CLJC + kotoba sources are the
# active authority, while Rust/Node/CLJS-era tooling is excluded from root gates.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_in() {
  local dir="$1"
  shift
  echo "── ${dir}: $* ──"
  (cd "$ROOT/$dir" && "$@")
}

echo "── migration ledgers / manifests ──"
bb "$ROOT/scripts/kami-webgpu-dsl-runtime-split-audit.bb" --strict --requested-tests
bb "$ROOT/scripts/kotoba-only-runtime-audit.bb" --strict
bb "$ROOT/scripts/kami-provider-split-audit.bb" --strict
bb "$ROOT/scripts/kami-provider-split-sync.bb" --check

echo "── language/source contracts ──"
run_in "orgs/kotoba-lang/kotoba-lang" clojure -M:test
run_in "orgs/kotoba-lang/kotoba-core-contracts" clojure -M:test
run_in "orgs/kotoba-lang/kotoba" clojure -M:test

echo "── core runtime anchors ──"
run_in "orgs/kotoba-lang/webgpu" clojure -M:test
run_in "orgs/kotoba-lang/kami-engine/kami-engine-sdk-clj" clojure -M:test

echo
echo "✓ kotoba-lang migration gates green — CLJ/CLJC + kotoba sources only"
