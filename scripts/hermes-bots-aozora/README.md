# `hermes-bots-aozora` — every Hermes bot gets an aozora.app account and posts its own run receipts

Owner request (2026-08-29): the wiki-growing bots should each hold an
aozora.app account on the web3/passkey/smart-contract identity plane and post
their updates autonomously.

What that means on this workspace's actual rails:

- **web3**: implemented here. An aozora account *is* an Ed25519 `did:key`;
  every session is a self-minted CACAO (SIWE/CAIP-122, DAG-CBOR) — no
  password, no server-held key. Same model as the nine `*-organism` fabric
  actors and the cloud-itonami distribution actors (ADR-2607161930).
- **passkey**: on aozora, a passkey is a *human* ceremony (`auth.aozora.app`
  trades a WebAuthn assertion for the same session JWT). A cron bot has no
  finger. The custody upgrade that applies to bots is passkey-PRF *seed
  wrapping* (ADR-2607022330 / `pds/keylink.cljc`) — an owner-side follow-up,
  not faked here.
- **smart contract**: `kotoba-lang/base-l2` (ERC-4337 sponsored writes,
  MST-root anchoring) exists and `cloud-itonami-app`'s `smart_account.cljc`
  is real 4337 code, but **zero of it is wired into aozora auth** (measured
  2026-08-29, grep + live). Anchoring bot MST roots to Base is a separate
  decision; nothing here pretends otherwise.

## Files

| file | role |
|---|---|
| `core.cljc` | names, strings, exit contract — the one place both scripts read |
| `register.cljs` | seed → Keychain → createSession-first bootstrap → profile + first post |
| `pulse.cljs` | one deterministic status post per bot per run (jobs.json fields only) |
| `aozora_pulse.py` | Hermes `--script` shim (bash/python only there; holds no decision) |

Roster = `~/.hermes/cron/jobs.json`, never a hardcoded list — the refresh
job's two-name-horizon bug (hermes-hyakka-bots README) is why. Opt out with
`HERMES_AOZORA_OPT_OUT=name,name`.

Seeds live in the login Keychain as `aozora.app/actor-seed/<name>.aozora.app`
(the fabric-actor namespace). Nothing secret is in this directory.

## Running

```bash
R=$PWD K=$PWD/orgs/kotoba-lang
CP="$R/scripts/hermes-bots-aozora:$K/org-chainagnostic-cacao/src:$K/authority/src:$K/org-ietf-ed25519/src:$K/org-ietf-cbor/src"
nbb --classpath "$CP" scripts/hermes-bots-aozora/register.cljk   # once / idempotent
nbb --classpath "$CP" scripts/hermes-bots-aozora/pulse.cljk      # what the cron job runs
```

Install the daily pulse as a Hermes no-agent job (the script *is* the job —
same reasoning as `hyakka-model-refresh`):

```bash
cp scripts/hermes-bots-aozora/aozora_pulse.py ~/.hermes/scripts/
H=~/.hermes/hermes-agent/venv/bin/hermes
$H cron create "50 9 * * *" --name bots-aozora-pulse \
   --script aozora_pulse.py --no-agent --deliver local
```

## ⚠ Current state: registration and posting are upstream-BLOCKED

Measured 2026-08-29 (full chain in **ADR-2608291500**): the kotobase.net
datomic data plane behind `pds.aozora.app` now requires **Biscuit** tokens
(`KOTOBASE_BISCUIT_AUTH_MODE=required` + `KOTOBASE_READ_OWNER_BINDING=strict`
on `kotobase-cf-wasm-staging`, ADR-2608280230 in net-kotobase/control-plane),
and the aozora PDS still speaks CACAO-only — so `createAccount`,
`createRecord` and even `resolveHandle` all die on `datoms/transact 401`.
`createSession` still works (it never touches datoms). This is not
bot-specific: a fresh random DID gets the same 401.

Both scripts run anyway and print `BLOCKED` receipts with the raw upstream
error — a run that cannot post must say so, not be silently skipped. The
seven seeds are already minted and custodied, so the day the PDS is migrated
(or the engine returns to `prefer`), `register.cljs` completes the accounts
and the pulse starts posting with **no further changes here**.

## What these bots will never do

- post model prose — the pulse text is scheduler fields, verbatim;
- hold a seed anywhere but the Keychain;
- create accounts for names not present in the live Hermes roster.
