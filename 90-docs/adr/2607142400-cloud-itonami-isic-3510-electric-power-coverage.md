# ADR-2607142400: `cloud-itonami-isic-3510` (Electric power generation, transmission and distribution) promoted `:blueprint` -> `:implemented` -- first ISIC Wave 1 class, Top-10 item #6

- Status: Accepted (2026-07-14)
- Related: `cloud-itonami-isic-6130` (Satellite telecommunications
  activities, most recent REPO-LAYOUT precedent at build start),
  `cloud-itonami-isic-3600` (Community Water Safety Operations, closest
  infrastructure/utility DOMAIN analog), `cloud-itonami-isic-6391`
  (News agency activities, this fleet's first asymmetric dual-actuation
  shape), ADR-2607121000 (ISIC/ISCO global reverse-toposort wave plan,
  this build's own explicit Top-10 value-ranking item #6), ADR-2607101800
  (`3510`'s own prior `:blueprint`-stage ADR)

## Context

ISIC Wave 0 and ISCO Wave 0 (the highest-priority "root" waves) reached
100% `:implemented` at the class level earlier this session
(ADR-2607141800/ADR-2607142200). This build moves into ISIC Wave 1
(governance/professional/energy: divisions 69-74, 84, 99, 35-36,
06/09/19, +8291), targeting `"3510"` specifically -- ADR-2607121000's
own explicit Top-10 value-ranking item #6 ("3510/3512 電力(lattice の
電力需要と垂直統合)").

Unlike `cloud-itonami-isic-6130`/`-6391` (this fleet's two most
recently completed actors, both fresh `:spec` -> `:implemented`
scaffolds with no prior blueprint stage), `"3510"` already had a
published `:blueprint`-only repo from an earlier build (ADR-2607101800:
`blueprint.edn`, `docs/business-model.md`, `docs/operator-guide.md`,
community files, no `src`/`test`) -- checked live before starting
(`gh api repos/cloud-itonami/cloud-itonami-isic-3510`, `pushed_at`
2026-07-09, and the registry's own `:repo`/comment block, both
confirmed unmodified this session before this build began, ruling out
a concurrent-session duplicate). This build adds the governed-actor
implementation on top of that existing scaffold -- the same
"blueprint promoted to implemented in a later build" precedent
`cloud-itonami-isic-6120` itself used.

The existing blueprint's published `docs/business-model.md` framed
this repo's Offer around interconnection/wheeling-request management
and dispatch/settlement between generators and the grid (a
TRANSMISSION-level, wholesale framing) -- this build's actual governed
R0 scope is the customer/meter-level DISTRIBUTION-to-retail-customer
slice (meter intake, identity verification, billing/service-dispute
screening, new-service provisioning, service disconnection), a
legitimate sub-scope of the same ISIC 3510 class distinct from, but not
contradicting, the published wholesale framing. `docs/business-
model.md`/`docs/operator-guide.md` were extended (not replaced) to
name both explicitly; see child-repo `docs/adr/0001-architecture.md`
for the full accounting.

## Decision

1. `cloud-itonami/cloud-itonami-isic-3510` promoted to a full governed-
   actor repo -- `blueprint.edn` gains `:itonami.blueprint/maturity
   :implemented`; `docs/business-model.md`/`docs/operator-guide.md`
   extended with the customer/meter-level governed scope; `src`/`test`/
   `deps.edn`/`docs/adr/0001-architecture.md` added.
2. **Grid Distribution Advisor ⊣ Grid Transmission Governor**
   (`:grid-transmission-governor`, grep-verified unique fleet-wide,
   distinct from `cloud-itonami-isic-3512`'s own `:grid-policy-
   governor`) -- `grid.*` namespaces. Module shape (`facts`/`registry`/
   `store`/`gridadvisor`/`governor`/`phase`/`operation`/`sim`) mirrors
   `cloud-itonami-isic-6130`'s `satcom.*` repo layout; the domain
   itself (a regulated infrastructure utility with a real
   disconnection actuation carrying public-welfare stakes) most closely
   mirrors `cloud-itonami-isic-3600`'s `water.*` domain shape, this
   fleet's OTHER infrastructure/utility vertical.
3. **`protected-recipient-violations` -- THIS FLEET'S FIRST always-
   un-overridable HARD check.** Checked explicitly against both named
   analogs (`water.governor`, `cloud-itonami-isic-3600`; `satcom.
   governor`, `cloud-itonami-isic-6130`) and a full-fleet grep for
   "life-support"/"critical-infrastructure"/"protected-recipient" --
   zero prior art. `grid.governor/protected-recipient-violations`
   independently recomputes a meter's own permanent
   `:protected-recipient?` field (a life-support-equipment or
   designated critical-infrastructure customer, e.g. the UK's Priority
   Services Register cited in this repo's own `grid.facts` GBR entry)
   for `:actuation/disconnect-service`; a hit is an ABSOLUTE,
   un-overridable HOLD -- unlike every other HARD check in this
   fleet's history (all clearable by a sufficiently well-evidenced
   re-submission), no confidence level and no human approval can clear
   this one. A protected-recipient disconnection is not a "try again
   with better evidence" case; this actor never performs it.
4. **`capacity-over-threshold-violations` -- THIS FLEET'S SECOND
   asymmetric dual-actuation shape, on a NEW (value-driven) dimension.**
   `cloud-itonami-isic-6391`'s own asymmetry is driven by WHICH op is
   proposed (`:actuation/distribute` conditionally high-stakes,
   `:actuation/issue-correction` unconditionally). This actor's own
   asymmetry is driven by a VALUE the SAME op's request carries:
   `:actuation/provision-service` is high-stakes only when the target
   meter's own recorded `:capacity-kw` exceeds `grid.registry/default-
   capacity-threshold-kw` -- a clean, verified, well-formed
   residential/small-commercial connection may reach phase-3
   auto-commit; the identical clean proposal for a large connection
   always escalates for a human distribution-capacity-impact review.
   `:actuation/disconnect-service` remains unconditionally high-stakes
   (this fleet's now-fifth negative actuation, after `3600`/`6190`/
   `6120`/`6130`'s own withholding actuations) -- disconnection is
   never autonomous regardless of any threshold, permanently excluded
   from every phase's `:auto` set, enforced independently by both
   `grid.governor`'s `high-stakes` set and `grid.phase`'s phase table.
5. `meter-number-invalid-format?` (`grid.registry`) -- the FOURTH
   application of this fleet's format/syntactic-validity check family
   (after `e164-invalid-format?` [`6190`], `msisdn-invalid-format?`
   [`6120`], `satellite-number-invalid-format?` [`6130`]): an
   IEC-62056-21-style numeric meter serial (8-12 digits, no letters),
   gating only `:actuation/provision-service`.
6. `dispute-unresolved-violations` reuses the established unconditional
   -evaluation dispute-screening discipline (`satcom.governor/
   coordination-dispute-unresolved-violations` [`6130`], `water.
   governor/threshold-breach-unresolved-violations` [`3600`]) -- an
   unresolved billing/service dispute HARD-holds regardless of which op
   surfaces it.
7. No `:effect :propose` marker (unlike `newswire.advisor`'s literal
   marker + separate `:action` field) -- follows `satcom.satcomadvisor`'s
   shape, where `:effect` IS the specific SSoT-mutation instruction
   directly; the "advisor never itself writes the SSoT" invariant is
   already structurally enforced by `grid.operation`'s single `:commit`
   node. Documented explicitly as a deliberate shape choice (Decision 6,
   child-repo ADR-0001), not an oversight.
8. `kotoba-lang/industry` registry: `"3510"` explicit `:maturity
   :implemented` set (was implicitly `:blueprint`, auto-derived from
   `:repo` presence with no explicit `:maturity` key). Fleet-wide
   maturity moves 150 -> 151 implemented, `:blueprint` 41 -> 40.
   `test/kotoba/industry_test.clj`'s live-state corroboration and
   `maturity-summary` assertions updated to match. Landed via a
   2-parent GitHub API server-side merge (no local rebase/force-push).

## Consequences

- (+) **First ISIC Wave 1 class promoted to `:implemented`**, closing
  ADR-2607121000's own explicit Top-10 value-ranking item #6.
- (+) Introduces this fleet's first protected-recipient / always-
  un-overridable-HARD-hold check -- a template other domains with an
  analogous vulnerable-recipient concept (e.g. a future healthcare- or
  eldercare-adjacent actor) may reuse.
- (+) Confirms `6391`'s asymmetric-dual-actuation pattern generalizes
  to a SECOND, value-driven (not op-kind-driven) dimension.
- (+) Confirms this fleet's format/syntactic-validity check family
  generalizes to a FOURTH real-world identifier (a utility meter's
  own nameplate serial) without modification to its check shape.
- (-) This R0 governs the customer/meter-level slice only -- no real
  SCADA/telemetry, no real interconnection/wheeling-request or
  settlement lifecycle between generators and the grid operator (the
  originally-published blueprint's wholesale framing), no emergency-
  outage/storm-restoration coordination; see child-repo README
  `Business-process coverage` for the full honest-scope accounting.
- Fleet-wide: 151 actors now `:implemented` out of the registry's full
  entry count.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Model the protected-recipient concept as a per-jurisdiction `grid.facts` field | ❌ | Whether a SPECIFIC customer depends on life-support equipment is a property of that meter, not the jurisdiction as a whole |
| Make `protected-recipient-violations` SOFT (human may override), matching `newswire.governor/legally-sensitive-violations`'s treatment of a comparable "serious but reviewable" risk | ❌ | The task's own framing is explicit: disconnecting a life-support/critical-infrastructure meter "must always hold, never overridable regardless of confidence" -- unlike a legal-sensitivity risk, there is no informed-judgment case where this actor should perform the disconnection |
| Make `:actuation/provision-service` permanently excluded from every phase's `:auto` set, matching `satcom`'s/`water`'s own symmetric shape | ❌ | The task's own domain framing anticipates only over-threshold provisioning escalating; a real distribution utility's ordinary cadence does not put a human in the loop on every small residential connection |
| Publish a fresh `newswire`-style marker (`:effect :propose` + `:action`) instead of `satcom`'s single-`:effect` shape | ❌ | This build's repo-layout precedent is `satcom`/`water`, not `newswire`; the invariant is already structurally enforced without the extra marker |
| See `cloud-itonami-isic-3510`'s own ADR-0001 Alternatives table for further build-level decisions | -- | (business-model.md scope-extension approach, etc.) |

## References

- `cloud-itonami-isic-3510/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `cloud-itonami-isic-3600/README.md` + `docs/adr/0001-architecture.md`
  (closest infrastructure/utility domain analog)
- `cloud-itonami-isic-6130/docs/adr/0001-architecture.md` (most recent
  repo-layout/deps.edn/ADR-convention template at build start)
- `cloud-itonami-isic-6391/docs/adr/0001-architecture.md` (first
  asymmetric dual-actuation precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
- ADR-2607121000 (ISIC/ISCO global reverse-toposort wave plan)
- ADR-2607101800 (`3510`'s own prior `:blueprint`-stage ADR)

## Verification Notes

- `cloud-itonami-isic-3510`: `clojure -M:dev:test` 42 tests / 194
  assertions, 0 failures. `clojure -M:lint` 0 errors / 0 warnings.
  `clojure -M:dev:run` demo verified end-to-end (a clean auto-committing
  under-threshold provisioning + a full dual-actuation lifecycle +
  six HARD-hold cases: a jurisdiction with no spec-basis, a malformed
  meter number, an over-threshold provisioning that still escalates for
  human sign-off despite phase-3 auto eligibility, a protected-recipient
  disconnection HARD-held and never reaching a human, an unresolved
  billing/service dispute screened directly, and a double provisioning/
  disconnection of an already-processed meter). Commit `93fa830`
  merged via 2-parent GitHub API server-side merge
  `67d314c36d81d2da8c0ec43e7f24ae5403c940d3` (parents `ff321bf` and
  `93fa830`), branch deleted after merge.
- `kotoba-lang/industry`: registry + test updated, `clojure -M:test`
  15 tests / 941 assertions, 0 failures; `clojure -M:lint` 0 errors /
  0 warnings. Landed via 2-parent GitHub API server-side merge
  `5cd023b46d8ffb35e27f0fcc73685db0c0180a2e` (parents `b533059` and
  `33cbf7e`), branch deleted after merge.
