# ADR-2607050700: Datomic terminology over RDF — `quad-store`+`kqe` merge into `arrangement`, `kotobase-engine` renames to `kotobase-peer`

**Status**: accepted (implemented)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

Following ADR-2607050500's operational-semantics gap assessment (Phase 0:
`kqe` gained a `visible?` query seam, `quad-store` gained a
`schema-version` marker), a direct follow-up question came up: **`kqe`
(Kotoba Query Engine) and `quad-store` are two separate repos that
duplicate the same job** — `quad-store` holds the 4-index Arrangement
(spo/pso/pos/ocp) and commit chain, `kqe` is a thin pattern-router *over*
those same indexes with no storage of its own. Splitting them into two
repos (ADR-2607010930 Phase 6 / ADR-2607022600) was itself a byproduct of
mirroring the old Rust crate split (`kotoba-query` = Arrangement + Datalog
engine in one crate), not a decision that CLJC needed two repos.

A second question followed: **why does `kotobase-engine` have "engine" in
its name at all**, when `kotobase` (the client-side `IStore` port) was
already taken by a different, adjacent repo, and "engine" doesn't
correspond to any term in the vocabulary this substrate otherwise mirrors?
ADR-2607032500 established the guiding equivalence **`kotoba : kotobase =
Clojure : Datomic`** — kotoba is the language, kotobase is a Datomic-style
persistent database built on it. Datomic's own vocabulary already has a
name for "the library an application embeds to call `transact`/`q`/
`pull`": a **Peer**. "Engine" was never that vocabulary; it was a leftover
generic noun from before the Datomic-alignment decision existed.

A third, explicit instruction narrowed the naming direction: **RDF
vocabulary (`quad`, `triple-store`) is not the priority — align to Datomic
terminology instead.** Datomic itself calls its own 4-covering-index
in-memory structure (EAVT/AEVT/AVET/VAET) an **Arrangement**. That term
already existed in this codebase's own Rust-era design vocabulary (see
`kotoba/CLAUDE.md`'s "4-Index Arrangement (Datomic EAVT/AEVT/AVET/VAET)"
table and `kotoba-query/benches/arrangement.rs`) but had been dropped when
the repo was named `quad-store` (RDF-flavored) during the CLJC migration.

Finally: **this codebase is pre-release.** There is no deployed consumer
outside this org's own repos, and the owner explicitly waived backward
compatibility for this change — required constructor arguments (a
`schema-version`, a `visible?` predicate) are the correct shape now, not
optional parameters with defaults bolted on to avoid breaking callers that
don't yet exist in the wild.

## Decision

### Merge: `kqe` → `arrangement.query` (inside the renamed `quad-store` repo)

`kqe.core/query` is a pure routing function over `quad-store`'s own
indices (`entity-attrs`/`by-predicate`/`by-predicate-value`/`refs-to`) with
zero storage of its own and zero independent state. Two repos for one
indivisible read path was the duplication the follow-up question correctly
identified. `kqe`'s source becomes `arrangement.query` inside the renamed
repo (below); the `kqe` repo itself is retired (README rewritten to point
to `arrangement`, then archived — GitHub repo rename/archive preserves
commit SHAs, so `kqe`'s own git history remains reachable, just read-only).

### Rename: `kotoba-lang/quad-store` → `kotoba-lang/arrangement`

Adopts Datomic's own term for the 4-index structure this repo already
implements, replacing the RDF-flavored `quad-store` name per the explicit
Datomic-priority instruction. `arrangement.core` keeps every function
(`empty-db`, `assert-quad`/`retract-quad`, `entity-attrs`/`by-predicate`/
`by-predicate-value`/`refs-to`, `link->edn`/`edn->link`, `index-root`,
`commit!`) unchanged in behavior; only the namespace and repo name change,
plus the `kqe` merge above lands as `arrangement.query` in the same repo.
`commit!`'s `schema-version` (ADR-2607050500 Phase 0) and
`arrangement.query/query`'s `visible?` (ADR-2607050500 Phase 0) both become
**required positional arguments**, not optional/defaulted — the
no-backward-compatibility decision from Context, applied consistently.

### Rename: `kotoba-lang/kotobase-engine` → `kotoba-lang/kotobase-peer`

Adopts Datomic's term for "the transact/q/pull library an application
embeds." `kotobase-peer.core` keeps every function (`commit!`,
`hot-datoms`, `should-fold?`/`fold!`, `novelty-size`, `chain`,
`verify-chain`, `transact`, `datoms`, `q`, `pull`) unchanged in behavior;
`q` now requires `visible?` as a mandatory third argument (no default),
consistent with `arrangement.query/query`'s own requirement — a caller
that wants "no filtering" must say so explicitly with `(constantly true)`,
rather than get it silently for free.

### Downstream consumers updated in the same landing

