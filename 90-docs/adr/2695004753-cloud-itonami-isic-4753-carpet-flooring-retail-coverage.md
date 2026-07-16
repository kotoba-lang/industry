# ADR-2695004753: cloud-itonami-isic-4753 — Carpet, Rug and Floor-Covering Specialized-Store Retail Operations Coordination

## Status

Accepted. `cloud-itonami-isic-4753` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-G4753` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 4753 (Retail sale of carpets, rugs, wall and floor coverings
in specialized stores) is a Wave 2 (coordination/logistics/trade,
ADR-2607121000) target — Wave 3 and Wave 4 class-level scope having
already reached completion in this fleet, Wave 2 is the next phase of the
reverse-toposort rollout plan. Identity independently verified against a
fresh clone of `kotoba-lang/industry` before any work began, per this
fleet's ID/name-mismatch caution: the live `{:id "4753" ...}` entry's
`:name` was found genuinely truncated in the registry (a pre-existing
seed-data bug affecting roughly 10% of entries) as `"Retail sale of
carpets, rugs, wall and floor coverings in s..."` — an exact prefix match
of the assigned class name "Retail sale of carpets, rugs, wall and floor
coverings in specialized stores", de-truncated as part of the normal
exact-block registry edit below. The separate 3-digit group entry `{:id
"475" ...}` ("Retail sale of other household equipment in specialized
s...") is a distinct, redundant registry artifact and was left untouched.
No ID/name mismatch found.

**Scope**: carpet/rug/flooring-retail OPERATIONS COORDINATION, NOT direct
pricing-authority or quality-dispute-resolution control. Mirrored closely
on the sibling `cloud-itonami-isic-4719` (other retail sale in
non-specialized stores)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed store directory,
append-only audit ledger). Domain-adapted for specialized flooring
storefronts: transaction/inventory/return sales-record logging,
installation/delivery scheduling, flooring-material supply-order
coordination with a registered/verified vendor, and quality-concern
flagging (defective material, failed/disputed installation work) — never
setting or overriding a shelf/unit price, and never finalizing a
quality-dispute resolution (issuing a refund, approving/denying a
warranty claim, determining final liability for a defective-material or
bad-installation dispute).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-sales-record` — inventory/sale/installation-order data logging
- `:schedule-installation-operation` — installation/delivery scheduling proposal
- `:coordinate-supply-order` — inventory procurement proposal
- `:flag-quality-concern` — surface a defective-material/installation-dispute concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Store unverified** — the target store's record (business
   registration) must exist in the store AND be independently
   `:registered?`/`:verified?` before any proposal for it may commit or
   even escalate. Re-derived from the store's own store record every
   time, never from the proposal's own `:store-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that resolves
   to an independently `:registered?`/`:verified?` vendor record in the
   store. A missing vendor-id, or one that resolves to an unregistered/
   unverified vendor, is a HARD block — a supply-chain
   counterparty-verification gate mirroring the sibling 47xx
   retail-coordination actors (e.g. ISIC 4719/4752).
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a quality-dispute resolution (issuing a
   refund, approving/denying a warranty claim, determining final
   liability for a defective-material or bad-installation dispute), is a
   permanent, un-overridable block. Evaluated **unconditionally** on
   every proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the advisor's
   own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "issued a refund resolution", "approved the warranty claim
   resolution", "determined final liability for"), never as a bare noun
   (bare "refund", "warranty", "dispute" or "quality concern") that could
   accidentally match inside this same namespace's own default
   mock-advisor text. This mattered concretely here: `flooringops.advisor`'s
   own printed `:op` keyword for the legitimate concern-flagging proposal
   literally contains the substring "quality-concern"
   (`:flag-quality-concern`), and its default rationale legitimately
   discusses "欠陥品・施工トラブル・品質紛争の観察事実の報告" (defective
   material/installation-dispute/quality-dispute observation reporting) —
   a bare-noun term list would have self-tripped this actor's own core
   happy path on every single run. A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` and
   `:op-not-allowed` absent from its violations, before this build was
   considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-quality-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $1500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`flooringops.phase`'s 0→3 rollout table independently agrees:
`:flag-quality-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant (exercised
directly by `quality-concern-holds-when-not-enabled` /
`quality-concern-escalates-when-enabled`). The high-cost supply-order
escalate gate requires no extra phase-layer code: the governor's own
`high-stakes?` already turns the base disposition into `:escalate` before
the phase gate runs, so phase 3's `:auto` membership for
`:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Module shape

`flooringops.store` (MemStore, string-keyed store AND vendor
directories — two distinct registries, not one), `flooringops.advisor`
(FlooringRetailAdvisor, mock + a real-LLM seam, plus an `:out-of-scope?`
test hook that deliberately drafts quality-dispute-resolution-
finalization-scope content so the governor's scope scan can be exercised
end to end), `flooringops.governor` (FlooringRetailGovernor),
`flooringops.phase` (0→3 rollout), `flooringops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit |
hold | request-approval), `flooringops.sim` (demo driver, `clojure
-M:run`).

Test suite written directly against this domain's checks (no
mirrored-repo bug carried over): all 56 tests passed on the first
`clojure -M:dev:test` run, including the dedicated self-trip regression
test verifying every default mock-advisor proposal for every allowed op
never trips `:scope-excluded` or `:op-not-allowed`.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "4753" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "475" ...}` group-level entry):
  `:name` de-truncated to the full "Retail sale of carpets, rugs, wall
  and floor coverings in specialized stores", `:repo`/`:business-id`
  de-placeholdered from `https://github.com/gftdcojp/cloud-itonami-G4753`
  / `cloud-itonami-G4753` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-4753` /
  `cloud-itonami-isic-4753`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated to match the actor state machine
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :retail]`, matching the sibling ISIC 4719/4752
  pattern).
- Actor repo `cloud-itonami/cloud-itonami-isic-4753` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 56 tests containing 166 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`), independently re-verified against a fresh
  clone with the same result. `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:run` (`flooringops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three
  non-dispute-resolution/low-cost ops, always-escalating quality-concern
  flag, always-escalating over-threshold supply order, and five HARD-hold
  scenarios: unregistered store, unverified store, unverified supply-order
  vendor, non-`:propose` effect, scope-excluded content) without error.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the pure-coordination actor pattern this actor follows)
- `cloud-itonami-isic-4752/` (ADR-2691004752 — the immediately-preceding
  Wave 2 47xx sibling, same coordination-only shape and the same
  scope-exclusion self-trip precedent this actor also applies)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"4753"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
