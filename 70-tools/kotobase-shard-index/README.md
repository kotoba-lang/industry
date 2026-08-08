# kotobase-shard-index

**A search index that a client reads directly out of an object store.** No
query server, no database to hydrate, no cache tier. One immutable object per
content address; the client descends a tree and stops as soon as the answer is
proven.

This is the read path from the Google-scale analysis in
[ADR-2608071500](../../90-docs/adr/2608071500-kotobase-client-side-shard-index-on-object-storage.edn),
built and measured rather than described.

## Why it is not the deployed path

kotobase.net answers a query in ~2.5s, and **92% of that is hydration** —
rebuilding a database value before the query starts (ADR-2607310900 訂正3).
The sequential part of hydration is walking the unfolded novelty cons-chain,
**97% of the round trips** at the fold threshold (ADR-2608021000). Both are
properties of "materialise the database, then query it", and neither exists
here: nothing is materialised, and a client only ever reads published blocks.

The cost model changes with it. `arrangement`'s measured hydrate is linear in
the size of the *database* — 57ms/50 block-reads at 2k facts, 678ms/640 at 32k,
however few rows come back. Here a query's GET count tracks the *answer*.

## Shape

```
manifest  (one CID -- the only mutable pointer in the system)
 └── shard[i]
      ├── dict-root ──> dict-internal ──> dict-leaf      term -> posting head
      │                                    (fanout B, height = log_B(terms))
      ├── posting-head ──> posting-chunk...              impact-ordered
      └── meta-dir     ──> meta-chunk...                 url/title for display
```

Every block is addressed by the sha-256 of its canonical bytes, so a block that
differs is a different key. That is what makes `Cache-Control: immutable`
correct without an invalidation path, and what lets an untrusted store be
verified by the reader (`block/verify-block`).

## Why one manifest, and not one ref per shard

The workspace rule that a query plane reaches exactly one ref
(ADR-260726) is about **atomicity**: two refs advance independently, so a
query spanning both has no single basis and can return a state that never
existed. It is not a limit on how much data one ref addresses.

So the shards live *under* one manifest. Readers get one consistent snapshot
(one manifest CID pins every shard); writers get parallelism, because shard
segments are built independently and only the manifest commit is serialised.
The usual "query reach versus write parallelism" trade-off does not apply,
because the thing being serialised is a pointer swap per publish rather than a
head CAS per write.

## Exactness

