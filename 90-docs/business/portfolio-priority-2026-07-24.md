# Portfolio priority — cash-first / profit-first recalculation

**As of:** 2026-07-24
**Status:** Decision-support baseline; estimates are priors, not observed revenue
**Rule:** `.cursor/rules/always/0.mdc` (`ポートフォリオ優先順位`)

## Why the previous ordering changes

The previous ordering over-weighted 12-month contract value and under-weighted
time-to-first-payment. That favors cloud-itonami B2B verticals even though all
currently report zero paid organizations. A separate cash-first objective makes
the live consumer payment surfaces visible:

- club-shinshi has existing traffic, a live $0.50 x402 resource, and zero
  observed creator GMV.
- net-babiniku has a live, on-chain-verified Base USDC tip path, but the default
  amount is 5 USDC and requires an injected wallet. Subscription and PPV remain
  unprovisioned.
- cloud-itonami has four external free tenants and live Stripe Payment Links,
  but the flagship managed tier requires a B2B buying decision at ¥80,000/month.

## Scoring assumptions

Scores are relative indices, not forecasts. They use conservative priors because
there is no observed external payment conversion yet.

| Candidate action | P(payment ≤30d) | First gross profit | Hours to evidence | Confidence | Cash index |
|---|---:|---:|---:|---:|---:|
| club-shinshi: place $0.50 paid companion/premium action directly in the active user path | 20% | ¥70 | 6h | 0.65 | 1.5 |
| net-babiniku: make the existing verified tip path discoverable and test a 1 USDC default | 12% | ¥120 | 10h | 0.60 | 0.9 |
| cloud-itonami 7810: direct offer to ten owner-operated placement agencies | 8% | ¥80,000 | 24h | 0.45 | 120.0 |
| cloud-itonami 6399: interview four free tenants and offer a paid pilot | 7% | ¥80,000 | 20h | 0.60 | 168.0 |
| cloud-itonami 6310: interview four free tenants and offer a paid pilot | 6% | ¥80,000 | 24h | 0.55 | 110.0 |
| cloud-itonami 5820: founder-led RevOps/CFO discovery and ¥20k paid trial | 5% | ¥20,000 | 30h | 0.50 | 16.7 |

The absolute indices are not comparable across currency scales without utility
normalization. For the decision:

- **Probability of any first payment:** club-shinshi ranks first.
- **Expected yen from the same limited effort:** a qualified cloud-itonami free
  tenant conversion ranks first.
- net-babiniku is second in consumer cash speed, but its wallet and 5-USDC
  friction are material.

## Cash-first ranking

1. club-shinshi — one-message or one-premium-item x402 purchase.
2. net-babiniku — one verified tip, preferably testing a lower default amount.
3. cloud-itonami 6399 — convert one of the four existing external free tenants.
4. cloud-itonami 7810 — reach owner-operated micro agencies with a paid pilot.
5. cloud-itonami 6310 — convert an existing tenant or staffing operator.
6. cloud-itonami 5820 — paid RevOps trial.
7. cloud-itonami 854 — private vocational/training operator pilot.
8. cloud-itonami 853 — higher-education advising operations.
9. cloud-itonami 851/852 — primary/secondary school operations.

## Profit-first ranking inside cloud-itonami

| Rank | Vertical | Reason |
|---:|---|---|
| 1 | 6399 Meta Job Search | Product score 5, four external free tenants overlap 6399/6310, live ¥80k Payment Link; shortest path is tenant interviews, not code |
| 2 | 7810 Placement Desk | Smaller buyer and likely founder decision-maker reduce procurement latency; near-flagship product score 4 |
| 3 | 6310 Talent | Product score 5 and live ¥80k offer, but replacement anxiety and mid-market procurement are heavier |
| 4 | 5820 CRM | Strong ¥80k governance positioning and ¥20k trial proposal, but zero qualified external lead is recorded |
| 5 | 854 Vocational/Continuing Education | ¥20k live offer; private training businesses can decide faster than schools |
| 6 | 853 Higher Education | ¥20k offer but institutional buying cycle is slower |
| 7 | 851 Primary Education | ¥25k offer; school procurement and trust requirements reduce near-term probability |
| 8 | 852 Secondary Education | Same ¥25k band and procurement friction |
| hold | Remaining verticals | No combination of storefront, price, payment, buyer, and channel sufficient for near-term selling |

## Eight-week allocation

| Workstream | Share |
|---|---:|
| club-shinshi paid-message/premium experiment and funnel instrumentation | 25% |
| cloud-itonami 6399/6310 free-tenant interviews and paid-pilot close | 25% |
| cloud-itonami 7810 owner-operated agency outbound | 15% |
| net-babiniku real-tip conversion experiment | 10% |
| cloud-itonami 5820 paid discovery calls | 10% |
| production, security, and payment reliability | 10% |
| all other product/research work | 5% |

## Gates

- club-shinshi: 14 days, at least one non-owner verified payment; otherwise
  inspect chat activation and wallet/payment friction before adding features.
- net-babiniku: 14 days, at least one non-owner verified tip or a measured wallet
  initiation funnel; otherwise do not build subscriptions.
- cloud-itonami 6399/6310: contact all four free tenants within seven days and
  obtain at least one explicit paid-pilot yes/no decision.
- cloud-itonami 7810/5820: ten qualified contacts each; continue only with at
  least two replies or one discovery call.
- A first small B2C payment wins the cash-first race, but resources only scale
  after repeat purchase or 30-day retention.
