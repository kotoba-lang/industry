---
id: adr-2607072200-kotoba-git-kotoba-rad-content-addressed-vcs
title: "ADR-2607072200: kotoba-git + kotoba-rad — content-addressed git/Radicle-equivalent layer on the kotobase-peer stack"
status: accepted
doc_type: adr
topic: kotoba-git-kotoba-rad-content-addressed-vcs
authoritative: true
last_verified: 2026-07-07 (addenda same day: datom-native object model, ref-policy, signed head-announce, CACAO delegation chains)
authoritative_for:
  - "kotoba-lang/kotoba-git owns the content-addressed git object model (blob/tree/commit) and mutable ref store for this ecosystem"
  - "kotoba-lang/kotoba-rad owns sovereign repo identity (RID), delegate authorization, and signed refs (the Radicle-equivalent layer)"
  - "The prior Rust kotoba-git (kotoba-lang/kotoba, deleted PR #259, 2026-07-01) and ADR-2606280300/2606251200's Rust-era implementation claims are superseded by this ADR for any future work"
related:
  - 90-docs/adr/2606280300-kotoba-rad-git-sovereign-repo.md
  - 90-docs/adr/2607022600-kotoba-database-crates-cljc-migration-roadmap.md
  - 90-docs/adr/2607032430-kotoba-datom-log-structured-engine-redesign.md
  - 90-docs/adr/2607032500-kotoba-kotobase-clojure-datomic-relationship.md
supersedes: []
superseded_by: []
---

# ADR-2607072200: kotoba-git + kotoba-rad — content-addressed git/Radicle-equivalent layer

