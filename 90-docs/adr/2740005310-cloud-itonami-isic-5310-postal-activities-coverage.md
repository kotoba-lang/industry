# ADR-2740005310: cloud-itonami-isic-5310 — Postal Activities Operations Coordination

## Status

Accepted. `cloud-itonami-isic-5310` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-H5310` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 5310 (Postal activities) is a Wave 2
(coordination/logistics/trade, ADR-2607121000) target — the final batch
of Wave 2's 4-digit gaps. Identity independently verified against a
fresh clone of `kotoba-lang/industry` before any work began, per this
fleet's ID/name-mismatch caution: the live `{:id "5310" ...}` entry's
`:name` is exactly "Postal activities" — no mismatch found. The
redundant 3-digit group entry `{:id "531" ...}` (`:repo nil`) was left
untouched, as instructed.

**Legally and constitutionally distinct from every freight/parcel-
transport sibling in this fleet** (e.g. ISIC 4923 road freight
transport, ISIC 5012/5022 water freight transport, ISIC 5120 air
freight transport, ISIC 5210 warehousing): mail carries a strong
PRIVACY/confidentiality expectation in most jurisdictions (postal
secrecy / the inviolability of correspondence — statutory or
constitutional protection against opening, reading or disclosing sealed
mail contents without independent legal authority). The template was
mirrored on `cloud-itonami-isic-4791`'s verified module shape (advisor/
governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directories,
append-only audit ledger — itself a "coordination only, no physical
storefront" pattern judged the closest structural fit among this
fleet's existing coordination-style actors) — but the domain, not just
the naming, was substantially adapted:

- The primary gate is a **postal-facility/carrier-license verification**
  check (a registered, independently-verified sorting facility or
  delivery-route carrier, additionally required to hold an active
  postal-carrier license), not a seller/merchant-account or physical-
  store-verification check.
- This class carries a **postal-secrecy/privacy dimension** that is
  categorically different in KIND (not just detail) from every sibling
  freight/transport actor's PHYSICAL-SAFETY exclusion in this batch: the
  closed op allowlist NEVER includes any op that directly finalizes a
  mail-content-inspection decision, a mail-interception authorization,
  or a contents-based delivery-refusal determination. That territory is
  a HARD, permanent, un-overridable block — there is no proposal shape
  in the closed allowlist that could ever legitimately reach it, at any
  confidence, at any phase, under any human approval. The only op that
  may touch adjacent territory at all (`:flag-security-concern`, a
  PHYSICAL-SAFETY intake-screening OBSERVATION — unusual weight, leaking
  substance, suspicious protrusion, handled per standard postal-security
  protocol, explicitly NOT a content-inspection finalization) ALWAYS
  escalates to a human and can never become auto-commit-eligible at any
  rollout phase.

**Scope**: postal sorting/routing-logistics OPERATIONS COORDINATION
ONLY, NOT direct mail-content-inspection, interception, or
contents-based delivery-refusal authority.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-item-record` — mail-item intake/sort/delivery-status metadata
  logging (sender/recipient/tracking only, NEVER content)
- `:schedule-route-operation` — sorting-facility/delivery-route
  scheduling proposal
- `:coordinate-facility-order` — sorting-equipment/facility-maintenance
  procurement proposal
- `:flag-security-concern` — surface a suspicious/hazardous-item-at-
  intake concern per standard postal-security protocol — **ALWAYS
  escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out. CRITICAL: no op that directly finalizes
a mail-content-inspection decision, a mail-interception authorization, or
a contents-based delivery-refusal determination is EVER a member of this
allowlist, by design — this is a PRIVACY/legal-authority exclusion, not
a rollout milestone still to come.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Postal-facility/carrier unverified** (POSTAL-SPECIFIC PRIMARY GATE)
   — the target sorting facility or delivery-route carrier's record must
   exist AND be independently `:registered?`, `:verified?` AND
   `:license-active?` before any proposal for it may commit or even
   escalate. A facility that is registered and verified but whose
   carrier license is not currently active (e.g. pending renewal) is
   treated exactly like an unregistered facility — HARD hold, not a
   lesser caution. Re-derived from the facility's own record every time,
   never from the proposal's own `:facility-id` claim.
