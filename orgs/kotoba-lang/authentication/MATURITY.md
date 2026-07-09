# Maturity

**Level: R2 live adapter**

Implemented:
- Authentication request, factor result, and decision models.
- Factor type validation.
- Assurance-level aggregation for single-factor, multi-factor, and phishing-resistant flows.
- Datom emitters for factor and decision records.
- Host verifier orchestration for factor requests.
- Factor verifier adapter boundary.
- Static local factor verifier implementation.
- Step-up challenge planner for required assurance level and risk.
- Replay-resistant decision ledger adapter boundary.
- Durable EDN decision ledger with duplicate request-id rejection.
- Production factor adapters for `faceid`, `touchid`, `webauthn`, `onetime`, OAuth, OIDC, and SAML.
- Production adapter for `cacao`.
- Contract tests for multi-factor success, factor-request verification, factor payload mapping, local factor verification, step-up planning, decision ledger replay rejection, and external factor normalization.

Not yet R2:
- None.
