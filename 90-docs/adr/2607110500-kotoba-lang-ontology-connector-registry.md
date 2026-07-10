# ADR-2607110500: kotoba-lang/ontology — object-type + connector provenance registry

**Status**: closed（Addendum 1 で実統合まで完了・検証済み）
**Date**: 2026-07-10
**Closed**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

Owner asked whether `cloud-itonami` has anything like Palantir. Comparison
found a real Foundry/AIP-shaped core already in place (`kotoba` datom log
projecting `activity → decision → effect → audit`, advisor-proposes /
independent-governor-gates pattern repeated across every
`cloud-itonami-isic-*` / `cloud-itonami-iso3166-*` blueprint, org/repo tenant
namespace per `docs/adr/0002-org-repo-tenant-isolation.md`) but also two real
gaps versus Foundry: no shared object-type vocabulary (each ingested source
invents its own ad hoc keyword shape) and no generalized external-data
connector foundation (only `kotoba-lang/goyoukiki`'s `jp.kkj`/`jp.geps`
existed, as a per-vertical embedded pattern, per
`90-docs/adr/2607070300-kotoba-lang-goyoukiki-jp-real-ingestion-connectors.md`).
Owner chose to build the data-integration/connector foundation and realize
it as an actual new child repo via the standard project-creation flow.

Researched existing conventions before designing: `manifest/repos.edn` has
no per-repo entry schema (repos are bare paths in `:extra-projects`);
`kotoba-lang` capability libraries (`robotics`, `phone`, `industry`,
`technology`) share a light skeleton (`LICENSE` Apache-2.0, `README.md`,
`deps.edn`, `.github/workflows/ci.yml`, `src/kotoba/<name>`, matching
`test/`) with no governance boilerplate and no `docs/adr/` — that heavier
skeleton belongs to `cloud-itonami-*` business blueprints, not capability
libraries. `kotoba-lang/technology`'s `resources/kotoba/technology/registry.edn`
is the fleet-wide SSoT mapping a `:required-technologies` keyword (`:robotics`,
`:phone`, `:audit-ledger`, ...) to the repo(s) implementing it; that is where
a new technology keyword must be registered for blueprints to ever declare
it. `blueprint.edn` already has a stable shape
(`:itonami.blueprint/required-technologies [...]`) confirmed against
`cloud-itonami-isic-6120` and `cloud-itonami-iso3166-jpn`.

## Decision

**Add `kotoba-lang/ontology`, a capability library (not a
`cloud-itonami-*` business blueprint), and register `:ontology` in
`kotoba-lang/technology`'s registry.**

1. `kotoba.ontology` — object-type registry + pure structural check.
   Seeded with exactly one object type, `:tender`, generalizing
   `goyoukiki.model/opportunity`'s coarse common denominator (`:status`).
   No speculative taxonomy: further object types are added only when a real
   connector needs one.
2. `kotoba.ontology.connector` — connector registry (id → object type →
   owning repo → maturity) plus `tag`, a pure function stamping an
   already-normalized fact with `:ontology/type` / `:ontology/source` /
   optional `:ontology/fetched-at` / `:ontology/confidence`. Seeded with the
   two connectors that already exist and already work:
   `kotoba-lang/goyoukiki`'s `jp.kkj` and `jp.geps`. Neither namespace
   performs I/O — fetch/parse stays in the connector's own JVM-only adapter,
   exactly where `2607070300` already put it; this repo only indexes that
   pattern and gives its output a common, checkable provenance tag.
3. Skeleton mirrors `kotoba-lang/robotics`/`phone`/`technology`: Apache-2.0
   `LICENSE`, `README.md` (including an explicit "Non-goals" section — not a
   pipeline/ETL builder, not a connector marketplace, not Gotham, not a
   replacement for a domain's own richer model), `deps.edn`
   (`:test`/`:lint` aliases, cognitect test-runner + clj-kondo, matching
   every sibling capability lib), `.github/workflows/ci.yml`. No
   `docs/adr/`, `GOVERNANCE.md`, `CONTRIBUTING.md`, `SECURITY.md`,
   `CODE_OF_CONDUCT.md` — that skeleton is for business blueprints, not
   capability libraries (confirmed against `robotics`/`phone`/`technology`,
   none of which carry it).
4. Registry data lives as an in-source `def` (not a `resources/*.edn` file
   read via `clojure.java.io/resource`/`slurp`), unlike
   `kotoba.industry`/`kotoba.technology`'s existing JVM-only pattern. Those
   two are grandfathered (CLAUDE.md: don't retroactively rewrite a working
   JVM-only lib once a higher-priority runtime exists), but this is new code
   and the monorepo's runtime-priority rule (kotoba wasm > clojurewasm >
   ClojureScript > nbb > JVM/bb last-resort) applies to it — an in-source
   data literal has zero I/O and is trivially portable to `:cljs`, so there
   is no reason to copy the JVM-only resource-loading shape into new code.
