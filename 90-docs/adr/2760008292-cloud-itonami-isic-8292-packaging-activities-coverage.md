# ADR-2760008292: cloud-itonami-isic-8292 — Packaging Activities Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8292` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-N8292` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.4 8292 (Packaging activities) is one of the final 8 remaining
4-digit gaps in Wave 2 (coordination/logistics/trade, ADR-2607121000);
after this batch lands, Wave 2's 4-digit class-level scope is fully
complete. Identity independently verified against a fresh clone of
`kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "8292" ...}` entry's `:name`
("Packaging activities") is unambiguous and matches the assigned class
exactly, no truncation. The live entry's `:required-technologies`
carried a stray `:labor` tag and `:operating-states` carried a
leftover `[:intake :register :match :dispatch :follow-up :audit]`
labor-marketplace-shaped placeholder inconsistent with a
packaging-activities actor — both corrected as part of this exact-block
registry edit (see Decision §5). The separate 3-digit group entry `{:id
"829" ...}` does not currently exist in the live registry and was not
created or touched.

**Scope**: contract/third-party-packaging OPERATIONS COORDINATION, NOT
direct package-integrity-safety-clearance or food/pharma-labeling-
compliance authority. Mirrored closely on the sibling
`cloud-itonami-isic-4752` (retail sale of hardware, paints and glass in
specialized stores)'s verified coordination-only module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj` StateGraph,
independent Governor, phase 0→3 rollout, string-keyed directories,
append-only audit ledger). Domain-adapted for contract-packaging
operators (bottling, blister-packing, gift-wrapping, bulk-to-retail
repackaging — frequently for FOOD and PHARMACEUTICAL clients where
package-integrity/tamper-evidence and labeling accuracy are
safety-critical): production batch/run/quantity data logging,
packaging-line/staffing scheduling, packaging-material supply-order
coordination with a registered/verified supplier, and
package-integrity/labeling-accuracy/contamination quality-concern
flagging — never finalizing a package-integrity-safety clearance
(certifying a package/batch as tamper-evident, clearing a batch for
release) and never a food/pharma-labeling-compliance sign-off (approving
or signing off on food/pharma label accuracy).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-production-record` — batch/run/quantity production data logging
- `:schedule-production-operation` — packaging-line/staffing scheduling proposal
- `:coordinate-supply-order` — packaging-material procurement proposal
- `:flag-quality-concern` — surface a package-integrity/labeling-accuracy/contamination concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 5 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: five HARD checks (permanent, un-overridable), plus escalation

1. **Facility unverified** — the target packaging facility's record
   (business registration + operating license) must exist AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or even escalate. Re-derived from the facility's own
   record every time, never from the proposal's own `:facility-id`
   claim.
2. **Contract unverified** — the client packaging-service contract the
   proposal is working under must ALSO exist AND be independently
   `:registered?`/`:verified?` — checked unconditionally on every op
   (not scoped to a single op), since contract packaging is always
   performed under a specific client engagement, never as a standalone
   facility action.
3. **Supplier unverified** — for `:coordinate-supply-order` ONLY, the
   proposal's own drafted `:value` must name a `:supplier-id` that
   resolves to an independently `:registered?`/`:verified?` supplier
   record in the store. A missing supplier-id, or one that resolves to
   an unregistered/unverified supplier, is a HARD block — a
   supply-chain counterparty-verification gate mirroring the sibling
   47xx retail-coordination actors' vendor checks.
4. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
5. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a package-integrity-safety clearance
   (certifying a package/batch as tamper-evident, clearing a batch for
   release, issuing a package-integrity safety clearance) OR a
   food/pharma-labeling-compliance sign-off (approving/signing off on
   food/pharma label accuracy, declaring a label regulatory-compliant),
   is a HARD, PERMANENT block. `:flag-quality-concern` itself is never
   excluded by this check — surfacing a package-integrity/labeling-
   accuracy/contamination concern for a human is exactly this actor's
   job; only FINALIZING/certifying/signing off on that concern is
   excluded.

Two ESCALATE (SOFT) gates: `:flag-quality-concern` ALWAYS escalates to a
human, regardless of confidence, and is never a member of any phase's
`:auto` set (two independent layers agree — the governor's own
`always-escalate-ops` and `packagingops.phase`'s own phase table); a
`:coordinate-supply-order` above a $2000 estimated-cost threshold also
always escalates. LLM confidence below the floor (0.6) also escalates.

### 3. Self-trip-avoidance regression test (known fleet-wide bug class)

Every `scope-excluded-terms` entry is phrased as the finalization/
execution ACTION (e.g. "certified the batch as tamper-evident", "signed
off on the food safety labeling compliance"), never a bare noun like
"tamper", "seal", "label" or "contamination" — a bare noun would
accidentally match inside this actor's own legitimate
`:flag-quality-concern` default proposal text, which legitimately
discusses seal/tamper-evidence/label/contamination concerns as
observations, and whose own printed `:op` keyword literally contains the
substring "quality". A dedicated regression test
(`packagingops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`)
asserts every op the default mock advisor can generate, with default
(non-`out-of-scope?`) request patches, never trips `:scope-excluded` or
`:op-not-allowed`. The governor keyword `:contract-packaging-governor`
and the `packagingops` namespace were both checked for fleet-wide
uniqueness via `gh api search/code` against `org:cloud-itonami` before
landing (0 hits for both).

### 4. Implementation

Real `langgraph-clj` StateGraph
(`intake -> advise -> govern -> decide -> commit | hold |
request-approval`) with `interrupt-before #{:request-approval}` for
human-in-the-loop resume, not a stub. Fully portable `.cljc` with no
JVM-only interop anywhere in `src/` (mock-only advisor; a real LLM would
plug into the same `Advisor` protocol). Modules: `packagingops.store`
(MemStore, string-keyed facility/contract/supplier directories,
append-only ledger), `packagingops.advisor` (contained intelligence
node), `packagingops.governor` (independent compliance layer),
`packagingops.phase` (staged 0→3 rollout), `packagingops.operation`
(the StateGraph), `packagingops.sim` (demo driver, `clojure -M:run`).

