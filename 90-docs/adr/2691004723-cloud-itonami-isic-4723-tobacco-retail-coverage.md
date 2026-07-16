# ADR-2691004723: cloud-itonami-isic-4723 — Specialized Tobacco Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4723` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4723` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 4723 (Retail sale of tobacco products in specialized stores)
is a Wave 2 (coordination/logistics/trade, ADR-2607121000) target.
Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "4723" ...}` entry's `:name` is
exactly "Retail sale of tobacco products in specialized stores" —
distinct from specialized food retail (ISIC 4721, already
`:implemented`) and stand-alone beverage retail (ISIC 4722, expected to
be built by a sibling agent in this same batch). No mismatch found.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-4721` (Specialized Food Retail Operations
Coordination)'s verified module shape (advisor/governor/phase/operation/
store/sim, `langgraph-clj` StateGraph, independent Governor, phase 0→3
rollout, string-keyed store directory, append-only audit ledger).
Domain-adapted for specialized tobacco retail stores — tobacconists,
cigar lounges, pipe & tobacco shops, and vape/e-cigarette specialty
retailers: inventory/sale/return sales-record logging, floor-staff
staffing scheduling, inventory-procurement supply-order coordination,
and compliance-concern flagging (suspected age-verification failures,
regulatory concerns) — never finalizing an age-verification override,
directly operating/controlling an age-verification/ID-scanning terminal,
or suspending/revoking a tobacco retail license. Tobacco retail carries a
direct age-verification/regulatory-compliance dimension (a heavily
regulated product), so this actor is held to the same
never-finalizes-the-gated-decision guardrail every sibling
consumer-facing regulated-product actor in this fleet applies.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/return data logging
- `:schedule-staffing-operation` — floor-staff scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-compliance-concern` — surface an age-verification-failure/regulatory-compliance concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Per the age-verification guardrail this fleet applies to every
consumer-facing regulated-product domain: tobacco retail has a direct
age-verification/regulatory-compliance dimension, so the closed op
allowlist NEVER includes any op that directly finalizes an
age-verification override — those are always either a hard permanent
block (see check 3) or, for the one "flag a concern" op, an
always-escalate op, never an auto-commit-eligible op in any phase's
`:auto` set.

1. **Store unverified** — the target store's record (business
   registration AND tobacco retail license) must exist in the store AND
   be independently `:registered?`/`:licensed?` before any proposal for
   it may commit or even escalate. Re-derived from the store's own store
   record every time, never from the proposal's own `:store-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches finalizing an age-verification override, directly operating/
   controlling an age-verification/ID-scanning terminal, or
   tobacco-retail-license suspension/revocation/licensing-authority
   enforcement (inspection clearance, excise/customs enforcement,
   compliance enforcement), is a permanent, un-overridable block.
   Evaluated **unconditionally** on every proposal via a lower-cased
   substring scan of the proposal's own content (English + Japanese term
   list) — never trusting the advisor's own framing. This is also the
   sole authority that can ever act on an age-verification override
   proposal — and it never grants one; it only ever hard-blocks it, with
   no auto-commit-eligible or human-approval-eligible path around this
   block.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "finalize the age-verification override", "control the age
   verification terminal"), never as a bare noun (bare "age" or bare
   "verification") that could accidentally match inside this same
   namespace's own default mock-advisor disclaimer text for a
   legitimate, allowed proposal. Concretely,
   `tobaccoretailops.advisor`'s own default `:log-sales-record` and
   `:schedule-staffing-operation` rationale strings legitimately discuss
   age verification in Japanese without finalizing or actuating it
   ("...年齢確認の実施可否については関与しない" /
   "...年齢確認端末の設定変更は行わない") — the exclusion term list uses
   full action phrases ("年齢確認を確定" / "年齢確認端末の直接操作") that
   are deliberately NOT substrings of those disclaimer sentences. A
   dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` absent
   from its violations, before this build was considered done. Two
   additional domain-specific tests
   (`no-allowed-op-finalizes-age-verification` /
   `flag-compliance-concern-is-always-escalate-never-auto`) assert
   structurally that no allowlisted op reads as a finalization action and
   that `:flag-compliance-concern` is a permanent member of
   `always-escalate-ops`.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-compliance-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $750 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`tobaccoretailops.phase`'s 0→3 rollout table independently agrees:
`:flag-compliance-concern` is never a member of any phase's `:auto` set,
at any phase — two layers, not one, enforce the same invariant
(exercised directly by `compliance-concern-holds-when-not-enabled` /
`compliance-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate` before
the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`tobaccoretailops.store` (MemStore, string-keyed store directory),
`tobaccoretailops.advisor` (TobaccoRetailAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
age-verification-override-finalization scope content so the governor's
scope scan can be exercised end to end), `tobaccoretailops.governor`
(TobaccoRetailGovernor), `tobaccoretailops.phase` (0→3 rollout),
`tobaccoretailops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`tobaccoretailops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4723" ...}` block only, not appended, not touching any other
  entry): `:repo`/`:business-id` de-placeholdered from
  `https://github.com/gftdcojp/cloud-itonami-G4723` / `cloud-itonami-G4723`
  to `https://github.com/cloud-itonami/cloud-itonami-isic-4723` /
  `cloud-itonami-isic-4723`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 4721
  pattern of `:robotics true` in `blueprint.edn` for retail-adjacent
  domains).
- Actor repo `cloud-itonami/cloud-itonami-isic-4723` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`
  (commit `c1fe57a22ba0e57290158d7c880cc5287baaa253`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 47 tests containing 133 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`tobaccoretailops.sim` demo) walked all scenarios
  (phase-1 approval-gated commit, phase-3 auto-commit for the three
  non-safety/low-cost ops, always-escalating compliance-concern flag,
  always-escalating over-threshold supply order, and all four HARD-hold
  scenarios: unregistered store, unlicensed store, non-`:propose`
  effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-4721/` (module-shape mirror, ADR-2670004721 —
  verified working reference for the specialized-retail-actor pattern,
  including the same self-trip-avoidance discipline)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4723"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
- ADR-2670004721 (ISIC-4721 specialized food retail coordination —
  scope-exclusion self-trip fix precedent this actor also applies)
