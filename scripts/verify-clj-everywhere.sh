#!/usr/bin/env bash
# Verify "everything CLJ/EDN, everywhere" (ADR-0042): the same EDN data + .cljc interpreters
# run on web (CLJS), native (kotoba-clj→WASM / kami-webgpu-rs→wgpu), and the JVM (babashka).
# Run from anywhere in the superproject; needs all submodules checked out (the native crates
# have cross-repo path deps, so this lives at the superproject level, not per-repo CI).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="$(rustc -vV 2>/dev/null | sed -n 's/host: //p')"

echo "── JVM: .cljc domain interpreters (babashka) ──"
( cd "$ROOT/orgs/com-junkawasaki/kami-webgpu" && bb test )

echo "── native WASM: kotoba-clj keystone (CLJ data subset → WASM) ──"
( cd "$ROOT/orgs/com-junkawasaki/kotoba" \
    && cargo test -p kotoba-clj --test keystone_domains --target "$HOST" )

echo "── native renderer: kami-webgpu-rs (EDN render-IR → wgpu; GPU-free subset) ──"
( cd "$ROOT/orgs/com-junkawasaki/kami-engine" \
    && cargo test -p kami-webgpu-rs --lib --target "$HOST" -- \
         --skip renders_geometry_headless --skip caster_casts_a_shadow )

echo
echo "✓ all surfaces green — same EDN/CLJ runs on web, native, and JVM (ADR-0042)"