`clojure -M:test`: **63 tests / 199 assertions, 0 failures, 0 errors**,
independently re-verified against a fresh clone. `clojure -M:lint`
(clj-kondo): 0 errors, 0 warnings. `clojure -M:run` (packagingops.sim
demo) walked all scenarios (phase-1 approval-gated commit, phase-3
auto-commit for the three non-quality-concern/low-cost ops, always-
escalating quality-concern flag, always-escalating over-threshold supply
order, and five HARD-hold scenarios: unregistered facility,
unverified facility, unverified client contract, unverified supplier,
non-`:propose` effect, and scope-excluded content) without error.

### 5. Registry (`kotoba-lang/industry`)

`{:id "8292" ...}` promoted `:spec` → `:implemented`; `:repo`/
`:business-id` de-placeholdered from the stale
`gftdcojp/cloud-itonami-N8292` target to
`cloud-itonami/cloud-itonami-isic-8292` /
`cloud-itonami-isic-8292`; `:operating-states` corrected from a leftover
labor-marketplace-shaped `:spec` placeholder
(`[:intake :register :match :dispatch :follow-up :audit]`) to the actual
langgraph-clj node sequence (`[:intake :advise :govern :approve :commit
:audit]`); `:required-technologies` corrected by dropping the stray
`:labor` tag (`[:robotics :identity :forms :dmn :bpmn :audit-ledger]`,
matching what this actor genuinely uses — robotics for the physical
packaging-line premise, identity/forms/dmn/bpmn/audit-ledger for the
governed-actor pattern every sibling actor shares); `:optional-
technologies` left unchanged (`[]`). Landed via a git-trees/blobs API
single-file PUT direct to `main` (sha-checked optimistic concurrency
against a freshly re-fetched snapshot per this fleet's hot-contention
discipline on this shared registry file), exact-block edit only,
verified via a before/after diff scoped to only the `"8292"` entry plus
sample-verification that neighboring `"8291"`/`"8299"` entries were
untouched, no mojibake detected. `test/kotoba/industry_test.clj`'s
`maturity-summary-counts-tiers` `:implemented` count assertion bumped to
the true live-recomputed count via `(kotoba.industry/maturity-summary)`
immediately before the edit (see the registry PR/commit for the exact
before/after numbers, since this is this fleet's single hottest
concurrently-edited file and the count can drift between the time this
ADR is written and the time the registry PUT lands).

## Consequences

- ISIC 8292 (Packaging activities) now has a real, tested, governed
  actor — the coordination layer any physical packaging-line dispatch
  system could eventually feed proposals into, always gated by the
  independent `ContractPackagingGovernor`.
- Wave 2's 4-digit class-level rollout scope (ADR-2607121000) is fully
  complete as of this batch (the last 8 remaining gaps, including this
  one, landed together).
- No op in the closed allowlist, and no phase's `:auto` set at any
  rollout stage, can ever finalize a package-integrity-safety clearance
  or a food/pharma-labeling-compliance sign-off — that authority
  structurally does not exist in this actor's vocabulary and stays with
  a qualified human/regulatory authority outside it.

## References

- ADR-2607121000 — cloud-itonami global ISIC/ISCO reverse-toposort
  rollout plan (Wave 2 definition)
- ADR-2691004752 — cloud-itonami-isic-4752 (reference module-shape
  mirror: coordination-only actor, vendor/supplier-verification pattern)
