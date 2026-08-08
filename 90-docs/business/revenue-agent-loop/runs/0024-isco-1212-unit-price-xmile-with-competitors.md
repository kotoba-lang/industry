# Run 0024 — /isco-1212/ unit price, XMILE with competitors

**Status:** completed — boundary computed from measured inputs; optimum shown to be non-identifiable
**Started / ended:** 2026-08-08
**Owner:** agent, under owner direction ("単価も xmile で、競合を含めて計算して", 2026-08-08)
**Mode:** cash-first
**Prior run:** 0023 (cancellation/refund decided; unit prices deliberately left open)

## Hypothesis

Run 0023 left the two unit prices undecided because the numbers configured in Stripe
cannot be read from this session. A **different** question is answerable: what
should the unit price be, given real competitor prices and real marginal cost?
If the workspace's existing XMILE machinery is pointed at it, a defensible number
should come out.

## Result

**Half of it came out. The other half provably cannot, and that is the finding.**

### Computable from measured inputs alone

The substitution boundary — the unit price at which a customer's itonami bill
equals the same customer's competitor bill — needs no demand curve, no Bass
coefficients, no elasticity:

```
p_proposal ≤ (¥800 − p_storage × 0.05) / r      r = proposals per employee per month
```

| r | boundary ¥/proposal | multiple of marginal cost |
|---|---|---|
| 0.5 | ¥1,570 | 1,047x |
| 1 | ¥785 | 523x |
| 2 | ¥393 | 262x |
| 5 | ¥157 | 105x |
| 10 | ¥79 | 52x |

**Cost is nowhere near binding** — the boundary sits 52–1,047× above the ¥1.5
marginal cost of serving one proposal. What constrains the price is substitution,
not cost, so a cost-plus-margin approach would land two orders of magnitude low.

**The whole 20× spread comes from one unmeasured number: r.**

### Not computable

Two demand curves, both unmeasured, fitted to the same measured inputs:

| r | unbounded power law | saturating Hill | disagreement |
|---|---|---|---|
| 0.5 | ¥5 | ¥640 | **128x** |
| 2 | ¥5 | ¥640 | 128x |
| 10 | ¥5 | ¥160 | 32x |

Both pin to the edges of the sweep — **there is no interior optimum**. Choosing the
curve is choosing the answer, and nothing measured justifies either curve. A demand
curve needs at least one conversion; cloud-itonami's measured conversion is 0/5.

## Competitors surveyed (2026-08-08, all fetched, none recalled)

| vendor | published price | tier |
|---|---|---|
| jinjer 人事労務管理 | **¥800/user/月**, 12-month minimum; ¥500 feature-restricted | published |
| kaonavi | **none** — routes to an estimate form | opaque |
| HRBrain | **none** — "月額料金制" only | opaque |
| SmartHR | 3 plans, all 要見積もり; aggregator figure unresolvable | ambiguous |

**Two of four publish no price at all.** Same sales-gated opacity this ledger
already recorded for K-12 and higher-ed. The baseline used is the highest
*published* real price (jinjer ¥800) — the opaque two were **not** assumed to be
higher, since assuming that would manufacture headroom.

Recorded as ledger seq 152–157.

## Two modelling errors, both hit and both recorded

1. **The first model diverged.** `market_size = 3000 × value_ratio^1.5` had no
   ceiling, so cheaper always meant a bigger market: 758,946 tenants and ¥33.4bn
   cumulative contribution, with the cheapest price always "optimal". Fixed with a
   real TAM ceiling (3,364,891 Japanese SMEs) and a saturating Hill response.
2. **The fix pinned to the opposite edge.** With saturation the market barely
   shrinks, so the *highest* price became optimal — the mirror image, not a
   correction. The Hill coefficients could have been tuned until a nice interior
   optimum appeared; that would be fitting the model to a desired answer, so it was
   not done. Both curves were kept and made to disagree in the output instead.

**A single model always returns an answer. Returning one is not evidence it is right.**

## What this run did not do

- Did not read the unit prices currently configured in Stripe. Still not readable
  from this session; ADR-2608080300 §5 stands unchanged.
- Did not measure r. `agentRuns7d 306 / 5 tenants` is measured, but agent runs are
  not the same object as HR-advisor proposals and tenant headcount is unknown, so
  it does not convert.
- No customer contacted, no checkout run, JPY 0 spent.

## Next

**Measure r.** It is the only variable moving the boundary, and it does **not**
require a paying customer — the five free tenants already generate proposals. Two
things must be added to the metrics surface: (a) count HR-advisor proposals
separately from agent runs, (b) capture tenant headcount. Until then the price can
be bounded but not chosen.
