# ADR-2607201700: cloud-itonami-isic-4220 (Construction of utility projects) utility-line-construction-project operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-4210 (Construction of roads and railways
-- the closest domain analog, water/sewer/gas/electric/telecom trenching
shares the SAME excavation/utility-strike legal exposure roads/railways
do, the reference this actor mirrors most closely), cloud-itonami-isic-
4211 (Community Building Construction -- the robotics-premise
construction-domain reference this actor follows structurally,
narrowed), cloud-itonami-isic-4311 (Demolition -- the sibling
coordination-only shape), ADR-2607011000 (ISIC section coverage), the
`kotoba-lang/industry` registry's `"4220"` catalog entry

## Context

`kotoba-lang/industry`'s registry carried `"4220"`
(`{:id "4220" :name "Construction of utility projects" ...}`) at
`:maturity :spec` with a stale `gftdcojp/cloud-itonami-F4220` placeholder
`:repo`/`:business-id`. The exact live `:name` was independently
re-verified against a fresh clone of `kotoba-lang/industry` before any
scaffolding began (per this fleet's ID/name-mismatch caution, several
prior agents having mislabeled their assigned ISIC class): `"4220"` =
"Construction of utility projects" (water/sewer/electricity/telecom
pipeline and utility-line construction) -- confirmed correct, no
mismatch, no need to stop. `gh api repos/cloud-itonami/cloud-itonami-
isic-4220` returned 404 before starting -- this was a genuinely fresh
scaffold, no prior attempt to recover from.

`cloud-itonami-isic-4210` (Construction of roads and railways) was read
in full as the primary structural template -- it is the closest domain
analog in this fleet: road/railway earthwork and utility-line trenching
share the identical legal exposure (excavation risks striking a buried
utility; work in a public right-of-way requires a road-opening/
utility-installation permit and advance notice). `cloud-itonami-isic-
4210` itself follows `cloud-itonami-isic-4211` (Community Building
Construction, this fleet's robotics-premise construction-domain
reference) structurally but is narrowed to coordination-only authority,
the same posture `cloud-itonami-isic-4311` (Demolition) established --
`cloud-itonami-isic-4220` follows that SAME narrowing: per this
workspace's standing rule against writing new Rust or robot-control
code, and per this task's explicit design brief (a utility-construction-
project OPERATIONS COORDINATION actor, not direct trenching/heavy-
equipment operation or utility-tie-in/energization-authorization
authority), `cloud-itonami-isic-4220` has NO robotics/simphysics analog,
no `kotoba-lang/robotics`/`kotoba-lang/physics-2d` dependency, and no
module that commits a real-world actuation effect. Every proposal this
actor's advisor can produce carries `:effect :propose`, unconditionally,
and the governor HARD-holds any proposal that doesn't.

