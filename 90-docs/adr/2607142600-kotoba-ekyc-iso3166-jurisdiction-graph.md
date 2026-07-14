# ADR-2607142600: `kotoba.ekyc.jurisdiction-graph` — cross-repo eKYC × ISO 3166 regulatory-linkage graph

**Status**: accepted (landed)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (+ Claude, designed and landed under standing authorization)
**Scope**: `kotoba-lang/ekyc` (new `kotoba.ekyc.jurisdiction-graph` namespace),
`cloud-itonami/cloud-itonami-iso3166-jpn` (new dependency + reference bridge),
`manifest/west.yml` (ekyc pin advance)

## Context

Earlier today a sibling agent built `kotoba.ekyc` in `kotoba-lang/ekyc`: a
real, spec-conformant catalog of Japan's 犯罪による収益の移転防止に関する法律
施行規則 (Act on Prevention of Transfer of Criminal Proceeds, Enforcement
Regulation) Article 6(1) non-face-to-face identity-verification methods
(ホ/ヘ/ト(1)/ト(2)/ル/カ/ワ/ヨ for individuals, ロ/ホ for corporates), each
with a real statute citation, cross-referenced against NIST SP 800-63A-4
Identity Assurance Levels. JPN is the only jurisdiction modeled.

The owner asked for this data to be linked with the `cloud-itonami-iso3166-*`
family (per-country "Market-Entry Compliance" actor repos in the
`cloud-itonami` org) via EDN, loadable into both Datomic (JVM) and
DataScript (in-browser) — a real queryable graph, not more markdown.

## Verification before design (premises checked, not assumed)

The task brief stated the family has "223 per-country repos" with JPN "the
ONLY one at `:implemented` maturity." Both were checked directly rather than
assumed, with material corrections:

- **223 → 188 real countries.** `find orgs/cloud-itonami -maxdepth 1
  -iname 'cloud-itonami-iso3166-*'` returns 223 directories, and paginated
  `gh api orgs/cloud-itonami/repos` confirms 216 are live on GitHub. Parsing
  every local `blueprint.edn`'s own `:itonami.blueprint/domain` field found
  the 7-entry gap resolves to THREE distinct facts: (1)
  `cloud-itonami-iso3166-ind-clean-air` is a different-domain blueprint
  (`:public-interest/airshed-clean-air`, not market-entry-compliance) that
  merely matches the directory glob; (2) six directories (`kgz` `lao` `mmr`
  `tjk` `tkm` `uzb`) are real local scaffolds with no `.git` of their own,
  never pushed to GitHub; (3) **34 of the remaining 222 are Japan/USA
  AGENCY-level sub-blueprints** (`cloud-itonami-iso3166-jpn-fsa`,
  `cloud-itonami-iso3166-usa-gsa`, etc. — 19 JPN + 15 USA), each a child of
  a country rather than an additional country, matching
  `kotoba-lang/iso3166`'s own registry (34 `:parent "JPN"`/`:parent "USA"`
  entries). **The real per-country count is 188.**
- **JPN is not the only `:implemented` country actor.** `bb`-querying
  `kotoba-lang/iso3166`'s `resources/kotoba/iso3166/registry.edn` directly:
  193 top-level (non-agency) entries, **68 at `:maturity :implemented`**
  (not 1), 154 `:blueprint`. Reading `cloud-itonami-iso3166-usa` and
  `cloud-itonami-iso3166-deu` confirmed this directly — both have the exact
  same `governor.cljc`/`operation.cljc`/`store.cljc` file set as JPN, and
  both READMEs self-report `:implemented`. This finding is independently
  corroborated by a concurrent agent's own same-day audit in
  ADR-2607141700 ("188repo 中 68 が `:implemented`"), landed a few commits
  before this one. **JPN remains this ADR's own scope anchor** because it
  is the only jurisdiction `kotoba.ekyc` actually models — the correction
  is about the country-actor family's maturity distribution, not about
  which jurisdiction's eKYC data is real (still exactly one, JPN).

Reporting the corrected numbers, not the assumed ones, matches this fleet's
own "never fabricate, report honest coverage" discipline applied to the
verification step itself.

## Decision

