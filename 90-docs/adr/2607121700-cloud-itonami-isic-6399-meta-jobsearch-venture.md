# ADR-2607121700: Indeed-style meta job-search — new cloud-itonami venture, ISIC 6399 narrowed to job-posting meta-search

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

The owner asked whether an Indeed-style **meta job-search** actor (crawl/
aggregate job postings from multiple sources, normalize, publish into a
public search index) exists anywhere in `cloud-itonami`, and on finding it
does not, approved building one ("ok, do it").

A fleet survey (full-text grep across `orgs/cloud-itonami` and the
`kotoba-lang/industry` registry) found no job-search/job-board/aggregator
venture. The nearest existing entries are all different businesses:

| Entry | What it is | Why it is not this |
|---|---|---|
| `cloud-itonami-isic-7810` (Community Employment Agency) | candidacy intake → jurisdiction assess → match → place, `:implemented` | a *placement agency* actor; its README explicitly rules candidate sourcing / posting aggregation out of scope |
| `cloud-itonami-isic-7820` (temp staffing) | employer-of-record staffing | not a search/aggregation business |
| `cloud-itonami-isic-6312` (Web portals) | generic third-party content curation portal (PortalCurator-LLM ⊣ PortalGovernor), `:implemented` | generic portal curation; not job postings, none of the job-advertising law surface |
| `cloud-itonami-isic-6310` (Talent Actor, 旧 gftd-talent-actor) | HR/talent-management SaaS replacement | internal people data, not public job search |
| `cloud-itonami-isic-6311` (market data) | market-data aggregation & hosting | securities prices, not postings |

Placement constraints discovered:

- `kotoba.industry/by-id` builds a map keyed by `:id` — **one registry
  entry per ISIC class**, so a second venture cannot share `"6312"` or
  `"7810"`.
- ISIC `6399` "Other information service activities n.e.c." (which the
  official explanatory notes give to *information search services on a
  contract or fee basis*) sits in the registry as a `:spec`-tier
  J-prefix placeholder (`gftdcojp/cloud-itonami-J6399` — repo never
  created), i.e. it is free to take.
- The fleet has an established **narrowing precedent**: `6311` (generic
  data processing → multi-asset market-data aggregation) and `4610`
  (generic wholesale-on-fee → commission broker).

## Decision

1. **Build the meta job-search venture as `cloud-itonami/cloud-itonami-isic-6399`,
   narrowing ISIC 6399 to job-posting meta-search/aggregation** — the same
   narrowing move as `6311`/`4610`. Registry `:name` "Meta Job Search",
   `:business-id "cloud-itonami-isic-6399"`, promoted `:spec` →
   `:implemented` (the J-prefix placeholder's `:repo`/`:business-id` are
   rewritten to the `cloud-itonami` org form, the same correction the
   fleet already applied to other promoted placeholders).
2. **Actor**: **JobSearch-LLM ⊣ Job Search Portal Governor**
   (`:itonami.blueprint/governor :job-search-portal-governor`,
   grep-verified unique fleet-wide). Same governed-actor architecture as
   the 140 implemented siblings: langgraph StateGraph, sealed advisor,
   independent governor, Phase 0→3 rollout, append-only audit ledger,
   `MemStore` ‖ `DatomicStore` parity.
3. **Primary entity**: a `posting`. Four ops: `:posting/ingest` (feed
   normalization, the only auto-eligible op), `:jurisdiction/assess`,
   `:posting/publish` and `:posting/delist` — a dual-actuation shape,
   SEQUENTIAL on the SAME posting (publish first, delist later), matching
   `7810`'s match→place shape. Publishing to and delisting from the
   public index are the two real-world acts; **neither is ever
   autonomous at any phase**.
4. **HARD checks** (honesty discipline — new vs. reapplied stated
   explicitly):
   - `stale-vacancy` (**flagship, domain-unique**: publishing a posting
     whose own record says the source vacancy is already closed/filled is
     blocked — 職業安定法5条の4 的確表示義務 (令和4年改正, the provision
     written for 募集情報等提供事業者 = exactly this business) / FTC Act §5 /
     GBR Conduct Regulations 2003 reg. 27 genuine-vacancy / UWG §5;
     kinship to `6311`'s stale-print and the fleet's assessment-stale
     checks acknowledged, the statutory grounding and vacancy concept are
     new — zero fleet hits for "vacancy").
   - `source-consent-unverified` (**conditional**; a new member of the
     fleet's existing consent-check family — customer-data-consent /
     prior-informed-consent / guardian-consent — applied to
     source-republication consent, conditional on the posting's own
     `:requires-source-consent?`; database-right/copyright + JPN
     特定募集情報等提供事業者届出 regime).
   - `ad-content-discriminatory` (honest reapplication of `7810`'s
     matching-basis-discriminatory discipline to *advertisement content*,
     under the ad-specific provisions: 男女雇用機会均等法5条・労働施策総合
     推進法9条 / Title VII §704(b)・ADEA §4(e) / Equality Act 2010 /
     AGG §11).
   - `displayed-compensation-mismatch` (honest reapplication of the
     ground-truth-recompute discipline: displayed pay must equal the
     source record's own hourly wage × monthly hours).
   - plus the standard spec-basis and evidence-incomplete checks, and
     already-published/already-delisted double-actuation guards on
     dedicated booleans.
5. **No robotics** — the actuation surface is a public search index, no
   physical work; same exemption class as `6310`/`6311`/`6312`/`7820`
   (registry `:required-technologies [:identity :forms :dmn :bpmn
   :audit-ledger]`).
6. **Registration**: GitHub repo in the `cloud-itonami` org (public, org
   default, AGPL-3.0-or-later, matching the whole fleet), entry update in
   `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` landed
   via worktree branch + server-side merge, followed by the `industry`
   west-pin advance (single-entry, `scripts/verify-west-pins.cljs`
   gates). The venture repos themselves are **not** west projects
   (fleet convention — only `gftdcojp/cloud-itonami` itself is).

## Consequences

- (+) The 141st implemented actor; the Indeed-shaped gap in the fleet is
  closed with the exact regulatory surface (的確表示義務 etc.) that real
  job-posting aggregators are regulated under in Japan since 2022.
- (+) Clear division of labor: `6399` aggregates/publishes postings and
  never matches or places anyone; `7810` matches/places and never
  aggregates; `6312` stays the generic content portal.
- (−) R0 covers 4 jurisdictions (JPN/USA/GBR/DEU) and does not include a
  real crawler/feed integration — the operator supplies source
  integrations; the actor supplies the governed publish/delist scaffold.
- (−) Sponsored-listing revenue (Indeed's actual business model) is a
  business-model doc line, not implemented mechanics.

## References

- `cloud-itonami/cloud-itonami-isic-6399` — new repo,
  `docs/adr/0001-architecture.md` (full decision detail).
- `cloud-itonami-isic-7810` `docs/adr/0001-architecture.md` (template
  sibling; scope-boundary statement).
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (`by-id` uniqueness; 6311/4610 narrowing precedent; J-prefix
  placeholder correction precedent).
- 職業安定法5条の4・43条の2 (令和4年改正、募集情報等提供事業者の的確表示義務
  ・特定募集情報等提供事業者届出制); Title VII §704(b), ADEA §4(e), FTC Act
  §5; Equality Act 2010, Conduct of Employment Agencies and Employment
  Businesses Regulations 2003 reg. 27, Copyright and Rights in Databases
  Regulations 1997; AGG §11, UWG §5, UrhG §§87a–87e.
