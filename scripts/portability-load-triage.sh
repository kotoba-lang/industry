#!/bin/bash
# portability-load-triage.sh -- can this repo's .clj source load under nbb?
#
# ## Why this is a script and not a grep
#
# The obvious triage is "does the file mention java.* / Long/ / .getBytes".
# Measured 2026-08-20 across 219 jvm-source repos, that heuristic is close to
# worthless as a predictor:
#
#   grep said "no interop"  25 repos ->  14 actually portable   (56%)
#   grep said "has interop" 175 repos ->  0 actually portable   (0.6%, and the
#                                         one hit was a false positive)
#
# Everything it missed is invisible to a name-based pattern: a bare
# `(catch Exception _)`, `byte-array`, `bigint`, `unchecked-byte`,
# `clojure.lang.PersistentQueue/EMPTY`, a JVM-only LIBRARY (datalevin,
# cheshire, babashka.http-client), Kotoba host-import symbols, and files with
# no ns form at all. The only classifier that answers is: rename it and try to
# load it.
#
# ## Three outcomes, kept apart on purpose
#
#   LOADS      the namespace loaded under nbb with no JVM
#   BLOCKED    it did not, and the reason is in the code
#   UNKNOWN    the run could not answer -- npm deps absent, no src, a portable
#              sibling already provides the namespace
#
# UNKNOWN is not BLOCKED and neither is clean. Two of this script's own bugs
# were exactly that confusion, both caught by calibrating against known
# answers rather than by reading it:
#
#   1. A missing npm module was reported as BLOCKED -- an environment fact
#      recorded as a verdict about the code.
#   2. Fixing that, `node_modules` was used as the marker, which appears in
#      EVERY nbb stack trace (the interpreter lives there), so every failure
#      became UNKNOWN.
#
# ## Loading is not running
#
# A LOADS result is necessary, not sufficient. cljs compiles `(.foo x)` fine
# and fails only on execution, so a JVM method call inside an unexercised
# branch survives this check. Two real bugs found this way the same day, both
# in files that loaded cleanly:
#
#   (bit-shift-left 1 32)  is 2^32 on the JVM and 1 in cljs -- JS shift counts
#                          are mod 32. Every nonzero field was rejected.
#   (int c) over a string  is a code point on the JVM and NaN in cljs, where
#                          iterating a string yields single-character strings.
#
# So: use this to find candidates, then write a test that EXERCISES them.
#
# usage: scripts/portability-load-triage.sh orgs/<org>/<repo>

ROOT=/Users/junkawasaki/github/com-junkawasaki
rel="$1"; d="$ROOT/$rel"
[ -d "$d/src" ] || { echo -e "UNKNOWN\tno-src\t$rel"; exit 0; }
files=$(find "$d/src" -name '*.clj' 2>/dev/null)
[ -n "$files" ] || { echo -e "UNKNOWN\tno-clj\t$rel"; exit 0; }

tmp=$(mktemp -d "/private/tmp/claude-501/lt-XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cp -R "$d/src" "$tmp/src" 2>/dev/null || { echo -e "UNKNOWN\tcopy-failed\t$rel"; exit 0; }

nss=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  relf="${f#$d/src/}"
  cp "$f" "$tmp/src/${relf%.clj}.cljc" 2>/dev/null
  rm -f "$tmp/src/$relf"
  ns=$(echo "${relf%.clj}" | tr '/' '.' | tr '_' '-')
  nss="$nss $ns"
done <<< "$files"

cp=""
if [ -f "$d/deps.edn" ]; then
  cp=$(cd "$d" && timeout 120 clojure -Spath -M:test 2>/dev/null || timeout 120 clojure -Spath 2>/dev/null)
fi

# npm deps, when the repo has them. Without this the load stops at the first
# missing module and the answer is UNKNOWN -- honest, but a shrug. Reuse the
# repo's own node_modules if it already has one; otherwise install into the
# temp copy so nothing in the real checkout is touched.
if [ -f "$d/package.json" ]; then
  cp "$d/package.json" "$tmp/" 2>/dev/null
  [ -f "$d/package-lock.json" ] && cp "$d/package-lock.json" "$tmp/" 2>/dev/null
  if [ -d "$d/node_modules" ]; then
    ln -s "$d/node_modules" "$tmp/node_modules" 2>/dev/null
  else
    (cd "$tmp" && timeout 300 npm install --silent --no-audit --no-fund >/dev/null 2>&1)
  fi
fi

reqs=""
for ns in $nss; do reqs="$reqs (quote $ns)"; done
out=$(cd "$tmp" && timeout 90 nbb --classpath "src:$cp" -e "(require$reqs) (println \"LOADED-OK\")" 2>&1)
if echo "$out" | grep -q "LOADED-OK"; then
  echo -e "LOADS\t-\t$rel"
elif echo "$out" | grep -qE "Cannot find module|ERR_MODULE_NOT_FOUND"; then
  # NOTE: do NOT match bare `node_modules` here. Every nbb stack trace contains
  # it -- the interpreter itself lives under /opt/homebrew/lib/node_modules --
  # so that pattern classified EVERY failure as UNKNOWN. Measured 2026-08-20 on
  # `dot`, which has no package.json at all and whose real blocker is
  # `clojure.lang.PersistentQueue/EMPTY`.
  # A missing npm package is a fact about THIS MACHINE, not about the code.
  # Measured 2026-08-20: kotobase-storage-pack reported
  # `Cannot find module '@noble/hashes/sha2.js'` here, while an agent that ran
  # `npm install` first got the real blocker -- a bare `(catch Exception _)`.
  # Reporting the first as BLOCKED would have recorded an environment gap as a
  # portability verdict, which is the confusion this whole exercise is about.
  why=$(echo "$out" | grep -oE "Cannot find module '[^']*'" | head -1)
  echo -e "UNKNOWN\t${why:-npm-deps-missing (run npm install first)}\t$rel"
else
  why=$(echo "$out" | grep -oE "(Could not find namespace: [a-zA-Z0-9._-]+|Unable to resolve symbol: [^ ,]+|No such file[^\"]*)" | head -1)
  [ -z "$why" ] && why=$(echo "$out" | grep -m1 -oE "Error: .{0,60}")
  [ -z "$why" ] && why="unparsed-failure"
  echo -e "BLOCKED\t$why\t$rel"
fi
