# ADR-2607122000: `gftdcojp/cloud-murakumo-market-intel` — industry market-size EDN dataset, Datomic ⊣ DataScript query parity

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: new repo `gftdcojp/cloud-murakumo-market-intel`

## Context

Visual Capitalist's *"All of the World's Money and Markets in One
Visualization" (2022 edition)* puts money supply, precious metals,
cryptocurrencies, capital markets, real estate, global debt, derivatives
and private wealth on one common (USD) scale. The source page itself
(`visualcapitalist.com/all-of-the-worlds-money-and-markets-in-one-visualization-2022/`)
returned HTTP 403 to every fetch path tried (direct `WebFetch`, `r.jina.ai`
reader proxy, several third-party mirrors that turned out to reproduce
older 2016/2020 editions instead) and the Chrome extension was not
connected, so the infographic's exact rendered numbers could not be
verified. The owner chose, when asked, to source each figure directly
from the primary providers Visual Capitalist itself cites, rather than
transcribe unverifiable numbers from a blocked page.

`cloud-murakumo` (Sora, ADR-2606272300) already needs market sizing for
its own GTM case (ADR-2607030030 `murakumo-inference-economy-gtm`), and
the "record it once, query it from every runtime" shape recurs across
this monorepo's actors (`talent.store` in `gftd-talent-actor`: a `Store`
protocol satisfied by both a `MemStore` and a `DatomicStore` backed by
`langchain.db`, a Datomic-API-compatible EAV store, proven equal by a
shared contract test). This dataset generalizes that same shape one
level further: **the same schema and the same Datalog queries must
return the same answer whether the backend is a real Datomic-API store
(server-side, `langchain.db` — can point at Datomic Local or a
kotoba-server pod) or DataScript (client-side/browser, zero-dep)**.

## Decision

- New repo: **`gftdcojp/cloud-murakumo-market-intel`** (private, per
  `repos.edn :orgs :visibility`). Family-named alongside `cloud-murakumo`
  (Sora, rents GPU) and `local-murakumo` (bundles your own fleet): this
  one **records and serves market-size facts**, consumed by the GTM case
  and by the new `cloud-murakumo-market-analyst` actor
  (ADR-2607122030).
- **Flat EAV schema, no refs.** Every market-size fact is one entity:
  `:market/id` (unique keyword identity), `:market/label`, `:market/group`
  (`:money` / `:precious-metals` / `:capital-markets` / `:real-assets` /
  `:derivatives` / `:wealth`), `:market/value-usd` (double), optional
  `:market/value-usd-low` / `:market/value-usd-high` for range estimates,
  optional `:market/quantity` + `:market/quantity-unit` +
  `:market/unit-price-usd` when a figure is *derived* (gold/silver: physical
  stock × spot price, both recorded so the USD figure is recomputable, not
  a bare transcribed number), `:market/as-of`, `:market/confidence`
  (`:measured` / `:estimated` / `:derived`), `:market/source`,
  `:market/source-url`, `:market/note`. No entity refs needed — flat
  attributes only, which is precisely the case DataScript needs least help
  with (`:db/valueType :db.type/ref` is the one thing DataScript treats
  specially; everything else is unenforced but declared for Datomic-side
  strictness and self-documentation).
- **Two backends behind one `Store` protocol**
  (`src/market_intel/store.cljc`), same shape as `talent.store`:
  - `LangchainDbStore` — `langchain.db/create-conn` + the shared schema.
    Datomic-API-compatible; swappable to a real Datomic Local or
    kotoba-server pod without touching a query.
  - `DataScriptStore` — `datascript.core/create-conn` + the *same* schema
    map (DataScript accepts the same `:db/valueType`/`:db/cardinality`
    shape). Runs offline, zero-dep, browser-native — the natural query
    surface for a CLJS cockpit per the repo-wide runtime priority
    (`kotoba wasm > clojurewasm > ClojureScript > nbb`, JVM/bb last-resort).
  - A shared `test/market_intel/store_contract_test.cljc` runs the same
    Datalog (`by-group`, `total-by-group`, `largest`, `category`) against
    both and asserts identical results — the same "swap the backend, not
    the query" property `gftd-talent-actor` already proves for its own
    domain.
- **Every figure is individually cited** (`:market/source` +
  `:market/source-url` + `:market/as-of` + `:market/confidence`), not one
  blanket "Visual Capitalist" citation — because the infographic itself
  could not be verified, each number's actual primary provenance (CIA
  Factbook-derived aggregate via Visual Capitalist's own 2022 estimate for
  M0/M1/M2, SIFMA for stock/bond markets, BIS for OTC derivatives, World
  Gold Council tonnage × LBMA close for gold, The Silver Institute tonnage
  × 2022 avg price for silver, CoinGecko for crypto, Savills for real
  estate, IIF-derived for global debt, Forbes for billionaire wealth,
  Credit Suisse/UBS for global wealth) is the thing that's actually
  auditable. `:market/confidence :derived` flags the two figures (gold,
  silver, global debt) that are computed/inferred rather than lifted
  verbatim from a single report, so a consumer (e.g. the market-analyst
  actor's Governor) can treat them with appropriately lower trust.

## Consequences

- Adding a new market-size fact is a 1-entity EDN map + a schema check,
  not a code change — `nbb`/`clj` scripts can regenerate `market-size-2022.edn`
  from a future dataset (e.g. a 2024/2026 edition) by the same shape.
- Because the schema has no refs, the DataScript backend never needs
  component/cascade-delete semantics; this keeps the browser-side store
  trivially small (no wasm bridge needed to query it — plain CLJS).
- The dataset intentionally does **not** claim to reproduce
  visualcapitalist.com's exact rendered pixel-values (that page could not
  be verified). Anyone diffing this dataset against the live infographic
  should expect the same order of magnitude per category, not a byte-exact
  match; the `:market/note` field documents where a figure is a derivation
  rather than a direct quote.
- Follow-up (not done by this ADR): west manifest registration
  (`nbb scripts/gen-west-manifest.cljs --entry cloud-murakumo-market-intel`),
  `gh repo create` + push (gftdcojp, private).

## Related

- ADR-2606272300 (`cloud-murakumo` — GPU cloud, Sora).
- ADR-2607041302 (murakumo family naming: cloud-murakumo / local-murakumo split).
- ADR-2607030030 (`murakumo-inference-economy-gtm`).
- ADR-2607122030 (`cloud-murakumo-market-analyst` actor, this dataset's consumer).
- `gftd-talent-actor/src/talent/store.cljc` — the `Store` protocol / contract-test
  pattern this design generalizes to a second backend pair.
