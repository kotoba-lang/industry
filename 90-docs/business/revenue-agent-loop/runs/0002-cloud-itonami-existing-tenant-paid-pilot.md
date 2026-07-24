# Run 0002 — cloud-itonami 6399/6310 existing-tenant paid pilot

**Status:** stopped — no legitimate contact path
**Started:** 2026-07-24
**Ended:** 2026-07-24
**Owner:** agent loop + founder for human relationship where needed
**Mode:** profit-first within zero-revenue portfolio

## Hypothesis

If each of the four existing external free-tenant owners receives a short
use-case interview and a concrete paid-pilot yes/no offer, at least one
qualified buying signal or explicit rejection reason will be observed within
seven days. This has higher expected value than adding another vertical or
feature because these tenants have already claimed and entered registry data.

## Before

| Funnel stage | Count/value | Evidence |
|---|---:|---|
| External free tenants | 4 | `cloud-itonami-6399-6310-acquisition-audit.md` |
| Registry-active tenants | 4 | same audit |
| Operator-interest issues | 0 | same audit |
| Active paid subscriptions | 0 | same audit / fleet metrics |
| MRR | ¥0 | same audit |

The current data does not identify whether each tenant primarily uses 6399 or
6310, whether the person is a budget owner, or whether outreach consent/contact
data is available. Those are observation tasks, not facts to infer.

## Score before

| Axis | 0–5 | Reason |
|---|---:|---|
| P(payment ≤30d) | 2 | actual tenants exist, but no buying signal |
| Speed to evidence | 4 | four bounded interviews can resolve fit quickly |
| Existing external demand | 4 | four external claims with registry input |
| Payment readiness | 4 | live Stripe Payment Link; production fulfillment still verify before close |
| 12m expected gross profit | 5 | ¥80k/month managed tier if retained |
| Repeat likelihood | 5 | recurring managed SaaS |
| Gross margin | 4 | software margin, but onboarding/support cost is not yet measured |
| Learning value | 5 | directly identifies role, use case, price and blocker |
| Spillover | 5 | informs 6399, 6310, 7810 and the cloud-itonami sales system |
| Evidence confidence | 4 | tenant facts are observed; buyer intent remains unknown |

**Total:** 79/100

## Hard gates

- [ ] Resolve the exact four tenant records without exposing unrelated personal data.
- [ ] Confirm a legitimate contact/consent path; do not infer or scrape private contact data.
- [ ] Confirm the ¥80k offer, trial terms, operator identity and support promise match the live page.
- [ ] Re-run Checkout→verified webhook→tenant entitlement preflight before accepting a customer.
- [ ] Keep dogfood/self-purchase excluded from externalPaid.

**Gate:** green for read-only segmentation and drafting; outreach only where a
legitimate recorded contact path exists. Payment acceptance remains conditional
on the fulfillment preflight.

## One selected action

Resolve and segment the four existing tenants, then obtain one explicit answer
per reachable tenant to:

1. role/budget authority,
2. 6399 or 6310 use case,
3. current blocker,
4. willingness to run a paid managed pilot.

No product feature work is included in this run.

## Success / continue / stop

- Success: one paid-pilot yes, followed by verified checkout and fulfillment.
- Continue: at least one qualified interview with a concrete price, feature or
  trust blocker.
- Change: reachable tenants are users but not budget owners; move to 7810
  founder/operator outbound with the learned message.
- Stop: no legitimate contact path or all four explicitly reject the problem,
  after recording reasons.
- Timebox: seven days or 12 person-hours, whichever comes first.

## Result

The existing implementation record resolves the contact question without
reading or exposing tenant PII:

- ADR-0025 explicitly states that all four existing real trial tenants predate
  the optional contact-email field.
- The first real nudge scan observed all four as `:no-contact-email`.
- The ADR explicitly requires fail-closed behavior: do not guess a fallback
  address and do not route around the missing consent/contact field.

Therefore the four tenants cannot be interviewed through a legitimate recorded
contact path today. No private contact data was enumerated, no guessed address
was used, and no message was sent.

## Score after

- Speed to evidence: `4 → 1`
- Evidence confidence: `4 → 5`
- Total: `79 → 75`

The product remains economically attractive, but this specific action is
unexecutable until a tenant voluntarily re-registers/adds contact information
or an in-product consented interview surface is introduced.

## Decision

`change`

Stop this action and advance to public, qualified 7810 prospect discovery. Do
not turn “add a contact form for old tenants” into unscheduled feature work in
this run.
