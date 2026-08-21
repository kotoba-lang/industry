# cloud-itonami 6399/6310 — Acquisition Funnel Audit

**Date**: 2026-07-18  
**Status**: Analysis (action items pending owner decision)  
**Related**: ADR-2607161745 (Stripe Payment Link live), ADR-2607161930 (aozora.app distribution actors)

## Current Funnel State

| Stage | Metric | Value | Reading |
|---|---|---:|---|
| **Demo** | GitHub Pages views (7d) | ~50–100 | Very low visibility; primarily bot probe (/robots.txt = 99% of non-4xx traffic) |
| **Free trial** | External free tenants | 4 | Actual human usage exists; not internal dogfood |
| **Free engagement** | Agent runs (7d) | ~22k–33k | Significant internal backend activity (likely operator/admin flows, data ingestion) |
| **Free-to-paid interest** | Operator-interest issues | 0 | No recorded RFI/sales intent signals |
| **Paid conversion** | Active Stripe subscriptions | 0 | Payment Link is live (ADR-2607161745) but unused |
| **Revenue** | MRR (JPY) | ¥0 | No paid org yet (hyp/itonami-smb-pay gate = externalPaid ≥ 1 unmet) |

### Free tenant profile (known)

- **Count**: 4 self-registered owners
- **Signups via**: `/isco-1212/` free claim UI
- **Engagement**: Registry entries 4 (catalog registrations = actual data input), not just account creation
- **Verticals**: All 4 mapped to one or both of 6399 (job-search) / 6310 (talent) — no clarity on which is primary

## Traffic Quality & Acquisition Channels

### Top-of-funnel (24h snapshot from 2026-07-18)

| Path | Requests | Reading |
|---|---:|---|
| `/itonami/verticals` | 2 | Product discovery page (intentional nav) |
| `/join/browser` | 1 | Sign-up / account creation CTA |
| `/robots.txt` | 1 | Search bot probe |
| **4xx (client-error, probe)** | 485 | **99% of error volume** — non-intentional traffic (bot scanning, misconfigured crawlers) |
| **2xx/3xx (actual success)** | 4 | Only 4 successful pageviews in 24h |

### Implied acquisition channels

From maturity-facts.edn note: **"distribution 2 据置 (24h 上位 path が robots.txt 中心、有効な獲得チャネル未実証)"**

1. **Search/SEO** — Not evident; no search-engine organic traffic observed
2. **Direct/word-of-mouth** — Possible but untracked (no referrer logs)
3. **Internal promotion** — cloud-itonami cockpit link? (`/isco-1212/` is primary signup path, not externally marketed)
4. **aozora.app actors (new)** — Posted 2026-07-16 but **aozora.app is a small self-hosted PDS, not Bluesky/public internet**, so external reach is likely still ~0

### Distribution lever status

**Current**: aozora.app actors + optionally HN/X posts (drafted but not posted)  
**Blocker**: **No proven external channel yet**. The 4 free tenants who exist may have come from:
- Direct URL sharing (unknown source)
- GitHub fork discovery
- Internal / founder network (most likely, given small scale)

**Gap to fill**: actual acquisition channel verification. Need to:
1. Ask existing free tenants: "How did you find 6399/6310?"
2. Enable UTM tracking on Payment Links + GitHub Pages CTA
3. Verify if HN/X posts would reach target audience (startup founder/hiring-manager personas)

## Conversion Funnel Blocker

### Why is paid-org conversion stuck at 0?

The Stripe Payment Link is **technically live and working** (ADR-2607161745 E2E checkout tested to email input), but **no one has clicked Subscribe**. Possible reasons (in priority order):

1. **No qualified leads are reaching the checkout** — Free tenants exist but may not be in buying decision roles. 6399/6310 require operator (hiring manager, CEO, association leader) intent, not just toolkit adopter interest.

2. **Free tier satisfies the use case** — All 4 tenants may be just using free features indefinitely (registry access, demo UI). Managed hosting (¥80k/月) is positioned for "gftdcojp as operator" scenarios, not self-managed AGPL deployments.

3. **Trial-to-paid messaging is missing** — No upgrade prompt, email sequence, or pricing-page CTA exists. Users must actively navigate to Payment Link (not linked from free UI).

4. **Price point alignment unclear** — ¥80k/月 ($533/mo) is in the competitive range for job-board SaaS, but:
   - For small Japanese staffing/association orgs (likely TAM), may be high
   - For large enterprise (another segment), may be too cheap / missing value-add pricing (per-seat, usage-based)
   - No price-anchoring experiment (A/B test, free trial period, discounts)

5. **First-paid-org chicken-egg problem** — Even operator-interested free tenant is hesitant to be the "first" paying customer of an unfamiliar vendor. Stripe Payment Link alone doesn't provide sales confidence (no SLA, no support promise, no trial period).

## Vertical-Specific Signals

### 6399 (Meta Job Search)

