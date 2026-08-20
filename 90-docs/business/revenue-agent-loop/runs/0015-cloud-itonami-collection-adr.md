# Run 0015 — cloud-itonami collection ADR

**Status:** completed — advance to Stripe test-mode preparation
**Started / ended:** 2026-07-24

## Result

The owner directed the AWAI Network–Gftd Japan payment-collection arrangement
to be concluded internally. ADR-2607242600 now records the accepted boundary:
AWAI contracts and supplies; Gftd Japan collects through Stripe as a disclosed,
limited agent; settlement is monthly with only actual payment costs deducted.

Formal signature counterparts and legal notice contacts remain confidential
corporate records outside git. The versioned agreement contains the approved
operating terms.

No Stripe object, checkout, deployment, prospect contact or payment changed.

## Score and decision

5820 remains 61/100 because no external conversion evidence changed. The
operator/collector sub-gate is closed. Tax/counsel and real E2E remain open.

**Continue:** prepare an exactly matching ¥20,000 fixed-term Stripe checkout in
test mode, without exposing credentials or changing the existing live
¥80,000/month Payment Link.
