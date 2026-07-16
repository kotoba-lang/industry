# ADR-2613417000: cloud-itonami ISIC 3315 (Repair of transport equipment, except motor vehicles) actor coverage

## Status

Accepted. `cloud-itonami-isic-3315` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-3315`
404, and `gh api repos/gftdcojp/cloud-itonami-C3315` also 404, both
before this work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-3315`, following
the verified fresh-scaffold protocol established across this fleet
(closest architectural sibling: `cloud-itonami-isic-3311`, repair of
fabricated metal products). This is the final batch of Wave 3 — after
this batch, only the two deliberately-scoped-out sensitive classes
(2520 weapons/ammunition, 3040 military fighting vehicles) remain
unimplemented in the entire wave.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carried `{:id "3315", :name "Repair of transport equipment, except
motor vehicles", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-C3315`
/ `cloud-itonami-C3315`) predating the current `cloud-itonami-isic-<id>`
naming convention. The live `:name` was independently re-verified
against a fresh clone before any work began, per this fleet's ID/name-
mismatch caution, and confirmed to match "Repair of transport
equipment, except motor vehicles" exactly — distinct from sibling 3311
(repair of fabricated metal products, already `:implemented`) and from
motor-vehicle repair (a different ISIC section entirely). This work
implements the blueprint as a real, tested `cloud-itonami-isic-<id>`
actor repo and corrects the registry entry's `:repo`/`:business-id`
fields to match.

ISIC 3315 covers repair of **transport equipment, except motor
vehicles** — ships and boats, aircraft and spacecraft, and railway
locomotives and rolling stock — distinct from sibling repair classes
3311 (fabricated metal products), 3312 (machinery and equipment), 3313
(electronic and optical equipment), 3314 (electrical equipment) and the
residual class 3319 (other equipment). UNLIKE many other 33xx repair
siblings (and unlike ISIC 2829's own deliberate one-illustrative-
product-line narrowing), this build does NOT narrow to a single
transport mode — it models all THREE structurally distinct asset
classes (`:marine-vessel` / `:aircraft` / `:railway-rolling-stock`)
under one repair-shop-coordination shape, because each sits under a
genuinely disjoint regulatory regime (classification-society/flag-
administration survey; airworthiness-authority release-to-service;
rail-safety-authority maintenance certification) even within the same
jurisdiction, and the task's own domain design explicitly named all
three ("structural-integrity/airworthiness/seaworthiness concern" plus
"rail-safety-certification-authority").

## Decision

Scaffold `cloud-itonami-isic-3315` mirroring the `cloud-itonami-isic-3311`
(repair of fabricated metal products) architecture closely — same
langgraph-clj StateGraph + independent Governor + Phase 0->3 rollout
pattern, same asset-verification/legal-basis/unresolved-concern gate
structure — but retargeted to transport-equipment repair's own hazard
profile, asset-class taxonomy and regulatory catalog:

- Namespace prefix `transport-equipment-repair` (module
  `transport_equipment_repair`).
- Governor keyword `:transport-equipment-repair-governor`.
- Closed 4-op allowlist, all `:effect :propose` only:
  `:log-repair-record` (inspection/disassembly/repair/reassembly/test-
  line batch data logging), `:schedule-repair-operation` (repair/
  overhaul scheduling proposal), `:flag-safety-concern` (surface a
  structural-integrity/airworthiness/seaworthiness/rail-safety concern
  — ALWAYS escalates), `:coordinate-return-to-service` (completed-unit
  return-to-customer LOGISTICS coordination — pickup/delivery
  scheduling, customer notification, NEVER a certification-authority
  sign-off).
