# ADR-2607040300: cloud-itonami-iso3166-jpn — agency maturity promotion, batch 3 (MAFF / MLIT / MOE)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

Continuing the self-paced `/loop` maturity/coverage promotion started by
ADR-2607040100 (Japan agency-level extension) and ADR-2607040200 (batch 2:
MOJ/MHLW/FSA). This is batch 3.

## Decision

Promote 3 more Japan agency entries from `:spec` to `:blueprint`, all
ministries, diversifying into domains not yet covered:

| Code | Body | Ooyake ID | Blueprint |
|---|---|---|---|
| `JPN-MAFF` | 農林水産省 (ministry) | `gov.jpn.maff` | Independent MAFF Food-Safety & JAS-Certification Compliance Service |
| `JPN-MLIT` | 国土交通省 (ministry) | `gov.jpn.mlit` | Independent MLIT Construction-Business Licensing Compliance Service |
| `JPN-MOE` | 環境省 (ministry) | `gov.jpn.moe` | Independent MOE Environmental-Assessment & Waste-Permit Compliance Service |

- **MAFF**: food-safety classification/labeling and JAS (日本農林規格)
  certification navigation for an operator supplying food/agricultural
  products under a public food-service contract.
- **MLIT**: construction-business licensing (建設業許可, Construction
  Business Act/建設業法) for an operator bidding on public-works
  infrastructure contracts, plus building-code (建築基準法) compliance.
- **MOE**: environmental impact assessment (環境アセスメント) screening
  and Waste Management Act (廃棄物処理法) permit compliance — the
  national regulatory-agency counterpart to `cloud-itonami-cofog-05.1`'s
  jurisdiction-agnostic local waste-collection operator template.

Same structure, robotics exemption, and actuation gate as batches 1-2
(README + blueprint.edn + docs/business-model.md + docs/operator-guide.md
+ governance docs + AGPL-3.0-or-later, `:itonami.blueprint/robotics
false`, `:required-technologies [:identity :forms :dmn :bpmn
:audit-ledger]`, `:filing/submit` never automated).

Registry now stands at 16/212 total blueprints (5 country + 11 Japan
agency); 12 tests / 562 assertions, all green.

## Consequences

- (+) Japan agency coverage grows from 8/19 to 11/19 (>50% of the family).
- (+) MOE's blueprint explicitly cross-references `cloud-itonami-cofog-05.1`
  in its business-model boundary section, clarifying the national-agency
  vs. local-operator relationship for waste-adjacent work.
- (−) 8 Japan agencies remain `:spec` (CAO, MIC, MOFA, MEXT, MOD,
  Reconstruction Agency, Board of Audit, Statistics Japan); no other
  country has any agency-level breakdown yet.
- superproject registration: no new repo; `kotoba-lang/iso3166`'s existing
  west.yml pin advances to the new commit. The 3 new
  `cloud-itonami-iso3166-jpn-*` blueprint repos remain standalone.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-jpn-{maff,mlit,moe}` (3 repos)

## References

- ADR-2607040100 (cloud-itonami-iso3166-jpn agency-level extension)
- ADR-2607040200 (batch 2: MOJ/MHLW/FSA)
- ADR-2607031600 (cloud-itonami-cofog) — `cloud-itonami-cofog-05.1`
  boundary referenced from the MOE blueprint.
