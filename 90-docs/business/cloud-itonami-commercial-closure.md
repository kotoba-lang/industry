# cloud-itonami commercial closure packet

**Prepared:** 2026-07-24
**Scope:** cloud-itonami B2B managed services, beginning with ISIC 5820
**Status:** operator selected; remaining owner/counsel decisions required; do not send a buyer to live checkout

## Recorded owner decision

On 2026-07-24 the owner selected **AWAI Network, L.L.C.** as the
cloud-itonami contracting party and Service operator. Gftd Japan K.K. is not
the contracting operator. On the same date the owner directed the AWAI–Gftd
Japan limited collection relationship to be concluded internally; its
approved terms are recorded by ADR-2607242600. Formal corporate counterparts
remain outside git.

## Why this packet exists

The current 5820 records describe two different offers:

- accepted/live Stripe offer: Managed CRM Starter, ¥80,000/month, unlimited seats;
- unratified GTM recommendation: ¥20,000 paid trial, credited on conversion.

Only the ¥80,000 recurring Stripe object exists. The ¥20,000 offer has no
ratified order form or matching checkout. The shared Terms and Privacy files
are marked DRAFT and contain unresolved operator, tax and foreign-company
registration statements. The existing E2E guide says the final live Subscribe,
webhook and provisioning steps were not completed.

No external buyer should receive either checkout until one coherent offer and
contracting entity are approved.

## Proposed first offer

Except for the selected operator, these are proposed defaults, not accepted
terms:

| Decision | Proposed default |
|---|---|
| Contracting operator | **AWAI Network, L.L.C. — owner-selected** |
| Product | ISIC 5820 Governed CRM validation sprint |
| Buyer | Japan-based business only; no consumer sale |
| Price | JPY 20,000; Japanese consumption-tax treatment subject to counsel confirmation of the B2B reverse-charge notice |
| Term | fixed 30 days; no automatic renewal |
| Conversion | optional Managed CRM Starter at ¥80,000/month |
| Credit | full ¥20,000 credit against the first Starter month |
| Seats | up to 5 named users during the sprint |
| Included | one pipeline, standard stage policy, standard discount-authority tiers, onboarding session, export at end |
| Excluded | Salesforce migration, custom integration, custom revenue-recognition policy, marketing automation, support desk, SLA |
| Success evidence | one real pipeline imported; one governed approval; one entitlement rejection; one audit-ledger export |
| Cancellation/refund | fixed sprint is not auto-renewed; refund for operator non-delivery, duplicate billing or law-required cases |
| Data deletion | export window and deletion timing must match the final Terms/DPA |

This bounded sprint is preferable to silently calling the existing ¥80,000
subscription a “trial.” It creates a genuine budget signal while limiting
implementation and legal scope.

## Decisions required before checkout creation

1. Who invoices and bears Japanese consumption-tax obligations.
2. Acceptance of the proposed sprint scope, tax treatment, credit and refund.
3. Final Terms, Privacy, DPA and order-form entity consistency.
4. Support contact and accountable service owner.
5. Authorization to create a ¥20,000 one-time or fixed-term Stripe checkout.
6. Authorized live-mode E2E method that cannot accidentally create an
   uncancelled ¥80,000 renewal.

## Release gate

Green requires all of:

- operator, invoice entity and tax display agree across Stripe and documents;
- counsel-reviewed Terms/Privacy/DPA/order form are versioned;
- the checkout amount and renewal behavior exactly match the order form;
- signed webhook and provisioning are observed end to end;
- cancellation/refund and data-export paths have named owners;
- one qualified external business has consented to receive the offer.

Until then, product demos and discovery conversations may occur, but agents
must not present the live Payment Link as an approved purchasable offer.
