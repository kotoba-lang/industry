# ADR-2607112400: kotoba-property global authority coverage

Date: 2026-07-10
Status: accepted

## Context

`kotoba-lang/property` collects land, building, corporate-owner, and public
UBO-adjacent data in EDN for Datomic/DataScript query. Publication, bulk
access, personal-data law, and reuse licences differ by authority. A public
search page is not by itself permission to create a person-indexed bulk
dataset.

The project must report worldwide coverage honestly. In particular, the
absence of a reviewed source must not be silently counted as either supported
or forbidden.

## Decision

Authority coverage is represented in
`orgs/kotoba-lang/property/resources/property/open_data/coverage.edn`.
Every jurisdiction or authority is assigned one of:

- `:allow-login-free`: machine-readable source, no account required, and the
  current collection scope is permitted.
- `:governed-license`: data exists, but collection or redistribution requires
  a licence, account, privacy controls, or a source-specific review.
- `:search-only`: individual lookup is available but bulk collection is not
  approved.
- `:deny`: the current project policy excludes the source or use case.
- `:unknown`: no reviewed determination exists yet.

`:unknown` is included in the global coverage denominator. It is not treated
as `:allow-login-free`, and collectors must fail closed unless their source
maps to an explicit catalog entry with an allowed status.

The current verified entries are:

| Authority | Status | Scope |
| --- | --- | --- |
| Global LEI Foundation | `:allow-login-free` | LEI entities and corporate parent relations |
| NYC Open Data | `:allow-login-free` | City-owned parcels/buildings only |
| Companies House | `:governed-license` | PSC and Register of Overseas Entities |
| HM Land Registry | `:governed-license` | UK/overseas corporate property owners in England/Wales |
| LINZ | `:governed-license` | New Zealand title/property ownership, subject to personal-data licence |
| EU/EEA general-public UBO registers | `:deny` | No unrestricted general-public UBO mirror |

The first two are the only sources currently enabled for credential-free
collection. GLEIF data is corporate identity/relationship data, not a natural
person UBO registry. NYC data is restricted to public-body ownership in this
project.

## Consequences

Global coverage is a measured state, not a claim that all countries have been
collected. A country with no reviewed authority remains visible as
`:unknown`; it cannot enter an automated collector by omission. Coverage can
grow by adding an authority record with source URL, dataset scope, licence,
review date, and status.

Natural-person property owners remain excluded from the public repository.
Natural-person UBO records require a separate jurisdiction-specific governed
storage and suppression policy.

## Verification

```bash
npm run query:coverage
npm run verify:coverage-gate
```

The nbb coverage gate maps collector source IDs to catalog entries and rejects
unclassified sources before network data is persisted.
