# Run 0004 — net-babiniku payment preflight

**Status:** stopped at product payment hard gate
**Started:** 2026-07-24
**Ended:** 2026-07-24

## Hypothesis

The live Base-USDC verified-tip implementation might permit a lower-friction,
nonadult consumer payment experiment while cloud-itonami commercial gates are
closed.

## Result

The hypothesis is rejected by the product's own business source of truth:

- `docs/bmc-lean-loop-log.md` says every subscription/PPV/tip monetization
  proposal HARD-holds until a PSP/crypto rail is contracted.
- It explicitly says no live paid transaction path is open and monetization
  measurement is not yet possible.
- The on-chain receipt verifier and verified-tip reaction machinery are
  technical integrity components; they are not authorization to sell.

No price change, payment solicitation, wallet transaction, or deployment was
performed.

## Score update

Payment readiness changes `3 → 1`; score `52 → 48`; hard gate changes
`yellow → red`.

## Decision

`stop`

Do not route around `unprovisioned-capability`. Reopen only after the operator
contracts and documents the rail, product terms and fulfillment.
