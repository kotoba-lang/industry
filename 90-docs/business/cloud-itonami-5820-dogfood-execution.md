# cloud-itonami-isic-5820: Dogfood Execution Plan

**Date**: 2026-07-18  
**Owner directive**: ADR-2607172600 — "operate cloud-itonami's own managed-tier sales motion (7810/6310/6399 leads) inside the 5820 actor itself"  
**Status**: Ready to execute  
**Estimated effort**: 2–3 weeks setup + 2 weeks feedback loop

---

## Why dogfood matters

1. **Product validation**: 5820 CRM is tested against real deals (cloud-itonami's own free→paid funnel), not hypothetical
2. **Governance proof**: The discount-authority / entitlement / stage-sequence / audit-ledger governance is demonstrated live
3. **Case study**: "This is how we use our own CRM to manage 6399/6310/7810" becomes a tangible, auditable sales motion
4. **Distribution**: When prospects ask "does this CRM actually work?", the answer is "yes, we manage our own 4-vertical business with it"
5. **Acquisition lever**: The live pipeline becomes a demo, reducing sales friction ("Why should I trust you?" → "See our own pipeline")

---

## Current 5820 Status

| Component | Status |
|---|---|
| **Code** | ✅ Fully implemented (`langgraph` StateGraph + `kotoba-lang/crm` commons) |
| **HTTP API** | ✅ Live (`POST /propose`, `GET /dashboard`, auth token required) |
| **Persistence** | ✅ File-based store (`ISIC5820_STORE_FILE`) |
| **Dashboard** | ✅ Pipeline funnel + conversion rates + revenue rollup |
| **Deployed** | ✅ Live at itonami.cloud/isic-5820/ (UI) |
| **Governor** | ✅ SubscriptionGovernor enforces discount-authority, entitlement, stage-sequence, ASC 606 |
| **Real model LLM** | ⚠ Optional; default is sealed deterministic mock (sufficient for demo) |

---

## Phase 1: Pipeline Schema Setup (Week 1–2)

### 1.1 Define the 4-vertical pipeline

Cloud-itonami's sales motion has these stages (adapting 5820's default 5-stage + exit to match reality):

```
Stage 1: Lead          — Free tenant registered (externalTenants 4)
Stage 2: Trial/Pilot   — Operator active on free tier, considering paid
Stage 3: Proposed      — RevOps-LLM (or owner) drafts paid offer (¥80k/月)
Stage 4: Negotiated    — Operator has reviewed, raised objections/feedback
Stage 5: Agreed        — Payment Link provided, operator ready to subscribe
Exit: Closed Won       — Stripe subscription created, ¥80k/月 charges begin
Exit: Closed Lost      — Operator declined or deferring indefinitely
```

### 1.2 Map the 4 verticals as accounts

| Account | ISIC | Vertical | Current stage | Next action |
|---|---|---|---|---|
| **6399-account** | 6399 | Job Board | Lead | Contact existing 4 free tenants; map to individual deals within this account |
| **6310-account** | 6310 | Talent Board | Lead | Same |
| **7810-account** | 7810 | Placement Desk | Lead | Same |
| **5820-account** | 5820 | CRM (managed tenants) | Agreed | ¥80k/月 Managed CRM Starter (self-referential — cloud-itonami pays itself to track its own pipeline) |

### 1.3 Define deal structure for 5820

Each free tenant becomes a **deal** (opportunity) inside the account. Example:

```
Deal: "Tenant A — Job Board (6399) free→paid conversion"
  Account: 6399-account
  Contact: tenant-a-owner@example.com
  Stage: Lead (free tenant just registered)
  Operator role: HR Director at medium staffing agency
  Problem: Managing multiple job boards; needs governance
  Proposed solution: 6399 Managed Starter ¥80k/月
  Discount authority: No discount (full ¥80k/月)
  Subscription tiers: [Free tier today] → [Managed Starter, effective date TBD]
  Revenue recognition: Straight-line monthly, ASC 606
  Expected close date: 2026-08-15 (2-3 week sales cycle guess)
  Audit trail: [owner contact 2026-07-18] → [sent pricing 2026-07-18] → [trial scheduled 2026-07-25]
```

