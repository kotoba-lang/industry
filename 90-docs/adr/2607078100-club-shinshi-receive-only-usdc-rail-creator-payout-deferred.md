# ADR-2607078100: club-shinshi V1 monetization — receive-only USDC rail (buyer → gftd), creator payout deferred

**Status**: implemented (landed 2026-07-08 — see Amendment)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

club-shinshi's creator monetization (subscriptions/PPV/tips) has been frozen
since ADR-2606010100/ADR-2605220000 pending an adult-friendly fiat PSP
contract (CCBill/Verotel/Epoch/Segpay). This blocks the riskiest hypothesis
`:hyp/club-shinshi-creator-take` (creator 課金 GMV > ExoClick ad 収益,
ADR-2607021900) from ever being measured — ExoClick ad revenue continues
unaffected as the portfolio's only `:live` revenue, but that's a different,
one-directional money flow (viewer → gftd via an ad network) from creator
payout (gftd receiving from many buyers, then paying out to many different
creators).

This session's owner direction, in order:
1. Crypto payment is acceptable in place of chasing the fiat PSP.
2. Because ADR-2607052100 (investigation-only) found reusing local-murakumo's
   treasury Safe unsafe (commingling risk) and flagged the payout shape
   (buyers → gftd → creators) as reading like money transmission, the owner
   chose the **etzhayyim-resident self-custody pattern** (ADR-2606011400) over
   gftd operating its own payout treasury — gftd never becomes
   merchant-of-record/custodian for creator payouts.
3. A code-level check of the existing etzhayyim ERC-4337 actor infra
   (`orgs/etzhayyim/root/50-infra/vultr/geth-private/contracts/src/
   EtzhayyimActorAccount.sol` / `EtzhayyimActorRegistry.sol`,
   `50-infra/etzhayyim-paymaster/src/EtzhayyimPaymaster.sol`) found it is
   **not yet safe to build on**:
   - `EtzhayyimActorRegistry.activate()` has no access control — a DID hash is
     public, so an attacker can front-run a user's first activation and
     permanently hijack that DID's smart-account slot (no reset path).
   - `EtzhayyimPaymaster`'s allowlist only checks the target contract address,
     not the function selector or recipient, contradicting its own README's
     claimed "USDC transfer to a whitelisted recipient" policy.
   - The self-custody wallet pair is deployed only to etzhayyim's own
     single-sealer private PoA chain (chainId 260425), not the public Base L2
     that the payments policy (ADR-2605172100) and paymaster actually target.
   - The paymaster itself is "stub-quality v0.0.0, not yet deployed or
     independently audited" (its own README).
4. Given those findings, the owner narrowed scope further: **defer creator
   payout entirely** (and the etzhayyim actor-infra fixes it would need) and
   build only the simpler, lower-risk half — **money flowing from buyers to
   the operator (gftd)**, with creator payout as an explicit future ADR/task.

