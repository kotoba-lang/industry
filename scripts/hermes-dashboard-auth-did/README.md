# `dashboard_auth/did` — a bearer token is a biscuit delegation

The versioned copy of a Hermes dashboard-auth provider plugin. Hermes lives in
someone else's repository (`NousResearch/hermes-agent`), so nothing here is
committed there; installing means copying this directory into that checkout's
`plugins/dashboard_auth/did/`.

```bash
cp -r scripts/hermes-dashboard-auth-did ~/.hermes/hermes-agent/plugins/dashboard_auth/did
```

## What it changes

Hermes decides who is calling from a token minted for the life of a process,
behind file ownership — `~/.hermes` 0700, `.env` 0600. That is a real boundary
and a local one: it establishes that the caller can read a file on this
machine, and it cannot say who they are anywhere else.

With this provider the bearer token is a biscuit whose authority block was
signed by a root key and whose later blocks may only *narrow* what the first
granted. The principal is the `did:key` the delegation names, and verifying it
needs the root **public** key alone — so the same token is checkable by this
dashboard, a remote, a Worker and another actor, none of whom hold a secret.

## Why there is no cryptography in the Python

There is one authorization decider in this workspace and it is not here
(ADR-2608197300 §3). `verify_token` shells out to `scripts/identity-verify.cljs`,
which runs `biscuit.token/verify` → `biscuit.kotoba/->delegated` →
`authority.chain/authorize` → `identity.startup/resolve-state`. A Python
reimplementation would be a second decider: two answers that agree until the
day they do not, with no test that would notice. The language is dictated by
the plugin host, and the file holds no decision, so being in another language
costs nothing.

## Configuration

| variable | |
|---|---|
| `HERMES_DID_ROOT_PUBLIC_KEY` | 64 hex chars — the Ed25519 root public key |
| `HERMES_DID_WORKSPACE` | the com-junkawasaki checkout holding the verifier |
| `HERMES_DID_NBB` | optional path to `nbb` |
| `HERMES_DID_KINDS` | optional EDN set narrowing the admitted grant kinds |

Both of the first two must be set or the plugin registers nothing, which
leaves the dashboard exactly as it was.

## Verifying an install

```bash
HERMES=~/.hermes/hermes-agent WORKSPACE=~/github/com-junkawasaki \
  ~/.hermes/hermes-agent/venv/bin/python \
  scripts/hermes-dashboard-auth-did/probe.py
```

Six cases, in pairs. `B` `C` `E` are bad tokens and must return `None` (401);
`D` `F` are a broken check and must raise `ProviderError` (503). Keeping those
apart is the whole point: `nbb` exits 1 when it cannot load a namespace, the
same 1 the verifier uses for `denied`, so a provider reading the exit code
alone would call every token invalid the moment its classpath broke.

## Not done

Token-only. `supports_session = False`, so Hermes never offers it on the login
page. Turning a delegation into an interactive session is a second question —
which factor proved a person is present — and the ADR routes that through
`kotoba-lang/authentication`.
