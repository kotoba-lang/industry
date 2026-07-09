# ADR-2607050300: Pregel/BSP positioning — three things share a name, only two exist, and they should stay separate

**Status**: accepted
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

"Pregel" and "BSP" appear in at least three places in this org with three
different referents, never previously distinguished in one place:

1. **`kotoba-lang/langgraph`'s `langgraph.graph`** — a `StateGraph`: nodes
   are `Runnable`s (state → partial update), edges static or router-driven,
   execution is an **in-process, single-threaded superstep loop** (its own
   docstring: "execution is a Pregel-style superstep loop with a recursion
   limit, optional checkpointing per superstep, and interrupt-before/after
   for human-in-the-loop... Single-threaded by design (WASM premise)").
   General-purpose agent/workflow orchestration — consumed by kekkai
   (tailnet coordination actor), kagi (vault actor), and other actors per
   the org's `sealed-intelligence ⊣ governor` StateGraph pattern.

2. **`kotoba-lang/utsushi`'s `utsushi.pregel`** — a **deterministic BSP
   executor over a media filtergraph**: nodes are a FIXED set of media
   operations (`demux`/`trim`/`concat`/`decode`/`encode`/`mux`), edges are
   a static acyclic DAG, execution precomputes topological layers once and
   runs one superstep per layer (its own docstring: "④ filtergraph を BSP
   （Pregel 風）superstep で決定論的に実行する"). Per-frame gas/fuel
   accounting bounds total cost; CID-memoized (`transcode`) so identical
   (input, graph) never re-executes. Domain-specific to `utsushi`'s video/
   audio pipeline (ADR-2606272200 §4) — not a general graph executor; the
   node op set is closed (`op-fn`, five ops, no user-supplied `Runnable`).

3. **"kotoba-vm `WasmPregelRunner`/`DistributedPregelRunner`"** — referenced
   in `kotoba-lang/kotoba`'s README (`KOTOBA ≝ … × Pregel[BSP] × Datalog[Δ]`,
   "Distributed Pregel — BSP graph computation across nodes via libp2p") and
   in ADR-2607021800's "Execution model" section ("the kotoba engine runs
   **real Pregel BSP** (`kotoba-vm` `WasmPregelRunner` /
   `DistributedPregelRunner`)... `kotoba-clj` compiles the Clojure subset
   \(incl. `defgraph`\) to WASM and runs it on kotoba-runtime as *compiled
   Clojure agent = langgraph defgraph × kqe datom writes × Pregel BSP*").
   **This does not exist.** The Rust workspace that held it was deleted
   2026-07-01 (`604896171b`, per `kotoba`'s own repository-boundaries
   history); `kotoba-lang/kotoba` today is a small JVM launcher + Chicory
   WASM runtime + in-mem `kgraph` — no VM, no distributed runner, no
   libp2p-borne graph computation. Unlike `quad-store`/`kqe`/`commit-dag`
   (which the DB-crates roadmap, ADR-2607022600, explicitly restored to
   CLJC), **no restoration effort for distributed Pregel was ever started.**
   ADR-2607021800 is dated 2026-07-02 — *written the day after the deletion*
   — and still describes this infrastructure in the present tense; its
   Follow-up #1 ("junbi → kotodama Pregel cell: ... moving the superstep
   loop onto kotoba-vm BSP") depends on it and was already "recorded, not
   landed" even then. Nobody has corrected this since.

## Decision

### 1 and 2 stay separate — this is not an oversight to fix

`langgraph.graph` and `utsushi.pregel` solve genuinely different problems
with genuinely different execution models:

| | langgraph.graph | utsushi.pregel |
|---|---|---|
| frontier | dynamic — recomputed each step from conditional routers | static — all topological layers precomputed once |
| cycles | allowed (recursion-limit bounded) | forbidden (throws) |
| node set | open — any `Runnable` | closed — 5 fixed media ops |
| state model | channel reducers over a shared map | per-node `{:bytes :cid}` outputs, no shared state |
| persistence | checkpointer (resumable, human-in-the-loop) | none (single `run`, memoized by content) |
| cost model | none | gas/fuel per frame, hard limit |

Forcing a shared "BSP runtime" abstraction over these would mean either
langgraph absorbing gas accounting and closed-op-set semantics it doesn't
need, or utsushi absorbing conditional routing and checkpointing it doesn't
need — a premature abstraction serving no actual third consumer. Both
namespaces keep the word "Pregel"/"BSP" in their docstrings because the
*execution shape* (process a batch of ready nodes per step, deterministic
ordering within a step) is a real, shared *idea*, not because they share or
should share *code*. No change to either namespace.

### 3 does not get restored, and the stale claim gets corrected

**Distributed (cross-node) Pregel-style graph computation is not needed
as a literal engine feature**, because the concerns it would have solved
are already served by mechanisms that exist and work today:

- **Cross-node graph replication/sync**: `kotoba-lang/p2p` (graph-sync
  protocol, ADR-2607023200) — gossip + bitswap + commit-dag verify + tag-42
  hydrate, already landed, already tested end-to-end (2/3-node topologies).
- **Horizontal scaling of "the graph"**: per-actor sharding (ADR-2607032430
  D2 — actor = shard = repo = RID = graph = IPNS head), not a single
  monolithic graph computed over by a distributed BSP engine.
- **Fleet-wide compute** (the one place a literal Pregel-across-nodes
  framing might seem to fit, e.g. murakumo): the substrate topology
  (ADR-2607022300) already routes this through `kototama` (resident Pregel
  *cell*, singular — an execution unit, not a distributed graph-computation
  engine) plus fleet-level inference dispatch, not a from-scratch
  distributed-BSP restoration.

Restoring `WasmPregelRunner`/`DistributedPregelRunner` would duplicate
capability that `p2p` + per-actor sharding + `kototama` cells already
provide, via a heavier, unimplemented abstraction. **Decision: do not
restore it.** `kotoba`'s README's `Pregel[BSP]` formula line and
`docs/index.html`'s design-record mentions are left as-is (already
correctly framed elsewhere in `kotoba`'s own docs as historical/removed-
Rust-workspace record, per the investigation this ADR follows from).

