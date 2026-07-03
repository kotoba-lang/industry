# ADR-2607031400: itonami games as AT-Protocol actors — per-game Worker-held identity, posting to app-aozora

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

The 8 (growing) `cloud-itonami` job-simulator games under `network-isekai`'s
`public/games/itonami/` (ADR-2607031000) are currently pure client-side play
— no social/discovery trail beyond network-isekai's own feed index. Each
game already models one specific `cloud-itonami-*` business/occupation
blueprint; giving each game its own social identity, rather than one shared
account, keeps that "one blueprint = one actor" posture consistent with the
rest of this ADR family.

**Existing precedent — etzhayyim actors post to app-aozora today.**
`com-etzhayyim-tashikame` and `com-etzhayyim-kouhou` (ADR-2607022200,
ADR-2607022210) are `langgraph-clj` StateGraph actors that already publish
to app-aozora: an independent Governor gates each proposal, every
commit/hold is appended to an immutable ledger, and on commit a `Publisher`
injection point calls app-aozora's `createRecord`. Authentication is
self-sovereign CACAO (SIWE/EIP-4361 signed by an Ed25519 `did:key`) — the
same scheme CLAUDE.md's kotoba-server section already documents ("actor が
自分の鍵で CACAO を自己発行").

**app-aozora is a bespoke PDS + AppView + XRPC server, "no-server-key" by
design.** It implements the standard `com.atproto.*` surface
(`repo.createRecord`/`putRecord`/`sync.subscribeRepos`, …) plus custom
`app.aozora.*`/`com.etzhayyim.*` lexicons. Its write path is explicitly
built for **client-signed commits, verified statelessly server-side**
against a published DID document — app-aozora itself never holds a
publishing secret.

**network-isekai already declares one AT-Proto-shaped lexicon but has never
emitted it.** `network.isekai.asset` (network-isekai's own
`90-docs/adr/0008-asset-hub.md` §5) is a record shape designed to be
"PDS-storable, federatable," but no XRPC call to any AT-Proto server exists
in the repo yet — it's declared, not wired.

**The actual CACAO mint implementation is JVM/babashka-only.**
`kotoba-lang/cacao`'s `cacao/mint` (the function that derives a `did:key`
from an Ed25519 seed and signs a SIWE plaintext) lives in `src/cacao/core.clj`
— it depends on `java.security` and is not `.cljc`. It cannot run unmodified
inside network-isekai's browser ClojureScript/`kototama`-compiled build.
Only *verification* has been ported to CLJS so far
(`cloud-itonami/edge/cacao.cljc`, a stateless Cloudflare-Worker-style edge
verifier) — minting/signing in the browser is not available today, and
porting `cacao/core.clj` to `.cljc` is its own nontrivial project, out of
scope here.

**network-isekai already runs exactly one persistent server-side
component**: `backend/`, a Cloudflare Worker already deployed and already
used for AI-generation jobs (`POST /api/gen/:stage`). This is the only place
per-game code executes outside the browser today.

## Decision

### 1. One AT-Protocol actor identity per game, not one shared account

Each `public/games/itonami/<slug>/` gets its own Ed25519 `did:key` CACAO
identity — the same granularity as the blueprint-per-occupation pattern the
games themselves already follow.

### 2. Keys are minted out-of-band (JVM/bb), not client-side in the browser

A one-time `bb` script (run per game, or batched over
`public/games/itonami/*`) calls `kotoba-lang/cacao`'s `cacao/mint` to derive
each game's `did:key` and CACAO credential. Because `cacao/core.clj` can't
run in CLJS today, minting happens server-side — this is a deliberate,
scoped tradeoff (see Consequences), not an attempt to solve browser-side
signing in this ADR. Each minted identity is stored as a `backend/` Worker
secret, keyed by game slug — the Worker-secret equivalent of the existing
`.{actor}/identity.edn` gitignored-file convention, adapted because the
signer now has to run server-side rather than on a developer's machine.

### 3. `backend/` Worker gains a publish endpoint; the browser never touches a key

