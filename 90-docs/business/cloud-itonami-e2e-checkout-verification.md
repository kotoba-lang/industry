# cloud-itonami E2E Checkout Verification Guide

**Date**: 2026-07-18  
**Status**: Verification pending (owner execution required)  
**Scope**: 4 flagship Stripe Payment Links (6399/6310/7810 + 5820)  
**Related**: ADR-2607161745, ADR-2607161620 (Stripe go-live procedures)

---

## Current Status: What's been tested

**Date tested**: 2026-07-16 (ADR-2607161745 verification notes)

| Step | Status | Notes |
|---|---|---|
| Stripe API: Product creation | ✅ Done | 4 products created (6399/6310/7810/5820) |
| Stripe API: Price creation | ✅ Done | 4 prices, ¥80,000/month recurring JPY, unit_amount=80000 |
| Stripe API: Payment Link creation | ✅ Done | 4 payment links generated |
| Payment Link accessibility | ✅ Done | All 4 URLs confirmed live and publicly accessible |
| **Checkout page loads** | ✅ Done | Page renders (no 404/500) |
| **Email input step** | ✅ Done | Email field is populated and editable |
| **Final "Subscribe" click** | ❌ **SKIPPED** | Owner decision: did not execute actual subscription creation |
| **Webhook reception** | ❌ **UNTESTED** | No checkout.session.completed event captured (because final step skipped) |
| **Subscription status API** | ❌ **UNTESTED** | No active subscription to query |
| **Email receipt** | ❌ **UNTESTED** | No receipt email sent (because subscription not created) |

---

## What needs to be tested

### Phase 1: Complete the subscription (Final Subscribe click)

**What happens**:
1. Click **Subscribe** button on Stripe checkout
2. Stripe processes the payment method (in this case, payment_method_collection=if_required means the payment form only appears if needed; for a ¥0 trial, it should skip straight to confirmation)
3. Stripe creates a live `Subscription` object (charges will begin next month: 2026-08-18 ¥80,000)
4. Checkout.session → subscription_id populated

**Success criteria**:
- ✅ Page redirects to success page or displays confirmation (Stripe default: blank page or redirect based on Payment Link config)
- ✅ No error message
- ✅ No "payment declined" or validation error

**Risk**: This is a LIVE Stripe account on LIVE mode. A subscription created here will:
- Bill ¥80,000 on 2026-08-18 (JPY recurring subscription, month[0] on 2026-07-18)
- Send an email to the provided email address confirming subscription
- Appear in Stripe Dashboard under "Subscriptions"

**Decision required from owner**: Proceed or cancel?

---

### Phase 2: Verify webhook receipt

**What should happen** (if webhook handler is live):
1. Stripe sends `checkout.session.completed` event to the webhook endpoint
2. Event includes:
   - `customer_email`
   - `subscription` (if subscription was created)
   - `payment_intent` (if payment was processed)

**Where to check**:
1. **Stripe Dashboard** → Webhooks → Recent deliveries
   - Filter by event type `checkout.session.completed`
   - Inspect payload to confirm `subscription_id` is populated

2. **itonami.cloud webhook logs** (if available):
   ```bash
   curl -s https://itonami.cloud/api/stripe/webhook-log \
     -H "Authorization: Bearer <owner-secret>" | \
     jq '.events[] | select(.type=="checkout.session.completed") | .data'
   ```

**Success criteria**:
- ✅ Stripe Dashboard shows event as "Delivered" (not "Failed")
- ✅ HTTP 200 response from webhook endpoint
- ✅ Event payload includes subscription_id

**Why it matters**: If webhook fails silently, the subscription is created in Stripe but itonami.cloud never knows, and the first paid org is "invisible" (not auto-provisioned as a managed tenant).

---

### Phase 3: Verify subscription object in Stripe

**Test command** (requires `STRIPE_SECRET_KEY`):
```bash
export STRIPE_SECRET_KEY=$(op read "op://gftdcojp/Stripe Live API Keys/STRIPE_SECRET_KEY")

# List all subscriptions (there should be exactly 1 if this is the first test)
curl -s https://api.stripe.com/v1/subscriptions \
  -H "Authorization: Bearer $STRIPE_SECRET_KEY" \
  -d "limit=1" | jq '.data[0] | {
    id,
    customer_email,
    items: .items.data[0] | {product: .price.product, amount: .price.unit_amount},
    status,
    current_period_start,
    current_period_end,
    next_invoice_date: .next_pending_invoice_item_invoice_date
  }'
```