- **Positioning**: Regional/municipal workforce programs + industry associations (construction, care, food)
- **TAM**: Hundreds of small associations & local governments in Japan; fragmented buying (multiple approval levels)
- **Likely first buyer**: HR director or ops lead of a specific association/cooperative (e.g., 全日本建設職人基本問題協議会 等)
- **Objection likely**: "We run our own IT / don't outsource hosting" (AGPL self-host is the escape hatch)

### 6310 (Talent / Kaonavi replacement)

- **Positioning**: Mid-market staffing/HR groups replacing kaonavi or internal spreadsheet
- **TAM**: Japanese staffing firms, agency groups (500–3,000 person range)
- **Likely first buyer**: Staffing agency talent manager or CIO
- **Objection likely**: "Is this as mature as kaonavi?" (maturity/support anxiety)

### 7810 (Placement Desk, near-flagship tier)

- **Positioning**: Small independent staffing/placement operators
- **TAM**: Smaller than 6310; more niche (sole-practitioner recruiters, micro-agencies)
- **Objection likely**: "I already use ATS X" (switching cost + unfamiliarity)

## Next Actions for Paid Conversion

### Phase 1: Validate & segment free tenants (no spend, 1 week)

- **Action**: Contact all 4 free-tenant owners via `/isco-1212/` email/registry
  - Q1: Operator role (hiring mgr / CEO / association leader / other)?
  - Q2: Current use case (demo / pilot / production index)?
  - Q3: If no paid subscription, why not? (price / feature gap / not relevant / prefer self-host)
  - Q4: If interested in paid, what's the blocker? (trial period / SLA / support / budget approval)
  
- **Owner**: cloud-itonami product lead (or founder for first-contact credibility)

### Phase 2: Trial-to-paid messaging (no code, 2 weeks)

- **Add to free UI** (`/isco-1212/`): 
  - Visible upgrade CTA ("Unlock managed hosting")
  - FAQ: "When should I use free vs. managed?"
  - Pricing page with Payment Link prominent
  - Email: trial-reminder 7d/14d before hypothetical 30-day limit (or explicit "90-day free trial then ¥80k/月")
  
- **Owner**: UX/product

### Phase 3: Acquisition channel A/B test (2–4 weeks)

1. **HN post** (ADR-2607161930 draft): "Show HN: Job board with a Governor preventing stale/non-compliant postings"
   - **Target metric**: referrer traffic to GitHub Pages + free signups
   - **Success gate**: ≥10 new free tenants from HN in 2 weeks

2. **LinkedIn / Japan-local social** (sales lead targeting):
   - Staffing association & HR communities (Japanese LinkedIn groups, Wantedly, recruit sites)
   - **Target**: Direct outreach to 10 micro-staffing agencies + city HR departments
   - **Success gate**: ≥1 operator-interest issue per vertical

3. **SEO content** (organic, long-tail):
   - "Job board compliance requirements by country" (SEO keyword: job recruitment regulation)
   - Links to GitHub Pages as working proof
   - **Timeline**: 4–8 weeks to index

### Phase 4: First paid customer (owner decision point)

Once Phase 1–3 have ≥1 qualified lead (operator role + articulated buying signal):

- **Owner**: direct sales (outbound email + call)
- **Offer**: ¥80k/月 or 14-day free trial of Managed tier (owner call)
- **Close**: Stripe Payment Link or manual invoice (if preferred)
- **Deployment**: Auto-enable tenant via webhook (or manual provisioning if webhook handling not ready per ADR-2607161745 note)

## Metrics to Track

Add to `metrics/cloud-itonami-6399-6310-specific.edn` (separate from cockpit-wide metrics):

| Metric | Collection method | Cadence | Success criterion |
|---|---|---|---|
| Funnel stages | Manual query of free-tenant DB | weekly | Track claim → registry entry → paid |
| Top referrer | HTTP referrer header (cf.country, cf.ua, etc.) | daily | Identify effective channels |
| Signup-to-paid latency | Database (created_at → subscription_created_at) | on-conversion | < 30 days for first org |
| Operator role distribution | Support email survey | on-signup | "CEO/founder" > "adopter" |
| Pricing sensitivity | Trial period engagement (% who use after 7d trial) | 14-day cohort | Validate ¥80k/月 anchor |

## Risk: Competitive response

Stripe Payment Link is public (no auth required to view/bookmark). If 6399/6310 gain traction, competitors (existing job-board SaaS, Shopify-based platforms, open-source Mattermost-style communities) may fork/imitate the Kaonavi-replacement or job-board-plus-governance positioning. Moat is governance automation + audit-ledger (hard to imitate), but market education is slow.

## Maturity-score implications

- **Distribution**: 2→3 only if HN/social traffic brings ≥10 free signups from external identifiable source
- **Business**: 1→2 only if first paid org is acquired and ≥1 month's subscription is active (revenue > ¥0)

Current state (捏造ゼロ原則): Both stay at current levels until verified.
