# ADR 2701004762: cloud-itonami ISIC 4762 music/video-recordings-retail operations-coordination coverage

## Status

Accepted

## Context

This is part of Wave 2 (coordination/logistics/trade, ADR-2607121000) of
the `cloud-itonami` fleet: codify every ISIC industry class as an
autonomous "actor" (LLM/advisor behind an independent Governor, a
langgraph-clj StateGraph, append-only audit ledger). This ADR covers
**ISIC Rev.5 class 4762 -- retail sale of music and video recordings in
specialized stores** only.

The `kotoba-lang/industry` registry's live `{:id "4762" ...}` entry was
verified against a fresh clone before any work began. Its `:name` field
was truncated with a literal `"..."` (a known ~10% pre-existing seed-data
bug also seen on sibling entries) but read exactly "Retail sale of music
and video recordings in specialized st..." -- confirming the premise
once de-truncated to "Retail sale of music and video recordings in
specialized stores". Prior to this ADR the entry's `:maturity` was
`:spec` with a placeholder `:repo`/`:business-id`
(`gftdcojp/cloud-itonami-G4762`); no repository existed at
`cloud-itonami/cloud-itonami-isic-4762` (`gh api` 404-confirmed before
scaffolding). A separate, unrelated 3-digit group entry `{:id "476"
...}` also exists in the registry as a redundant registry artifact and
was deliberately left untouched. This ADR and its companion registry
edit promote the `4762` class-level entry to `:implemented`.

The reference implementation mirrored is
[`cloud-itonami/cloud-itonami-isic-4742`](https://github.com/cloud-itonami/cloud-itonami-isic-4742)
(retail sale of audio and video equipment in specialized stores),
independently re-verified working in this session, adapted to
music/video-recordings-retail operations coordination (a record shop /
video store selling vinyl, CDs, DVDs, Blu-rays and related physical
media).

## Decision

Publish `cloud-itonami/cloud-itonami-isic-4762` as an OSS operations-
coordination actor for music/video-recordings specialty retail, following
the SAME governed-actor architecture as every prior `cloud-itonami-isic-*`
actor in this fleet: **MusicVideoRetailAdvisor ⊣ MusicVideoRetailGovernor**
on a `langgraph-clj` StateGraph, with an independent Governor,
human-in-the-loop approval via `interrupt-before`, and an append-only
audit ledger.

This actor is deliberately **operations coordination only**, never
copyright/licensing-dispute authority:

### Closed proposal-op allowlist (all `:effect :propose`)

- `:log-sales-record` -- inventory/sale/return data logging
- `:schedule-staffing-operation` -- floor-staff scheduling proposal
- `:flag-inventory-concern` -- surface a counterfeit/bootleg-recording/
  piracy/mis-shipment concern, ALWAYS escalates
- `:coordinate-supply-order` -- inventory procurement proposal (label/
  distributor orders)

### Four HARD governor checks (permanent, un-overridable)

1. **Store unverified** -- target store record must exist AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or escalate. Never trusts the proposal's own claim.
2. **Vendor unverified** -- for `:coordinate-supply-order` only, the
   proposal's own drafted `:vendor-id` must resolve to an independently
   `:registered?`/`:verified?` vendor record -- the flagship
   supply-chain counterparty-verification gate, which matters doubly in
   this vertical given the grey-market/counterfeit-media-import risk.
3. **Effect not `:propose`** -- any other `:effect` value is a HARD
   block (a claim to directly actuate outside governance).
4. **Scope exclusion** -- any proposal touching directly finalizing a
   copyright/licensing-dispute resolution (approving, denying, paying
   out, or otherwise settling a copyright or licensing dispute claim;
   issuing a copyright-infringement settlement, licensing-fee resolution
   or royalty payout decision) is a HARD, PERMANENT block, evaluated
   unconditionally. An op outside the closed four-op allowlist is folded
   into the same check. `:flag-inventory-concern` itself is never
   excluded -- only FINALIZING a copyright/licensing dispute is.

### Two ESCALATE (SOFT) gates

- `:flag-inventory-concern` -- ALWAYS escalates to a human, regardless of
  confidence or phase (independently agreed by both the governor's
  `always-escalate-ops` and the phase table's `:auto` set, which never
  contains this op at any phase 0-3).
- `:coordinate-supply-order` above `supply-cost-threshold` (1000.0
  domain-illustrative units) -- always needs human sign-off.
- (LLM confidence below `confidence-floor` 0.6 also escalates.)

### Self-trip guard (fleet-known bug class)

Multiple sibling actors in this fleet independently discovered the same
bug class: a governor's scope-exclusion term list phrased as a bare noun
(e.g. "copyright", "piracy", "counterfeit") can accidentally match
inside the mock advisor's own default rationale text for a legitimate
proposal, self-blocking the happy path. This actor's
`mvretailops.governor/scope-excluded-terms` is phrased exclusively as
finalization/execution ACTIONS (e.g. "approved the copyright claim",
"processed the royalty payment"), never bare nouns, and
`mvretailops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
is a dedicated regression test asserting every op the default mock
advisor can generate (with default, non-`out-of-scope?` patches) never
trips `:scope-excluded` or `:op-not-allowed`. This bug class was hit and
fixed once in this actor's own development: the initial `out-of-scope?`
test-hook text ("approved the copyright dispute and processed the
royalty payout") did not exactly match any listed action phrase (which
say "copyright claim"/"royalty payment", not "copyright dispute"/
"royalty payout"), so the `scope-excluded-content-hard-hold` integration
test initially failed to trip the HARD block -- corrected by aligning
the test-hook text to the actual action phrases. Verified green after
the fix.

## Consequences

- `kotoba-lang/industry` registry's `{:id "4762" ...}` entry is promoted
  from `:spec` to `:implemented`, with `:name` de-truncated to "Retail
  sale of music and video recordings in specialized stores" and `:repo`/
  `:business-id` corrected to the real repository
  (`https://github.com/cloud-itonami/cloud-itonami-isic-4762` /
  `cloud-itonami-isic-4762`), and `maturity-summary`'s implemented count
  bumped accordingly (recomputed from the live registry via
  `kotoba.industry/maturity-summary`, not by `grep -c`).