**Success criteria**:
- ✅ `status` = "active" or "past_due" (not "incomplete" or "incomplete_expired")
- ✅ `items.amount` = 80000 (JPY ¥80,000)
- ✅ `current_period_end` is 2026-08-18 (1 month from 2026-07-18)

---

### Phase 4: Verify itonami.cloud tenant auto-provisioning

**Test command** (check if external paid org was created):
```bash
curl -s -A "owner/1" https://itonami.cloud/api/fleet/metrics | jq '.tenants'
```

**Expected output** (if webhook handler auto-provisions):
```json
{
  "externalPaid": 1,
  "externalTotal": 5,  // was 4, now 5 if provisioning worked
  "external-paid": 1   // duplicate key for backward compat
}
```

**If still `externalPaid: 0`**: Webhook was received but tenant was not auto-provisioned (either handler is broken or configured to skip auto-provisioning).

---

### Phase 5: Check for email receipt

**Where**: Email inbox for the address used in checkout

**Expected**:
- **From**: `billing@stripe.com` or `support@itonami.cloud` (depending on Stripe config)
- **Subject**: "Invoice for your subscription" or "Subscription confirmation"
- **Content**: ¥80,000 / month, next billing date 2026-08-18, payment method on file

**Success**: Email arrives within 5 minutes of subscription creation

---

## Step-by-step execution

### For owner to run

1. **Choose 1 flagship to test** (recommend **6399 Job Board** as lowest-risk choice for first test):
   ```
   Product: cloud-itonami-isic-6399
   Payment Link: https://buy.stripe.com/bJe9AS74n1dmcOQcEvbMQ0b
   ```

2. **Gather prerequisites**:
   - A test email address (e.g., `owner+test-6399@junkawasaki.com`)
   - Access to that email inbox
   - Stripe Live API secret key (via 1Password)
   - A browser (Chrome preferred for Console access if debugging needed)

3. **Open Payment Link in browser**:
   ```
   https://buy.stripe.com/bJe9AS74n1dmcOQcEvbMQ0b
   ```

4. **Fill checkout form**:
   - Email: `owner+test-6399@junkawasaki.com` (or your test address)
   - (Payment method may not appear if Stripe recognizes it as ¥0 / free tier. If it does, use a test card: `4242 4242 4242 4242` / any future date / any CVC)

5. **Click Subscribe**:
   - Watch for redirect or success page
   - Note the time (e.g., 2026-07-18 14:30:00 JST)

6. **Check email** (wait 5–10 seconds):
   - Verify receipt arrived
   - Save confirmation for records

7. **Run Phase 2–4 verification commands** (timing: immediately after subscribe, then 5 min later):
   - Check Stripe Dashboard webhook log
   - Query subscription API
   - Check `/api/fleet/metrics` for `externalPaid` increment

8. **Document results** in a new comment/note:
   - Time of subscription creation
   - Email address used
   - Subscription ID (from Stripe Dashboard: `sub_xxxxx`)
   - Webhook status (delivered / failed)
   - Tenant auto-provision status (yes / no)
   - Any errors or surprises

---

## Expected outcomes & next steps

### Success path (all phases pass):
✅ E2E checkout works end-to-end  
✅ First paid subscription is live  
✅ Webhook delivers and auto-provisions tenant  
→ **Proceed to real first-customer acquisition** (use this as confidence signal for sales team)  
→ Update maturity-facts.edn: Business 1→2 (Stripe live-wired, now with first paid org verified)

### Partial success (Phase 1–3 pass, Phase 4 fails):
✅ Subscription created in Stripe  
✅ Webhook delivered  
❌ Tenant not auto-provisioned  
→ **Webhook handler is broken or needs tuning**. Debug required:
- Is the webhook endpoint receiving the event? (check Stripe Dashboard → Webhook deliveries)
- Is the handler parsing the event correctly? (check itonami.cloud logs: `ssh itonami.cloud tail -f /var/log/webhook.log`)
- Is there a permission/database error preventing tenant creation? (check CloudFlare Workers KV writes)
→ Fix handler, then re-test by manually creating a second subscription

