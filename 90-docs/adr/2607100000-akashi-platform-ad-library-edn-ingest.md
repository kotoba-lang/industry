# ADR-2607100000: akashi platform ad-library fixture ingest + EDN tx-data projection

## Status

Accepted.

## Context

`akashi` was already the correct actor for public ad-disclosure transparency,
but Meta/Facebook/Instagram and X/Twitter were only registry seeds. The missing
first implementation step was not live scraping; it was a reviewed, deterministic
adapter path that can take source-disclosed platform ad-library snapshots,
normalize them into akashi lexicons, and project the result as EDN tx-data for
Datomic/DataScript/kotoba import.

Live collection remains gated by source-policy review. No adapter may log in,
scrape behind anti-bot controls, use tracking pixels, or infer private targeting
profiles.

## Decision

1. Add `platform_ad_library_fixture_parser.py` for reviewed local Meta/
   Instagram and X ad-library fixture samples. It emits the same akashi record
   families as the regulator bulk parser: source policy, method note,
   disclosure snapshot, advertiser identity, landing evidence, creative
   disclosure, and delivery disclosure.
2. Add `edn_export.py` to project validated akashi records into deterministic
   DataScript/kotoba tx maps and a Datomic import bundle with schema plus
   scalar `:db/add` tx-data. This is deliberately pure: the caller chooses
   whether the EDN is written to git, DataLad/git-annex, or future
   kotoba-git/kotoba-rad storage.
3. Extend `dry_run_fixtures.py` so the local fixture suite includes regulator,
   Meta/Instagram, X, and closure fixtures. `--emit-edn` prints EDN tx-data.
4. Add `edn_query.cljc` so the emitted tx-data is immediately queryable by
   platform, advertiser, landing domain, and aggregate platform counts without
   requiring a live Datomic/DataScript instance. The helper also materializes
   the Datomic scalar tx bundle for the same queries.
5. Add `persist_fixture_edn.py` to materialize the tx-data as
   `*.tx.kotoba.edn`, the Datomic bundle as `*.datomic.edn`, and a storage
   manifest under `20-actors/akashi/data/`. The primary tx artifact carries a
   CIDv1 suitable for `:rad/holds-dataset`.
6. Declare the materialized fixture dataset in `akashi/manifest.edn`
   `:substrate :datasets`, then append the holding to
   `80-data/kotoba-rad/akashi.identity.journal.edn` with
   `bb rad:add-holding akashi --apply`.
7. Keep all live source runtimes disabled in `source-policy-reviews.seed.json`.
   Fixture parser coverage is evidence that the shape is implemented, not
   authorization to collect from platform services.

## Consequences

- Meta/Instagram and X now have concrete fixture parser coverage instead of
  registry-only coverage.
- The actor has EDN boundaries suitable for DataScript/kotoba query import and
  Datomic schema+tx import.
- DataLad/git/kotoba-rad persistence now has a materialized local handoff
  artifact, CIDv1 manifest, actor dataset declaration, and
  `80-data/kotoba-rad/akashi.identity.journal.edn` holding. The data artifacts
  were saved through `bb kotoba:annex save 20-actors/akashi/data`; because the
  artifacts are small text EDN, they are git-tracked rather than annexed.
- Verification: `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest tests/ -q`
  in `20-actors/akashi` passes 25 tests; `./20-actors/akashi/run_tests.sh`
  passes 24 CLJC tests / 100 assertions.

## References

- `orgs/etzhayyim/root/20-actors/akashi/adapters/platform_ad_library_fixture_parser.py`
- `orgs/etzhayyim/root/20-actors/akashi/adapters/edn_export.py`
- `orgs/etzhayyim/root/20-actors/akashi/adapters/edn_query.cljc`
- `orgs/etzhayyim/root/20-actors/akashi/adapters/persist_fixture_edn.py`
- `orgs/etzhayyim/root/20-actors/akashi/fixtures/platform_ad_library/`
- `orgs/etzhayyim/root/20-actors/akashi/tests/test_adapters.py`
