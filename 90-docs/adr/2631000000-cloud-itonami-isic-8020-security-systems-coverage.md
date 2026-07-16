# ADR-2631000000: cloud-itonami-isic-8020 (Security systems service activities) filled in and promoted to `:implemented`

## Status

Accepted

## Related

- ADR-2607103500 (`cloud-itonami-isic-8020` own blueprint-publication
  ADR, 2026-07-10 -- the pre-existing boilerplate this build fills in)
- ADR-2628080000 (`cloud-itonami-isic-4912`, freight rail transport --
  the nearest structural sibling; this build's `secsys.*` module
  mirrors `railfreight.*` module-for-module)
- `cloud-itonami-isic-6511`'s own `docs/adr/0001-architecture.md`
  (origin of the general governed-actor architecture pattern)
- the full `:adr/related` chain in the companion `.edn` file

## Context

`cloud-itonami-isic-8020` ("Security systems service activities") was,
like several other recent promotions in this fleet, an **UNUSUAL
case**: `gh api repos/cloud-itonami/cloud-itonami-isic-8020` confirmed
a PRE-EXISTING repository already published at `:blueprint` tier (via
ADR-2607103500, 2026-07-10) — `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`/
`docs/` only, no `deps.edn`/`src`/`test`. This build ADDED the missing
implementation module set on top of that existing boilerplate rather
than re-scaffolding the repository (docs kept, not recreated).