A new endpoint (e.g. `POST /api/itonami/publish`) on the existing `backend/`
Worker: looks up the requesting game's slug → its stored CACAO identity →
signs a `com.atproto.repo.createRecord` commit → relays it to app-aozora's
XRPC surface. The in-browser game client calls this endpoint the same way it
already calls `/api/gen/:stage` for AI-generation jobs — the private key
never leaves the Worker.

### 4. New lexicon: `network.isekai.session`

Extends the `network.isekai.asset` precedent (ADR-0008 §5) with a record
shape for in-game *events* rather than assets: one record per meaningful
moment — a round cleared (`:flow :victory :when-picked` reached), a fork
event (already tracked by `isekai.fork`), an urgent-bonus catch. Minimal
fields: game id, blueprint id (`cloud-itonami-*`), event kind, score,
timestamp, and a link back to the game/fork URL.

### 5. Publish is Governor-gated, same containment shape as tashikame/kouhou

The game client *proposes* a publish; a lightweight rule in the Worker
endpoint (not an LLM — simple validity + rate-limit checks: valid event
shape, one publish per round per game, no replay) approves or denies before
anything reaches app-aozora. Every publish/deny is appended to an audit
trail, reusing the fork-ledger conventions network-isekai already has for
fork events rather than inventing a new ledger mechanism.

### 6. Land one game end-to-end first

Wire the Worker endpoint + lexicon + `plumbing-rounds` fully, verify the
round-trip against app-aozora, *then* extend the same wiring to the
remaining 7 (and future) games as a mechanical follow-up — not all 8 in one
shot.

## Consequences

- (+) Each game becomes independently discoverable/attributable on the
  AT-Proto graph via its own `did:key`, consistent with "one blueprint = one
  actor" running through ADR-2607011000 → ADR-2607012000 → ADR-2607031000.
- (+) Reuses proven infra end to end: `kotoba-lang/cacao` for minting
  (already the ecosystem's standard), the existing `backend/` Worker
  (already deployed) as signer, app-aozora's existing no-server-key XRPC
  write path (no new component needed on app-aozora's side), and the
  Governor+ledger containment pattern tashikame/kouhou already established.
- (−) Key custody is centralized in the Worker rather than truly
  self-sovereign per-game browser-held keys — a deliberate near-term
  tradeoff, not the end state. Revisiting this needs a `.cljc` port of
  `cacao/core.clj`'s mint/sign path, which is its own follow-up ADR, not
  bundled here.
- (−) Key-management overhead scales with game count (8 now, up to 111
  possible across the full `cloud-itonami` blueprint family per
  ADR-2607012000). Minting is a deliberate one-time manual `bb`-script gate
  per game, not automatic — fine at pilot scale, worth revisiting before any
  push toward the full 111.
- (−) `network.isekai.asset` (ADR-0008) remains declared-but-unemitted; this
  ADR does not close that gap — it adds a separate, new lexicon scoped to
  session events rather than retrofitting the asset lexicon.

## References

- ADR-2607031000 (cloud-itonami × network-isekai job-simulator games — the
  games this ADR wires up)
- ADR-2607022200 (`com-etzhayyim-tashikame`), ADR-2607022210
  (`com-etzhayyim-kouhou`) — the actor + Governor + ledger + CACAO +
  app-aozora precedent this ADR follows
- network-isekai's `90-docs/adr/0008-asset-hub.md` §5 — the
  `network.isekai.asset` AT-Proto-shaped lexicon precedent
- `kotoba-lang/cacao` (mint/verify), `kotoba-lang/atproto` (portable `.cljc`
  lexicon/XRPC helpers), `cloud-itonami/edge/cacao.cljc` (CLJS
  edge-verifier precedent for a future browser-side mint port)
- `app-aozora` (bespoke PDS + AppView + XRPC, no-server-key write path)
- CLAUDE.md's "kotoba-server（kotobase.net）= actor が自分の鍵で CACAO を
  自己発行" section
- 本 ADR とペアの `.edn`
