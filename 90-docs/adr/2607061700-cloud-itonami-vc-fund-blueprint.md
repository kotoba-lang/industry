# ADR-2607061700: cloud-itonami-vc-fund — venture capital fund blueprint (DD-LLM ⊣ InvestmentCommitteeGovernor)

**Status**: accepted
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

The owner asked whether `cloud-itonami` had already designed the venture
capital business itself — LP fundraising, due diligence (DD), portfolio
operations, and exit (including YC-style benchmarking). Investigation found:

1. `cloud-itonami` (`gftdcojp/cloud-itonami`) is the business-operating OS
   for gftdcojp's *own* venture portfolio (BMC canvas + Lean BML loop +
   YCBench self-scorecard, ADR-0010 in that repo) — it runs gftdcojp's
   businesses, it is not itself a VC investing in outside startups.
   ADR-0010's funding ledger (`funding.cljc`) is deliberately **non-equity**
   (`guard-non-equity!` structurally refuses any payload carrying an
   equity/profit-share/cap-table key, to stay outside Japan's FIEA
   集団投資スキーム持分 classification) — the opposite of what a VC LP fund
   requires.
2. `kotoba-lang/industry`'s `registry.edn` already gives **100% ISIC Rev.4
   class coverage** (643 entries: 425 classes + 218 groups). The finance
   codes closest to "venture capital" — `6420` (holding companies), `6430`
   (trusts/funds — the passive fund *vehicle*, explicitly excludes fund
   management per its own explanatory note), `6630` (fund management
   activities — fee-based management *for* investors) — are all already
   published `cloud-itonami-isic-*` blueprint repos with unrelated generic
   content. None can be reused for a new VC blueprint without overwriting a
   live public repo.
3. UN ISIC Rev.4's own explanatory note for `6499` ("Other financial service
   activities, except insurance and pension funding activities, n.e.c.")
   reads: *"...own-account investment activities, such as by venture
   capital companies, investment clubs etc."* — verified directly against
   `https://unstats.un.org/unsd/classifications/Econ/Detail/EN/27/6499`
   (2026-07-06). This is the internationally correct classification for a
   VC fund's own-account investing activity, and it is *also* already a
   published, differently-scoped `cloud-itonami-isic-6499` repo (factoring /
   check-cashing / money-order framing).

So a VC blueprint cannot be added as a new ISIC-numbered entry: every
candidate number is both classification-imprecise for the full VC lifecycle
(no single ISIC class distinguishes "fund vehicle" vs "GP management fee"
vs "GP own-account carry-generating investing" — a real VC firm spans
`6430` + `6630` + `6499` simultaneously) and already claimed by an unrelated
published repo.

## Decision

### 1. `cloud-itonami-vc-fund` — a new, non-ISIC-numbered blueprint

Follows the precedent already set by `cloud-itonami-isco-*` (ADR-2607012000)
and `cloud-itonami-cofog-*` (ADR-2607031600): when ISIC granularity or
numbering can't host a business cleanly, publish a plainly-named blueprint
outside the ISIC numbering instead of overloading/overwriting an existing
ISIC entry. `kotoba-lang/industry`'s `registry.edn` is **not** touched by
this ADR (same as isco/cofog/gtin/unspsc/iso3166 — that registry stays a
strict ISIC Rev.4-only ledger, 643/643).

The blueprint's `docs/business-model.md` and `docs/adr/0001-architecture.md`
cite ISIC `6499`'s own-account-investment note as its spec-basis, and note
the adjacency to (but non-overwrite of) `6430`/`6630`.

### 2. Actor pair: `DD-LLM ⊣ InvestmentCommitteeGovernor`

Structurally identical to the fleet's other `:implemented`-tier actors
(`cloud-itonami-isic-6511`'s Underwriter-LLM ⊣ UnderwritingGovernor is the
direct template port): a langgraph-clj StateGraph, one run = one operation,
the LLM sealed into a single `:advise` node, every proposal censored by an
independent governor before touching the SSoT.

Lifecycle ops, matching the owner's four named stages (LP募集 / DD / 運用 /
exit) plus the KYC/sanctions split the insurance template already
established as a separately-gated HARD check:

