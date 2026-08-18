# Run 0020 — capital-allocation governor

**Status:** completed — T1 designed, external execution held
**Started / ended:** 2026-07-24
**Owner:** agent / human
**Mode:** cash-first

## Hypothesis

If the portfolio governs the proposed JPY 3,000,000 as evidence-gated capital
rather than a one-time spend authorization, then it can test one high-ticket
offer with at most JPY 300,000 at risk and preserve JPY 2,700,000 when the
buyer, message, channel, or offer is wrong.

## Result

ADR-2607246100 is accepted as the authoritative capital-allocation rule.
The revenue-loop contract and run template now require tranche state, cash at
risk, actual contribution, CAC, payback, founder hours, and tranche verdict.

The initial commercial hypothesis is:

- JPY 20,000 paid diagnostic, fully credited to implementation;
- JPY 300,000–500,000 AI Revenue/Sales Pipeline implementation;
- JPY 80,000/month managed improvement;
- cloud-itonami as delivery and measurement substrate.

T1 has a JPY 300,000 ceiling. Paid advertising remains held until manual sales
produces a verified non-owner payment and successful fulfillment. T2–T4 remain
held. Investor outreach remains outside the revenue objective because financing
cash is not revenue or profit.

No customer was contacted, no campaign was launched, no financial commitment
was made, and no revenue score increased in this governance-only run.

## Capital state

| Tranche | Ceiling | Status | Release evidence |
|---|---:|---|---|
| T1 | JPY 300,000 | released as ceiling; JPY 0 committed/spent | ADR accepted |
| T2 | JPY 700,000 | held | first verified non-owner payment + fulfillment |
| T3 | JPY 1,000,000 | held | at least two customers + repeatable close |
| T4 | JPY 1,000,000 | held | observed payback <= 3 months + positive contribution |

## Decision

`continue`

Next single action: close the remaining cloud-itonami commercial gate and select
one buyer segment with a verifiable 100-account population for T1. Do not start
paid acquisition or count financing activity as revenue.
