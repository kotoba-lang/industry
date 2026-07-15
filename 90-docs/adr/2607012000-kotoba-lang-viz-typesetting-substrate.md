# ADR-2607012000: `kotoba-lang/{tex,katex,force3d,d3}` — visualization/typesetting EDN-first substrate

Status: Accepted
Date: 2026-07-01

## Context

An owner survey asked whether `d3`, `force3d`, `katex`, `tex` were already lib-designed
as `.cljc`/kotoba substrate under `kotoba-lang`. They were not: the only adjacent
prior art was `orgs/kotoba-lang/kami-engine/kami-graph`, a **Rust** crate providing
force-directed graph layout inside the WGPU game engine, and `main.tex` files that
are LaTeX paper *sources* (build artifacts), not a library. No math-rendering or
chart/scale/shape library existed at all.

The owner decided to build native, from-scratch implementations (not JS/npm
interop wrappers) of all four, following the same EDN-first pattern already
established by `kotoba-lang/svg`, `kotoba-lang/html`, `kotoba-lang/css`, and the
foundational stdlib libs (`coll`, `json`, `spec`, ...): plain nouns (no `-clj`
suffix), zero third-party runtime deps, single-or-few well-tested `.cljc`
namespaces, registered as new `kotoba-lang` repos via `manifest/repos.edn`
`:extra-projects`.

## Decision

Four new repos, each scaffolded and pushed independently (parallel subagents,
one repo each, no shared-file conflicts), then registered together in one
manifest commit:

| Repo | Scope (v1) | Explicitly out of scope (v1) |
| --- | --- | --- |
| `kotoba-lang/tex` | EDN→LaTeX-source *document generator* (Hiccup-like `[:command {opts} & args]` / `[:env "name" {opts} & body]`, `render`, `escape`, `document`/`section`/`itemize`/`enumerate`/`frac`/`sqrt`/`math`/`tabular` helpers) | A TeX engine/compiler (no pdflatex invocation, no page layout, no font metrics) |
| `kotoba-lang/katex` | Recursive-descent parser from a TeX-math-like string to a pure EDN math AST (`\frac`, `^`/`_`, `\sqrt`/`\sqrt[n]{}`, Greek letters, common operators/relations, `{}` grouping), plus an AST→MathML EDN/XML renderer | Full glyph-level font-metric layout (what KaTeX.js actually does); matrices, `\left`/`\right` auto-sizing, accents, text mode |
| `kotoba-lang/force3d` | Pure-functional 3D force-directed graph simulation on EDN data (`{:nodes [...] :links [...]}`, composable forces `charge`/`link`/`center`/`collision`, `simulation`/`tick`/`simulate` integrator with alpha cooling) — the portable analogue of `d3-force-3d`, independent of the Rust `kami-graph` crate | Barnes-Hut/octree optimization (documented as a future improvement, brute-force O(n²) charge force is v1) |
| `kotoba-lang/d3` | `kotoba.lang.d3.scale` (linear/log/ordinal/point/band scales) and `kotoba.lang.d3.shape` (line/area/arc path generators, pie-layout angle computation) — the `d3-scale`+`d3-shape` analogue, output wire-compatible with `kotoba-lang/svg`'s `[:path {:d ...}]` shape | DOM selections/data-binding (`d3-selection`), transitions/animation, force layout (that's `force3d`) |

All four: zero third-party runtime deps, real test suites (all green — see
Verification below), private GitHub repos under the `kotoba-lang` org, plain
nouns per the org naming convention (no `-clj` suffix).

## Verification

| Repo | HEAD SHA | Tests |
| --- | --- | --- |
| `kotoba-lang/tex` | `3491a3a74234b706761d00dfbbdda74e4c447816` | 19 tests / 45 assertions, 0 failures |
| `kotoba-lang/katex` | `63ce122a66c0b7b0379c48d0ba086bfd4b1c561a` | 20 tests / 53 assertions, 0 failures |
| `kotoba-lang/force3d` | `f1a76c2e227230b2a384c15a06d8990d5ff65ed9` | 10 tests / 17 assertions, 0 failures (one real charge-force sign-convention bug found and fixed during scaffolding) |
| `kotoba-lang/d3` | `eed5dd8cc1b5cd8009c3559e776872564c976d30` | 17 tests / 52 assertions, 0 failures |

Manifest registration: `manifest/repos.edn` `:extra-projects` gained the four
paths (`orgs/kotoba-lang/{tex,katex,force3d,d3}`), `manifest/west.yml` was
regenerated with `nbb scripts/gen-west-manifest.cljs` in a clean worktree checked
out from `origin/main` tip (avoiding the local superproject checkout's
unrelated in-progress `kami-provider-catalog` WIP on `repos.edn`), with the
four new repos' working trees copied in so `working-head` could resolve their
pins directly. A before/after name→revision diff confirmed the regeneration
touched **only** the four new entries — no other project's pin moved or was
removed (the large raw line-diff was pure alphabetical-resort churn from
inserting four entries into a sorted list, not data drift). `--check` passes.

## Consequences

- Chart/graph/math/document generation in any kotoba actor can now go through
  a shared, portable, dependency-free EDN pipeline instead of ad hoc JS
  interop or bespoke per-project code.
- `kotoba-lang/d3`'s path-string output and `kotoba-lang/katex`'s MathML output
  are designed to compose with `kotoba-lang/svg`/`kotoba-lang/html` (Hiccup-like
  shapes) without a hard dependency on them.
- `force3d` gives kotoba a JVM/cljs/SCI/GraalVM-portable force-directed layout
  that does not require the Rust `kami-engine` toolchain, at the cost of not
  sharing an implementation with `kami-graph` (acceptable: different runtime
  targets, different consumers).
- v1 scope is intentionally narrow (see tables above) — broadening (Barnes-Hut,
  full TeX math grammar, curved-line interpolation, axis rendering) is future
  work, not blocking initial registration.
- The pre-existing uncommitted `kami-provider-catalog` WIP on the primary
  working directory's `manifest/repos.edn` was left untouched (preserved via
  stash/restore, never discarded) — this ADR's registration was landed via an
  independent clean worktree specifically to avoid bundling unrelated WIP into
  this commit.

## Alternatives

- **JS/npm interop wrappers around real d3.js/KaTeX.js**: rejected by the
  owner in favor of native implementations, consistent with the org's
  EDN-first, zero-third-party-runtime-dep convention for all sibling
  substrate libs (`svg`, `html`, `css`, `did`, ...).
- **Bridging to the existing Rust `kami-graph` crate via WASM for `force3d`**:
  rejected — adds a WASM bridge dependency and couples a portable JVM/cljs
  library to the game-engine build toolchain for no clear benefit over a
  straightforward native reimplementation of the (well-known, small) algorithm.
- **Full feature-parity reimplementations (complete TeX math engine, full
  d3.js module set, LaTeX compiler)**: rejected as v1 scope — these are each
  independently large undertakings; a focused, well-tested core surface
  (documented explicitly per-repo) ships faster and matches how other
  `kotoba-lang` substrate libs (`coll`, `svg`) were scoped.
