# ADR-2607142100: kotoba-lang/banking gains a real Open Banking (PSD2/XS2A) API layer

**Status**: accepted — landed (2026-07-14)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Related**: `kotoba-lang/banking` `docs/adr/0001-open-banking-api-layer.md`
(child-repo ADR, full research citations and scope note), `kotoba-lang/swift`
(sibling interbank-*messaging* library, NOT duplicated by this change),
`kotoba-lang/kessai` (payment-gateway abstraction this namespace's
construct/`ex-info` validation style follows), `cloud-itonami-isic-6493`
(the factoring actor this capability library is being built ahead of)

## Context

`cloud-itonami-isic-6493` (factoring) and its Cloudflare Worker deployment
need REAL, standards-accurate banking capability libraries to eventually
wire into — no live bank connection is being attached (that requires a
licensed financial institution's real API credentials, explicitly out of
scope for this codebase to fabricate), but the request/response SHAPES
must be genuinely spec-conformant so a licensed operator could later plug
this into a real bank's actual API with minimal translation work.

`kotoba-lang/banking` (`src/kotoba/banking.cljc`) already modeled internal
bookkeeping — IBAN (ISO 13616) validation, account records, a double-entry
ledger, a clearing-batch contract — but had no external-facing REST/JSON
API layer. A sibling agent was separately upgrading `kotoba-lang/swift`
(SWIFT MT / ISO 20022 interbank *messaging*) in parallel; this work is
deliberately distinct — Open Banking payment-initiation/account-information
APIs are a bank-to-TPP surface, not interbank settlement messaging.

## Decision

Added `kotoba.banking.api` (`src/kotoba/banking/api.cljc`) to
`kotoba-lang/banking`, targeting the **Berlin Group NextGenPSD2 XS2A
Framework**, Implementation Guidelines + OpenAPI definition **version
1.3.11 (2021-09-24)** as the primary, most-faithfully-implemented target —
chosen because it fits this fleet's existing multi-jurisdiction
facts-catalog convention (`cloud-itonami-isic-6419`'s `banking.facts`
already cites BaFin for the DEU jurisdiction; Berlin Group XS2A is
Germany/EU's Open Banking standard).

Research was done against real, fetched sources, not recalled from
training data: the Berlin Group's own machine-readable OpenAPI 3.0 schema
(14,069 lines, mirrored in
[`adorsys/xs2a`](https://github.com/adorsys/xs2a), the Berlin-Group-
affiliated reference open-source implementation), fetched and read in
full; cross-checked against a live ASPSP's generated API docs (Memo Bank)
for the field list and the `tppMessages` error-code catalog (which the
OpenAPI file itself leaves as a free string — the concrete catalog lives
in the Implementation Guidelines' prose tables). Secondary jurisdictions
(UK Open Banking/OBIE, Japan's 全銀協) were researched and are documented
in the namespace docstring for this fleet's honest multi-jurisdiction-
coverage discipline, but are explicitly NOT implemented.

Covers: single payment-initiation request/response
(`POST /v1/payments/{payment-product}`, four JSON-bodied payment
products), the real 14-value `transactionStatus` enum
(`RCVD`/`PDNG`/`ACTC`/`ACSC`/`RJCT`/...), the `tppMessages`-shaped 4xx
error body, and account information (`GET /v1/accounts`, `GET
/v1/accounts/{account-id}/balances`) — all wired to/from the existing
`kotoba.banking` `account` record and `iban-valid?`, not a duplicate data
model. Does NOT cover consent management, SCA flows, bulk/periodic
payments, cancellation, the transaction list, card accounts, or the
`pain.001`-XML payment products (see the child-repo ADR for the full
scope note). No network, no I/O anywhere — pure request/response shape
construction and parsing.

Landed via the standard west-manifest flow: fresh sibling clone (not the
shared `orgs/kotoba-lang/banking` checkout), feature branch, 90 new test
assertions (53 → 143, all green), zero new `clj-kondo` warnings, pushed
and merged server-side (`gh api .../merges`, no local rebase, no
force-push) to `kotoba-lang/banking` main at `73ac6d2f`. This
superproject's `manifest/west.yml` `banking` entry pin was then advanced
via the documented single-entry GitHub API PUT method (blob-SHA-matched,
minimal one-line diff, verified pure fast-forward — `ahead_by 6,
behind_by 0` — via the compare API before writing), and re-verified with
`nbb scripts/gen-west-manifest.cljs --check` from a topdir-isolated
sibling worktree (`west init -l manifest` + `west update --fetch smart
banking`), which reported `west.yml is up to date.`

## Consequences

- `cloud-itonami-isic-6493` (and any other actor) can now construct/parse
  real-shaped Open Banking JSON payloads purely in-memory, reducing the
  eventual integration cost of a real bank connection without this
  codebase ever fabricating one.
- This is a scoped slice of the XS2A catalogue (payment-initiation +
  account-information only), not the full standard — a future ADR should
  scope consent management/SCA/UK-OBIE/全銀協 work separately if/when an
  actor actually needs it.
