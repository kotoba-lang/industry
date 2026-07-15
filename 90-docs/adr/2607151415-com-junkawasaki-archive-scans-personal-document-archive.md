# ADR-2607151415: `com-junkawasaki/archive-scans` — personal ScanSnap document archive (EDN + DataLad/B2, org/contact/project queryable)

## Status

Accepted (prototype phase — small-batch validated, full 319-file rollout pending owner go-ahead)

## Context

`/Users/junkawasaki/Documents/ScanSnap Home folder` accumulates 319 scanned
files (246 jpg + 73 pdf) from a Fujitsu ScanSnap scanner: personal utility
bills, resident-tax notices, medical/pharmacy receipts, business-card scans
(meishi), taxi fare receipts, insurance certificates, a corporate registration
certificate, and — notably — National Tax Agency withholding-tax delinquency
notices addressed to **JK株式会社** (mailed to the owner's home address, since
JK株式会社 is the owner's own company). This is highly sensitive personal
financial/medical/legal data that currently lives unmanaged in a local Finder
folder with unreliable OCR-garbled filenames.

The owner asked to (1) classify these documents, (2) organize them "by org"
into projects, (3) make the metadata queryable via EDN/Datomic, (4) store the
actual image data via DataLad, and (5) surface/manage it through the existing
`orgs/gftdcojp/local-manimani` app's UI.

Clarifying answers obtained from the owner (2026-07-15):

1. **Org taxonomy**: exactly 3 receiving-entity orgs — `gftdcojp` (GFTD Japan
   株式会社), `jk-luxury` (JK株式会社), `com-junkawasaki` (personal/個人). Group
   by *receiving entity*, not by counterparty/vendor (a repo-per-vendor would
   be excessive). Same data must remain cross-referenceable by "project" unit
   and by "contact" unit — i.e. a single canonical data store with multiple
   query dimensions, not physically duplicated per grouping.
2. **Storage/visibility**: consolidate under **one private repo under
   `com-junkawasaki`** (not one repo per org, not pushed anywhere public).
3. **Rollout**: prototype on a small batch first, then expand to all 319
   files.

## Decision

### 1. New repo: `com-junkawasaki/archive-scans` (private, DataLad dataset)

One new west project, `orgs/com-junkawasaki/archive-scans`, created via the
standard `new-project-scaffold` flow (private GitHub repo per
ADR-2607021330's com-junkawasaki default; `com-junkawasaki` is a GitHub
**personal user account**, not an org — confirmed via `gh api
users/com-junkawasaki` → `"type":"User"`). It follows the exact
`gftdcojp/m365-archive` DataLad+B2 hybrid template (reusing the *same*
already-provisioned B2 credential, 1Password item `com-junkawasaki.b2/annex`
in the `gftdcojp` vault — this bucket already exists precisely for
com-junkawasaki personal-archive data):

- **Plain git** (never annexed): `README.md`, `bin/**`, `datomic/**` (schema +
  query definitions only, no data), `docs/adr/**`, `.gitattributes`.
- **git-annex + B2, encrypted** (`annex.largefiles=anything` by default, MD5E
  backend matching m365-archive): `scans/**` (the raw image/pdf binaries) and
  **`facts/**` unconditionally** — i.e. the derived metadata (dates, amounts,
  counterparties, medical/tax details) is *also* annexed+encrypted, not
  committed as plaintext EDN to GitHub, exactly mirroring m365-archive's
  `.gitattributes` rule that PII-bearing facts stay annexed even though
  they're `.edn`/`.jsonl` text. This is a deliberate defense-in-depth choice:
  even in a *private* repo, tax-delinquency and psychiatric-clinic line items
  should not sit as cleartext blobs in GitHub's object store.

### 2. Schema (`datomic/schema-archive.edn`)

DataScript/Datomic-transactable schema (`:db/ident`/`:db/valueType`/
`:db/cardinality`/`:db/unique`, matching the house style established by
`90-docs/design-quality/design-quality.datoms.edn`), five entity kinds:

- `:org/*` — exactly 3 static entities: `:org/personal`, `:org/jk-luxury`,
  `:org/gftdcojp`.
- `:contact/*` — a person/company appearing on a document (business-card
  counterparties, addressees), optionally scoped to an `:org`.
- `:project/*` — a cross-cutting case/matter grouping (e.g. a multi-document
  tax-delinquency saga), refs an `:org`.
- `:blob/*` — one content-addressed scanned file. `:blob/cid` is the
  **git-annex key** (e.g. `MD5E-s147511--<hash>.jpg`) — this codebase's
  existing convention for "CID" per `m365-archive/facts/blobs.jsonl`
  ("CID (git-annex key) ↔ path ↔ size"); no separate IPFS CID scheme is
  introduced.
- `:doc/*` — one logical document (may span multiple `:blob` pages via
  `:doc/blobs` many-ref), with `:doc/org`, `:doc/category`, `:doc/date`,
  `:doc/counterparty`, optional `:doc/contact`/`:doc/project`/`:doc/amount`,
  `:doc/source-filename`, `:doc/notes`.

### 3. Query mechanism — nbb-native now, real DataScript deferred to the web UI

Spiked `datascript` (npm v1.7.8) called from `nbb` via `["datascript" :as
ds]`: **incompatible**. The npm package is a Closure-advanced-compiled
ClojureScript bundle with its own internal `cljs.core` copy; nbb/SCI's
interpreted data structures don't satisfy its internal protocols
(`IKVReduce` etc. — confirmed empirically: `create_conn` needs a genuine JS
object for schema, but `transact`/`q` silently no-op or throw depending on
whether args are SCI collections, `clj->js`-converted objects, or raw EDN
strings — no combination round-tripped real query results in nbb). This is a
cross-runtime interop limitation, not a data-format problem — the schema/facts
EDN remains 100% valid input for a *real* DataScript/Datomic. Two working
paths going forward:

- **Now (CLI/nbb)**: plain nbb functions over the `facts/*.jsonl`/`*.edn`
  (filter/group-by/sum) for practical lookups — honest, no fake Datalog
  layer.
- **Phase 2 (web UI)**: `orgs/gftdcojp/local-manimani/web` is a real
  shadow-cljs-compiled ClojureScript app (not nbb/SCI) — a genuine
  `:require [datascript.core :as d]` there compiles cleanly against the same
  compiler and will not hit this interop wall. This is where real ad-hoc
  Datalog querying should live, as a new "Documents" view alongside
  manimani's existing Inbox Queue / m365-archive-status views.

### 4. Prototype scope (this pass)

Classified and modeled the 2026-07-15 scan batch (48 files, not 38 as
initially estimated) — dominated by Tokyo utility bills, a Shibuya
resident-tax arrears saga, psychiatric clinic/pharmacy receipts (all
`:org/personal`), and the JK株式会社 withholding-tax delinquency thread
(`:org/jk-luxury`). Full 319-file rollout, GitHub repo creation, B2 push, and
the local-manimani web UI integration are **deferred pending owner review of
this prototype** (see ADR addendum / conversation for the concrete go/no-go).

## Consequences

- Sensitive personal data gets a durable, queryable home instead of an
  unmanaged Finder folder, while keeping actual PII off GitHub's plaintext
  object store (annex+encrypt boundary matches m365-archive precedent
  exactly).
- The org taxonomy (personal / jk-luxury / gftdcojp) is intentionally coarse
  (3 entities) per owner direction — finer-grained "which taxi company /
  which utility" distinctions live as `:doc/counterparty` values and
  `:contact`/`:project` refs, not as separate repos or orgs.
- A real interactive Datalog query experience requires the Phase-2 web UI
  work in local-manimani; today's nbb tooling only offers direct
  filter/group-by helpers.
- Scaling to all 319 files is mechanical repetition of this same
  classify → facts-append → annex-add pipeline, but should still get a
  once-over from the owner given the sensitivity of what's in this folder
  (tax delinquency, medical, corporate registration documents).