- **`kotobase-cljc-worker`** (production Cloudflare Worker behind
  `kotobase.aozora.app`): `shadow-cljs.edn` source-paths repointed from
  `kotobase-engine`+`kqe`+`quad-store` to `kotobase-peer`+`arrangement`;
  `handler.cljc`'s `do-q` updated to the new mandatory-`visible?` 3-arity
  (`(eng/q db pat (constantly true))` — this is also a real bug fix: the
  old 2-arity call would have broken the moment the dependency update
  landed, since the new `q` has no 2-arity to fall back to).
- **`p2p`**: `deps.edn`'s `:test` extra-dep coordinate updated to
  `kotobase-peer` at the same pinned SHA (the SHA predates this rename, so
  the historical commit's own `ns` is still literally `kotobase-engine.core`
  — that `:require` is correctly left unchanged; renaming a GitHub repo
  does not rewrite the content of commits that predate the rename).

## Consequences

- (+) One repo (`arrangement`) now owns the entire read+write path over
  the 4-index structure (storage, commit chain, *and* pattern query),
  matching how Datomic itself is one library, not a storage crate plus a
  separate query-routing crate.
- (+) Repo names now consistently mirror the `kotoba : kotobase = Clojure
  : Datomic` equivalence (ADR-2607032500): `arrangement` (Datomic's index
  structure), `kotobase-peer` (Datomic's embedded library), `commit-dag`
  (Datomic's tx log, unchanged), `datom` (unchanged). RDF-flavored naming
  (`quad-store`, `kqe` unexplained beyond its acronym) is gone from the
  active repo set.
- (+) `visible?`/`schema-version` moving from optional (ADR-2607050500
  Phase 0, backward-compatible by design at the time) to **required**
  closes the gap ADR-2607050500 itself flagged: a seam that exists but
  nothing is forced to use is easy to silently skip. Every caller of `q`/
  `query`/`commit!` now states its intent explicitly.
- (−) This is a genuine breaking change to 2 repos' public API
  (`arrangement.query/query`, `kotobase-peer.core/q`, `kotobase-peer.core/
  commit!`) — acceptable only because of the pre-release/no-compat
  decision in Context; would not be an acceptable change once
  `kotobase-peer` has external callers beyond this org.
- (±) `kqe`'s own git history survives (GitHub rename/archive semantics
  preserve commit SHAs and auto-redirect), but the repo is read-only going
  forward. Anyone who had `kqe` pinned by SHA in a `deps.edn` outside this
  org (none known to exist) would need to repoint to `arrangement`.
- (±) **Documentation debt this ADR itself corrects**: the repo READMEs
  and code comments written during the rename cited this decision as
  "ADR-2607050600" before this document existed under that number — that
  number was already claimed by the unrelated
  `2607050600-manimani-retire-consolidate-cloud` ADR (a same-day
  coincidence: two unrelated decisions landed the same session-date).
  Every one of those citations (`arrangement/README.md`,
  `kotobase-peer/{README.md,deps.edn,src/kotobase_peer/core.cljc}`,
  `kotobase-cljc-worker/{README.md,shadow-cljs.edn}`, `p2p/{README.md,
  deps.edn}`, retired `kqe/README.md`) is corrected to cite this document,
  **ADR-2607050700**, as a follow-up commit to each repo.

## Follow-up

- **`manifest/repos.edn` + `manifest/west.yml`** still reference the old
  paths (`orgs/kotoba-lang/quad-store`, `orgs/kotoba-lang/kqe`,
  `orgs/kotoba-lang/kotobase-engine`) — updated in the same session as
  this ADR, as a separate `chore(manifest)` commit (this ADR documents the
  *why*, the manifest commit does the *mechanical* path/pin update).
- The 2 remaining prose-only mentions of `kotobase-engine`/`quad-store` in
  `kotoba/src/kotoba/kgraph.clj` (comments) and
  `commit-dag/src/commit_dag/core.cljc` (one comment) are cosmetic, not
  build-breaking; left as optional cleanup, not required by this ADR.
  `kotoba/docs/HISTORICAL-RUST-ARCHITECTURE.md`'s mentions are deliberately
  **not** touched — that document is an explicitly preserved historical
  design-vocabulary snapshot (see its own header), not live documentation.
- Phase 1–5 of ADR-2607050500's roadmap (WASM fuel metering, ciphertext-
  over-CID persistence, wiring `visible?` end-to-end through
  `kotobase-peer`/`kotoba`'s host providers, organizational identity,
  Protocol Adapter wire I/O) are unaffected by this rename and remain open.

## One-line summary

**`kqe` (pure query routing, no storage of its own) merges into the
renamed `arrangement` (was `quad-store` — Datomic's own term for its
4-index EAVT/AEVT/AVET/VAET structure); `kotobase-engine` renames to
`kotobase-peer` (Datomic's term for the embedded transact/q/pull library)
— both moves replace RDF-flavored naming with the Datomic vocabulary
`kotoba:kotobase = Clojure:Datomic` (ADR-2607032500) already commits this
substrate to, and both make the ADR-2607050500 Phase-0 seams
(`visible?`, `schema-version`) mandatory rather than optional, since this
codebase is pre-release and owes no caller backward compatibility yet.**
