# ADR-2607050200: EAVT §6 implementation, round 1 — `ref?` naturalizes to `ipld/link?`

**Status**: accepted (implemented, partial — see Follow-up)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

ADR-2607023200 §6 proposed a Datomic-referenced EAVT design for the kotobase
datom database, as **proposed, not implemented**. Two things needed
resolving before touching code:

1. **§6 has since been partially superseded**, not merely left undone.
   ADR-2607032430 (D1, 2026-07-03) shipped a log-structured
   novelty-log + amortized-fold write path in `kotobase-engine` to fix a
   production CPU-limit collapse. That redesign independently arrived at
   the same destination §6 points 1–3 were reaching for:
   - **§6-1** ("Tx = commit-dag itself") — D1's commit-dag chain already is
     the tx log; no separate tx log exists or is needed.
   - **§6-2** ("delta commit: assertions/retractions/snapshot/prev/seq") —
     D1's `{"indexed" <snapshot Link> "novelty" [<tx-block Link> ...]}`
     state shape **is** a delta-commit design, just a different concrete
     shape (a novelty CID list against a snapshot pointer, rather than one
     flat delta record per commit) — arguably better, since it also gives
     the amortized-fold cost model §6 never specified.
   - **§6-3** ("Added: retraction as tombstone") — already the retraction
     semantics `quad-store.core/retract-quad` and the novelty-merge read
     path (`hot-datoms`) implement.

   This ADR is the first document to say so explicitly: **§6 points 1–3 are
   closed, by D1, not by directly implementing §6's own literal shape.**
   Nothing further is needed there.

2. **§6-4 ("`ref?` naturalizes to: is the value `ipld/link?`") turned out to
   be blocked on something §6 never mentioned.** Investigating why
   `quad-store.core/assert-quad`'s `ref?` parameter defaulted to
   `(constantly false)` (i.e., reverse-indexing was opt-in, never
   automatic) found the real reason: **`ipld.core/Link` is a bare
   `deftype` with no reader or `print-method`** (by design — the codebase's
   dag-cbor/IPLD layer round-trips Links via CBOR tag 42, not a JVM/cljs
   reader macro). `quad-store.core/index-root` persists index keys via
   `pr-str`/`edn/read-string` (a prolly-tree leaf key, not a dag-cbor
   block) — so a raw `Link` value could never have survived that round
   trip. Defaulting `ref?` to `ipld/link?` without first fixing this would
   have silently broken every persisted-then-reloaded ref.

   This also clarifies §6-4's real dependency on §6-5 (typed values):
   the two aren't independent line items, they share one blocking root
   cause (index-root's value encoding didn't preserve anything but strings).

## Decision

### Implemented this round: Link round-trip + ref naturalization

**`kotoba-lang/quad-store`** (`094e8da8`):
- New `link->edn`/`edn->link`: a `Link` becomes the plain 2-vector
  `["ipld/link" cid]` for persistence — ordinary, portable EDN, no custom
  reader needed on JVM or ClojureScript. `index-root` applies this to
  every position of the `[k1 k2 v]` triple before `pr-str`.
- `assert-quad`/`retract-quad`'s `ref?` default changes from
  `(constantly false)` to `ipld/link?` (§6-4, literally). A caller can
  still pass a different predicate to opt out or widen it.
- 8 tests / 30 assertions green, JVM **and** real `shadow-cljs` (this
  repo's own documented discipline: `nbb` hides real bugs, per
  ADR-2607022600 add.3 — verified with the actual toolchain, not skipped).

