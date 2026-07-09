# Maturity

**Level: R2 live adapter**

Implemented:
- Authorization request and decision models.
- Host port for policy/capability decision delegation.
- Request validation for principal, action, resource, and request ID.
- Decision validation for known outcomes, request matching, and deny reason.
- Datom emitters for request and decision records.
- Policy engine adapter boundary.
- In-process rules policy engine implementation.
- Policy bundle identity and version propagation.
- Policy-effect trace and obligation normalization.
- External `policy` engine adapter with `cacao` capability verification.
- Replay-resistant decision ledger adapter boundary.
- Durable EDN authorization decision ledger with duplicate request-id rejection.
- Contract tests for delegation, invalid request/decision rejection, policy payload mapping, allow/deny rules, default deny, bundle provenance, effect traces, obligations, and decision ledger replay rejection.

Not yet R2:
- None.
