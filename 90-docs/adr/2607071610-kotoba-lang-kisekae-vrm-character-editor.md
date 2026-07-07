# ADR-2607071610: kotoba-lang/kisekae — VRM character editor as a domain library (spec-as-artifact, org-vrmc-vrm engine, kotobase persistence)

## Status
Accepted (scaffolded, tested, pushed: `github.com/kotoba-lang/kisekae` @ `adc47d0`, public per kotoba-lang org default ADR-2607021330; registered in `manifest/west.yml` by this ADR's landing commit)

## Context

User direction (2026-07-07): "vrm character editor なども kotoba-lang として、ユーザーが
使いやすいものを再設計して. kotoba wasm, cljc, kotobase datomic" — redesign the VRM
character editor as a kotoba-lang library, user-friendly, on the kotoba-wasm/cljc/
kotobase-datomic stack. The consuming service design is ADR-2607071600 (babiniku.net's
"create your character" flow); this ADR is the library itself.

### The engine already exists — the editor layer doesn't

`kotoba-lang/org-vrmc-vrm` (restored 1:1 from the deleted kami-vrm Rust crate,
ADR-2607010930) already ships the hard parts of a character editor as zero-dep `.cljc`:

- `vrm.part/decompose` — split an avatar into swappable parts, classified
  `#{:body :hair :face :outfit :accessory :other}` with a production-hardened name
  heuristic (its mesh-name-first fix shipped against a real misclassification);
- `vrm.compose/compose` — merge parts from *different documents* onto one unified
  skeleton (`:skeleton-base`), remapping nodes/buffers/materials/springs/expressions;
- `vrm.export/export-glb` — write a VRM 1.0 GLB back out (via `kotoba-lang/glb`);
- plus mtoon materials, expressions, spring-bone physics, humanoid mapping, and its own
  dependency-free JSON and vec/quat/mat math.

What no library owns is everything *around* that engine: a persisted document model for
"what the user chose," pure edit operations with undo/redo, the selection logic turning
choices into `compose` inputs, and a storage contract. Adjacent kotoba-lang blocks that
should compose rather than be reimplemented: `canvaskit` (UIKit-vocabulary viewport
gestures, ADR-2607071130), `editor` (generic walk-EDN-and-surface-controls), `webgpu`
(kami-webgpu preview), and kotobase's `:db-api` map for datomic-shaped persistence.

## Decision

**`kotoba-lang/kisekae`** (着せ替え, "dress-up") — a pure-`.cljc` domain library, one
dependency (`org-vrmc-vrm`), no UI. Four namespaces, all scaffolded and tested (33
checks, `bb kisekae`):

1. **The spec is the artifact** (`kisekae.spec`). A character is a small plain-EDN value
   — base VRM URL, part overrides, material edits, user meta — from which the `.vrm` is
   *deterministically rebuilt on demand*. Not a binary blob of record: diffable,
   versionable, cheap, and exactly what a datomic-shaped store wants. Validation returns
   a problem *list* (an editor UI shows every issue at once), not a bare boolean.
2. **Dress-up semantics, skeleton stays home** (`kisekae.spec`/`kisekae.edit`). Each
   spec part overrides exactly one kind (one hair at a time — adding a second replaces);
   `:body` is *never* overridable — the base's body is the canonical skeleton `compose`
   unifies onto, and skeleton swapping is a rigging problem this editor does not pretend
   to solve. Part vocabulary derives from `vrm.part/part-categories` (minus `:body`,
   `:other`), so a spec can never name a kind the engine's classifier won't produce.
3. **Edits are data; failures are loud** (`kisekae.edit`). Ops are
   `{:op/type ...}` maps applied by one pure function — so undo/redo is a cursor over
   snapshots, a UI only dispatches ops, and an op-log is what a collaborative/persistent
   layer would transact. Unknown ops, unfetched URLs, donors lacking the requested part:
   all `ex-info` throws, never silent no-ops or substitutions — an editor that drops an
   edit or swaps an unchosen part corrupts user work.
4. **Build is pure given fetched docs** (`kisekae.build`). `effective-sources` (base
   parts minus overridden kinds + donor parts, `:skeleton-base` tracked) →
   `vrm.compose/compose` → `apply-material-edits` (glTF `baseColorFactor`) →
   `apply-meta` → `vrm.export/export-glb`. The caller resolves URLs into
   `docs-by-url` (browser fetch + `vrm.parse` in an app; a fixture map in tests); no I/O
   in the library. `apply-meta` stamps the *user's* name/authors/license — whether the
   base/donor licenses permit recomposition is a concern the consuming app surfaces;
   build can't check it and doesn't pretend to.
5. **Persistence in two honest layers** (`kisekae.store`). A `SpecStore` protocol +
   `MemSpecStore` (the `talent.store` MemStore ≡ DatomicStore precedent) — built and
   tested now — plus the *pure datom mapping* (`spec->tx`/`entity->spec`: one entity,
   `:kisekae/id` / `:kisekae/name` / `:kisekae/spec-edn`, document-as-datom per the
   canvas-ledger precedent) that the future kotobase-backed store transacts through the
   `:db-api` map (`{:q :transact! :db :pull :entid}`). The mapping is tested today; the
   kotobase record is follow-up *wiring*, not design.
6. **Runtime priority, stated honestly.** Everything is interop-free `.cljc`: executes
   on cljs today, sits in the kototama-compatible subset *by construction*. No
   `#?(:kototama ...)` anywhere — that reader feature does not exist (CLAUDE.md
   2026-07-06 decision), and numeric cores as future `.kotoba` targets are recorded as
   aspiration, not claimed capability.
7. **UI is the consumer's, and the seams are named.** The editor *app* (canvaskit
   viewport + kami-webgpu live preview + `kotoba-lang/editor` spec controls) is a
   consumer of this library, first materializing in babiniku.net's character-creation
   flow (ADR-2607071600 M6).

