# ADR-2607032000: cloud-itonami insurance (ISIC 65/66) + real-estate (ISIC 68) coverage push

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

A coverage audit of `cloud-itonami` (ADR-2607011000's ISIC Rev.5 blueprint
fleet, tracked in `kotoba-lang/industry`'s registry) found:

- **Insurance** (division 65: life/non-life insurance, reinsurance, pension
  funding; division 66 group 662: risk evaluation, insurance agents/brokers,
  other insurance auxiliary): **0 of 7 relevant classes** had a published
  blueprint repo. All 7 sat at `:spec` (registry entry only), and their
  `:required-technologies` borrowed `:banking`'s capability id and
  `:operating-states` as an unedited placeholder (`:intake :onboard :transact
  :clear :settle :audit` — a banking lifecycle, not an insurance one).
- **Real estate** (division 68): `6810` (real-estate agency, own/leased
  property) already had a published blueprint repo (`cloud-itonami-L6810`,
  ADR-2607011000) but no deeper actor implementation — still `:blueprint`
  maturity, same tier as a freshly-scaffolded repo. `6820` (real estate on a
  fee or contract basis — brokerage/management for others) was `:spec` only.
- No `:insurance` capability existed in `kotoba-lang/technology`'s registry;
  the 7 insurance classes had nothing real to point `:required-technologies`
  at, which is why they borrowed `:banking`.

## Decision

### 1. New capability lib: `kotoba-lang/insurance`

A pure `.cljc` capability library (Apache-2.0), same shape as
`kotoba-lang/property`/`labor`/`retail`: policy / premium / claim /
underwriting-decision pure-data contracts + validation, a read-only
`ui.cljc` operator dashboard (no write surface), `export.cljc` (CSV/JSON),
tests. No real actuarial tables — this is a forkable scaffold an operator
fills with their own licensed actuarial data, not a source of actuarial
authority itself. Registered in `kotoba-lang/technology`'s registry as
`:insurance` (layer `:finance/insurance`).

### 2. Eight blueprint repos (`:spec` → `:blueprint`)

| ISIC | Name | Repo |
|---|---|---|
| 6511 | Life insurance | `cloud-itonami-6511` |
| 6512 | Non-life insurance | `cloud-itonami-6512` |
| 6520 | Reinsurance | `cloud-itonami-6520` |
| 6530 | Pension funding | `cloud-itonami-6530` |
| 6621 | Risk and damage evaluation | `cloud-itonami-6621` |
| 6622 | Activities of insurance agents and brokers | `cloud-itonami-6622` |
| 6629 | Other activities auxiliary to insurance and pension funding | `cloud-itonami-6629` |
| 6820 | Real estate activities on a fee or contract basis | `cloud-itonami-6820` |

Each publishes directly under the `cloud-itonami` org (public, AGPL-3.0-or-later),
per ADR-2607012100's standing note that new blueprint repos no longer need to
transit `gftdcojp` first. Each follows the existing blueprint-tier template
(`README.md` / `blueprint.edn` / `docs/business-model.md` /
`docs/operator-guide.md` / `GOVERNANCE.md` / `CONTRIBUTING.md` /
`SECURITY.md` / `CODE_OF_CONDUCT.md` / `LICENSE`), corrected per class (not
a copy-paste of an unrelated vertical's governance text — a defect found in
some earlier-batch repos, e.g. `cloud-itonami-K6419`'s `SECURITY.md` still
reads "This project handles HR and talent-management workflows").

The 7 insurance entries' `:required-technologies` swap the placeholder
`:banking` for the new `:insurance` capability, and `:operating-states`
change from the banking placeholder to an insurance lifecycle: `:intake
:underwrite :bind :endorse :claim :settle :audit` (loss-adjustment/brokerage
classes use the subset that applies to their actual activity).

### 3. `6810` deepened to `:implemented`

`cloud-itonami-L6810` (real-estate agency) is deepened from blueprint-only
to a real actor, same pattern as `cloud-itonami-M6910` (company
incorporation) and `cloud-itonami-6310` (HR/talent) — the 3rd
`:implemented` entry in the registry:

- **Realtor-LLM** (sealed advisor) proposes listing intake, buyer/tenant
  matching, offer drafting and closing-readiness checks; never itself
  executes a transaction.
- **RealtorGovernor** (independent) gates on spec-basis (per-jurisdiction
  disclosure/title requirements, citation-backed), fraud/sanctions hold,
  document-complete, confidence floor, and a hard **actuation gate**.
- **Actuation invariant**: title transfer and escrow/fund disbursement are
  never a member of any phase's `:auto` set and always escalate to human
  sign-off in the governor's high-stakes check — the same two-independent-
  layers shape as `formation.phase`/`formation.governor` in `M6910`.
- `realty.store` (MemStore + append-only audit ledger), `realty.registry`
  (listing/offer/closing draft records), `realty.facts` (per-jurisdiction
  disclosure/title requirement catalog with citations, honest coverage
  reporting — seed a handful of jurisdictions, not a global claim),
  `realty.phase` (0→3), `realty.operation` (langgraph-clj StateGraph).

### 4. Registry hygiene

`kotoba-lang/industry`'s registry entries for all 8 classes get their
`:repo` URL corrected from the stale `gftdcojp/cloud-itonami-*` placeholder
to the actual `cloud-itonami/cloud-itonami-*` URL now that the repo exists,
and `6810`'s `:maturity` flips from `:blueprint` to `:implemented`.

## Consequences

- (+) Insurance coverage: 0/7 blueprinted → 7/7 blueprinted (still 0
  `:implemented` — a blueprint repo is a scaffold, not a governed actor;
  see ADR-2607011000's three-tier maturity model).
- (+) Real estate coverage: 1/2 blueprinted (0 implemented) → 2/2
  blueprinted, 1/2 (`6810`) implemented.
- (+) `kotoba-lang/technology` gains a real `:insurance` capability instead
  of insurance classes silently borrowing `:banking`'s id.
- (+) Fleet-wide maturity counts move from 1 implemented / 25 blueprint /
  617 spec to 2 implemented / 33 blueprint / 608 spec (out of 643 total).
- (-) The other insurance-adjacent classes still at `:spec`
  (central banking 6411, holding companies 6420/642, financial leasing
  6491/6492, fund management 663/6630, financial market administration
  6611, securities brokerage 6612) are unchanged — out of this ADR's scope
  per the user's explicit scoping.
- Manifest registration: `kotoba-lang/insurance` is added to
  `manifest/repos.edn`/`west.yml` (a real lib, same as `property`/`labor`).
  The 8 `cloud-itonami-*` blueprint/actor repos stay unregistered
  (standalone), per the existing convention (ADR-2607011000/2607012100).

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21 section
  coverage; three-tier maturity model; blueprint-repo template)
- ADR-2607012100 (cloud-itonami org split; new blueprints publish directly
  under `cloud-itonami`)
- ADR-2607031500 (`cloud-itonami-M6910`; Registrar-LLM ⊣ RegistrarGovernor
  pattern this ADR's `6810` deepening mirrors)
- `kotoba-lang/industry/docs/cloud-itonami.md` (maturity tier definitions)
- `kotoba-lang/property` (capability-lib template `kotoba-lang/insurance`
  follows)
