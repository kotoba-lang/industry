# kotoba-industry

Industry registry for kotoba-lang and itonami open businesses.

This repository maps ISIC-coded businesses to the technology capabilities they
need in order to operate. `cloud-itonami-*` uses this to move from a published
business model to an executable operating stack.

## Contract

```clojure
(require '[kotoba.industry :as industry])

(industry/get-industry "3512")
(industry/required-technologies "3600")
(industry/readiness "8691" #{:forms :identity :audit-ledger :bpmn :dmn})
```

## Layers

- business: customer-facing open business model
- industry: ISIC-coded operating domain
- technology: reusable kotoba-lang capability stack
- implementation: concrete repos, services and operators

## Current ISIC Blueprints

| ISIC | Business | Required technology |
|---:|---|---|
| 3512 | Community Renewable Energy Operations | telemetry, optimization, DMN, BPMN, audit |
| 3600 | Community Water Safety Operations | telemetry, DMN, BPMN, audit, forms |
| 3830 | Local Materials Recovery | forms, telemetry, optimization, audit |
| 6310 | Talent Actor | identity, DMN, BPMN, audit, forms |
| 8569 | Community Learning Support | identity, forms, DMN, BPMN, audit |
| 8691 | Health Access Navigation | identity, forms, DMN, BPMN, audit |
| 8810 | Community Care Coordination | identity, forms, DMN, BPMN, audit |

Engineering/manufacturing ISICs can add EDA/CFD/CAE through the same registry.

## Test

```bash
clojure -M:test
```
