# cloud-itonami-isic-5820 — CRM Go-to-Market Strategy

**Date**: 2026-07-18  
**Owner**: Jun Kawasaki (directive 2026-07-17: prioritize Salesforce-replace)  
**Status**: Strategic direction (owner decision required to execute)  
**Related**: ADR-2607172600 (pricing-intel + Stripe Payment Link live), ADR-2607172500 (kotoba-lang/crm registration)

---

## I. Competitive Position

### Market landscape (pricing-intel 20260717-01)

| Vendor | Per-seat (typical SMB) | Moat | Weakness vs 5820 |
|---|---|---|---|
| **Salesforce** | $25–550/user/mo (tiered) | Brand, feature depth | Complexity, configuration tax, expensive |
| **HubSpot** | $7/mo (annual, Starter) – $150+/mo (Enterprise) | Ease of setup, bundled marketing | Still relies on workflow rules (not structural governance) |
| **Pipedrive** | $14–79/user/mo | Visual pipeline, affordability | No audit ledger, minimal entitlement control |
| **Zoho CRM** | $14–52/user/mo | Cheap, vertical-specific variants | No governance, high configuration density |
| **Microsoft Dynamics 365 Sales** | $65–150/user/mo | Microsoft ecosystem lock-in | Expensive, complex licensing |
| **cloud-itonami-5820** | **¥80k/月 flat (unlimited seats)** | **Structural governance, audit-ledger, ASC 606 compliance** | Early product, unknown vendor, self-hosted risk |

### 5820's unique value (governance-first)

**Positioning**: The only CRM that makes discount-authority, subscription-entitlement, and revenue-recognition **structurally unbypassable** — not a configurable workflow, but a sealed Governor.

**Technical moat**:
- RevOps-LLM (AI-assisted revenue operations) sealed behind SubscriptionGovernor (no human override)
- Append-only audit ledger (ASC 606/IFRS 15 compliance proof)
- 3-tier discount-authority (CFO/VP Sales/Sales Rep, hard limits, not advisory)
- Stage-sequence guarantee (deal cannot skip approval stages)

**Market segment**: CFOs & RevOps leaders at SaaS/subscription vendors (not general sales teams)

---

## II. Target Persona & TAM

### Primary persona: VP Revenue / Chief Revenue Officer

| Trait | Implication |
|---|---|
| **Pain**: Reps apply unauthorized discounts; deals close over-provisioning entitlements; revenue recognition is manual/error-prone | 5820 directly solves audit/compliance risk |
| **Budget holder**: Revenue operations, CFO office (not just sales) | Pricing at ¥80k/月 aligns with RevOps tool spend (Gong, Chorus, etc.) |
| **Technical requirement**: Self-hosted + API-first (integrates with their own billing engine) | Match 5820's scope (no managed-only offering until Phase 2) |
| **Buying signal**: Audited by Big 4, SOC 2 in-flight, or recent revenue-recognition restatement | Concrete accountability driver |
| **Objection**: "Salesforce + configured workflow rules is good enough" | Needs to be reframed as inadequate |

### Secondary: SaaS CFO / Finance Operations

- Needs ASC 606/IFRS 15 evidence for auditors
- Wants to enforce that billing reality = pipeline promise
- May require a staged trial (pilot with 1–2 sales reps before full rollout)

### TAM estimate

**Serviceable Obtainable Market (SOM)** at ¥80k/月:
- Japanese SaaS vendors, VC-backed (need audit trail for fundraising) + recurring-revenue companies: ~50–200 orgs
- Global English-language SaaS, if 5820 supports EN: +500–1000 orgs
- Current: 0 paid orgs (all estimation)

---

## III. First-Customer Acquisition: Two-lever strategy

### Lever 1: Dogfood the platform inside cloud-itonami's own sales ops

**Directive from ADR-2607172600**: "operate cloud-itonami's own managed-tier sales motion (7810/6310/6399 leads) inside the 5820 actor itself; 7310 (advertising) / 7320 (market research) actor cores as the growth engine feeding that pipeline."

**Execution**:
1. Map cloud-itonami's current sales pipeline (6399/6310/7810 free-to-paid conversion) into the 5820 actor's data model
   - Deals: `[Free → Trial → Managed Starter → Managed Pro]`
   - Stages: `[Lead → Qualified → Proposed → Agreed → Activated → ✓ Closed Won / ✗ Closed Lost]`
   - Discount tiers: `[No discount / Associate discount (10%) / Executive discount (15%)]`
   - Subscription tiers: `[Free / Managed Starter ¥80k / Managed Pro ¥250k (future)]`
2. Operators (itonami.cloud admins) use 5820 UI to track these 4 flagship verticals' actual pipeline
3. Result: "The pipeline you are looking at is our real pipeline" — live proof of concept
4. Marketing collateral: "Salesforce-replace for SaaS: how we use our own CRM to sell managed hosting"

**Timeline**: 2–3 weeks of actor-data modeling + 2 weeks of operator feedback loop

**Success gate**: 10+ deals tracked in 5820 pipeline (could be all free-tier, but the pipeline discipline itself is real)

