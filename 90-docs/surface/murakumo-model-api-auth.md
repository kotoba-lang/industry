# Murakumo model API authentication

As of 2026-08-29, the model API is OpenAI-compatible but is not uniformly
passkey- or smart-contract-authenticated.

## Current route contract

| Route | Current authentication | What it proves |
|---|---|---|
| `GET /v1/models` | anonymous | catalog read only |
| `POST /v1/chat/completions` | anonymous, `max_tokens <= 2048` | bounded public inference; no caller identity |
| `POST /v1/messages` | `x-api-key` or Bearer when the proxy secret is configured; open when unset | possession of a shared proxy token |
| privileged `/infer/*` writes | verified CACAO or service Bearer, depending on route | actor capability or operator service authority |
| `POST /infer/transfer` | exact, single-use CACAO only | the issuer authorized this recipient and credit amount |

The recommended client configuration remains:

```text
Base URL: https://api.murakumo.cloud/v1
Model: murakumo-main
API key: optional for the current public chat route
Timeout: 120 seconds or longer
```

An OpenAI SDK that requires a non-empty key may use a local placeholder such
as `unused`; the Worker does not interpret it as an identity. Do not store a
real credential merely to satisfy the SDK.

## Passkey, smart account, and capability are different layers

Kotoba's identity model can represent a WebAuthn P-256 passkey as a controller
of a stable principal. It can also link that controller to an ERC-4337 smart
account whose signatures are checked with ERC-1271 and, when needed, ERC-6492.
Neither link grants API authority by itself.

Murakumo's current privileged request wire is CACAO: a short-lived,
scope-carrying CAIP-122/SIWE envelope signed by an Ed25519 `did:key`. Verifying
that envelope proves control of that DID and its included capability. It does
not prove that a WebAuthn ceremony occurred. A separate passkey service may
gate and mint the CACAO, but that custody and ceremony must be stated and
verified separately.

No current model request calls an ERC-4337 account, ERC-1271/6492 verifier,
UserOperation, paymaster, or on-chain transaction. Therefore the accurate
current statement is:

> Kotoba defines a passkey-controller and smart-account identity model;
> Murakumo currently exposes bounded anonymous chat and CACAO/service-token
> authorization. Passkey-to-smart-account admission is specified but not yet
> integrated into the model API.

## Target higher-assurance flow

```text
WebAuthn assertion
  -> verified Kotoba principal controller
  -> optional linked smart-account evidence
  -> short-lived audience/action/budget-scoped capability
  -> Murakumo admission
```

The target does not require an on-chain transaction for every inference.
Passkeys authenticate controllers; smart accounts provide a linked execution
or recovery surface; CACAO or Biscuit carries bounded authority to the API.

The target may be called production-ready only after WebAuthn ceremony checks,
principal evidence, smart-account verification, capability scope/replay
checks, and live positive and negative API tests all exist.

## Sources of truth

- ADR: `90-docs/adr/2608291057-murakumo-model-api-authentication-boundary.edn`
- machine contract: `90-docs/surface/murakumo-model-api-auth.edn`
- claim rule: `90-docs/surface/murakumo-model-api-auth-rule.edn`
- deployed implementation: `network-awai/local-murakumo`
