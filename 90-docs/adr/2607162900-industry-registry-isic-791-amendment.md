# ADR-2607162900: Industry Registry Amendment — ISIC 791 Implementation

**Status:** Implemented  
**Date:** 2026-07-16  
**Deciders:** Cloud-itonami fleet, kotoba-lang registry maintainers  
**Related:** ADR-2607162800

## Context

ISIC 791 (Travel agency and tour operator activities) actor (`cloud-itonami-isic-791`) has been scaffolded and implemented. This ADR amends the `kotoba-lang/industry` registry to mark isic-791 as `:implemented` with the correct repository URL and business identity.

## Decision

Update the industry registry entry for ISIC 791:

```edn
{:itonami.industry/isic-rev5 "791"
 :itonami.industry/name "Travel agency and tour operator activities"
 :itonami.industry/status :implemented
 :itonami.industry/repo "https://github.com/cloud-itonami/cloud-itonami-isic-791"
 :itonami.industry/business-id "cloud-itonami-791"
 :itonami.industry/governance-url "https://github.com/cloud-itonami/cloud-itonami-isic-791/blob/main/README.md"
 :itonami.industry/adr "ADR-2607162800"
 :itonami.industry/blueprint {:id "cloud-itonami-791"
                              :domain :travel/booking-coordination
                              :governor :travel-agency-governor
                              :maturity :implemented}}
```

### Registry Fields

| Field | Value | Notes |
|-------|-------|-------|
| `isic-rev5` | `791` | ISIC Revision 5 code |
| `name` | "Travel agency and tour operator activities" | UN official industry name |
| `status` | `:implemented` | Active, production-ready actor deployed |
| `repo` | `https://github.com/cloud-itonami/cloud-itonami-isic-791` | Public GitHub repository (verified public, not private) |
| `business-id` | `cloud-itonami-791` | Canonical actor identity (also blueprint ID) |
| `governance-url` | Repository README.md | Human-readable actor governance & operation documentation |
| `adr` | `ADR-2607162800` | Authoritative design decision record |
| `blueprint` | EDN map (see above) | Metadata from `blueprint.edn` in repo |

**No `:robotics` field** (unlike earlier Wave-4 actors): Travel agency booking coordination is pure administrative coordination, requires no robotics/embodied automation.

## Rationale

1. **Status `:implemented`** — Actor is complete, tested, pushed to main, ready for deployment
2. **Repository URL verified public** — `gh api repos/cloud-itonami/cloud-itonami-isic-791 --jq '.private'` returns `false`
3. **No `:robotics`** — Domain is booking logistics, not physical automation
4. **Governor pattern** — `:travel-agency-governor` in blueprint, three HARD checks (booking-verification, effect, scope-exclusion)
5. **Maturity `:implemented`** — Full scaffold, tests, governance files, ADR documented

## Consequences

### Positive

- Registry now correctly reflects isic-791 implementation status
- External systems can query registry to discover travel-agency actor
- Clear audit trail (registry amendment + ADR + GitHub repo link)

### Negative

- None anticipated; registry update is additive, non-breaking

## Alternatives Considered

1. **Mark status `:planning` instead of `:implemented`** — Incorrect; code is deployed to main and tested
2. **Defer registry update** — Inconsistent with prior Wave-4 batches; blocks external discovery

## Related

- **ADR-2607162800:** Actor design specification (SIMPLE pattern, three HARD checks)
- **Repository:** `cloud-itonami/cloud-itonami-isic-791`
- **Blueprint:** `blueprint.edn` in repository root

## Follow-up

Registry update is standalone. No further action required; next Wave-4 batch (isic-792+) follows the same procedure.
