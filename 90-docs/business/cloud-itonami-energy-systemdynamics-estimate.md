# cloud-itonami — world electricity coverage: a system dynamics estimate

**Date**: 2026-07-18
**Status**: Illustrative estimate — NOT a business commitment, NOT measured
cloud-itonami telemetry (none exists; see ADR-2607181800 §Context).
**Scope**: ISIC 3510 (T&D grid operations) / 3511 (SMR generation
operations) / 3512 (community renewables operations) only — 3520 (gas)
and 3530 (steam) are out of scope (different unit basis, would break the
TWh-electricity comparison below).
**Model / results**: `cloud-itonami-energy-systemdynamics-model.cljs`
(nbb, deterministic, re-run with `nbb 90-docs/business/cloud-itonami-energy-systemdynamics-model.cljs`)
→ `cloud-itonami-energy-systemdynamics-results.edn` (full time series).
**Dashboard**: interactive version of this same model, published as a
Claude artifact (see chat).
**Related**: ADR-2607181800.

---

## Question being estimated

"cloud-itonami の今の予測生産で全世界のどれぐらいのエネルギー需要を賄えるか" —
cloud-itonami has no real generation assets and no measured production
data (confirmed by a prior repo-wide search — see chat history). This
document instead builds a **system dynamics estimate**: if cloud-itonami's
existing energy-vertical actors (3510/3511/3512) grow along a plausible,
constrained adoption curve, what share of world electricity demand would
they plausibly govern, and by when? All cloud-itonami-specific numbers
below (TAM share, adoption speed, fee rates, onboarding-capacity growth)
are **stated assumptions**, not measurements — they are chosen to be
order-of-magnitude defensible, not fitted to any real data (none exists).
The world-demand baseline is real, cited public data.

## 1. World electricity demand baseline (real, cited)

- 2024 global electricity demand crossed **30,000 TWh** for the first
  time, +4.0% y/y — a sharp acceleration from the 2010–2023 historical
  average of 2.6%/yr (Ember, *Global Electricity Review 2025*).
- IEA *Electricity 2025* forecasts **+3,500 TWh over 2025–2027** (≈3.8%
  CAGR), driven disproportionately by data-center/AI load: global
  data-center electricity demand is set to **double to ~945 TWh by
  2030** (≈15%/yr, with AI-optimized servers growing ~30%/yr) — IEA
  *Energy and AI* (2025).
- World total *primary* energy consumption (all forms — electricity,
  transport fuel, heat, industrial feedstock) was **592 EJ in 2024**
  (Energy Institute, *Statistical Review of World Energy 2025*) ≈
  164,400 TWh-equivalent. Electricity is therefore roughly **18%** of
  total world energy demand — used below to convert "% of world
  electricity demand" into "% of world energy demand" (all forms).

**Model assumption**: the near-term elevated growth rate (3.8%/yr,
AI/data-center driven) reverts toward the long-run historical rate
(2.6%/yr) with an 8-year time constant — a standard SD "rate reversion"
formulation, not a cited forecast beyond ~2027.

Simulated world electricity demand:

| Year | World demand (TWh/yr) |
|---|---:|
| 2026 (start) | 32,279 |
| 2031 | 37,973 |
| 2036 | 44,199 |
| 2041 | 50,821 |
| 2046 | 57,704 |
| 2051 | 66,026 |

## 2. cloud-itonami segment model (assumptions, not measured)

Three electricity-relevant ISIC verticals, each modeled as an
independent **Bass diffusion** process (innovation coefficient `p` +
imitation coefficient `q`) against its own addressable-market slice of
world demand:

| Segment | TAM (% of world demand) | p | q | churn/yr | fee ($/TWh-yr managed) |
|---|---:|---:|---:|---:|---:|
| 3510 T&D grid ops | 15% | 0.010 | 0.25 | 4% | $50,000 |
| 3511 SMR generation ops | 3% | 0.005 | 0.20 | 2% | $150,000 |
| 3512 Community renewables ops | 10% | 0.030 | 0.40 | 5% | $30,000 |

Sanity check on fees: $50,000/TWh-yr = $0.00005/kWh — a thin governance
SaaS fee layered on top of the underlying electricity price (~5–15
¢/kWh), not a generation-cost assumption. Order-of-magnitude plausible
for a compliance/audit-layer subscription, not verified against any
real pricing.

TAM shares (15% / 3% / 10%) reflect a judgment call: large
vertically-integrated utilities are unlikely to adopt a third-party
AI-governed ops layer soon; municipal utilities, cooperatives, merchant
IPPs, and new-build SMR fleets are the realistic near/mid-term buyer.
These are the single most debatable inputs in the model — see the
dashboard for sensitivity.

## 3. The system dynamics structure — why "optimal" needs more than a
   spreadsheet CAGR

