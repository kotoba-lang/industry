# ADR-2607106100: product ↔ party join via kotoba-lang/product-party + cloud-itonami

**Status**: accepted  
**Date**: 2026-07-10  
**Deciders**: Jun Kawasaki

## Context

Product identity, company master data, and classification registries were
already split across orgs:

| Concern | Owner |
|---|---|
| Trade-item identity (GTIN) | etzhayyim GTIN actor / uchiwake |
| Listed companies (`org.corp.*`) | etzhayyim kabuto |
| Commodity UNSPSC (8-digit) | etzhayyim `20-actors/unspsc` |
| Segment open-business blueprints | `kotoba-lang/unspsc` + `cloud-itonami-unspsc-*` |
| Industry open-business blueprints | `kotoba-lang/industry` + `cloud-itonami-isic-*` |
| Engine supplier / procurement | `itonami.store` (aerospace lifecycle) |
| Tender ↔ vendor match | `kotoba-lang/goyoukiki` |

**Missing:** a first-class, portable **product ↔ party edge** that
cloud-itonami operators can use without re-implementing uchiwake⊣kabuto, and
without putting company/product masters into kotoba-lang registries.

Uchiwake already prototypes `brand-owner` / supplier / carrier links into
kabuto, but that lives only in etzhayyim. Operators running open-business
blueprints and procurement needed the same join in the
kotoba-lang / cloud-itonami control plane.

## Decision

### 1. `kotoba-lang/product-party` — pure join contract (Apache-2.0)

A portable `.cljc` library that owns **only the edge**:

```edn
{:party.product/id "pp.gtin.05449000000996.brand-owner.org.corp.us.coca-cola"
 :party.product/product "gtin.05449000000996"
 :party.product/party   "org.corp.us.coca-cola"
 :party.product/role    :brand-owner
 :party.product/unspsc  "50202301"
 :party.product/status  :active
 :party.product/sourcing :authoritative}
```

Roles (closed set): `:brand-owner` · `:manufacturer` · `:assembler` ·
`:supplier` · `:distributor` · `:merchant` · `:carrier` · `:operator`.

Also provides:

- GTIN-14 normalize + mod-10 check digit
- id validators (`gtin.*` / `prod.*` / `org.corp.*` / `sup-*` / `merchant.*`)
- UNSPSC segment extract (bridge to `kotoba-lang/unspsc`)
- pure graph: `bind` / `revoke` / `parties-of` / `products-of` / `brand-owner`
- bridges: `party→itonami.supplier`, `itonami.supplier→party`,
  `product→goyoukiki unspsc-tags`
- demo graph aligned with uchiwake seed products + kabuto company ids

**Explicit non-goals:** not a GTIN master, not a company registry, not a
commodity table, not a code-keyed blueprint fleet (ADR-2607031800 / 2607031700).

### 2. `cloud-itonami.product-party` — operator runtime

In `gftdcojp/cloud-itonami`:

- tenant graph store (atom over pure graph)
- high-stakes gate: `:brand-owner` / `:manufacturer` binds & revokes require
  `:approve-high-stakes? true` (human-gate, same spirit as CertGovernor)
- `sync-suppliers-into-itonami!` — project parties into `itonami.store`
  supplier records for procurement genealogy
- `procurement-line-for` — build engine procurement lines from product+party
- `open-business-hints` — emit `cloud-itonami-unspsc-{segment}` and
  `cloud-itonami-isic-{code}` routing hints from a product

### 3. Boundary with etzhayyim

| If you need… | Call… |
|---|---|
| Authoritative product identity / BOM | etzhayyim gtin / uchiwake |
| Authoritative listed company graph | etzhayyim kabuto |
| Authoritative commodity classification | etzhayyim `20-actors/unspsc` XRPC |
| Operator join + procurement projection | **this ADR** |

## Consequences

- (+) Operators can connect products and companies in cloud-itonami without
  forking etzhayyim graphs or inventing parallel id spaces.
- (+) UNSPSC segment + ISIC from the join route cleanly into existing
  open-business blueprint families.
- (+) itonami.store supplier/procurement and goyoukiki tags get a single
  upstream projection path.
- (−) Tenant graphs can diverge from etzhayyim SSoT; honesty tags
  (`:authoritative | :representative | :synthesized`) and external refresh
  remain operator responsibility.
- (−) Workspace effect kinds (`:product-party/bind` etc.) are specified but
  not yet wired into `workspace.cljc` projection (follow-up; bind API is
  callable directly today).

## Artifacts

- lib: `kotoba-lang/product-party` (`orgs/kotoba-lang/product-party`)
- runtime: `gftdcojp/cloud-itonami` ns `cloud-itonami.product-party`
- tests: product-party unit suite + cloud-itonami.product-party-test

## References

- ADR-2607031700 — UNSPSC segment blueprints vs commodity actor
- ADR-2607031800 — GTIN functional blueprints (not code-keyed)
- ADR-2606302300 — org taxonomy (kotoba-lang libs / etzhayyim actors / gftdcojp apps)
- etzhayyim product-bom-ontology / public-company-ontology (uchiwake ⊣ kabuto)
- cloud-itonami ADR-0020 — catalog → match → share lifecycle (implementation)

---

## Addendum 1 (2026-07-10) — catalog procurement maturity loop closed

A recurring maturity pass (cockpit + edge + CLI) landed the operator path
that was only sketched in the Decision/Consequences of this ADR.

### Landed chain (gftdcojp/cloud-itonami)

| Stage | What |
|---|---|
| Catalog UI | keiei 製品・当事者 tab: coverage, goyoukiki candidates, procurement draft |
| Propose match | `POST …/procurement/propose-match` → `:procurement/propose-match` (`:read-only`) |
| Approve match | goyoukiki `:match/propose`; tool holds `:goyoukiki/match` |
| Propose share | `POST …/procurement/propose-share` from executed match; fail-closed on re-propose |
| Approve share | `:external-send`; tayori draft (never-sent) + delivered? |
| State contract | `matchedEffects` / `sharedEffects` on `GET …/state` |
| Overlay fix | client `:product-party` preserved across `fetch-state!` on catalog tab |
| CLI / doctor | `ops-commands` SSoT; `nbb procurement-match-and-share`; readiness next-action |
| Phase-1 resume | draft runners resume after outer approve (no stuck escalate) |

Representative SHAs (main history; pins advanced on superproject west.yml):

- share outcome + full-loop e2e → earlier series through `528d6339`
- state GET matched/shared tests → `029cd921`
- catalog overlay preserve → `15cf7d3b`

### SSoT surfaces

- `cloud-itonami.product-party/ops-commands`, `share-lifecycle`,
  `share-outcome-view`, `share-effect-id-for-match`
- Implementation ADR: **cloud-itonami ADR-0020**

### Still open (not part of this addendum)

- `:product-party/bind` workspace projection (unchanged from original (−))
- Doctor staged next-actions (match vs share separately)
- Hosted live E2E with real CACAO (unit/edge/portable gates only)

### Loop closure

Product-party maturity `/loop` (30m `next`) **stopped** 2026-07-10 after
catalog overlay + ADR documentation. Further work is on-demand, not
scheduled.