| op | stage | actuation? |
|---|---|---|
| `:lp/intake` | LP募集 — subscription/commitment intake, capital account | no |
| `:kyc/screen` | (DD) — AML/sanctions screening, LPs and portfolio-company parties | no |
| `:dd/assess` | DD — deal due-diligence checklist vs. a named framework's spec-basis | no |
| `:investment/commit` | 運用 — Investment Committee capital deployment into a portfolio company | **yes** |
| `:exit/distribute` | exit — exit proceeds + LP waterfall distribution | **yes** |

Unlike the insurance template (exactly one `:actuation` member, "actuation
is not a spectrum"), a VC fund has **two** independent real-money-movement
events — capital going out to a portfolio company, and proceeds coming back
to LPs — so `high-stakes` has two members here. Each is still absolute (not
a gradation): `:investment/commit` and `:exit/distribute` are never in any
phase's `:auto` set, at any phase, by construction — the same two-layer
invariant (`governor` + `phase`) as every other actor in the fleet.

### 3. HARD checks add one VC-specific rule beyond the insurance template

Spec-basis, sanctions-hit and DD-incomplete carry over unchanged. A new
HARD check, `accredited-investor-violation`, blocks `:investment/commit`
when an LP's subscription record has no recorded accredited-investor/
qualified-purchaser affirmation — a genuine securities-law requirement (US
Reg D 506(b)/(c), Investment Company Act §3(c)(1)/3(c)(7)) that has no
analog in the life-insurance template. (ADR-0010's non-equity guard does
NOT carry over here: a VC fund legitimately deals in real securities, so
the correct posture is the opposite of ADR-0010's — assume real securities
compliance applies, the same way the insurance blueprint assumes a real
insurance license applies.)

### 4. Facts catalog reuses the same four seed jurisdictions as insurance

`vcfund.facts/catalog` seeds JPN (金融商品取引法 適格機関投資家等特例業務 —
QII exemption, FSA), USA (SEC Regulation D 506(b)/(c) + ILPA DDQ/Reporting
Template + NVCA model documents), GBR (FCA sub-threshold AIFMD regime), DEU
(BaFin / KAGB, EuVECA opt-in) — the same four jurisdictions
`cloud-itonami-isic-6511`'s `underwriting.facts` seeded, for fleet-wide
consistency. Coverage is reported honestly (`vcfund.facts/coverage`), same
discipline as every other blueprint's facts catalog.

### 5. Waterfall distribution is deal-by-deal, not whole-fund — documented, not silent

`vcfund.registry/distribute-waterfall` computes return-of-capital,
preferred-return, and GP carry split for **one investment record**. A real
whole-fund European waterfall (cross-deal netting, GP clawback) needs
fund-level state beyond a single investment and is out of scope for this R0
— flagged explicitly in the docstring and README, the same honesty
`underwriting.registry` used for not inventing a global policy-number
check-digit standard.

### 6. Maturity: `:implemented` from R0

Per the owner's explicit choice: full governed-actor Clojure implementation
(not blueprint-markdown-only), matching `cloud-itonami-isic-6511`'s bar —
`src/vcfund/{facts,registry,ddllm,governor,phase,operation,sim,store}.cljc`
+ `test/vcfund/*_test.clj`, `MemStore` ‖ `DatomicStore` parity, clj-kondo
clean.

## Consequences

- (+) The owner's original question is now actually answered with running
  code, not just a blueprint description: a forkable VC-fund actor exists,
  covering LP intake → DD → capital deployment → exit distribution, each
  step governed and audited.
- (+) `kotoba-lang/industry`'s ISIC-only invariant (643/643, no foreign
  entries) stays intact — this ADR does not touch `registry.edn`.
- (+) Reuses the exact fleet-proven actor shape (langgraph-clj StateGraph,
  Store protocol, phase 0→3, hold/escalate/commit) rather than inventing a
  new architecture for one more vertical.
- (−) R0 seeds only 4 jurisdictions' fund-formation/exemption regimes, out
  of ~194 worldwide — reported honestly via `vcfund.facts/coverage`, not
  claimed as global.
- (−) The waterfall calculation is deal-by-deal, not a real whole-fund
  European waterfall with GP clawback — a real fund administrator still
  needs a proper fund-accounting system; this actor supplies the governed,
  audited decision scaffold, not a fund-accounting replacement.
- (−) Real accredited-investor verification, real KYC/AML screening
  provider integration, and real cap-table/fund-accounting system
  integration are out of scope for this OSS actor, same as every sibling
  `:implemented` entry in the fleet.
