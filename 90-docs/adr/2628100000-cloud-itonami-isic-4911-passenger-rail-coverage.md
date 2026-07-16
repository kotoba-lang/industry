# ADR-2628100000: cloud-itonami-isic-4911 (Passenger rail transport, interurban) operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607102200 (cloud-itonami-isic-4911 open business blueprint,
original publish), skill `build-actor` (advisor/governor/StateGraph/
audit-ledger actor pattern), `cloud-itonami-isic-4920` (Community Freight
Transport, verified sibling actor whose Store/Governor/Phase/Operation
module shape this build mirrors).

## Context

ISIC class `4911` = passenger rail transport, interurban (scheduled
interurban/regional passenger rail service). Before any work began, the
live `kotoba-lang/industry` registry entry for `"4911"` was independently
verified fresh (clone of `kotoba-lang/industry`, not assumed from the
task's own premise): its `:name` field reads `"Passenger rail transport,
interurban"`, an exact, untruncated match.

Unlike most `cloud-itonami-isic-*` promotions in this fleet, this was
**NOT a fresh scaffold**. `gh api repos/cloud-itonami/cloud-itonami-isic-4911`
confirmed a pre-existing repository already published at `:blueprint`
tier: `CODE_OF_CONDUCT.md` / `CONTRIBUTING.md` / `GOVERNANCE.md` /
`LICENSE` / `README.md` / `SECURITY.md` / `blueprint.edn` / `docs/`
(`operator-guide.md`, `business-model.md`) only — no `deps.edn`, no
`src/`, no `test/` — from an earlier bulk-scaffolding pass (commit
`78c9023`, 2026-07-09). This work **added** the missing implementation
module set on top of that existing boilerplate rather than re-scaffolding
the repository; the pre-existing docs were kept, not recreated or
rewritten.

This is Wave-2-shaped coordination/logistics work, but passenger rail
carries an obvious direct-passenger-safety dimension distinct from a pure
logistics/coordination vertical: a rail operator's dispatch and
signal-safety authority is a wholly separate, certified system of record
that this actor must never be able to touch, even indirectly. Per this
fleet's established person-facing-service guardrail (mirrored from
ADR-2607152500's Wave 4 rule, applied here proactively because the same
underlying hazard exists), the closed op allowlist must never include an
op that directly finalizes a passenger-safety-authority decision (e.g.
overriding a signal/dispatch-safety interlock, clearing a train for
departure after a reported fault) — those are always either a hard
permanent block or an always-escalate op, never auto-commit-eligible; any
"flag a concern" op must always escalate to human sign-off and must never
appear in any phase's `:auto` set.

This build also had to defend against a bug class multiple sibling agents
in this fleet have independently discovered and fixed: a governor's own
scope-exclusion term list phrased as a bare noun (e.g. "safety",
"dispatch", "signal") can accidentally match inside the mock advisor's
own default rationale/disclaimer text for a legitimate, allowed proposal
— self-blocking the actor on its own happy path. This risk is especially
acute here because `:flag-passenger-safety-concern`'s entire legitimate
purpose is to talk *about* signal-fault/platform-hazard/incident topics
using exactly the words a naive bare-noun list would ban, and this
actor's own default rationale text routinely explains "this actor never
overrides a signal interlock" using those same words as a disclaimer.

## Decision

Fill in `cloud-itonami-isic-4911` with a `railops.*` module set (Store,
Registry, Governor, Phase, Advisor, Operation, Sim), mirroring
`cloud-itonami-isic-4920`'s (Community Freight Transport)
Store/Governor/Phase/Operation shape, adapted from "freight
dispatch/settlement" to "interurban passenger-rail OPERATIONS
COORDINATION" — explicitly **not** direct dispatch/signal-safety
authority or rolling-stock control:

