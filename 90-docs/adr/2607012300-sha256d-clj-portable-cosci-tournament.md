# ADR-2607012300: sha256d-clj — portable .cljc SHA-256/SHA-256d + a Google-co-scientist/AlphaEvolve-shaped evolutionary benchmark tournament

## Status
Accepted

## Context

Owner request: design a repo, in `.cljc`, that efficiently derives Bitcoin's SHA-256,
taking a reference case study (`matrixflow.net/case-study/110`) and a "Google
co-scientist" approach as inspiration.

The referenced case study turned out **not** to be about SHA-256 or Bitcoin at all --
`WebFetch` on it showed it covers Google DeepMind's AlphaProof / Gemini Deep Think /
AlphaEvolve / FunSearch line of work (LLM + evolutionary-search systems that discover
genuinely new algorithms; AlphaEvolve's headline result is a 48-multiplication
algorithm for 4x4 matrix multiplication, beating Strassen's 56-year-old bound). "Google
co-scientist" is a second, related but distinct Google DeepMind system: a six-agent
architecture (Generation / Reflection / Ranking / Evolution / Proximity / Meta-review,
under a Supervisor) that runs a tournament-of-ideas over scientific hypotheses using
pairwise Elo ranking, drawn from AlphaGo's competitive-ranking lineage.

Both systems share a search shape (maintain a population of candidates, verify/score
each, keep and recombine the best) that transfers to SHA-256 -- but with one
constraint neither AlphaEvolve's matrix-multiplication search nor co-scientist's
hypothesis tournament has: **for a hash function, correctness is binary, not a
score.** A candidate that's 99% close to SHA-256 on some inputs isn't a slightly-worse
SHA-256, it isn't SHA-256 at all. This reframing -- keep the search *shape*, make
correctness a hard gate rather than a fitness term -- is this ADR's central design
decision, confirmed with the owner (`AskUserQuestion`) before implementation: a
self-contained deterministic search (no LLM/Workflow calls needed at run time) over a
hand-verified gene pool, executed as a real, immediately-runnable repo, rather than a
one-off `Workflow` invocation.

Placement: per `manifest/repos.edn`'s org-routing rule ("cross-cutting 基盤 -clj →
com-junkawasaki"), this is `orgs/com-junkawasaki/sha256d-clj`, not a kotoba-lang
language-substrate lib. `orgs/kotoba-lang/eth-crypto` was checked first for an existing
raw SHA-256 implementation to avoid duplication -- it has none (its `hmac-sha256` is a
private helper for RFC 6979 ECDSA nonce derivation, not a public from-scratch SHA-256).

**Concurrent, related, and NOT overlapping in practice**: while this repo was being
built, ADR-2607012230 landed (`kotoba-lang/btc-crypto` + `kotoba-lang/btc-mining` +
`kotoba-lang/mining-pool` + `kotoba-lang/wallet`), which also touches SHA-256d and
Bitcoin block-header mining. The two are complementary rather than duplicate: that
stack's SHA-256 comes from `kotoba-lang/crypto`, a data contract with a **host-injected**
cipher (a vetted platform crypto implementation, for production wallet/mining-pool
use) -- there is no algorithm-level surface to search or benchmark inside a host crypto
API black box. This repo is deliberately the opposite: a from-scratch, host-crypto-free,
algorithm-level implementation, specifically so its internal formula/implementation-
strategy space is actually searchable and benchmarkable. If `kotoba-lang/crypto` ever
wants a pure-`.cljc` backend option (no host dependency, e.g. for a WASM target without
a crypto API), `sha256d.core` here is a candidate to reuse; that integration is
out of scope for this ADR.

## Decision

New repo `orgs/com-junkawasaki/sha256d-clj` (GitHub, private, `com-junkawasaki` org):

- **`sha256d.core`** -- FIPS 180-4 SHA-256 + `sha256d` (`sha256(sha256(x))`), over plain
  sequences of byte values (ints 0-255), no host byte-array type in the hot path.
  `compress`/`sha256-bytes`/`sha256d-bytes` take `ch-fn`/`maj-fn` as an explicit seam
  (2-arity overloads default to this namespace's own `ch`/`maj`), so alternative
  primitive formulations run through the exact same padding/schedule/compression
  pipeline without duplicating it. This is the correctness oracle everything else in
  the repo is checked against.
- **`sha256d.ops`** -- the gene pool: `ch-alt` (`z ^ (x & (y^z))`, one fewer gate than
  the FIPS textbook form) and `maj-alt` (`(x&y) | (z&(x|y))`), both real-world
  formulations (OpenSSL/Bitcoin Core's C code uses them), each proven algebraically
  equivalent to the reference primitive in a doc-comment and re-checked exhaustively
  (all 8 single-bit truth-table rows + 2000 randomized 32-bit words per primitive) in
  `test/sha256d/ops_test.cljc`.
- **`sha256d.midstate`** -- Bitcoin block-header mining's classic optimization: an
  80-byte header pads to exactly two 64-byte blocks; the first block (version +
  prev-hash + a 36-byte merkle-root prefix) is constant while a miner iterates the
  nonce for a fixed template, so caching the compression state after it (the
  "midstate") skips 64 rounds of the *inner* SHA-256 per nonce attempt. Verified via
  property test (500+200 random headers, `header-hash` via cached midstate == the
  no-caching reference on every one) plus a fixture: a synthetic (explicitly
  **not** the real genesis block, to avoid hand-transcribing a value from memory) 80-byte
  header whose SHA-256d was computed independently via Python's `hashlib`, not
  hand-derived.
- **`sha256d.evolve`** -- the tournament: Generation (enumerate gene combinations) ->
  Reflection (hard correctness gate: every candidate must match `sha256d.core`'s output
  on 8 representative messages spanning the padding boundaries, or it's disqualified
  outright, never merely down-scored) -> Ranking (pairwise benchmark, within-1%-is-a-draw,
  Elo update starting at 1000, mirroring co-scientist's tournament-of-ideas) ->
  Proximity (cluster ranked results within 1% so Meta-review reports ties honestly
  instead of a spurious single winner) -> Evolution (recombine elite gene choices for
  the next generation) -> Meta-review, under a `run-tournament` Supervisor.
- Portability proof, not just claim: `test/sha256d/cljs_verify.cljs`, compiled via
  `clojure -M:cljs` and run under `node` (mirroring `com-junkawasaki/num-clj`'s
  `cljs-verify` pattern). This actually caught a real bug during development (see
  Consequences) that the JVM suite alone did not.
- Standard scaffold: `deps.edn` (zero third-party deps for the core; `cognitect
  test-runner` for `:test`; `org.clojure/clojurescript` for `:cljs`), `README.md`,
  `.gitignore`, `.github/workflows/ci.yml` (JDK 17/21 matrix `clojure -M:test` +
  a `cljs-portability` job running the node proof), `docs/evolution-log.md`
  (append-only tournament findings).

## Consequences

- **All test vectors are independently computed, not hand-transcribed.** Every hex
  literal in `test/sha256d/core_test.cljc` and the midstate fixture was generated with
  Python's `hashlib`/the system `shasum` and cross-checked by exact string length
  before use, after a live near-miss during authoring: a by-hand transcription of the
  well-known "1,000,000 x 'a'" NIST vector was one hex character short, caught only by
  comparing against a freshly-computed value rather than trusting memory a second time.
  The lesson generalizes: never hand-type a cryptographic ground-truth constant into a
  test; always regenerate it from an independent, trusted tool.
- **The cljs proof caught a real portability bug the JVM suite could not.**
  `sha256d.core/pad`'s original 64-bit big-endian length-field encoding shifted a
  JVM `long` by up to 56 bits, which is valid there (`unsigned-bit-shift-right` on a
  64-bit `long`) but silently wrong under ClojureScript: JS bitwise operators are
  32-bit only and mask any shift count to its low 5 bits (`x >>> 40` behaves as
  `x >>> 8`), corrupting the length field for any input needing shifts past bit 31.
  Fixed by splitting the 64-bit value into two 32-bit halves via `quot`/`mod` (portable
  arithmetic, not shifting past bit 31) in `sha256d.core/u64be-bytes`. All 14 JVM tests
  (5144 assertions) and all 5 cljs checks pass after the fix. This is the concrete
  reason `.cljc` claims of portability in this codebase should be proven by actually
  compiling+running under cljs, not inferred from "it's bitwise code, should be fine."
- **The tournament's first real run is an honest negative result, not a discovery.**
  `docs/evolution-log.md` records three `clojure -M:evolve` runs: no stable champion
  (2 of 3 runs land in a single 1%-proximity cluster -- Ranking itself found no
  significant difference), and a generation-over-generation diversity-loss pattern in
  `evolve-round` (recombination only reintroduces gene values already present among
  elites, so a gene collapses to one variant for the rest of a run once both elites
  happen to agree on it -- itself mostly driven by the benchmark noise in the first
  finding, not real selective pressure). Both are logged as explicit follow-ups rather
  than smoothed over.
- **West registration.** `manifest/repos.edn` `:extra-projects` gained one entry;
  `manifest/west.yml` gained the matching one entry, hand-inserted rather than via a
  full `bb scripts/gen-west-manifest.bb` regen, because `--check` was already STALE
  before this change (pre-existing, unrelated child-repo pin drift from concurrent
  activity) -- a full regen+commit would have bundled that unrelated drift into this
  registration. The hand-inserted entry was verified byte-for-byte identical to what
  the generator itself produces for this path (regenerated to a scratch copy, diffed,
  reverted, matched) before being committed, and `west update sha256d-clj` resolves
  and checks out the pinned revision (`847da43...`) cleanly. `origin/main` showed a
  `(forced update)` warning + `unrelated histories` on fetch/merge during this session;
  `gh api compare` confirmed it was the known shallow-clone false-positive (`status:
  ahead, behind_by: 0, merge_base_commit == old local tip`), resolved via
  `git fetch --deepen=20` per CLAUDE.md, not treated as a real force-push.

## Alternatives

- **A real `Workflow`-orchestrated multi-agent loop** (literal Generation/Reflection/
  Ranking/Evolution/Proximity/Meta-review subagents proposing and verifying code diffs
  each run, more faithful to AlphaEvolve/co-scientist) -- rejected for this pass per
  owner's explicit choice (`AskUserQuestion`): it costs tokens on every run and needs
  Workflow opt-in, versus a self-contained deterministic harness that ships in the repo
  and needs no LLM to operate. Left as a documented possible follow-up, not implemented.
- **Scoring correctness as a fitness term instead of a hard gate** (closer to how
  AlphaEvolve scores an imperfect matrix-multiplication algorithm) -- rejected: SHA-256
  has no meaningful notion of "partial credit," so this would just be a more confusing
  way to implement the same disqualify-on-mismatch behavior.
- **Hand-deriving the real Bitcoin genesis block header as a test fixture** -- rejected
  in favor of a clearly-labeled synthetic header cross-checked via Python `hashlib`,
  specifically to avoid the same class of transcription risk the million-`a` vector
  near-miss demonstrated, for a 160-hex-character value with no independent local
  oracle to check it against at authoring time.

## Follow-ups (explicitly out of scope here)

- Grow `sha256d.ops`'s gene pool (loop-unrolling degree, message-schedule buffer reuse,
  batch/lane-parallel hashing) -- today's search space (2 primitives x 2 variants) is
  small enough that Ranking's findings are dominated by benchmark noise, per the
  evolution-log's first entry.
- Run `sha256d.evolve/run-tournament` under node, not just the JVM (the cljs
  portability proof covers `core`/`ops`/`midstate` correctness, not the tournament
  itself).
- If desired, offer `sha256d.core` as an alternative host-crypto-free backend option for
  `kotoba-lang/crypto`'s SHA-256 contract (see Context) -- not attempted here.

## Addendum (2026-07-02): the full 17-round search, mining, real-genesis validation, and the cryptanalysis pivot

The initial scaffold above was iterated as a running co-scientist tournament (17 rounds, all in
`orgs/com-junkawasaki/sha256d-clj/docs/evolution-log.md`, which has a summary table at its top).
Every result is bit-identical-gated and, where a speedup is claimed, measured. Net outcome:

**Forward direction (efficient derivation).** The tournament *rejected* four plausible optimizations
after measuring them -- Ch/Maj round-primitive formula (noise on JVM & V8, boxed & unboxed),
schedule data structure (`:rolling` ~7-10% slower, `:precompute-transient`/`:mutable` tie the
reference: even a zero-allocation `long-array` schedule does not beat the persistent-vector
precompute), and software 2-way multi-buffer interleave (~6% slower: register spills > ILP gain).
The one lever that moved single-hash throughput was **removing per-operation runtime overhead**:
`compress-primitive-inline` (JVM, unboxed round loop + inlined ch/maj) ~2.7x; `compress-v8-inline`
(cljs, `Int32Array` + fixed-arity int32 arithmetic) ~3.6x -- each a JVM- resp. cljs-only opt-in
fast path, with the portable `compress` staying the default. Portable *relative* verdicts were
confirmed to reproduce on V8 (round 8); the parallel nonce-search plateau (~4.6x on 10 cores) was
diagnosed (round 14) as all-core turbo frequency scaling, NOT GC/allocation (a cheap GC-MXBean +
all-cores-hot diagnostic refuted the allocation hypothesis and correctly stopped an allocation-free
path from being built). Allocation never mattered anywhere -- single-thread or parallel.

**Mining.** `sha256d.midstate` composes with the fast path via `header-hash-with`/`search-nonce`/
`search-nonce-parallel` for ~4.4x per-nonce over naive full-header hashing (1.59x midstate x 2.78x
fast compress, stacking cleanly), ~272k nonce/s on 10 cores, all bit-identical.

**Real-world grounding.** The whole stack (core sha256d + midstate + fast path) was validated
against the **actual Bitcoin genesis block** (block 0, hash `000000000019d668...`) on JVM and V8,
and `search-nonce` recovers Satoshi's real genesis nonce 2083236893 -- the first non-synthetic
validation, with fixtures independently verified via Python hashlib.

**Cryptanalysis pivot (inverse direction).** The owner then redirected from *forward* optimization to
the *inverse* problem: use the co-scientist approach to invert SHA-256 (find a preimage) below the
2^256 brute-force bound. `sha256d.mitm` (see `docs/preimage-mitm-cosci.md`) delivers the honest
answer:
- Below-brute-force preimage algorithms are real (meet-in-the-middle / splice-and-cut / biclique).
  The co-scientist search *designs* them by finding neutral-word splits over the message-schedule
  dependency graph, and `run-mitm` **runs one on the real 32-bit round function**: 8-round SHA-256,
  a partial preimage found in 2,049 compression-chunk evals vs brute force's 2,325,617 -- a measured,
  verified **~1135x** speedup (the MITM square root, on real rounds, not a toy cipher).
- It *locates the wall*: word-granularity MITM dies at ~24 rounds when the message expansion's
  dependency fan-out makes every base word feed both chunks. Bit-level neutral bits give NO gain over
  word-level under sound analysis (tested): a bit of W_i is neutral for a chunk iff the whole word is
  unused. The published ~45-round / 2^255.5 record needs bicliques (differential trails), which this
  repo describes but does **not** fabricate a complexity for.
- **Full 64-round SHA-256 stays unbroken by any meaningful margin, and this repo does not fabricate a
  break.** The honest reasoning was recorded (including why an AlphaProof-Nexus-style formal-proof AI
  cannot help either: a fast inversion is conjectured false so no proof can be searched for it, and
  one-wayness is a circuit lower bound beyond known mathematics that no prover can establish -- SHA-256
  sits in the deliberate gap between provably-true and provably-false).

**Defensive risk framing** (discussed, not yet doc'd): full-round preimage break risk is a negligible
tail (best attack 2^255.5 on 45/64 rounds; ~17 years of zero full-round margin; Grover only reaches
2^128, sequential/infeasible); the real practical risks are implementation/misuse (length-extension ->
HMAC, low-entropy -> KDF, non-constant-time compare); AI-assisted formal/automated cryptanalysis
(AlphaProof/co-scientist style) is a monitoring signal that may *accelerate reduced-round records* but
does not change the full-round tail risk. A `docs/sha256-preimage-risk.md` capturing this is an open
follow-up.

**Shipped API:** `compress` (portable reference) · `compress-primitive-inline` (JVM ~2.7x) ·
`compress-v8-inline` (V8 ~3.6x) · `midstate`/`header-hash`/`search-nonce`/`search-nonce-parallel` ·
`mitm` (reduced-round preimage-attack search + runnable demo). Correctness gated throughout
(clojure -M:test: 29 tests / 8000 assertions; cljs-verify 8/8), verified against NIST vectors and the
real Bitcoin genesis block.
