# ADR-2607151931: cloud-itonami ISIC 2012 (Manufacture of fertilizers and nitrogen compounds) coverage

## Status

Accepted. `cloud-itonami-isic-2012` scaffolded fresh (no prior repo
existed — verified via `gh api repos/gftdcojp/cloud-itonami-C2012` and
`gh api repos/cloud-itonami/cloud-itonami-isic-2012`, both 404 before
this work) and promoted from `:spec` to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

This is part of the ongoing cloud-itonami "every ISIC industry class
is an autonomous actor" rollout, in a careful, smaller-batch phase
after a prior 18-agent haiku batch had a 61% defect rate. This build
follows the stricter protocol (capable model + mandatory
run-and-paste-raw-output verification) that 84+ consecutive prior
agents completed successfully.

`kotoba-lang/industry`'s registry entry for `{:id "2012" ...}` was
verified fresh (independent clone of `kotoba-lang/industry`, sibling
`kotoba-lang/technology`) to have `:name` **"Manufacture of fertilizers
and nitrogen compounds"** — exact match to the assigned scope, before
any implementation work began. Its `:repo` field pointed at
`https://github.com/gftdcojp/cloud-itonami-C2012`, a placeholder that
was never actually created (confirmed 404), matching the pattern
already documented on the neighboring `"2013"` registry entry (a
never-created `gftdcojp/cloud-itonami-C2013` placeholder). This build
uses a fresh `cloud-itonami/cloud-itonami-isic-2012` repo instead,
matching the naming convention of `cloud-itonami-isic-2013` and other
recently-promoted siblings.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-2012` mirroring
`cloud-itonami-isic-2013` (Manufacture of plastics and synthetic
rubber in primary forms) closely: FertAdvisor (sealed intelligence
node) ⊣ Fertilizer Plant Operations Governor (independent compliance
layer), a langgraph-clj StateGraph (`fertmfg.operation/build`), and an
append-only audit ledger (`fertmfg.store`).

**Domain design — a PLANT OPERATIONS COORDINATION actor, NOT direct
ammonia-synthesis/granulation-line control authority.** Four ops, all
`:effect :propose` only:
- `:log-production-batch` — synthesis/granulation batch, N-P-K nutrient-content (`:n-percent`/`:p2o5-percent`/`:k2o-percent`) and product-grade data logging
- `:schedule-maintenance` — ammonia-synthesis/granulation-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard (ammonia exposure, ammonium-nitrate explosion risk) or environmental concern; ALWAYS escalates, never auto-decided
- `:coordinate-shipment` — outbound fertilizer/nitrogen-compound shipment coordination proposal

Ten concrete governor checks (`fertmfg.governor`) elaborate four HARD
invariants (always `:hold`, no override):
1. Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action — equipment before maintenance scheduling, batch before shipment coordination; a shipment's own claimed weight is independently recomputed against the batch's own logged production weight, never self-reported
2. The request's own `:effect` must be `:propose` only
3. The proposal's own `:effect` must be one of the four propose-shaped effects — any proposal touching synthesis/granulation-line-equipment control (a hallucinated actuation effect) is a hard, permanent block
4. `:op` must be in the closed four-op allowlist

Plus: permanent `:actuate-line? true` block (never overridable, not
even by a human), a double-schedule guard on `:scheduled?` (never a
`:status` value), a closed `:product-grade` allowlist (straight
nitrogen fertilizers/nitrogen compounds, phosphate fertilizers, potash,
blended N-P-K compound), and an N-P-K nutrient-content plausibility
check (`fertmfg.registry/nutrient-content-valid?`: each of
N/P2O5/K2O in `[0,100]`, and their sum never exceeding 100% of the
product's own mass — catches a fabricated/mislabeled grade like
"60-60-0" that no real fertilizer batch could physically be).

`:flag-safety-concern` ALWAYS escalates to a human plant supervisor
(SOFT gate, two independent layers agree: the governor's own
`high-stakes` set AND `fertmfg.phase`'s permanent absence of this op
from every phase's `:auto` set); low-confidence proposals also
escalate. `:log-production-batch` is the only op eligible to
auto-commit, and only at phase 3 when governor-clean — the same
"administrative logging carries no physical/financial risk" posture
every sibling actor in this fleet establishes.

This vertical has no pre-existing `kotoba-lang/fertmfg`-style
capability library to wrap (verified: no such repo exists), so domain
logic (equipment/batch verification, shipment-weight recompute,
product-grade validation, N-P-K nutrient-content validation) lives as
self-contained pure functions in `fertmfg.registry`, re-verified
independently by the governor — never trusting the advisor's own
self-report. This mirrors `cloud-itonami-isic-2013`'s own
`resinmfg.registry` precedent exactly.

`:itonami.blueprint/governor` is `:fertilizer-plant-operations-
governor`, grep-verified UNIQUE fleet-wide before this repo was
created (`gh search code "fertilizer-plant-operations-governor"
--owner cloud-itonami`, zero hits).

All source is `.cljc` (cljs-first / portable) — no JVM-only interop.

## Consequences

(+) Fertilizer-and-nitrogen-compound plant operations back-office now
has a documented, governed, auditable coordination layer.

(+) The domain's real hazard profile (ammonia toxic-release exposure,
ammonium-nitrate explosion risk — a well-documented industrial hazard
class for exactly this ISIC vertical — plus environmental-release risk)
is encoded as an always-escalating safety-concern flag, never a
threshold a phase gate could quietly relax.

(+) Scope is bounded and verifiable: the closed op allowlist, closed
proposal-effect allowlist, and permanent line-actuate block together
make "this actor never directly operates plant equipment" a
structural property, not a policy note.

(-) Still a simulation/proposal layer — no integration with real plant
telemetry, batch-tracking, or freight-dispatch systems.

## Verification

`cloud-itonami-isic-2012` fresh clone, `clojure -M:test`:

```
Running tests in #{"test"}

Testing fertmfg.governor-contract-test

Testing fertmfg.operation-test

Testing fertmfg.phase-test

Testing fertmfg.registry-test

Testing fertmfg.store-contract-test

Ran 75 tests containing 203 assertions.
0 failures, 0 errors.
```

`clojure -M:lint`: `linting took 527ms, errors: 0, warnings: 0`.

`clojure -M:dev:run` demo narrative exercises proposal submission,
escalation, and every one of the ten HARD-hold scenarios directly
(not-propose-effect, unknown-op, equipment-not-verified,
batch-not-verified, shipment-weight-exceeded, line-actuate-blocked,
already-scheduled, invalid-product-grade, invalid-nutrient-content,
plus the closed-effect-allowlist check exercised transitively).

Repo: <https://github.com/cloud-itonami/cloud-itonami-isic-2012>,
commit `52bbe767fe44a13eff38877b4543c5927c5439ae` on `main`.

`kotoba-lang/industry` registry `"2012"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected to point at the real
repo, `:maturity-comment` referencing this ADR — see companion
registry-update PR/commit for the exact merge SHA and post-merge
`clojure -M:test` re-verification output.