Built by reading `cloud-itonami-isic-4210` in full and adapting its
module shape (`facts`/`governor`/`advisor`/`notify`/`operation`/`phase`/
`registry`/`sim`/`store`, namespaced `utilconstr.*`) to the utility-line-
construction domain, renaming the domain-specific forbidden-action-class
markers from 4210's `:finalizes-engineering-design?`/`:finalizes-grade-
plan?` to this actor's `:finalizes-tie-in-authorization?`/`:finalizes-
energization-authorization?` (connecting a new line to a live main /
starting flow-pressure-current on it -- the licensed utility engineer's
exclusive authority in this domain, the direct analog of 4210's
engineering-design/grade-plan-finalization exclusion), and following the
same stricter verification protocol (capable model, mandatory
`clojure -M:test` re-run before and after every push, fresh-clone
post-merge re-verification) as the preceding batch of ISIC-coverage
actors in this rollout (72+ consecutive successes on this protocol after
an earlier 18-agent haiku batch had a 61% defect rate).

## Scope exclusions (hard, permanent, governor-enforced -- not just prose)

- Heavy-equipment control (excavators, trenchers, directional-boring
  rigs, cranes) -- outside this actor's authority entirely; the closed
  op-allowlist cannot express it, and the governor's
  `forbidden-action-class` HARD check independently rejects any proposal
  whose `:value` carries an `:equipment-control?`/`:direct-actuation?`
  marker, un-overridable by any human approval.
- Utility-tie-in / energization-authorization authority -- connecting a
  new water/sewer/gas/electric/telecom line to a live main, or
  starting flow/pressure/current on it -- the licensed utility
  engineer's / site supervisor's exclusively. `:schedule-construction-
  operation` proposes a trenching/pipe-laying/tie-in-crew phase window,
  NEVER a finalized tie-in or energization decision; the same
  `forbidden-action-class` check rejects any proposal carrying a
  `:finalizes-tie-in-authorization?`/`:finalizes-energization-
  authorization?` marker.
- Real-world actuation of any kind -- every committed record carries
  `:effect :propose` only (`utilconstr.governor`'s `effect-not-propose`
  HARD check, defense-in-depth against a compromised/malfunctioning
  advisor).

## Decision

Implement `cloud-itonami-isic-4220` with Governor, Facts, Registry,
Store, Notify, Advisor, Phase, Operation, Sim (structured after
`cloud-itonami-isic-4210` module-for-module, narrowed/renamed to the
utility-line-construction domain):

- **Governor** (Utility-Construction Governor): 8 hard checks
  (unknown-op, effect-not-propose, forbidden-action-class [equipment-
  control/direct-actuation/tie-in-authorization-finalization/
  energization-authorization-finalization markers], site-not-verified,
  legal-basis-missing, utility-locate-incomplete, notification-lead-
  time-insufficient [`:quantitative` jurisdictions only, independently
  recomputed from the site's own recorded `:notification-lead-hours-
  actual`], unresolved-safety-concern) + high-stakes escalation
  (`:flag-safety-concern` and `:schedule-construction-operation` ALWAYS
  escalate unconditionally at every phase; `:order-supplies` escalates
  above a $5000 USD cost threshold or below the 0.6 confidence floor).
- **Facts**: per-jurisdiction (JPN/USA/DEU) excavation/buried-utility-
  strike-prevention + road-opening/utility-installation-permit
  legal-basis catalog, reusing the SAME real official-source citations
  `cloud-itonami-isic-4210`'s already-verified catalog carries (Japan's
  労働安全衛生規則第355条 utility-locate duty + 道路法第32条／建設リサイクル
  法第10条 168-hour notice; the USA's OSHA 29 CFR 1926.651(b) 24-hour
  utility-locate floor + 23 CFR 645.213 utility use-and-occupancy
  permit -- the most literally on-point citation in this whole fleet for
  this ISIC class -- + 23 CFR 630 Subpart J/MUTCD Part 6 work-zone
  traffic control; the EU/Germany's Directive 92/57/EEC Art.3 +
  StVO §45 Abs.6, honestly `:qualitative`, no fabricated numeric
  lead-time), reframed for utility-line construction (these statutes
  govern excavation/buried-utility-strike prevention generically, so
  reusing them is not fabrication -- it is the same real legal exposure
  applied to the same excavation activity). Pipeline-specific technical-
  safety regimes (USA 49 CFR Parts 192/195; Japan ガス事業法/電気事業法
  tie-in/energization technical rules) are honestly noted as real but
  out of scope for this R0 catalog, the same disciplined exclusion
  `cloud-itonami-isic-4210` gives FRA track law -- and are exactly WHY
  the governor permanently blocks tie-in/energization-authorization
  proposals (that authority belongs to the licensed engineer operating
  under those technical codes).
- **Registry**: pure-function site-record-log/schedule-proposal/
  safety-concern-flag/supply-order-proposal drafts (jurisdiction-scoped
  sequence numbering) + `render-safety-concern-notice` (human-readable
  document, legal basis inline, explicitly disclaims tie-in/
  energization-authorization authority in its own `## Status` section).
- **Store**: `Store` protocol, dual `MemStore`/`DatomicStore`
  (`langchain.db`), contract-tested for parity. `DatomicStore` uses
  `langchain-store.core` (ADR-2607141600) for the EDN-blob codec/
  identity-schema/event-log pattern via a data-driven entity field-spec.
  Like every sibling coordination-only actor, no history is
  double-actuation-guarded -- every op may recur any number of times per
  site, since this actor never actually dispatches/authorizes/
  finalizes anything.
- **Notify**: mail+phone (Resend+Twilio) `Notifier` protocol,
  structurally identical to `roadrail.notify`, fires only after human
  approval of `:flag-safety-concern` (never on an auto-commit path).
- **Advisor**: `Advisor` protocol + deterministic `mock-advisor`
  (default) + `llm-advisor` (real-inference seam), all four ops carry
  `:effect :propose`.
- **Phase**: 0→3 rollout gate; `:log-site-record`/`:order-supplies` are
  the ONLY ops ever in a phase's `:auto` set (phase 3);
  `:schedule-construction-operation`/`:flag-safety-concern` permanently
  absent from every phase's `:auto` set.