- Registration: `cloud-itonami-vc-fund` is published standalone under the
  `cloud-itonami` GitHub org (public, AGPL-3.0-or-later), same as every
  other `cloud-itonami-*` blueprint — **not** added to `manifest/west.yml`
  (this whole fleet is intentionally outside west management) — noted only
  as a doc-comment in `manifest/repos.edn`, matching the existing
  `cloud-itonami-isco-*`/`cloud-itonami-cofog-*` precedent.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Overwrite `cloud-itonami-isic-6499` with VC-specific content | ❌ | Discards its existing published factoring/check-cashing/money-order framing; overwrites a live public repo for a classification-precise but scope-narrowing rewrite |
| Overwrite `cloud-itonami-isic-6420` ("holding companies") | ❌ | Not even the correct ISIC citation for VC own-account investing (that's 6499's explanatory note, not 6420's) — would be both wrong and destructive |
| Add `cloud-itonami-vc-fund` to `kotoba-lang/industry`'s `registry.edn` under a synthetic non-numeric id | ❌ | Breaks the registry's stated ISIC Rev.4-only 643/643 invariant; no precedent (isco/cofog/gtin/unspsc/iso3166 all stay out of this specific registry too) |
| Publish blueprint-tier only (markdown, no running code) | ❌ | Owner explicitly asked for the full `:implemented` governed-actor tier, matching `cloud-itonami-isic-6511`'s bar |

## References

- `cloud-itonami-isic-6511` ADR-0001 (Underwriter-LLM ⊣ UnderwritingGovernor)
  — the direct template this ADR ports.
- ADR-2607012000 (`cloud-itonami-isco-*`) / ADR-2607031600
  (`cloud-itonami-cofog-*`) — precedent for a non-ISIC-numbered blueprint
  family living outside `kotoba-lang/industry`'s registry.
- `gftdcojp/cloud-itonami` ADR-0010 (non-equity funding ledger) — the
  design this repo deliberately does NOT reuse (a VC fund legitimately
  issues real securities; `guard-non-equity!` does not apply here).
- UN ISIC Rev.4 code 6499:
  `https://unstats.un.org/unsd/classifications/Econ/Detail/EN/27/6499`
  (verified 2026-07-06).

## Addendum (2026-07-06, same day): consolidated into cloud-itonami-isic-6499

The owner pushed back on publishing outside the ISIC-numbered naming
convention and asked to reconsider whether `6499` was really unavailable.
On inspection, `cloud-itonami-isic-6499` (published 2026-07-04) was a
two-commit, zero-fork, zero-star, code-free `:blueprint`-tier scaffold
illustrated with a different member of the same n.e.c. bucket (factoring /
check-cashing / money-order issuance) -- not meaningfully "occupied," and
`6499`'s own explanatory note names venture capital first among its
examples. Decision reversed from "Alternatives considered" above:

1. `cloud-itonami-isic-6499`'s content (README, blueprint.edn, docs,
   GOVERNANCE/CONTRIBUTING/SECURITY) was replaced with the venture-fund
   business description, and the full `vcfund.*` implementation
   (`src/`, `test/`, `deps.edn`) was moved in as-is (namespace unchanged --
   `vcfund.*` is a domain word, not tied to the ISIC number, same
   convention as `underwriting.*`/`realty.*`/`formation.*` on their own
   ISIC-numbered repos). 33 tests / 153 assertions, lint clean, re-verified
   in the new location before push.
2. The standalone `cloud-itonami-vc-fund` repo was deleted (`gh repo
   delete`) -- it had existed for under an hour, this same session, no
   forks/stars/clones to worry about.
3. `kotoba-lang/industry`'s `registry.edn` entry for `"6499"` was promoted
   `:maturity :blueprint` → `:implemented` (its own established
   maturity-roadmap: `:spec` → `:blueprint` → `:implemented`), via a
   worktree branch + server-side merge (the shared west checkout was in
   detached HEAD; direct commits to it are exactly what the superproject's
   "並行エージェント運用" convention warns against). `:required-technologies`
   switched `:banking` → `:securities` to match the actual domain.
   `docs/cloud-itonami.md`'s maturity-tier counts and
   `test/kotoba/industry_test.clj`'s hardcoded `:implemented` count (4→5)
   were updated in the same commit; both re-verified (7 tests / 53
   assertions green).
4. `manifest/west.yml`'s pin for the `industry` project was advanced via
   `nbb scripts/gen-west-manifest.cljs --entry industry` (single-entry,
   server-verified fast-forward) to include the promotion commit.
