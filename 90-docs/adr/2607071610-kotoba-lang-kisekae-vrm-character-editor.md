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
checks, `nbb kisekae`):

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

- Repo scaffolded/pushed/registered: `spec`/`edit`/`build`/`store` + 33-check `nbb`
  gate, clj-kondo clean, README documenting all invariants and the not-yet-built list.
- Follow-ups, in rough order: full `compose`→`export-glb` integration test against a
  real fixture `.vrm` (synthetic-doc unit tests cover selection/transforms; buffer merge
  rides the engine's own 116-assertion suite until then); kotobase-backed `SpecStore`;
  the editor app in babiniku; expression/texture/spring ops (engine supports all three —
  specs/ops are additive).
- `net-babiniku` gains its first kisekae dependency at ADR-2607071600 M6.

### Addendum (2026-07-07): two real `org-vrmc-vrm/vrm.compose` bugs found building the babiniku part-swap UI, one fixed, one still blocking

Building net-babiniku's actual cross-avatar part-swap flow (M6 slice 2 — swap a
donor's hair/face/outfit/accessory onto a different base avatar, real composition
against real license-clear VRMs, not a reused-base shortcut) surfaced two genuine
engine bugs in `vrm.compose`, neither exercised by the existing test suite because
every prior compose test/usage composed parts from the SAME source document (base
== donor doc), where these bugs are invisible by construction:

1. **Fixed** (`kotoba-lang/org-vrmc-vrm#1`, pin advanced): `compose`'s skin rebuild
   grew a skin's `:joints` list with every new joint a donor part contributed, but
   kept reusing the base skin's own (shorter) `inverseBindMatrices` accessor
   unchanged — real avatars then threw `"No item N in vector of length N"` loading
   the composed GLB (babiniku's joint-palette builder walking past the accessor's
   end). Fixed by building a genuinely new IBM accessor spanning the full grown
   joint list when growth occurs; regression-tested on a synthetic fixture that
   fails with the exact same exception shape pre-fix.
2. **Not yet fixed, blocks cross-avatar part-swap**: with (1) fixed, composing
   Seed-san (base) + a donor's hair no longer crashes, but the RENDERED MESH IS
   VISIBLY DISTORTED — `compose`'s mesh/attribute remap (`remap-attr-map`) only
   redirects which accessor a `:JOINTS_0` attribute POINTS TO (via
   `accessor-remap`); it never remaps the per-vertex joint-index VALUES stored in
   that accessor's data. Those raw ints are copied byte-for-byte from the donor
   document, so they still mean "position in the DONOR's own original skin joint
   array" — but after compose, every mesh is bound to the ONE unified skin, whose
   joint array order is base's joints followed by newly-appended donor joints, a
   different order/indexing entirely. A donor vertex's joint index is therefore
   read against the wrong palette entry (a different bone) whenever its unified
   position differs from its original local position — which is the common case,
   not the exception, for two independently-authored avatars. Real fix requires
   reordering `compose`'s phases (joint-set/node-remap must be fully resolved
   BEFORE mesh merge, not after, as today) and a component-type-aware
   decode/remap/re-encode of every donor mesh's `JOINTS_0` (and, by the same
   argument, any per-vertex data indexed by a remapped table) — a materially
   larger, higher-risk change to a 450-line, well-tested core function, correctly
   judged out of scope to attempt blind in the cycle that found it.
- **Consequence for net-babiniku**: the cross-avatar part-swap UI built this cycle
  was NOT shipped (reverted, no PR) rather than ship a visibly broken/distorted
  character — consistent with this very ADR's fail-loudly invariant ("no silent
  part substitution"). `net-babiniku`'s character creator remains at M6 slice 1
  (base avatar reused as-is; no cross-document compose) until the joint-index
  remap fix lands. That fix is the concrete, precisely-diagnosed prerequisite for
  M6 slice 2 — not a vague "make part-swap work" task.

### Addendum 2 (2026-07-07, later cycle): item 2 above is now fixed — a third issue surfaced composing the real pair

