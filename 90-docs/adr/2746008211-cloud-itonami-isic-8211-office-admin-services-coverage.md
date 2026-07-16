# ADR-2746008211: cloud-itonami-isic-8211 — Combined Office Administrative Service Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8211` promoted from `:spec` (the live
`{:id "8211" ...}` entry already carried a stale placeholder `:repo`/
`:business-id` pointing at a never-created `gftdcojp/cloud-itonami-N8211`
repo — 404 confirmed for both that placeholder and the real
`cloud-itonami` org target before any work began) to `:implemented` in
the `kotoba-lang/industry` registry.

## Context

ISIC Rev.5 8211 (Combined office administrative service activities) is
one of the final 4-digit gaps of Wave 2 (coordination/logistics/trade,
ADR-2607121000). Identity independently verified against a fresh clone
of `kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "8211" ...}` entry's `:name` is
exactly "Combined office administrative service activities" — not
truncated, no mismatch found. A separate, coarser-granularity `{:id
"821" ...}` 3-digit group entry ("Office administrative and support
activities") exists in the registry and was deliberately left untouched
(only the `"8211"` class-level block was edited).

**Domain**: ISIC 8211 covers outsourced back-office bundling — mail
handling, reception, billing, records management, filing — provided as
a combined service to client businesses. This carries a direct
client-confidentiality/data-handling dimension distinct from this
fleet's retail/commerce verticals: an office-administration-services
provider routinely holds a client's confidential business records
(invoices, correspondence, filings) without any authority to decide
what happens to them. Per this batch's own guardrail for that dimension,
the closed op allowlist NEVER includes any op that directly finalizes
disclosure of a client's confidential records/data to a third party, or
finalizes a legal/financial decision on the client's behalf — always a
hard permanent block, never auto-commit-eligible. This actor coordinates
ADMINISTRATIVE TASK SCHEDULING/LOGGING ONLY; it never makes a decision
on behalf of the client business.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-4719` (Other retail sale in non-specialized stores)
verified module shape (advisor/governor/phase/operation/store/sim,
`langgraph-clj` StateGraph, independent Governor, phase 0→3 rollout,
string-keyed client/vendor directories, append-only audit ledger).
Domain-adapted: `officeadminops.*` namespace in place of
`merchandiseops.*`, a client service-contract entity in place of a
store entity, task-record/service-operation ops in place of
sales-record/staffing-operation ops, and a confidentiality-concern flag
in place of a loss-prevention-concern flag. The `:coordinate-supply-order`
op (office-supplies/equipment procurement, HARD-gated on independent
vendor verification) is carried over unchanged from the reference
module, since the underlying supply-chain-counterparty-verification
pattern applies identically to an office-administration provider's own
office-supplies/equipment vendors.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-task-record` — administrative-task/document-processing
  completion data logging (mail handling, reception, billing, records
  management, filing)
- `:schedule-service-operation` — staffing/task-assignment scheduling
  proposal
- `:coordinate-supply-order` — office-supplies/equipment procurement
  proposal
- `:flag-confidentiality-concern` — surface a data-handling/
  confidentiality-breach-risk concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 4 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: four HARD checks (permanent, un-overridable), plus escalation

1. **Client contract unverified** — the target client's service-contract
   record must exist AND be independently `:registered?`/`:verified?`
   in the store before ANY proposal for it may commit or even escalate.
   Re-derived from the store's own record every time, never from the
   proposal's own `:client-id` claim.
2. **Vendor unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:vendor-id` that
   resolves to an independently `:registered?`/`:verified?` vendor
   record. A missing vendor-id, or one that resolves to an unregistered
   or unverified vendor, is a HARD block — the same supply-chain
   counterparty-verification gate `cloud-itonami-isic-4719` established.
3. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
4. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing disclosure of a client's confidential
   records/data to a third party, or making a legal or financial
   decision on the client's behalf, is a permanent, un-overridable
   block. Evaluated **unconditionally** on every proposal via a
   lower-cased substring scan of the proposal's own content (English +
   Japanese term list) — never trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class (independently
   discovered and fixed by multiple sibling actors): every
   scope-excluded term is phrased as the finalization/execution ACTION
   (e.g. "disclosed the client's confidential records to a third
   party", "made the financial decision on the client's behalf"), never
   as a bare noun (bare "confidential", "disclosure", "data" or
   "legal") that could accidentally match inside this same namespace's
   own default mock-advisor rationale text for a legitimate, allowed
   `:flag-confidentiality-concern` proposal (whose whole job is to talk
   about data-handling/confidentiality concerns, and whose own printed
   `:op` keyword literally contains the substring "confidentiality"). A
   dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op clears the governor with `:scope-excluded` absent
   from its violations; a companion sanity test,
   `out-of-scope-test-hook-does-trip-scope-exclusion`, confirms the
   advisor's own `:out-of-scope?` test hook genuinely does trip the
   check (so the term list is not vacuously non-matching) — both
   verified before this build was considered done.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-confidentiality-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $1000 estimated-cost threshold —
  always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`officeadminops.phase`'s 0→3 rollout table independently agrees:
`:flag-confidentiality-concern` is never a member of any phase's
`:auto` set, at any phase — two layers, not one, enforce the same
invariant (exercised directly by
`confidentiality-concern-holds-when-not-enabled` /
`confidentiality-concern-escalates-when-enabled` /
`confidentiality-concern-never-in-any-phase-auto-set`). The high-cost
supply-order escalate gate requires no extra phase-layer code: the
governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`).