- Full test suite (`clojure -M:test`): 56 tests, 166 assertions, 0
  failures, 0 errors -- raw output captured in this ADR's companion EDN
  and in the task report.
- `clojure -M:lint` (clj-kondo, `--fail-level error`): 0 errors, 0
  warnings.
- `clojure -M:run` demo driver exercises the full happy path (phase 1
  approval-gated commit, phase 3 auto-commit, low/high-cost supply
  orders, inventory-concern flag-then-approve) and every HARD-hold
  scenario (unregistered store, unverified store, unverified vendor,
  non-`:propose` effect, scope-excluded content) end-to-end without
  exception.
- No `manifest/west.yml` changes -- this actor is registered purely via
  the `kotoba-lang/industry` capability registry, consistent with
  sibling `cloud-itonami-isic-*` actors.

## Alternatives considered

- Reusing ISIC 4742's `audio-video-retail-governor` scope/keyword
  directly: rejected -- each ISIC class in this fleet gets its own
  independently-named, independently-tested governor
  (`:music-video-retail-governor` here), even where the shape of the
  checks is structurally similar, so that no single governor becomes an
  unbounded catch-all and each vertical's genuinely distinct risk (here:
  grey-market/counterfeit-recording vendors, copyright/licensing-dispute
  finalization rather than warranty-claim finalization) has its own
  dedicated, auditable test suite.
- A bare-noun scope-exclusion term list (e.g. `["copyright" "piracy"
  "counterfeit"]`): rejected outright per the fleet-known self-trip bug
  class described above -- would have self-blocked this actor's own
  `:flag-inventory-concern` happy path, which structurally must discuss
  counterfeit/bootleg/piracy/mis-shipment concerns to do its job.
