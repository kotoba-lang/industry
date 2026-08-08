# Run 0021 — cloud-itonami commercial gate re-measurement

**Status:** completed — 4 gates closed in the record, 1 red + 3 yellow remain
**Started / ended:** 2026-08-08
**Owner:** agent
**Mode:** cash-first
**Prior run:** 0020 (2026-07-24) — 15 days with no run

## Hypothesis

If every gate in COMMERCIAL-GO-NO-GO.md is re-measured against live evidence rather
than re-read, then some of the reds that are holding every SCORECARD action at
`blocked` will turn out to have been closed already by work that landed after the
table was written.

## Result

Confirmed. **Four of six cloud-itonami reds were already closed and the table had
not been updated for 15 days.** The gate document, not the commercial situation,
was the binding constraint on the loop's own action ranking.

| Gate | 2026-07-24 | 2026-08-08 | What actually closed it |
|---|---|---|---|
| Terms / Privacy | red | **green** | run 0018 owner approval + run 0019 generation; both live 200, effective 2026-07-24, no DRAFT marker |
| DPA / subprocessors | red | **green** | `/legal/dpa/` live 10,047 B; Terms §7.1 incorporates by reference; Privacy §5 names subprocessors |
| Merchant / collection | red | **green** | ADR-2607242600 accepted; Terms §6.3 publicly discloses Gftd Japan as limited collection agent, AWAI as supplier |
| 契約主体 | yellow | **green** | Terms §15: AWAI Network, L.L.C., Delaware file 10704996, contact `hello@gftd.co.jp` |
| 日本 法人・税務 | red | **red** | unchanged — ADR-2607242600 §7 explicitly defers it; no counsel written advice exists |
| 価格・税・返金 | red | **yellow** | currency/cycle/tax closed by Terms §6.1/§6.4; refund and cancellation clauses are absent from the Terms entirely, and the pricing page Terms §6.1 points at does not exist |
| Fulfillment | yellow | **yellow** | rail is complete (`missing:[]`), but no checkout has ever run |

## Measurements taken

All figures are live reads on 2026-08-08, not re-quotations.

```
GET /api/fleet/metrics   → externalTotal 5, externalPaid 0, agentRuns7d 306,
                           selfRegisteredOwners 5, asOf 2026-08-08T09:05:35Z,
                           stripe.activeSubscriptions 0
                           bottleneck: "run Stripe checkout via /isco-1212/"
GET /api/billing/status  → mode "live", stripeConfigured true, webhookReady true,
                           readyForLiveCheckout true, readyForEntitlement true,
                           missing []
GET /legal/terms/        → 200, 15,322 B
GET /legal/privacy/      → 200, 16,902 B
GET /legal/dpa/          → 200, 10,047 B
GET /pricing             → 200, 35,552 B — cockpit SPA fallback, NOT a pricing page
GET /legal/company/      → 200, 35,552 B — same fallback (byte-identical)
```

The `/pricing` result is the one that would have been misread. It answers 200 and
looks like a published price list; it is the same byte count as `/legal/company/`
and `/legal/refund/`, i.e. the catch-all. **Any unknown path on itonami.cloud
returns 200 with the cockpit**, so status code alone cannot establish that a page
exists — byte size against a known-missing path can.

## What did NOT change

`externalPaid` is 0, as it has been at every observation since the funnel was first
measured. `agentRuns7d` is 306, continuing the decline through 2,173 (07-30) and
984 (07-31) — recorded, not smoothed, and unexplained by anything measured here.

## Decision

1. COMMERCIAL-GO-NO-GO.md is rewritten to the measured current state. cloud-itonami
   moves `no-go` → `conditional`.
2. The "smallest path to one green product" recommendation moves from net-kotobase
   to cloud-itonami. net-kotobase was chosen in the prior version specifically to
   avoid the AWAI collection question, and that question is now closed for
   cloud-itonami while net-kotobase has not been re-measured since 2026-07-24.
3. The remaining owner actions are enumerated as four, not six.
4. **No customer was contacted and no checkout was run.** The remaining red
   (Japan registration / tax counsel) is a real legal constraint on soliciting
   payment, not a permissions question, so outbound paid solicitation stays held.

## Capital state

| Tranche | Ceiling | Status | Release evidence |
|---|---:|---|---|
| T1 | JPY 300,000 | released as ceiling; JPY 0 committed/spent | ADR-2607246100 accepted |
| T2 | JPY 700,000 | held | first verified non-owner payment + fulfillment |
| T3 | JPY 1,000,000 | held | at least two customers + repeatable close |
| T4 | JPY 1,000,000 | held | observed payback <= 3 months + positive contribution |

Unchanged. This run spent JPY 0 and moved no tranche.

## Stop condition reached

The agent-resolvable portion of the commercial gate is exhausted. Every remaining
item requires either an authorized human signature/decision (counsel advice, refund
policy, `sk_test_…` issuance, accountable support owner) or a buyer. Continuing to
run this loop without one of those changes produces documentation, not evidence.

## Next

Owner picks up items 1–4 in COMMERCIAL-GO-NO-GO.md § "Smallest path". Item 2
(`sk_test_…`) unblocks the only remaining piece the agent can then finish
mechanically: the test-mode checkout → signed webhook → entitlement E2E record.