**ADR-2607021800 gets a correction addendum** (in place, following this
codebase's own established convention for correcting a prior ADR — see
e.g. ADR-2607022600's "2026-07-02 追記" pattern): its "Execution model"
paragraph and Follow-up #1 describe `kotoba-vm` infrastructure that no
longer exists post-2026-07-01 deletion. The addendum does not retract the
ADR's actual decisions (junbi's architecture, EN/ENGI design) — only flags
that the stated migration path onto "the engine's real BSP" has no engine
to migrate onto, so Follow-up #1 is currently not actionable as written.

## Consequences

- (+) No wasted effort trying to unify two executors that are correctly
  separate; no wasted effort restoring a third that isn't needed.
- (+) A future reader of ADR-2607021800 won't be misled into thinking
  `kotoba-vm`/`WasmPregelRunner` is live infrastructure they can build on.
- (+) The three-way naming collision ("Pregel" meaning three different
  things) is now documented in one place instead of silently confusing
  whoever greps for it next.
- (−) junbi's TreasuryActor genuinely has no designed continuity path onto
  an engine-level BSP anymore (ADR-2607021800's Follow-up #1 is now
  blocked, not just unlanded) — if that capability is still wanted, it
  needs its own from-scratch design, not a "move it onto the existing
  engine" follow-up.

## Follow-up

- If junbi (or any actor) later needs the TreasuryActor-style superstep
  loop to run somewhere other than in-process JVM/WASM, design that as a
  fresh ADR against what actually exists today (`p2p` + per-actor sharding
  + `kototama` cells), not against the deleted `kotoba-vm`.
- ADR-2607021800's own Follow-up #2 (EN datom projection bridge) is
  unaffected by this ADR and remains open on its own terms.

## One-line summary

**`langgraph.graph` (general agent orchestration) and `utsushi.pregel`
(media-filtergraph DAG execution) are both correctly named "Pregel-style"
and correctly separate — different execution models, no shared consumer,
no unification. The third "Pregel" — kotoba-vm's distributed BSP runner —
doesn't exist (deleted with the Rust workspace) and isn't needed (`p2p` +
per-actor sharding + `kototama` cells already cover what it would have
done); ADR-2607021800's still-present-tense description of it is corrected
in place rather than left to mislead the next reader.**
