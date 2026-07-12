---
id: adr-2607120730-yoro-social-fold-scaling-incremental-fold-design
title: "ADR-2607120730: yoro-social-v2 fold does not scale (full-rebuild, no incremental prolly-tree insert) — incremental-fold design, periodic kawaraban/media writes paused"
status: accepted
doc_type: adr
topic: kotobase-scaling
authoritative: true
last_verified: 2026-07-12
implemented: false
related:
  - orgs/kotoba-lang/kotobase-peer/src/kotobase_peer/core.cljc
  - orgs/kotoba-lang/arrangement/src/arrangement/core.cljc
  - orgs/kotoba-lang/prolly-tree/src/prolly_tree/core.cljc
  - orgs/kotoba-lang/kotobase-cljc-worker
  - orgs/gftdcojp/app-aozora/40-engine/cljs/pds
  - orgs/etzhayyim/com-etzhayyim-kawaraban/src/kawaraban/run_live_ingest.clj
  - orgs/gftdcojp/cloud-itonami/src/cloud_itonami/media/batch.clj
  - 90-docs/adr/2607110200-kawaraban-r0-r1-cloud-itonami-isco-3521-media-broadcast.md
---

# ADR-2607120730: yoro-social-v2 fold does not scale — incremental-fold design (not yet implemented)

**Status**: accepted (diagnosis + design; implementation deferred as its own scoped work per owner instruction)
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki

## Context