A naive extrapolation (fixed % growth rate) hides the actual constraint
that matters: cloud-itonami's own **onboarding throughput** — the rate
at which new customers can be sold, verified, and brought under
CertGovernor's human-approval-in-the-loop audit pipeline — is itself a
stock that has to be built up over time, not an infinite tap. This is
the classic SD "Limits to Growth" / "growth and underinvestment"
archetype: if desired bookings (from Bass diffusion) exceed onboarding
capacity, you either (a) hard-cap bookings at capacity and grow capacity
steadily, or (b) let bookings overshoot and pay for it in churn from
under-governed, under-supported customers.

Three policies were simulated over 2026–2051 (25 years), starting from
an onboarding capacity of 5 TWh/yr in 2026:

| Policy | Onboarding-capacity growth | Bookings rule |
|---|---:|---|
| **Aggressive** | 35%/yr | Uncapped — books full Bass-desired flow every year regardless of capacity |
| **Capacity-matched (recommended)** | 35%/yr | Hard-capped at current onboarding capacity |
| **Conservative** | 15%/yr | Hard-capped at current onboarding capacity |

### Result

| Policy | Coverage of world electricity demand, 2051 | Cumulative revenue 2026–2051 |
|---|---:|---:|
| Aggressive | 18.13% | $5.03B |
| **Capacity-matched** | **14.02%** | **$2.50B** |
| Conservative | 1.45% | $0.28B |

**The Aggressive policy's headline numbers are not operationally
credible and are shown only as a cautionary counter-example, not a real
option.** Uncapped Bass diffusion books ~150 TWh/yr of new managed
throughput in year one alone — more electricity than most G20 countries
consume annually — from an actor with zero prior customers and a
CertGovernor pipeline sized for 5 TWh/yr. No sales, legal, or
human-approval process scales that fast; the model's only penalty for
this (an elevated churn multiplier) understates the real-world
consequence, which is closer to outright failure to deliver, regulatory
rejection, or reputational collapse. Its higher modeled revenue is an
artifact of ignoring the capacity constraint, not a finding.

**The real, decision-relevant comparison is Capacity-matched vs.
Conservative — both respect the capacity ceiling, differing only in how
fast that ceiling is grown (35%/yr vs. 15%/yr).** Capacity-matched wins
by **~9x cumulative revenue** for the same discipline (never overshoot),
because the compounding of a faster-growing ceiling dominates over 25
years. **The optimal lever is investment in onboarding/governance
throughput, not sales aggressiveness against demand.**

## 4. Headline answer — recommended (capacity-matched) policy

| Year | Managed throughput (TWh/yr) | % of world **electricity** demand | % of world **all-energy** demand (×18%) |
|---|---:|---:|---:|
| 2026 | 5 | 0.015% | 0.003% |
| 2031 | 67 | 0.18% | 0.032% |
| 2036 | 335 | 0.76% | 0.14% |
| 2041 | 1,531 | 3.02% | 0.54% |
| 2046 | 5,135 | 8.87% | 1.60% |
| 2051 | 9,256 | 14.02% | 2.52% |

**Answer**: under the most defensible ("optimal" = realistic capacity
growth, revenue-maximizing among achievable policies) system dynamics
scenario, cloud-itonami's energy-vertical actors would cover roughly
**0.015% of world electricity demand today (2026)**, crossing **1% of
world electricity demand around 2037**, and reaching **~14% of world
electricity demand (~2.5% of all world energy demand)** by 2051 — a
quarter-century out. It does not reach anywhere close to "全世界の
エネルギー需要を賄う" (covering all world energy demand) within this
horizon under realistic constraints; doing so would require either a
far larger addressable-market assumption or abandoning the capacity
discipline that this model's own comparison shows destroys long-run
value.

## 5. Known limitations (read before citing this number anywhere)

- TAM shares, Bass p/q, churn, and fee rates are **judgment-call
  assumptions**, not fitted or sourced data — no real cloud-itonami
  customer/revenue data exists to calibrate against. Treat all coverage
  percentages as order-of-magnitude, not forecasts.
- World-demand growth-rate reversion (8-year time constant) is a
  modeling simplification, not itself a cited IEA/Ember projection
  beyond ~2027.
- 3520 (gas) and 3530 (steam) verticals are excluded — including them
  would require a non-electricity unit basis and a different world
  baseline; this document does not attempt that conversion.
- The model does not account for competitor entrants, price
  erosion, macro shocks (recession, resource constraints), or physical
  buildout limits on new SMR/renewables capacity itself (only adoption
  of cloud-itonami's governance layer on top of assumed-available
  capacity is modeled).
- Re-run `nbb 90-docs/business/cloud-itonami-energy-systemdynamics-model.cljs`
  to reproduce every number in this document exactly; do not hand-edit
  the tables above without re-running the script.
