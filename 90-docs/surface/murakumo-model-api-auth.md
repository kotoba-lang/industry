# Murakumo model API authentication

As of 2026-08-29, `api.murakumo.cloud` accepts both bounded anonymous chat and
passkey-gated inference. Smart Account ownership is still a separate,
unverified on-chain link; it is not required per inference and must not be
inferred from a valid passkey session or Biscuit.

## Client settings

```text
Base URL: https://api.murakumo.cloud/v1
Model: murakumo-main
Anonymous API key: optional (SDK placeholder is accepted but proves nothing)
Authenticated API key: mrb_<short-lived Biscuit>, issued for 15 minutes
Timeout: 120 seconds or longer
```

Anonymous `POST /v1/chat/completions` remains capped at 2048 output tokens.
A high-assurance passkey session scoped to `murakumo.cloud` may call the same
route directly and is capped at 8192. For CLI/OpenAI SDK use, the signed-in
browser exchanges that session at:

```http
POST https://auth.murakumo.cloud/v1/murakumo/token
Origin: https://auth.murakumo.cloud
Content-Type: application/json

{"model":"murakumo-main","maxOutputTokens":8192}
```

The response's `token` is supplied as `Authorization: Bearer mrb_…`. The
credential is a Biscuit v3 grant bound to `https://api.murakumo.cloud`, the
exact model, `inference:chat`, an output-token ceiling, holder/account DIDs,
and a 15-minute expiry. Issuance is capped at 32768 output tokens.

## What the passkey path proves

`auth.murakumo.cloud` is its own WebAuthn RP. It verifies the single-use
challenge, RP ID, origin, P-256 signature, user verification, and credential
counter, then stores a `Domain=murakumo.cloud` session. The inference Worker
forwards only the cookie or `mrb_` bearer to `kotobase-authn` through a
Cloudflare service binding. The Biscuit root seed and passkey custody key never
enter `local-murakumo`; it receives only a bounded admission result.

This implementation is passkey-gated, not non-custodial Smart Account
authentication. A passkey cannot directly sign the Ed25519 Biscuit root or a
CACAO. The service mints the short-lived capability after the ceremony using
server-held keys. A linked ERC-4337 account becomes verified only after an
existing owner signs the owner-update UserOperation and target-chain receipts
are recorded. That on-chain step is not currently complete.

## Route contract

| Route | Authentication | Authority |
|---|---|---|
| `GET /v1/models` | anonymous | catalog read |
| `POST /v1/chat/completions` | anonymous, passkey cookie, or `mrb_` bearer | 2048 anonymous; 8192 cookie; token-specific ceiling up to 32768 |
| `POST /v1/messages` | conditional shared token | Anthropic-compatible inference |
| privileged `/infer/*` writes | verified CACAO or service bearer, by route | actor capability or operator authority |
| `POST /infer/transfer` | exact single-use CACAO only | recipient and credit amount |

## Production evidence

- Authn Worker version: `fd7bc41f-150a-4ffb-bca0-c5a63173b6ba`.
- Inference Worker version: `5e4d9ac6-52ce-4a67-8baf-297260b088b1`.
- `GET https://auth.murakumo.cloud/health` returned the
  `murakumo.cloud` apex and `auth.murakumo.cloud` RP.
- Token issuance without a passkey session returned 401
  `passkey_session_required`.
- A forged `mrb_` bearer returned 401 at the public inference route.
- Anonymous real inference returned 200 from
  `qwen3.8-27b-throughput-b70` with one generated choice.
- The positive passkey ceremony requires a real user/device assertion; it is
  implemented and cryptographically tested, but was not replayed by an agent
  as a production user in this deployment run.

Sources of truth: the sibling ADR, machine contract, claim rule,
`net-kotobase/control-plane/authn`, and `network-awai/local-murakumo`.
