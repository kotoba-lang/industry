# Run 0007 — net-kotobase privacy inventory

**Status:** completed — change
**Started / ended:** 2026-07-24
**Mode:** close commercial privacy gate

## Hypothesis

Most Privacy placeholders can be resolved from implementation evidence without
inventing legal approval. Doing so will isolate the genuinely external or
missing controls.

## Action and result

Inspected account registration, sessions, PATs, OAuth, storage, diagnostics and
deletion behavior. Updated `legal/privacy.md` and `docs/DATA-HANDLING.md`.

Confirmed:

- normalized email and PBKDF2-derived credential storage;
- session cookie properties and 30-day expiry;
- 10-minute OAuth transaction expiry;
- PAT digest storage and 1–365 day expiry;
- optional Google/GitHub identity data;
- no advertising or client-side behavioral analytics integration;
- server-side first-party funnel counters;
- content-addressed deletion limits;
- account and billing records currently have no automatic expiry.

## Funnel / accounting

- External contacts: 0
- Checkout starts / payments: 0 / 0
- Revenue / fees / marginal cost / gross profit: ¥0 / ¥0 / ¥0 / ¥0
- Decision: **change**

## Posterior and next

Privacy uncertainty is no longer one broad draft problem. The remaining
commercial blockers are finite:

1. implement a verified account-deletion procedure or endpoint;
2. record actual Cloudflare diagnostic retention;
3. create the per-subprocessor foreign-transfer register;
4. counsel review of Terms, Privacy and commercial disclosure;
5. Stripe test-mode E2E evidence.

The next internally executable action is account deletion because the current
Privacy text accurately discloses that it is manual, but a self-service path
reduces privacy and support risk.
