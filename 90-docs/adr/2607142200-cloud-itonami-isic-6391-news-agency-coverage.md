# ADR-2607142200: `cloud-itonami-isic-6391` (News agency activities) published fresh and deepened to `:implemented` -- LAST ISIC Wave 0 class-level gap, closing the wave

- Status: Accepted (2026-07-14)
- Related: `cloud-itonami-isco-3521` (Independent Broadcast & Media
  Syndication Practice, closest DOMAIN analog -- media content moving
  from creation/verification to distribution), `cloud-itonami-isic-6130`
  (Satellite telecommunications activities, most recent REPO-LAYOUT
  precedent, landed same day), ADR-2607121000 (ISIC Wave 0
  reverse-toposort plan), ADR-2607141800 (`6130`'s own coverage ADR,
  which explicitly left `"6391"` for this build), ADR-2607131000
  (direct spec->implemented promotion precedent, e.g. 4620/2910)

## Context

ISIC Wave 0 (the highest-priority "money/info root" wave: banking,
insurance, telecom, data) was down to its LAST class-level gap:
`"6391"` (News agency activities) -- `cloud-itonami-isic-6130`'s own
same-day coverage ADR (ADR-2607141800) closed the second-to-last gap
and explicitly left this one for a possible concurrent sibling build.
Like `"6130"`, `"6391"` had NO existing blueprint repo to build on: the
only prior reference (`gftdcojp/cloud-itonami-J6391`, the legacy
pre-rename naming convention) was confirmed a dead link (404) before
starting. This is a fresh scaffold from zero, not a `:blueprint` ->
`:implemented` promotion.

News agency activities are NOT telecom infrastructure -- an
information/media-content business. This build therefore deliberately
splits its reference points: `cloud-itonami-isco-3521` (this fleet's
closest DOMAIN analog) shapes the domain logic; `cloud-itonami-isic-
6130` (this fleet's most recent REPO-LAYOUT precedent) shapes the
boilerplate.

## Decision

1. `cloud-itonami/cloud-itonami-isic-6391` (public, AGPL-3.0-or-later)
   published as a full governed-actor repo in one build -- README,
   `blueprint.edn`, `docs/business-model.md`, `docs/operator-guide.md`,
   community files (GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/SECURITY),
   `src`/`test`/`deps.edn`/`docs/adr/0001-architecture.md` -- rather
   than a `:blueprint`-only stage, matching this fleet's existing
   direct `:spec` -> `:implemented` promotion precedent (e.g. `4620`,
   `2910`, `6130`; ADR-2607131000).
2. **Wire Advisor ⊣ Wire Governor** (`:wire-governor`, grep-verified
   unique fleet-wide) -- `newswire.*` namespaces. Domain-logic module
   shape (`store`/`advisor`/`governor`/`actor`, single `MemStore`, no
   `DatomicStore`) mirrors `cloud-itonami-isco-3521`'s `media.*` shape;
   `newswire.registry`/`newswire.phase` are additions beyond 3521's
   four-file shape, warranted by this domain's distinct sourcing/
   embargo/legal-sensitivity/correction requirements. Scope: a
   subscriber news-wire operator (story intake, sourcing/verification,
   legal-sensitivity screening, distribution, correction/retraction) --
   distinct from `cloud-itonami-isco-3521`'s downstream commercial
   syndication-practice scope (which consumes an UPSTREAM mirror feed)
   and unrelated to `cloud-itonami-isic-61xx`'s telecom line-
   provisioning scope.
3. `newswire.advisor` proposals carry a fixed `:effect :propose`
   (the literal invariant `newswire.governor`'s `no-actuation-
   violations` independently re-checks) PLUS a separate `:action`
   field naming the specific SSoT mutation a governor-cleared commit
   would apply -- distinct from `cloud-itonami-isic-6130`'s
   `satcom.satcomadvisor` shape (where `:effect` IS the specific
   mutation, no separate propose-marker check exists at all). This is
   `cloud-itonami-isco-3521`'s `:effect :propose` literal-invariant
   shape, adapted with `:action` in place of reusing `:op` for the
   proposal's own action name.
4. `embargo-violated?` (`newswire.registry`) is a genuinely NEW check
   kind for this fleet: a pure ground-truth TEMPORAL check (is `now`
   still before the story's own recorded `:embargo-until` instant?),
   distinct from every prior format/syntactic-validity check
   (`e164-invalid-format?` [`6190`], `msisdn-invalid-format?` [`6120`],
   `satellite-number-invalid-format?` [`6130`]), which recompute
   identifier WELL-FORMEDNESS, not a temporal comparison. HARD,
   un-overridable -- the task's own framing is explicit that an
   embargo break is "not just risky."
5. `legally-sensitive-violations` is SOFT (`:escalate?`, a human MAY
   approve past it), independently ground-truth-recomputed off the
   story's own `:legally-sensitive?` field for `:actuation/distribute`
   -- deliberately NOT gated behind a prior committed
   `:sensitivity/screen` record. An earlier draft checked only the
   committed screen verdict (mirroring `satcom.governor/coordination-
   dispute-unresolved-violations`'s `hit-on-file?` pattern too
   literally); this repo's own `test/newswire/actor_test.clj` caught
   the resulting "forgot to screen" loophole (a bureau that never ran
   `:sensitivity/screen` on a story would otherwise auto-publish a
   legally-sensitive story with no check at all) before landing. See
   `docs/adr/0001-architecture.md` Decision 4 in the child repo for the
   full account.
6. **This fleet's FIRST asymmetric dual-actuation shape.** Every prior
   dual-actuation sibling (`cloud-itonami-isic-6190`/`-6120`/`-6130`)
   permanently excludes BOTH actuations from every phase's `:auto` set.
   Here, `:actuation/distribute` MAY reach phase-3 auto-commit when
   clean (sourced, unembargoed, non-sensitive) -- a real wire service's
   ordinary, continuous operation does not put a human in the loop on
   every single clean story -- while `:actuation/issue-correction`
   NEVER auto-commits, at ANY phase, enforced independently by both
   `newswire.governor`'s ONE-member `high-stakes` set
   (`#{:actuation/issue-correction}`, unlike every prior sibling's
   two-member set) and `newswire.phase`'s phase table. Grounded
   directly in the task's own framing: "issuing a correction or
   retraction for a previously-distributed story is a distinct,
   auditable, always-human-signoff action (the actor never silently
   overwrites what it already distributed)."
7. Correction/retraction is modeled as a POSITIVE act (issuing a new,
   distinct notice), NOT this fleet's fifth negative actuation (unlike
   `3600`'s alert suppression, `6190`'s billing-record suppression,
   `6120`'s service suspension, `6130`'s service suspension, all of
   which WITHHOLD something). `kind` (`:correction`/`:retraction`)
   distinguishes the two outcomes within the same op/record shape.