### 1.4 Set up 5820 instance for production use

**Local dev** (minimal, no real API calls):
```bash
cd orgs/cloud-itonami/cloud-itonami-isic-5820

# Use sealed deterministic mock LLM (no API key needed)
export ISIC5820_API_TOKEN=$(openssl rand -hex 16)   # Generate a random token
export ISIC5820_STORE_FILE=./cloud-itonami-deals.edn  # Persistent store

clojure -M:serve   # Runs on port 8080 by default
# curl -s http://localhost:8080/health
```

**Or, Docker (more realistic for demo)**:
```bash
docker build -t cloud-itonami-isic-5820 .

export API_TOKEN=$(openssl rand -hex 16)
mkdir -p /tmp/isic5820-deals

docker run -d --name isic5820-crm \
  -p 8080:8080 \
  -e ISIC5820_API_TOKEN=$API_TOKEN \
  -e ISIC5820_STORE_FILE=/data/cloud-itonami-deals.edn \
  -v /tmp/isic5820-deals:/data \
  cloud-itonami-isic-5820

# Test health
curl -s http://localhost:8080/health
```

**Result**: A running 5820 CRM instance with an empty `/data/cloud-itonami-deals.edn` ready to record deals.

---

## Phase 2: Deal Import & First Proposal (Week 2–3)

### 2.1 Query current cloud-itonami state

Get list of 4 free tenants and their metadata:

```bash
# From itonami.cloud cockpit
curl -s -A "owner/1" https://itonami.cloud/api/fleet/metrics | jq '.tenants | {
  externalTenants,
  registryEntries,
  selfRegisteredOwners
}'

# Expected output:
# {
#   "externalTenants": 4,
#   "registryEntries": 4,
#   "selfRegisteredOwners": 4
# }
```

### 2.2 Create 4 Account + 4 Deals in 5820

For each free tenant, create:
1. Account (if not already created per vertical)
2. Deal (opportunity for that tenant to convert to paid)

**Example curl** (requires `$ISIC5820_API_TOKEN`):

```bash
export TOKEN=<generated-token-from-phase-1>

# Create Account for 6399
curl -s -X POST http://localhost:8080/propose \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "account/create",
    "account-name": "6399-job-board-vertical",
    "isic": "6399",
    "vertical": "Job Board",
    "operator": "cloud-itonami (dogfood)"
  }' | jq '.account-id'  # Returns account ID, e.g., "acct-6399-001"

# Create Deal #1 within 6399 account
curl -s -X POST http://localhost:8080/propose \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "deal/create",
    "account-id": "acct-6399-001",
    "deal-name": "Tenant Alpha — Job Board free→paid",
    "contact-email": "tenant-alpha@example.com",
    "contact-role": "HR Director",
    "current-stage": "lead",
    "problem": "Multi-board governance + compliance audit trail",
    "solution": "6399 Managed Starter ¥80,000/月",
    "proposed-price": 80000,
    "discount-authority-required": "none",
    "subscription-term": "monthly",
    "expected-close": "2026-08-15"
  }' | jq '.deal-id'  # Returns deal ID, e.g., "deal-6399-alpha-001"
```

Repeat for all 4 tenants across 3 verticals (3–4 deals likely; some tenants may be for different verticals).

### 2.3 First proposal: Stage transition to "Trial"

Once deals are created at stage "Lead", propose advancing to "Trial":

```bash
curl -s -X POST http://localhost:8080/propose \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "deal/transition",
    "deal-id": "deal-6399-alpha-001",
    "to-stage": "trial",
    "reason": "Tenant expressed interest in Managed tier trial",
    "rep": "owner (direct outreach)",
    "audit-notes": "Initial contact completed 2026-07-18"
  }'

# Expected response: Governor validates stage sequence, discount authority (none), etc.
# If valid: {"status": "approved", "new-stage": "trial", "audit-id": "audit-202607181.1"}
# If invalid: {"status": "rejected", "reason": "...", "audit-id": "audit-202607181.2"}
```

---

## Phase 3: Live Dashboard & Conversion Reporting (Week 3–4)