## Consequences

- Repo scaffolded/pushed/registered: `spec`/`edit`/`build`/`store` + 33-check `bb`
  gate, clj-kondo clean, README documenting all invariants and the not-yet-built list.
- Follow-ups, in rough order: full `compose`→`export-glb` integration test against a
  real fixture `.vrm` (synthetic-doc unit tests cover selection/transforms; buffer merge
  rides the engine's own 116-assertion suite until then); kotobase-backed `SpecStore`;
  the editor app in babiniku; expression/texture/spring ops (engine supports all three —
  specs/ops are additive).
- `net-babiniku` gains its first kisekae dependency at ADR-2607071600 M6.

## Alternatives Considered

1. **Store exported `.vrm` blobs as the artifact of record.** Rejected — opaque,
   undiffable, storage-heavy, and hostile to the kotobase-datomic direction; the spec
   rebuild is deterministic, so the blob is cache, not truth.
2. **A full mesh-modeling editor (VRoid Studio class).** Rejected — the engine does
   decompose/compose/recolor, not sculpting; pretending otherwise is vaporware. Dress-up
   composition is what the engine actually supports and what babiniku needs.
3. **Build the editor inside net-babiniku.** Rejected — explicit user direction
   ("kotoba-lang として"); also a jk-luxury (private, vendor) repo is the wrong home for
   a reusable public library, and network-isekai is an obvious second consumer.
4. **Name it `vrm-editor`.** Rejected — `editor` is already the generic EDN-editor lib
   (collision-prone), and the Japanese product-name family (murakumo, giemon, kenchi,
   itonami) is the org convention; 着せ替え says exactly what it does.
5. **Wire kotobase persistence now.** Rejected for this slice — the `:db-api` contract
   + tested pure mapping makes the follow-up mechanical; blocking the library on live
   kotobase wiring would couple a pure-domain scaffold to service availability.

## References

- `github.com/kotoba-lang/kisekae` @ `adc47d0` (scaffold: 4 namespaces, 33-check gate)
- `orgs/kotoba-lang/org-vrmc-vrm` (the engine; README's restored-module inventory) +
  `orgs/kotoba-lang/org-khronos-glb` (GLB codec, transitive)
- `orgs/kotoba-lang/canvaskit` (ADR-2607071130), `orgs/kotoba-lang/editor`,
  `orgs/kotoba-lang/webgpu` — the consumer-side UI blocks
- `langchain.kotoba-db/kotoba-api` + `kotobase.component` (the `:db-api` persistence
  contract); `talent.store` (MemStore ≡ DatomicStore precedent);
  `babiniku.governor`'s Store (in-repo precedent of the same shape)
- ADR-2607071600 (first consumer), ADR-2607021330 (kotoba-lang = public visibility)
- CLAUDE.md `.cljc`/`.kotoba` runtime-priority rule (2026-07-06) — the honest-constraint
  framing followed here