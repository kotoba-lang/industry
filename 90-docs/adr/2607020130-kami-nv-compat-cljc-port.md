# ADR-2607020130: kami-nv-compat TypeScript → portable CLJC port (pure math/algorithm core; NO vendor seam)

**Status**: proposed (classification complete; wave-by-wave port pending)
**Date**: 2026-07-01
**Refines**: ADR-2607012200 §Step-7 (whose numbers + "NVIDIA SDK seam" assumption pre-dated a direct scan of this repo)

## Context

`kotoba-lang/kami-nv-compat` is the NVIDIA-Omniverse / Isaac-Sim / Isaac-Lab /
OptiX / RTX / Replicator / DriveSim / Nucleus / Alpamayo **API-compat facade**,
backed by KAMI-native canonical engines (e7m-sim, kami-rt, kami-usd,
utsushimi, wadachi-sim, murakumo-render, amenominaka, kotoba-datomic-nucleus).
Per ADR-2606302300 (kotoba-lang layer test) it must be pure `.cljc` (zero vendor
SDK, zero network I/O). ADR-2607012200 listed it as Step-7 ("20 557 LOC, NVIDIA
SDK seam, own ADR, phased") — but those numbers and the "NVIDIA SDK seam"
assumption were **stale**; a direct scan of the repo on 2026-07-01 corrects both
and materially de-risks the port.

## Decision

Port `kami-nv-compat` to pure `.cljc` as a **straightforward TS→CLJC translation
of pure math/algorithm/data code — no injected-capability seam is needed**,
because the repo has no vendor SDK to seam away. Owner directive "丁寧に"
(carefully) applies: robotics/simulation math, ported wave-by-wave with the
existing vitest tests carried over as `.cljc` parity tests.

### Classification findings (the load-bearing correction)

- **71 TS source files, 14 382 LOC** (ADR-2607012200 said 111 files / 20 557
  LOC — both stale; the repo was trimmed before this scan).
- **Runtime deps: `@noble/hashes` ONLY** (`^1.8.0`). No `@nvidia/*`, no
  Omniverse Kit, no WebGPU/wasm runtime, no `three`. It is a compat *facade*
  (models the API surface as pure data/logic), not a binding over NVIDIA SDKs.
- **70 of 71 files have ZERO external imports** (local/relative only) → pure.
- **1 file imports `@noble/hashes`** — `src/kotoba-datomic-nucleus/store.ts`
  (SHA-256) → reuse `kotoba-lang` SHA-256 (`multiformats` / `ed25519` / JDK
  `MessageDigest`, the same platform-not-vendor choice `pqh`/`checkpointer`
  made for `util.clj`).
- **40 vitest test files** exist → port as `.cljc` parity tests (the
  correctness bar for the robotics math).

So, unlike `pqh` (BouncyCastle) or `checkpointer` (`@atproto/repo` MST/CAR),
there is **no vendor SDK to extract behind a `defprotocol`**. The port is pure
TS→CLJC: records/protocols for the data shapes, pure functions for the math
(BVH build/traverse, articulated dynamics, operational-space / differential-IK
controllers, USD/USDA serialization, warp-style parallel-reduction skeletons,
planners), reader conditionals only for platform primitives.

### Wave plan (smallest/purest first; each wave = port + parity tests + pin)

dependency-ordered by subdir (2026-07-01 LOC census):

| wave | subdir | LOC | files | notes |
|---|---|---|---|---|
| 1 | `policies` | 157 | 2 | smallest; warm-up exemplar (proves the TS→CLJC recipe for this repo) |
| 2 | `murakumo-render` | 153 | 2 | |
| 3 | `e7m-sim` | 261 | 1 | |
| 4 | `kotoba-datomic-nucleus` | 326 | 3 | **the one @noble/hashes file** — swap to kotoba-lang sha256 |
| 5 | `amenominaka` | 434 | 4 | |
| 6 | `wadachi-sim` | 537 | 3 | |
| 7 | `kami-usd` | 575 | 3 | USDA serialization |
| 8 | `e7m-shugyo` | 593 | 4 | |
| 9 | `actions` | 595 | 4 | task-space actions |
| 10 | `kami-drive` | 612 | 4 | planner |
| 11 | `assets` | 754 | 5 | |
| 12 | `controllers` | 763 | 3 | operational-space, differential-IK |
| 13 | `utsushimi` | 981 | 6 | writers |
| 14 | `dynamics` | 1033 | 3 | articulated dynamics |
| 15 | `kami-rt` | 1493 | 7 | BVH / ray-trace |
| 16 | `warp` | 3392 | 4 | GPU-compute sim skeleton (incl. 2475-LOC `examples.ts` — port last) |
| — | top-level facades (`omni-*`, `isaac-*`, `optix`, `rtx-renderer`, `drive-sim`, `alpasim`, `alpamayo`, `index`) | — | 13 | thin entry points; port with/after their dependency waves |

### Porting recipe (per wave)

Same landing mechanics as ADR-2607012200: child-repo worktree → `clojure
-M:lint` / `-M:test` green → server-side merge → advance superproject west pin
via GitHub-API single-entry (no `--force`, no rebase). Each wave lands
independently and leaves the repo green; the TS for a ported wave is deleted
incrementally (the repo can be mixed TS+CLJC mid-port, since the layer test is
applied repo-at-completion, not per-wave — the final wave deletes the last TS
+ `package.json` + `tsconfig`).

## Consequences

- Step-7 is **de-risked**: no crypto/PQ-parity risk (à la `pqh`), no native MST
  reimplementation (à la `checkpointer`). The risk is ordinary translation
  fidelity of pure math, bounded by the existing 40 test files.
- The "NVIDIA SDK capability seam" anticipated in ADR-2607012200 §Step-7 is
  **not required** and will not be built.
- Deps will be pure data libs (likely `multiformats` for the one sha256 site +
  any CID use; otherwise zero runtime deps).
- Scope is ~14k LOC across 16 waves + 13 facade files — multi-session,
  wave-per-session, "丁寧に".
