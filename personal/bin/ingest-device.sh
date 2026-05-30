#!/usr/bin/env bash
# Ingest this terminal/device's information into personal/device/.
# Safe-by-default: environment variables are captured by NAME ONLY (values are
# never written) to avoid leaking secrets/tokens into the dataset.
set -u
cd "$(dirname "$0")/.." || exit 1   # -> personal/
OUT=device
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
j() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

mkdir -p "$OUT"

# ---- system / hardware (JSON) ----
{
  echo "{"
  echo "  \"ingested_at\": $(j "$TS"),"
  echo "  \"hostname\": $(j "$(scutil --get LocalHostName 2>/dev/null || hostname)"),"
  echo "  \"os\": {"
  echo "    \"product\": $(j "$(sw_vers -productName 2>/dev/null)"),"
  echo "    \"version\": $(j "$(sw_vers -productVersion 2>/dev/null)"),"
  echo "    \"build\": $(j "$(sw_vers -buildVersion 2>/dev/null)"),"
  echo "    \"kernel\": $(j "$(uname -a)")"
  echo "  },"
  echo "  \"shell\": $(j "${SHELL:-}"),"
  echo "  \"uptime\": $(j "$(uptime | sed 's/^ *//')"),"
  echo "  \"user\": $(j "$(whoami)")"
  echo "}"
} > "$OUT/system.json"

# ---- hardware detail (model, chip, memory, serial) ----
system_profiler SPHardwareDataType 2>/dev/null > "$OUT/hardware.txt"

# ---- installed packages ----
{ brew list --formula --versions 2>/dev/null; } > "$OUT/packages-brew-formula.txt"
{ brew list --cask --versions 2>/dev/null; }    > "$OUT/packages-brew-cask.txt"
{ command -v pip3 >/dev/null && pip3 list --format=freeze 2>/dev/null; } > "$OUT/packages-pip.txt"
{ command -v npm  >/dev/null && npm  ls -g --depth=0 2>/dev/null; }      > "$OUT/packages-npm-global.txt"
{ command -v pnpm >/dev/null && pnpm ls -g --depth=0 2>/dev/null; }      > "$OUT/packages-pnpm-global.txt"
{ command -v cargo >/dev/null && cargo install --list 2>/dev/null; }     > "$OUT/packages-cargo.txt"
{ command -v code >/dev/null && code --list-extensions --show-versions 2>/dev/null; } > "$OUT/vscode-extensions.txt"

# ---- dotfiles inventory (names only) ----
{ ls -la ~ 2>/dev/null | grep -E '^\..*|^[d-].* \.' ; ls -A ~ 2>/dev/null | grep '^\.'; } | sort -u > "$OUT/dotfiles.txt"
{ ls -A ~/.config 2>/dev/null; } > "$OUT/config-dirs.txt"

# ---- environment variable NAMES only (no values) ----
env | sed 's/=.*//' | sort -u > "$OUT/env-var-names.txt"

# ---- disk / volumes ----
df -h 2>/dev/null > "$OUT/disk.txt"

# ---- manifest ----
{
  echo "# device ingest @ $TS"
  echo "files:"
  for f in "$OUT"/*.txt "$OUT"/*.json; do
    [ -f "$f" ] && printf "  - %s (%s bytes)\n" "$(basename "$f")" "$(wc -c <"$f" | tr -d ' ')"
  done
} > "$OUT/MANIFEST.txt"

echo "device ingest complete -> $OUT/"
