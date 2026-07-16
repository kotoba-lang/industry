# ADR-2607042900: cloud-itonami-iso3166 — second sweep closing summary (loop concluded)

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki (self-paced `/loop`, standing scaffold authorization)

## Context

This closes out the second run of the self-paced
`/loop 30min 成熟度, coverage を向上` sweep, resumed after ADR-2607040800
(the first closing summary, which ended at country batch 3 / 11
countries + full 19/19 Japan agencies). This second run picked up the
"country-level breadth" item from that ADR's future-work list and ran
country promotion batches 4 through 23 continuously. The owner asked to
close the loop again; this ADR is the record of what this second wave
produced, so a future session can pick up cleanly.

## What this wave built

Twenty consecutive country-level promotion batches (batch 4 through
batch 23, ADR-2607040900 through ADR-2607042800), each promoting 3
countries from `:spec` to `:blueprint` with a web-verified real
procurement portal + business/tax registration system, a matching
`cloud-itonami-iso3166-{code}` blueprint repo, and a paired ADR.

**Country-level coverage grew from 11/193 to 71/193** (roughly 6% to
37% of current UN member states). Every populated continent is
represented, spanning common-law, civil-law, and mixed legal
traditions; EU/EEA-vs-non-EU procurement-regime nuances are explicitly
modeled (`:eu-single-market-access` vs `:eea-single-market-access` vs
country-specific domestic-preference tags). Two full regional clusters
were completed during this wave: the Nordic five (Sweden, Finland,
Norway, Denmark, Iceland) and the Baltic three (Estonia, Latvia,
Lithuania).

**Final registry state** (`kotoba-lang/iso3166`, commit `fa65f73`):

- Total entries: 212 (193 countries + 19 Japan agencies)
- `:blueprint` 90 total: **71/193 countries**, **19/19 Japan agencies
  (COMPLETE, unchanged from the first sweep)**
- `:spec` 122 (all at country level)
- `:implemented` 0
- 13 tests / 885 assertions, all green

**Countries promoted this wave** (batch 4 onward, in landing order):
POL, MEX, SAU, CAN, NZL, ZAF, CHL, NGA, IDN, IRL, NLD, VNM, THA, COL,
ESP, PHL, PER, ITA, BGD, ARG, GHA, FRA, EGY, PAK, TUR, MAR, ETH, SWE,
KAZ, QAT, CHN, CRI, CZE, UKR, ISR, URY, EST, RWA, PAN, GEO, JOR, SEN,
NPL, FIN, TUN, NOR, LKA, BWA, DNK, LVA, ECU, ISL, LTU, ZMB, HUN, HRV,
NAM, SVK, BOL, KHM.

**Repos published this wave**: 60 `cloud-itonami-iso3166-*` blueprint
repos (20 batches × 3), all public, AGPL-3.0-or-later, standalone. The
`kotoba-lang/iso3166` registry lib received 20 commits (one per batch).

## Consequences

- (+) Country-level coverage more than sextupled (11 → 71) while
  maintaining the honesty discipline established in the first sweep:
  every promoted country cites real, web-verified procurement portals
  and business/tax registration systems — no fabricated system names.
- (+) Regional/legal-tradition diversity is broad and deliberate: full
  Nordic and Baltic clusters, deep South America (8 jurisdictions),
  Southeast Asia (6), Southern Africa (3), Central Europe/EU (multiple),
  Gulf (3), Middle East beyond the Gulf, Caucasus, and first
  representation for OHADA-zone West Africa and Central Asia.
- (+) The EEA-not-EU vs EU-member procurement-regime distinction (Norway,
  Iceland vs the EU member states) is modeled explicitly rather than
  collapsed into one "Europe" bucket — a correctness detail that would
  have been easy to skip.
- (+) Every batch's git landing followed the same disciplined worktree +
  server-side-merge pattern with pin verification; no rebases, no
  force-pushes, no lost WIP across 20 batches. All temporary landing
  branches were deleted after merge and confirmed absent from the
  remote at closing time (verified via `gh api .../branches` — zero
  `iso3166-*` branches remain on GitHub; only stale local
  remote-tracking refs needed pruning).
- (−) Country-level coverage is still 71/193 (~37%) — 122 countries
  remain `:spec` registry stubs with no blueprint repo.
- (−) The Japan agency-level pattern (per-ministry/agency leaves) has
  still not been extended to any other country; this remains an open
  design question, not a decision made by this wave.
- (−) No `:implemented` tier exists yet for this family — still a
  distinct, larger future undertaking untouched by either sweep.

## Future work (explicit, for whoever resumes this)

1. **Country-level breadth, continued**: 122/193 countries remain
   `:spec`. Continue promoting in batches of 3, prioritizing
   regions/legal traditions not yet represented — candidates already
   scouted but not yet promoted include Georgia-adjacent Armenia,
   Serbia/Slovenia/other Balkans, Paraguay, Mozambique/Namibia-adjacent
   Southern Africa, Bahrain (Gulf), and remaining Pacific/Caribbean
   island states.
2. **Agency-level depth for another country**: still open from the
   first closing ADR. USA remains the most complex/valuable candidate
   given its federal structure, but scope deliberately — the U.S. has
   dozens of cabinet departments and independent agencies, not a clean
   19-body mapping like Japan.
3. **`:implemented` tier**: none of the 90 blueprint repos (71 country +
   19 Japan agency) has a corresponding running actor yet. Promoting one
   would validate the full maturity ladder this family shares with
   `kotoba-industry` / `kotoba-occupation` / `kotoba-cofog`.
4. Re-entering this via `/loop 30min 成熟度, coverage を向上` again would
   resume the same pattern — read this ADR + `kotoba-lang/iso3166`'s
   README/`docs/cloud-itonami.md` for current state before picking a
   direction.

## Artifacts

- lib (kotoba-lang org, public, Apache-2.0, pure cljc, tests green):
  `kotoba-lang/iso3166` (updated across 20 commits this wave)
- blueprint repos (cloud-itonami org, public, AGPL-3.0-or-later): 60
  published this wave (84 total across both sweeps at country + Japan
  agency levels — see tables in `kotoba-lang/iso3166`'s README.md for
  the full current list)

## References

- ADR-2607032330 (cloud-itonami-iso3166 market-entry-compliance
  blueprints) — family creation.
- ADR-2607040800 (first sweep closing summary) — state at the end of the
  first wave (11/193 countries, 19/19 Japan agencies).
- ADR-2607040900 through ADR-2607042800 — the twenty country promotion
  batches (4 through 23) this second wave ran.
- ADR-2606301900 (ISCO/COFOG organism actors) — the coordinator+leaf
  pattern the Japan extension mirrors (unchanged this wave).
