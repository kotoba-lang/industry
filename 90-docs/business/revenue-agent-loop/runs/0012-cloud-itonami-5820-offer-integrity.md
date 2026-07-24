# Run 0012 — cloud-itonami 5820 offer integrity

**Status:** completed — commercial closure required
**Started / ended:** 2026-07-24
**Owner:** agent
**Mode:** cash-first

## Hypothesis

5820 looked like the highest-ranked executable green action after net-kotobase
reached external-review wait state. A bounded offer audit would show whether a
qualified B2B prospect could truthfully be sent to checkout now.

## Evidence

- Accepted 5820 ADR and live Stripe object: ¥80,000/month recurring, unlimited
  seats.
- GTM document: recommends a separate ¥20,000/30-day paid trial, but marks it
  as an owner decision. No matching checkout or ratified order form exists.
- Checkout guide: final Subscribe, webhook receipt, subscription status and
  tenant provisioning remain unverified.
- Shared cloud-itonami Terms and Privacy: explicitly DRAFT; contracting entity,
  Japanese tax and foreign-company registration contain unresolved counsel
  markers.

## Result

The previous scorecard label “¥20k paid discovery trial” was not an executable
green offer. Created `cloud-itonami-commercial-closure.md` with one bounded
proposed sprint, its truthful delivery scope, and seven explicit approval
decisions. No live Stripe object, document publication, prospect contact,
subscription or deployment was changed.

## Score

The numerical prior remains 61/100 because no external conversion evidence
changed. The hard gate changes from green to **red**; score cannot override it.

## Decision

**Stop external sale; continue commercial closure.** The next single action is
operator/counsel ratification of the closure packet. After ratification, create
an exactly matching Stripe checkout and verify it end to end before outreach.