**Status**: accepted (both repos created, code real and unit-tested, not yet published/registered at time of writing this section — see Verification)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki (instruction: "kotobase の datomic 基盤の上に git, racile 相当を構築してね" — build a git-equivalent and Radicle-equivalent on top of kotobase's Datomic-shaped foundation)

## Context

An earlier investigation this session established:

- A real Rust `kotoba-git` (byte-exact git object↔CID bridging, packfile
  decode, smart-HTTP v0) existed in `kotoba-lang/kotoba` and was **deleted
  wholesale** on 2026-07-01 (PR #259). ADR-2606251200's "implemented and
  wired... proven end-to-end" claim about it has no corresponding code
  anywhere in the repo as of this writing.
- ADR-2607022600 (the CLJC migration roadmap rebuilding a Datomic-shaped
  substrate as `prolly-tree`/`arrangement`/`chain`/`kotobase-peer`/
  `io-ipld`/`io-multiformats`/`org-ietf-cbor`) **explicitly excludes**
  `kotoba-git`/`kotoba-rad` from its scope.
- `kotoba-lang/kotobase-peer` (ADR-2607032430, ADR-2607032500: "kotoba :
  kotobase = Clojure : Datomic") is the real, actively-developed Datomic-
  shaped engine — not real Datomic, not DataScript, a from-scratch 4-index
  (`arrangement`) + log-structured (`chain`) + content-addressed
  (`prolly-tree`/`io-ipld`) design, borrowing Datomic's vocabulary and API
  shape, not its code.
- `etzhayyim/root/70-tools/src/etzhayyim/kotoba_rad.cljc` is a real,
  unit-tested RID/identity/sigref prototype, but uses its own hand-rolled
  raw-codec (0x55) CID implementation, independent of and incompatible with
  the `io-multiformats`/`io-ipld` scheme the actual `kotobase-peer` stack
  uses — a fragmentation this ADR resolves by building on the latter.

So: there was no working git-equivalent or Radicle-equivalent layer on the
codebase's actual current Datomic-shaped foundation, only design documents,
a deleted prototype, and a disconnected earlier CLJC prototype using a
different CID scheme.

## Decision

**Build `kotoba-git` (git-equivalent) and `kotoba-rad` (Radicle-equivalent)
as two new, decoupled `kotoba-lang` CLJC repos, composed entirely from the
existing `kotobase-peer` stack's real primitives, rather than reimplementing
git's own SHA-1/packfile format or resurrecting the deleted Rust code.**

### Layering

```
kotoba-rad   RID/identity, delegate authorization, signed refs, push-gate
   |         (arrangement not required; built on chain + io-ipld + ed25519)
kotoba-git   blob/tree/commit objects, ref store, DAG walk
   |         (io-multiformats + org-ietf-cbor + io-ipld + arrangement)
kotobase-peer / arrangement / chain / prolly-tree / io-ipld   (existing, unmodified)
```

- **`kotoba-git.object`**: blobs are raw-codec (0x55) content-addressed
  bytes (`multiformats.core/cidv1-raw`); trees and commits are DAG-CBOR
  nodes (`io-ipld`, real CBOR tag-42 links, 0x71 codec). Commits carry a
  `parents` **vector** — a real commit DAG (merges representable), not a
  linear chain.
- **`kotoba-git.refs`**: `refs/heads/main`-style mutable pointers as quads
  in an `arrangement` db (`arrangement.core`/`arrangement.query`), the same
  mutable-pointer-over-immutable-DAG pattern Datomic itself uses. Persisted
  via `arrangement.core/commit!` with identity blind/encrypt functions
  (refs are public repo metadata; no privacy semantics needed here).
- **`kotoba-git.log`**: `ancestors` (full DAG reachability, not just
  first-parent), `log` (first-parent history), `missing-since` (the
  object-negotiation primitive a push/pull exchange needs).
- **`kotoba-rad.identity`**: RID = CID of a repo's own genesis block
  (`{did, created}`), via `io-ipld` — the **same** CID scheme `kotoba-git`
  uses for objects, unifying the two-CID-scheme fragmentation flagged above.
- **`kotoba-rad.journal`**: an append-only, hash-chained log of identity
  events, built **directly on `kotoba-lang/chain`** — chain's single-parent,
  opaque-state design is exactly a linear identity journal (as distinct
  from `kotoba-git`'s N-parent commit DAG, which chain's shape cannot
  represent — this is why the two repos use different underlying
  primitives for their respective histories).
- **`kotoba-rad.delegate`**: folds the journal into the current
  authorized-delegate set. Owner is always authorized; every
  `delegate-add`/`delegate-remove` entry must itself carry a valid Ed25519
  signature from an already-authorized did:key (`org-ietf-ed25519`) — so
  authority only ever flows forward from the genesis owner.
- **`kotoba-rad.sigref`** + **`kotoba-rad.push-gate`**: signed
  ref→commit-cid attestations and `authorize-push?`, a pure-function
  reimplementation of the deleted Rust `push_gate`/`RadRegistry` — callable
  from either a client or a server, unlike the original's server-only
  middleware form.

### Why two decoupled repos, not one

`kotoba-rad` only ever handles plain CID strings for refs/commits — it
never imports a `kotoba-git` object directly. This means `authorize-push?`
can gate any content-addressed system's ref updates, not only
`kotoba-git`'s, and either repo can evolve independently.

### Why not real Datomic, real git, or the deleted Rust code

Consistent with `kotobase-peer` itself (ADR-2607032430/2607032500):
`arrangement`/`chain` borrow Datomic's *vocabulary*, not Datomic-the-
product. Likewise `kotoba-git` borrows git's *concepts* (blob/tree/commit,
refs, DAG history) but its content-addressing is CID-based (multiformats/
DAG-CBOR), not SHA-1/packfile — there is no requirement to be byte-
compatible with real `git`, and no external consumer depends on that. The
deleted Rust implementation attempted git-CLI wire compatibility; this ADR
deliberately does not re-attempt that scope (see "What this ADR does NOT
decide").

## What this ADR does NOT decide

- **No git-CLI wire compatibility.** No smart-HTTP bridge, no SHA-1 hashing,
  no binary packfile format. If real `git` interop is ever needed, that is
  a distinct translation layer on top of these primitives, not a rewrite.
- **No transport/replication wiring.** `kotoba-git.log/missing-since` gives
  the object diff a sync protocol needs, but neither repo depends on
  `kotoba-lang/p2p` directly — that repo's `deps.edn` currently points at a
  renamed-away `commit-dag` coordinate and needs a patch first, and its
  `:head-announce` message has no signature field yet. Wiring
  `kotoba-rad.push-gate/authorize-push?` into a signed head-announce is the
  natural next step, not attempted here.
- **No CACAO/SIWE delegation chains.** `kotoba-rad.delegate` uses direct
  Ed25519 did:key signing, not `cacao.core/verify-chain`-style root-first/
  leaf-last delegation. A reasonable future enhancement, deliberately
  deferred to keep the crypto surface small and directly testable.
- **No ref-policy** (protected branches, fast-forward-only, etc) —
  `authorize-push?` checks *who* signed, not policy about what ref updates
  are allowed once authorized.
- **No restore-from-persisted-snapshot for `kotoba-git.refs`.**
  `arrangement.core/commit!` is confirmed and used; a public "rehydrate a
  db from a snapshot CID" counterpart is not currently exposed by
  `arrangement` (that logic lives inside `kotobase-peer`'s own `fold!`/
  `cold-datoms`, not as a standalone reusable API).

## Consequences

- `etzhayyim/root/70-tools/src/etzhayyim/kotoba_rad.cljc`'s hand-rolled CID
  scheme is now a divergent, superseded prototype relative to `kotoba-rad`'s
  `io-ipld`-based RID; migrating it (or the actor identity journals under
  `etzhayyim/root/80-data/kotoba-rad/`) onto this ADR's scheme is a
  follow-up, not done here.
- Both new repos depend on the actively-churning `kotobase-peer` stack
  (three renames in the week before this ADR: `quad-store`+`kqe`→
  `arrangement`, `commit-dag`→`chain`, `kotobase-engine`→`kotobase-peer`).
  `deps.edn` pins exact `:git/sha`s (not floating refs) for this reason;
  expect to re-verify upstream API shapes before extending either repo.
- Neither repo is wired into any production surface (`kotobase.net`,
  `kotobase.aozora.app`) — this ADR only establishes the primitives.

## Verification

- `kotoba-git`: `clojure -M:test` — 12 tests / 25 assertions, passing
  against both pinned `:git/sha` deps and local sibling checkouts
  (`clojure -M:local:test`). Covers blob/tree/commit round-trip, merge
  commits (multi-parent), tree entry ordering, DAG ancestor reachability,
  first-parent log, `missing-since` object negotiation, and ref set/move/
  list/persist.
- `kotoba-rad`: `clojure -M:test` — 19 tests / 29 assertions, passing
  against both pinned `:git/sha` deps and local sibling checkouts. Covers
  RID genesis + content-addressing, journal append/read/hash-chain-tamper
  detection, delegate add/remove with real Ed25519 signature verification
  (including rejecting an unauthorized signer and honoring revocation),
  sigref sign/verify (including tamper detection), and end-to-end
  `authorize-push?` (owner push, delegated push, outsider rejection,
  mismatched-target rejection, revoked-delegate rejection).
- No `wrangler deploy` / production wiring attempted for either repo.

## Addendum (2026-07-07, same day): object model redesigned onto native arrangement datoms

**Owner instruction**: "git は外部化するのではなく、kotoba-git で git 自体を
Datomic、kotoba で再設計実装してください" — don't externalize git as a
side store; redesign git itself on Datomic/kotoba, inside `kotoba-git`.

**What changed**: the original decision above stored blob/tree/commit as
`io-ipld` content-addressed DAG-CBOR blocks — a store that happened to sit
*next to* `arrangement` (which only held refs). That is a git object store
riding alongside the Datomic-shaped foundation, not git redesigned as part
of it. This addendum corrects that:

- `kotoba-git.object`'s `write-blob`/`write-tree`/`write-commit` (the
  trailing `!` dropped — they are now pure) assert blob/tree/commit
  content directly as `arrangement` quads whose **subject is the object's
  own content hash** — `{cid "blob/bytes" bytes}`, `{cid "tree/entries"
  [...]}`, `{cid "commit/tree" (ipld/link tree)}` +
  `commit/parents`/`commit/author`/`commit/message`/`commit/ts`. Every
  write function is now `(fn [db ...] -> [db' cid])`, matching
  `kotoba-git.refs`'s pre-existing db-in/db-out style, so object writes and
  ref updates thread the *same* db value.
- `io-multiformats`/`org-ietf-cbor`/`io-ipld` are now used **only** for
  their pure canonical-encoding/hashing functions (`cidv1-raw`,
  `encode`+`cid`) to derive each object's content-addressed identity —
  nothing is persisted through them anymore. Persisting a repo to durable
  storage is one operation over the whole db (new `kotoba-git.repo/
  persist!`, wrapping `arrangement.core/commit!`), covering objects and
  refs together, the same path every other `kotobase-peer` domain uses.
- A concrete Datomic-native payoff, not just a storage-location change:
  `commit/tree` is asserted as a real `ipld/link`, so
  `arrangement.core/refs-to` answers "which commits reference this tree"
  with zero extra code — a reverse graph query that the prior
  side-content-store design could not offer for free.
- **Trade-off accepted, stated plainly**: `commit/parents` is stored as one
  literal vector-of-links (not decomposed into one quad per parent), to
  preserve parent order (first-parent history matters for `log`); the
  cost is that individual parents are not reverse-indexed via `refs-to`.
  `ancestors`/`log`/`missing-since` still walk the DAG procedurally rather
  than as a single Datalog query, because `arrangement.datalog` is a
  conjunctive-join layer, not (yet) a transitive-closure/fixpoint one
  (ADR-2607022600 already flags "Datalog fixpoint" as an open follow-up
  for this whole stack, not something this ADR resolves).
- `kotoba-rad` is untouched: it never depended on `kotoba-git`'s object
  representation (only on plain CID strings), so the decoupling from the
  original decision paid off exactly as intended when this half of the
  design changed underneath it.

**Verification**: `kotoba-git`'s own suite grew from 12 to 14 tests / 25 to
28 assertions (adding `kotoba-git.repo-test` and an
`arrangement.core/refs-to` reverse-lookup test), all green against both
pinned `:git/sha` deps and local sibling checkouts. The full cross-repo
integration script from this ADR's original Verification section (RID →
delegate → object write → sigref → `authorize-push?` → ref move → second
commit → revoke → outsider-rejection) was re-run end-to-end against the
new API and produced identical pass/fail results, plus a new step
demonstrating `arrangement.core/refs-to` resolving `tree-2`'s referencing
commit with no `kotoba-git`-specific code.

## Addendum (2026-07-07, later same day): ref-policy, signed head-announce, CACAO delegation chains

Three follow-on iterations closed gaps this ADR's original "What this
ADR does NOT decide" section named, each verified with real tests
(`clojure -M:test`, pinned deps and local sibling checkouts) before
landing:

- **`kotoba-git.ref-policy`** (new namespace): `fast-forward?` (is a
  proposed ref move a fast-forward — old target nil, equal, or an
  ancestor of the new one, via `kotoba-git.log/ancestors`) and
  `set-ref-ff-only!` (throws, leaving the ref untouched, on a
  non-fast-forward). This is *shape* policy, independent of and
  composable with `kotoba-rad`'s *identity* policy — no single function
  combines both yet. 23→32 tests / 51→68 assertions.
- **Signed head-announce**, split across two repos to keep them
  decoupled: `kotoba-lang/p2p` gained an optional `new-node` 3-arity opts
  map (`:sign-announce`/`:verify-announce?`, both defaulting to a no-op
  so existing 2-arity callers are unaffected — kotoba-lang/p2p PR #1,
  merged after CI (JVM + real ClojureScript) passed) closing that
  namespace's own docstring note that "head-record signing is CACAO's
  job and a tracked follow-up". New `kotoba-rad.announce` namespace
  provides the concrete hooks: a p2p head-announce
  (`{:graph :head-cid :seq ...}`) turned out to be exactly a sigref shape
  (ref-name=graph, commit=head-cid, ts=seq), so no new signing primitive
  was needed, only this adapter. p2p: 9 tests / 19 assertions (5 new).
  kotoba-rad: 34→40 tests / 55→62 assertions. In the process, corrected a
  standing inaccuracy in both READMEs claiming p2p's `deps.edn` needed a
  `commit-dag`→`chain` patch — that had already been fixed upstream,
  independently, before this was checked.
- **`kotoba-rad.cacao-delegate`** + **`push-gate/authorize-push-cacao?`**
  (new dependency: `org-chainagnostic-cacao`): a second, journal-free
  authorization scheme alongside `kotoba-rad.delegate`'s ledger — the
  owner mints a CACAO (CAIP-122/SIWE) capability chain (root-first,
  leaf-last, `cacao.core/verify-chain`) granting a push-resource string
  to a delegate's did:key; the delegate presents that chain (or a further
  sub-delegated link) to prove authorization with no journal lookup.
  Verified: single-link exact/wildcard grants, two-link sub-delegation to
  a third party, sub-delegation cannot escalate resources beyond what the
  delegator holds (`cacao.core`'s own `covers?` constraint), wrong root
  issuer / wrong claimed holder rejected, expiry enforced when checked.
  Known gap, documented rather than solved: no revocation without an
  expiry (unlike the journal's `remove-delegate!`). 40→50 tests / 62→76
  assertions.

All three were driven by the owner's explicit direction to keep
iterating on "成熟度, coverageを向上" (maturity, improve coverage) on a
recurring cadence rather than stopping after the initial ADR; each
iteration's manifest pin advance and test-count delta is in the
superproject's own commit history for `manifest/west.yml` around this
date, not restated per-bullet here.

## Addendum (2026-07-07, later same day): a real cross-repo verification pass, and a real bug it found

A `/verify`-style pass loaded `kotoba-git` + `kotoba-rad` + `kotoba-lang/
p2p` together in one process (a real downstream consumer's classpath),
rather than trusting each repo's own isolated test suite. `set-ref-
guarded!` and the CACAO delegation chain (previous addendum) verified
cleanly this way. The signed head-announce integration did not:

- **Finding**: `kotoba-lang/p2p`'s `chain` pin predated ADR-2607050800's
  internal `commit-dag`→`chain` rename (it still required
  `commit-dag.core`). This looked harmless in every isolated check this
  session ran on `p2p` alone — its own test suite pinned the same stale
  SHA consistently, so nothing ever contradicted it. The moment a real
  consumer also depended on `chain` at a *current* SHA (`kotoba-rad`
  does), tools.deps resolved one version for the whole classpath, and
  picking the newer one broke `p2p`'s own `commit-dag.core` require —
  confirmed directly (`(require 'chain.core)` failed, `(require
  'commit-dag.core)` succeeded, on the merged classpath; the reverse held
  before the fix). This silently blocked the exact integration
  `kotoba-rad.announce` + `kotoba-lang/p2p`'s hooks were built for — no
  amount of testing either repo in isolation could have caught it.
- **Fix** (`kotoba-lang/p2p` PR #2, merged after CI green on both JVM and
  real ClojureScript): pinned `chain` to the same SHA `kotoba-rad` uses;
  changed the require from `commit-dag.core` to `chain.core` (API
  unchanged by the rename — pure coordinate/require update).
- **Re-verified after the fix, end-to-end, for real** (not the contract-
  level workaround the initial verification pass had to fall back to): a
  delegate signed a real 2-node `chain.core` commit history via
  `kotoba-rad.announce`'s hooks; the receiving node fully converged
  (bitswap/hydrate genuinely ran, not just message-passing) to the signed
  head; the same receiver correctly *rejected* a real, valid,
  further-ahead but **unsigned** announce from the same peer, leaving its
  head unchanged.
- kotoba-rad: 40→41 assertions unchanged (docs-only update recording the
  now-verified integration). p2p: 9 tests / 19 assertions, unchanged
  count, green on the corrected pin.

The lesson generalizes past this one bug: this session's per-repo test
suites (and even the first cross-repo `/verify` pass's unit-level checks)
were each internally consistent and each green, and none of that
surfaced the conflict — only actually loading the repos a real consumer
would combine, together, in one process, did.