- **`railops.store`** — `Store` protocol + `MemStore` + `DatomicStore`
  (`langchain.db`-backed), contract-tested for parity. The unit of work
  is a `service` (one scheduled interurban passenger-rail service run: a
  route + timetable slot + consist). `:route-schedule-registered?` is an
  independently-set flag — no op/effect anywhere in this actor's closed
  allowlist can ever set it; only `with-services`/`seed-db` (demo
  seeding / an external registration feed) can, so this actor can never
  self-certify the precondition its own governor gates on.
- **`railops.registry`** — pure-function draft-record construction for
  all four ops: service-log drafts (`LOG-######`), schedule/consist
  operation proposal drafts (`SCH-######`), passenger-safety-concern
  flags (`SFC-######`, always `requires_human_review`/`advisory_only`
  true), and maintenance-coordination drafts (`MNT-######`). Every
  certificate is UNSIGNED — this actor never issues an authoritative
  record itself.
- **`railops.governor`** (Rail Safety Governor, matching
  `blueprint.edn`'s pre-existing `:itonami.blueprint/governor
  :rail-safety-governor`) — four HARD checks, all permanent and
  un-overridable by any human approval:
  1. `op-not-allowlisted` — the proposal's `:op` is outside the closed
     four-op allowlist (`log-service-record`/`schedule-service-operation`/
     `flag-passenger-safety-concern`/`coordinate-maintenance`).
  2. `dispatch-safety-override-blocked` — the proposal's `:op` is a
     forbidden finalize op (`:finalize-dispatch-safety-override`/
     `:clear-train-for-departure`/`:override-signal-interlock`/
     `:authorize-departure-despite-fault`), OR its own
     rationale/summary/cites text names one of the forbidden
     finalization ACTIONS. **Self-trip discipline**: scope-exclusion
     phrases are phrased as the finalization/execution ACTION —
     `"finalize the dispatch-safety override"`, `"clear the train for
     departure"`, `"override the signal interlock"`, `"authorize
     departure despite fault"`, `"bypass the dispatch-safety
     interlock"` — never a bare noun ("safety"/"dispatch"/"signal"
     alone). A dedicated regression test
     (`mock-advisor-defaults-never-self-trip-scope-exclusion`) asserts
     all four default proposal generators, for a clean, verified
     service, never trip this check and never HARD-hold; a further
     end-to-end test (`end-to-end-dispatch-safety-override-attempt-is-held`)
     proves a compromised/malicious advisor proposing the forbidden
     phrase on an otherwise-allowed op is still caught by the full
     actor graph, not merely by the unit-level governor check.
  3. `effect-not-propose` — every proposal's `:effect` must literally be
     `:propose`; this actor never emits an effect that would read as a
     direct mutation of a real dispatch/signalling/rolling-stock system.
  4. `route-schedule-not-verified` — the target service's own
     `:route-schedule-registered?` must be independently true in the
     store. Applies to **all four ops**, not only actuation-shaped ones
     — this actor never coordinates around a service whose own
     route/schedule record is unverified.

  ESCALATE (always human sign-off, soft gate): `:flag-passenger-safety-
  concern` carries a dedicated `:safety-concern/flag` high-stakes stake
  that always escalates, independently confirmed by `railops.phase`
  never including it in any phase's `:auto` set (two independent
  layers); low confidence (< 0.6) also escalates.
- **`railops.phase`** — 0→3 rollout: phase 0 read-only; phase 1 enables
  `log-service-record`/`flag-passenger-safety-concern` (approval-gated)
  — the safety-concern flag is deliberately writable from the
  *earliest* assisted phase so a rollout-phase gate is never the reason
  a passenger-safety concern cannot be surfaced; phase 2 adds
  `schedule-service-operation`/`coordinate-maintenance`, still
  approval-gated; phase 3 auto-commits only `log-service-record` (no
  capital/safety risk — a data-logging record only) when governor-clean;
  `schedule-service-operation`/`flag-passenger-safety-concern`/
  `coordinate-maintenance` NEVER auto-commit, at any phase.