8. Dedicated `:distributed?`/`:retracted?` booleans on the story
   record, never a single `:status` value -- the same discipline every
   prior sibling governor's guards establish (`cloud-itonami-isic-
   6492`'s status-lifecycle bug, ADR-2607071320).
9. `:itonami.blueprint/robotics false` -- unlike `cloud-itonami-isco-
   3521` (a studio/production-booth robot performs camera framing/
   audio-riding under that actor), a news-wire operator gathers,
   sources and distributes text/data content with no physical
   production equipment. Follows this fleet's existing "no :robotics"
   exemption class (`-6310`/`-6910`/`-8291`/`-4690`/`-4610`/`-6311`/
   `-6312`/`-7820`, most directly `-6312` "Web portals").
   `required-technologies` drops `:robotics`/`:phone` accordingly.
10. `kotoba-lang/industry` registry: `"6391"` `:maturity :spec` ->
    `:implemented` DIRECTLY (no intermediate `:blueprint`), `:repo`/
    `:business-id` updated from the dead `gftdcojp/cloud-itonami-
    J6391` placeholder to the real `cloud-itonami/cloud-itonami-isic-
    6391`. Fleet-wide maturity moves 149 -> 150 implemented
    (`:blueprint` unchanged at 41). `test/kotoba/industry_test.clj`'s
    live-state corroboration and `maturity-summary` assertions updated
    to match. Landed via a 2-parent GitHub API server-side merge (no
    local rebase/force-push), `3cd5032abb8b833e74711eac702c7ec97436ac2f`.
11. **This closes the LAST ISIC Wave 0 class-level gap.** ISIC Wave 0
    is now 100% `:implemented` at the class level: all 32 four-digit
    ISIC classes in wave 0 (division-wave `"61"`/`"62"`/`"63"`/`"64"`/
    `"65"`/`"66"` and code-override `"5820"`) are `:implemented`; the
    15 remaining non-`:implemented` wave-0 registry entries are all
    3-digit GROUP-level codes (611/612/613/620/631/639/642/643/649/
    651/652/653/662/663) or the `6611-cryptoexchange` role-suffix
    satellite id (`:blueprint`, a deliberately unbuilt incident-proof
    exchange design per ADR-2607141200) -- none are 4-digit classes.

## Consequences

- (+) **ISIC Wave 0, the highest-priority "money/info root" wave, is
  now fully `:implemented` at the class level** -- mirroring ISCO
  Wave 0's earlier completion this session (per this task's own
  framing). The next "pick a new ISIC/ISCO vertical" build is free to
  move to Wave 1 (governance/energy) or deepen wave-0 group-level/
  satellite entries.
- (+) Confirms this fleet's ground-truth-recompute discipline
  generalizes to a genuinely TEMPORAL check (embargo) and, after the
  actor_test-caught fix, to a defamation/liability-risk flag as well
  -- both independently re-derived from the entity's own permanent
  field rather than trusted from a proposal or a prior op's committed
  output alone.
- (+) Introduces this fleet's first asymmetric dual-actuation phase
  table and `high-stakes` set -- a template other domains with a
  "routine autonomous act, rare always-human corrective act" shape may
  reuse.
- (-) This R0 models story sourcing/embargo/sensitivity/distribution/
  correction only -- no real wire-transport infrastructure (SFTP/
  NewsML-G2, satellite uplink), no real subscriber billing/entitlement
  enforcement, no press-credential issuance; see `cloud-itonami-isic-
  6391`'s own README coverage table for the full honest-scope
  accounting.
- Fleet-wide: 150 actors now `:implemented` out of the registry's full
  entry count.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Make `:actuation/distribute` permanently excluded from every phase's `:auto` set, matching every prior dual-actuation sibling | ❌ | The task's own domain framing distinguishes ordinary clean distribution (may eventually be autonomous) from legally-sensitive distribution and correction/retraction (always human-gated); symmetric always-human treatment for both actuations would misrepresent a real wire service's ordinary operating cadence |
| Check `legally-sensitive-violations` only via a committed `:sensitivity/screen` record, matching `satcom.governor`'s `hit-on-file?` pattern literally | ❌ | Caught by this repo's own test suite as a "forgot to screen" loophole for a defamation/liability risk specifically too serious to leave conditional on a prior op having run |
| Model correction/retraction as a negative actuation (silently marking the original distribution retracted/hidden) | ❌ | The task's own framing is explicit: the actor "never silently overwrites what it already distributed" -- a correction/retraction must be its own distinct, additive record |
| Publish `:blueprint`-only first, defer the actor implementation | ❌ | This fleet's direct spec->implemented precedent (ADR-2607131000) applies cleanly; no independent blueprint-stage work was already in flight |
| See `cloud-itonami-isic-6391`'s own ADR-0001 Alternatives table for further build-level decisions | -- | (DatomicStore deferral, robotics-exemption classification, etc.) |

## References

- `cloud-itonami-isic-6391/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `cloud-itonami-isco-3521/README.md` + its own commit history
  (closest domain analog, module-shape template)
