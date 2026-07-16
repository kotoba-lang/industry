# ADR-2607086900: cloud-itonami-isic-9411 (activities of business and employers membership organizations) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607080100 (`cloud-itonami-isic-9412`, association — origin of
  the Association Governance Governor)
- ADR-2607086100 (`cloud-itonami-isic-9499`, memberorg — origin of the
  single-actuation `publish-position` shape this build's entity/op
  design is modeled on)
- ADR-2607086600 (`cloud-itonami-isic-9512`, commrepair — origin of the
  governor-name-reuse precedent)
- ADR-2607086700 / 2607086800 (applianceshop/9522, socialresearch/7220
  — second and third confirmations of the precedent)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file (every
  prior deepened vertical this fleet)

## Context

`cloud-itonami-isic-9411` ("Activities of business and employers
membership organizations") was a `:blueprint`-tier stub in the
`kotoba-lang/industry` registry, previously flagged as blocked by the
fleet-wide governor-name-collision survey (its own
`:association-governance-governor` keyword is identical to
`association`/9412's). Per the governor-name-reuse precedent
`commrepair`/9512's own ADR-0001 established — confirmed a second
time by `applianceshop`/9522 within the same cluster, and a third time
by `socialresearch`/7220 on a different governor-name family — this
build re-examined whether 9411's own collision was similarly
resolvable: both 9411 and 9412 perform association-governance
oversight of a membership organization, differing only in WHAT
high-stakes act they gate (9412: certifying/disciplining professional
members; 9411: publishing a public advocacy position). This is the
same shared-archetype, different-real-act pattern the precedent
requires.

9411's actual real-world act (publishing a public advocacy position)
turned out to be structurally identical to `memberorg`/9499's own
shape — not `association`/9412's certification/discipline shape — so
this build's entity (`position`) and op set were modeled on
`memberorg`/9499's own architecture instead, following the same
"borrow the entity/op shape from whichever sibling's real act
actually matches" pattern `memberorg`/9499 itself established
relative to `partyops`/9492.

## Decision

Build `bizassoc` (AssocOps-LLM ⊣ Association Governance Governor)
following the exact governed-actor architecture established by
`cloud-itonami-isic-6511` and reused by every subsequent actor in this
fleet: `bizassoc.store` (Store protocol, MemStore + DatomicStore,
proven parity via `store-contract-test`), `bizassoc.registry` (pure
DRAFT-record construction, honest reuse of `position-review-
overdue?` from `memberorg`/9499), `bizassoc.governor` (independent
HARD-check compliance layer, a new `lobbying-registration-
unconfirmed?` check, and a `high-stakes` actuation gate),
`bizassoc.phase` (0→3 rollout table), `bizassoc.assocopsllm` (mock+llm
Advisor pair), `bizassoc.operation` (langgraph StateGraph, generic
shape copied verbatim), and `bizassoc.sim` (demo driver).

The shape is single-actuation (`#{:actuation/publish-position}`),
mirroring `memberorg`/9499's own shape exactly, since this blueprint's
own text names one distinct real-world act: publishing a public
advocacy position on the association's behalf.

`9411`'s own `:itonami.blueprint/governor` keyword,
`:association-governance-governor`, is identical to `association`/
9412's. This is the FOURTH confirmation of the fleet-wide governor-
name-reuse precedent, and the SECOND on a governor-name family other
than `:repair-shop-governor`.

The one genuinely new HARD check —
`lobbying-registration-unconfirmed-violations` — is the SECOND
CONDITIONAL variant of the unconditional-evaluation-discipline family
(after `socialresearch`/7220's own `human-subjects-review-
unconfirmed?`): it activates only when a position's own record
declares `:lobbying-registration-required? true`. A position published
from a jurisdiction with no formal lobbyist/lobbying-organization
registration regime (Japan, honestly, in this R0 catalog) has no such
requirement at all. Grounded in real lobbying-registration/disclosure
law:

- US: Lobbying Disclosure Act of 1995 (2 U.S.C. §1601 et seq.),
  administered jointly by the Clerk of the U.S. House of
  Representatives and the Secretary of the U.S. Senate.
- UK: Transparency of Lobbying, Non-Party Campaigning and Trade Union
  Administration Act 2014, Part 1 (Register of Consultant Lobbyists),
  administered by the Registrar of Consultant Lobbyists.
- Germany: Lobbyregistergesetz (2021), administered by the Deutscher
  Bundestag (Bundestagsverwaltung).

This is the 64th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently
`socialresearch.governor/human-subjects-review-unconfirmed-
violations` at 63rd).

This check deliberately does NOT reuse `memberorg.governor/tax-
exempt-status-risk-unresolved-violations`, despite both concerning a
membership organization's public advocacy act: trade/business
associations are typically structured as business leagues (US IRC
§501(c)(6)) or member-governed corporate/association-law entities
elsewhere, which face no equivalent charitable-status-jeopardizing-
from-lobbying concern — lobbying on behalf of member businesses is
their express, permitted purpose. The load-bearing regulatory concern
for this domain is instead the procedural lobbyist-registration duty,
a genuinely distinct legal regime.

One check is an honest, literal reuse, not claimed as new:
`position-review-overdue?` (the 15th MAXIMUM-ceiling instance, from
`memberorg`/9499's own 14th instance).

## Consequences

- `cloud-itonami-isic-9411` moves from `:blueprint` to `:implemented`
  in `kotoba-lang/industry`'s registry (fleet maturity: 79 → 80
  implemented, 6 → 5 blueprint).
- The governor-name-reuse precedent is now confirmed a fourth time,
  and for the second time on a governor-name family other than
  `:repair-shop-governor` — reinforcing it as a general, fleet-wide
  pattern rather than isolated to one or two clusters.
- 34 tests / 134 assertions pass in `bizassoc`; lint is clean; the
  demo (`clojure -M:dev:run`) walks one clean actuation lifecycle plus
  four HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 127
  assertions) was re-run clean before committing the promotion. Its
  own test suite's "still-blueprint" example entry was swapped from
  `"9411"` to `"8522"` in three places (`maturity-tier`,
  `maturity-roadmap-reports-next-step`,
  `execution-plan-reports-ui-export-readiness`), since 9411 is no
  longer blueprint-tier after this promotion.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note

`:fleet-maturity-before`/`:fleet-maturity-after` in the companion
`.edn` reflect the actual observed sum of the three maturity tiers in
`registry.edn` at build time (630 total), not
`docs/cloud-itonami.md`'s separately-tracked "Total entries: 643"
figure — the same clarification made in every prior ADR this fleet
(2607085700 through 2607086800).

## Alternatives considered

- **Reusing `memberorg.governor/tax-exempt-status-risk-unresolved-
  violations` directly** instead of designing a new check. Rejected:
  trade/business associations face no equivalent charitable-status-
  jeopardizing-from-lobbying concern — a mechanical copy would not be
  grounded in this vertical's actual regulatory reality.
- **An unconditional lobbying-registration check.** Rejected: not
  every jurisdiction has a formal lobbyist-registration regime —
  Japan, honestly, does not in this R0 catalog. Forcing the check onto
  every position regardless of jurisdiction would fabricate a
  requirement.
- **Borrowing `association`/9412's own certification/discipline
  entity/op shape** instead of `memberorg`/9499's publish-position
  shape, purely because they share a governor name. Rejected: the
  entity/op shape must match the blueprint's own actual text (which
  names publishing an advocacy position as the one real-world act),
  not merely follow whichever sibling happens to share the governor
  keyword.

## References

- `cloud-itonami-isic-9411/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-9499/docs/adr/0001-architecture.md` (origin of
  the single-actuation `publish-position` entity/op shape)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md` (origin of
  the governor-name-reuse precedent)
- Lobbying Disclosure Act of 1995 (2 U.S.C. §1601 et seq., US)
- Transparency of Lobbying, Non-Party Campaigning and Trade Union
  Administration Act 2014, Part 1 (UK)
- Lobbyregistergesetz (2021, Germany)