`kotoba-lang/org-vrmc-vrm#2` (pin advanced) fixes the JOINTS_0 per-vertex remap bug
(addendum 1, item 2): joint-set computation moved earlier in `compose` (right after
node-remap, before mesh merge) so mesh merge can build `joint-index-of` and rewrite
every skinned mesh's `JOINTS_0` data with real remapped values, not raw donor-local
copies. Two regression tests with unambiguous right/wrong answers (a reversed-joint-
order donor; an unmappable-joint-with-zero-weight tolerance case) confirmed the fix
against the pre-fix code's wrong output, not just "doesn't crash." Full suite:
44 tests / 158 assertions.

That fix also had to tolerate a real pattern found in actual content: a `JOINTS_0`
slot referencing a joint with no place in the unified skeleton no longer throws
unconditionally — only when its paired `WEIGHTS_0` is genuinely non-zero (a
zero-weight slot's joint index has no effect on the render; real exporters commonly
leave arbitrary-but-valid indices there from a whole-file shared skin).

**Still not resolved**: composing the ACTUAL real-world pair this ADR keeps testing
against (Seed-san as base + VRM1_Constraint_Twist_Sample's hair as donor) still
throws — now on a DIFFERENT unmappable-joint case (a full, non-zero weight on a node
whose name resolves to a leg-related bone, which shouldn't legitimately be a hair
vertex's binding). Diagnosis in this cycle was inconclusive: an ad-hoc browser probe
script (hand-rolled `cljs.core` interop to inspect the donor's raw glTF structure)
produced results that contradicted each other across two attempts regarding which of
the file's 3 skins the hair mesh's node actually references — meaning the probe
script itself is suspect, not necessarily `compose`. Continuing to add ad-hoc probe
scripts was correctly judged lower-value than stopping and recording this precisely:
the next attempt at this specific real-avatar case should add direct, in-code
diagnostics inside `vrm.compose`/`vrm.part` (a `deftest` against the actual
Seed-san/VRM1_Constraint_Twist_Sample bytes, not a browser script) rather than more
hand-rolled JS interop, so the diagnosis tool itself is held to the same test rigor
as the fixes. `net-babiniku`'s part-swap UI remains unshipped.

### Addendum 3 (2026-07-07, later cycle): definitive diagnosis, using the recommended method — a smaller fix landed, a genuinely bigger design gap confirmed

Followed addendum 2's own recommendation: a direct `clojure -M -e` script against the
actual downloaded `.vrm` bytes (`vrm.parse`/`vrm.part`/`vrm.compose` called directly,
JVM exceptions and `ex-data` inspected in the REPL) rather than another browser
script. This immediately caught that addendum 2's browser probe had been WRONG —
inspecting the wrong skin entirely (the donor file has 3 skins; the probe read skin 0
when the hair mesh actually uses skin 2) and reporting a bogus node/bone name as a
result. The reliable method surfaced two real, distinct findings:

1. **Fixed** (`kotoba-lang/org-vrmc-vrm#3`, pin advanced): the real unmappable-joint
   case in VRM1_Constraint_Twist_Sample carries a weight of 0.00266 (~0.27%) — an
   ordinary blend-smoothing residual, not exactly zero. Addendum 2's `1e-4` tolerance
   was too strict for real content; raised to `1e-2` (1%), with a test locking in the
   exact real-world value.
2. **Confirmed, genuinely bigger, correctly NOT attempted**: composing Seed-san
   specifically still fails — not on a donor issue at all, but because **Seed-san has
   5 separate skins**, one per mesh (`hair`=skin0/23 joints, `hair_tail`=skin1/7,
   `head`=skin2/1, `robo_arm`=skin3/21, `wear`=skin4/80). `compose`'s entire design
   assumes ONE shared skin per document (`base-skin = (first (:skins base-gltf))`);
   a mesh using any OTHER skin has joints `joint-index-of` (built from skin 0 only)
   simply doesn't contain — so even Seed-san's OWN "wear" (body) mesh fails the
   "no place in the unified skeleton" check against its own base document. This is a
   materially different, more fundamental gap than anything found before it: it
   requires either unifying a single document's own multiple skins into one before
   any donor merging, or extending composed output to support multiple skins — a real
   architecture decision, not a bounded patch, and confirmed this time with certainty
   (not "inconclusive" as addendum 2 left it).

**Consequence**: `net-babiniku`'s part-swap UI remains unshipped. The multi-skin
question is the concrete next blocker — precisely diagnosed, not vague — but is
correctly left for a dedicated design decision (likely its own ADR) rather than
improvised under the pressure of "one more fix might finish it," which is exactly how
addendum 2's unreliable probe happened in the first place.

### Addendum 4 (2026-07-08, later cycle): the multi-skin question resolved — Seed-san's own structure fully fixed; one different, precisely isolated blocker remains

Addendum 3 deferred the multi-skin question as "a real architecture decision, not a
bounded patch." Revisiting it with a concrete design (unify a document's own multiple
skins into the SAME joint-set/growth machinery `compose` already built for cross-
document donor merging, rather than treating single-skin and multi-skin documents as
different code paths) showed it was smaller than expected — the JOINTS_0 remap
machinery (addendum 2) was ALREADY skin-index-agnostic; only the joint-set/
inverseBindMatrices *seeding* assumed one skin.

**Fixed** (`kotoba-lang/org-vrmc-vrm#4`, pin advanced): `compose`'s joint-set seeding
now unions the joints of EVERY skin any source document (base or donor) actually has,
not just `(first (:skins doc))`. `ibm-plan` entries gained a `:skin-idx` so the
inverseBindMatrices accessor build reads each joint's real matrix from whichever
specific skin — any document, any index — it actually came from. A new regression test
mirrors the exact real failure shape (a document's SOLE selected mesh using skin index
1, not 0) and is confirmed to fail pre-fix with the identical error signature as the
real Seed-san bug, and pass with the fix. Full suite: 46 tests / 164 assertions.

**Verified against the real file** (direct JVM diagnostic, per addendum 3's own
established method): composing Seed-san as a base **no longer fails on its own
structure at all** — every one of its 5 skins is now handled correctly. Composing the
full real-world pair (Seed-san + VRM1_Constraint_Twist_Sample) still throws, but now
on a *different, single, precisely identified* issue entirely on the donor side:
`J_Sec_Hair1_01`, a genuine hair-sway secondary/physics bone with a real 4.1% skinning
weight, parented under `J_Bip_C_Head` rather than under the hair mesh's own node. This
is invisible to `vrm.part/decompose`'s current node-indices heuristic (walks only the
selected mesh's own node subtree), not a `compose` defect — a real, different, and
narrower question than the one this addendum chain has been chasing: *should a
decomposed part's node-indices also include secondary/spring-bone descendants of
humanoid-bone ancestors, even when they're not descendants of the mesh's own node?*
Left for its own follow-up (touches `vrm.part`, not `vrm.compose`) rather than
conflated with this fix. `net-babiniku`'s part-swap UI remains unshipped until it
resolves, but the blocker is now concrete, singular, and well-understood — not an
open-ended architecture question anymore.

### Addendum 5 (2026-07-08): decompose's node-indices now seeds secondary/physics
bones, closing the `J_Sec_Hair1_01` blocker — the real pair composes end to end

Addendum 4 narrowed the remaining real-world blocker to a single, precise question:
should a decomposed part's `node-indices` also include secondary/spring-bone
descendants of humanoid-bone ancestors, even when they're not descendants of the
mesh's own node? Yes — and the fix belongs in `vrm.part/decompose`, not
`vrm.compose` (decompose is what defines "what nodes does this part touch"; compose
only ever consumes whatever decompose already decided).

**Fixed** (`kotoba-lang/org-vrmc-vrm#5`, pin advanced): `decompose` now seeds a
part's `node-indices` with every node the part's own mesh's `JOINTS_0` vertex data
*genuinely resolves to* (decoded the same way `vrm.compose/remap-joints-accessor!`
does), on top of the existing pure-descendant walk. An earlier draft seeded a whole
skin's *declared* `:joints` palette unconditionally — wrong: a skin is a shared glTF
object, and unconditional seeding pulled in joints no vertex in that particular mesh
ever actually references (caught via 5 real test regressions during development,
diagnosed down to the precise mechanism, then corrected to decode actual per-vertex
values instead).

One existing test's premise needed reconsideration, not just a number bump:
`compose-still-throws-on-genuinely-weighted-unmappable-joint`'s old fixture (a joint
reachable via neither humanoid-mapping nor the old node-indices) is now legitimately
resolved by this fix — correctly so, since the joint IS genuinely referenced by the
mesh's own skinning data. The fixture was redesigned around what remains a genuine,
unrecoverable defect: an out-of-range `JOINTS_0` local-pos with no corresponding
entry in the skin's own `:joints` array at all (malformed accessor data, not
reachability). A new dedicated regression test
(`decompose-seeds-secondary-bone-not-a-structural-descendant`) reproduces the exact
real-world topology (a secondary bone parented under Head, sibling to the mesh's own
node, with a real ~4% weight matching `J_Sec_Hair1_01`'s actual ~4.1%) and is
confirmed to fail pre-fix with the identical error signature as the real bug
(`ExceptionInfo: ... no place in the unified skeleton`, weight `0.04`). Full suite:
47 tests / 169 assertions, 0 failures.

**Verified against the real files** (re-downloaded from `vrm-c/vrm-specification`,
direct JVM diagnostic): composing the **full real Seed-san** (as base, with all 4 of
its own decomposed parts — hair/face/other/outfit) plus **VRM1_Constraint_Twist_
Sample's real hair mesh** as donor now **succeeds end to end** — compose, GLB export,
and reparse all complete without error (1 unified skin, 363 joints, 6 meshes, 424
nodes). This is the exact real-world avatar pair this addendum chain set out to
support, composing cleanly for the first time. `net-babiniku`'s M6 slice-2 part-swap
UI (built then deliberately unshipped pending this engine work, per addendum 2) can
now be revisited on the strength of an engine that has been proven against real,
unmodified Consortium content rather than synthetic fixtures alone.

### Addendum 6 (2026-07-08): M6 slice 2 shipped — net-babiniku's part-swap UI wires
the already-built kisekae library end to end, verified live in production

Addendum 5 closed the last engine blocker; this addendum ships the consumer UI it
unblocked. `kisekae.build`/`kisekae.edit`/`kisekae.spec` already had a complete,
tested API (`effective-sources`, `build-document`, `export-bytes`, `:op/add-part`) —
what was missing was net-babiniku actually calling it from the "create your
character" form (`jk-luxury/net-babiniku#38`).

**Design**: compose happens on demand, not at character-creation time. The
character record stores only the small `kisekae.spec` (base + per-kind part
overrides); `babiniku.web` recomposes fresh from that spec the first time the
character is put on stage (fetch base + donor VRM bytes, `vrm.part/decompose` +
`vrm.compose/compose`, feed the resulting `VrmDocument` straight into the existing
mesh-upload path — no export/reparse round-trip), cached by character-id. A
character with no part overrides is unaffected — same plain fetch-by-URL path as
before, zero added engine work for the common case. This matches kisekae.spec's own
docstring design ("a character is not a `.vrm` file in a bucket") rather than
eagerly building and storing a blob URL, which would not survive a page reload
(`URL.createObjectURL` blobs die with the browsing context) and would blow past
localStorage's quota for multi-megabyte avatar files.

**Verified live, twice** (not a mock, not a local dev server — this session's
browser-automation tooling runs in a different network namespace than the sandbox
that builds the code, so verification used a real Cloudflare Pages preview deploy,
then production itself after merge): created a character with Aoi (Seed-san) as
base and Yui's (VRM1_Constraint_Twist_Sample) hair as an override; selecting it on
stage showed "building your character's avatar…" (confirming the compose branch,
not the plain-fetch branch, was taken) then "▶ live VRM avatar"; the composed
avatar rendered as a full, correctly-posed, textured humanoid with hair visibly
distinct from Aoi's own short black hair (Yui's long brown hair swapped in
correctly) — confirmed by direct side-by-side comparison against Aoi's own
unswapped avatar. No console errors. The plain (no-override) fast path was
re-verified unaffected.

**A real mid-flight defect, caught and fixed before landing**: the swap-parts
picker's `.selected` CSS class had no matching style rule (only
`.base-picker button.selected` existed) — every row LOOKED unselected even when the
underlying re-frame state was correct, caught by screenshot inspection during
verification, not by code review. Fixed by extending the existing rule to cover
`.part-swap-row button.selected` too, rebuilt, redeployed, reverified.

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