2. **Vendor unverified** — for `:coordinate-facility-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that
   resolves to an independently `:registered?`/`:verified?` equipment/
   maintenance vendor record. A missing vendor-id, or one that resolves
   to an unregistered/unverified vendor, is a HARD block (reused from
   the 47xx template's supply-order-verification pattern — equipment/
   maintenance procurement is not postal-specific, but remains necessary
   since a postal operator still procures from vendors).
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** (POSTAL-SECRECY/PRIVACY DIMENSION — categorically
   distinct from the physical-safety exclusions used by sibling
   freight/transport actors this batch) — any proposal (regardless of
   op) outside the closed allowlist, or whose rationale/summary/
   citations/draft value touches directly finalizing a mail-content-
   inspection decision, a mail-interception authorization, or a
   contents-based delivery-refusal determination, is a permanent,
   un-overridable block. Evaluated **unconditionally** on every proposal
   via a lower-cased substring scan of the proposal's own content
   (English + Japanese term list) — never trusting the advisor's own
   framing. This is a PRIVACY/legal-authority exclusion: this actor
   structurally never rules on mail contents AT ALL, safety-motivated or
   otherwise, at any phase, under any approval — distinct in kind from a
   sibling freight actor's cargo-safety-inspection-finalization
   exclusion, which excludes only a specific *safety* ruling while still
   permitting other contents-adjacent logistics.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/authorization
   ACTION (e.g. "finalized the mail-content-inspection decision",
   "authorized the mail interception", "refused delivery based on the
   item's contents"), never as a bare noun (bare "mail", "contents" or
   "inspect") that could accidentally match inside this same namespace's
   own default mock-advisor text. This mattered concretely here: the
   legitimate `:flag-security-concern` proposal's whole job is to
   describe an observed exterior anomaly at intake — its default
   rationale was deliberately written to avoid even the standalone words
   "inspect"/"intercept"/"傍受"/"開封"/"内容" entirely (not merely to
   avoid the exact excluded phrases), and a dedicated advisor-level
   regression test (`security-concern-rationale-never-claims-content-
   determination` in `advisor_test.clj`) asserts this vocabulary
   discipline directly, in addition to the standard governor-level
   regression test. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done. A companion sanity test,
   `out-of-scope-injection-still-trips-scope-exclusion`, confirms the
   check is not accidentally a no-op by asserting the advisor's
   `:out-of-scope?` test hook (which appends finalization-action
   language) DOES trip `:scope-excluded`. The governor keyword
   (`:postal-ops-governor`) and the `postalops` namespace were confirmed
   unique across the `cloud-itonami` org via `gh api search/code` before
   any code was written, given 7 sibling actors landing concurrently in
   this exact batch.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-security-concern` — always, regardless of confidence. Can never
  be promoted to auto-commit-eligible at any rollout phase.
- `:coordinate-facility-order` above a $1500 estimated-cost threshold —
  always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`postalops.phase`'s 0→3 rollout table independently agrees:
`:flag-security-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant
(exercised directly by `security-concern-holds-when-not-enabled` /
`security-concern-escalates-when-enabled`). The high-cost facility-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate`
before the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-facility-order` never applies to an over-threshold order
(exercised by `high-cost-facility-order-always-escalates` /
`low-cost-facility-order-auto-commits`).

### 3. Module shape

`postalops.store` (MemStore, string-keyed facility AND vendor
directories — two distinct registries, not one; facility records carry
a third boolean dimension, `:license-active?`, the postal-specific
adaptation absent from the mail-order template's `:payment-processor-
linked?` field), `postalops.advisor` (PostalOpsAdvisor, mock + a
real-LLM seam, plus an `:out-of-scope?` test hook that deliberately
drafts mail-content-inspection/interception-scope content so the
governor's scope scan can be exercised end to end), `postalops.governor`
(PostalOpsGovernor), `postalops.phase` (0→3 rollout), `postalops.operation`
(the `langgraph-clj` StateGraph: intake → advise → govern → decide →
commit | hold | request-approval), `postalops.sim` (demo driver,
`clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "5310" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "531" ...}` group entry):
  `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-H5310` / `cloud-itonami-H5310`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-5310` /
  `cloud-itonami-isic-5310`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :logistics]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-5310` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`
  (`bf48ce092fcb059a8a0907fed2eb511a2af7f832`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 62 tests containing 186 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`postalops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three non-security-
  flag/low-cost ops, always-escalating security-concern flag,
  always-escalating over-threshold facility order, and six HARD-hold
  scenarios: unregistered facility, unverified facility, facility
  registered+verified but whose carrier license is NOT active,
  unverified facility-order vendor, non-`:propose` effect, scope-
  excluded content) without error.

## References

- `cloud-itonami-isic-4791/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows;
  domain substantially adapted per the postal-secrecy-specific guidance
  above, not copied verbatim)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"5310"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2706004791 (cloud-itonami-isic-4791 — the mirrored structural
  template)
