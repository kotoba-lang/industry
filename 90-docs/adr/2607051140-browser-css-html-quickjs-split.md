# ADR-2607051140: kotoba-lang/browser split — cssom, htmldom, quickjs

**Status**: accepted (implemented)
**Date**: 2026-07-04 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

An investigation into "how usable is `kotoba-lang/browser` as an actual
browser" (prompted by the owner) found the superproject's manifest pin was
stuck on a README-only scaffold commit; the real implementation lived on
upstream `main` (`2b55834`, "build kotoba-only browser maturity baseline")
and had never been reflected in `manifest/west.yml`. After advancing that
pin, `clojure -M:test` in the freshly-synced repo failed to even **compile**:
`browser.dom-bridge` called `kotoba.wasm.dom/insert-before` and
`remove-child` (from the sibling `kotoba-lang/wasm-ui` repo), neither of
which existed there. A first rough grep suggested ~13 missing functions;
per-namespace isolated-JVM compilation (each `require` run in its own fresh
process, since a shared JVM masks the true failure site behind cascading
"namespace not found" errors) showed the real gap was exactly these two.
Both were added to `wasm-ui`'s `kotoba.wasm.dom` following its existing
`append-child`/`remove-children` conventions (PR
`kotoba-lang/wasm-ui#2`).

With the compile blocker gone, all 444 of `browser`'s JVM tests ran for the
first time ever (previously zero could). 75 failures + 1 error surfaced,
concentrated in `core_test.clj` (67) and `session_test.clj` (8): flexbox
`justify-content`/`align-items` positioning math, `position: absolute/
relative` + `z-index` projection, `min/max-width` + border-box sizing,
border draw-ops, and a caret/selection-width NPE. Root cause:
`browser.core` calls `kotoba.wasm.layout/draw-ops` (also in `wasm-ui`),
whose own docstring says outright it is a "reference" projection —
vertical block-stacking with padding/gap only, explicitly not implementing
flex/position/box-model sizing. This is a real, separate, larger body of
work than the compile fix; not addressed by this ADR (see Follow-up).

The owner then asked to give `browser`'s CSS-layout, HTML, and QuickJS code
each its own repo under `kotoba-lang`, "integrated" back via dependency.
Investigation surfaced a naming trap: `kotoba-lang/css` and
`kotoba-lang/html` **already exist** — but as an unrelated pair of
EDN-as-markup Hiccup-style renderers (`{:rules [...]}`/hiccup vectors ->
CSS/HTML text), the opposite direction from what `browser.css`/`browser.html`
do (CSS/HTML text -> parsed cascade/DOM). Reusing those names would have
created exactly the kind of naming-surface collision ADR-2607050900 (the
kotoba-lang org naming audit) flagged as a real, recurring problem class in
this org. New names were chosen instead: `cssom` and `htmldom`.

A further investigation (this time of the four `browser.compat.quickjs-*`
namespaces) found only one — `quickjs-binary` — has zero coupling to
`browser`'s own domain model. The other three
(`quickjs`/`-binding`/`-execution`) require `browser.audit`, `browser.compat`,
`browser.dom-bridge`, `browser.event-loop`, `browser.net`, `browser.origin`,
`browser.profile`, `browser.runtime`, and `browser.storage` — they describe
how a QuickJS engine's capability requests route through *this specific
browser's* session/audit/origin model, not a generic reusable contract.
Moving them out would have required either duplicating that domain model in
the new repo or making a low-level engine-binding library depend back on a
browser (backwards). Only `quickjs-binary` moved.

## Decision

### New repo: `kotoba-lang/cssom`

