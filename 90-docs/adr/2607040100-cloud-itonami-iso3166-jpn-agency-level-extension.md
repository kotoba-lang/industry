# ADR-2607040100: cloud-itonami-iso3166-jpn — agency-level extension (ministries / agencies / independent commissions, Japan only)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

ADR-2607032330 established `cloud-itonami-iso3166-{code}`: one commercial-
operator blueprint per ISO 3166-1 alpha-3 COUNTRY, helping an
already-incorporated operator navigate that country's public-procurement
market entry. The follow-up request was to extend this to the granularity
of individual government ORGANIZATIONS within a country — 行政機関、組織、
委員会 (ministries, agencies, committees) — using the ID scheme a country
already issues for its own administrative bodies.

That ID scheme already exists in this workspace: `com-etzhayyim-ooyake`
carries a verified, sourced record for every Japanese central-government
body (`gov.jpn.{code}`), split across:

- `gov-units.jp-central.seed.edn` — 13 ministries/cabinet-office + 2
  agencies (デジタル庁, 復興庁), all `:level :ministry`/`:agency`
  `:branch :executive`.
- `gov-units.seed.edn` — MOF (財務省) + NTA (国税庁), part of the base G7
  seed.
- `gov-units.oversight-{competition,dataprotection,financial-regulator,
  audit,statistics}.edn` — 5 independent commissions/agencies (`:branch
  :independent`): JFTC (公正取引委員会), PPC (個人情報保護委員会), FSA
  (金融庁), Board of Audit (会計検査院), Statistics Japan (総務省統計局).

Total: 19 central-government bodies, each with an official URL, Wikidata
QID, and (for most) an HQ address, `:sourcing :authoritative` /
`:verification-status :maintainer-verified`.

Doing this for all 5 pilot countries × all their agencies at once would be
premature (95+ repos before the pattern is validated). Scope is narrowed to
Japan only for this round, per owner direction.

## Decision

### 1. Second registry level: agency, `:parent "JPN"`

`kotoba-lang/iso3166`'s registry gains a SECOND level, structurally
identical to how `kotoba-lang/cofog` has division/group: country entries
stay flat (unchanged, ADR-2607032330), and 19 new entries are added with
`:level :ministry` / `:agency` / `:independent-commission` and `:parent
"JPN"`. Codes are `JPN-{ACRONYM}` (e.g. `JPN-METI`, `JPN-JFTC`), each
carrying an `:ooyake-id` cross-reference back to the source `gov.jpn.*`
record (traceability, not duplication — names/URLs/Wikidata QIDs are reused
verbatim from ooyake, not re-derived).

`kotoba.iso3166/children` resolves the agency-level entries under a country
code, giving `"JPN"` 19 children and every other country 0 (until/unless
this pattern is extended elsewhere) — the same `children(family, code)`
shape the shared organism substrate (ADR-2606301900) already anticipates.

### 2. 5 curated agency blueprints, spanning all 3 body types

| Code | Body | Ooyake ID | Blueprint |
|---|---|---|---|
| `JPN-METI` | 経済産業省 (ministry) | `gov.jpn.meti` | Independent METI-Regulated Trade & Industrial-Policy Compliance Service |
| `JPN-MOF` | 財務省 (ministry) | `gov.jpn.mof` | Independent MOF-Regulated Customs & Tax Compliance Service |
| `JPN-DIGITAL` | デジタル庁 (agency) | `gov.jpn.digital` | Independent Digital-Agency-Regulated GovTech Procurement Compliance Service |
| `JPN-JFTC` | 公正取引委員会 (independent commission) | `gov.jpn.competition` | Independent JFTC Antitrust & Bid-Rigging Compliance Service |
| `JPN-PPC` | 個人情報保護委員会 (independent commission) | `gov.jpn.dataprotection` | Independent PPC Personal-Data Compliance Service |

Each targets a real, named Japanese regulatory regime rather than a generic
restatement of the country-level blueprint:

- **METI**: FEFTA (外為法) export-control classification + METI
  subsidy/grant program navigation.
- **MOF**: Customs & Tariff Bureau tariff classification + NTA Qualified
  Invoice Issuer (インボイス) registration.
- **Digital Agency**: gBizID registration + Gov-Cloud service-catalog/
  technical-baseline compliance.
- **JFTC**: Antimonopoly Act (独占禁止法) bid-rigging (談合) prevention +
  Subcontract Act (下請法) prime/subcontractor compliance.
- **PPC**: APPI (個人情報保護法) personal-data compliance + cross-border
  data-transfer restrictions.

