# Run 0017 — cloud-itonami 5820 validation sprint order form

**Status:** completed — Stripe test credential required
**Started / ended:** 2026-07-24

## Result

Created the execution template and machine-readable Stripe object contract for
the JPY 20,000 fixed 30-day 5820 validation sprint.

The order form fixes B2B eligibility, AWAI supplier identity, Gftd Japan
collection disclosure, one-time/non-renewing billing, five-user limit,
delivery acceptance, refund boundary, conversion credit and exclusions.

No Stripe object, customer contract, deployment or payment was created.

## Decision

Continue when an authorized Stripe test secret is provisioned. Create a
one-time JPY 20,000 test Price and Payment Link with the exact metadata in the
order form, then prove that it cannot create a subscription.
