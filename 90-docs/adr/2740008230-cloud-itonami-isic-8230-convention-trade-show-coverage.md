# ADR-2740008230: cloud-itonami-isic-8230 — Organization of Conventions and Trade Shows Coverage

## Status

Accepted. `cloud-itonami-isic-8230` promoted from `:spec` (stale
placeholder in `kotoba-lang/industry` registry, pointing at a
never-created `gftdcojp/cloud-itonami-N8230` repo — 404 confirmed for
both that placeholder and the real `cloud-itonami` org target before any
work began) to `:implemented` in the registry.

## Context

ISIC Rev.5 8230 (Organization of conventions and trade shows) is a
Wave 2 (coordination/logistics/trade, ADR-2607121000) target — the final
batch of Wave 2's 4-digit gaps (8 classes dispatched together, this
class among them). Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began, per this fleet's
ID/name-mismatch caution: the live `{:id "8230" ...}` entry's `:name` is
exactly "Organization of conventions and trade shows". A separate
3-digit group entry `{:id "823" ...}` also exists in the registry (repo
`nil`, `:maturity :spec`) — left untouched, per this fleet's standing
rule that group entries are a distinct, redundant registry artifact.

**Scope**: convention/trade-show event-operations COORDINATION ONLY
(booking/exhibitor/attendance data logging, venue-booking/floor-plan/
logistics scheduling, event-vendor — catering/AV/security-staffing —
procurement coordination, crowd-safety/venue-occupancy-limit concern
flagging) — NOT direct venue-occupancy-limit-override finalization, and
NOT direct fire-code-compliance or emergency-egress-compliance clearance
finalization/issuance. This vertical carries a distinct crowd-safety/
venue-capacity dimension (fire-code capacity limits, emergency-egress
planning) not present in the retail/logistics siblings this batch also
covers, so its governor adds a HARD, structural exclusion for that
territory rather than merely gating it behind approval.

Mirrored, module-for-module, on the fleet's verified-working reference
shape `cloud-itonami-isic-4719` (Other retail sale in non-specialized
stores) — same `Advisor ⊣ Governor` StateGraph pattern, same four-check
governor shape (target-entity unverified / counterparty-vendor
unverified / effect-not-propose / scope-exclusion), same Phase 0→3
rollout, same append-only ledger — substantially adapted for the
event-organization domain: `store`→`venue` (renamed `eventops.store`'s
protocol methods accordingly), `sales-record`→`event-record`,
`staffing-operation`→`venue-operation`, `supply-order`→`vendor-order`,
`loss-prevention-concern`→`safety-concern`.

## Decision

Publish `cloud-itonami/cloud-itonami-isic-8230` as a public OSS actor
repo:

- **Advisor**: `EventOperationsAdvisor` (`eventops.advisor`) — sealed
  intelligence node, closed four-op allowlist, `:effect :propose` always.
- **Governor**: `EventOperationsGovernor` (`eventops.governor`,
  blueprint keyword `:event-operations-governor` — confirmed unique via
  `gh api search/code -f q="event-operations-governor org:cloud-itonami"`,
  zero collisions, checked against all sibling agents landing
  concurrently in this same Wave-2 final batch).
- **Closed proposal-op allowlist** (all `:effect :propose`):
  - `:log-event-record` — booking/exhibitor/attendance data logging.
  - `:schedule-venue-operation` — venue-booking/floor-plan/logistics
    scheduling proposal.
  - `:coordinate-vendor-order` — event-vendor (catering, AV, security
    staffing) procurement proposal.
  - `:flag-safety-concern` — surfaces an occupancy-limit/fire-code/
    emergency-egress-planning concern for human triage. ALWAYS
    escalates (`always-escalate-ops`), and is never a member of any
    rollout phase's `:auto` set (`eventops.phase/phases`) — two
    independent layers agree, neither is a rollout milestone still to
    come.