### 3. Governor keyword / namespace collision check

`:office-admin-governor` and the `officeadminops` namespace were
checked via `gh api search/code` immediately before landing (zero hits
for `office-admin-governor`, `officeadminops`, and
`:office-admin-governor org:cloud-itonami`) — confirmed distinct across
the fleet, including the 7 sibling agents landing concurrently in this
same final Wave-2 4-digit-gap batch.

### 4. Module shape

`officeadminops.store` (MemStore, string-keyed `clients`/`vendors`
directories, append-only audit ledger + coordination log),
`officeadminops.advisor` (OfficeAdminAdvisor, mock + a real-LLM seam,
plus an `:out-of-scope?` test hook that deliberately drafts
client-confidential-disclosure/decide-on-behalf-of-client scope content
so the governor's scope scan can be exercised end to end),
`officeadminops.governor` (OfficeAdminGovernor),
`officeadminops.phase` (0→3 rollout), `officeadminops.operation` (the
`langgraph-clj` StateGraph: intake → advise → govern → decide → commit
| hold | request-approval), `officeadminops.sim` (demo driver,
`clojure -M:run`). All source is portable `.cljc` with no JVM-only
interop anywhere in `src/` (mock-only advisor, cljs-compatible).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "8211" ...}` block only, not appended, not touching any other
  entry, including the separate `{:id "821" ...}` 3-digit group entry
  which was left untouched): `:repo`/`:business-id` de-placeholdered
  from `https://github.com/gftdcojp/cloud-itonami-N8211` /
  `cloud-itonami-N8211` to
  `https://github.com/cloud-itonami/cloud-itonami-isic-8211` /
  `cloud-itonami-isic-8211`, `:maturity` `:spec`→`:implemented`,
  `:operating-states` updated from the stale placeholder
  `[:intake :register :match :dispatch :follow-up :audit]` to
  `[:intake :advise :govern :approve :commit :audit]` to match the
  actor's real StateGraph, matching sibling `cloud-itonami-isic-6310`'s
  own `:operating-states` for an `:implemented` actor
  (`:required-technologies` left as-is: `[:robotics :identity :forms
  :dmn :bpmn :audit-ledger :labor]`, already domain-appropriate for the
  staffing/task-assignment-scheduling dimension this actor covers;
  `:optional-technologies` left as `[]`).
- Actor repo `cloud-itonami/cloud-itonami-isic-8211` scaffolded (fresh —
  no prior repository existed at either the stale `gftdcojp` placeholder
  or the real `cloud-itonami` org target, both confirmed 404 before any
  work began) and pushed to `main`
  (commit `826aec2cbb8bcf3b6bd31e901d90f4fa2d42ca4f`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 57 tests containing 168 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test` and `clojure -M:dev:test`, identical), independently
  re-verified against a fresh clone with the same result.
  `clojure -M:lint`: 0 errors, 0 warnings. `clojure -M:dev:run`
  (`officeadminops.sim` demo) walked all scenarios (phase-1
  approval-gated commit, phase-3 auto-commit for the three
  non-confidentiality/low-cost ops, always-escalating
  confidentiality-concern flag, always-escalating over-threshold supply
  order, and all four HARD-hold scenarios: unregistered client,
  unverified client, unverified vendor, non-`:propose` effect, and
  scope-excluded content) without error or exception.

## References

- `cloud-itonami-isic-4719/` (module-shape mirror — verified working
  reference for the coordination-actor pattern, incl. the
  vendor-verification gate for `:coordinate-supply-order`; its own
  registry `:maturity` is `:implemented`, no specific ADR number cited
  here since it was not independently re-verified this session)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"8211"` entry (and the untouched, separate `"821"` 3-digit group
  entry)
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — Wave 2
  coordination/logistics/trade)
