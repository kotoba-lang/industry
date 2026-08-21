# Run 0001 — club-shinshi first external payment

**Status:** stopped at hard-gate preflight
**Started:** 2026-07-24
**Ended:** 2026-07-24
**Owner:** agent loop
**Mode:** cash-first

## Hypothesis

If the already-live $0.50 x402 companion/premium purchase is placed directly
after a real companion activation, at least one nonowner visitor will initiate
payment within 14 days. Existing traffic makes this faster to falsify than a
new acquisition campaign.

This is not a hypothesis that traffic alone proves demand. Current GMV is zero.

## Before

| Funnel stage | Count/value | Evidence |
|---|---:|---|
| Visitors | 1,145 | `90-docs/business/canvas-ledger.edn`, 2026-07-23 snapshot |
| Companion activations | unknown | instrumentation gap |
| Checkout/wallet initiated | unknown | instrumentation gap |
| Verified external payments | 0 | creator GMV snapshot |
| Revenue / gross profit | ¥0 | creator GMV snapshot |
| Repeat/retained | 0 | no first payment |

## Score before

| Axis | 0–5 | Reason |
|---|---:|---|
| P(payment ≤30d) | 3 | existing traffic and low price, but zero prior conversions |
| Speed to evidence | 5 | live product and payment resource |
| Existing external demand | 4 | traffic exists; intent quality is not fully known |
| Payment readiness | 4 | x402 resource is live; end-user friction remains unmeasured |
| 12m expected gross profit | 1 | one purchase is very small and repeat rate is unknown |
| Repeat likelihood | 3 | companion messages can repeat, but no observed cohort |
| Gross margin | 4 | digital delivery; generation cost still needs measurement |
| Learning value | 5 | directly resolves whether visitors will pay |
| Spillover | 4 | x402 funnel learning applies to nexus and other consumer products |
| Evidence confidence | 3 | visitor and GMV facts exist; middle funnel is missing |

**Total:** 66/100

## Hard gates

- [ ] Reconfirm adult/age/content/region gates on the exact paid path.
- [ ] Reconfirm payment is verified before premium fulfillment.
- [ ] Reconfirm the purchased entitlement is actually delivered.
- [ ] Reconfirm product-specific operator/refund disclosure on the paid path.
- [ ] Reconfirm no secret or treasury key is exposed.

**Gate:** yellow pending exact paid-path preflight
**Scope restriction:** no paid traffic acquisition and no real payment solicitation
until all five checks are green. Read-only funnel instrumentation and preflight
may proceed.

## One selected action

Measure the current companion activation → paid-offer view → x402 initiation →
verified settlement funnel, then place the existing $0.50 offer at the nearest
honest post-activation point if the hard-gate preflight passes.

Do not add a new subscription system, new character feature, or advertising in
this run.

## Success / continue / stop

- Success: at least one verified, nonowner payment with successful entitlement
  fulfillment and recorded marginal cost.
- Continue: at least three payment initiations but zero settlement; use one
  follow-up run to isolate payment friction.
- Stop/change: at least 100 genuine companion activations and zero payment
  initiation, or the 14-day/20-hour timebox expires without a new external signal.
- Timebox: 14 days or 20 person-hours, whichever comes first.

## Result

Read-only preflight found a non-compensable product-specific legal and safety
gate before any payment solicitation or deployment:

- `orgs/jk-luxury/club-shinshi/legal/terms.md` says the current age gate is
  self-attestation only and requires specialist jurisdictional review.
- The same terms say creator billing is measurement-only and require payout,
  refund/chargeback, currency/FX, tax and withholding terms before paid launch.
- `legal/privacy.md` says no live payment backend exists and the PSP/data
  handling section must be completed.

The generic x402 resource and on-chain verification code do not satisfy those
product-specific conditions. No payment was solicited, no production change was
made, and no revenue is claimed.

## Score after

Payment readiness changes `4 → 1`; evidence confidence changes `3 → 4` because
the blocking evidence is explicit. Total changes `66 → 62`, but the numerical
score is secondary: the gate changes `yellow → red`.

## Decision

`stop`

Do not resume this revenue experiment until specialist adult-industry review,
age-assurance/region scope, operator/refund terms, payment data handling, and
real entitlement fulfillment are explicitly green. The portfolio loop advances
to cloud-itonami 6399/6310.