Postings are impact-ordered, so a document missing from the prefix of a term's
list cannot score above that term's next unread chunk. Every candidate
therefore has a lower bound (what has been summed) and an upper bound (plus
its unseen terms' thresholds); an unseen document has the shard's threshold
sum. The scan stops when those separate.

**Exact by score.** The k scores returned are the k largest, and each is the
document's complete score. **Ties are unspecified** — which of several
equal-scoring documents is returned, and in what order, is not determined. The
bound reasons about scores and cannot see the doc-id tie-break; forcing it to
would mean reading every chunk whose max-impact equals the k-th score.

This limit is stated because the first version of this code silently claimed
more, and `test/run_tests.cljs` caught it by checking the bounded scan against
a full scan of the same index.

That test is the reason to believe the rest — but only after it was made
capable of failing. Mutation testing showed the original version passed with
the bound understated 4x and with the unseen-document term removed entirely:
the corpora were small enough that one prefetch wave consumed every posting
list, so every query ended `:exhausted` and the bound was never consulted. The
suite now includes configurations where stopping early is a real decision, and
asserts that `:proof :bounded` actually occurs. Both mutations fail it now.

## Measured

`bench/results/2026-08-07-get-scaling.edn`, corpus grown 64x (1,000 → 64,000):

| shard count | index blocks | mean GETs/query | waves |
|---|---|---|---|
| **fixed at 8** | 39,310 → 59,990 | **77.4 → 76.0 (0.98x)** | 9 → 11 |
| **grown 1 → 32** | 7,268 → 175,116 | **19.8 → 240.8 (12.2x)** | 8 → 15 |

Per-shard cost is flat in corpus size. All of the growth is fan-out — every
shard is asked every query. So shard count should follow write parallelism and
object size, not corpus size; and client-side fan-out, not index size, is what
bounds this design.

## The routing dictionary — `route.cljc`

The fan-out above is what put the honest reach at 10⁹–10¹⁰ pages. One
dictionary for the whole index removes most of it
(`bench/results/2026-08-08-route-scaling.edn`, ADR-2608085000):

    term -> [{:shard :max-impact :df :postings} ...]

Locating a term costs one descent instead of one per shard, and a shard whose
per-term maxima cannot reach the k-th score is skipped without being opened.
Same corpus, same queries, both read paths on one index:

| corpus | docs / shards | v1 GETs | route GETs | |
|---|---|---|---|---|
| uniform | 64,000 / 32 | 240.8 | **77.6** | fan-out growth 12.2x → **3.9x** |
| uniform | 16,000 / 8 | 72.8 | **39.2** | descent only — nothing was pruned |
| clustered | 64,000 / 32 | 150.6 | **20.4** | 30.8 of 32 shards never became candidates |

Two mechanisms, and the stats report both because a corpus picks between them:
`total → considered` drops shards that hold no query term (free — the routing
entry does not list them), `considered → opened` drops shards whose bound
cannot win (pruning). A topical corpus collapses at the first step, a uniform
one at the second.

`:shard-batch` is the GETs-versus-waves dial. Opening one shard per wave is
GET-optimal and depth-pessimal — 70.6 GETs in 70 waves at 64k/32, against 77.6
in 18 at the default of 8. Answers are identical at every setting, which the
suite checks.

Index cost: blocks ×1.00–1.02, bytes ×1.18–1.32.

## Run

```bash
npm install --no-save nbb@1.4.208

# tests (25 assertions, including the exactness oracle)
npx nbb --classpath 70-tools/kotobase-shard-index/src \
  70-tools/kotobase-shard-index/test/run_tests.cljs

# GET-scaling receipt -> bench/results/
npx nbb --classpath 70-tools/kotobase-shard-index/src \
  70-tools/kotobase-shard-index/bench/get_scaling.cljs
```

## Use

```clojure
(require '[kotobase-shard-index.build :as build]
         '[kotobase-shard-index.query :as query]
         '[kotobase-shard-index.node :as node])

;; build side -- runs where the crawler runs
(def store (node/fs-store "/tmp/index"))          ; or a bucket
(def m (build/build! store node/sha256-hex docs {:shard-count 16}))

;; read side -- runs in the client, touches nothing but the store
(query/search store (:manifest-cid m) "検索 エンジン" {:k 10})
```

## Where this is staged, and where it belongs

This subsystem lives in `com-junkawasaki/root` because that is the only
repository this session had write access to. It is a `kotobase-` family
subject and belongs in its own `kotoba-lang` repo, registered through
`manifest/repos.edn` + `gen-west-manifest.cljs --entry`. Moving it is a named
follow-up, not something to leave implied.

## Known gaps

- **Content address is not a CIDv1.** `b` + sha-256 hex, not multiformats.
  `kotoba-lang/io-multiformats` exists and adopting it is follow-up work.
- **Range GET is implemented but unused by the query path.** Blocks are fetched
  whole. Sub-block ranges cut bytes, not round trips, and round trips are what
  the design is bounded by — so this is deliberate ordering, not an oversight.
- **No `IPatternSource` adapter, on purpose.** Posting lists are bytes and
  belong on the block plane, not the datom plane (ADR-2608039970). The
  `datom-source` seam is the right home for the manifest/metadata plane, which
  this subsystem does not yet expose.
- **Single-writer publish is assumed, not enforced.** There is no ref plane
  here at all; `build!` returns a manifest CID and says nothing about how it is
  published. That is the `inga` quorum boundary and is out of scope.
- **No incremental build.** `build!` rebuilds from the whole corpus. Segment
  merge (add a shard's new segment, rewrite only the manifest) is the next
  thing to write.
- **Fan-out ceiling.** Every shard is asked every query, so client-side fan-out
  is the real scale limit — practical to ~10² shards, which is why the honest
  ceiling of this design is 10⁹–10¹⁰ documents rather than 10¹¹.