Follow-up to ADR-2607110200 addendum 1 (2026-07-10's CPU-time-limit read fix). That
addendum fixed an unbounded-concurrency bug in `pmap-async` and raised
`kotobase-cljc-worker`'s `limits.cpu_ms` to 300000, which successfully let a stuck fold
complete once and brought reads back to single-digit seconds.

Today (2026-07-12), continuing the same session's "wire up periodic execution" task
(owner: "1" — periodic kawaraban ingest + cloud-itonami media digest), I built
`kawaraban.run-live-ingest` (a non-interactive, high-water-marked entrypoint) and, to
validate it before wiring a GitHub Actions cron, ran one real full backfill across all 20
verified outlets: **584 articles published** (19 outlets succeeded; `outlet.cbc` hit a
transient `RST_STREAM` network error, correctly isolated without aborting the batch).

Immediately after, the shared `yoro-social-v2` operator graph became **completely
unresponsive** — worse than the addendum-1 incident:

- Direct `kotobase.aozora.app` datoms reads: zero HTTP response at 60s, 90s, 280s, and
  340s timeouts (the addendum-1 incident at least returned a 503/error-1102 within ~49s,
  or a 200 within 129s once the CPU budget was raised).
- `pds.aozora.app` `listRecords` (the AVET-narrowed production read path, NOT the raw
  unbounded `datoms` scan): also unresponsive at 30s. This matters — AVET narrowing only
  accelerates lookups against the **cold** (already-folded) snapshot; all 584 new
  records exist only in **unfolded novelty**, so a read touching any of them still pays
  the full novelty-replay cost regardless of index narrowing.
- `wrangler tail` on `app-aozora-pds` showed the `*/5 * * * *` fold cron firing on
  schedule (15:55, 16:00, 16:05, 16:10 JST) but every single invocation reported
  `"Unknown"` status (never the clean `"Ok"` seen before addendum-1's fix and after its
  first successful fold) — the fold is not completing within its 300000ms CPU budget, on
  every attempt, not as a one-off flake.

## Root cause (confirmed by code inspection, not just symptom-matching)

`kotobase-peer.core/fold!` (`src/kotobase_peer/core.cljc:1452`) is, and always has been,
an **O(total graph size) full rebuild**, not an incremental update — its own docstring
already says so ("Cost: O(graph_shard) — the same full rebuild `snapshot!`/the pre-D1
`commit!` always paid, now amortized"). Addendum 1's fix (bounded `pmap-async` batching +
raised CPU budget) reduced the *concurrency* cost of that rebuild, but did not change its
*total* cost, which scales with **the entire shared graph's size since inception**, not
with how much new novelty a given fold call is trying to compact.

Traced why there is no cheaper path available today:

- `fold!` → `hydrate-db` (`core.cljc:1117`) reads **one full index tree (spo) via
  `cold-datoms` with `(constantly true)`** — i.e. scans and decrypts *every* entry
  currently in the cold snapshot, every single fold call, regardless of how small the new
  novelty batch is.
- `fold!` → `qs/commit!` (`arrangement.core:183`) → `index-root` → `prolly-tree.core/
  build-tree` (`prolly-tree/src/prolly_tree/core.cljc:106`) — `prolly-tree.core` exposes
  exactly three public functions: `build-tree`, `lookup`, `scan-prefix`. **There is no
  incremental insert/update primitive at all.** Every commit of the 4 indexes
  (spo/pso/pos/ocp) rebuilds each tree from its complete, freshly-sorted entry set.
- `yoro-social-v2` is not kawaraban's own graph — it is the **shared cross-actor AppView
  index** (`YORO_DB_NAME`, `YORO_OPERATOR_DID`) that every actor's `createRecord`
  projects into (per `app-aozora-pds/src/aozora/pds/worker.cljc`'s own
  `scheduled-fold` docstring: "every per-actor write projects a firehose-ledger entry
  here"). Its total size is not bounded by kawaraban's write volume alone — it is the
  accumulated history of the whole aozora ecosystem, and grows monotonically over the
  graph's entire lifetime, not just this session's.

The consequence: fold's cost is not a function of "how much novelty is there right now,"
it is a function of "how large has the shared graph become, period" — and that number
only grows. Batching and a higher CPU ceiling (addendum 1) buy headroom, but the design
does not scale, and today's 584-write backfill was enough to exceed even the raised
headroom, on every subsequent attempt (not a transient blip — 4 consecutive cron ticks
all failed to complete).

## Decision

1. **Pause the original "wire up periodic execution" task.** Do not create the planned
   `live-ingest.yml`/`media-digest.yml` GitHub Actions scheduled workflows, and do not run
   any further bulk write batches against `yoro-social-v2`, until fold is fixed. Continued
   periodic writes against an already-unrecoverable backlog would only make the eventual
   fix harder (a larger backlog to fold) without any corresponding benefit, since reads
   are already broken for anything written since the last successful fold.
2. **The real fix (incremental fold) is designed here but deliberately NOT implemented in
   this session** (owner instruction: scope it as its own piece of work rather than rush
   it under time pressure into a foundational shared library three other actors already
   depend on). Design below.
3. `kawaraban.run-live-ingest` and `cloud-itonami.media.batch`'s high-water-mark /
   dedup logic (this session's other deliverable) are NOT reverted — they are still
   correct and necessary (they bound *future* write volume once fold is healthy again);
   they are just not yet wired to run on a schedule.

## Incremental-fold design (for the follow-up implementation)

Two complementary pieces, ordered by how tractable/low-risk they are:

1. **Memoized hydration (small, safe, high-value first increment).** Cache
   `hydrate-db`'s expensive decrypt-and-scan result in R2, keyed by `indexed-cid` (the
   cold snapshot's own content-address — a natural cache key, since it only changes when
   a fold actually succeeds). Every *retry* of a fold against the same still-unfolded
   `indexed-cid` currently re-pays the full hydrate cost from scratch; caching it means
   only the FIRST attempt against a given snapshot pays that cost, and every subsequent
   attempt (this cron tick, or any future one, until the next successful fold) can skip
   straight to applying new novelty on top of the cached hydrated db. This does not by
   itself guarantee the FIRST hydrate completes in one CPU budget, but it stops paying
   that cost redundantly on every failed retry, which is a meaningful chunk of the current
   waste (today's tail showed 4 consecutive failed attempts, i.e. approximately 4x the
   necessary hydrate cost paid for zero forward progress).
2. **Chunked, checkpointed hydration (the real fix for "graph too big for one
   invocation").** Make `hydrate-db`'s underlying `cold-datoms` scan resumable across
   multiple Worker invocations: persist a cursor (last-seen prefix/key position in the
   prolly-tree scan) plus the partially-accumulated decrypted entries to R2 after each
   bounded batch, and have `fold!` check for and resume an in-progress hydration
   checkpoint before starting a fresh one. `prolly_tree.core/scan-prefix` already supports
   prefix-bounded range scans (used today by `cold-datoms`'s indexed reads) — the
   groundwork for a resumable cursor exists; it needs to be exposed and driven across
   invocation boundaries instead of consumed synchronously within one.
3. **Deeper alternative (not recommended to pursue first): a true incremental
   prolly-tree insert primitive** in `prolly-tree.core` (structural-sharing insert of a
   bounded delta into an existing tree, avoiding `build-tree`'s full-rebuild entirely).
   This is the theoretically "correct" fix matching what prolly/Merkle-search trees are
   designed for, but is a genuine data-structure algorithm project across a shared
   library with its own correctness surface — larger scope, higher risk, and not
   necessary if (1)+(2) get fold reliably under the CPU budget for the foreseeable
   future. Worth revisiting only if `yoro-social-v2`'s total size grows enough that even
   chunked hydration across many cron ticks stops keeping pace.

## Consequences

- **No data was lost.** kawaraban's mirror articles and cloud-itonami's one published
  media digest are append-only, content-addressed writes — they exist in R2 regardless of
  whether fold can currently compact them. The problem is *read latency/availability*,
  not durability.
- Production reads against `yoro-social-v2` (via `pds.aozora.app`, including the AVET-
  narrowed `listRecords` path) are currently degraded/unresponsive for any record written
  since the last successful fold (today, that's effectively all 584 of this session's
  backfilled articles plus whatever else other actors wrote in the interim).
- The originally-requested periodic-execution wiring (GitHub Actions cron for kawaraban
  live-ingest and cloud-itonami media-digest) is blocked on this fix landing — tracked as
  a follow-up, not abandoned.
- `kawaraban.aozora/jvm-http-fn` was fixed in passing (this session, same repo) to have an
  explicit 120s request timeout via a shared pooled `HttpClient` — it previously had none
  at all, unlike the fetch-side client, which made a genuinely stuck request
  indistinguishable from a slow-but-progressing one. Kept regardless of this ADR's
  broader fold issue, since it is an independently-correct robustness fix.

## Alternatives considered

- **Keep raising `limits.cpu_ms` further.** Rejected as a durable fix — the graph's total
  size is unbounded and monotonically increasing; any fixed ceiling is eventually
  exceeded again, just later. Useful as a stopgap (as addendum 1 was), not a design.
- **Shard `yoro-social-v2` into smaller per-collection or per-time-window sub-graphs.**
  Not rejected outright, but out of scope here — it changes cross-actor query semantics
  (`getAuthorFeed`/firehose currently rely on one shared index) and is a bigger structural
  change than incremental fold; worth a separate ADR if incremental fold alone proves
  insufficient once actually measured against real growth rates.
- **Implement the full incremental-fold fix right now, in this session.** Rejected per
  owner instruction — this touches `kotobase-peer`/`arrangement`/`prolly-tree`, shared
  foundational libraries with other real consumers (minidrama, tashikame, the AppView
  itself), and deserves proper scoped implementation + testing, not a rushed patch on top
  of an already-long session that has already caused one production incident today.
