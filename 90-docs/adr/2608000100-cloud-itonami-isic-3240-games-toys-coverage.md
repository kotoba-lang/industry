# ADR-2608000100: cloud-itonami-isic-3240 (Manufacture of games and toys) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse
toposort plan); ADR-2607201000 (cloud-itonami-isic-3230 Manufacture of
sports goods coverage — this build's closest domain analog, back-
office plant-operations-coordination actor pattern for a molding/
assembly plant with a consumer-protection dimension)

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries ISIC class `3240` ("Manufacture of games and toys") at
`:maturity :spec`, pointed at a stale, never-created `gftdcojp/cloud-
itonami-C3240` placeholder repo. Per this task's assignment (part of
the ongoing careful, smaller-batch cloud-itonami ISIC-coverage rollout
that replaced an earlier 18-agent haiku batch with a 61% defect rate),
this ADR promotes 3240 to `:implemented` via a fresh scaffold at the
real `cloud-itonami` GitHub org.

**Identity verification (per this fleet's ID/name-mismatch caution)**:
before any work began, `kotoba-lang/industry` was cloned fresh
(read-only, via the GitHub git-data/Contents API to avoid CDN-cached
`raw.githubusercontent.com` staleness) alongside a `../technology`
sibling clone, and the live registry entry was confirmed:

```
{:id "3240"
 :name "Manufacture of games and toys"
 :repo "https://github.com/gftdcojp/cloud-itonami-C3240"
 :business-id "cloud-itonami-C3240"
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]
 :optional-technologies []
 :maturity :spec
 :operating-states [:spec :design :produce :inspect :package :audit]}
```

The `:name` matches the assigned domain exactly -- no mismatch. The
`:repo` target (`gftdcojp/cloud-itonami-C3240`) was confirmed to not
exist (never created), and the real target org/repo name
(`cloud-itonami/cloud-itonami-isic-3240`) was independently confirmed
404 via `gh api repos/cloud-itonami/cloud-itonami-isic-3240` before
scaffolding began.

## Decision

Scaffolded `cloud-itonami/cloud-itonami-isic-3240` as a games-and-toys-
plant **plant operations coordination** actor (ToysGamesAdvisor ⊣
Toys & Games Plant Operations Governor, langgraph-clj StateGraph,
append-only audit ledger), closely mirroring `cloud-itonami-isic-
3230`'s (Manufacture of sports goods) verified module shape -- this
fleet's closest domain analog for a fixed molding/assembly plant with
a real safety/consumer-protection dimension. Full rationale, decision
records and verification detail live in the child repo's own
`docs/adr/0001-architecture.md`.

### Scope: plant-operations coordination, not molding/assembly-line control, not a toy-safety-certification authority

Closed op allowlist, all `:effect :propose` only:
- `:log-production-batch` — molding/assembly/safety-test batch, output-quality data logging
- `:schedule-maintenance` — molding/assembly-equipment maintenance scheduling proposal
- `:flag-safety-concern` — choking-hazard/materials-safety (e.g. lead paint, phthalate)/small-parts concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

HARD invariants (always `:hold`, no override), elaborated into
thirteen concrete governor checks:
1. Plant/batch record must be independently verified/registered (equipment before maintenance, batch before shipment) before any action
2. Request `:effect` must be `:propose` only
3. Closed op allowlist enforced
4. Any proposal touching molding/assembly-line-equipment control (closed proposal-effect allowlist), direct equipment actuation (`:actuate-equipment? true`), or a toy-safety-certification-authority decision (`:issue-safety-certification? true`, e.g. ASTM F963 / EN 71) is a HARD, PERMANENT, unconditional block

Plus independent shipment-quantity recompute, double-schedule guard,
and product-type / safety-test-pass-percent / weight-grams /
defect-rate plausibility validation on production-batch patches.

ESCALATE (always human sign-off, overridable by a human):
- `:flag-safety-concern` always escalates, regardless of confidence
- Low-confidence proposals

This vertical additionally carries a CHILD-consumer-protection
dimension its closest analog (3230, sports goods) does not, since the
finished products (plastic/wooden toys, board games, puzzles) are used
directly by children -- see the child repo's ADR Decision 2/3 for the
explicit safety-escalation discipline this motivates.

## Consequences

(+) ISIC 3240 (games and toys manufacturing) now has a governed,
auditable, back-office coordination actor, closing another gap in the
cloud-itonami fleet's manufacturing coverage.

(+) The design explicitly excludes molding/assembly-line equipment
control and toy-safety compliance certification authority -- both
permanently, unconditionally blocked at the governor layer,
independent of phase or human approval.

(-) Still a proposal/coordination layer, not a real plant-operations
control system or a substitute for an accredited testing/
certification body.

## Verification

- `cloud-itonami-isic-3240`: `clojure -M:test` green --
  `Ran 82 tests containing 222 assertions. 0 failures, 0 errors.`
  (fresh clone, pre-push run; re-verified post-merge/push from an
  independent fresh clone below), `clojure -M:lint` clean (0 errors, 0
  warnings), `clojure -M:dev:run` demo exercises the happy path and
  all twelve HARD-hold scenarios directly.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*`.
- Pushed directly to `origin/main` at
  `https://github.com/cloud-itonami/cloud-itonami-isic-3240`
  (freshly created empty repo, no auto-init/unrelated-history
  conflict) at commit `bd0c578f991f44e4aef4fd44d7a870db88aac791`.
- Post-push re-clone (fresh temp dir, `../technology` sibling for
  `kotoba-lang/industry`) re-ran `clojure -M:test` to confirm
  post-merge validity -- see the `kotoba-lang/industry` registry PR/
  merge for that exact raw output line.
- `kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
  `"3240"` entry promoted `:spec` -> `:implemented`, `:repo` corrected
  to `https://github.com/cloud-itonami/cloud-itonami-isic-3240`,
  `:business-id` corrected to `cloud-itonami-isic-3240`, ADR reference
  added, via an exact-text in-place edit of the literal entry block
  (never a parse-transform-reserialize of the whole file).
