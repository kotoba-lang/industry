# ADR 2607142300: cloud-itonami ISIC 3530 — Steam and Air Conditioning Supply Coverage

**Status**: Implemented  
**Date**: 2026-07-14T23:00:00Z

## Context

ISIC Rev.5 3530 (Steam and air conditioning supply) is the third utility supply actor in the cloud-itonami series, following 3510 (Electric power) and 3520 (Gas manufacturing). Steam and chilled-water distribution networks share the same governance structure as gas supply: customer intake, meter verification, supply provisioning/suspension with protected-recipient safety gates.

## Decision

Created `cloud-itonami-isic-3530` as a public OSS repository containing:

### Core Components

1. **Blueprint**: steam/chilled-water supply actor with thermal-safety-governor
2. **Source**: Full `.cljc` portable implementation (JVM/ClojureScript/GraalVM)
   - `steam.facts`: jurisdiction thermal-safety requirements catalog (JPN/USA/GBR)
   - `steam.governor`: Thermal Safety Governor (5 HARD gates + 1 SOFT gate)
   - `steam.store`: Protocol-based SSoT (MemStore + future DatomicStore)
   - `steam.phase`: Lifecycle phase table (actuation always human-gated)
   - `steam.registry`: Provision/suspension draft generation
   - `steam.advisor`: Mock advisor for demo/test
   - `steam.operation`: OperationActor StateGraph binding
3. **Tests**: 21 unit tests covering facts, governor contract, store contract, phases
4. **Docs**: business-model.md, operator-guide.md covering deployment and regulatory  
5. **Governance**: CODE_OF_CONDUCT.md, CONTRIBUTING.md, SECURITY.md, GOVERNANCE.md
6. **License**: AGPL-3.0-or-later (same as 3510/3520)

### Testing Status

- All tests green: 21/21 pass
- Linting clean: 0 errors, 54 warnings (protocol stubs)
- Repository: https://github.com/cloud-itonami/cloud-itonami-isic-3530

## Rationale

Steam/AC supply distribution (heating, cooling, process steam) is a critical infrastructure layer in many municipalities and industrial complexes. The actor-per-ISIC pattern (3510→3520→3530) establishes that utility supply governance is a repeatable, spec-cited, audit-ledger scaffold that any jurisdiction can adapt without rebuilding compliance from scratch.

The steam/AC domain adds novel safety considerations:
- Temperature monitoring and pressure relief
- Heat-exchanger certification
- Thermal meter accuracy (BTU/kWh)

These are modeled in `steam.facts` with jurisdiction-specific citations.

Protected-recipient gates apply doubly: not only hospitals/emergency services but also district heating plants (utility critical-infrastructure) can never be suspended.

## Consequences

- **Registry**: `kotoba-lang/industry` gains ISIC 3530 entry pointing to this repo
- **Deployment**: Operators can now fork and deploy thermal supply management without SaaS rent
- **Compliance**: Scaffold (Thermal Safety Governor) is auditable and jurisdiction-extensible
- **Actuation**: Actuation (real supply provision/suspension) remains human-gated by law of two layers
  - Governor HARD gate (:protected-recipient, :no-spec-basis, :evidence-incomplete, :already-*-ed)
  - Phase table (:actuation/* never in :auto set)

## Addendum 1: Jurisdiction Catalog

`steam.facts` covers starting catalog with official spec-basis:

- **:JPN**: High Pressure Gas Safety Act §24/28/31/32/33 (steam networks)
- **:USA**: ASME PTC 4.4, NIST Handbook 44 (thermal metering)
- **:GBR**: Heat Networks (Metering) Regulations

**Coverage**: 3/194 jurisdictions (honest report, not global claim)

## Addendum 2: Protected-Recipient Logic

The actor prevents disconnection of life-critical customers:

- Hospitals, fire stations, emergency services: `protected-recipient? true`
- District heating plants, utility substations: also `protected-recipient? true`
- Meter can **never** be suspended even with human approval (HARD gate, rule `:protected-recipient`)

Mirrors 3510/3520 pattern but domain-specific: **thermal > electrical > gas** in criticality order.

## Addendum 3: Demo and Testing

- **MemStore**: in-memory atom (EDN) for dev/test/offline runs
- **MockAdvisor**: canned proposals for demo workflows
- **sim.cljc**: `clj -M:dev:run` drives provision and suspension scenarios end-to-end
- **Test suite**: 21 tests pass (facts, governor, store, phase)
- **Dependencies**: Only `langgraph-clj` (transitively `langchain-clj`)
