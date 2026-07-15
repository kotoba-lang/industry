# ADR-2607191000: cloud-itonami-isic-0130 (Plant propagation) nursery-operations-coordination actor -- fixed a broken prior attempt, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0164 (Seed processing for propagation --
the reference module shape this actor mirrors, independently re-read in
full before use), the `kotoba-lang/industry` registry's `"0130"` catalog
entry (previously `:spec` with a note recording that a prior attempt was
reverted for being broken)

## Context

`kotoba-lang/industry`'s registry carried a `"0130"` entry at `:maturity
:spec`, but unlike a typical placeholder gap, a real repo already
existed: `cloud-itonami/cloud-itonami-isic-0130` (pushed 2026-07-14,
commit `3d450370a28e`). The registry entry's own inline comment recorded
why the promotion never landed: "REVERTED 2026-07-14: has deps.edn added
by hand for verification but src/propagation/facts.cljc uses JVM-only
`(new Date ...)`, a cljs-first violation and compile failure -- confirmed
broken by direct test run." This ADR does not assume that note was
accurate or complete -- the existing repo was cloned and read in full
before any decision was made.

Direct inspection confirmed the repo was, in fact, broken in more ways
than the note recorded: `facts.cljc` used unconditional `js/Date.` (a
cljs-only call with no `:clj`/`:cljs` reader conditional, so it fails to
compile/resolve under plain JVM Clojure, which is how `clojure -M:test`
actually runs); `deps.edn`, `blueprint.edn`, `GOVERNANCE.md`,
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, and `SECURITY.md` did not exist
at all; `test/propagation/` had only `governor_test.cljc` and
`operation_test.cljc` (no `facts_test`/`phase_test`/`registry_test`/
`store_test`); and the architecture itself diverged substantially from
this fleet's required domain design -- the proposal op names
(`:log-batch-record`/`:schedule-field-operation`/
`:flag-plant-health-concern`/`:order-supplies`) did not match the
specified allowlist (`:log-propagation-batch`/`:schedule-maintenance`/
`:flag-quality-concern`/`:coordinate-shipment`), and the store was an
atom-backed `defprotocol`/`defrecord` design rather than the pure
immutable-data shape every other implemented cloud-itonami actor in this
fleet uses. A `docs/adr/0001-architecture.md` already checked into the
repo also cited two ADR numbers (`ADR-2607141200`, `ADR-2607151800`) that,
on inspection of this superproject's `90-docs/adr/`, belong to unrelated
topics (isic-6611 cryptoexchange; isic-1040/isic-1071) -- both references
were fabricated, never real.

This is part of an ongoing careful, smaller-batch rollout (one ISIC class
per agent, a capable model, mandatory verification) after a prior
18-agent haiku batch produced a 61% defect rate on other ISIC classes;
60+ consecutive agents on this stricter protocol have all succeeded
since. This ADR covers ISIC 0130 only. Before any work began, the
registry entry's identity (`{:id "0130" :name "Plant propagation"}`) was
independently verified against a fresh clone, per this fleet's caution
against ID/name mismatches -- confirmed correct, no mismatch.

## Decision

Given how much was missing or architecturally divergent, rebuild
`cloud-itonami/cloud-itonami-isic-0130` **in place on the same repo**
(same GitHub repo/URL, new commits on top of the existing history rather
than a fresh `gh repo create`) as a plant-propagation-NURSERY OPERATIONS
COORDINATION actor (not direct nursery-equipment operation authority),
mirroring `cloud-itonami-isic-0164`'s verified module shape (`facts`/
`registry`/`store`/`governor`/`operation`/`phase`/`advisor`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/
SECURITY) with fresh, plant-propagation-specific domain logic under the
existing `propagation` namespace (kept, since it was already correctly
named for this domain). A nursery propagates plants FOR SALE OR
TRANSPLANT (cuttings, tissue-culture plantlets, grafted stock, seedlings
raised specifically to be propagated onward) -- this is what distinguishes
ISIC 0130 from growing-to-harvest of a specific commodity crop (e.g. ISIC
0111/0121), and it drives every domain-specific check below:

1. **`propagation.facts`** -- propagation-method-type viability/readiness
   windows (`rooting-min-percent`/`hardening-min-days`/
   `genetic-fidelity-verification-required`, by method id: softwood/
   hardwood cutting, whip-and-tongue/bud graft, tissue-culture
   micropropagation, direct seed-sown) and jurisdiction phytosanitary-
   certificate/evidence-checklist requirements (JP/US/EU). A
   per-propagation-source genetic-fidelity risk table (own-root cuttings
   carry no risk; grafted and tissue-cultured sources carry a real
   off-type/somaclonal-variation risk) drives the label-completeness
   check, replacing 0164's GM-trait table with the domain-correct
   genetic-fidelity concern for nursery stock.
2. **`propagation.registry`** -- pure, host-clock-free validation
   predicates the Governor uses to independently verify physical/
   operational constraints: rooting/take/germination-rate floor,
   hardening-off-period floor, genetic-fidelity-declaration risk, pest/
   pathogen detection, phytosanitary-inspection currency (90-day
   interval), and sanitation-score floor.
3. **`propagation.store`** -- rebuilt from an atom-backed
   `defprotocol`/`defrecord` design to plain immutable data (`{:batches
   {...} :facts [...]}`), matching every other implemented actor's shape
   in this fleet, with batch lookup/registration/logged/
   shipment-finalized flags and an append-only audit ledger.
4. **`propagation.governor`** (renamed conceptually to
   `NurseryOperationsGovernor`, kept from the prior attempt's naming) --
   14 independently-verified hard-violation checks plus a closed
   operation allowlist as a hard, permanent block per the domain design:
   the advisor may only ever propose `:log-propagation-batch`,
   `:schedule-maintenance`, `:flag-quality-concern`,
   `:coordinate-shipment` (all `:effect :propose`); anything else --
   most importantly direct greenhouse/irrigation/propagation-equipment
   control -- is refused unconditionally (`:op-not-allowed`), never a
   soft escalation. A `:batch-not-registered` hard check is applied
   across ALL FOUR allowed ops (broader than 0164's coordinate-shipment-
   only registration check), per this actor's explicit domain-design
   requirement that "nursery/batch record must be independently
   verified/registered before any action." `:flag-quality-concern`
   always escalates to a human regardless of confidence, as do the two
   real actuation events (`:log-propagation-batch`/
   `:coordinate-shipment`).
5. **`propagation.phase`** -- rebuilt from a bespoke 0-3 rollout-gate
   design to `:intake -> :propagate -> :root -> :harden -> :inspect ->
   :audit -> :archived`, a plant-propagation-specific phase sequence
   using the same portable `keep-indexed`-based `index-of` helper 0164
   introduced (not the JVM-only `.indexOf`).
6. **`propagation.advisor`** -- replaces the prior attempt's
   `propagationadvisor.cljc` (a stateful `defrecord`/`defprotocol`
   `MockAdvisor`) with a documentation-only skeleton matching 0164's own
   current shape; `propagation.operation/run-operation` takes an
   already-formed proposal plus an injected `governor-fn` rather than
   internally invoking an advisor.
7. **`deps.edn` / `blueprint.edn` / docs** -- all created fresh (none
   existed before), mirroring `cloud-itonami-isic-0164`'s shape
   (`:test`/`:lint`/`:run` aliases, `itonami.blueprint/*` metadata,
   scope/design/testing README sections). `:itonami.blueprint/robotics`
   is honestly `false` (this actor holds no equipment-control authority).
   The stale `docs/adr/0001-architecture.md` (citing two fabricated ADR
   numbers) was deleted rather than corrected in place, since the
   reference pattern (`cloud-itonami-isic-0164`) does not carry a
   `docs/adr/` directory at all -- architecture decisions for this fleet
   live in this superproject's `90-docs/adr/`, not duplicated per-repo.

### What this actor does NOT do

Greenhouse climate-control systems, irrigation/misting systems, and
grafting/cutting-tool operation remain exclusive to licensed nursery
staff, permanently, with no actor or human-approval override path --
enforced structurally by the closed operation allowlist, not just
documented. This actor also does not perform phytosanitary-certification
(human inspector/regulator only) or growing-to-harvest of a specific
commodity crop (separate, already-implemented ISIC classes in this
fleet, e.g. 0111/0121).

## Verification

- `cloud-itonami-isic-0130`: `clojure -M:test` -- raw final line: `Ran
  43 tests containing 134 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- Grepped for stray JVM-only interop (`.indexOf`, `java.`, unguarded
  `System/`, unguarded `js/`) outside `#?(:clj ...)`/`#?(:cljs ...)`
  reader conditionals -- none found; every host-clock call
  (`System/currentTimeMillis` / `js/Date.now`) is behind a `:clj`/`:cljs`
  reader conditional.
- Rebuilt on a feature branch (`rebuild-0130-plant-propagation`) off the
  existing repo's `main`, pushed, and landed via GitHub API server-side
  merge (`gh api repos/cloud-itonami/cloud-itonami-isic-0130/merges`),
  merge commit `7af86d4aa1658aa85cf5230ecde456b46d600ab4`. Confirmed via
  the GitHub compare API: `ahead_by 0, behind_by 0, status "identical"`
  against `main`. Independently re-verified against a brand-new fresh
  clone: `Ran 43 tests containing 134 assertions.` / `0 failures, 0
  errors.`
- `kotoba-lang/industry` registry `"0130"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`; `:repo`/`:business-id` were
  already correct and left unchanged; `:operating-states` corrected from
  a generic actor-lifecycle placeholder
  [`:intake :propose :govern :approve :record :audit`] to the real
  batch-lifecycle sequence [`:intake :propagate :root :harden :inspect
  :audit`], matching how 0164's own registry entry mirrors its
  `phase.cljc`; the stale comment citing two fabricated ADR numbers was
  replaced with an accurate one). `test/kotoba/industry_test.clj`'s
  `maturity-summary` assertion bumped (live-recomputed via
  `(industry/maturity-summary)` against a freshly re-fetched
  `origin/main` immediately before each edit -- not assumed). First
  attempt (251 -> 252 relative to a stale local base) hit a genuine `409
  Merge conflict` against a concurrent promotion
  (`cloud-itonami-isic-1104`) that landed on `main` in the interim --
  rather than fight a textual 3-way merge on the shared registry/test
  files, the branch was discarded, `main` was re-fetched (confirming
  `:implemented` had moved 250 -> 251 from the concurrent promotion), the
  same targeted edit was redone against the new tip (251 -> 252), and
  pushed on a fresh branch. Landed via GitHub API server-side merge (`gh
  api repos/kotoba-lang/industry/merges`) on the second attempt, merge
  commit `ef797d457a5a671f2aece8c7676368c34ad2eb4b`. Full
  `kotoba-lang/industry` suite re-run green pre-merge (on the branch):
  `Ran 15 tests containing 953 assertions.` / `0 failures, 0 errors.`
- Post-merge re-verification: freshly re-fetched `origin/main`, re-cloned
  into a brand-new scratch directory at merge commit
  `ef797d457a5a671f2aece8c7676368c34ad2eb4b` (plus a fresh
  `kotoba-lang/technology` sibling clone for `deps.edn` resolution),
  re-ran `clojure -M:test` against the clean post-merge clone: `Ran 15
  tests containing 953 assertions.` / `0 failures, 0 errors.`
  `grep -c "â" resources/kotoba/industry/registry.edn` returned `0` (no
  file-wide UTF-8 mojibake). Confirmed via the GitHub compare API:
  `ahead_by 0, behind_by 0, status "identical"` against `main`.

## Consequences

(+) `cloud-itonami-isic-0130` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
repo that had a clean `git log` but a broken/incomplete/architecturally-
divergent working tree behind it.
(+) `kotoba-lang/industry` registry `"0130"` entry promoted to
`:maturity :implemented`, count 251 -> 252.
(+) This ADR is a worked example of the "check the existing repo
honestly -- do not assume either way" discipline this fleet's stricter
protocol requires: the registry comment's own diagnosis (JVM-only `(new
Date ...)`) was a real but incomplete description of the breakage
(current code used unconditional `js/Date.`, and the deeper problems were
architectural divergence and missing files, not just one bad call), and
two ADR numbers cited in the repo's own `docs/adr/0001-architecture.md`
turned out to be fabricated -- independent verification against this
superproject's actual `90-docs/adr/` caught both.
(+) The `:batch-not-registered` hard check applied uniformly across all
four allowed ops (rather than only the highest-stakes op, as 0164 does)
is a reusable pattern for any future ISIC class in this fleet whose
domain design explicitly states a registration-before-any-action
invariant, as distinct from 0164's narrower shipment-only registration
check.
(-) `propagation.advisor` remains a documentation-only stub (no
`MockAdvisor` implementation); `propagation.operation/run-operation`
takes an already-formed proposal plus an injected `governor-fn` rather
than internally invoking an advisor. This matches
`cloud-itonami-isic-0164`'s own current shape and is a natural,
contained future extension, not required for this ADR's verification
bar.