1. **Home**: `kotoba.ekyc.jurisdiction-graph`, a new namespace in
   `kotoba-lang/ekyc` (not a new repo). The graph spans 188 country repos +
   one capability library, so it belongs to none of them individually;
   `kotoba-lang/ekyc` already owns the underlying method-catalog data this
   graph reuses (derived live from `kotoba.ekyc/method-catalog`, never
   hand-copied) and already has the `.cljc`/test/lint/CI scaffolding this
   addition extends. See `kotoba-lang/ekyc` `docs/adr/0002-iso3166-
   jurisdiction-graph.md` for the full design ADR (schema detail,
   empirical DataScript probes, 223→188 reconciliation method).
2. **Schema**: `:country/*` (188 real entities: alpha-3 unique identity,
   alpha-2, name, repo-url, coverage-status), `:ekyc-method/*` (10
   entities, derived from the live catalog), `:recognition/*` — a REIFIED
   edge entity (not a direct country→method ref) so each recognition can
   carry its own jurisdiction-specific legal-basis citation, distinct from
   the method's own canonical citation. `:country/recognizes-method`
   (ref, cardinality-many) points at recognition entities.
3. **Dual Datomic/DataScript compatibility, proven empirically**:
   `schema-datomic` (full vocabulary) and `schema-datascript`
   (`derive-datascript-schema`, a mechanical, inspectable transform) are
   both defined; the derivation logic and the resulting schema are proven
   against a REAL `datascript.core` conn (`datascript/datascript` 1.7.8,
   test-only JVM dep — the actual portable `.cljc` DataScript source, same
   code that compiles to run in a browser). The empirical probe (run before
   finalizing the schema, not after) found DataScript rejects ANY
   `:db/valueType` other than `:db.type/ref`/`:db.type/tuple` outright — a
   stricter, more specific incompatibility than "just `:db/fulltext`",
   which was the only divergence assumed going in. A second probe found
   DataScript's map-form `transact!` does not resolve forward lookup refs
   to sibling entities within one tx-data vector (real Datomic does); the
   graph's `base-tx-data`/`edge-tx-data` split into two sequential
   transact calls exists because of this. `test/kotoba/ekyc/
   jurisdiction_graph_test.cljc` transacts real tx-data into a real
   DataScript conn and runs every example query against it: 213 assertions
   / 49 tests, `clojure -M:test`, all green; `clojure -M:lint`: 0 errors,
   0 warnings.
4. **Coverage honesty**: `:country/recognizes-method` edges exist ONLY for
   JPN (10 edges, one per real `kotoba.ekyc/method-catalog` entry). All 187
   other real countries get a structurally complete `:country/*` entity
   with `:country/coverage-status :not-yet-researched` — absence of an
   edge means "not yet researched," never "no requirements," matching this
   fleet's `vcfund.facts/coverage`/`marketentry.facts/coverage`/
   `statute.facts/coverage` convention.