### Lever 2: Direct outreach to audited SaaS founders / RevOps leaders

**Targeting**: Japanese SaaS companies (known names) who have:
- Recent Series B–C funding (need audit-trail credibility)
- Recurring revenue but sales-force > 3 reps (discount-authority risk is material)
- Non-Salesforce current platform (GAS spreadsheet, Hubspot without governance, or Zoho)

**Outreach narrative**:
> "Every SaaS has a revenue-recognition nightmare waiting: a rep applies an unauthorized discount, a subscription entitlement doesn't match the deal, an auditor asks for ledger proof and finds email threads instead. We built a CRM that makes this impossible — the discount authority, subscription terms, and revenue recognition are locked behind a Governor, not configurable workflows.  
> 
> For ¥80k/月, you get unlimited seats + a complete audit trail for ASC 606. Would you be open to a 30-minute chat about your current RevOps pain?"

**Channels**:
1. LinkedIn: VP Revenue / CFO / RevOps director at known Japanese SaaS (Zaim, Yappli, Sansan, Wantedly, etc.)
2. Twitter/X: #RevOps #SaaS Japan community (retweet + direct message)
3. Warm intro from founder network (if available)

**First-touch goal**: 5 discovery calls in 30 days  
**Close goal**: 1 trial customer (14-day free Managed CRM Starter) by week 8

---

## IV. Pricing strategy

### Why ¥80k/月 flat (not per-seat)?

**Rationale** (from ADR-2607172600 + portfolio consistency):
1. **Competitive intelligence**: 5-rep SMB team at Salesforce/HubSpot costs ~¥17k–131k/月; 5820 at ¥80k/月 is price-competitive
2. **Portfolio consistency**: Same flat rate as 6399/6310/7810 (job board, talent board, placement desk) — one managed-tier price point across all flagships
3. **Governance premium**: Unlimited seats + audit ledger (not available from Salesforce/HubSpot at similar price) justifies the band
4. **Simplicity**: No per-seat calculation disputes; no "how many reps do you have" licensing friction

### Trial pricing

**Recommendation** (owner decision):
- **Option A** (aggressive): 30-day free trial of Managed CRM Starter, then ¥80k/月
  - Risk: high trial abandonment (no commitment signal)
  - Upside: removes friction for early adopters
- **Option B** (staged): ¥20k/月 trial for 30 days, then ¥80k/月 full; credit ¥20k toward first month if convert
  - Better signal (customer has committed budget)
  - Easier upsell narrative ("trial was a success, now you scale")
- **Option C** (freemium path): Free tier (1–3 reps, limited stages) → ¥80k/月 Managed Starter (unlimited)
  - Long conversion tail (many free users, few converters)
  - Aligns with cloud-itonami's current free-to-paid funnel

**Recommendation**: Start with **Option B** (¥20k trial) for disciplined signal, then A/B test after 3 first customers.

---

## V. Differentiation vs incumbent

### Salesforce/HubSpot attack

**Their pitch**: "Universal, feature-rich CRM that scales from SMB to enterprise."

**5820's counter-pitch**: "Not a universal platform — a specialized governance layer for founders who've been burned by unauthorized discounts or revenue-recognition surprises."

| Dimension | Salesforce/HubSpot | 5820 |
|---|---|---|
| **Setup time** | 4–8 weeks (configuration, admin training) | 1 week (predefined stages, tiers, audit schema) |
| **Audit trail** | Requires external logging + compliance add-ons | Built-in (every discount, stage, entitlement is immutable) |
| **Discount authority** | Configurable workflow rules (can be bypassed if admin careless) | Structural Governor (impossible to override) |
| **Revenue recognition** | Manual, or via 3rd-party integration (Zuora, etc.) | Automatic ASC 606 straight-line proof |
| **Learning curve** | High (1,000+ configuration options) | Low (sales reps: 2 hours; CFO: 1 hour) |
| **Price** | $25–550/seat/mo (scales with headcount) | ¥80k/月 flat (scales with ambition, not headcount) |
| **Target buyer** | VP Sales + CIO | VP Revenue + CFO + VP Sales (joint) |

**"Salesforce is for sales teams that trust each other. 5820 is for founders who learned the hard way."**

---

## VI. Execution roadmap

### Month 1 (July 2026): Validate dogfood + target list

- [ ] **Week 1–2**: Model cloud-itonami's 4-flagship pipeline in 5820 actor; import first 10 deals
  - Owner: product lead
  - Success: 5820 UI shows live cloud-itonami sales motion
- [ ] **Week 3**: Outreach list (50 names: VP Revenue / CFO at Japanese SaaS + English-language founders via warm intro)
  - Owner: founder (credibility)
- [ ] **Week 4**: 5 discovery calls scheduled
  - Owner: founder or sales leader
  - Metric: 5 calls completed, ≥2 "next step" (trial/demo)

### Month 2 (August 2026): First trial + Salesforce win narrative

