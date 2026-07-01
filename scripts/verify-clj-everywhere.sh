#!/usr/bin/env bash
# Verify "everything CLJ/EDN, everywhere" (ADR-0042): the same EDN data + .cljc interpreters
# run on web (CLJS), native (kotoba-clj→WASM / kami-webgpu-rs→wgpu), and the JVM (babashka).
# Run from anywhere in the superproject; needs all submodules checked out (the native crates
# have cross-repo path deps, so this lives at the superproject level, not per-repo CI).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="$(rustc -vV 2>/dev/null | sed -n 's/host: //p')"

echo "── JVM: .cljc domain interpreters (babashka) ──"
( cd "$ROOT/orgs/kotoba-lang/kami-webgpu" && bb test )

echo "── native WASM: kotoba-clj keystone (CLJ data subset → WASM) ──"
( cd "$ROOT/orgs/kotoba-lang/kotoba" \
    && cargo test -p kotoba-clj --test keystone_domains --target "$HOST" )

echo "── native renderer: kami-webgpu-rs (EDN render-IR → wgpu; GPU-free subset) ──"
( cd "$ROOT/orgs/kotoba-lang/kami-engine" \
    && cargo test -p kami-webgpu-rs --lib --target "$HOST" -- \
         --skip renders_geometry_headless --skip caster_casts_a_shadow )

# Phase 0.2 (clj-wgsl migration): WGSL authoring parity surface. The CLJC-authored
# shaders (kami.shaders → bb gen-wgsl → fixtures/*.wgsl) must (a) be regenerable from
# CLJC with zero diff against the committed fixture (single-source — no hand-edits to
# generated WGSL), and (b) be token-equivalent to the native include_str!'d shader
# (bb wgsl-parity --strict). Today only lit/shadow are CLJC-authored; the remaining
# kami-render/src/shaders/*.wgsl are hand-authored native (tracked as :wgsl-ownership
# stay-Rust-builtin until Phase 2.3 ports them). See 90-docs/migration/clj-wgsl-ledger.edn.
echo "── WGSL authoring parity: CLJC canonical ↔ native include_str! (clj-wgsl Phase 0.2) ──"
( cd "$ROOT/orgs/kotoba-lang/kami-webgpu" \
    && bb gen-wgsl \
    && git diff --exit-code -- fixtures/lit-shader.wgsl fixtures/shadow-shader.wgsl fixtures/pipeline_specs.rs \
    && bb wgsl-parity --strict )

echo
echo "✓ all surfaces green — same EDN/CLJ runs on web, native, and JVM (ADR-0042)"