5. **`cloud-itonami-iso3166-jpn` software dependency** (the one country
   actor plausibly able to consume this as real code): added
   `io.github.kotoba-lang/ekyc` as a real `:local/root` git dependency in
   `deps.edn` (matching the fleet's existing `langgraph`/`ontology`
   dependency convention). Read `marketentry.governor.cljc`,
   `marketentry.facts.cljc`, and the freshly-landed `statute.facts.cljc`
   (ADR-2607141700's "compliance-fact-federation," landed a few commits
   before this one) before deciding where to wire it:
   - `marketentry.governor`'s existing HARD checks
     (`japan-resident-rep-missing-violations` etc.) all answer
     PUBLIC-PROCUREMENT ELIGIBILITY questions (is a Japan-resident
     representative on file, is a corporate number verified) — a
     structurally different regulatory domain from `kotoba.ekyc`'s
     ANTI-MONEY-LAUNDERING CUSTOMER-IDENTITY-VERIFICATION methods (which
     non-face-to-face method a specified business operator may use to
     verify ITS OWN CUSTOMER). Forcing `kotoba.ekyc/validate` into an
     existing HARD check would conflate the two under one boolean — NOT
     done, per this task's own explicit permission to say so rather than
     force an artificial integration point.
   - Instead: `statute.facts` (the freshly-landed general-law compliance
     catalog, ADR-2607141700) had exactly 3 statutes and no AML/eKYC
     entry. Added a 4th, real, `gh`/WebFetch-verified entry
     (`jpn.aptcp-enforcement-regulation`, e-Gov law id
     `420M60000F5A001` — the SAME statute `kotoba.ekyc` already models;
     promulgation, ordinance number, and date verified live via the e-Gov
     law-data API, independently cross-confirming `kotoba.ekyc`
     ADR-0001's own citation of the same 2026-04-15 revision date). Then
     added `marketentry.ekyc-bridge`, a small new namespace mirroring the
     shape of the repo's existing `marketentry.goyoukiki` bridge
     (coarse statute citation ↔ fine-grained companion library), grounding
     that one-line statute in `kotoba.ekyc`'s real method-by-method detail.
     Its own docstring states plainly: this is a REFERENCE surface, not a
     governor HARD-check dependency, and names exactly what a genuine
     future check (verifying a Japan-resident representative's own
     identity, should this actor's scope grow to cover that) would build
     on. 37 tests / 122 assertions, `clojure -M:dev:test`, all green;
     `clojure -M:lint`: 0 errors (10 pre-existing warnings, none in
     touched files).
   - Landed as a separate PR from the `statute.facts.cljc`
     ADR-2607141700 lineage it extends (concurrent agent, unrelated to
     this task) — re-fetched `origin/main` immediately before branching to
     pick up its landed commits (`09f9fd3` statute.facts, `15252d7`
     organization.edn HQ fix) and confirmed neither touched this ADR's own
     files before merging.
6. **`manifest/west.yml`**: `ekyc` entry `revision:` advanced from
   `3494b310ca7d7116e22cbfce49eb27b54fc67139` to
   `ad8b291fb257ae8aedb1248422b1f4efc54b8b79` (the jurisdiction-graph merge
   commit) via the documented GitHub-API single-entry PUT (tip blob SHA
   fetched, only that one `revision:` line edited, PUT with matching blob
   SHA — one-line diff). Verified with `nbb scripts/gen-west-manifest.cljs
   --entry ekyc --check` from a topdir-isolated sibling clone (`west init
   -l manifest` + `west update --fetch smart ekyc` there first, so the
   check runs against the real checked-out HEAD, not the shared `orgs/`
   tree): `west.yml is up to date.`
   `cloud-itonami-iso3166-jpn` is intentionally outside west management
   (same as the rest of the `cloud-itonami-*` fleet) — no west.yml entry
   for it.

## Consequences

(+) A real, queryable, dual-Datomic/DataScript regulatory-linkage graph
with one honestly-researched jurisdiction (JPN, 10 methods) and 187
honestly-unresearched country placeholders, structurally ready for whoever
researches the next one.
(+) Two independent, non-forced findings corrected the task's own stated
premises (223→188 real countries; 1→68 `:implemented` country actors)
before any design decision depended on them.
(+) `cloud-itonami-iso3166-jpn` gets a real, tested code path to
`kotoba.ekyc`'s method catalog, honestly scoped as reference-only rather
than forced into an ill-fitting governor check.
(−) `schema-datomic` itself is not verified against a licensed Datomic
peer (none available in this workspace, the same limitation the rest of
the `cloud-itonami-*` fleet works around via `langchain.db`) — verified
instead via the empirically-confirmed-compatible DataScript derivation.
(−) 187 of the 188 real countries remain unresearched by design; six of
the 188 (`KGZ` `LAO` `MMR` `TJK` `TKM` `UZB`) are not yet pushed to
GitHub, unrelated to this ADR.

## Related

- `kotoba-lang/ekyc` `docs/adr/0002-iso3166-jurisdiction-graph.md` — the
  full design ADR (schema detail, empirical DataScript probes, 223→188
  reconciliation method).
- `90-docs/adr/2607141700-cloud-itonami-compliance-fact-federation.md` —
  the concurrent, unrelated-in-origin ADR whose Wave-0 tick landed
  `statute.facts.cljc` in `cloud-itonami-iso3166-jpn` a few commits before
  this one; its own independent audit corroborates the 188/68
  `:implemented` finding above. This ADR's `marketentry.ekyc-bridge`
  extends that catalog (adds the 4th, AML/eKYC statute entry) rather than
  duplicating it.
- `90-docs/adr/2607142315-kotoba-lang-ekyc-jpn-aptcp-nist-800-63a-capability-library.md` —
  the sibling agent's ADR for `kotoba.ekyc` itself (this ADR's dependency).
- `orgs/etzhayyim/global-energy-datoms` — this workspace's prior art for
  the schema/tx-data/queries EDN shape and the JVM-`datascript.core`
  compatibility-proof pattern, reused here.
- `cloud-itonami-isic-6492`'s `credit.store` — this fleet's
  `langchain.db`-backed Datomic-API-compatible `Store` protocol
  convention, referenced for schema/attribute-naming style.
