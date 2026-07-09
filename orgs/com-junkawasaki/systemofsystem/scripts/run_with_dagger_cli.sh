#!/usr/bin/env bash
set -euo pipefail

# Wrapper to run the dagger task defined in dagger.hcl
if ! command -v dagger &> /dev/null; then
  echo "dagger CLI not found. Install: https://dagger.io/docs/install" >&2
  exit 1
fi

echo "Running dagger task 'run' defined in dagger.hcl"
dagger do -f dagger.hcl run
