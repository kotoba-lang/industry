# ADR-2607105300: cloud-itonami-iso3166-jpn deepened to `:implemented` (first iso3166 running actor)

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (standing scaffold authorization + coverage-improvement request)

## Context

The iso3166 family (ADR-2607032330) had reached broad `:blueprint`
coverage (71 countries + 19/19 Japan agencies by ADR-2607042900) but
**zero** `:implemented` entries. Closing ADRs explicitly listed
promoting one blueprint to a running actor as the next maturity-ladder
validation. Owner requested design+implementation coverage improvement
after reviewing that Japan/US/EU/China/Russia government-system work
exists only as market-entry compliance blueprints, not as runnable
actors.

Japan is the deepest jurisdiction (country + full agency tree), so it
is the correct first pilot.

## Decision

1. Implement the full governed-actor architecture in
   `cloud-itonami/cloud-itonami-iso3166-jpn` under the `marketentry`
   namespace (Store / Registry / Governor / Phase / OperationActor /
   MockAdvisor / Sim), matching the ISIC fleet template
   (`cloud-itonami-isic-7810` and siblings).
2. Flagship HARD check: `japan-resident-rep-missing` (conditional on
   `:requires-japan-resident-rep?`), grounded in 全省庁統一資格
   domestic office / agent requirements. Companion checks:
   corporate-number verification (法人番号), engagement-fee recompute,
   evidence completeness, spec-basis honesty, double draft/submit
   guards.
3. Flip `kotoba-lang/iso3166` registry entry for `JPN` from
   `:blueprint` to `:implemented`. Agencies remain `:blueprint`.

## Consequences

- Family maturity: `{:implemented 1 :blueprint 92 :spec 119 :total 212}`
  after the concurrent country batch 24 (ADR-2607105400) lands
  (without batch 24: implemented 1, blueprint 89, spec 122).
- 24 tests / 79 assertions green in the child repo; 13 tests / 890
  assertions green in `kotoba-lang/iso3166`.
- Ladder `:spec` → `:blueprint` → `:implemented` is now validated for
  this family. Sibling countries (USA/CHN/RUS/…) promote by forking
  this actor and swapping facts/demo data.

## Artifacts

- actor: `cloud-itonami/cloud-itonami-iso3166-jpn` (`src/marketentry/*`)
- registry: `kotoba-lang/iso3166` (JPN `:maturity :implemented`)
- child ADR: `docs/adr/0001-architecture.md` in the jpn repo

## References

- ADR-2607032330 (family creation)
- ADR-2607040800 / ADR-2607042900 (sweep closings; `:implemented` future work)
- ADR-2607092400 (employmentops/7810 — ISIC template used here)
- ADR-2607105400 (country batch 24: RUS/BEL/AUT, concurrent)
