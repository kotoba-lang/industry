# Run 0011 — net-kotobase Stripe boundary E2E

**Status:** completed — continue
**Started / ended:** 2026-07-24
**Owner:** agent
**Mode:** cash-first

## Hypothesis

If the complete Worker billing boundary is exercised with a mocked Stripe API
and signed webhook events, then checkout metadata, entitlement activation and
cancellation defects can be removed before using external Stripe test
credentials.

## Before

- verified external payments: 0
- external checkout starts: 0
- real Stripe test-mode trace: 0
- Worker-boundary checkout-to-cancellation automated trace: 0
- score: 60/100; commercial hard gate red

## One selected action

Add one automated boundary trace:

`register → Standard checkout → signed checkout event → active entitlement → signed cancellation`

The trace must also prove that Pro remains unavailable, a correctly signed
event older than the five-minute tolerance is rejected, and an older checkout
event cannot reactivate a newer canceled subscription.

## Result

- Added `worker/test/stripe_e2e_test.cljc` and made it part of the default
  Worker suite.
- Verified checkout session parameters carry the tenant DID and Standard tier
  on both Checkout and Subscription metadata.
- Webhook verification now accepts Stripe's multiple `v1` signatures during
  secret rotation and rejects timestamps outside 300 seconds.
- Fulfillment now ignores events older than the persisted tenant state, so
  delayed delivery cannot undo a newer cancellation.
- `npm run build` completed. Existing unrelated compiler warnings remain.
- The complete Worker suite passed, including the new five-step Stripe trace
  and the existing account-deletion/late-webhook regressions.

No deployment, production setting, external contact or payment occurred.

## Score after

**Total: 60/100; unchanged.** Internal test evidence reduces implementation
risk but is not external demand, a real Stripe test-mode trace, or revenue.
The commercial hard gate remains red until counsel and provider evidence are
returned.

## Decision

**Continue.** Next single action: with authorized Stripe test credentials,
capture a real test-mode Checkout → signed webhook → KV entitlement →
cancellation trace. If credentials remain unavailable, wait for counsel/provider
exports rather than substituting another internal implementation task.