The remaining 14 bodies are `:maturity :spec`, available for future
promotion via the same `:spec` → `:blueprint` → `:implemented` path the
other `kotoba-lang/*` registries use.

### 3. Coordinator + leaf, not a replacement

`cloud-itonami-iso3166-jpn` (country level, ADR-2607032330) is UNCHANGED
and remains the general-market-entry coordinator. The 5 new
`cloud-itonami-iso3166-jpn-{code}` repos are LEAVES under it — narrower,
deeper, agency-specific — mirroring the coordinator + per-code-organism
split `com-etzhayyim-isco` / the proposed `com-etzhayyim-cofog` already use
(ADR-2606301900), here applied to the commercial-operator side instead of
the non-adjudicating organism-actor side. An operator typically needs the
country-level blueprint plus only the agency-level blueprints that actually
apply to its specific contract — not all of them.

### 4. Naming: reuse ooyake's ID, not a new scheme

Repo/code naming reuses the acronym form already used for these bodies
(METI/MOF/JFTC/PPC) rather than inventing a new scheme, while the
`:ooyake-id` field preserves the exact source ID (`gov.jpn.competition`,
`gov.jpn.dataprotection`) for entries where ooyake's internal suffix
differs from the commonly-known acronym (JFTC/PPC).

### 5. Robotics premise and actuation gate unchanged

Same digital-service exemption as the country-level family
(`:itonami.blueprint/robotics false`, `:required-technologies [:identity
:forms :dmn :bpmn :audit-ledger]`) and same actuation invariant
(`:filing/submit` never in any phase's `:auto` set, mirrors
`cloud-itonami-M6910`'s `filing-submit-never-auto-at-any-phase`).

## Consequences

- (+) Agency-level granularity now composes with the existing country-level
  and function-level (COFOG/ISCO/UNSPSC/ISIC) axes: an operator can fork a
  COFOG-function blueprint + the `cloud-itonami-iso3166-jpn` coordinator +
  only the agency leaves relevant to its contract, instead of one
  monolithic per-country blueprint trying to cover every agency's rules at
  once.
- (+) Full 19/19 Japan agency coverage in the registry (12 tests / 532
  assertions across the whole `kotoba-lang/iso3166` repo, all green),
  traceable back to `ooyake`'s verified source records via `:ooyake-id`.
- (+) 5 pilot agencies span all 3 body types (ministry / agency /
  independent commission) and 5 distinct real regulatory regimes (export
  control, customs/tax, GovTech procurement standards, antitrust/bid-
  rigging, personal-data protection) — no two blueprints restate the same
  compliance surface.
- (−) Scope is Japan-only; the same agency-level pattern for USA/DEU/KEN/
  IND (or Japan's remaining 14 bodies) is explicit future work, not decided
  by this ADR.
- (−) Country-level and agency-level blueprints necessarily overlap in
  generic scaffolding (governance docs, robotics exemption, actuation gate)
  — by design, since each repo must stand alone as a forkable business, at
  the cost of some repeated boilerplate across the family.
- superproject registration: no new `kotoba-lang/*` repo (this ADR extends
  the EXISTING `kotoba-lang/iso3166`, registered by ADR-2607032330); its
  west.yml pin advances to the new commit. The 5
  `cloud-itonami-iso3166-jpn-*` blueprint repos remain standalone, following
  the existing `cloud-itonami-*` convention.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated, not new)
- blueprint repo (cloud-itonami org, public, AGPL-3.0-or-later):
  `cloud-itonami-iso3166-jpn-{meti,mof,digital,jftc,ppc}` (5 repos)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints) — the country-level family this ADR extends.
- ADR-2607031600 (cloud-itonami-cofog) — the division/group coordinator+
  leaf precedent this ADR's country/agency split mirrors.
- ADR-2606301900 (etzhayyim/root, ISCO/COFOG organism actors) — the shared
  organism substrate's `children(family, code)` contract, reused here as
  `kotoba.iso3166/children`.
- ADR-2607031500 (cloud-itonami-M6910) — the `filing-submit-never-auto-at-
  any-phase` actuation invariant this ADR's agency blueprints also follow.
- `com-etzhayyim-ooyake/registry/gov-units.jp-central.seed.edn`,
  `gov-units.seed.edn`, `gov-units.oversight-{competition,dataprotection,
  financial-regulator,audit,statistics}.edn` — source of all 19 Japan
  agency IDs/names/URLs, reused verbatim (not re-derived).
