# ADR-2607130700: `kotoba-lang/mail-archive` — content-addressed Gmail archive, Datomic⊣DataScript query parity

- Status: Accepted
- Date: 2026-07-13
- Deciders: Jun Kawasaki
- Scope: new repo `kotoba-lang/mail-archive`; small addition to `kotoba-lang/com-gmail`
- Builds on: ADR-2607061423 (`com-gmail` architecture), ADR-2607061503 (IMAP/SMTP/Gmail channel split), ADR-2606301200 (`kotoba-lang/mail`/`mailer`), ADR-2607122000 (`cloud-murakumo-market-intel` — the `Store` protocol + Datomic⊣DataScript parity pattern this repo generalizes to a second domain)

## Context

Earlier the same day, a personal-data pipeline (`orgs/personal/bin/*.py`, `orgs/personal/bin/datomic/`) bulk-archived a Gmail mailbox into content-addressed `.eml` files, a JVM `com.datomic/local` index, and a separate hand-written nbb+npm-datascript loader script for browser/CLI queries. It works, but it's ad hoc: Python ingest, a schema duplicated by hand between the Datomic (vector-of-`:db/ident`-maps) and DataScript (JS-object) loaders, and no proof the two ever agree.

An audit of `kotoba-lang`'s existing mail-related repos found this capability doesn't exist there:
- `com-gmail` (Gmail API client) is a thin thread/label/draft boundary — bulk read/archive is a stated non-goal.
- `tayori` (correspondence actor)'s `email/list-new-messages` returns `[]` unconditionally, with a code comment explaining why: Gmail's thread/message list endpoints have no "since" filter, and a real implementation needs `users.history.list` against a persisted `historyId` cursor — a documented, unresolved gap.
- `mail`/`mailer` model the send workflow (`:mail/*` — draft → approval → send effect → receipt) with no thread concept and no declarative schema.
- `manifest/schema.edn` has zero `:email/*` or `:mail/*` attributes.

`cloud-murakumo-market-intel` (same day, different domain) already generalized `gftd-talent-actor/talent.store`'s pattern: one schema, one `Store` protocol, two backends — `LangchainDbStore` (`langchain.db`, Datomic-API-compatible, swappable to real Datomic Local) and `DataScriptStore` (real DataScript, browser/nbb-native) — proven equal by a shared contract test. This repo applies that exact shape to mail.

## Decision

- New repo **`kotoba-lang/mail-archive`** (public, per `repos.edn :orgs` kotoba-lang default). Portable `.cljc` where possible; the ingest path is JVM-only because `com-gmail` itself is JVM-only today (every fn is `#?(:clj ...)`, despite the `.cljc` extension).
- **Small addition to `com-gmail`**: `gmail.history/list-history`, wrapping `users.history.list`. Closes the exact gap `tayori`'s own comment flagged (wiring it into `tayori.channel.email/list-new-messages` is a separate follow-up, not done here). Documented limitation: `gmail.client/request!`'s query-string builder supports one value per key with no URL-encoding, so `historyTypes` (a repeated Gmail param) isn't exposed — filter client-side instead.
- **One shared schema** (`src/mail_archive/schema.edn`, a DataScript-style attribute map — `langchain.db` and DataScript both consume this exact shape, unlike Datomic's native vector-of-`:db/ident`-maps format used elsewhere in this superproject): `:blob/*` (content-addressed by sha256), `:person/*` (id/emails/canonical-alias), `:email/*` (cid/message-id/thread-id/date/subject/labels/from/to/cc/blob/attachments as refs), `:attachment/*`.
- **`Store` protocol, two backends, one contract test**: `LangchainDbStore` (`langchain.db/create-conn`, pure `.cljc`, zero deps, supports nested-map ref auto-expansion natively) and `DataScriptStore` (nbb + real DataScript via npm, `manifest/edn-query.cljs`'s established pattern in this superproject) — the latter does its own two-pass tempid/ref-flattening to accept the *same* nested-map tx-data shape the former accepts natively, since DataScript-via-nbb has no nested-map-ref sugar. `test/mail_archive/store_contract_test.cljc` (JVM) and `test/mail_archive/datascript_contract_test.cljs` (nbb) run the same sample data through the same Datalog queries and assert identical results — the actual parity proof.
- **`:email/date` stored as a `YYYYMMDD` integer**, not an ISO string — numeric comparison is the one range-query semantics `langchain.db` and DataScript both support identically; string comparison isn't portable between them.
- **Blob storage is injectable** (`BlobStore` protocol, default `LocalDirBlobStore`) rather than hardcoded to git-annex — this library isn't coupled to `orgs/personal`'s specific storage backend.
- **Auth**: `org-ietf-oauth2` (zero-dep request/response shaping, authorization_code + PKCE + refresh_token) for the OAuth flow shape; actual HTTP execution and credential storage (macOS Keychain via `security`, mirroring `orgs/personal/bin/google-auth.py`'s approach) are `mail-archive`'s own responsibility, per the same "token management is the caller's job" posture `com-gmail`/`org-ietf-oauth2` both already take.
- `mail.inbound/from-parts` (from `kotoba-lang/mail`) is reused for address/message normalization rather than re-deriving it.

## Consequences

- The Datomic⊣DataScript parity claim is now machine-verified (a passing contract test on both backends), not asserted by two independently-hand-maintained scripts like the same-day `orgs/personal` pipeline.
- `gmail.history` is available for any future `com-gmail` consumer, including `tayori`'s own documented gap — not wired in by this ADR, left as a follow-up.
- Relationship to `orgs/personal/bin/*.py`'s existing pipeline: not addressed by this ADR. The two remain separate, non-cross-referencing lineages (one Python/bash bulk-archival, one kotoba-lang `.cljc` library) — migrating `orgs/personal` onto this library, if ever done, is a distinct future decision.

## Non-goals (this ADR)

- Wiring `gmail.history` into `tayori.channel.email/list-new-messages`.
- Multi-account support, launchd/cron scheduling for periodic sync.
- IMAP-sourced (non-Gmail) ingestion — the schema/Store layer is provider-agnostic by construction, but `ingest.cljc` only implements the Gmail path today.
- Migrating or retiring `orgs/personal`'s existing Python pipeline.