`cssom.core` (cascade: selector parsing/tokenizing, specificity, cascade
resolution — moved from `browser.css`) and `cssom.layout` (draw-ops
projection — moved from `wasm-ui`'s `kotoba.wasm.layout`, unchanged). Both
live in one repo because cascade and the layout that consumes its computed
styles are one cohesive "CSS engine" concern. `cssom.layout`'s reference
vertical-stack-only implementation is carried over as-is (documented as a
known gap in this repo's own README maturity table, not fixed by this ADR).
`cssom` depends on `wasm-ui` (`kotoba.wasm.dom`, used by `cssom.core`); `wasm-ui`
now depends back on `cssom` (for `cssom.layout`, used by
`kotoba.wasm.host.retained` and two of its own tests) — a circular
`:local/root` reference, verified to resolve fine via `clojure -Spath`
(tools.deps dedupes by canonical path; no infinite recursion).

### New repo: `kotoba-lang/htmldom`

`htmldom.core` (trusted-HTML-subset parser, moved from `browser.html`,
unchanged). Had no dedicated test file in `browser` (only exercised
indirectly via other tests there); 4 new tests were written for the new
repo (`parses-simple-element-tree`, `void-tags-do-not-consume-a-closing-tag`,
`inline-style-is-parsed-into-style-attrs`,
`select-initializes-value-from-selected-option`).

### New repo: `kotoba-lang/quickjs`

`quickjs.binary` only (WASM magic-byte validation, SHA-256 integrity,
JVM/CLJS binary loading — moved from `browser.compat.quickjs-binary`,
unchanged). The other three `browser.compat.quickjs-*` namespaces stay in
`browser`; this repo's own README documents why (Context above), so the
scoping decision travels with the code, not just this document.

### `browser`'s own `css_test.clj` split

Of its 14 original tests, the 6 that exercised `browser.css`'s pure
functions directly (`parse-rules`/`split-selector-list`/`selector-tokens`/
`parse-selector`/`specificity`) moved to `cssom`'s own test suite. The
remaining 8 (`cascade-applies-*`) stay in `browser`, since they test the
full `browser.core/load-html` pipeline (HTML parse -> cascade -> resolved
style attrs), not `cssom.core` in isolation — an integration test belongs
with the thing it integrates, not the library it exercises.

## Consequences

- (+) `browser`'s JVM test suite compiles and runs for the first time on
  record: 438 tests / 2486 assertions (down from 444/2503 by exactly the 6
  tests / 17 assertions that moved to `cssom` — byte-identical failure set
  otherwise, verified by diffing normalized failure lists before/after).
  `cognitect.test-runner -d test-browser-use` also now compiles and runs:
  13 tests / 112 assertions (2 pre-existing scroll-position failures,
  unrelated to this split, now visible for the same reason as the other 75).
- (+) Three new, independently-testable, independently-versioned repos exist
  with their own green test suites: `cssom` (7 tests/19 assertions),
  `htmldom` (4 tests/9 assertions), `quickjs` (3 tests/9 assertions).
- (+) Avoided a real naming collision with `kotoba-lang/css`/`kotoba-lang/html`
  before it shipped, per the discipline ADR-2607050900 asked this org to
  practice — each new repo's README states the distinction directly ("not
  to be confused with...").
- (−) `quickjs`'s scope is narrower than "all of browser's QuickJS code" —
  a deliberate, documented tradeoff (Context above), not an oversight.
- (−) The two dom-bridge-blocking functions and the cssom/htmldom/quickjs
  split are now fixed/organized, but the underlying flexbox/position/
  box-model layout gap (75 of the 76 total failures) is untouched — this
  ADR is a compile-and-reorganize pass, not a layout-engine implementation.
- (±) `wasm-ui` <-> `cssom` is now a circular repo dependency. Verified safe
  today; worth revisiting if it ever causes real friction (e.g. a future
  need to publish one without the other).
- (±) **This document's own number was mis-cited twice during the work**:
  first, every commit message and several READMEs/docstrings across
  `cssom`, `htmldom`, `quickjs`, and `manifest/repos.edn` cited
  "ADR-2607041700" — a number already claimed by an unrelated ADR
  (`2607041700-cloud-itonami-iso3166-country-maturity-promotion-batch12`).
  The follow-up fix picked "2607051100" as the correction — which a
  concurrent session then also claimed in the same window
  (`2607051100-kami-gen-procedural-composed-character-pipeline` and three
  sibling `kami-gen-*` ADRs), forcing a second renumbering to this
  document's actual final number, 2607051140. Forward-looking citations
  (READMEs, docstrings, this superproject's `repos.edn` comment) were
  corrected in the same commits that added this document; historical
  commit messages already pushed/merged were **not** rewritten (no
  force-push, per repo policy) — this paragraph is the correction of
  record, same pattern as ADR-2607050700's own "Documentation debt this
  ADR itself corrects" note. Two collisions on one number in one session is
  itself a signal this org's ADR-numbering convention (session-numbered,
  not a coordinated counter) is prone to concurrent-session collisions —
  worth a follow-up if it keeps happening.

## Follow-up

> **2026-07-04 追記 (same session)**: the layout-engine gap below is now
> closed. `cssom`'s `feat/box-model-flex-layout` (PR #1,
> `713aeae23bfebc242989fec0be6a51ba04676678`) replaced the reference
> vertical-stack `cssom.layout` with a real implementation: box model
> (padding/border/margin, min/max-width, content-box/border-box),
> flexbox (row/column, wrap, justify-content, align-items, gap),
> position:relative/absolute + z-index stacking, multiplicatively
> inherited opacity, background/background-color, borders,
> overflow+scroll clipping, and form-control value/checked/selection/caret
> projection — derived directly from the 76 failing assertions below as
> the behavioral spec. `wasm-ui`'s golden-test fixture was regenerated in
> a companion commit (`fc5ffc456e720118f5a734c9eddee828533c0586`) since it
> snapshot-compares exact draw-op shape. Result: `browser` 438 tests/2486
> assertions, 0 failures/0 errors (was 75/1); `test-browser-use` 13/112, 0
> failures (was 2). Superproject pins advanced in the same session.

- ~~The 75 failures + 1 error in `browser`'s test suite (flexbox layout,
  `position` + `z-index`, min/max-width + border-box sizing, border
  draw-ops, caret/selection width) point at `cssom.layout` needing a real
  box-model/flexbox implementation to replace the current reference
  vertical-stack projection. Not started by this ADR.~~ Done, see above.
- ~~The 2 scroll-position failures newly visible in `test-browser-use`
  (`document-wheel-events-*`) are likely downstream of the same layout gap;
  not investigated further here.~~ Fixed as a byproduct of the above.
- `quickjs.binary` is a contract with no actual JS execution behind it on
  the JVM; real execution requires the CLJS + `quickjs-emscripten-core` +
  `@jitl/quickjs-singlefile-cjs-release-sync` path in `browser.compat.
  quickjs-wasm`, which has no shadow-cljs/npm toolchain checked into
  `browser` today (noted in the earlier browser-maturity investigation,
  unaffected by this split).

## One-line summary

**Split `kotoba-lang/browser`'s CSS cascade+layout, HTML parsing, and the
one decoupled QuickJS namespace into three new repos (`cssom`, `htmldom`,
`quickjs` — new names, chosen to avoid colliding with this org's existing
unrelated `css`/`html` renderers), fixing a two-function compile blocker
along the way that let `browser`'s 444-test suite run for the first time and
expose 75 pre-existing, unrelated layout-engine gaps left for follow-up.**
