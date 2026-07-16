# ADR-2607051500: kotoba-lang/browser split — org-w3-aria

**Status**: accepted (implemented)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

Following up on ADR-2607051140's own precedent (splitting `browser.css`/
`browser.html`/`browser.compat.quickjs-binary` into `cssom`/`htmldom`/
`quickjs`, each moved only when it had zero coupling to `browser`'s own
session/domain model), the owner asked which of `browser`'s remaining
namespaces would be good candidates for the same treatment, and what
`kotoba-lang/{name}` a genuine WAI-ARIA library should use.

Investigation of `browser.accessibility` (571 lines) found it splits
cleanly into two halves:

- `implicit-roles`, `role`, `hidden?`, `accessible-node`, `tree`, and every
  helper they call (`text-content`, `name-for`, `aria-state`,
  `disabled-by-fieldset?`, `select-values`, etc. — lines 1-464) is a pure
  WAI-ARIA accessibility-tree projection: `(document, node-id) -> a11y
  tree`. Its own `ns` form requires only `clojure.string` — it never calls
  into `kotoba.wasm.dom`, `browser.dom-bridge`, or any other kotoba-lang/
  browser-specific namespace, treating `document`/`node` purely as plain
  maps (`get-in`/`get` only). This is the exact same "zero coupling to
  browser's own domain model" bar ADR-2607051140 used to decide
  `quickjs.binary` alone (of four `quickjs.*` namespaces) could move.
- `surface-app-node`, `rect-state`, `surface-window-node`, `surface-tree`,
  `session-tree` (lines 466-571) project `browser`'s own OS-shell/
  window-manager "surface" model (`:surface/app-id`, `:window/text-buffer`,
  `:browser.session/page`, `:browser.session/profile`, ...) into an
  accessibility-tree-shaped structure, built ON TOP of the generic
  `tree`/`accessible-node` functions above. This half is genuinely
  `browser`-specific (mirrors the reasoning ADR-2607051140 gave for why
  `quickjs`/`-binding`/`-execution` stayed in `browser`) and does not move.

A naming question followed: which `kotoba-lang/{name}` fits a genuine
WAI-ARIA library, consuming the org's own established `org-<standards-body>-
<spec>` naming pattern (ADR-2607041500 precedent: `org-khronos-glb`,
`org-khronos-gltf`, `org-materialx`, `org-openusd`, `org-w3-webgpu` — each a
thin, spec-scoped library named after the standards body that maintains the
spec it implements/wraps, chosen specifically to avoid the kind of
generic-name collision ADR-2607050900's naming audit flagged, and the exact
kind ADR-2607051140 itself hit with `css`/`html`). WAI-ARIA is maintained by
the W3C's Web Accessibility Initiative, making `org-w3-aria` the direct
sibling of the existing `org-w3-webgpu` — same org (`w3`), same shape,
verified against `manifest/repos.edn`/`west.yml` to not already be claimed
by an unrelated repo the way `css`/`html` were.

## Decision

### New repo: `kotoba-lang/org-w3-aria`

`aria.core` — `implicit-roles`, `role`/`hidden?`/`accessible-node`/`tree`
and every private helper the public `tree` function calls (moved from
`browser.accessibility`, unchanged behavior). Zero `:deps` in this repo's
`deps.edn` (unlike `cssom`/`htmldom`, which both depend on `dom-gpu` for
`kotoba.wasm.dom`'s own functions to actually construct/query a document) —
`aria.core` never calls into `kotoba.wasm.dom` at all, it only reads an
already-built document as a plain map, so it needs no dependency to do so.

New, independent tests hand-build plain-map documents directly (the same
`{:root ... :nodes {id {:node/type :element ...}}}` shape `kotoba.wasm.dom`
produces, but constructed as literal EDN here rather than via that
library's own constructor functions) rather than reusing `browser`'s
existing `accessibility_test.clj` (which drives the FULL
`browser.core/load-html` pipeline end to end) — mirrors ADR-2607051140's
own `cssom`/`css_test.clj` split precedent exactly: pure-function tests
move to the new repo, pipeline-integration tests stay with the pipeline
they integrate.

### `browser`'s own `accessibility.cljc`

Kept, now much smaller: requires `aria.core`, delegates `tree`/
`accessible-node` to it, and keeps only the browser-specific surface/session
projection (`surface-tree`/`session-tree` and their own private helpers).
`browser`'s existing `accessibility_test.clj` (388 lines, all pipeline-
integration tests through `browser.core/load-html`) stays as-is, unchanged
— every one of those tests still exercises the real, now-delegating
`browser.accessibility/tree`, so they continue to prove the same real
end-to-end behavior; they were never testing `aria.core`'s internals in
isolation to begin with.

## Consequences

- (+) `browser`'s own JVM test suite passes unchanged (388-line
  `accessibility_test.clj` untouched, still exercises the real delegating
  pipeline) — this is a compile-and-reorganize move, not a behavior change.
- (+) A new, independently-testable, independently-versioned, genuinely
  dependency-free repo exists with its own green test suite, following the
  established `org-<standards-body>-<spec>` naming convention this org
  already uses for `org-khronos-glb`/`org-w3-webgpu`.
- (-) `browser`'s own surface/session-level accessibility projection
  (`surface-tree`/`session-tree`) stays exactly where it was — a
  deliberate, documented tradeoff (Context above), not an oversight,
  mirroring `quickjs`'s own narrower-than-"all of X" scope decision in
  ADR-2607051140.

## One-line summary

**Split `kotoba-lang/browser`'s pure WAI-ARIA accessibility-tree projection
(`accessible-node`/`tree` and their helpers) into a new, dependency-free
repo `kotoba-lang/org-w3-aria` (`aria.core`), following this org's
established `org-<standards-body>-<spec>` naming convention; `browser`'s own
OS-surface/window-manager-specific accessibility projection stays in
`browser`, now built on top of the new library instead of duplicating it.**
