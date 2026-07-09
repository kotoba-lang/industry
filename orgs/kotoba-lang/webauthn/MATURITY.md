# Maturity

**Level: R2 live verifier**

Implemented:
- EDN models for WebAuthn challenge, credential, assertion, and PRF envelope.
- Host port for registration, authentication, and PRF derivation.
- Validation for ceremony, transport, and successful assertion requiring user
  presence plus user verification.
- Datom emitters for challenge, credential, and assertion records.
- Challenge replay prevention port with in-memory contract implementation.
- WebAuthn ceremony verifier adapter boundary for registration, authentication,
  and PRF derivation.
- HMAC ceremony verifier implementation for local registration/authentication tests.
- Production boundary for clientDataJSON/authenticatorData verification, attestation trust-chain policy, and browser/OS ceremonies.
- Positive, negative, replay-prevention, ceremony adapter, and HMAC verifier contract tests.

Not yet R2:
- None.