- `cloud-itonami-isic-6130/docs/adr/0001-architecture.md` (most recent
  repo-layout/deps.edn/ADR-convention template)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
- ADR-2607121000 (ISIC Wave 0 reverse-toposort plan)
- ADR-2607141800 (`cloud-itonami-isic-6130`'s own coverage ADR, same
  day, explicitly left `"6391"` for this build)
- ADR-2607131000 (direct spec->implemented promotion precedent)

## Verification Notes

- `cloud-itonami-isic-6391`: `clojure -M:test` 37 tests / 94
  assertions, 0 failures. `clojure -M:lint` 0 errors / 0 warnings.
  `clojure -M:run` demo verified end-to-end (one clean lifecycle --
  intake -> verify -> screen -> phase-3 auto-distribute -> correction
  -> retraction -- plus five HARD-hold cases: an unsourced
  distribution, an embargo that has not lapsed, a legally-sensitive
  story's distribution ESCALATING rather than auto-publishing, a
  double distribution, and a correction attempt against a
  never-distributed story). Initial commit `d57c7a3`, pushed to
  `github.com/cloud-itonami/cloud-itonami-isic-6391` (public,
  confirmed via `private: false`).
- `kotoba-lang/industry`: registry + test updated, `clojure -M:test`
  15 tests / 941 assertions, 0 failures; `clojure -M:lint` 0 errors /
  0 warnings. `industry/wave-maturity-summary` confirms 32/32 wave-0
  four-digit classes `:implemented`, 0 remaining. Landed via 2-parent
  GitHub API server-side merge
  `3cd5032abb8b833e74711eac702c7ec97436ac2f` (parents
  `387b2e84ab2e12c173a3ef70de72e604d35eed7b` and
  `d8faa58e95f2dca21e90ccdd42d2a05e4f96d8da`), branch deleted after
  merge.

## Addendum 1 (2026-07-14): timestamp renumbered 2607142100 -> 2607142200

This ADR was initially drafted at timestamp `2607142100`, matching the
then-latest `90-docs/adr/` entries seen at survey time. Before landing,
a fresh `origin/main` sync revealed a concurrent session had already
claimed `2607142100` (`kotoba-banking-open-banking-xs2a-api-layer`) --
so this ADR was renumbered to the first free slot, `2607142200`,
mirroring `cloud-itonami-isic-6130`'s own same-day Addendum 1
(renumbered `2607141500` -> `2607141800` for the identical reason).
The `kotoba-lang/industry` registry entry's test-file comment (landed
at `3cd5032abb8b833e74711eac702c7ec97436ac2f`, before the renumbering
was caught) referenced the stale `2607142100` ID; corrected in a small
follow-up commit, comment-only, no behavior/registry-value change.