Buyer → gftd (one-directional, gftd as the receiving merchant) is a
structurally simpler, lower-risk shape than buyer → gftd → creator (which
reads as money transmission per ADR-2607052100's finding #3). It is also
**already proven in production**: `local-murakumo` operates exactly this
shape (USDC → Gnosis Safe on Base L2, Etherscan-confirmed, real revenue) for
GPU-compute-credit purchases. `kotoba-lang/treasury` (`treasury.core`,
ADR-2607051621) already extracts the domain-agnostic parts of that rail
(quote, pending/confirmed ledger entries, `verify-payment`) out of
murakumo's `itonami.cljc`, injecting the treasury address and fee split —
exactly the shape club-shinshi needs, with its **own, separate** treasury
address (ADR-2607052100's commingling finding — one freezable address shared
across two unrelated business lines — applies to a receive-only rail just as
much as to a payout rail).

## Decision

Build club-shinshi's V1 crypto rail as **receive-only USDC, reusing
`kotoba-lang/treasury` directly** rather than duplicating its logic (unlike
`local-murakumo`'s own `tools/verify-payments.clj`, which pre-dates the
extraction and still duplicates the decision logic locally — club-shinshi
should not repeat that debt now that the library exists and is portable).

1. **`60-apps/.../d1/migrations/0008_crypto_pay.sql`** — `pay_run` ledger
   table (`pending` → `confirmed`/`rejected`), scoped to the buyer-pays-gftd
   leg only; no payout/withdrawal columns.
2. **`shinshi.worker.payments`** (new ns) — buyer-facing `GET /pay/quote`,
   `POST /pay/claim`, `GET /pay/status`, direct D1 access (no internal
   secret), wired into the route table in `shinshi.worker.core`.
3. **`shinshi.worker.d1-gateway`** — two operator-only ops added:
   `list_pending_pay_runs` (read) and `confirm_pay_run` (write), gated by the
   existing `DISPATCHER_INTERNAL_SECRET`/`x-internal-trust` mechanism. Confirm
   calls `treasury.core/verify-payment` (the same pure decision local-murakumo
   uses: wrong recipient / underpaid / too few confirmations all reject) and,
   on success, upserts `creator_billing_daily` (0007) so the riskiest-
   hypothesis gate becomes measurable. That row is a **bookkeeping claim** of
   what the creator would be owed (`creator_share_minor`) — not an actual
   payout; no funds move to creators in this scope.
4. **`shadow-cljs.edn`** gains a `:source-paths` entry pointing at
   `kotoba-lang/treasury/src` (sibling repo, west `orgs/<org>/<repo>` layout).
   Only the platform-neutral fns (`crypto-quote`, `pending-entry`,
   `verify-payment`) are called from the cljs Worker;
   `etherscan-row->onchain` uses `Math/pow` (JVM-only) and is called only from
   the babashka verifier.
5. **`nbb.edn` + `tools/verify-payments.clj`** (new, club-shinshi's own,
   modeled on `local-murakumo/tools/verify-payments.clj` but requiring
   `treasury.core` directly instead of duplicating it): polls
   `list_pending_pay_runs`, fetches incoming USDC transfers to club-shinshi's
   treasury address from Etherscan/Basescan/Arbiscan (`PAY_CHAIN`), converts
   rows via `treasury.core/etherscan-row->onchain`, and POSTs each to
   `confirm_pay_run`. The verifier never holds keys or moves funds — it only
   reads the chain and reports what the Worker already needs to verify.
6. Env config (all unset by default, never fabricated): `SHINSHI_TREASURY_ADDR`,
   `SHINSHI_CREATOR_FEE_FRAC` (default 0.20, the BMC's 80/20 take rate),
   `SHINSHI_PAY_MIN_CONFIRMATIONS`, `SHINSHI_WORKER_URL`,
   `ETHERSCAN_API_KEY`, `PAY_CHAIN`.

Build verified in an isolated west worktree (`club-shinshi` +
`kotoba-lang/treasury` checked out as siblings so the relative source-path
resolves): `npx shadow-cljs compile worker` succeeds (only pre-existing
`:infer-warning`s, same class as before this change), `npm test` — 24 tests /
1078 assertions, 0 failures — and `nbb tools/verify-payments.clj --dry-run`
safely no-ops with secrets unset.

## Explicitly out of scope (deferred)

- **Creator payout itself.** No funds reach creators in this scope; the BMC's
  "80/20, lower fees than OnlyFans" UVP is not literally true until payout
  ships. `creator_share_minor` in `creator_billing_daily` is a claim, not a
  transfer.
- **The etzhayyim self-custody actor infra fixes** the payout path would
  need: `EtzhayyimActorRegistry.activate()` access control,
  `EtzhayyimPaymaster` allowlist selector/recipient enforcement, and the
  private-PoA-chain vs public-Base-L2 deployment decision. These live in
  `orgs/etzhayyim/root`, a different repo/org, and are a separate future
  ADR/task — not touched here.
- **Provisioning a real treasury address.** `SHINSHI_TREASURY_ADDR` stays
  unset until the operator creates and configures one (Safe or otherwise) out
  of band; this ADR does not choose or generate a wallet.
- **The adult-friendly fiat PSP search** (CCBill/Verotel/Epoch/Segpay,
  ADR-2606010100) is not cancelled by this — it's simply not the path taken
  for V1.
- **Legal/compliance certification.** ADR-2607052100 already noted real
  counsel review (資金決済法/AML, 特定商取引法) is needed before real money
  moves; nothing in this pass changes that.

## Consequences

- (+) The riskiest hypothesis becomes measurable without waiting on a PSP
  vendor contract or fixing the (currently vulnerable) payout infra.
- (+) Reuses `kotoba-lang/treasury`'s already-tested decision logic
  (`verify-payment`) rather than re-deriving it — and, unlike
  `local-murakumo`'s own script, club-shinshi's verifier requires the library
  directly instead of duplicating it, closing a small piece of existing
  tech debt rather than repeating it.
- (+) Keeps club-shinshi's treasury address separate from local-murakumo's,
  per ADR-2607052100's commingling finding.
- (−) Creator payout remains frozen; this only unblocks the *measurement* of
  the gate, not the underlying business model claim.
- (−) V1 is crypto-native-only (buyers who already hold a wallet) — no
  wallet-onboarding UX; this was a deliberate scope cut to avoid blocking on
  that larger surface.
- (−) Nothing here executes until the operator provisions a real treasury
  address and configures the env vars — this ADR ships code, not revenue.

## Amendment (2026-07-08) — landing evidence + a build-portability fix

Landed as `jk-luxury/club-shinshi#6`, merge commit `d7f83c2a2e9d8e73afbf53531ba43693c594b9c3`.

CI (`repo-checks.yml` / `appview-cljs`) initially **failed** on the first push:
`the required namespace "treasury.core" is not available`. The original
Decision (#4 above) had `shadow-cljs.edn` reference `kotoba-lang/treasury/src`
via a relative sibling-repo source-path — that only resolves inside a full
west multi-repo checkout. club-shinshi's CI, and its production deploy
pipeline, both check out this repo **alone**, so the sibling path was never
on the classpath there; it only happened to work in the isolated west
worktree used to verify the original implementation, which is not
representative of how this repo actually builds elsewhere.

Fix (same PR, second commit): **vendored** `treasury.core` at
`60-apps/.../cljs/src/treasury/core.cljc` — byte-identical to
`kotoba-lang/treasury` pinned at commit `e87e1299fe8562c98ef4b6044a1ad025dd493258`,
with a header comment recording provenance and the reason it's a vendored
copy rather than a live cross-repo reference. `nbb.edn` was repointed at the
same vendored copy. Re-verified in a clean standalone clone of just
`club-shinshi` (matching CI's own checkout shape): `shadow-cljs release app
worker` succeeds, `npm test` — 24/24, `nbb tools/verify-payments.clj
--dry-run` no-ops safely — then confirmed green on CI itself before merging.

This narrows one claim in the original Decision: club-shinshi's reuse of
`treasury.core` is no longer a *live* dependency on `kotoba-lang/treasury`
(so it can drift — future upstream fixes to that library won't propagate
here automatically). Follow-up, not done here: once this repo has real
`deps.edn`/git-dependency resolution wired up, replace the vendored copy
with a pinned git dependency.

## Amendment (2026-07-08) — Phase 2: treasury address configured

Landed as `jk-luxury/club-shinshi#7`, merge commit
`57f117c6efa81693851952a526db3341aedd4be1`: `SHINSHI_TREASURY_ADDR` is now set
in `wrangler.jsonc` `vars` to the operator-provisioned address
`0xf1592811063554e2EDfC137964aE181C830967Ac` — club-shinshi's own, separate
from local-murakumo's treasury. `DISPATCHER_INTERNAL_SECRET` was already live
as a Worker secret (confirmed via `wrangler secret list`) — no action needed
there. `ETHERSCAN_API_KEY` for the babashka verifier now resolves via a new
`shinshi`-scoped Keychain entry (copied from the existing `murakumo`-scoped
key — a read-only block-explorer API key, not a custody credential, so
reuse across the account's own products carries none of the treasury-address
commingling risk ADR-2607052100 flagged). Verified with `wrangler deploy
--dry-run` before merging — config parses, binds correctly, nothing
deployed.

**Not done in this pass**: an actual `wrangler deploy`/`gftd deploy` to
production. This repo has no CI auto-deploy on merge to main (confirmed —
`.github/workflows/` only has `actors-test.yml`/`repo-checks.yml`), so the
address is not yet live/reachable anywhere. Deploying is the point at which
this rail could actually start receiving real funds, so it's held as a
separate, explicit go-live decision rather than folded into this merge.

## Amendment (2026-07-09) — go-live: deployed, migrations applied, smoke-tested

Deployed from a clean clone of `main` (`57f117c` — both #6 and #7):
`wrangler deploy` — Version ID `802adc09-8deb-4fef-b3bf-06709162135d`, live at
`shinshi.club` (custom domain), account `ai-gftd-cloud`.

Discovered and fixed a real gap before declaring this live: **migrations
0007 (`creator_billing_daily`) and 0008 (`pay_run`) had never been applied to
the production D1 database** (`ai-gftd-shinshi`) — this repo has no
`migrations_dir` wired into `wrangler.jsonc`, so `d1/migrations/*.sql` files
are not auto-applied on deploy; confirmed by querying
`sqlite_master` before touching anything (neither table existed, alongside
several other older migrations also never applied — a pre-existing gap
unrelated to this ADR, not fully audited/fixed here). Without this, the
first real `confirm_pay_run` call would have thrown trying to write to a
nonexistent `creator_billing_daily`. Applied both via
`wrangler d1 execute ai-gftd-shinshi --remote --file=...`, in order (0007
before 0008, since confirm depends on 0007's table).

Smoke-tested live against `https://shinshi.club` after deploy:
- `GET /pay/quote?usd=10&kind=sub` → correct quote (`treasury` = the real
  address, `net`/`fee` = 8/2 on the 80/20 split, correct mainnet USDC
  contract) — the full quote → claim → confirm pipeline is reachable
  end-to-end for the first time.
- `GET /pay/status` (no `payer`) → 422; `?payer=<x>` → `{"runs":[]}` (honest
  empty, no fabricated data).
- `POST /pay/claim` with an invalid `kind` → 422.
- `GET /` (homepage) → 200 — no regression from the deploy.

No real USDC transaction has occurred and none was needed for this
verification — every check above exercises validation/quoting logic only.
`tools/verify-payments.clj` has not been run against production (that's the
operator's own recurring task once real traffic exists, not part of this
pass). club-shinshi's crypto rail is now genuinely live for Phase 4
(measuring `:hyp/club-shinshi-creator-take`) once Phase 3 (UI wiring) ships.

## References

- ADR-2607052100 (crypto payout rail regulatory review — commingling finding,
  fiat-first sequencing, etzhayyim-resident pattern recommendation)
- ADR-2607051621 (`kotoba-lang/treasury` extraction from
  `local-murakumo/itonami.cljc`)
- ADR-2605172100 (`etzhayyim-payments-on-chain-only` — Base L2 + USDC +
  Coinbase Smart Wallet + Paymaster policy scope)
- ADR-2606011400 (Consensys-pattern etzhayyim-resident on-chain settlement,
  gftd-never-MoR rule)
- ADR-2606010100 / ADR-2605220000 (shinshi ad-monetization BMC, fiat PSP
  freeze)
- ADR-2607021900 (portfolio BMC — `:hyp/club-shinshi-creator-take` riskiest
  hypothesis)
- `orgs/gftdcojp/local-murakumo/tools/verify-payments.clj` (the pattern this
  mirrors, and the tech debt — undupe via `treasury.core` — this avoids)
- `orgs/etzhayyim/root/50-infra/etzhayyim-paymaster/README.md`,
  `.../vultr/geth-private/contracts/src/EtzhayyimActorRegistry.sol` (the
  vulnerabilities that put creator payout out of scope here)
