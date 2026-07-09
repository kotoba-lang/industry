# ADR-2607092345: extract the aozora messenger as `kotoba-lang/kotobase-messenger`

**Status**: accepted — implemented (Phase A + Phase B complete)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/app-aozora` messenger PDS/AppView/UI code → new `orgs/kotoba-lang/kotobase-messenger`

## Context

The aozora messenger (1:1/group chat, Signal-protocol E2E via `kotoba-lang/org-signal`,
block/unblock, edit/delete, search, typing indicators, read receipts, pagination, group
rename, leave-group, Web Push, offline-retry) was built entirely inside
`gftdcojp/app-aozora` across the messenger feature's full build-out this session. The owner
asked to make this chat functionality reusable as a library on the `kotoba-lang` side,
scoped to both backend and frontend, to be done after the UX-gaps phase completed.

`kotoba-lang/org-signal` (the crypto layer — X3DH, Double Ratchet, Sender Keys) is already
extracted and consumed by app-aozora via a plain relative source-path
(`60-apps/appview/cljs/deps.edn`: `"../../../../../kotoba-lang/org-signal/src"`, that dir's
`shadow-cljs.edn` has `:deps true` and defers entirely to `deps.edn`; `40-engine/cljs/
shadow-cljs.edn` has its own `:source-paths` list directly, same relative-path convention
one level shallower). That is the proven precedent this extraction mirrors exactly: no
namespace renaming, no npm-style packaging, a relative source-path a consumer's shadow-cljs
build picks up. ClojureScript namespaces resolve by their declared `(ns ...)` name
regardless of which physical repo/source-path they live on, so moving files with zero
namespace renaming is low-risk — `aozora.pds.router`'s `(:require [aozora.pds.convo :as
convo])` keeps working unchanged once the file relocates and the local copy is deleted (a
forgotten duplicate would surface immediately as a namespace-redefinition/compile error).

`manifest/repos.edn` registration is a bare path string inside an `:extra-projects` set with
a `;;` comment — no rich metadata schema (confirmed by reading the `org-signal` entry).
`00-contracts/lexicons/*.json` files are pure documentation — grepped the whole repo for any
build/codegen/test consumer, zero hits.

## Decision

### Domain boundary

Not everything under `aozora.pds.*`/`aozora.appview.*`/`yoro_ui.*` is messenger-specific.
The split:

**Moves to `kotoba-lang/kotobase-messenger`** (messenger domain logic):
- PDS: `aozora.pds.convo` (convo/message/reaction/receipt/typing/rename CRUD),
  `aozora.pds.actor` (block/unblock), `aozora.pds.prekeys` (X3DH bundle registration —
  nothing else in app-aozora uses prekeys), `aozora.pds.push` (messenger-triggered fan-out:
  `notify-new-message!`, `notify-sealed-message!`, `post-one!`, `cleanup-dead-subscription!`).
- AppView: `aozora.appview.convo`, `aozora.appview.actor`, `aozora.appview.prekeys`.
- Lexicons: `app/aozora/convo/*.json`, `app/aozora/actor/*.json` (the protocol spec, kept
  alongside the code implementing it).
- Frontend: `yoro_ui.pages.{convo,convo_detail}`, `yoro_ui.state.{convos,convo_search,
  blocks}`, `yoro_ui.interop.{signal,signal_group}`.
- All corresponding test files.

**Stays in app-aozora** (generic platform infrastructure the moved code depends on via
ordinary `:require`, same shape as any other kotoba-lang→consumer dependency):
- `aozora.pds.repo` (generic AT-proto-over-kotobase CRUD, used by every collection type —
  posts/follows/profiles too, not just messenger), `aozora.pds.per-actor` (per-actor DB
  routing), `aozora.pds.webpush` (RFC 8291/8292 crypto, no convo awareness),
  `aozora.pds.pushsub` (generic subscribe/unsubscribe), `aozora.appview.push` (generic
  subscription reads), `aozora.appview.{feed,scan}` (the shared kotobase-scan substrate
  every AppView projection reads from).
- `yoro_ui.interop.push` (browser Notification/SW-registration plumbing, not convo-shaped)
  and `public/sw.js` (a static asset, deployed per-app regardless).
- `app.aozora.push.{subscribe,unsubscribe}` lexicons (generic subscription protocol).

### Phasing

Two independently-verified, sequential PR-pairs — backend and frontend are already separate
shadow-cljs builds today (`40-engine/cljs` vs `60-apps/appview/cljs`), so splitting the
extraction the same way keeps blast radius small and each phase independently testable,
rather than one large atomic move.

- **Phase A (backend)**: scaffold the new repo (`pds/`, `appview/`, `lexicons/`), move the
  files, register in `manifest/repos.edn` + `west.yml`, wire `40-engine/cljs/shadow-cljs.edn`
  source-paths, verify the full `40-engine/cljs` test suite is unchanged (375 tests / 1376
  assertions at the time of writing), both Workers build clean.
- **Phase B (frontend)**: add `ui/` to the same repo, move the frontend files, wire
  `60-apps/appview/cljs/deps.edn`, verify the full frontend test suite is unchanged (195
  tests / 453 assertions at the time of writing), production build clean.

One ADR covers both phases, not two.

### Standalone testability — deliberately deferred

`kotobase-messenger`'s moved code still `:require`s app-aozora's own platform namespaces
(`aozora.pds.repo`, `aozora.pds.per-actor`, `aozora.appview.{push,feed,scan}`,
`aozora.pds.webpush`) that stay behind. A fully standalone test harness for the new repo
would need to replicate app-aozora's own ~15-entry `shadow-cljs.edn` source-path list for
zero real consumers today (app-aozora is the only one). Rather than build that speculatively,
the new repo gets a minimal `deps.edn`/`shadow-cljs.edn` sufficient for `clj-kondo` lint-
ability, and its README documents plainly that automated test *execution* runs via
app-aozora's own suite (the moved test files' `-test$` names are picked up automatically
once the source-path is added, no extra wiring needed on app-aozora's side). Revisit if/when
a second real consumer exists.

## Consequences

- No behavior change — this is a pure relocation. Every `:require` in app-aozora's own
  `aozora.pds.router`/`aozora.appview.router` continues to resolve the same namespace names
  unchanged, now sourced from `kotobase-messenger`'s source-path instead of a local file.
- `app.aozora.convo.*`/`app.aozora.actor.*` NSIDs are unchanged — this extraction relocates
  *implementation*, not the wire protocol. Any other kotoba-lang/gftdcojp app that wants
  messenger functionality gains full data-level interop with app-aozora's existing records
  by adopting the same source-path + the same NSIDs, not a forked/renamed protocol.
- `kotobase-messenger` is not (yet) portable to a codebase outside this monorepo's west-
  managed sibling-checkout convention — it depends on app-aozora's own PDS/AppView platform
  namespaces by name, the same way it already implicitly did before the move. Making it
  portable to an arbitrary external project would require injecting those platform
  dependencies (e.g. a protocol/map instead of a hard `:require`), deliberately out of scope
  here (no concrete second consumer driving that requirement yet).

## References

- `kotoba-lang/org-signal` — the crypto-layer extraction this ADR's source-path mechanism
  mirrors exactly.
- This session's messenger build-out PRs (`gftdcojp/app-aozora` #58–#72) — the code being
  relocated here, unmodified in behavior.