- **`railops.railopsllm`** — mock `Advisor` (deterministic, offline);
  seam ready for a real LLM swap (`llm-advisor`), not wired in this ADR.
- **`railops.operation`** — real `langgraph-clj` `StateGraph`
  (intake → advise → govern → decide → commit|hold|request-approval),
  `interrupt-before #{:request-approval}` for human-in-the-loop resume,
  not a stub.
- **`railops.sim`** — demo runner (`clojure -M:dev:run`) walking the
  happy path for all four ops, the always-escalate safety-concern flow,
  an unverified-route-schedule HARD hold, an out-of-allowlist op HARD
  hold, and a direct governor-level dispatch-safety-override-block
  demonstration.
- Tests: `store_contract_test`, `registry_test`, `phase_test`,
  `governor_contract_test` (including the mandatory self-trip
  regression and the end-to-end compromised-advisor regression) — 33
  tests / 157 assertions, all green.
- All `.cljc` (portable, no JVM-only interop). AGPL-3.0-or-later
  (pre-existing `LICENSE`, unchanged). `README.md`/`GOVERNANCE.md`/
  `CONTRIBUTING.md`/`SECURITY.md`/`CODE_OF_CONDUCT.md`/`docs/` all
  pre-existing and left untouched.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Finalizing a dispatch/signal-safety override (clearing a signal
  interlock, authorizing a train's departure after a reported fault) —
  always a hard, permanent block, both by op identity
  (`forbidden-finalize-ops`) and by scope-exclusion text scan.
- Direct rolling-stock control or maintenance execution/release — not
  represented in the closed op allowlist at all; `coordinate-maintenance`
  only ever produces a coordination request record, never a maintenance
  release.
- `:flag-passenger-safety-concern` never finalizes, overrides, or clears
  anything — every record it produces is
  `requires_human_review`/`advisory_only` true, and it always escalates
  to a human, at every phase.

## Consequences

(+) ISIC 4911 interurban-passenger-rail operations-coordination is
genuinely implemented and fully tested, on top of the pre-existing
blueprint-tier boilerplate rather than a wholesale re-scaffold.

(+) The dispatch/signal-safety-override exclusion is hardcoded in
governor checks (`dispatch-safety-override-violations`), not just
asserted in README prose, and is defended on two axes (forbidden op
identity AND scope-exclusion text scan).

(+) The fleet-wide bare-noun self-tripping bug class is avoided by
construction (action-phrased scope-exclusion terms) and covered by both
a unit-level regression test and a full end-to-end graph test with a
deliberately malicious mock advisor.

(+) `:flag-passenger-safety-concern` always-escalate is a two-layer
invariant (governor `high-stakes` gate + phase's permanent absence from
every `:auto` set), and is writable from the earliest assisted phase so
rollout maturity never blocks surfacing a safety concern.

(+) `route-schedule-not-verified` applies to all four ops (not just
actuation-shaped ones), and the flag it reads can never be set by any op
in this actor's own allowlist — this actor structurally cannot
self-certify its own operating precondition.

(+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is 0
errors / 0 warnings.

(-) Real persistent store (Datomic/kotoba-server) is a follow-up; tests
exercise `DatomicStore` via `langchain.db`'s in-process API, not a real
Datomic/kotoba-server deployment.

(-) `railops.railopsllm`'s `mock-advisor` is deterministic, not a real
LLM call; the `Advisor` protocol seam is ready for that swap but not
wired in this ADR.

## Verification

- `cloud-itonami-isic-4911`: `clojure -M:test` → `Ran 33 tests
  containing 157 assertions. 0 failures, 0 errors.` `clojure -M:lint` →
  0 errors, 1 warning (fixed to 0/0 before push). `clojure -M:dev:run`
  demo runs end-to-end: `log-service-record` auto-commits at phase 3;
  `schedule-service-operation`/`flag-passenger-safety-concern`/
  `coordinate-maintenance` all interrupt for human approval then commit;
  an unregistered/unverified service HARD-holds on `log-service-record`;
  an out-of-allowlist op HARD-holds; a direct governor-level
  `finalize-dispatch-safety-override` proposal is HARD-blocked
  (`:dispatch-safety-override-blocked` + `:op-not-allowlisted`).
- Commit `d80e5e767c7e873165f0f2488c33e15a9dc12abf` pushed to
  `cloud-itonami/cloud-itonami-isic-4911`'s `main` (on top of the
  pre-existing blueprint-tier boilerplate at `78c9023`).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"4911"` entry had **no `:maturity` key** before this edit (resolved
  to `:blueprint` only via the `:repo`-presence fallback in
  `kotoba.industry/maturity-of`); this edit added `:maturity
  :implemented` explicitly. `:repo`/`:business-id` were already correct
  (`:business-id "cloud-itonami-4911"` matches `blueprint.edn`'s own
  `:itonami.blueprint/id`) and left unchanged, per this fleet's
  exact-block-edit discipline (`:required-technologies`/
  `:operating-states` also left unchanged). Landed via a Contents-API
  single-file PUT (sha-checked optimistic concurrency), landed on the
  first attempt, commit `69925d1e7dc725cde220b170d8c6fbfa421365ca`.
  Diff-verified via a byte-exact prefix/suffix scan against the
  immediately-prior fresh fetch: a single contiguous insertion
  (`maturity :implemented`) with everything else in the 230KB file
  byte-identical; re-confirmed via a fresh post-PUT GET that the
  `"4911"` block and sample sibling blocks (`"4920"`, `"3512"`,
  `"5320"`) were all intact.
- `test/kotoba/industry_test.clj`: this file was under extremely heavy
  concurrent fleet contention during this promotion (observed
  `:blueprint`/`:implemented` counts moving 24/393 → 22/395 → 20/397 →
  18/399 across successive fresh re-fetches within this single
  promotion's own edit window, purely from concurrent sibling
  promotions landing in the interim — including a concurrent sibling
  agent, `cloud-itonami-isic-5221`, independently corroborating this
  promotion's own `"4911"` entry mid-flight before this session got to
  it). The pinned `:implemented`/`:blueprint` count assertions were
  live-recomputed via `(kotoba.industry/maturity-summary)` against a
  freshly re-fetched `origin/main` immediately before each PUT attempt,
  not assumed and not `grep -c`'d. Landed on the first Contents-API PUT
  attempt at `:blueprint 18` / `:implemented 399`, commit
  `10c0b87dc4fb35ee3a8acc14e037cb85d9513116`. This edit added the
  missing dedicated `testing` block for `"4911"` (the short early
  corroboration line was normalized to its final `:implemented`
  wording) and re-synced both hot count assertions to that fresh live
  recompute.
- Post-merge re-verification from a brand-new fresh clone of
  `kotoba-lang/industry` `main` (commit
  `10c0b87dc4fb35ee3a8acc14e037cb85d9513116`, plus a fresh
  `../technology` sibling clone): `clojure -M:test` → `Ran 15 tests
  containing 1060 assertions. 3 failures, 0 errors.` The 3 residual
  failures (`"5011"`, `"4912"`, `"5222"`) are **pre-existing, unrelated
  concurrent-fleet drift** — confirmed by re-running the *pristine,
  unedited* `main` test file against the *current* live registry.edn
  before this session's own test-file edit was applied, which already
  showed the identical 3 (plus this promotion's own now-fixed
  `"4911"`/count failures) — not caused by, or in scope for, this
  promotion. `clojure -M:lint` → 0 errors, 0 warnings. No mojibake
  detected in `registry.edn` (`file -I` reports `text/plain;
  charset=us-ascii`). `"4911"`'s own entry (`:maturity :implemented`,
  both the short and detailed `industry_test.clj` corroboration blocks)
  independently re-confirmed intact.