**`kotoba-lang/kqe`** (`8600fb12`): pin-bump only, no source change (kqe's
query routing doesn't inspect value types). 4 tests / 6 assertions green,
both platforms.

**`kotoba-lang/kotobase-engine`** (`9279de77`):
- `->quad`/`->quad-value`: an `ipld/link` value in the `:o` position now
  passes through unchanged instead of being blanket-stringified — the
  precondition for `ref?` to ever see a real Link at `assert-quad`-call
  time.
- `transact`/`transact-tx`/`fold!` default `ref?` to `ipld/link?` (was
  `(constantly false)`).
- New `refs` (thin `quad-store.core/refs-to` wrapper) — the reverse-lookup
  read side had **no public entry point in this engine at all** before
  this; a caller could assert a ref but never query it back.
- `v->edn`/`cold-datoms`/`hydrate-db` round-trip a Link correctly through
  the wire (`v_edn`) and the persisted prolly-tree snapshot
  (`qs/link->edn` on write, `qs/edn->link` on read).
- **The novelty (tx-block) path needed no fix** — `put-tx-block!`/
  `read-tx-block` already go through `ipld/put-node!`/`get-node`, which
  round-trips `Link`s correctly via real dag-cbor tag 42. Only the
  prolly-tree-backed **cold/snapshot** path (`pr-str`/`edn/read-string`,
  not dag-cbor) had the gap. This ADR's fix is exactly as wide as the gap,
  not wider.
- 20 tests / 72 assertions green (18 existing + 2 new covering the full
  novelty → `fold!` → cold-read → `refs`/`refs-to` round trip), JVM and
  real `shadow-cljs`.

### Not implemented this round (explicit follow-up, not silently dropped)

- **General typed-value domain** (§6-5: int/bytes/bool/null/list/map on
  the wire, beyond string + Link). `quad-store`'s own docstring already
  flagged this as a follow-up before this ADR; still true. This round's
  fix is deliberately narrow — Link round-trip only, since that's what
  unblocks `ref?` naturalization, the concrete thing asked for.
- **Schema self-description** (§6-6: `:db/cardinality`, `:db/unique`
  enforcement at transact time). `kotobase-engine.core/pull`'s own
  docstring already says "quad-store has no cardinality tracking, every
  attribute is multi-valued" — unchanged by this ADR.
- **Entity id as Link** (§6-7's content-addressed-entity option; external
  string ids remain the only supported shape).
- **`kotobase-cljc-worker`** (the production Cloudflare Worker consuming
  `kotobase-engine`) is not touched by this ADR. It calls the engine's
  public functions generically and doesn't construct `Link`-valued quads
  itself yet; wiring an actual ref-bearing write path through to it (e.g.
  entity references in `handler.cljc`'s `tx-edn->quads`) is separate,
  product-level follow-up work, not a mechanical consequence of this ADR.

## Consequences

- (+) §6-4 is now real, not aspirational: assert a `Link`-valued datom and
  it is reverse-indexed automatically, persists correctly through a fold,
  and is queryable via `kotobase-engine.core/refs` — the first working
  slice of "Datomic-style ref attributes" in kotobase.
- (+) The dependency between §6-4 and §6-5 that ADR-2607023200 didn't
  state explicitly (ref-detection requires the value's type to survive
  storage) is now documented, so a future implementer of full typed values
  doesn't have to rediscover it.
- (+) Scope stayed proportional to what's proven and tested: three small,
  reviewable diffs (quad-store, kqe pin bump, kotobase-engine), not a
  sweeping schema/typed-value migration bundled in under one ADR.
- (−) `quad-store`'s s/p positions (and e/a in kotobase-engine) are still
  blanket-stringified — only the object/value position can carry a Link.
  A content-addressed *entity* id (§6-7) still isn't representable.
- (±) `link->edn`'s `["ipld/link" cid]` 2-vector is now a de facto informal
  wire convention. It isn't registered anywhere as a formal schema/spec —
  acceptable for an internal engine-to-storage boundary, but a future
  typed-value ADR should fold it into whatever general value-tagging
  scheme §6-5 lands on, rather than leaving two conventions.

## Follow-up

- General typed-value domain (§6-5) — likely reuses `link->edn`/`edn->link`'s
  shape (a small tagged-vector convention) generalized to int/bool/list/map.
- Schema cardinality/unique enforcement (§6-6) at `kotobase-engine.core`
  transact time.
- Wire an actual ref-bearing write path through `kotobase-cljc-worker` once
  a real caller needs entity references on the wire (product-level, not
  mechanical).
- **`manifest/west.yml` pin advancement for `quad-store`/`kqe`/
  `kotobase-engine` is deferred**, same reason as ADR-2607050100: all three
  repos' shared west checkouts (`orgs/kotoba-lang/{quad-store,kqe,
  kotobase-engine}`) sit at a stale HEAD that `gen-west-manifest.cljs --entry`
  reads from directly, and advancing that HEAD in a shared checkout is out
  of this session's authority (see ADR-2607050100's identical finding for
  `kotoba`). `kotobase-engine`'s shared checkout additionally carries
  uncommitted local changes to `deps.edn`/`core.cljc`/`core_test.cljc` that
  byte-match an already-landed commit (`963ed6d4`, the ADR-2607032500 datom-
  unification commit) — the same "duplicate debris" pattern found in
  `kotoba`'s checkout, not this session's own edits. The 3 child repos are
  live on GitHub `main` regardless (see `:artifacts`); only the
  superproject's own pin of *them* lags.

## One-line summary

**ADR-2607023200 §6 points 1–3 are closed — by ADR-2607032430's D1
novelty-log design, not by implementing §6's own literal shape. §6-4 (ref
naturalization) is now implemented for real, after discovering and fixing
its true blocker: `ipld.core/Link` had no way to survive `quad-store`'s
`pr-str`/`edn/read-string` persistence round-trip. §6-5 (typed values) and
§6-6 (schema) remain explicit, scoped follow-up — not fabricated to make
this ADR look more complete than the tests prove it is.**
