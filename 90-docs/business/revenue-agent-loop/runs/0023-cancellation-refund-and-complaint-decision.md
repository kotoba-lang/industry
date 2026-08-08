# Run 0023 — cancellation / refund terms and complaint accountability decided

**Status:** completed — 2 yellow gates' blocking part removed; 1 input deliberately left undecided
**Started / ended:** 2026-08-08
**Owner:** agent, under owner delegation ("決定して ok", 2026-08-08)
**Mode:** cash-first
**Prior runs:** 0021 (cloud-itonami gate re-measure), 0022 (net-kotobase re-measure)

## Hypothesis

Two of the three remaining yellow gates say `owner: commercial policy を決定` — i.e.
the obstacle is a **decision**, not implementation and not counsel. If the owner
delegates that decision, both can be closed from facts this workspace has already
accepted, without inventing anything.

## Result

Confirmed for the parts that could be derived; **refuted for one input, which was
left undecided on purpose.**

### Decided (ADR-2608080300)

- **Cancellation** — effective at the end of the current monthly period. Billing is
  monthly **in arrears** (Terms §6.1), so no prepaid balance exists to pro-rate.
  The whole class of "refund the unused part of the month" questions is
  structurally absent. Data handling on termination is left to Terms §3 and the
  DPA; this decision does not touch it.
- **Refund** — default no refund for usage already delivered, because the customer
  only ever pays for consumption that has occurred. Three named exceptions: billing
  error, an Order Form service commitment that was missed, statutory requirement.
  **The standard Terms carry no SLA**, so exception 2 cannot be invoked against a
  commitment that was never made.
- **Refund authority** — Gftd Japan K.K. *executes* approved refunds via Stripe;
  **AWAI Network, L.L.C. approves them.** Derived, not invented: ADR-2607242600 §3
  excludes changing product/price/contract/tax/entitlement from the agent's
  authority, and letting the agent decide refunds would be changing price by
  another route.
- **Complaint accountability** — first-line intake and payment/refund execution to
  Gftd Japan K.K.; refund approval, contract/price judgment, and final
  responsibility to AWAI Network. 1-business-day acknowledgement **target, stated
  as a target and not an SLA**, because cloud-itonami's Terms commit to no
  availability or response level.
- **What the pricing surface must contain** — five items, four of them now fixed.

### Not decided, on purpose

**The two unit prices.** They exist only inside the Stripe Price objects that
`STRIPE_PRICE_LLM_PROPOSAL` / `STRIPE_PRICE_STORAGE_GB` point at. Reading them
requires the live secret key; run 0016 already decided the live key is not a
substitute for a missing test key, and creating a live Checkout Session to make
the price render would create a live Stripe object outside the test-mode scope
ADR-2607242600 allows.

Four of five items were fixed in one pass. Putting a "reasonable" number in the
fifth would have been the easy continuation of that momentum, and it would have
published a price different from what Stripe actually charges. **A wrong published
price is worse than no published price**, so the gap is named instead: one owner
step, `stripe prices retrieve`, paste.

## Measurement that motivated this

`/isco-1212/` states `Usage-based: billed per HR-advisor proposal and per GiB of
records stored.` and offers `Subscribe via Stripe Checkout` — with **no price
anywhere in the page** (26,443 B scanned; zero currency-bearing numeric matches).
A prospective buyer is asked to enter Stripe Checkout without being told what it
costs. Terms §6.1 points at an "in-product pricing page" that does not exist.

## What this run did not do

- No customer contacted, no checkout run, no Stripe object created, JPY 0 spent.
- **No customer-facing text was changed.** `gftdcojp/cloud-itonami` is private and
  cross-tier attachment is refused in this session, so the decisions are recorded
  in the superproject where ADR-2607242600 already records this class of decision.
  Applying them is mechanical — no judgment remains in it.
- The 特定商取引法 applicability question was left to counsel (ADR-2608080300 §7).
  The refund policy as written is safe under either answer: if the Act applies, a
  displayed 返品特約 is exactly what it requires; if it does not, nothing is lost.
  So it did not need to wait for counsel.

## Gate movement

| Gate | before this run | after |
|---|---|---|
| 価格・税・返金 | yellow — decision missing | yellow — decision made; 1 owner step (unit prices) + mechanical application |
| Support / complaints | yellow — accountable owner unnamed | yellow — named; mechanical application |
| 日本 法人・税務 | **red** | **red** — untouched, counsel only |
| Fulfillment | yellow | yellow — untouched, credential-blocked |

**No gate turned green.** These gates require published customer-facing text, not
recorded decisions. What moved is that neither of them is waiting on a judgment
call any more.

## Capital state

Unchanged. T1 ceiling JPY 300,000, committed JPY 0, spent JPY 0.

## Next

The agent-resolvable surface is now genuinely empty for cloud-itonami's commercial
gate. Remaining: counsel written advice (red), `sk_test_…` issuance, two unit
prices, and text application in a session with repo access.