### 3.1 View pipeline health

```bash
curl -s "http://localhost:8080/dashboard?role=sales-manager&year=2026&month=7" \
  -H "Authorization: Bearer $TOKEN" | jq '.'

# Expected output:
{
  "stage-counts": {
    "lead": 4,
    "trial": 0,
    "proposed": 0,
    "negotiated": 0,
    "agreed": 0,
    "closed-won": 0,
    "closed-lost": 0
  },
  "conversion-rates": {
    "lead-to-trial": 0,
    "trial-to-proposed": 0,
    "proposed-to-agreed": 0,
    "agreed-to-closed-won": 0
  },
  "revenue": {
    "recognized-to-date": 0,  # ASC 606 straight-line recognized so far
    "potential": 320000,       # 4 deals × ¥80k/月 if all close
    "committed": 0             # Active subscriptions only
  }
}
```

### 3.2 Track conversions

As each tenant moves through the funnel, propose stage transitions:

```
Deal: Tenant Alpha (6399)
  2026-07-18 Lead → Trial (sent trial link + docs)
  2026-07-25 Trial → Proposed (tenant scheduled demo call)
  2026-08-01 Proposed → Negotiated (tenant asks about discounts for annual prepay)
  2026-08-08 Negotiated → Agreed (discount approved by RevOps Governor if within authority)
  2026-08-15 Agreed → Closed Won (Stripe subscription created via Payment Link)
  → Revenue recognized: ¥80,000 (July 15–Aug 15) + ¥80,000 (Aug 16–Sept 15) …
```

Dashboard updates in real-time:
- `stage-counts` reflects current distribution
- `conversion-rates` show progress (e.g., "lead-to-trial: 25% if 1 of 4 moved")
- `revenue.committed` updates when subscription is created

### 3.3 Export & share

**Weekly snapshot** (owner decision point, every Monday):

```bash
# Generate JSON report
curl -s "http://localhost:8080/dashboard?role=sales-manager&year=2026&month=7" \
  -H "Authorization: Bearer $TOKEN" > cloud-itonami-crm-week-$(date +%Y%m%d).json

# Share with team (or post to Slack, etc.)
jq '.revenue.committed, .stage-counts' cloud-itonami-crm-week-20260718.json
```

---

## Phase 4: Case Study & Distribution (Week 4+)

### 4.1 Document the motion

Write case study: **"How cloud-itonami manages its own SaaS pipeline with the 5820 CRM"**

```markdown
# Case Study: Using cloud-itonami-isic-5820 to run cloud-itonami's own sales ops

**Challenge**: We built a CRM that enforces governance (discount-authority, entitlement, 
audit-ledger), but had no live proof it actually works. Our own sales motion was tracked 
via spreadsheet and email, vulnerable to unauthorized discounts and non-compliant deals.

**Solution**: We deployed our own 5820 CRM to track 4-vertical Managed-tier conversion 
funnel (Job Board, Talent Board, Placement Desk, and this CRM itself).

**Outcome** (after 4 weeks):
- 4 free tenants mapped as deals
- 1 tenant advanced to "Proposed" stage (governance allowed a 10% early-bird discount)
- Pipeline funnel visible in real-time; conversion rates computed
- Revenue recognized: ¥X (ASC 606 straight-line)
- Audit trail: every deal transition, discount approval, and stage skip logged

**Lesson learned**: The governance model is real. When a tenant asked for a 30% 
discount, the Governor correctly rejected it (authority limit is 15% for this tier), 
forcing explicit CFO approval before proceeding.

**For prospects**: If you want a CRM that structurally enforces your revenue rules 
(not just advisory workflows), see how we operate this one ourselves.
```

### 4.2 Public demo

- **Screenshot**: Dashboard showing live pipeline (stage counts, conversion rates, revenue)
- **Case study page**: Add to 5820 repo's `/docs/dogfood-case-study.md`
- **Distribution**: Link from:
  - 5820 `business-model.md` → "See how we use this CRM ourselves"
  - itonami.cloud/isic-5820/ → case study card
  - Acquisition landing pages (prospect sees our live pipeline)

---

## Phase 5: First Real Conversion (Week 5+)

