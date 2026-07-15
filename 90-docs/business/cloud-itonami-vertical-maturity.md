# cloud-itonami — vertical maturity (design vs implementation)

**As-of**: 2026-07-15  
**Product**: `cloud-itonami`  
**正本 (structured)**: `cloud-itonami-vertical-maturity.edn`  
**Portfolio scores**: `maturity-facts.edn` + generated `maturity-scores.md` (ADR-2607021700)  
**Flagship sprint**: ADR-2607122300  

This is the *within-product* breakdown. Portfolio BMC/YC answers “is the
business mature?”; this doc answers “where inside the fleet is that maturity?”

## Rubric (0–5)

| Axis | Meaning |
|---|---|
| **Design** | Domain scope, Governor discipline, op contract, ADR/blueprint clarity |
| **Impl-core** | `operation` / governor|policy / `store` / `phase` / `sim` / `facts` present and tested |
| **Impl-product** | Live demo, operator-quickstart, CI regen, pricing docs |
| **Business** | External tenant, billing, hyp validation |

## Fleet snapshot (mechanical)

Local ISIC checkouts under `orgs/cloud-itonami/cloud-itonami-isic-*` (**n=165**):

| module-score (0–6) | count | reading |
|---|---:|---|
| 6 | **127** | full actor modules |
| 5 | 3 | near-full |
| 0 | **35** | thin / empty src |

| product-score (0–5) | count | reading |
|---|---:|---|
| 5 | **2** | 6399, 6310 only |
| 4 | **1** | 7810 |
| 3 | 16 | mostly mfg demos / sample HTML |
| 1–2 | **146** | actor without storefront |

`kotoba-lang/industry` registry (updated **2026-07-14T19:00:00Z**):

| maturity | count |
|---|---:|
| `:implemented` | **286** |
| `:blueprint` | 1 |
| `:spec` | **345** |

## Cohort table

| Vertical / layer | Design | Impl-core | Impl-product | Business | One-liner |
|---|---:|---:|---:|---:|---|
| **6399 Meta job-search** | 5 | 5 | 5 | 1 | Indeed replacement; 6 jurisdictions; live demo; no paid tenant |
| **6310 Talent** | 5 | 5 | 5 | 1 | kaonavi replacement; assignment op; live demo; no paid tenant |
| **7810 Employment/placement** | 4 | 4 | 4 | 1 | Near-flagship (demo + quickstart) |
| **Insurance / finance** | 4 | 4 | 1 | 0 | Full modules; almost no storefront |
| **Real estate** | 4 | 4 | 1 | 0 | Same pattern |
| **Health / care / hospital** | 5 | 4 | 1 | 0 | Strong clinical scope exclusion; thin product face |
| **Education** | 4 | 4 | 1 | 0 | Full modules; no product face |
| **Mfg / auto** | 4 | 4 | 3 | 0 | Higher demo-HTML rate; no operator funnel |
| **Infra / utility** | 4 | 3 | 1 | 0 | Some thinner checkouts |
| **Software/IT 5820/620x** | 4 | — | — | 0 | Registry implemented; **local checkout missing** in this snapshot |
| **ISO3166 countries** | 4 | 4 | 1 | 0 | Country coordinators; no GTM surface |
| **ISO3166 JPN agencies** | 4 | 0 | 1 | 0 | blueprint + docs; essentially no src |
| **ISCO occupations** | 3 | 1 | 0 | 0 | Thin tests / partial modules |
| **Catalog / org surface** | 4 | 4 | 4 | 1 | github.io from registry SSoT |
| **itonami.cloud cockpit** | 4 | 3 | 4 | 1 | productSurface 7/7; free-tenant selfReg=1; paid blocked on Stripe secrets |

## Reading

```
Design        ████████████████████  high across the factory
Impl-core     ████████████████░░░░  ~77% of local ISIC full modules
Impl-product  ██████░░░░░░░░░░░░░░  cockpit + flagships; thin elsewhere
Business      ██░░░░░░░░░░░░░░░░░░  free-tenant ok; paid hyp untested
```

**Design maturity and implementation maturity are not uncorrelated —
implementation has split into core vs product.** Core was mass-produced;
product was invested in flagships + the itonami.cloud cockpit (now
productSurface 7/7); business has not cleared a paid gate on any vertical.

## Priorities (from this table)

1. **Owner STRIPE_*** secrets → live `/isco-1212` checkout (only remaining
   productSurface→business gate; see `/docs/stripe-billing-setup.md`).
2. Prefer **product → business** on 6399 / 6310 (+7810) over new verticals.
3. Keep the portfolio wedge narrative on the flagship pair (`:wedge 3` in
   maturity-facts; not “all industries” as the sales wedge).
4. Treat ISO3166 agencies and ISCO as **design assets**; defer runtime spend.
5. Portfolio `validation` / `revenue` stay 0 until
   `:hyp/itonami-smb-pay` (10 external paid orgs).

## Related

- Portfolio rescore note: `maturity-facts.edn` `:cloud-itonami` (2026-07-15)
- Metrics: `metrics/cloud-itonami.edn` (cockpit traffic)
- Flagship depth: ADR-2607122300
- Pattern saturation (earlier): ADR-2607011200
