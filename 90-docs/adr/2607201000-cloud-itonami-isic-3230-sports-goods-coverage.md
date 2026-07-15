# ADR-2607201000: cloud-itonami-isic-3230 (Manufacture of sports goods) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse
toposort plan); ADR-2607200000 (cloud-itonami-isic-3211 Manufacture of
jewellery and related articles coverage — this build's closest domain
analog, back-office plant-operations-coordination actor pattern)

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries ISIC class `3230` ("Manufacture of sports goods") at
`:maturity :spec`, pointed at a stale, never-created `gftdcojp/cloud-
itonami-C3230` placeholder repo. Per this task's assignment (part of
the ongoing careful, smaller-batch cloud-itonami ISIC-coverage rollout
that replaced an earlier 18-agent haiku batch with a 61% defect rate),
this ADR promotes 3230 to `:implemented` via a fresh scaffold at the
real `cloud-itonami` GitHub org.

**Identity verification (per this fleet's ID/name-mismatch caution)**:
before any work began, `kotoba-lang/industry` was cloned fresh
(read-only) alongside a `../technology` sibling clone, and the live
registry entry was confirmed:

```
{:id "3230"
 :name "Manufacture of sports goods"
 :repo "https://github.com/gftdcojp/cloud-itonami-C3230"
 :business-id "cloud-itonami-C3230"
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]
 :optional-technologies []
 :maturity :spec
 :operating-states [:spec :design :produce :inspect :package :audit]}
```

The `:name` matches the assigned domain exactly -- no mismatch. The
`:repo` target (`gftdcojp/cloud-itonami-C3230`) was confirmed to not
exist (never created), and the real target org/repo name
(`cloud-itonami/cloud-itonami-isic-3230`) was independently confirmed
404 via `gh api repos/cloud-itonami/cloud-itonami-isic-3230` before
scaffolding began.

## Decision

Scaffolded `cloud-itonami/cloud-itonami-isic-3230` as a sports-goods-
plant **plant operations coordination** actor (SportsGoodsAdvisor ⊣
Sports Goods Plant Operations Governor, langgraph-clj StateGraph,
append-only audit ledger), closely mirroring `cloud-itonami-isic-
3211`'s (Manufacture of jewellery and related articles) verified
module shape -- this fleet's closest domain analog for a fixed
processing plant with precision production equipment and a real
safety/consumer-protection dimension. Full rationale, decision
records and verification detail live in the child repo's own
`docs/adr/0001-architecture.md`.

### Scope: plant-operations coordination, not molding/assembly-line control, not a product-safety-certification authority

Closed op allowlist, all `:effect :propose` only:
- `:log-production-batch` — molding/assembly batch, output-quality data logging
- `:schedule-maintenance` — molding/assembly-equipment maintenance scheduling proposal
- `:flag-safety-concern` — materials-safety/product-safety-standard (e.g. impact-protection rating) concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

HARD invariants (always `:hold`, no override), elaborated into
thirteen concrete governor checks:
1. Plant/batch record must be independently verified/registered (equipment before maintenance, batch before shipment) before any action
2. Request `:effect` must be `:propose` only
3. Closed op allowlist enforced
4. Any proposal touching molding/assembly-line-equipment control (closed proposal-effect allowlist), direct equipment actuation (`:actuate-equipment? true`), or a product-safety/impact-protection-certification-authority decision (`:issue-safety-certification? true`) is a HARD, PERMANENT, unconditional block

Plus independent shipment-quantity recompute, double-schedule guard,
and product-type / impact-rating-percent / weight-grams / defect-rate
plausibility validation on production-batch patches.

ESCALATE (always human sign-off, overridable by a human):
- `:flag-safety-concern` always escalates, regardless of confidence
- Low-confidence proposals

## Consequences

(+) ISIC 3230 (sports goods manufacturing) now has a governed,
auditable, back-office coordination actor, closing another gap in the
cloud-itonami fleet's manufacturing coverage.

(+) The design explicitly excludes molding/assembly/finishing-line
equipment control and product-safety/impact-protection compliance
certification authority -- both permanently, unconditionally blocked
at the governor layer, independent of phase or human approval.

(-) Still a proposal/coordination layer, not a real plant-operations
control system or a substitute for an accredited testing/
certification body.

## Verification

- `cloud-itonami-isic-3230`: `clojure -M:test` green --
  `Ran 82 tests containing 222 assertions. 0 failures, 0 errors.`
  (fresh clone, pre-push run; re-verified post-merge/push from an
  independent fresh clone below), `clojure -M:lint` clean (0 errors, 0
  warnings), `clojure -M:dev:run` demo exercises the happy path and
  all twelve HARD-hold scenarios directly.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*`.
- Pushed directly to `origin/main` at
  `https://github.com/cloud-itonami/cloud-itonami-isic-3230`
  (freshly created empty repo, no auto-init/unrelated-history
  conflict) at commit `3942b846063b59f33eae835b380b0b3f60c690e6`.
- Post-push re-clone (fresh temp dir, `../technology` sibling for
  `kotoba-lang/industry`) re-ran `clojure -M:test` to confirm
  post-merge validity -- see the `kotoba-lang/industry` registry PR/
  merge for that exact raw output line.
- `kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
  `"3230"` entry promoted `:spec` -> `:implemented`, `:repo` corrected
  to `https://github.com/cloud-itonami/cloud-itonami-isic-3230`,
  `:business-id` corrected to `cloud-itonami-isic-3230`, ADR reference
  added, via an exact-text in-place edit of the literal entry block
  (never a parse-transform-reserialize of the whole file).