### Failure (Phase 1 fails):
❌ Checkout page errors or refuses payment  
→ **Debugging**:
- Check Stripe Dashboard → Events → `checkout.session.*` for errors
- Open browser Console (F12) → check for JavaScript errors
- Check Payment Link config (is it correctly linked to Product + Price?)
→ Fix configuration, retry

---

## Safety checklist

- [ ] Testing with a **real email address you control** (not a fake address)
- [ ] Aware that this **WILL charge ¥80,000 in one month** (unless subscription is cancelled before 2026-08-18)
- [ ] **Secret key is used via `op read` only** (never pasted into chat or logged)
- [ ] **Webhook log is checked in Stripe Dashboard only** (not in plaintext logs)
- [ ] **Subscription object is queried, not modified** (no cancellation or metadata edits unless intentional)
- [ ] **Results are documented** (date/time/subscription ID noted for audit trail)

---

## Immediate follow-up after checkout verification

If Phase 1–4 pass (subscription created + webhook delivered + tenant provisioned):

1. **Update maturity-facts.edn**: Change Business score from 1→2 (or 2→3 if this is deemed "first paid org") with notation:
   ```edn
   :revenue 1  ;; "first paid subscription" verified; ¥80,000 MRR from test org (or real first customer)
   ```

2. **Announce to acquisition team**: "Checkout flow is verified. We can now route real sales leads to Payment Link with confidence."

3. **Test Phase 5 (email receipt)**: Ensure billing email is received so first real customer has a clear receipt (good confidence signal).

4. **Cancel test subscription** (if owner wants to):
   ```bash
   curl -X POST https://api.stripe.com/v1/subscriptions/{sub_xxxxx}/cancel \
     -H "Authorization: Bearer $STRIPE_SECRET_KEY"
   ```
   (This stops the ¥80,000 charge on 2026-08-18. Do this if the test org should not actually be charged.)

---

## If webhook handler is missing/incomplete

**Current state** (from ADR-2607161745 note):
> "未検証のまま残る: 支払い後の「tenant を managed として有効化する」というオペレーション側のフォローアップは現状 100% 手動"

**Workaround for Phase 1 test**:
1. Complete checkout (Phase 1)
2. Manually enable tenant in itonami.cloud:
   ```bash
   # Query the subscription
   export SUB=$(curl -s https://api.stripe.com/v1/subscriptions -H "Authorization: Bearer $STRIPE_SECRET_KEY" \
     -d "limit=1" | jq -r '.data[0].id')
   
   # Manually create tenant record in itonami.cloud KV:
   # (owner-specific — depends on tenant schema)
   curl -X PUT https://itonami.cloud/api/tenants \
     -H "Authorization: Bearer <owner-secret>" \
     -H "Content-Type: application/json" \
     -d "{\"subscription_id\": \"$SUB\", \"status\": \"active\"}"
   ```

3. Verify `/api/fleet/metrics` now shows `externalPaid: 1`

**Then**: Raise issue to wire up automatic webhook handler if not already done.

---

## Test subscription IDs (for reference)

If this guide is followed multiple times:

| Test run | Date | Email | Subscription ID | Status | Notes |
|---|---|---|---|---|---|
| #1 (this guide) | 2026-07-18 | owner+test-6399@... | `sub_XXXXX` | [pending owner execution] | 6399 Job Board |

(Add rows as tests are completed.)

---

## Success criteria for this verification

- ✅ Stripe subscription is created and appears in Dashboard
- ✅ Webhook event is received and logged
- ✅ itonami.cloud recognizes the paid org (externalPaid increments or tenant is created)
- ✅ Email receipt is sent to customer address
- ✅ All steps are documented with timestamps

**Once all pass, the revenue gate (`hyp/itonami-smb-pay`, external-paid ≥ 1) transitions from "design" to "in progress" and maturity-facts.edn can be updated from `revenue: 0` to `revenue: 1` or `revenue: 3` depending on whether this is a test or a real first customer.**