- Six HARD governor checks elaborating four HARD invariants (unknown
  op / effect-not-propose / forbidden action class / asset not
  independently verified-registered before ANY action, elaborated
  further into legal-basis-missing and unresolved-safety-concern for
  `:schedule-repair-operation` AND `:coordinate-return-to-service`).
  UNLIKE `cloud-itonami-isic-3311`'s equivalent check (which exempts
  `:log-repair-record`), this actor's asset-verification check applies
  to ALL FOUR ops — a genuine, documented domain-design requirement
  broader than 3311's own, the same "before any action" posture
  `cloud-itonami-isic-0130`'s batch-registration invariant established
  for its own domain. `:asset-verified?` is set only by an independent
  process outside this actor (the vessel's flag-administration
  registration, the aircraft's airworthiness registration, the rolling
  stock's fleet registration) so this check never creates a
  bootstrapping deadlock.
- Forbidden-action-class permanent block covers
  `:repair-equipment-control?` / `:direct-actuation?` /
  `:airworthiness-certification-decision?` /
  `:seaworthiness-certification-decision?` /
  `:rail-safety-certification-decision?` / `:return-to-service-sign-off?`
  markers — un-overridable by any human approval, structurally
  preventing this actor from ever certifying fitness for service
  regardless of which of the three transport modes an asset belongs to.
- Per-(jurisdiction, asset-class) legal-basis catalog
  (`transport-equipment-repair.facts`, JPN/USA/DEU x
  marine-vessel/aircraft/railway-rolling-stock = 9 seeded pairs, ALL
  honestly `:qualitative`), keyed `iso3 -> asset-class -> basis-map`
  rather than 3311's flat `iso3 -> basis` map, because ships, aircraft
  and railway rolling stock genuinely sit under disjoint regulatory
  regimes even within the same jurisdiction — citing one combined law
  per jurisdiction would be dishonest. Every citation was verified via
  live web search before the catalog was written (no fabrication):
  JPN marine 船舶安全法第5条第1項第3号 (laws.e-gov.go.jp/law/308AC0000000011),
  JPN aircraft 航空法第19条 (laws.e-gov.go.jp/law/327AC0000000231),
  JPN rail 鉄道に関する技術上の基準を定める省令第89条
  (laws.e-gov.go.jp/law/413M60000800151), USA marine 46 CFR 2.01-15 +
  170.005 (law.cornell.edu/cfr/text/46/2.01-15), USA aircraft
  14 CFR 43.5 (ecfr.gov, Approval for return to service), USA rail
  49 CFR Part 215.9 (ecfr.gov, Movement of cars for repair), DEU/EU
  marine Regulation (EC) No 391/2009 (eur-lex.europa.eu), DEU/EU
  aircraft Commission Regulation (EU) No 1321/2014 Annex II Part-145.A.50
  (easa.europa.eu), DEU/EU rail Commission Implementing Regulation
  (EU) 2019/779 ECM certification (eur-lex.europa.eu).
- `:schedule-repair-operation` and `:coordinate-return-to-service` MAY
  auto-commit at phase 3 when the governor is completely clean —
  matching `cloud-itonami-isic-3311`/`-3314`/`-3319`'s own shape — since
  checks 3-6 (forbidden-action-class, asset-verification, legal-basis,
  unresolved-concern) already gate the real hazard surface
  independently of phase; only `:flag-safety-concern` is a permanent,
  unconditional `high-stakes` member.
- Fully portable `.cljc`, zero JVM-only interop in `src/`
  (`transport-equipment-repair.notify` ships only the deterministic
  mock `Notifier`, per this workspace's cljs-first runtime-priority
  rule).

## Verification

Actor repo, built from a uniquely-named scratch dir mirroring the
workspace's `orgs/cloud-itonami/<repo>` layout, sibling to symlinked
`orgs/kotoba-lang/{langgraph,langchain,langchain-store,technology}`
(read-only references into the shared checkout, never edited), so
`deps.edn`'s `:local/root` paths resolve exactly as they would in the
monorepo checkout:

```
$ clojure -M:test
Ran 86 tests containing 282 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 799ms, errors: 0, warnings: 0

$ clojure -M:dev:run
(full demo narrative: happy-path log/schedule/flag-approve/re-schedule/
coordinate-return-to-service for a JPN marine vessel, four HARD-hold
scenarios -- uncovered jurisdiction, not-independently-verified asset
[even for :log-repair-record], unresolved safety concern, op outside
the closed allowlist -- then cross-asset-class USA aircraft and DEU/EU
railway-rolling-stock schedule walkthroughs -- no exceptions)
```

Pushed to `main` at commit `3298dd7f344c0b4218e0f8e157752b686ed41376`
(`cloud-itonami/cloud-itonami-isic-3315`), independently confirmed via
`gh api repos/cloud-itonami/cloud-itonami-isic-3315/commits/main`.
Re-verified from an INDEPENDENT fresh clone (same sibling layout) — see
the task's final report for the exact re-verification `clojure -M:test`
output.

`kotoba-lang/industry` registry: `"3315"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-C3315` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-3315` /
`cloud-itonami-isic-3315`, exact in-place edit of only the `"3315"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the task's final report for the exact
before/after counts and merge SHA.

## Consequences

(+) ISIC 3315 now has a documented, governed, auditable
transport-equipment-repair-shop-operations-coordination actor,
following this fleet's established pattern, genuinely covering all
three transport modes the ISIC class describes (ships/boats, aircraft/
spacecraft, railway locomotives/rolling stock) rather than narrowing to
one illustrative product line.

(+) The "coordination, not control/certification" boundary is a hard,
permanent, unconditional governor block (covering repair-equipment
control AND all three certification-authority-decision markers) plus a
structural phase-table absence for `:flag-safety-concern` — two
independent layers agree, not just asserted in prose.

(+) The asset-verification gate is broader than sibling 3311's own
(applies to ALL FOUR ops, not just three) — a genuine, documented
domain-design strengthening for this higher-average-stakes transport-
equipment vertical, not an oversight.

(+) Nine real official per-(jurisdiction, asset-class) legal-basis
citations, each independently verified via live web search before the
catalog was written, honestly reported as `:qualitative` (no fabricated
numeric lead-times).

(+) Portable `.cljc`, zero JVM-only constructs, clj-kondo 0 errors / 0
warnings.

(-) Still a simulation/proposal layer — single `MemStore` backend
(plus a parity-tested `DatomicStore`), mock advisor, no real plant-
management database integration. Same limitation as every sibling
actor in this fleet at this stage.

(-) The nine-pair legal-basis catalog is a STARTING catalog (JPN/USA/
DEU x 3 asset classes), not a survey of all ~194 jurisdictions x every
transport-equipment sub-type — documented plainly as a scope
limitation in `transport-equipment-repair.facts` ns docstring, not
silently generalized.
