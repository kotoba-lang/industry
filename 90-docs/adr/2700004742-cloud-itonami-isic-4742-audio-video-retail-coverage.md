# ADR 2700004742: cloud-itonami ISIC 4742 audio/video-equipment-retail operations-coordination coverage

## Status

Accepted

## Context

This is part of Wave 2 (coordination/logistics/trade, ADR-2607121000) of
the `cloud-itonami` fleet: codify every ISIC industry class as an
autonomous "actor" (LLM/advisor behind an independent Governor, a
langgraph-clj StateGraph, append-only audit ledger). This ADR covers
**ISIC Rev.5 class 4742 -- retail sale of audio and video equipment in
specialized stores** only.

The `kotoba-lang/industry` registry's live `{:id "4742" ...}` entry name
was verified against a fresh clone before any work began and reads
exactly "Retail sale of audio and video equipment in specialized stores"
-- confirming the premise. Prior to this ADR the entry's `:maturity` was
`:spec` with a placeholder `:repo`/`:business-id`
(`gftdcojp/cloud-itonami-G4742`); no repository existed at
`cloud-itonami/cloud-itonami-isic-4742` (`gh api` 404-confirmed before
scaffolding). This ADR and its companion registry edit promote the entry
to `:implemented`.

The reference implementation mirrored is
[`cloud-itonami/cloud-itonami-isic-4719`](https://github.com/cloud-itonami/cloud-itonami-isic-4719)
(other retail sale in non-specialized stores), independently re-verified
working in this session, adapted to audio/video-equipment-retail
operations coordination.

## Decision

Publish `cloud-itonami/cloud-itonami-isic-4742` as an OSS operations-
coordination actor for audio/video-equipment specialty retail (TVs,
home-theater systems, speakers, headphones, car audio, projectors and
related AV gear), following the SAME governed-actor architecture as
every prior `cloud-itonami-isic-*` actor in this fleet:
**AudioVideoRetailAdvisor ⊣ AudioVideoRetailGovernor** on a
`langgraph-clj` StateGraph, with an independent Governor, human-in-the-
loop approval via `interrupt-before`, and an append-only audit ledger.

This actor is deliberately **operations coordination only**, never
warranty-claim authority:

### Closed proposal-op allowlist (all `:effect :propose`)

- `:log-sales-record` -- inventory/sale/return/warranty-registration
  data logging
- `:schedule-staffing-operation` -- floor-staff/delivery scheduling
  proposal
- `:flag-warranty-concern` -- surface a defect/warranty-dispute/
  counterfeit-product concern, ALWAYS escalates
- `:coordinate-supply-order` -- AV-equipment inventory procurement
  proposal

### Four HARD governor checks (permanent, un-overridable)

1. **Store unverified** -- target store record must exist AND be
   independently `:registered?`/`:verified?` before any proposal for it
   may commit or escalate. Never trusts the proposal's own claim.
2. **Vendor unverified** -- for `:coordinate-supply-order` only, the
   proposal's own drafted `:vendor-id` must resolve to an independently
   `:registered?`/`:verified?` vendor record -- the flagship
   supply-chain counterparty-verification gate, which matters doubly in
   this vertical given the grey-market/counterfeit-AV-import risk.
3. **Effect not `:propose`** -- any other `:effect` value is a HARD
   block (a claim to directly actuate outside governance).
4. **Scope exclusion** -- any proposal touching directly finalizing a
   warranty-claim decision (approving, denying, paying out, or
   otherwise settling a warranty claim; issuing a warranty refund or
   replacement) is a HARD, PERMANENT block, evaluated unconditionally.
   An op outside the closed four-op allowlist is folded into the same
   check. `:flag-warranty-concern` itself is never excluded -- only
   FINALIZING a claim is.

### Two ESCALATE (SOFT) gates

- `:flag-warranty-concern` -- ALWAYS escalates to a human, regardless of
  confidence or phase (independently agreed by both the governor's
  `always-escalate-ops` and the phase table's `:auto` set, which never
  contains this op at any phase 0-3).
- `:coordinate-supply-order` above `supply-cost-threshold` (1000.0
  domain-illustrative units) -- always needs human sign-off.
- (LLM confidence below `confidence-floor` 0.6 also escalates.)

### Self-trip guard (fleet-known bug class)

Multiple sibling actors in this fleet independently discovered the same
bug class: a governor's scope-exclusion term list phrased as a bare noun
(e.g. "warranty", "claim") can accidentally match inside the mock
advisor's own default rationale text for a legitimate proposal,
self-blocking the happy path. This actor's
`avretailops.governor/scope-excluded-terms` is phrased exclusively as
finalization/execution ACTIONS (e.g. "approved the warranty claim",
"processed the warranty refund"), never bare nouns, and
`avretailops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
is a dedicated regression test asserting every op the default mock
advisor can generate (with default, non-`out-of-scope?` patches) never
trips `:scope-excluded` or `:op-not-allowed`. Verified green.

## Consequences

- `kotoba-lang/industry` registry's `{:id "4742" ...}` entry is promoted
  from `:spec` to `:implemented`, with `:repo` and `:business-id`
  corrected to the real repository
  (`https://github.com/cloud-itonami/cloud-itonami-isic-4742` /
  `cloud-itonami-isic-4742`), and `maturity-summary`'s implemented count
  bumped accordingly (recomputed from the live registry, not by
  `grep -c`).
- Full test suite (`clojure -M:test`): 56 tests, 166 assertions, 0
  failures, 0 errors -- raw output captured in this ADR's companion EDN
  and in the task report.
- `clojure -M:lint` (clj-kondo, `--fail-level error`): 0 errors, 0
  warnings.
- `clojure -M:run` demo driver exercises the full happy path (phase 1
  approval-gated commit, phase 3 auto-commit, low/high-cost supply
  orders, warranty-concern flag-then-approve) and every HARD-hold
  scenario (unregistered store, unverified store, unverified vendor,
  non-`:propose` effect, scope-excluded content) end-to-end without
  exception.
- No `manifest/west.yml` changes -- this actor is registered purely via
  the `kotoba-lang/industry` capability registry, consistent with
  sibling `cloud-itonami-isic-*` actors.

## Alternatives considered

- Reusing ISIC 4719's `merchandise-retail-governor` scope/keyword
  directly: rejected -- each ISIC class in this fleet gets its own
  independently-named, independently-tested governor
  (`:audio-video-retail-governor` here), even where the shape of the
  checks is structurally similar, so that no single governor becomes an
  unbounded catch-all and each vertical's genuinely distinct risk (here:
  grey-market/counterfeit AV-equipment vendors, warranty-claim
  finalization rather than loss-prevention-enforcement finalization) has
  its own dedicated, auditable test suite.
- A bare-noun scope-exclusion term list (e.g. `["warranty" "claim"
  "counterfeit"]`): rejected outright per the fleet-known self-trip bug
  class described above -- would have self-blocked this actor's own
  `:flag-warranty-concern` happy path, which structurally must discuss
  warranty/claim/counterfeit concerns to do its job.