5. **Add one entry to `kotoba-lang/technology`'s
   `resources/kotoba/technology/registry.edn`** (`:id :ontology`, pointing
   at this repo) so `:ontology` becomes a nameable
   `:required-technologies` keyword — the only change needed to an existing
   repo, landed via an isolated worktree + server-side merge
   (`gh api .../merges`), never by editing the shared `orgs/kotoba-lang/technology`
   checkout in place (that checkout's branch changed under this session
   mid-task, confirming the multi-agent shared-checkout hazard
   `manifest/cleanup-workflow.md` warns about).
6. **Explicitly out of scope for this change** (fleet-wide follow-up, not
   done here): wiring individual `cloud-itonami-*` blueprints'
   `required-technologies` to include `:ontology`; adding `:ontology` to any
   `kotoba-lang/industry` per-ISIC entry; any further object types beyond
   `:tender`; any UI/export namespace (unlike `robotics`/`phone`, this R0
   ships no `.ui`/`.export` — add them if/when a consumer needs a dashboard
   or a CSV/JSON dump, not speculatively).

## Consequences

- (+) Closes the "no shared object-type vocabulary" and "no generalized
  connector foundation" gaps identified in the Palantir comparison, scoped
  honestly (seeded from what already exists and is tested, not invented
  ahead of a real need).
- (+) `kotoba-lang/goyoukiki`'s already-working connectors get a shared
  index and a common provenance tag without any change to `goyoukiki`
  itself — `kotoba.ontology.connector` only references them by URL/id.
- (+) 5 tests / 15 assertions, clj-kondo clean, verified locally before any
  commit.
- (−) Only one real object type (`:tender`) and two real connectors seeded;
  broader fleet coverage is deliberate follow-up, not delivered here.
- (−) No blueprint anywhere declares `:required-technologies [:ontology]`
  yet — this ADR only makes the keyword nameable via
  `kotoba-lang/technology`, adoption is separate follow-up work.
- (−) Still not Foundry-scale: no pipeline/ETL builder, no cross-org
  connector marketplace, no Gotham-style intel product. See the repo
  README's "Non-goals" section.

## References

- `90-docs/adr/2607070300-kotoba-lang-goyoukiki-jp-real-ingestion-connectors.md`
  (the real connectors this registry indexes)
- `90-docs/adr/2607070200-kotoba-lang-goyoukiki-procurement-signal-actor.md`
- `gftdcojp/cloud-itonami`'s `docs/adr/0002-org-repo-tenant-isolation.md`
  (the tenant namespace this ontology projects into)
- `kotoba-lang/technology`'s `resources/kotoba/technology/registry.edn` (the
  fleet-wide technology-keyword SSoT this ADR adds one entry to)

## Addendum 1 (2026-07-10): real integration into kotoba-lang/goyoukiki

Owner asked to raise maturity further. Checked `teian`/`tayori` (goyoukiki's
downstream `match` consumers per its own docstring) as candidates for a
`:required-technologies [:ontology]` declaration — both are still spec-only
(no `src/` at all), so wiring either would not be a real adoption. Chose a
more substantive integration instead: `kotoba-lang/goyoukiki` itself now
depends on `kotoba-lang/ontology` and calls
`kotoba.ontology.connector/tag` on the opportunity map inside both
`jp.kkj/ingest!` and `jp.geps/ingest!`, right before `operation/register!` —
every fact registered from either connector now carries `:ontology/type
:tender` and `:ontology/source (:jp.kkj or :jp.geps)`.

`->opportunity` itself (the directly unit-tested mapping fn in both
namespaces) is untouched — tagging happens only at the `ingest!` boundary,
which was already documented as real-network-only and outside the existing
test suite, so this is non-breaking by construction. Added one new test per
connector (`ingest-tags-opportunity-with-ontology-provenance`) verifying the
composed `connector/tag` output against the same real captured fixtures the
existing tests already use, and asserting
`kotoba.ontology.connector/tagged-conforms?` is true. Full suite: 45 tests /
170 assertions green (was 41/150 before goyoukiki's own maturity work since
ADR-2607070300 added more; this addendum adds 2 tests / 20 assertions to
that count), clj-kondo clean. Landed via isolated worktree + server-side
merge (`gh api repos/kotoba-lang/goyoukiki/merges`), same as the rest of
this ADR's changes.

This resolves the "no blueprint anywhere declares `:required-technologies
[:ontology]`" gap in a stronger form than originally scoped: not a business
blueprint's declarative metadata, but the connector library itself
consuming the ontology tag in real, tested code. Moves `kotoba-lang/ontology`
from "registered, zero real consumers" to "registered, one real consumer".
Still open: `teian`/`tayori` remain spec-only so cannot yet declare
`:required-technologies [:ontology]` themselves; no `cloud-itonami-*`
blueprint or `kotoba-lang/industry` entry references `:ontology` yet;
`:tender` is still the only object type.
