# cloud-itonami Integration

`cloud-itonami-*` repositories publish business models. `kotoba-industry`
declares what technology capabilities are required to run them.

Runtime flow:

```text
cloud-itonami-{isic}
        |
        v
kotoba.industry/execution-plan
        |
        v
kotoba.technology/stack
        |
        v
concrete repos and operator services
```

The business UI should show:

- operating states
- required technologies
- missing technologies
- available operator services
- proof and audit contracts

The business should not import a CFD or EDA implementation directly. It should
request the capability contract from `kotoba-technology`.

## Maturity & readiness

`kotoba.industry/maturity-summary` and `execution-plan` expose per-vertical
maturity and UIUX/export readiness so an operator console can show them:

| Maturity tier | Meaning |
|---|---|
| `:implemented` | source actor exists (reference implementation) |
| `:blueprint` | blueprint repo published (`:repo` set) |
| `:spec` | registry entry only (blueprint repo pending) |

Current state (ISIC Rev.4 class coverage 100%):

- Total entries: 643 (425 classes + 218 groups)
- Sections: 21/21, groups: 238/238
- `:implemented` 54 · `:blueprint` 30 · `:spec` 546
- UI+export ready (backed by a capability lib with `:ui?`/`:export?`): 643/643

Every entry requires `:robotics` (ADR-2607011000 robotics-premise): a robot
performs the physical domain work under an actor + independent governor.

The business UI should also surface the `:ui-ready?`/`:export-ready?` flags
from `execution-plan` so an operator knows whether a console and audit export
exist for a given vertical before wiring it.
