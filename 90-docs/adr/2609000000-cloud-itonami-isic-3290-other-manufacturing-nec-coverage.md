# ADR-2609000000: cloud-itonami-isic-3290 (Other manufacturing n.e.c.) coverage

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2608000100 (cloud-itonami-isic-3240 Manufacture of
games and toys coverage — this build's closest domain analog,
back-office plant-operations-coordination actor pattern for a
molding/assembly plant with a consumer-protection dimension)

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries ISIC class `3290` ("Other manufacturing n.e.c.") at
`:maturity :spec`, pointed at a stale, never-created `gftdcojp/cloud-
itonami-C3290` placeholder repo. Per this task's assignment (part of
the ongoing careful, smaller-batch cloud-itonami ISIC-coverage rollout
that replaced an earlier 18-agent haiku batch with a 61% defect rate),
this ADR promotes 3290 to `:implemented` via a fresh scaffold at the
real `cloud-itonami` GitHub org.

**Identity verification (per this fleet's ID/name-mismatch caution)**:
before any work began, `kotoba-lang/industry` was cloned fresh
(read-only, via the GitHub git-data blob API to avoid CDN-cached
`raw.githubusercontent.com` staleness), and the live registry entry
was confirmed:

```
{:id "3290"
 :name "Other manufacturing n.e.c."
 :repo "https://github.com/gftdcojp/cloud-itonami-C3290"
 :business-id "cloud-itonami-C3290"
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]
 :optional-technologies []
 :maturity :spec
 :operating-states [:spec :design :produce :inspect :package :audit]}
```

The `:name` matches the assigned domain exactly -- no mismatch. ISIC
3290 is a broad residual manufacturing category (e.g. brooms/brushes,
umbrellas, buttons, pens/pencils, artificial flowers), distinct from
sibling ISIC 3240 (games and toys) and ISIC 3220 (musical
instruments), both already `:implemented`. The `:repo` target
(`gftdcojp/cloud-itonami-C3290`) was confirmed to not exist (never
created), and the real target org/repo name
(`cloud-itonami/cloud-itonami-isic-3290`) was independently confirmed
404 via `gh api repos/cloud-itonami/cloud-itonami-isic-3290` before
scaffolding began.

## Decision

### Concrete illustration for a broad n.e.c. category

ISIC 3290 has no single natural product line -- it is a residual
"everything else" manufacturing bucket. This build documents, plainly
in the README, a **concrete illustrative product line**:
**pen/pencil/writing-instrument manufacturing** (ballpoint pens,
pencils, markers, fountain pens). Scaffolded `cloud-itonami/
cloud-itonami-isic-3290` as a writing-instrument-plant **plant
operations coordination** actor (WritingInstrumentAdvisor ⊣ Writing
Instrument Plant Operations Governor, langgraph-clj StateGraph,
append-only audit ledger), closely mirroring `cloud-itonami-isic-
3240`'s (Manufacture of games and toys) verified module shape -- this
fleet's closest domain analog for a fixed molding/assembly plant with
a real safety/consumer-protection dimension. Full rationale, decision
records and verification detail live in the child repo's own
`docs/adr/0001-architecture.md`.

### Scope: plant-operations coordination, not molding/assembly-line control, not a materials-safety-certification authority

Closed op allowlist, all `:effect :propose` only:
- `:log-production-batch` — molding/assembly batch, output-quality data logging
- `:schedule-maintenance` — molding/assembly-equipment maintenance scheduling proposal
- `:flag-safety-concern` — materials-safety (e.g. ink/pigment toxicity, solvent exposure) or equipment-safety concern, ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

HARD invariants (always `:hold`, no override), elaborated into
thirteen concrete governor checks:
1. Plant/batch record must be independently verified/registered (equipment before maintenance, batch before shipment) before any action
2. Request `:effect` must be `:propose` only
3. Closed op allowlist enforced
4. Any proposal touching molding/assembly-line-equipment control (closed proposal-effect allowlist), direct equipment actuation (`:actuate-equipment? true`), or a materials-safety-certification-authority decision (`:issue-safety-certification? true`, e.g. the ACMI AP/CL seal under ASTM D4236) is a HARD, PERMANENT, unconditional block

Plus independent shipment-quantity recompute, double-schedule guard,
and product-type / materials-safety-pass-percent / weight-grams /
defect-rate plausibility validation on production-batch patches.

ESCALATE (always human sign-off, overridable by a human):
- `:flag-safety-concern` always escalates, regardless of confidence
- Low-confidence proposals

## Consequences

(+) ISIC 3290 (other manufacturing n.e.c.) now has a governed,
auditable, back-office coordination actor for its chosen illustrative
product line, closing another gap in the cloud-itonami fleet's
manufacturing coverage.

(+) The design explicitly excludes molding/assembly-line equipment
control and materials-safety compliance certification authority --
both permanently, unconditionally blocked at the governor layer,
independent of phase or human approval.

(-) Still a proposal/coordination layer, not a real plant-operations
control system or a substitute for an accredited testing/
certification body.

(-) A broad n.e.c. category is represented by only one illustrative
product line (writing instruments); other n.e.c. product families
(brooms/brushes, umbrellas, buttons, artificial flowers, etc.) are not
separately modeled.

## Verification

- `cloud-itonami-isic-3290`: `clojure -M:test` green --
  `Ran 82 tests containing 222 assertions. 0 failures, 0 errors.`
  (fresh scratch build, pre-push run; re-verified post-push from an
  independent fresh clone below), `clojure -M:lint` clean (0 errors, 0
  warnings).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*`.
- Pushed directly to `origin/main` at
  `https://github.com/cloud-itonami/cloud-itonami-isic-3290`
  (freshly created empty repo, no auto-init/unrelated-history
  conflict) at commit `43d945790ae569728c1c417468e891f1c026505e`.
- Post-push re-clone (fresh temp dir, `../technology` sibling for
  `kotoba-lang/industry`) re-ran `clojure -M:test` to confirm
  post-merge validity -- see the Report section of this task for the
  exact raw output line.
- `kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
  `"3290"` entry promoted `:spec` -> `:implemented`, `:repo` corrected
  to `https://github.com/cloud-itonami/cloud-itonami-isic-3290`,
  `:business-id` corrected to `cloud-itonami-isic-3290`, ADR reference
  added, via an exact-text in-place edit of the literal entry block
  (never a parse-transform-reserialize of the whole file).
- This superproject ADR pair was written from a sibling `git worktree`
  outside the superproject root, never inside the shared
  `com-junkawasaki/root` checkout directly, and landed via a
  server-side merge (`gh api repos/com-junkawasaki/root/merges`).
