# ADR-2606302300: Four-org taxonomy (kotoba-lang / etzhayyim / gftdcojp / com-junkawasaki)

**Status**: proposed
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context

The superproject partitions work across four GitHub orgs checked out under
`orgs/<org>/`. The split has been **implicit** until now — referenced in passing
by many library ADRs ("最初の消費者は別 org(**公益=etzhayyim / 事業=gftdcojp**)
の actor が …") and encoded structurally in `manifest/repos.edn`
(`:remotes`, `:path-overrides`, `:extra-projects`) — but never recorded as a
single authoritative taxonomy. This ADR writes it down so new repos land in the
right org by design, not by accident.

## Decision

Four orgs, partitioned along two axes — **what kind of thing** (language /
agent / human-facing product / platform) and, for actors/products only,
**for whom** (公益 public-interest / ビジネス business):

| org | role (what) | scope (whom) | west remote | ~projects |
|---|---|---|---|---|
| `kotoba-lang` | **kotoba 言語** — pure-CLJC language/core libraries & tech (`kotoba`, `kotoba-code`, `kasane`, `kami-*`, `murakumo`, `office-*`, `svgraph`, …) + (migrating) all generic `-clj` infra | language substrate, consumed by all orgs | `git@github.com:kotoba-lang` | 85→growing |
| `etzhayyim` | **agent-centric** — dispatchable organism actors (`com-etzhayyim-*`, one per UNSPSC / ISCO / COFOG code) | **公益 (public-interest)** | `github.com/etzhayyim` | 170 |
| `gftdcojp` | **human-centric** — human-facing products & business services (`ai-gftd-*`, apps, HR `talent`) | **ビジネス (business)** | `git@github.com:gftdcojp` | 50 |
| `com-junkawasaki` | **transitional foundation** — generic `-clj` infra **migrating fully to `kotoba-lang`** (decided); retains cloud control planes + heavy/data projects | Jun Kawasaki platform/foundation (origin org) | `git@github.com:com-junkawasaki` | 53→shrinking |

### Axes

1. **What**: `kotoba-lang` is the *language/substrate* (and, after migration,
   the home of all `-clj` infrastructure libraries); `etzhayyim` and `gftdcojp`
   are *agents/products* built on it; `com-junkawasaki` is being *emptied of
   libraries* into `kotoba-lang`.
2. **Whom (actors/products only)**: `etzhayyim` = 公益 (common-good services
   delivered as agents); `gftdcojp` = ビジネス (commercial). The 公益/事業
   distinction was previously only asserted inline by the library ADRs
   (ci-clj, ddl-clj, policy-clj, bpmn-clj); this ADR is now its authority.

### Migration (com-junkawasaki → kotoba-lang) — DECIDED

Owner decision (2026-06-30): **the generic infrastructure `-clj` libraries
fully migrate to `kotoba-lang`**, using the same `repos.edn :path-overrides`
mechanism as the language-core.

- **Already migrated** (language-core): `kotoba`, `kotoba-code`, `kasane`,
  `kami-*`, `murakumo`, `office-*`, `svgraph`, `aiueos`, `kekkai`, `utsushi`,
  `vehicle-design-actor` → `kami-engine-vehicle-designer`, …
- **To migrate** (generic `-clj` infra): `aero-clj`, `bpmn-clj`, `ci-clj`,
  `ddl-clj`, `dmn-clj`, `crash-clj`, `cron-clj`, `datom-clj`, `graphql-clj`,
  `ical-clj`, `jsonlogic-clj`, `kagi-clj`, `kenchi-clj`, `kotobase-clj`, …
  (full inventory to be enumerated in the migration PR).
- **`com-junkawasaki` end-state**: **cloud control planes** (`cloud-manimani`,
  `cloud-murakumo`) + **heavy/data projects** (`spirit-in-physics`,
  `ghosthacker`) only. All libraries will have left for `kotoba-lang`.

### Exception: SVG-era Office tooling restored to com-junkawasaki

Resolved 2026-07-01 in ADR-2607011100: the CLJ/EDN rewrites remain in
`kotoba-lang` (`office`, `svgraph`), but the legacy SVG-era browser/TypeScript
tooling is restored as separate `com-junkawasaki` repos:
`com-junkawasaki/office-causal` and `com-junkawasaki/svgraph`. This is an
explicit exception to the general library migration rule because those repos
preserve package names, GitHub Pages URLs, and SVG/Office integration surfaces
that should not be conflated with the kotoba CLJ/EDN substrate.

Mechanism: add `<x>-clj` → `<x>` entries to `:path-overrides`; regenerate
(`nbb scripts/gen-west-manifest.cljs`) + `west update` to relocate checkouts;
verify `--check` canonical and `pin == repo HEAD`. GitHub org relocation per
repo is a separate decision (some repos may stay at `github.com/com-junkawasaki`
and be checked out under `kotoba-lang/` via `:remote-overrides`, as
`kami-engine-sdk` already is).

### Out of scope (not west-managed)

Personal / operational data lives **outside** west and is **not** part of this
taxonomy:

- `orgs/kawasakijun/` — personal ops/state (finances, goals, litigation, KPIs).
- `orgs/personal/` — life-data vault (mail, photos, takeout, accounts).

## Consequences

- **New-repo placement rule**:
  - **any library / substrate** (language or `-clj` infra) → `kotoba-lang`
  - public-interest agent → `etzhayyim/com-etzhayyim-*`
  - commercial product / actor → `gftdcojp/ai-gftd-*` (or `app-*`)
  - cloud control plane / heavy data → `com-junkawasaki` (the only thing that
    stays here once migration completes)

  Enforced through the standing "new project" workflow (CLAUDE.md) and the
  doc-only `:orgs` key in `repos.edn`.
- `:path-overrides` is the migration mechanism; `gen-west-manifest.cljs --check`
  keeps `west.yml` canonical.
- This ADR is the single reference for the org taxonomy; the passing 公益/事業
  mentions in library ADRs now resolve here.

## Decisions (resolved 2026-06-30)

- **`com-junkawasaki` end-state**: the generic `-clj` infrastructure libraries
  **fully migrate to `kotoba-lang`** (same `:path-overrides` mechanism as the
  language-core). `com-junkawasaki` will retain only **cloud control planes
  (cloud-manimani, cloud-murakumo) + heavy/data projects (spirit-in-physics,
  ghosthacker)**. Migration in progress; tracked via `repos.edn
  :path-overrides`.

## Amendment (2026-07-01): repo-creation-time placement check

The "what" axis (§Axes 1) was previously judged informally ("does this look
like a library?"). Formalize it into a decision procedure run **before**
scaffolding any new repo, not after:

**Step 1 — layer test (kotoba-lang admission).** A candidate belongs in
`kotoba-lang` only if it is a **pure technical layer**: `.cljc`, zero network
I/O, zero vendor SDK, models records/protocol/data only (cf. `banking`,
`card`, `swift`, `eth-crypto`, `cacao`, `did`, `vc`, `koe` — the last defines
ports + a dialog loop but injects every concrete capability from the host,
so it stays admissible even though its domain, voice telephony, is
service-shaped). The moment a repo needs to *actually call* PayPay/Stripe/a
bank API/a WebRTC SFU/an Ethereum RPC node, it fails this test and moves to
step 2.

**Step 2 — 3-axis + Charter Rider check (etzhayyim vs gftdcojp).** For any
individual/vendor-specific service (real API integration, a video-call app,
a payment rail client, a wallet UI, …), run the same classification already
applied during the gftdcojp→etzhayyim migration (see
`orgs/gftdcojp/ai-gftd-stripe/NOT-MIGRATED-VENDOR-REGULATORY.md.edn` and
`orgs/gftdcojp/ai-gftd-livecam/NOT-MIGRATED-CHARTER-VIOLATION.md.edn` for the
worked examples) **at creation time, not retroactively**:

  1. **3-axis regulatory/settlement check** — does the service hit a
     Settlement/regulatory axis (PCI-DSS, 資金決済法, 景品表示法・特定商取引法,
     KYC/AML, banking/broker-dealer licensing, …)? If yes →
     `gftdcojp/ai-gftd-*` (vendor-retained; regulatory liability sits with
     the gftd business entity, not the public-interest actor mesh).
  2. **Charter Rider v2.0 §2(a–h) check** — does the business model or
     content type hit any of WEAPONS/MILITARY, SPECULATIVE FINANCE,
     SURVEILLANCE CAPITALISM (ad-tech/data brokerage), FOSSIL FUEL
     EXTRACTION, SPECIALIST GATEKEEPING, MULTI-GENERATIONAL HARM, STRICT
     INDIVIDUALIST ONTOLOGY, or WELLBECOMING SUBORDINATION? If yes →
     `gftdcojp/ai-gftd-*` (excluded from etzhayyim by charter, same as
     `ai-gftd-livecam`).
  3. If **neither** axis hits → `etzhayyim/com-etzhayyim-*` as a proper
     actor (sealed-intelligence ⊣ independent governor, append-only ledger,
     RAD identity — per the Actors section of `CLAUDE.md`), consuming
     `kotoba-lang` protocol libraries for the technical layer.

This makes the org choice for a new individual-service repo a **two-step,
answerable-before-scaffolding** check instead of a taxonomy migration done
after the fact. Encoded machine-readably in the companion `.edn` under
`:repo-creation-check`.

## Amendment (2026-07-01): noted exception — `cloud-itonami` org

ADR-2607012100 moved the 35 `cloud-itonami-*` public OSS business/occupation
blueprint repos (formerly `gftdcojp`, public) to a dedicated `cloud-itonami`
org. This is **not** a 5th taxonomy tier: the four org roles above are
unchanged, and `cloud-itonami` exists solely as the public-repo home for the
`cloud-itonami-*` product line (fork-away business/occupation blueprints,
distinct in kind from `gftdcojp`'s own commercial `ai-gftd-*` products and
from the private `cloud-itonami` business-os base repo, which stays under
`gftdcojp`). Future `cloud-itonami-*` blueprint repos may be created directly
under `cloud-itonami` without transiting `gftdcojp` first.

## Amendment (2026-07-04): `cloud-manimani` / `cloud-murakumo` already transferred to `gftdcojp`

The "com-junkawasaki end-state" text above (§Migration, §Decisions) still reads
"cloud control planes (cloud-manimani, cloud-murakumo)" as staying under
`com-junkawasaki` — that is stale. Both were GitHub-transferred to `gftdcojp`
on 2026-07-02 (`manifest/repos.edn` `:path-overrides` note, line ~29/57);
`cloud-murakumo` was renamed to `cloud-murakumo-fleet` on transfer to avoid
colliding with the pre-existing `gftdcojp/cloud-murakumo` (Sora, the GPU
serverless product). ADR-2607041302 formalizes the resulting three-repo
`murakumo` family (`kotoba-lang/murakumo` common lib/CLI,
`gftdcojp/cloud-murakumo` Sora, `gftdcojp/cloud-murakumo-fleet` → proposed
rename to `gftdcojp/local-murakumo`) and is now the authority for that naming;
this ADR's org-taxonomy table is unaffected (both remain `gftdcojp`-owned
cloud control planes, not `com-junkawasaki`).
