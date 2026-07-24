# ISIC 5820 Governed CRM Validation Sprint — Order Form

**Status:** owner-approved execution template; checkout E2E pending
**Version:** 2026-07-24
**Supplier:** AWAI Network, L.L.C.
**Payment collector:** Gftd Japan K.K., acting solely as AWAI Network’s
disclosed collection agent

This Order Form is available only to a legal entity or business operator
purchasing solely for business use. It is not a consumer offer.

## Customer

- Legal business name:
- Registration/corporate number:
- Registered address:
- Authorized contact:
- Contact email:
- Business-use confirmation: `Yes / No`

## Commercial terms

| Item | Term |
|---|---|
| Service | ISIC 5820 Governed CRM Validation Sprint |
| Fee | JPY 20,000 |
| Term | 30 calendar days from kickoff |
| Renewal | None; fixed-term and non-renewing |
| Users | Up to 5 named users |
| Payment | One-time Stripe checkout |
| Starter option | Separate, optional ¥80,000/month subscription after sprint acceptance |
| Conversion credit | JPY 20,000 against the first Starter month if ordered within 30 days after sprint completion |

The fee does not include Japanese consumption tax charged by AWAI. Where the
transaction qualifies as a business-oriented electronic service supplied by a
foreign provider, the Japanese customer may have reverse-charge obligations.
Customer should obtain its own tax advice.

## Included delivery

1. one customer pipeline configured using the standard linear stage model;
2. standard discount-authority and subscription-entitlement policies;
3. import of one mutually agreed sample or bounded real pipeline dataset;
4. one onboarding/review session;
5. evidence of one governed approval;
6. evidence of one blocked entitlement or policy violation; and
7. one audit-ledger export at sprint end.

## Excluded

Salesforce migration, bespoke integration, custom recognition policy,
marketing automation, customer-support ticketing, production SLA, unlimited
data remediation, and any feature outside the published 5820 honest scope.

## Acceptance and refund

Delivery is accepted when the seven included items are made available, subject
to customer review within five business days. AWAI will correct a reproducible
material delivery defect within the sprint scope. Refunds are limited to
duplicate billing, AWAI’s failure to begin or materially deliver the sprint,
and cases required by applicable law.

## Data and governing documents

The versioned cloud-itonami Terms, Privacy Policy and applicable DPA govern.
Customer Data export and deletion follow those documents. If this Order Form
conflicts with them, this Order Form controls only for its product, price,
fixed term, renewal and included delivery.

## Stripe object contract

The test and live Stripe objects must use:

```text
name: cloud-itonami ISIC 5820 Governed CRM Validation Sprint
currency: jpy
unit_amount: 20000
type: one_time
quantity: 1
automatic_renewal: false
metadata:
  service: cloud-itonami
  vertical: isic-5820
  offer: validation-sprint-30d-v1
  supplier: awai-network-llc
  collector: gftd-japan-kk
```

A recurring Price, subscription-mode Checkout, or the existing ¥80,000/month
Payment Link does not satisfy this Order Form.

## Approval

- Customer authorized representative / date:
- AWAI Network authorized representative / date:
