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

Current state (ISIC Rev.4 class coverage 100%, plus a small number of
project-internal "isic-rev5" codes beyond Rev.4 -- see the petroleum-fleet
note below):

- Total entries: 647 (429 classes + 218 groups)
- Sections: 21/21, groups: 238/238
- `:implemented` 147 · `:blueprint` 5 · `:spec` 474 (as of `cloud-itonami-
  isic-6493`'s registration, superproject ADR-2607141700 -- this line had
  drifted from the live registry through many untracked promotion ADRs;
  refreshed to the actual current `kotoba.industry/maturity-summary`
  counts as part of that registration rather than perpetuating the stale
  106/36/504 split)
- UI+export ready (backed by a capability lib with `:ui?`/`:export?`): 647/647

ADR-2607100400 (petroleum supply-chain fleet) added 3 entries with no real
Rev.4 equivalent -- `4671` (fuel wholesale; real Rev.4 code is `4661`),
`4950` (pipeline; real Rev.4 code is `4930`), and `5020` (tanker/water
freight; real Rev.4 splits this into `5012`/`5022`) -- registered under
their built/published `cloud-itonami-isic-*` repo names rather than the
real Rev.4 codes, so `:repo` / GitHub naming / registry `:id` stay
consistent with each other. The real-Rev.4 entries (4661/4930/5012/5022)
remain separately registered at `:spec`. `6493` (Factoring activities,
superproject ADR-2607141700) is a SIMPLER case of the same "Rev.5-only,
beyond Rev.4" category: Rev.4's own division 649 only ever had three
classes (`6491`/`6492`/`6499`) with no factoring-specific slot at all --
Rev.5 split it into six (adding `6493`/`6494`/`6495`) -- so `6493` needed
no shadow Rev.4 entry to preserve, unlike the petroleum fleet's three
diverging-name cases.

Every entry requires `:robotics` (ADR-2607011000 robotics-premise): a robot
performs the physical domain work under an actor + independent governor.

The business UI should also surface the `:ui-ready?`/`:export-ready?` flags
from `execution-plan` so an operator knows whether a console and audit export
exist for a given vertical before wiring it.
