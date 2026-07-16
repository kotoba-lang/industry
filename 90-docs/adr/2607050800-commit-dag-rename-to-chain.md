# ADR-2607050800: `commit-dag` renames to `chain`

**Status**: accepted (implemented)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

Direct follow-up to ADR-2607050700 (the `quad-store`+`kqe`→`arrangement`,
`kotobase-engine`→`kotobase-peer` rename): the owner asked whether
`commit-dag` and `kotoba/kgraph` (the language's own in-mem EAVT mirror)
were also worth renaming for the same Datomic-alignment reasons.

**`kgraph`**: kept unchanged. Renaming it to `graph` would collide with
`kotoba-lang/langgraph`'s own `langgraph.graph` namespace (the
StateGraph/Pregel execution-graph implementation) — a different concept
already live in the same ecosystem. `kgraph`'s own docstring already
documents its deliberate scope (it was itself renamed once before, from
`kqe`, for a documented reason). No change made here.

**`commit-dag`**: this repo's own README already stated, quoting
ADR-2606041151, "the CommitDag IS the write-ahead log, not a separate
journal." Datomic's own formal architecture term for exactly that
immutable, durable, append-only transaction sequence is the **Log** —
the obvious rename target. **First attempt: rename to `log`.** This
failed at the point of executing `gh repo rename` — `kotoba-lang/log`
turned out to already be a real, distinct, already-registered repo
(structured logging/telemetry, `kotoba.lang.log`, part of the
foundational stdlib, unrelated to this codebase's content-addressed
commit chain). The collision was caught before any GitHub-side rename
executed; only source-level edits (already made in anticipation of the
rename) needed correcting.

**Second candidate, presented to the owner**: `commit` vs `chain`.
`commit` matches the terser single-noun style of `arrangement`/`datom`,
but reads as a single commit object, and would sit awkwardly next to
this namespace's own `commit!` function (`commit.core/commit!`).
**`chain`** was chosen instead: it names the parent-linked structure
itself, and lines up directly with the repo's own pre-existing
`chain`/`verify-chain`/`head` functions (`chain.core/chain`,
`chain.core/verify-chain`) — no rename needed for those.

## Decision

**Rename `kotoba-lang/commit-dag` → `kotoba-lang/chain`.** Namespace
`commit-dag.core` → `chain.core`, function set unchanged (`commit!`,
`commit-info`, `chain`, `verify-chain`, `head`). GitHub repo rename
preserves commit history and auto-redirects the old remote URL.

### Downstream consumers updated in the same landing

- **`kotobase-peer`**: `deps.edn` coordinate `io.github.kotoba-lang/
  commit-dag` → `io.github.kotoba-lang/chain`, bumped to the post-rename
  SHA; `:require [commit-dag.core :as cd]` → `[chain.core :as cd]`; every
  prose comment/README passage describing the *current* relationship
  updated to say `chain`. One passage is a **verbatim quote** of
  ADR-2607022600's own historical wording ("quad-store/commit! and
  commit-dag are two unmerged implementations") — left textually
  unchanged (same treatment ADR-2607050700 gave its own quad-store/kqe
  quote), with an updated gloss noting both renames.
- **`p2p`**: `deps.edn` coordinate renamed to `chain`, **same pinned
  SHA** (`05cc0473...`) — that SHA predates this rename (verified via
  `gh api .../compare`, ahead_by 1 relative to this rename's starting
  commit), so the `:require [commit-dag.core :as cd]` in `sync.cljc` and
  `sync_test.cljc` is correctly left **unchanged**: renaming a GitHub
  repo does not rewrite the content of commits that predate the rename,
  so the namespace literally declared at that pin is still
  `commit-dag.core`. Same reasoning ADR-2607050700 already established
  for p2p's `kotobase-engine` pin. Prose describing current (not
  historical) state was updated to `chain`.
- **`kotobase-cljc-worker`** (production Cloudflare Worker behind
  `kotobase.aozora.app`): `shadow-cljs.edn`'s `../commit-dag/src`
  source-path → `../chain/src` (a real functional path, not prose);
  README/`wrangler.jsonc` prose ("prolly/commit-dag block format") →
  "prolly/chain".

All four repos verified green after the change: `chain` itself (8
tests/22 assertions), `kotobase-peer` (21 tests/73 assertions), `p2p` (4
tests/10 assertions), `kotobase-cljc-worker` (12 tests/54 assertions) —
JVM and real `shadow-cljs` where applicable (`kotobase-cljc-worker` is
cljs-only).

## Consequences

- (+) `chain` reads unambiguously against its own function names
  (`chain.core/chain`, `chain.core/verify-chain`) — no self-referential
  awkwardness the way `commit.core/commit!` would have had.
- (+) The near-miss with `kotoba-lang/log` is now on record: a future
  renamer reaching for "the Datomic term for X" should check the actual
  GitHub org first, not just the vocabulary mapping — a name being
  *conceptually* correct doesn't mean it's *available*.
- (±) `commit-dag`'s own git history survives (GitHub rename preserves
  SHAs and auto-redirects), read-only under the old name going forward.
- (±) `p2p`'s pinned `chain` SHA still literally declares `ns commit-dag.
  core` at that commit — documented explicitly in-repo (both in this ADR
  and in `p2p`'s own source comments) so a future reader isn't confused
  about why the require doesn't match the dependency's current name.
- (−) This ADR itself is being written **after** the rename had already
  landed and been cited (as "ADR-2607050800") across 4 repos' READMEs
  and code comments — a process gap: the number was reserved and used
  in commit messages/prose before the document existed under it. Unlike
  ADR-2607050700 (which corrected a *wrong* number colliding with an
  unrelated ADR), this is a *missing* document for a number already
  correctly reserved and consistently used everywhere it appears. Fixed
  by this document filling the gap, not by renumbering anything.

## Follow-up

None outstanding specific to this rename — `manifest/repos.edn`/
`manifest/west.yml` were updated in the same session
(`orgs/kotoba-lang/commit-dag` → `orgs/kotoba-lang/chain`, pin verified
server-side via `scripts/verify-west-pins.cljs`).

## One-line summary

**`commit-dag` renames to `chain` — not `log` (Datomic's own term for
this concept, but already claimed by an unrelated existing
`kotoba-lang/log`) — because `chain` names the parent-linked structure
itself and already matches the repo's own `chain`/`verify-chain`/`head`
functions; the 3 real dependents (`kotobase-peer`, `p2p`,
`kotobase-cljc-worker`) are updated and green, with `p2p`'s pre-rename
pin correctly left referencing the historical `commit-dag.core`
namespace rather than a namespace that didn't exist yet at that commit.**
