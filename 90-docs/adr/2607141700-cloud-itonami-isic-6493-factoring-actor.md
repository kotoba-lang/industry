# ADR-2607141700: `cloud-itonami-isic-6493` (Factoring activities) -- new actor, registered directly at `:implemented`

- Status: Accepted (2026-07-14)
- Related: ADR-2607032000 (original insurance/real-estate batch,
  `:blueprint`-tier scaffold template); ADR-2607080200 (`6491` financial
  leasing, most recent prior superproject-level "new vertical" ADR --
  establishes the check-family-taxonomy vocabulary this ADR reuses);
  `cloud-itonami-isic-6492`'s own ADR-0001 (Credit-LLM ⊣ Credit
  Governor, the closest operational template); `cloud-itonami-isic-6499`'s
  own ADR-0001 (DD-LLM ⊣ InvestmentCommitteeGovernor, the template for
  this build's two-actuation shape); `cloud-itonami-isic-6493/docs/
  adr/0001-architecture.md` (the authoritative repo-level architecture
  record for this actor -- this superproject ADR records the fleet-level
  context, ISIC citation and registry/manifest bookkeeping)

## Context

The owner asked whether `cloud-itonami` has a factoring business.
Investigation found `cloud-itonami-isic-6499` was originally scaffolded
around factoring/check-cashing/money-order framing but was fully
repurposed in 2026-07 into a venture-capital-fund actor (`vcfund.*`,
ADR-2607061700) -- factoring content no longer exists there, and `6499`
is now firmly `:implemented` as a VC fund (untouched by this ADR).

The owner then asked whether ISIC has a dedicated factoring code.
Verified directly against the OFFICIAL UN ISIC Rev.5 structure CSV and
explanatory-notes PDF (`ISIC5_structure.csv`, `ISIC5_Exp_Notes_
11Mar2024.pdf`, UN Statistics Division Task Team on ISIC, both fetched
from unstats.un.org for this build):

- **ISIC Rev.5 class `6493` = "Factoring activities"** is a genuine,
  distinct, standalone class in division 649, sitting alongside `6491`
  (Financial leasing activities), `6492` (International trade financing
  activities), `6494` (Securitisation activities) and `6495` (Other
  credit granting activities) -- NOT a subset of `6499` ("Other financial
  service activities n.e.c."). Full official explanatory note (verbatim):
  *"6493 Factoring activities -- This class covers the activity of
  purchasing accounts receivable (i.e., invoices) from third parties at
  a discount. This class excludes: debt financing undertaken with
  account receivables as collateral, see 6495."*
- `6493` had ZERO existing footprint anywhere in this superproject: no
  `orgs/cloud-itonami/cloud-itonami-isic-6493` directory, no `kotoba-
  lang/industry` registry entry (not even a `:spec` placeholder -- unlike
  every prior "new vertical" ADR, which promoted an EXISTING registry
  row), no GitHub repo. Re-confirmed immediately before starting work
  (`gh repo view cloud-itonami/cloud-itonami-isic-6493` 404'd; the
  registry grep was empty) and again immediately before `gh repo
  create` (still unclaimed).
- The `https://unstats.un.org/unsd/classifications/Econ/Detail/EN/27/
  6493` deep-link URL pattern (classification ID 27) was tested directly
  and returns HTTP 200 with body text "Classification not found" -- a
  soft-404 (classification ID 27 turns out to be ISIC **Rev.4**, whose
  division 649 only has three classes, `6491`/`6492`/`6499`, with no
  `6493` slot at all; confirmed by also fetching `.../27/6492`, which
  returns REV.4's "Other credit granting" content, not Rev.5's
  "International trade financing"). Per this build's own instruction not
  to fabricate a working deep-link for a Rev.5-only code, this ADR and
  the repo's own docs cite the ISIC Rev.5 structure/explanatory-notes
  documents by name/title instead.

While this build was still at the `facts.cljc` stage (before any
governor/store/operation code existed), the owner surfaced a real 2026-07
news story that reframed the whole design: **全東信 (Zentoshin)**, a
Japanese card-settlement/early-payment-advance company (functionally
factoring-adjacent -- it advanced merchants their card-sales proceeds
early, then collected from the card companies later), filed for
bankruptcy with **JPY 115.1 billion** in liabilities. At least **~20
years of alleged accounting fraud (決算粉飾)** had hidden its true
insolvency the entire time. When it collapsed, **~200,000 merchant
stores nationwide** were affected and **~50,000+ merchants had JPY 5+
billion in payments stuck/unpaid**, with zero warning -- merchants had no
way to independently verify the company's actual solvency; they could
only trust its self-reported books, falsified for two decades. The owner
asked for a factoring design that structurally prevents this class of
failure via three properties: **分散型 (distributed)**, **コード公開 (open
source)**, and **公平 (fair)** -- as first-class, load-bearing
implementation requirements, not README disclaimers.

## Decision

1. `cloud-itonami-isic-6493` is registered as a new repository (public,
   AGPL-3.0-or-later, matching every `cloud-itonami` sibling's
   visibility) with **Factoring-LLM ⊣ Factoring Governor** -- `factoring.*`
   namespaces, modeled closely on `cloud-itonami-isic-6492`'s Store/
   Registry/Governor/Phase/Advisor/Operation/Sim shape (the closest
   operational template: single-entity Store, lending-style lifecycle,
   ground-truth-recompute affordability check) and `cloud-itonami-isic-
   6499`'s multi-actuation shape (the template for this build's TWO
   actuation events, rather than every single-actuation sibling's one).
   Registered DIRECTLY at `:implemented` (no intermediate `:blueprint`
   scaffold stage), since it was built and tested end-to-end before
   registration -- the same posture several recent post-blueprint-tier-
   zero registrations in this registry have taken.
2. **Two actuation events**: advancing real cash to the client
   (`:actuation/advance-cash`) and releasing the reserve to the client
   (`:actuation/release-reserve`), both permanently excluded from every
   phase's `:auto` set by construction -- verified by a dedicated test
   analogous to `6492`'s `loan-disburse-never-auto-at-any-phase` for
   BOTH events independently. Collection (recording the account debtor's
   payment) is deliberately NOT an actuation -- no cash leaves the factor
   there, it is an inbound receipt, not an act the factor originates.
3. **Underwriting inversion**: unlike `credit.governor`'s (`6492`)
   borrower-side affordability check, factoring's credit risk properly
   sits with the ACCOUNT DEBTOR (a third party who never submits a
   proposal to this actor at all), not the client (the seller of the
   receivable, whose own creditworthiness is comparatively unimportant
   since the factor buys the receivable rather than lending against the
   client's balance sheet). `debtor-concentration-ceiling-exceeded-
   violations`'s ground truth is the account debtor's aggregate
   exposure. The LEGAL spec-basis, by deliberate contrast, still follows
   the CLIENT's jurisdiction, mirroring UCC Article 9's own choice-of-
   law rule (perfection follows the location of the party granting/
   selling the interest, not the account debtor) -- a genuine, non-
   symmetric split between legal domicile and credit-risk domicile that
   this fleet has not modeled before.
4. **The one genuinely novel HARD-check contribution, honestly scoped**:
   `factoring.registry/aggregate-exposure` is a pure fn whose ground
   truth is an AGGREGATION over MULTIPLE stored receivable records for
   one counterparty axis (verified DISTINCT from both templates read
   directly for this build -- `credit.governor/affordability-exceeded-
   violations` reads a single record's own fields; `vcfund.governor/
   clawback-exceeds-entitlement-violations` independently recomputes
   from a proposal's claim; neither aggregates across multiple stored
   records). `debtor-concentration-ceiling-exceeded-violations` and
   `funder-concentration-ceiling-exceeded-violations` both reuse the
   EXISTING direct-comparison MAXIMUM-ceiling family established in this
   fleet's check-family taxonomy (per ADR-2607080200's vocabulary:
   direct-comparison MINIMUM-threshold / MAXIMUM-ceiling / two-sided-
   range / ratio-based / unconditional-evaluation-screening) -- this
   build does NOT claim a new comparison family, only a new INPUT shape
   underneath it, and explicitly does NOT claim this is a whole-fleet
   first (only verified against the two templates read directly, not
   audited against the whole ~140-actor fleet).
5. **Three first-class, load-bearing anti-Zentoshin mitigations** (not
   README prose):
   - **Publicly-queryable, ledger-recomputed solvency attestation.**
     `factoring.registry/solvency-report` is a pure ground-truth
     recompute from the actor's own receivable/funder history, exposed
     on the SAME `Store` protocol every read already goes through --
     any client, account debtor, or outside observer with read access
     can independently verify it, the exact capability Zentoshin's
     merchants never had. `solvency-attestation-mismatch-violations`
     refuses to let the advisor publish an attestation whose numbers do
     not match this independent recompute. `solvency-attestation-stale-
     or-missing-violations` refuses EVERY cash advance unless a fresh,
     clean attestation is on file AND a live recompute (including the
     pending advance) still shows solvent.
   - **Distributed, non-custodial funding.** Every receivable is
     attributed to a named, independent funding source; `funder-
     concentration-ceiling-exceeded-violations` blocks the book from
     concentrating too much exposure on any single funder, so no one
     funder's collapse can cascade to the whole client base the way
     Zentoshin's single-balance-sheet failure did.
   - **Fair, non-discretionary pricing.** `factoring.registry/fee-
     schedule` is a published, versioned schedule by account-debtor
     risk tier; `fee-rate-mismatch-violations` blocks any advance whose
     applied rate does not exactly match it -- no privately negotiated
     per-client secret terms.
6. `kotoba-lang/industry`'s registry: brand-new `"6493"` entry (no prior
   `:spec` placeholder existed for this code at all, unlike every prior
   post-batch promotion in this registry) registered directly at
   `:implemented`. Fleet-wide maturity moves 146 -> 147 `:implemented`
   (out of 647 total registry entries, up from 646 -- a genuinely NEW
   class-level entry, not a tier promotion of an existing row).
   `test/kotoba/industry_test.clj`'s `maturity-summary` assertion and a
   dedicated `cloud-itonami-isic-6493 ... is also :implemented` test
   updated to match; `docs/cloud-itonami.md`'s maturity-tier count line
   (which had drifted to a stale 106/36/504 split through many untracked
   promotion ADRs predating this build) refreshed to the actual current
   counts as part of this registration.
7. `manifest/west.yml`'s `industry` project pin advanced via the GitHub
   API single-entry commit workflow (this repo's CLAUDE.md's only
   sanctioned path) to `kotoba-lang/industry`'s new `main` tip, verified
   with `nbb scripts/gen-west-manifest.cljs --check` from a properly
   west-topdir-isolated sibling worktree.
8. **Sequence-ordinal honesty note.** Earlier post-batch ADRs (`6612`
   through `6491`) maintained an explicit "Nth vertical outside
   ADR-2607032000's original batch" hand-counted sequence. That
   convention became impractical to maintain rigorously once the fleet
   passed ~40 verticals and started registering multi-ISIC batches in a
   single ADR (e.g. ADR-2607100400's 8-ISIC petroleum fleet,
   ADR-2607110500's 2-ISIC heavy-industry pair) and, most recently,
   framing new builds around a CRM-family sibling-actor roadmap rather
   than a flat ordinal (`5820`/`6201`/`6202`, none of which state an
   "Nth vertical" ordinal at all). Rather than guess or perpetuate a
   counter that had already silently stopped being maintained
   consistently, this ADR reports the number computed DIRECTLY from the
   registry's own current ground truth: 146 `:implemented` entries prior
   to this build, minus the 8 ISICs in ADR-2607032000's original batch,
   is 138 verticals built outside that batch to date -- this build is
   the 139th, by direct count rather than a continued hand-maintained
   list.

## Known issue, out of scope: `cloud-itonami-isic-6492`'s title/content mismatch

While cross-checking division 649 against the official ISIC Rev.5
structure for this build, a pre-existing classification mislabeling was
found in `cloud-itonami-isic-6492`'s own README, which this ADR does
**not** fix (out of scope for a factoring build; flagged here for a
future ADR):

`cloud-itonami-isic-6492`'s README claims "ISIC Rev.5 6492: Other credit
granting." The OFFICIAL Rev.5 title for class `6492` is actually
**"International trade financing activities"** (explanatory note: *"This
class covers the provision of financial support often combined with
additional services to assist entities in receiving and shipping goods
to and from the rest of the world."*). `6492`'s actual implemented
content (loan-application intake, underwriting, disbursement -- general
consumer/commercial lending) structurally matches official code **`6495`
"Other credit granting activities"** instead (explanatory note lists
exactly this: granting of consumer credit, provision of long-term
finance to industry, money lending outside the banking system, credit
granting by specialized non-depository institutions, pawnbrokers) -- NOT
`6492`. This looks like a classification mislabeling from before Rev.5's
official class-level structure was cross-checked against the live UN
source, predating this build. Per this build's own scope (`cloud-
itonami-isic-6492` is a read-only template, not something this ADR
touches), and per the explicit instruction not to claim `6495` here, this
is recorded as a known issue only -- a future ADR should decide whether
to relabel `6492`'s README/registry entry, or register a genuinely
separate `6495` repository for "Other credit granting."

## Consequences

- (+) Factoring gets the same governed, auditable-actor treatment as
  every prior actor in this fleet, and this superproject now has a
  concrete precedent for a publicly-queryable, ledger-recomputed
  solvency attestation gating a real-money actuation -- motivated by a
  documented, large-scale, real-world failure (Zentoshin), not
  hypothetical hardening.
- (+) The exposure-aggregation primitive (`debtor-exposure`/`funder-
  exposure`/`book-exposure`, one pure fn, three scopes) is a genuine
  structural contribution, honestly scoped against only the two
  templates read directly for this build.
- (+) `kotoba-lang/industry`'s `docs/cloud-itonami.md` maturity-tier
  count line, independently discovered to have drifted ~40 entries from
  the live registry through many untracked promotion ADRs, was refreshed
  to ground truth as a side effect of this registration.
- (+) A citation-accuracy mistake made mid-build (this ADR's own ID was
  referenced as `2607141000` in the registry/test/docs commit before
  that number was confirmed free -- it collided with an unrelated,
  concurrently-landed ADR) was caught and fixed via a small follow-up
  commit + a second west.yml pin advance, rather than left wrong.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `factoring.facts/coverage`
  reports this honestly.
- (-) One funder per receivable in this R0 (no pro-rata multi-funder
  syndication on a single advance) and no on-chain/stablecoin settlement
  rail -- both documented as additive future scope in the repo's own
  ADR-0001, not built here, per the owner's own explicit guidance not to
  let them block or bloat this R0.
- (-) The `.kotoba`/WASM-subset safety-kernel extraction (`credit.
  kernels.gate`/`vcfund.kernels.gate`) both templates read directly for
  this build have is deferred, not ported -- `factoring.governor`/
  `factoring.phase` decide directly in idiomatic `.cljc`, the shape most
  of this fleet's actors still use.
- 63 tests / 268 assertions, lint-clean, demo (`clojure -M:dev:run`)
  verified end-to-end: one clean lifecycle through both actuations
  (gated by a solvency attestation) plus SEVEN HARD-hold cases (stale/
  missing attestation, debtor+funder concentration co-firing, an
  isolated funder-concentration breach, an isolated fee-schedule
  mismatch, spec-basis, sanctions-hit, evidence-incomplete) that never
  reach a human, plus double-advance/double-settle guards -- every
  scenario's actual disposition and violation `:basis` was inspected
  directly against the design before landing.
- Fleet-wide: 147 actors now `:implemented` out of 647 total registry
  entries.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `6493` unregistered | ❌ | Genuine, distinct, correctly-cited, zero-footprint Rev.5 class; the owner's original question ("isn't there an ISIC code for factoring?") is directly answered by building it |
| Continue the hand-maintained "Nth vertical" ordinal counter | ❌ (see Decision 8) | The convention had already silently stopped being consistently maintained by several of the most recent new-vertical ADRs; a direct recount from registry ground truth is more honest than perpetuating or guessing at a stale counter |
| Treat the three anti-Zentoshin mitigations as README-only disclaimers | ❌ | Explicitly rejected by the owner mid-build -- "genuinely load-bearing in the implementation, not just prose." All three are real governor HARD checks with dedicated tests, verified by directly inspecting the demo run's actual dispositions |
| Fix `cloud-itonami-isic-6492`'s title/content mismatch as part of this ADR | ❌ | Out of scope -- `6492` is a read-only template for this build; recorded as a known issue for a future ADR instead of touched here |
| See `cloud-itonami-isic-6493`'s own ADR-0001 Alternatives table for build-level decisions | -- | (kernels-extraction deferral, single-funder-per-receivable R0 scope, on-chain settlement future scope, check-consolidation rejection, etc.) |

## References

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607080200 (`6491`, most recent prior superproject-level "new
  vertical" ADR; establishes the check-family-taxonomy vocabulary)
- `cloud-itonami-isic-6493/docs/adr/0001-architecture.md` (the
  authoritative architecture record for this actor)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
- UN Statistics Division, *International Standard Industrial
  Classification of All Economic Activities, Revision 5 (ISIC Rev.5)*
  structure and *Explanatory Notes* (`ISIC5_Exp_Notes_11Mar2024.pdf`,
  Task Team on ISIC, last update 11 March 2024) -- the source of the
  6493/6491/6492/6494/6495/6499 citations in this ADR
