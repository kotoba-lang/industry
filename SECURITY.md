# Security Policy

## System and Scope

This policy applies to every first-party project registered by this
superproject that can authenticate a human, bootstrap a human identity,
register or replace a human credential, recover an account, or issue a human
session.

Nested SECURITY.md files may strengthen or specialize this boundary. Treat any
nested policy or implementation that weakens it as a reportable policy
conflict.

Protocol libraries, external specification mirrors, test fixtures, and
notification/connectivity implementations are not human authentication
authorities merely because they implement Email, SMS, OAuth, OIDC, SAML, OTP,
password, or related protocols.

## Threat Model and Trust Boundaries

Assume an attacker may control browser input, network requests, an Email or
telephone account, an upstream identity-provider account, dormant provider
configuration, or a legacy credential record. An operator, support account, or
tenant administrator may also be compromised or socially engineered.

No one of those conditions may be sufficient to create or upgrade a human
session, register a credential, recover an identity, or bypass a recovery
delay.

## Human Authentication Invariants

- Web3 first is the current policy (ADR-2609070400): verified SIWE wallet
  authentication is a first-class, preferred login path. Approved methods are
  SIWE with ERC-191 EOA signatures, SIWE with ERC-1271 contract signatures,
  and WebAuthn Passkeys. A product may retain a Passkey-only surface; record
  its actual methods in the inventory. Policy adoption is not implementation proof.
- Wallet connection, an address, a DID, or a client hint is not authentication.
  Verify the server-issued single-use nonce, exact domain/origin/URI, admitted
  chain, expiry, and signature on the server; consume the nonce atomically.
  ERC-1271 verification must bind the RPC to the declared chain and recheck
  current contract authorization when accepting a contract-wallet session.
  Verification errors must fail closed. Sessions must be bounded and revocable.
- WebAuthn requires exact RP ID and Origin binding, a server-issued single-use
  challenge, replay protection, and user verification. Wallet signatures do not
  inherit WebAuthn's phishing resistance merely by including a domain string.
- Authentication does not authorize transfers, delegated signing, or governance.
  Preserve operation-specific authority checks. Wallet and Passkey principals
  must not be silently merged. Adding a credential to an existing identity
  requires that identity's verified owner authority; a new wallet login cannot
  recover another identity.
- Email links, Email OTPs, Email addresses, passwords, SMS/voice OTPs,
  telephone ownership, OIDC/OAuth/SAML/social/enterprise SSO, security
  questions, device fingerprints, support judgments, operator resets, and
  administrator overrides are permanently prohibited as human authentication,
  bootstrap, step-up, credential-registration, or recovery authority.
- If the approved authenticator is unavailable, fail closed. Browser
  capability, locale, tenant configuration, installed secrets, incident flags,
  or operational convenience must not enable a weaker fallback.
- Email, SMS, and federation may be used only after strong authentication for
  notification, contact, data connectivity, or account association. They must
  not mint or upgrade a human session.
- Agent and service authentication is a separate boundary. It must use
  short-lived, audience- and resource-scoped cryptographic authority such as
  CACAO, Biscuit, mTLS, or signatures, and must never double as human recovery.
  An agent may register its own account with a signature by a key it holds
  (auth.kotoba.cloud `POST /v1/agents/register`, ADR-2609071200): the account
  is created as kind `agent`, the request's cookie is ignored so it can never
  link into or step up a human account, and the durable API credential it
  then obtains is a scoped Biscuit, not the session.

## Recovery Invariants

- Recovery must replace a credential; it must not directly create a session.
- Recovery requires an independently generated, high-entropy, one-time offline
  secret, a durable server-enforced delay, and registration of a fresh approved
  authenticator. The delay must be at least 48 hours. External wallet recovery
  is outside this service and cannot bypass its identity replacement controls.
- Store only a verifier or digest of the recovery secret. Plaintext is shown
  once.
- Recovery possession must not reveal whether an account exists.
- An existing strongly authenticated owner may cancel a pending request during
  the delay.
- Completion must revoke superseded credentials, sessions, continuations, and
  remaining recovery secrets. Partial failure must leave former credentials
  unusable.
- Support, operators, Email, SMS, SSO, or direct database edits must not shorten
  or bypass the delay. Operations may freeze an account but cannot grant
  identity.

## Legacy and No-Downgrade Requirements

- Closed legacy routes must return 404 or 410 without starting a ceremony,
  redirecting to one, or issuing a session, token, or credential.
- Legacy records and provider secrets are inert migration data, not latent
  configuration.
- Runtime flags and tenant settings must not re-enable prohibited routes.
- Source, built artifact, and live-route negative tests are required. Tests
  must include plausible legacy secrets so dormant configuration cannot
  resurrect a route.
- A future federation product requires a separate hostname, RP/trust boundary,
  session namespace, threat model, and ADR. It must not be introduced as a
  fallback for an existing authority.

## Reportable Findings and Severity

- Critical: a reachable prohibited path can issue a human session, register or
  replace a credential, recover an identity, or bypass the recovery delay.
- High: dormant configuration, a feature flag, redirect, legacy record, or
  operator action can reactivate such a path.
- High: recovery replay, account enumeration, client-side delay enforcement,
  or partial failure can preserve an old usable credential.
- Medium: UI or documentation advertises a prohibited method even though the
  server currently refuses it.
- Policy conflicts and missing release gates are reportable; absence of a
  known exploit is not evidence that a dormant trust root is safe.

## Exclusions and Accepted Availability Risk

Implementing or testing a protocol in a library or external specification
mirror is not prohibited unless that project exposes it as first-party human
authentication authority.

If every approved authenticator and every offline recovery secret is lost, the
identity may be unrecoverable. This availability loss is accepted. A manual
reset would become the weakest route and lower the security of every account.

## Known Migration Gaps

ADR-2608302125 previously required Passkey-only authentication. Its exclusivity
is superseded by ADR-2609070400; its prohibition of Email/password/SMS/SSO
and operator recovery remains in force.

The inventory retains earlier evidence for the Passkey surface at
`auth.itonami.cloud`; this policy change does not reverify its live behavior.
Cloud Itonami PR #610 merged wallet and Passkey browser sessions, but full build,
migration, and live verification remain pending. Legacy closure gaps are not
resolved by allowing wallet authentication. Do not claim workspace conformance
while a declared surface is a migration gap or unverified.

Other first-party human-authentication surfaces remain subject to inventory and
verification. Unverified means unverified, not conformant.
