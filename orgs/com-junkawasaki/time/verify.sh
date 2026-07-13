#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

lake build

if rg -n '\bsorry\b|^axiom\b' --glob '*.lean' RelationalTime RelationalTime.lean Main.lean; then
  echo "unproved declaration marker found" >&2
  exit 1
fi

output="$(lake exe time)"
printf '%s\n' "$output"
grep -q '^RELATIONAL_TIME_PASS$' <<<"$output"
echo "TIME_VERIFY_PASS"
