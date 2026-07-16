# ADR-2607201200: cloud-itonami ISIC 2750 (Manufacture of domestic appliances) coverage

## Status

Accepted. `cloud-itonami-isic-2750` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

Continuing the ongoing careful, smaller-batch cloud-itonami actor
rollout (capable model + mandatory verification per agent, following a
prior 18-agent haiku batch that had a 61% defect rate; 72+ consecutive
agents on the stricter protocol had succeeded as of this task's start),
this ADR covers **ISIC class 2750, Manufacture of domestic
appliances** only.

Verified before any implementation work began: the live
`kotoba-lang/industry` registry entry `{:id "2750" ...}` carries
`:name "Manufacture of domestic appliances"` (grep-confirmed against a
fresh clone of `kotoba-lang/industry`, `resources/kotoba/industry/
registry.edn` line 4599) -- the ID/name pair matches the assignment,
so this build proceeded on a correct premise. `gh api
repos/cloud-itonami/cloud-itonami-isic-2750` returned 404 before this
task began: no pre-existing repo, a fresh from-scratch scaffold.

## Decision

Scaffolded `cloud-itonami/cloud-itonami-isic-2750` from scratch,
mirroring the verified architecture of `cloud-itonami-isic-2640`
(Manufacture of consumer electronics, the closer domain analog for a
discrete-unit consumer-facing product line) and
`cloud-itonami-isic-2710` (Manufacture of electric motors,
generators, transformers and electricity distribution and control
apparatus): a `DomApplAdvisor` (sealed intelligence node) ⊣
`Domestic Appliance Plant Operations Governor` (independent censor),
`langgraph-clj` StateGraph, append-only audit ledger. See that repo's
own `docs/adr/0001-architecture.md` for the full domain-adaptation
rationale (equipment/product vocabulary, safety-concern vocabulary,
dielectric-test-kv ceiling grounded in IEC 60335-1).

A plant OPERATIONS COORDINATION actor for domestic-appliance
manufacturing (compressor/motor/wiring assembly and final-assembly/
test lines producing refrigerators, washing machines, dishwashers,
microwave ovens, and small kitchen appliances such as vacuum
cleaners) -- NOT direct assembly/test-bench-equipment control
authority and NOT domestic-appliance-safety-certification authority
(e.g. UL/CE/CSA compliance marks).

Four ops, all `:effect :propose` only:
- `:log-production-batch` -- assembly/test batch, output-quality/test-result data logging
- `:schedule-maintenance` -- assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` -- surface an electrical-safety/refrigerant-leak/UL-CE-compliance concern, ALWAYS escalates
- `:coordinate-shipment` -- outbound product shipment coordination

Twelve concrete governor checks (`domappl.governor`) elaborate four
HARD invariants (no override, ever):
1. Plant/batch record must be independently verified/registered before any action (equipment before maintenance scheduling, batch before shipment coordination)
2. Request's own `:effect` must be `:propose` only
3. Closed op allowlist (the four ops above only)
4. Closed proposal-effect allowlist -- no direct assembly/test-bench-equipment control; direct equipment actuation (`:actuate-equipment? true`) and self-issuing a domestic-appliance safety-certification mark (`:issue-certification? true`) are PERMANENT, unconditional blocks

Plus independent shipment-quantity recompute against the batch's own
logged production quantity, a double-schedule guard, and product-
type/dielectric-test-kv/defect-rate plausibility validation.

`:flag-safety-concern` ALWAYS escalates to a human plant supervisor,
regardless of confidence. `:log-production-batch` is the only op
eligible to auto-commit, and only at phase 3 when governor-clean --
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`
are never in any phase's `:auto` set (permanent, structural).

## Verification

All source is `.cljc` (portable ClojureScript/JVM/nbb), no JVM-only
interop, per this workspace's cljs-first mandate. `deps.edn` pins
`io.github.kotoba-lang/langgraph` and `io.github.kotoba-lang/langchain`
via `:local/root` directly in the top-level `:deps` (not only under a
`:dev` alias), so a bare `clojure -M:test` resolves offline inside the
monorepo checkout.

Built and tested from a uniquely-named scratch directory (never inside
the shared `orgs/kotoba-lang/industry` checkout), with the actor repo
placed at a sibling-path mirror `<scratch>/orgs/cloud-itonami/
cloud-itonami-isic-2750` alongside symlinked `<scratch>/orgs/
kotoba-lang/{langgraph,langchain}` so the `:local/root` relative paths
resolved without touching the shared superproject checkout.

`clojure -M:test` from an independent fresh clone of
`cloud-itonami/cloud-itonami-isic-2750` (post-push):

```
Ran 77 tests containing 210 assertions.
0 failures, 0 errors.
```

`clojure -M:lint`: `linting took 424ms, errors: 0, warnings: 0`.
`clojure -M:dev:run` demo narrative exercises proposal submission,
escalation, and every HARD-hold scenario directly (not-propose-effect,
unknown-op, equipment-not-verified, batch-not-verified, shipment-
quantity-exceeded, equipment-actuate-blocked, certification-authority-
blocked, already-scheduled, invalid-product-type, invalid-dielectric-
test-kv, invalid-defect-rate).

Merge SHA and post-registry-merge re-verification are recorded in this
task's final report (this ADR is committed alongside the registry
promotion, both landed via server-side merge per this workspace's
git-operations policy -- no local rebase, no force-push).

## Consequences

(+) Domestic-appliance plant operations back-office now has a
documented, governed, auditable coordination layer, consistent with
every other `cloud-itonami-isic-*` actor in this fleet.

(+) `kotoba-lang/industry` registry entry `{:id "2750" ...}` promoted
from `:spec` to `:implemented`, with `:repo`/`:business-id` corrected
from the never-created `gftdcojp/cloud-itonami-C2750` placeholder to
the real `cloud-itonami/cloud-itonami-isic-2750`.

(-) Still a simulation/proposal layer -- equipment actuation, line
operation, and certification issuance remain human-/institution-
controlled via external channels, same as every sibling actor.
