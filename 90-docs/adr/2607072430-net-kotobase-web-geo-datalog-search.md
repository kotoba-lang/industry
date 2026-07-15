# ADR-2607072430: net-kotobase web.*/geo.* — webpage + maps search compiled onto the datomic.* plane

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki
**Repo**: gftdcojp/net-kotobase (landed on main `67b8f6d8058e`; detailed ADR lives in-repo as `docs/adr/2607072330-web-geo-datalog-search.md`)

## Context

kotobase.net (net-kotobase edge Worker) had three query families —
datomic.\* (Datalog), graph.\* (SPARQL/quads), kg.\* (ontology-shaped hybrid
search) — but no content-type surfaces: webpage search and maps/place search
did not exist (確認 2026-07-07: maps/webpage 相当の surface はゼロ)。オーナー
指示: 「では設計実装してください. maps, search なども datomic 系をベースに.」

## Decision

Add four tenant NSIDs to net-kotobase that are pure *compilers onto the
existing datomic.\* plane* — no new pod API, no new index/storage, no new
auth model:

- `web.ingest` / `geo.ingest` → caller-supplied webpage text / places become
  `:webpage/*` / `:place/*` datoms via the **same tenant-cap
  datomic.transact CACAO gate** (graph bound to `kotobase/db/<did>/<db_name>`,
  defaults `webpages` / `places`; the edge does not crawl — no SSRF surface).
- `web.search` / `geo.search` → one `:limit 1000`-capped map-form `datomic.q`
  Datalog candidate scan + edge-side ranking (web: ASCII+CJK-bigram tokens,
  coverage-dominant deterministic score, snippets — Japanese works without a
  segmenter; geo: bbox containment or haversine radius with `distance_m`).
- `webpages` / `places` join the Clojure-owned well-known schema authority
  (`kotobase.schema`, `:webpage/url` / `:place/id` unique-identity);
  `datomic.listDatabases` now advertises four well-known graphs.
- Generated Datalog is data-patterns-only: kotoba-datomic predicate/fulltext
  clause support is unverified, so push-down is a documented follow-up lift
  (lexicons/response shapes won't change; `candidates` field makes the
  1000-row recall cap observable).

Pure cores `kotobase.web` / `kotobase.geo` / `kotobase.rows` (clj/cljs/nbb
portable, tested); route glue `kotobase.domain-search` (cljs, mirrors
`kotobase.datomic` style); lexicons `contracts/lexicons/ai/gftd/apps/
kotobase/{web,geo}/{ingest,search}.json`; worker bundle rebuilt and
boundary-tested against the built artifact (8/8).

## Superproject reflection

- west pin advance: `net-kotobase` `ad0397dcf24d` → `67b8f6d8058e`
  (GitHub API single-entry commit on manifest/west.yml, server-side merge —
  no local shallow merge, no rebase, no force-push).

## Findings (out of scope, reported)

- The shared checkout `orgs/gftdcojp/net-kotobase` is stale AND carries ~4
  unpushed local commits on `main` (`475fa6a` tip, not on GitHub — landing
  page / deps work). Not touched; needs reconcile via the cleanup runbook.
- Pre-existing on net-kotobase main (fail identically on pristine main,
  unrelated to this change): `worker/test/explore_test.cljc`,
  `worker/test/pin_test.cljc`, the full `nbb scripts/test.cljc` runner
  invariant (reads `worker/package.json`, absent from the repo), and
  `scripts/check-metadata.cljc` (kotoba submodule paths).
