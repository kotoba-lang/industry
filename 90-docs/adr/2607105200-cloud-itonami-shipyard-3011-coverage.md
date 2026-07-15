# ADR-2607105200: `cloud-itonami-isic-3011` (shipbuilding) deepened to `:implemented` — first classic heavy-industry manufacturing vertical

- Status: Accepted (2026-07-10)
- Related: ADR-2607080800 (`3030` aerospace manufacturing), ADR-2607091800
  (`0810` quarry), ADR-2607081400 (`3512` energy), fleet governed-actor
  pattern ADRs, langgraph ADR-0001

## Context

Classic heavy industry (steel 2410 / engines-turbines 2811 / shipbuilding
3011) sat in `kotoba-lang/industry` as `:maturity :spec` placeholders with
dead `gftdcojp/cloud-itonami-C####` URLs — no repo, no business model, no
actor. Aerospace (`3030`) and semiconductor fab (`2610`) already cover
high-end manufacturing; shipbuilding is the first **classic heavy-industry**
discrete manufacturing vertical (hull blocks, weld/NDT, class evidence).

## Problem

A shipyard's block-dispatch / class-evidence workflow bundles:

1. **Jurisdiction class-rules correctness** — official citation from a
   real maritime/class authority (MLIT/ClassNK, USCG/ABS, MCA/LR, BSH/DNV),
   never fabricated.
2. **Block dimensional-tolerance sufficiency** — fleet two-sided range
   check family after testlab / conservation / water / aerospace.
3. **NDT-defect resolution** — unconditional evaluation so `:ndt/screen`
   itself can HARD-hold.
4. **Dual high-stakes actuation** — robot block dispatch and class-evidence
   issuance, both human-gated, both double-actuation guarded via dedicated
   booleans (6492 lesson).

## Decision

1. Create public OSS repo `cloud-itonami/cloud-itonami-isic-3011` with
   **Shipyard Advisor ⊣ Shipyard Governor** (`shipyard.*` namespaces),
   modeled on `cloud-itonami-isic-3030`.
2. Promote registry entry `"3011"` from `:spec` → `:implemented`, replace
   dead C3011 URL with the real repo.
3. Business model, operator guide, and child ADR-0001 live in the child
   repo; this superproject ADR records fleet-level bookkeeping.

## Consequences

(+) Classic heavy-industry manufacturing gains a forkable governed stack.
(+) Industry registry honesty: no more dead C3011 placeholder for 3011.
(−) Physical yard digital-twin geometry remains a follow-up (giemon-factory
style domain data), out of scope for this promotion.
(−) Class-society catalog seeds four jurisdictions only.

## Verification

- Child: `clojure -M:dev:test` (36 tests / 176 assertions green)
- Child: `clojure -M:lint` clean
- Industry: `(industry/maturity "3011")` → `:implemented`
- GitHub: https://github.com/cloud-itonami/cloud-itonami-isic-3011
