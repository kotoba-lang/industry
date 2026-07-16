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

### 2. Keys are minted out-of-band (JVM/nbb), not client-side in the browser

A one-time `nbb` script (run per game, or batched over
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
  ADR-2607012000). Minting is a deliberate one-time manual `nbb`-script gate
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

## Addendum (2026-07-03, reconciled with ADR-2607032300 / ADR-2607032400)

Two ADRs landed in `app-aozora`/`network-isekai` after this ADR was written,
both directly relevant:

**ADR-2607032300 (aozora self-sovereign 3-layer authority)** replaces the
flat "app-aozora is a no-server-key XRPC surface" picture this ADR's Context
used with a more specific one: writes land in a **per-actor graph**
(`canonical-graph(actor-did, db)`), not a shared operator graph — the old
single-`OPERATOR_SECRET`-signs-everyone's-writes design is explicitly the
antipattern that ADR dismantles. Discovery/feed/timeline is a separate,
derived **AppView** layer (`com.etzhayyim.yoro.*` projection today), never
the authoritative write target itself. This ADR's Context was directionally
right (client-signed, no shared secret held by the app-aozora *server*) but
under-specified *where* a write actually lands — it lands in the writing
actor's own graph, full stop, not "app-aozora" as an undifferentiated whole.

More importantly, ADR-2607032300 reveals `app-aozora` already ships a
**custodial per-actor key derivation mechanism** this ADR didn't know about:
`aozora.pds.actorkey` — HKDF-SHA256 over `operator master + actor-did →
per-actor Ed25519 key`, landing writes in `kotobase/db/<actor-did>/repo`,
gated behind the (currently off) `PER_ACTOR_DB` env flag, already
implemented and node-testable. ADR-2607032300 explicitly names the
follow-up migration **"Level B self-sovereign"** (the actor's own
`key-backup` credential, ADR-2607022330, signs client-side instead of the
operator-held master) — it does not literally name the *current* custodial
state; this addendum labels it **"Level A custodial"** only for symmetry
with the source ADR's own "Level B," not as a direct quote.

**Revises Decision §2**: rather than this ADR's original "run
`kotoba-lang/cacao`'s `cacao/mint` out-of-band per game and store each
result as its own `backend/` Worker secret" (a bespoke minting step,
growing one secret per game, up to 111), the `backend/` Worker should derive
each game's key **the same way `aozora.pds.actorkey` already does**: HKDF
from a single operator-held master seed + the game's `did`/slug as the
derivation input. One master secret, not N per-game secrets; no separate
out-of-band `nbb` minting step per game; and it's the exact mechanism
`app-aozora` itself already uses for this exact custodial stage, rather than
a parallel bespoke scheme this ADR would otherwise be inventing. This is
still squarely the custodial stage this addendum calls "Level A" above — it
does not change this ADR's original Consequences trade-off (key custody stays
Worker-held, not self-sovereign; migrating to Level B still needs the
not-yet-built `.cljc` port of CACAO client-side signing) — it only
simplifies *how* the custodial key is produced, and aligns it with the
platform's own roadmap instead of a one-off design.

**Revises Decision §3**: the publish endpoint signs a commit into the
*publishing game's own per-actor graph* (`canonical-graph(<game-did>,
<db>)`), never a shared graph. Cross-game discovery (an "itonami games"
feed/timeline across all 8+ games) is an AppView-layer concern — a future
`app.aozora.*`/`com.etzhayyim.yoro.*`-style projection reading the games'
per-actor commit streams — not something this ADR's write path should ever
touch directly. This keeps itonami games' publish path from accidentally
reproducing the exact single-shared-graph antipattern ADR-2607032300 spent
an incident dismantling.

**ADR-2607032400 (network-isekai consolidation)** is orthogonal to this
ADR's actual decision — it confirms `network-isekai` (not `isekai-network`)
is the canonical repo, which this ADR and ADR-2607031000 already assumed
correctly. No change needed; noted here only for completeness since it
landed in the same window.

No change to this ADR's Status, numbered Decision list beyond §2/§3 above,
or overall Consequences — the shape (one actor per game, Worker-custodial
for now, Governor-gated publish, land `plumbing-rounds` first) still holds.
