#!/usr/bin/env bash
# Phase 0 — recover 8 cyber-drill panoramas after /tmp wipe.
# Source: v19 R2 deployment (cyber-drill-vendor.04-feasts-minded.workers.dev assets).
# These are the source panoramas that DreamScene360 trains 3DGS scenes from.
set -euo pipefail

OUT_DIR="${HOME}/tanabe-3d/output/equirect"
mkdir -p "${OUT_DIR}"

# v19 panorama URLs (R2-hosted via cyber-drill worker).
# The .splat files at /v19/{scene}.splat were derived from these panoramas.
# Original panorama PNGs may be in a different R2 path — confirm before run.
BASE_URL="https://cyber-drill-vendor.04-feasts-minded.workers.dev"
SCENES=(op-room scada cleanroom chemical-yard server-room exec-room utility-room press-room)

echo "Phase 0 panorama recovery — target: ${OUT_DIR}"
echo ""

# Step 1: probe for panorama hosting path
for path_candidate in /assets/panoramas /panoramas /v19/panoramas /assets/equirect; do
  url="${BASE_URL}${path_candidate}/plant-op-room.png"
  status=$(curl -sI -o /dev/null -w "%{http_code}" "${url}" || echo "000")
  echo "  probe ${url} → ${status}"
  if [ "${status}" = "200" ]; then
    PANORAMA_PATH="${path_candidate}"
    echo "  ✓ found panoramas at ${BASE_URL}${PANORAMA_PATH}/"
    break
  fi
done

if [ -z "${PANORAMA_PATH:-}" ]; then
  echo ""
  echo "ERROR: panorama PNGs not found at any of the candidate paths."
  echo "Manual recovery options:"
  echo "  1) wrangler r2 object get <bucket>/plant-<scene>.png — if you have R2 bucket creds"
  echo "  2) Re-generate from the original SDXL panorama prompts (see ds360-modal-b200-te2.py for the prompt set)"
  echo "  3) Use the .splat files at ${BASE_URL}/v19/<scene>.splat as input to a NeRF→panorama reverse-render (impractical)"
  echo ""
  echo "Recommended: open 1Password 'Cloudflare R2 cyber-drill' to find the bucket name + access keys, then:"
  echo "  rclone copy r2:<bucket>/panoramas/ ${OUT_DIR}/"
  exit 1
fi

echo ""
echo "Downloading 8 panoramas..."
for scene in "${SCENES[@]}"; do
  url="${BASE_URL}${PANORAMA_PATH}/plant-${scene}.png"
  out="${OUT_DIR}/plant-${scene}.png"
  if [ -f "${out}" ] && [ "$(stat -f%z "${out}")" -gt 1000000 ]; then
    echo "  ✓ ${scene} (cached, $(du -h "${out}" | cut -f1))"
    continue
  fi
  echo "  ↓ ${scene}..."
  curl -sf "${url}" -o "${out}" || { echo "  ✗ ${scene} download failed"; rm -f "${out}"; continue; }
  size=$(stat -f%z "${out}")
  echo "    ${size} bytes ($(du -h "${out}" | cut -f1))"
done

echo ""
echo "=== recovery summary ==="
ls -lh "${OUT_DIR}/" | head -20
echo ""
ok_count=$(find "${OUT_DIR}" -name "plant-*.png" -size +1M | wc -l | tr -d ' ')
echo "${ok_count}/8 panoramas recovered (>1MB each)"
[ "${ok_count}" -eq 8 ] && echo "✓ Phase 0 done, proceed to Phase 1a" || echo "✗ recovery incomplete, see manual options above"