5. This ADR's decision text above (the "publish standalone, non-ISIC"
   reasoning and its "Alternatives considered" table) is left unedited as
   the historical record of the first attempt; this addendum is the
   corrected final state. `manifest/repos.edn`'s doc-comment (originally
   written to describe the standalone repo) was updated in the same spirit
   to point at the consolidated `cloud-itonami-isic-6499` entry instead.

Net effect: one venture-capital-fund actor, at `cloud-itonami-isic-6499`,
`:implemented` maturity, ISIC-numbered like every sibling blueprint, with
no dangling standalone repo left behind.

## Addendum 2 (2026-07-06, same day): `cloud-itonami-isic-6430`/`6630` promoted, real cross-repo integration designed

The owner asked whether `cloud-itonami-isic-6499` was itself "連携/連動"
(linked/interoperating) with any other ISIC classification. Investigation
found `6430` (trust/fund vehicle) and `6630` (fee-based fund management)
were named as "adjacent" in `cloud-itonami-isic-6499`'s own repo-level
ADR (`docs/adr/0001-architecture.md` §6), but the relationship was
documentation-only: both were still `:blueprint`-tier markdown stubs with
zero lines of code, so `6499` exchanged no actual data with either. Asked
to design real interoperation, the owner picked the largest of three
offered scopes: implement BOTH as real governed actors and wire them to
genuinely interoperate with `6499`.

1. **`cloud-itonami-isic-6430`** promoted `:blueprint` → `:implemented`:
   `trustfund.*` (TrustAdmin-LLM ⊣ TrustFundGovernor) -- the fund vehicle,
   the legal entity that actually holds LP subscriptions and issues the
   binding capital-call NOTICE off an upstream `6499` proposal. 26 tests /
   116 assertions, lint-clean.
2. **`cloud-itonami-isic-6630`** promoted `:blueprint` → `:implemented`:
   `fundmgmt.*` (FundManager-LLM ⊣ FundManagementGovernor) -- the
   management company, the GP entity that draws the management fee `6499`
   computes as an accrual. 25 tests / 98 assertions, lint-clean.
3. **The integration is a documented DATA CONTRACT, not shared code**:
   each downstream actor's governor independently RE-VERIFIES an upstream
   `6499` fact rather than trusting it -- `trustfund.governor`
   independently re-derives the SAME pro-rata-by-commitment-share math
   `vcfund.registry/capital-call-allocations` computes (a deliberately
   separate re-implementation); `fundmgmt.governor` independently
   reapplies the SAME flat-rate fee formula `vcfund.nav/management-fee-
   accrued` computes, AND checks the claimed rate against its own
   recorded LPA mandate cap (a check `6499` has no concept of at all).
   Neither new repo imports or requires `vcfund.*` -- the same
   "self-contained sibling" posture `underwriting.*` already has toward
   `kotoba-lang/insurance`.
4. **`cloud-itonami-isic-6499` needed almost no change** -- its existing
   pure functions already produced the exact fact shapes needed. One
   small, additive change: `vcfund.nav/fund-nav-report`'s return now also
   exposes `:fee-basis`/`:annual-fee-rate`/`:years-elapsed` (previously
   internal-only), so a downstream fee-drawdown actor doesn't have to
   separately re-derive the fee basis. Purely additive, all 164
   pre-existing tests pass unmodified.
5. `kotoba-lang/industry`'s `registry.edn` entries for `"6430"`/`"6630"`
   were promoted `:blueprint` → `:implemented`, via a worktree branch +
   server-side merge (same convention as `6499`'s own promotion in
   Addendum 1 -- the shared checkout stays browse-only).
   `docs/cloud-itonami.md`'s maturity-tier counts (5→7 implemented,
   92→90 blueprint) and `test/kotoba/industry_test.clj`'s hardcoded
   `:implemented` count (5→7) were updated in the same commit; 7 tests /
   55 assertions re-verified green.
6. `manifest/repos.edn`'s doc-comment for the finance-repo batch was
   extended to describe `6430`/`6630`'s promotion alongside `6499`'s.

Net effect: a real, tested three-actor VC-fund system --
`cloud-itonami-isic-6499` (investment decisions), `cloud-itonami-isic-
6430` (fund vehicle), `cloud-itonami-isic-6630` (management company) --
each independently forkable and deployable, interoperating through a
documented fact contract rather than shared code, each with its own
independent-re-verification governor check proven by tests and demos,
not merely asserted.