- [ ] **Week 1–2**: Close first trial customer (14-day or ¥20k/月 trial, whichever agreed)
  - Owner: founder/sales lead
  - Deployment: Managed CRM Starter via Stripe Payment Link (webhook auto-provisioning, or manual if webhook handler not ready per ADR-2607161745)
- [ ] **Week 3**: Co-author case study (customer + cloud-itonami)
  - "How [Customer] Replaced Salesforce with a ¥80k/月 Governed CRM"
  - Emphasize: discount-authority enforcement, audit-ledger, 8-week setup → 1-week setup
- [ ] **Week 4**: Post to HN / relevant SaaS communities
  - Same distribution strategy as ADR-2607161930 (6399/6310 posts)
  - Goal: +10 trial signups

### Month 3+ (September 2026): Funnel optimization

- [ ] **Metric tracking**: ARR, trial-to-paid rate, sales cycle length
- [ ] **Bottleneck analysis**: Where do trials convert (no → yes) or churn (yes → no)?
  - Feature gap? (unlikely; scope is narrow)
  - Governance mismatch? (customer wants more flexibility than 5820 allows)
  - Price? (¥80k/月 too high despite value)
  - Perceived risk? (unknown vendor vs Salesforce enterprise SLA)
- [ ] **Iteration**:
  - If price is blocker: introduce ¥50k/月 Starter tier (limited discount tiers)
  - If feature gap: validate against roadmap (marketing-automation, customer-service sibling actors)
  - If risk: add SLA doc, publish audit checklist, co-market with existing customers

---

## VII. Revenue assumptions (捏造ゼロ — no fabrication)

**Current state**:
- Paid orgs: 0
- Trial orgs: 0 (hypothetical: ¥20k/月)
- MRR: ¥0

**Optimistic forecast (end of August 2026)**:
- Paid orgs: 1–2
- MRR: ¥80k–160k (¥80k/月 × 1–2 orgs)
- CAC (Customer Acquisition Cost): ~¥150k–200k (founder time + dogfood modeling) — paid back by month 2–3 of customer lifetime

**Conservative forecast (end of September 2026)**:
- Paid orgs: 0 (trial-to-paid conversion is hard; takes 3–6 months typical SaaS)
- MRR: ¥0 (but 2–5 trials in-flight)
- Next gate: "Are trials converting at all?" If not, pivot messaging or price

---

## VIII. Differentiation vs cloud-itonami's other flagships

### How 5820 (CRM) complements 6399/6310/7810

| Vertical | Operator | Customer segment | 5820 connection |
|---|---|---|---|
| **6399 (Job Board)** | Association/municipality | Job seekers + employers | If association runs sales ops, 5820 tracks recruiting-lead pipeline |
| **6310 (Talent Board)** | Staffing agency | Candidates + placement buyers | Staffing agency uses 5820 to manage sales-to-placement funnel |
| **7810 (Placement Desk)** | Recruitment consultant | Placements | Same: 5820 tracks placement sales ops |
| **5820 (CRM)** | SaaS founder / RevOps | Sales team + subscribers | RevOps discipline for SaaS (not recruitment) |

**Strategic implication**: 5820 is NOT adjacent to 6399/6310/7810; it's a **different TAM** (SaaS founders, not job-board/staffing operators). Distribution channels don't overlap. No cannibalization risk.

**But dogfood opportunity is powerful**: If cloud-itonami uses 5820 to run its own 6399/6310/7810 sales ops, it proves the model live and creates a demo case study ("How we use our own CRM to run our own SaaS fleet").

---

## IX. Next decision gates

1. **Dogfood go/no-go** (owner decision, ~1 week):
   - Should cloud-itonami invest 2–3 weeks in modeling its pipeline inside 5820 for demo value?
   - Alternative: skip dogfood, direct-sell to external RevOps leaders (faster to first customer if signal is strong)

2. **Trial pricing** (owner decision, after first 3 discovery calls):
   - Free trial (30d) vs paid trial (¥20k/月 for 30d)?

3. **E2E checkout verification** (owner decision, before ANY lead sees Payment Link):
   - ADR-2607161745 notes E2E checkout tested to email input, but final Subscribe click skipped
   - MUST verify before sending trial-ready lead to https://buy.stripe.com/4gM28q88r4pyaGIdIzbMQ0e
   - Otherwise: first customer hits checkout bug, deal dies, credibility wrecked

---

## X. Maturity score update (捏造ゼロ)

**Current** (accepted 2026-07-17): Design 5 / Impl-core 4 / Impl-product 4 / Business 2

**Will remain unchanged until**:
- **First paid customer is active for ≥1 month** → Business 2→3 (external paid org acquired)
- **Distribution channel proven (e.g., 10+ trial signups from HN post)** → Distribution 2→3
- **Product completeness** (governance limits hit; needs marketing/service sibling) → Impl-product 4→5 (when siblings land)

**Honest checkpoint**: All three flagships (6399/6310/5820) are currently 0→1 in the paid funnel. The challenge is **not feature depth or positioning; it's closing the first customer**. Whoever runs the sales motion (founder outreach) will determine the next score update.