- **Four HARD governor checks** (`eventops.governor/check`), all
  permanent and un-overridable by human approval:
  1. `venue-unverified` — the target venue's business registration +
     organizer license must exist AND be independently
     `:registered?`/`:verified?` in the store before ANY proposal for
     it may commit or even escalate.
  2. `vendor-unverified` — for `:coordinate-vendor-order` only, the
     named event-vendor must exist AND be independently
     `:registered?`/`:verified?`. The flagship genuinely-new
     counterparty-verification gate for this vertical, same "ground
     truth, not self-report" discipline as check 1, reapplied to the
     event-vendor supply chain.
  3. `effect-not-propose` — any `:effect` other than `:propose` is a
     HARD block (a claim to directly actuate/commit outside
     governance).
  4. `scope-excluded` / `op-not-allowed` — a proposal outside the
     closed four-op allowlist, OR one whose op/summary/rationale/cites/
     value touches directly finalizing a venue-occupancy-limit override
     or directly finalizing/issuing a fire-code-compliance or
     emergency-egress-compliance clearance, is a HARD, PERMANENT block.
     Per this task's explicit crowd-safety/venue-capacity-dimension
     requirement: **no op in the closed allowlist directly finalizes a
     venue-occupancy-limit override or a fire-code/emergency-egress-
     compliance clearance** — that authority structurally does not
     exist in this actor's op surface, and `:flag-safety-concern`
     (the closest related op) is a hard-permanent-block-adjacent,
     always-escalate op, never auto-commit-eligible in any phase.
- **Scope-exclusion term phrasing (known bug-class mitigation)**: every
  `eventops.governor/scope-excluded-terms` entry is phrased as the
  finalization/execution ACTION (e.g. "finalize the occupancy-limit
  override", "issue the fire-code clearance"), never a bare noun like
  "occupancy limit" or "fire code" — a bare noun would self-trip the
  actor's own legitimate `:flag-safety-concern` default proposal text,
  a self-blocking bug class multiple sibling agents in this fleet
  independently discovered and fixed. Dedicated regression test
  `eventops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  asserts every default mock-advisor proposal for every allowed op
  never trips `:scope-excluded` or `:op-not-allowed`.
- **Staged Phase 0→3 rollout** (`eventops.phase`): 0 read-only, 1
  event-record logging only (approval-gated), 2 + venue-operation/
  vendor-order proposals (approval-gated), 3 auto-commits clean
  high-confidence low-cost proposals (`:flag-safety-concern` and
  high-cost `:coordinate-vendor-order` always escalate regardless of
  phase).
- **Store** (`eventops.store`): `MemStore`, string-keyed `venues`/
  `vendors` directories, append-only `ledger` + `coordination-log`.
- **Runtime**: portable `.cljc` throughout (cljs-first, no JVM-only
  interop), `langgraph-clj` StateGraph, `interrupt-before
  #{:request-approval}` for human-in-the-loop.

## Consequences

- Positive: a ninth ISIC class in the cloud-itonami fleet moves from
  `:spec` to `:implemented`, with its own genuinely novel event-vendor
  counterparty-verification gate and a structurally-absent
  venue-safety-clearance-finalization authority (not merely a
  human-approval gate — the op does not exist in the allowlist at all).
- Positive: reuses the fleet's verified `Advisor ⊣ Governor` StateGraph
  shape with zero net-new architecture, keeping the fleet's actor
  pattern uniform.
- Neutral: `MemStore` is the only backend today (matches every sibling
  actor's current maturity) — a Datomic/kotoba-server-backed `Store`
  implementation is a follow-up, not required for `:implemented`.
- Follow-up (explicitly out of scope for this R0, not a gap): real
  ticketing/badge-scanning-system integration, facility-management/CAD
  floor-plan-system integration, real supplier-ordering-system
  integration, and — permanently, structurally out of scope, never a
  follow-up — directly finalizing any venue-occupancy-limit override or
  fire-code/emergency-egress-compliance clearance (that authority stays
  with the venue's own licensed fire-marshal/safety-official process,
  outside this system).

## Verification

- `clojure -M:test` in the actor repo: `Ran 56 tests containing 166
  assertions. 0 failures, 0 errors.` (raw output captured in this
  session; re-verified after registry merge from a fresh clone).
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:run` (demo driver `eventops.sim`): full StateGraph walk
  through all four ops across all Phase 0→3 scenarios, including the
  HARD-hold `out-of-scope?` regression scenario (advisor rationale
  polluted with "finalized the occupancy-limit override and issued the
  fire-code clearance" text) — correctly triggers `:scope-excluded`,
  zero uncaught exceptions in the full run.
- `gh api search/code -f q="event-operations-governor org:cloud-itonami"`
  → zero collisions against sibling actors landing concurrently in this
  same batch.
- Repo: <https://github.com/cloud-itonami/cloud-itonami-isic-8230>
  (commit `712777b39fa8c72e39cce66f453aa53cf12e2ac2` on `main`).
- Registry: `kotoba-lang/industry`
  `resources/kotoba/industry/registry.edn`, `{:id "8230" ...}` block
  edited in place to `:maturity :implemented`, correct `:repo`/
  `:business-id`, referencing this ADR.