Once a real free tenant agrees to paid subscription:

1. **Final stage transition**: Deal → "Closed Won" via 5820 CRM
2. **Record the subscription**: `subscription-id`, `start-date`, `term` in deal record
3. **Revenue recognition**: Dashboard auto-computes ASC 606 straight-line (¥80k/月 appears as committed revenue)
4. **Update case study**: "First real paid customer acquired through this CRM"

**Success metric**: `revenue.committed` > 0 (at least one active subscription) in 5820 dashboard.

---

## Implementation Checklist

### Prerequisites (must complete first):
- [ ] E2E Stripe checkout verified (owner decision, Task #1)
- [ ] 5820 CRM instance deployed locally or on Cloudflare Pages/Workers (if public demo needed)
- [ ] `ISIC5820_API_TOKEN` generated and stored securely (1Password, env var, or kagi)
- [ ] `ISIC5820_STORE_FILE` location decided (local `/tmp/`, Docker mount, or Cloudflare KV)

### Phase 1 (Week 1–2):
- [ ] Define pipeline stages (Lead → Trial → Proposed → Negotiated → Agreed → Closed Won/Lost)
- [ ] Create 4 Accounts in 5820 CRM (one per vertical: 6399, 6310, 7810, 5820)
- [ ] Create 4 Deals (one per free tenant, or map tenant to account if multi-tenant per vertical)
- [ ] Test `/propose` endpoint with dummy deal creation

### Phase 2 (Week 2–3):
- [ ] Populate deals with real free-tenant data (name, email, company, operator role)
- [ ] Propose first stage transitions (Lead → Trial)
- [ ] Monitor Governor's validation (discount authority, stage-sequence, entitlement checks)
- [ ] Test dashboard: `GET /dashboard?role=sales-manager`

### Phase 3 (Week 3–4):
- [ ] Weekly dashboard snapshots (JSON export)
- [ ] Advance real deals through pipeline as they progress (calls, proposals, negotiations)
- [ ] Record Governor decisions (e.g., discount approval/rejection with audit trail)
- [ ] Share pipeline status with team

### Phase 4 (Week 4+):
- [ ] Write case study doc
- [ ] Publish to 5820 repo + itonami.cloud
- [ ] Include screenshot + conversion rates + audit examples

### Phase 5 (Week 5+):
- [ ] Close first real deal (Agreed → Closed Won when Stripe subscription created)
- [ ] Update dashboard & case study
- [ ] Update maturity-facts.edn: Business 1→2 (first paid org confirmed via CRM audit ledger)

---

## Success Criteria

| Gate | Criterion | Timeline |
|---|---|---|
| **Pipeline visible** | Dashboard shows 4 deals at "Lead" stage | Week 2 |
| **Governance enforced** | At least 1 discount proposal rejected or approved by Governor | Week 3 |
| **Conversion tracked** | ≥1 deal advanced to "Trial" or "Proposed" | Week 3 |
| **Case study published** | Public documentation of dogfood process + outcomes | Week 4 |
| **First paid org via 5820** | Deal transitioned to "Closed Won" when real Stripe subscription created | Week 5+ |

---

## Owner Decision Point

**Before proceeding with Phase 1, owner must decide**:

1. **Local dev or cloud-hosted?** (Local: `clojure -M:serve`. Cloud: Docker on Cloudflare Workers or VM)
2. **Real model LLM or sealed mock?** (Sealed mock is sufficient for demo; real model requires `ISIC5820_MODEL_API_KEY`)
3. **Who executes?** (Owner, product lead, or dedicated sales-ops person)
4. **Timeline urgency**: 2-3 weeks is realistic; can accelerate to 1 week if focused sprint

---

## Why this unlocks acquisition

1. **Removes "prove it works" objection**: Prospects see cloud-itonami's actual pipeline, governed by its own CRM
2. **Demonstrates discipline**: Audit trail proves deals aren't faked; governance proves authority limits are real
3. **Creates urgency**: "This is how serious SaaS vendors run their business; should you trust anything less?"
4. **Provides feedback**: Real deal flow surfaces missing features or governance gaps before selling to customers
