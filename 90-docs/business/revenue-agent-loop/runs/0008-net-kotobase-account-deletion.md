# Run 0008 — net-kotobase verified account deletion

**Status:** completed — legacy production audit found zero auth records
**Started:** 2026-07-24
**Mode:** privacy-risk reduction

## Hypothesis

A deletion path that invalidates every credential and removes mutable
account/billing records will close more commercial risk than another sales or
UI change.

## Pre-implementation finding

The current KV shape is not safely deletable from one account record:

- account records are keyed by email hash;
- OAuth identity records are keyed by provider+subject hash;
- sessions and PATs are keyed by independent token hashes;
- billing state is keyed by tenant DID;
- there is no per-tenant index of those authentication keys.

Deleting only the email account row would leave sessions, PATs, OAuth identity
or billing state behind. That is not an acceptable deletion implementation.

## Selected implementation

Introduce a tenant-scoped credential index for newly created session, PAT,
account and OAuth identity keys. Account deletion must:

1. require a live browser session and deliberate confirmation;
2. reject deletion while Stripe status is active/trialing/past_due, directing
   the customer to cancel first;
3. tombstone the tenant DID before deleting other keys, so concurrent or old
   credentials fail closed;
4. delete indexed sessions, PATs, account/OAuth identity records and mutable
   billing state;
5. retain only a minimal non-PII deletion tombstone for replay/late webhook
   protection;
6. return an explicit list of content-addressed data that cannot be guaranteed
   erased;
7. cover password, OAuth, session, PAT and late-Stripe-event cases in tests.

## Migration constraint

Existing credentials predate the tenant index. Before enabling self-service
deletion, an operator migration must enumerate current KV keys, build the index,
and verify counts without printing emails, tokens, password hashes or provider
subjects.

## Success / stop

- Success: all mutable records and credentials for a fixture tenant are removed,
  old credentials fail, and a late webhook cannot silently reactivate it.
- Stop: Cloudflare KV cannot provide a bounded safe enumeration/migration path;
  retain verified manual deletion and do not advertise self-service deletion.

## Implementation evidence

Implemented:

- marker-first tenant credential indexing for password accounts, OAuth
  identities, sessions and PATs;
- deletion tombstone checked by old browser sessions and PATs;
- `DELETE /auth/account` with live-session and explicit-confirmation gates;
- active/trialing/past_due subscription deletion refusal;
- indexed mutable auth and billing-state deletion;
- fail-closed behavior when a legacy tenant index is missing or exceeds one
  bounded KV page;
- late signed Stripe webhook suppression for a deleted tenant;
- explicit response that content-addressed erasure is not guaranteed.

Verification:

```text
account deletion:
  ok - indexed account/session/PAT removed and old credentials rejected
  ok - late signed Stripe event cannot reactivate deleted tenant
  ok - active subscription blocks deletion

site routes: 6 tests passed
production entry bundle: build completed
```

No deployment or production KV mutation was performed.

## Decision

**Complete.** The new-record path is internally green. Run 0009's read-only
production audit found no legacy account, OAuth identity, session or PAT
records, so no migration writes were required.
