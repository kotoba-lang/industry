#!/usr/bin/env bash
# Relocate one orgs/etzhayyim/root/20-actors/<old>-compat clean-room API-compat
# actor to a standalone orgs/kotoba-lang/<new> repo. See ADR-2607041500 for the
# naming rule (new = "com-" + kebab(strip "-compat" suffix)) and full recipe.
#
# Usage: scripts/migrate-etzhayyim-compat.sh <old-compat-dir> <new-kotoba-lang-repo> <adr-id>
# Example: scripts/migrate-etzhayyim-compat.sh adyen-compat com-adyen 2607041500
#
# Leaves the source directory under etzhayyim/root untouched (copy, not move) —
# deletion of the old copy is a separate follow-up per the ADR.
set -euo pipefail

OLD="${1:?usage: $0 <old-compat-dir> <new-kotoba-lang-repo> <adr-id>}"
NEW="${2:?usage: $0 <old-compat-dir> <new-kotoba-lang-repo> <adr-id>}"
ADR="${3:?usage: $0 <old-compat-dir> <new-kotoba-lang-repo> <adr-id>}"

ROOT="$(git rev-parse --show-toplevel)"
SRC="$ROOT/orgs/etzhayyim/root/20-actors/$OLD"
WORKDIR="${MIGRATE_WORKDIR:-$(mktemp -d)}/$NEW"

[[ -d "$SRC" ]] || { echo "no such source dir: $SRC" >&2; exit 1; }

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"
cp -R "$SRC/." "$WORKDIR/"
cd "$WORKDIR"

TEST_DIR="test"
[[ -d tests ]] && TEST_DIR="tests"

# Replace the old deps.edn (either a non-standard {:dependencies {:x "workspace"}}
# pseudo-format, or a stale {:local/root "../../40-engine/kotoba"} that no longer
# resolves) with a correct, self-contained tools.deps file. The real test runner
# (bb.edn task or run_tests.sh) never actually reads deps.edn — see ADR-2607041500.
cat > deps.edn <<EOF
{:paths ["src" "$TEST_DIR"]
 :deps {org.clojure/clojure {:mvn/version "1.11.1"}}}
EOF

cat >> README.md <<EOF

## Provenance

Relocated $(date -u +%Y-%m-%d) from \`etzhayyim/root/20-actors/$OLD\` to
\`kotoba-lang/$NEW\` per the org-taxonomy library-placement rule (any
library/substrate code belongs in \`kotoba-lang\`, ADR-2606302300), following
the same relocation pattern as \`kami-nv-compat\` (ADR-2607020130). See
ADR-$ADR for the full ~1,027-repo migration plan and naming convention.
EOF

# Best-effort test run before push: find the test namespace via its own (ns ...)
# form (not inferred from the file path — vendor names can contain literal
# underscores, so path->ns inference is unreliable across the catalog).
ns_file="$(find "$TEST_DIR" -name '*_test.cljc' -o -name '*-test.cljc' 2>/dev/null | head -1)"
if [[ -n "$ns_file" ]]; then
  ns="$(grep -m1 -oE '\(ns +[A-Za-z0-9_.-]+' "$ns_file" | awk '{print $2}')"
  if [[ -n "$ns" ]] && command -v bb >/dev/null 2>&1; then
    bb --classpath "src:$TEST_DIR" -e "
      (require 'clojure.test)
      (require (symbol \"$ns\"))
      (let [r (clojure.test/run-tests (symbol \"$ns\"))]
        (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))" \
      || { echo "TEST FAILED for $NEW ($ns) — not pushing" >&2; exit 1; }
  else
    echo "WARNING: could not determine test namespace for $OLD; skipping test run" >&2
  fi
else
  echo "WARNING: no *_test.cljc found for $OLD; skipping test run" >&2
fi

git init -q -b main
git add -A
git commit -q -m "Relocate $OLD to kotoba-lang/$NEW (clean-room API-compat cljc actor)

Per org-taxonomy library-placement rule (ADR-2606302300); see ADR-$ADR
for the full migration plan. deps.edn replaced with a valid self-contained
tools.deps file (old file used a non-standard {:dependencies {:x \"workspace\"}}
pseudo-format or a stale relative :local/root, neither ever consumed by the
real test runner, which invokes bb directly with an explicit classpath).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"

gh repo create "kotoba-lang/$NEW" --public --source=. --remote=origin --push

echo "created: https://github.com/kotoba-lang/$NEW"
echo "worktree left at: $WORKDIR"
