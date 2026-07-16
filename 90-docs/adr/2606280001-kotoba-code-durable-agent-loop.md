# ADR-2606280001: kotoba code durable agent loop

- **Status**: accepted
- **Date**: 2026-06-28
- **Context tags**: kotoba-code, clj, cljc, langgraph-clj, durable-agent-loop, checkpoint, datomic, kotoba
- **Related**: `orgs/kawasakijun/docs/adr/0013-clj-agent-stack.md`, `orgs/kotoba-lang/langgraph/docs/adr/0001-architecture.md`

## Context

Claude Code style agents can run for a long time: inspect repository state,
choose tools, edit, test, checkpoint, recover after process death, and continue.
The existing `langgraph-clj` rule says `1 run = 1 operation` and avoids unbounded
internal loops. That rule is still correct for portable `.cljc` code and WASM
hosts, but it is not enough for kotoba code when the product requirement is a
long-duration agent.

## Decision

Design kotoba code as a **durable outer loop over bounded StateGraph runs**.
The inner graph remains finite and deterministic enough to checkpoint. The
outer loop is a host/runtime concern that repeatedly claims work, runs one
bounded graph segment, persists facts, and decides whether to continue.

```
lease/tick -> load checkpoint -> run bounded StateGraph segment
           -> persist events/checkpoint/budget -> governor decision
           -> continue | sleep | interrupt | stop
```

### 1. Two loop levels

- **Inner loop**: `langgraph-clj` ReAct / StateGraph execution. It has
  `:recursion-limit`, `interrupt-before/after`, and checkpointer support.
- **Outer loop**: kotoba code durable supervisor. It owns cadence, leases,
  crash recovery, budget windows, model/tool health, and long-term continuation.

The outer loop may run for hours or days, but every tick is a small auditable
transaction. No agent relies on process memory for correctness.

### 2. Durable state model

Every durable loop must persist these datom families:

| fact | purpose |
|---|---|
| `:agent.loop/id`, `:agent.loop/status` | active / sleeping / interrupted / stopped |
| `:agent.tick/id`, `:agent.tick/loop`, `:agent.tick/seq` | monotonic tick history |
| `:agent.lease/owner`, `:agent.lease/expires-at` | single active worker per loop |
| `:checkpoint/*` | graph state, frontier, step, status |
| `:agent.budget/*` | token, wall-clock, tool-call, spend, and retry budgets |
| `:agent.event/*` | model calls, tool calls, edits, tests, errors, observations |
| `:agent.governor/*` | allow / hold / require-human / stop decisions |

The checkpoint remains compatible with `langgraph.checkpoint`. Extra loop facts
are layered around it rather than embedded into opaque blobs.

### 3. Safety invariants

- A tick must be idempotent or have an idempotency key before performing an
  external effect.
- All external effects are tool calls with typed inputs, captured outputs, and
  persisted receipts.
- The governor is evaluated at every tick boundary and before privileged tools.
- Budgets are enforced by the outer loop, not trusted to the model.
- A stale lease may be stolen only after expiry and a recovery event is written.
- Human approval uses `interrupt-before` or an outer-loop `:require-human`
  decision, never an unbounded wait inside `.cljc` graph code.

### 4. Host boundary

Portable `.cljc` libraries still do not own clocks, threads, sleeps, process
spawning, network, file system, or real browser/desktop drivers. The host
injects those capabilities and drives the durable supervisor. This keeps the
same code runnable on JVM, SCI, CLJS, and kotoba-clj/WASM.

### 5. Claude Code compatibility

Claude Code style behavior is represented as a graph plus durable tools:

- repository observation: `read-file`, `rg`, `git-status`, `test-result`
- modification: `apply-patch`, formatter, dependency update
- verification: test/lint/build tools
- publishing: commit, push, PR tools behind explicit policies

The model sees the current state and emits tool calls, but the durable loop
decides tick boundaries, retries, leases, and stop conditions.

## Consequences

- Long-running kotoba code agents are allowed by design without weakening the
  existing `1 run = 1 operation` discipline.
- Crash recovery is a first-class behavior: restart from the latest checkpoint
  and loop lease, not from chat memory.
- Audit and replay stay Datalog-native because loop, checkpoint, event, budget,
  and governor facts are all datoms.
- The remaining implementation work is a small host-side durable supervisor and
  a standard schema/tool pack; the existing `langgraph-clj` inner loop does not
  need to become an unbounded process loop.