- **Operation**: langgraph-clj StateGraph actor, real
  `interrupt-before #{:request-approval}` human-in-the-loop + checkpoint
  resume, structurally identical to `roadrail.operation`.
- **Sim**: demo driver (`clojure -M:dev:run`), 8 seeded sites (water-
  main renewal, fiber backbone, sewer extension, gas tie-in, electric
  duct-bank, water service line, FTTH trunk, gas distribution renewal),
  full episode + all 8 HARD-hold paths + cross-jurisdiction (USA) +
  qualitative (DEU/EU) walkthrough.
- **Operations** (closed allowlist, all `:effect :propose`):
  `:log-site-record` (utility-locate/trenching-progress data logging,
  auto-eligible at phase 3), `:schedule-construction-operation`
  (trenching/pipe-laying/tie-in-crew scheduling proposal, ALWAYS
  escalates), `:flag-safety-concern` (utility-strike/excavation-
  collapse/gas-leak concern, ALWAYS escalates), `:order-supplies`
  (pipe/cable/equipment procurement proposal, cost-threshold-gated).
- **Tests**: 68 tests / 258 assertions, all green.
- All `.cljc` (portable, no JVM interop in `src`), AGPL-3.0-or-later.
- README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY domain-adapted
  for utility-line-construction coordination.

## Consequences

(+) Construction of utility projects (ISIC 4220) utility-line-
construction-project operations coordination is now genuinely
implemented and fully tested.

(+) Scope boundaries (no heavy-equipment control, no utility-tie-in/
energization-authorization authority, no real-world actuation of any
kind) are hardcoded in governor checks (`forbidden-action-class`,
`effect-not-propose`), not just asserted in prose -- covered by
dedicated tests exercising the governor directly with
hypothetically-compromised-advisor proposals the deterministic mock
advisor itself never produces.

(+) Safety-critical escalation (`:flag-safety-concern` and
`:schedule-construction-operation` ALWAYS human, at every phase,
unconditionally) enforced at two independent layers (`utilconstr.phase`'s
`:auto` set and `utilconstr.governor`'s `high-stakes` set), not an
add-on.

(+) Per-jurisdiction legal-basis catalog reuses REAL, already-verified
official sources from `cloud-itonami-isic-4210`'s own catalog, honestly
reframed rather than re-fabricated -- the underlying excavation/buried-
utility-strike-prevention and utility-installation-permit statutes apply
identically to this ISIC class, and the USA's 23 CFR 645.213 citation is
in fact a more literal fit here than it was for roads/railways.

(+) `DatomicStore` uses the current `langchain-store.core` convention
(ADR-2607141600).

(+) Portable `.cljc`, zero JVM-only constructs in `src`; clj-kondo 0
errors (1 pre-existing-pattern warning shared with `roadrail.notify` in
`cloud-itonami-isic-4210` itself, not a regression).

(-) Real Datomic/kotoba-server deployment is a follow-up (in-memory
`MemStore` is the tested default), matching every sibling actor's
current deployment status.

(-) `utilconstr.facts` catalog covers only JPN/USA/DEU (3 of ~194
jurisdictions) -- an honestly-reported starting catalog, matching
`roadrail.facts`'s own disclosed scope. Pipeline-specific technical-
safety regimes (gas/hazardous-liquid pipeline codes, tie-in/
energization technical rules) are explicitly out of scope for this R0
slice -- a documented future extension.

## Verification

- `cloud-itonami-isic-4220`: `clojure -M:test` → "Ran 68 tests containing
  258 assertions. 0 failures, 0 errors." `clojure -M:lint` → 0 errors, 1
  warning (`clojure.string` required-but-unused-under-cljs-analysis in
  `utilconstr.notify`, confirmed to be the SAME pre-existing warning
  `roadrail.notify` in `cloud-itonami-isic-4210` itself carries -- not a
  regression). `clojure -M:dev:run` → demo runs end-to-end through every
  op and all HARD-hold paths, safety-concern notice document renders
  with real legal citations, no exceptions.
- Pushed to `main` as a single scaffold commit,
  `d74ac99863c143fc884697a1e63216ccc853d6c8` (no rename/correction
  needed -- ISIC 4220 was confirmed correct against the
  `kotoba-lang/industry` registry, `:id "4220"` = `:name "Construction of
  utility projects"`, before scaffolding).
