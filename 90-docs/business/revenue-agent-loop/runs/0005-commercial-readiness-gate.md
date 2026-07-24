# Run 0005 — commercial readiness gate

**Status:** stopped — external authority required
**Started:** 2026-07-24
**Mode:** unblock revenue

## Hypothesis

The current portfolio bottleneck is no longer product implementation or lead
discovery. It is the absence of one legally and operationally green seller
boundary. Closing or explicitly owner-routing that boundary raises the expected
value of every downstream sales action.

## Selected action

Produce one authoritative go/no-go checklist for:

1. service operator and contracting party,
2. Stripe merchant-of-record/payment collection relationship,
3. signed intercompany collection agreement where applicable,
4. Japan foreign-company-registration decision,
5. consumption-tax/reverse-charge treatment,
6. final terms/privacy/DPA/operator disclosure,
7. product-specific refund, cancellation and support terms,
8. counsel/owner approvals and their evidence.

Do not add product features or contact prospects in this run.

## Success / stop

- Success: one product has a documented green commercial boundary and may
  truthfully solicit/accept a nonowner payment.
- Continue: all internally resolvable facts and drafts are complete, with a
  finite owner/counsel decision list.
- Stop: required professional advice, signature or corporate action is the only
  remaining work; record it without fabricating approval.

## Result

The authoritative checklist is
[`../COMMERCIAL-GO-NO-GO.md`](../COMMERCIAL-GO-NO-GO.md).

No currently inspected product has a green commercial boundary:

- cloud-itonami has an unsigned AWAI/Gftd Japan payment-collection agreement,
  unresolved Japan registration/tax questions, and draft terms/privacy;
- club-shinshi has unresolved adult-service legal, age, refund, tax and payout
  conditions;
- net-babiniku's own lean-loop record hard-holds monetization until a payment
  rail is contracted; and
- net-kotobase names Gftd Japan as operator, but its terms/privacy contain
  unresolved `CONFIRM` items and its price ADR remains proposed.

The smallest path is net-kotobase Standard because it avoids the AWAI
intercompany and foreign-company boundary. It still requires owner decisions,
counsel approval, price ratification and test-mode fulfillment evidence.

## Evidence / accounting

- External contacts: 0
- Checkout starts / payments: 0 / 0
- Revenue / fees / marginal cost / gross profit: ¥0 / ¥0 / ¥0 / ¥0
- Human time: not recorded; agent inspection only
- Decision: **stop** this run at the professional-advice/signature boundary
- Next: Run 0006, bounded net-kotobase Standard commercial-closure packet