Before any work began, the registry's own live `:name` for `{:id
"8020" ...}` was independently re-verified against a fresh clone of
`kotoba-lang/industry` and confirmed to read exactly "Security systems
service activities" (no truncation, no mismatch) — this fleet's own
ID/name-mismatch caution.

The pre-existing `README.md`/`docs/business-model.md`/`docs/
operator-guide.md` describe a broader aspirational business
(robotics-assisted installation-support, monitoring-center escalation,
full technician-dispatch workflow, response-time compliance). This
build deliberately implements a NARROWER, explicitly-scoped slice: a
security-systems-services OPERATIONS COORDINATION actor, distinct from
`cloud-itonami-isic-8010` (private/personnel-based security guarding)
and `cloud-itonami-isic-8030` (investigation activities) per this
blueprint's own README `Scope note` — the pre-existing README already
drew this distinction carefully (California BSIS's separate "Alarm
Company Operator" vs. "Private Patrol Operator" licenses; UK SIA's
separate "Public Space Surveillance (CCTV)" licensing; Japan's 警備業法
treating 機械警備業務 as its own registration category).

## Decision

Build `secsys` (SecuritySystems-LLM ⊣ Security Systems Governor)
implementing the same langgraph StateGraph + independent Governor +
Phase 0→3 rollout pattern as `railfreight`/4912 (the nearest
structural sibling — same required-technologies/operating-states
shape as `cloud-itonami-isic-7830`, a `:labor`-tech-flagged
register/match/dispatch/follow-up domain), closed to a four-member op
allowlist:

- `:log-monitoring-record` — client-site/monitored-system (alarm/
  CCTV/access-control) data logging
- `:schedule-installation-operation` — installation/maintenance
  scheduling proposal
- `:flag-security-concern` — surfaces a system-fault/tamper/
  unresolved-alarm concern; ALWAYS escalates
- `:coordinate-equipment-supply` — hardware procurement coordination

This actor is explicitly NOT the alarm-response dispatcher and NOT the
access-control-override authority — deciding to dispatch police/fire
in response to an alarm is always a human/emergency-services decision,
never this actor's, and neither is finalizing an access-control-
override decision.

### Decision 1: TWO independent layers block any alarm-response-dispatch or access-control-override proposal

Every proposal carries a literal `:effect :propose` (never an
actuation) and an `:action` drawn from a four-member closed allowlist
(`secsys.governor/allowed-actions`). A proposal to directly finalize
an alarm-response-dispatch decision or an access-control-override
decision cannot be represented in this allowlist at all —
`action-allowlist-violations`/`op-allowlist-violations` hard-block
structurally. A SECOND, independent layer, `scope-exclusion-
violations`, text-scans the proposal's own rationale/summary for the
same class of forbidden finalization ACTION phrases, catching a
proposal that merely names a forbidden act in prose without a matching
`:action`. Both are HARD, permanent, un-overridable blocks (HOLD never
reaches human approval).

### Decision 2: scope-exclusion terms phrased as ACTIONS, never bare nouns — this fleet's known self-tripping bug class, guarded by construction AND by a dedicated test

Multiple sibling agents in this fleet have independently discovered
and fixed the SAME bug: a governor's own scope-exclusion term list
phrased as a bare noun (e.g. "response", "dispatch", "override")
accidentally matches inside the mock advisor's OWN default rationale/
disclaimer text for a legitimate, allowed proposal — causing the actor
to self-block on its own happy path. `secsys.governor/scope-
exclusion-actions` is phrased as full finalization ACTION phrases
("dispatch police response for this alarm", "override the access-
control system for this site", "finalize the access-control
override") rather than bare nouns. `test/secsys/
governor_self_trip_test.clj` is the actual guarantee, not wording care
alone: it runs the default mock advisor's `infer` across every op and
every seeded demo site (including the permit/open-concern/already-
open/no-spec-basis branches) and asserts none of the resulting
proposals trip the scope-exclusion check, plus a belt-and-suspenders
direct literal-substring check.

### Decision 3: "record must be independently verified/registered before ANY action" applies to all three non-registration ops

`record-not-verified-violations` gates `:schedule-installation-
operation`, `:flag-security-concern`, AND `:coordinate-equipment-
supply` alike on the subject site's own `:registered?` fact (set only
by a committed `:log-monitoring-record` with a valid spec-basis
citation) — matching this blueprint's own hard invariant text
literally ("a system/client-site record must be independently
verified/registered before any action").

### Decision 4: `:flag-security-concern` always escalates — TWO independent layers

`secsys.governor/high-stakes` includes `:security/flag-concern`
(confidence/actuation gate always escalates), AND `secsys.phase/
phases` never includes `:flag-security-concern` in any phase's
`:auto` set (structural). Both `secsys.phase-test` and `secsys.
governor-contract-test` assert this independently.
`:schedule-installation-operation`/`:coordinate-equipment-supply` get
the same double-guard, for the same reason this actor coordinates but
never authorizes.

### Decision 5: hand-rolled EDN-blob codec, not `kotoba-lang/langchain-store`

`kotoba-lang/langchain-store` (ADR-2607141600) is the newer shared
substrate for this pattern and the preferred path for NEW stores. This
build instead mirrors `railfreight`/4912's own hand-rolled `enc`/
`dec*` exactly, to avoid combining two different `langchain`/
`langchain-clj` coordinate families on one classpath while this
actor's own `-M:test` path (no `:dev` override) only resolves
`kotoba-lang/langgraph`'s transitive `langchain` via `:git/sha`. A
reasonable, low-risk migration follow-up once touched again, per this
workspace's own "touched, migrate incrementally" policy.

### Decision 6: minimal registry.edn diff — `:maturity` only

This promotion adds ONLY `:maturity :implemented` to the registry
entry. `:repo`/`:business-id` were already correct
(`cloud-itonami-8020`) and were left untouched, as were
`:required-technologies`/`:operating-states` — a deliberately minimal,
exact-block diff.

## Concurrency

This registry (`kotoba-lang/industry`) and its `test/kotoba/
industry_test.clj` were, at the time of this promotion, under
EXTREMELY active concurrent fleet load. Both sibling verticals named
in this actor's own README `Scope note` — `cloud-itonami-isic-8010`
(Private security activities) and `cloud-itonami-isic-8030`
(Investigation activities) — were independently promoted from
`:blueprint` to `:implemented` by concurrent sibling fleet agents in
overlapping windows around this same promotion, confirmed via
repeated freshly re-fetched `(kotoba.industry/maturity-summary)`
recomputes: an initial baseline read at the start of this session
already showed `{:blueprint 16, :implemented 401}` (already stale
relative to the test file's own prior-synced count, from other
concurrent activity before this session even began), which continued
to drift (`16→14→13→12` blueprint, `401→403→404→405` implemented)
across repeated re-fetches during this single promotion's own work.
`test/kotoba/industry_test.clj`'s own per-id "live-state
corroboration" test for `"8010"` (asserting `:blueprint`) was found
stale mid-session (the sibling promotion having already landed in the
registry) and was, by the time of this session's own final PUT,
already fixed by a THIRD concurrent sibling agent (working on ISIC
5229) rather than by this session — confirmed via a direct diff
before touching that region, so this session's own edit was reduced
to strictly ADDING the missing detailed `"8020"` corroboration block,
not re-touching the `"8010"`/`"8020"` bare-corroboration lines a
concurrent agent had already converted to superseded-comments. The
`test/kotoba/industry_test.clj` PUT itself required two attempts
(first attempt 409'd against a sha that had already moved between
fetch and PUT); the second attempt, built against an immediately
re-fetched sha, landed cleanly.

## Consequences

- `cloud-itonami-isic-8020` promoted from `:blueprint` to
  `:implemented`. Fleet maturity as independently observed at the
  START of this session (before this promotion, before either sibling
  concurrent promotion, and already reflecting unrelated prior
  concurrent activity): `{:total 649, :spec 232, :blueprint 16,
  :implemented 401}`. Final, live-recomputed state immediately before
  this session's own final `test/kotoba/industry_test.clj` PUT (after
  this promotion's own `+1`, PLUS concurrent sibling promotions of
  `cloud-itonami-isic-8010`/`cloud-itonami-isic-8030` and others
  landed in the same fast-moving window): `{:total 649, :spec 232,
  :blueprint 12, :implemented 405}` — live-recomputed via
  `(kotoba.industry/maturity-summary)` against a freshly re-fetched
  `origin/main`, not assumed; re-confirmed identical via a THIRD,
  independent fresh clone after this session's own final PUT.
- 44 tests / 405 assertions pass in the actor repo (`clojure -M:test`
  and `clojure -M:dev:test`, identical); clj-kondo (`clojure -M:lint`)
  0 errors / 0 warnings; the demo (`clojure -M:dev:run`) walks one
  clean record-log + installation-schedule + equipment-supply-
  coordination + concern-flag lifecycle, plus seven HARD-hold
  scenarios, end-to-end, with no exceptions — independently
  re-verified against a fresh post-push clone.
- `kotoba-lang/industry`'s own full test suite (15 tests / 1063
  assertions) re-run clean from a brand-new fresh clone (plus a fresh
  `kotoba-lang/technology` sibling) after this promotion's own final
  PUT, with no mojibake detected in `registry.edn`.
- Establishes, for the security-systems-services domain specifically,
  the same "operations coordination, not dispatch/override authority"
  pattern several other siblings in this fleet have independently
  converged on: closed four-op allowlist, literal `:effect :propose`,
  scope-exclusion phrased as finalization ACTIONS never bare nouns, a
  dedicated self-trip regression test, and a "flag a concern" op that
  is always escalate-only, never auto-eligible, at any phase.

## Alternatives considered

- **Wrapping `kotoba-lang/robotics`/`kotoba-lang/labor` immediately**,
  per the pre-existing README `Capability layer` section. Deferred:
  none of this actor's four ops touch a validated external format the
  way, e.g., a parcel tracking number does; documented as an explicit
  follow-up in the child repo's own `docs/adr/0001-architecture.md`.
- **Adopting `kotoba-lang/langchain-store` immediately.** Deferred per
  Decision 5 above — a reasonable near-term follow-up, not a
  rejection.
- **Fixing the concurrently-discovered stale `"8010"` corroboration
  test as part of this session's own edit.** Unnecessary: a THIRD
  concurrent sibling agent (working on ISIC 5229) had already landed
  that specific fix by the time of this session's own final
  pre-PUT fetch, confirmed via direct diff; re-doing it would have
  created a redundant/conflicting edit on an already-resolved region.

## References

- `cloud-itonami-isic-8020/docs/adr/0001-architecture.md` (child-repo
  ADR, full decision structure)
- `cloud-itonami-isic-4912/docs/adr/0001-architecture.md` (nearest
  structural sibling; mirrored closely, substituting the
  security-systems-services domain for freight-rail)
- `kotoba-lang/langchain-store` (ADR-2607141600; deferred adoption)
- California Business and Professions Code, Chapter 11.6 (Alarm
  Company Act) — Bureau of Security and Investigative Services (BSIS)
- Private Security Industry Act 2001 — UK Security Industry Authority
  (SIA), "Public Space Surveillance (CCTV)"
- 警備業法 (Security Business Act) 第2条第1項第4号 (機械警備業務) — Japan,
  National Police Agency / Prefectural Public Safety Commissions
