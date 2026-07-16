# ADR-2607092151: cloud-itonami-isic-4211 (Community Building Construction) deepened to `:implemented` -- robot-dispatch (build/handover) slice

## Status

Accepted.

## Related

- ADR-2607082700 (`cloud-itonami-construction-4211-disaster-safety` -- the prior :partially-implemented slice this completes)
- `cloud-itonami-isic-4211/docs/adr/0001-architecture.md` Decision 1 (the explicit robot-dispatch follow-up this closes)
- `cloud-itonami-isic-4211/docs/adr/0002-robot-dispatch-slice.md` (this build's own actor-repo ADR)
- `cloud-itonami-isic-6511` (the reference governed-actor pattern the whole fleet follows)
- ADR-2607091900 (`cloud-itonami-agronomyops-0162` -- a concurrent promotion in the same window)

## Context

`cloud-itonami-isic-4211` was the fleet's only `:partially-implemented` entry: the
disaster/severe-weather SAFETY slice (Construction Advisor ⊣ Construction Governor,
per-jurisdiction legal-basis catalog JPN/USA/DEU, Resend+Twilio alert dispatch) shipped
under ADR-2607082700, while the physical robot-dispatch (build) slice was left as an
explicit follow-up in the actor repo's own ADR-0001 Decision 1. This ADR closes that
follow-up and promotes the entry to full `:implemented`.

This is a **non-overlapping** completion. A concurrent `/loop` in another session is
draining the `:blueprint` -> `:implemented` tier (it landed 4711/4920/0810/0162 in this
window) but it skips entries that already carry source code, so it never touches 4211.

## Decision

Extend the existing actor with the physical build/handover actuation slice, reusing its
own Store / Phase / Governor / Advisor / StateGraph scaffolding (all edits additive -- the
safety slice is unchanged):

- **Two new dual-actuation ops** on the existing `site` entity: `:build/dispatch-placement`
  (a robot physically places a building element -- real-world actuation) and
  `:handover/complete` (hand over the completed/inspected structure), each with its OWN
  dedicated double-actuation-guard boolean (`:placement-dispatched?` / `:handed-over?`,
  never a `:status` value), history collection, sequence counter, registry draft
  (JPN-PLC / JPN-HDO) and (handover) a rendered handover certificate. Both are high-stakes
  and NEVER auto-eligible at any phase -- real physical acts, always human-gated, matching
  every sibling's non-alert actuation posture.
- **One genuinely-new governor check**, `permit-and-inspection-required` (governor check
  8), scoped to the build/handover ops only: a placement requires an ISSUED BUILDING
  PERMIT on file; a handover requires the permit AND a PASSED COMPLETION INSPECTION.
  Grounded in real construction/building-code law, cited per-jurisdiction in
  `construction.facts`: **JPN** 建築基準法 第6条（建築確認）/ 第7条（完了検査）; **USA**
  IBC §105 (Permits) / §111 (Certificate of Occupancy + final inspection) -- honestly
  labeled an ICC *model code*, not federal statute; **DEU** Landesbauordnung
  (Baugenehmigung / Abnahme) + **EU** Construction Products Regulation 305/2011.
  Grep-verified UNIQUE fleet-wide (no sibling governor gates on a building permit). Plus
  two new double-actuation guards (`already-placement-dispatched` / `already-handed-over`).
- **No fourth maturity tier.** industry stays three-tier (`:spec`/`:blueprint`/  `:implemented`); `:partially-implemented` was a property of the actor repo's own
  `blueprint.edn`, now resolved to `:implemented` by completing the slice -- no library
  change needed.

## Consequences

- `cloud-itonami-isic-4211` is now fully `:implemented` (both the safety slice AND the
  robot-dispatch slice). `blueprint.edn` `:partially-implemented` -> `:implemented`.
- Tests: actor 78 / 344 assertions (baseline 59 / 238; +19 / +106), lint clean, demo
  verified end-to-end incl. the new robot-dispatch segment, the handover-certificate
  render, and every HARD hold (no-permit / no-completion-inspection / double-placement /
  double-handover). industry 7 / 138 green. The safety slice's 59 / 238 pass verbatim.
- industry west pin advanced `adf0c75` -> `0cdd5e5e` (sha-gated single-entry PUT,
  PreToolUse pin-verify hook gated reachability + forward-move; verified == industry main
  HEAD). Other west.yml entries were STALE under `--check` (the concurrent loop's
  mid-flight pins) and intentionally NOT regenerated -- wholesale regen is forbidden
  (the `90852b86` incident) and would clobber the concurrent loop.
- Concurrent-edit recovery: while landing, another session promoted 0162 on industry main
  (89 -> 90); the first promote-4211 industry merge 409'd on the 3 shared files, re-applied
  off the new main with corrected counts (4211 = ninety-first, count 90 -> 91, blueprint
  8 -> 7, total 643). No rebase, no force-push; shallow clones throughout.

## References

- companion `.edn`, ADR-2607082700, the actor repo's own ADR-0001/0002.
