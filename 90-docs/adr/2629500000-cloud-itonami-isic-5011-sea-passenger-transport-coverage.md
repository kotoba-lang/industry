# ADR-2629500000: cloud-itonami ISIC 5011 (Sea and coastal passenger water transport) coverage

## Status

Accepted. `cloud-itonami/cloud-itonami-isic-5011` promoted from
`:blueprint` to `:implemented` in the `kotoba-lang/industry` registry.

## Context

`kotoba-lang/industry`'s registry entry `{:id "5011" ...}` resolves to
`:name "Sea and coastal passenger water transport"`
(`:business-id "cloud-itonami-5011"`,
`:repo "https://github.com/cloud-itonami/cloud-itonami-isic-5011"`,
verified against the live registry before starting this work).

Unlike most `cloud-itonami-isic-*` actors built in this fleet's recent
batches, `cloud-itonami-isic-5011` was NOT a fresh 404 target: the
GitHub repository already existed as a legitimate `:blueprint`-tier
scaffold, published by `ADR-2607102400` (the THIRTEENTH "author a new
blueprint from `:spec` tier" build, closing out a three-mode air/rail/
water transport family alongside `cloud-itonami-isic-5110` and
`cloud-itonami-isic-4911`) from an earlier bulk-scaffolding pass
(created and pushed 2026-07-09, boilerplate docs -- `CODE_OF_CONDUCT.md`
/ `CONTRIBUTING.md` / `GOVERNANCE.md` / `LICENSE` / `README.md` /
`SECURITY.md` / `blueprint.edn` / `docs/business-model.md` /
`docs/operator-guide.md` -- with no `deps.edn`, `src`, or `test`, per
that ADR's own Decision 1 explicitly reserving those for "a future
`:blueprint`->`:implemented` promotion pass").
`blueprint.edn` already declared
`:itonami.blueprint/governor :maritime-safety-governor` and the
`README.md` already named a "Ferry Operations Advisor -> Maritime
Safety Governor" core contract, `:required-technologies [:robotics
:identity :forms :dmn :bpmn :audit-ledger :logistics]` and
`:optional-technologies [:optimization]` (all already correct, no
field-sync fix needed). This ADR records filling that scaffold in with
a real, tested implementation -- not creating the repository fresh.

The nearest already-`:implemented` transport-sector sibling at the time
of this build was `cloud-itonami-isic-5020` (water freight transport /
tanker) -- `cloud-itonami-isic-4911` (passenger rail, the originally
suggested mirror target) was itself still `:blueprint`-tier, being
built concurrently by a sibling agent in this same fleet batch, so
`cloud-itonami-isic-5020` and the current-generation
`cloud-itonami-isic-6511` (life insurance, the `kotoba-lang/langchain-
store` reference adopter, pushed 2026-07-14) were used as the mirrored
references instead.

## Decision

### Decision 1: passenger-ferry OPERATIONS COORDINATION actor, not maritime-safety authority

Per this task's explicit domain design, `cloud-itonami-isic-5011` is
scoped as an operations-coordination actor, deliberately narrower than
`cloud-itonami-isic-5020`'s dispatch/discharge-drafting tanker actor:
every proposal's `:effect` is HARD-required to be the literal keyword
`:propose` (`ferry.governor/effect-not-propose-violations`), and there
is no domain-specific mutation-effect keyword. Four governed ops, a
closed allowlist enforced independently at the governor layer
(`ferry.governor/op-not-allowed-violations`), all effect `:propose`:
`:log-voyage-record`, `:schedule-sailing-operation`, `:flag-maritime-
safety-concern`, `:coordinate-maintenance`.

### Decision 2: the scope-exclusion self-trip bug class, fixed by construction

This fleet has repeatedly hit and independently fixed the SAME bug
class across multiple sibling actors: a governor's scope-exclusion term
list phrased as a bare noun (e.g. "safety", "clearance", "weather-
hold") accidentally matches inside the actor's own DEFAULT advisor
rationale/disclaimer text, because an honest disclaimer explaining what
the actor does NOT do necessarily uses the bare noun it is disclaiming
-- causing the actor to self-block on its own happy path.

Fix applied here: `ferry.governor/scope-exclusion-phrases` (in
`cloud-itonami-isic-5011`'s own repo) is a set of full FINALIZATION/
EXECUTION ACTIONS ("finalize the sail-clearance override", "override
the weather-hold", etc.), never a bare noun, and `ferry.ferryadvisor`'s
own rationale/disclaimer text is written in different words than any of
those phrases (attributing final departure-clearance judgment to "the
vessel master and the maritime-safety authority" rather than restating
what the actor does not do). `test/ferry/scope_exclusion_test.cljc` is
a dedicated regression test sweeping every op x every demo sailing
(including a fault-reported + weather-hold-active one) through the
DEFAULT mock advisor and asserting none of the resulting proposals trip
`:scope-exclusion-sail-clearance`; a separate direct-unit test file
(`test/ferry/governor_test.cljc`) proves the check is not vacuous.

### Decision 3: Wave 2 (coordination/logistics) maritime-safety invariant

Per this fleet batch's Wave 2 policy for passenger-safety-adjacent
coordination actors: the closed op-allowlist never includes an op that
directly finalizes a maritime-safety-authority decision (clearing a
vessel to sail after a reported fault, overriding a weather hold) --
these are always either a hard permanent block (the scope-exclusion
check, Decision 2) or an always-escalate op, never auto-commit-eligible.
`:flag-maritime-safety-concern` (the "flag a concern" op) always
escalates to human sign-off via TWO independent layers
(`ferry.governor/high-stakes` AND `ferry.phase` never putting it in any
phase's `:auto` set) and is never in any phase's `:auto` set, including
phase 3.

### Decision 4: self-contained domain logic, `langchain-store` store seam

Like `cloud-itonami-isic-5020`, no `kotoba-lang/maritime` capability
library exists to delegate passenger-vessel safety validation to.
`ferry.registry/imo-number-valid?` honestly reapplies `cloud-itonami-
isic-5020`'s own reapplication of the SOLAS / IMO resolution A.600(15)
seven-digit check-digit scheme (applies to every SOLAS-class ship,
including passenger vessels, not only tankers). Because this is a NEW
store as of 2026-07, it adopts `kotoba-lang/langchain-store`
(ADR-2607141600) as its `DatomicStore` field-spec entity-store seam
(the `cloud-itonami-isic-6511` `underwriting.store` pattern) rather than
hand-rolling the `enc`/`dec*` + schema/pull boilerplate `cloud-itonami-
isic-5020` (which predates that library) hand-rolls.

### Decision 5: portable `.cljc` throughout (cljs-first mandate)

Per this workspace's CLAUDE.md runtime priority (`kotoba wasm >
clojurewasm > ClojureScript > nbb`, JVM/bb last resort) and its explicit
"no JVM-only interop" instruction for this build, ALL source AND test
files are `.cljc` (not `.clj`), including a `ferry.portable-cljs-test-
runner` + `:cljs` deps.edn alias -- a deliberate departure from
`cloud-itonami-isic-5020`'s `.clj`-only test suite, which predates that
mandate, and a mirror of `cloud-itonami-isic-6511`'s current-generation
convention.

## Verification

- `clojure -M:dev:test` in the built repo: `Ran 46 tests containing 272
  assertions. 0 failures, 0 errors.` (raw output captured; re-verified
  again after a fresh clone post-merge).
- `clojure -M:lint`: 0 errors, 0 warnings.
- `clojure -M:dev:run`: demo walks the happy paths (log -> schedule ->
  flag-concern -> coordinate-maintenance, all via escalate/approve/
  commit except the auto-committing voyage-record log) plus every
  HARD-hold scenario (no spec-basis, invalid IMO, certification
  incomplete on all three non-logging ops, already scheduled) and a
  low-confidence-but-governor-clean escalation (fault-reported +
  weather-hold-active), end-to-end, exit 0.
- Registry: `kotoba-lang/industry`'s `{:id "5011" ...}` entry, which had
  no `:maturity` key (resolved to `:blueprint` via the `:repo`-present
  fallback in `kotoba.industry/maturity-of`), now has `:maturity
  :implemented` added explicitly (commit
  `23db6561e48ed605f979465aee6f16f0eadaccf9`). `:repo` and
  `:business-id` were already correct (no change needed).
- `test/kotoba/industry_test.clj` (a very hot contention file, re-fetched
  fresh multiple times over the course of this edit as concurrent
  sibling agents landed several other promotions in the same window)
  got a dedicated corroboration block for 5011 (final content, commit
  `dbdcec1107af99aad51d3b0a037f1f7c638ad79d`). Honesty note: an
  intermediate PUT attempt (commit `83a1aedbc040f09599e70899a2cf8ea0b96e82f0`)
  accidentally sent EMPTY content due to a local `base64` CLI-argument
  error (BSD `base64` on macOS silently no-ops without `-i`), briefly
  zeroing the shared file; caught immediately by this session's
  standing post-PUT re-fetch-and-diff verification and corrected within
  the same turn, then independently re-verified via a brand-new fresh
  clone. See the paired `.edn`'s `:incident-note` for the full account.

## Consequences

- Sea and coastal passenger water transport (ISIC 5011) actor on the
  same governed-actor architecture as the rest of the cloud-itonami
  fleet, filling in a pre-existing `:blueprint`-tier repo rather than
  scaffolding a new one.
- The scope-exclusion self-trip bug class now has a THIRD (at least)
  independent fix-and-regression-test instance in this fleet, plus a
  fleet-wide-legible design record (this ADR + the child repo's own
  `docs/adr/0001-architecture.md`) of the fix pattern (full action
  phrases, not bare nouns, plus a dedicated cross-op x cross-entity
  sweep test) for future actors in maritime-safety-adjacent or other
  authority-adjacent domains to follow.

## References

- `90-docs/adr/2607102400-cloud-itonami-passengerferry-5011-blueprint.md`
  (the originating blueprint-publication ADR this build promotes from)
- `cloud-itonami-isic-5011/docs/adr/0001-architecture.md` (child-repo
  architecture ADR, full design record)
- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md`
  (current-generation governed-actor architecture +
  `kotoba-lang/langchain-store` reference adopter this build follows)
- `cloud-itonami-isic-5020/docs/adr/0001-architecture.md` (nearest
  transport-sector sibling; contrast: marine CARGO not passenger, no
  `:effect :propose` constant, pre-`langchain-store`, `.clj` tests)
- ADR-2607141600 (`kotoba-lang/langchain-store` store-seam
  commonalization)
- IMO resolution A.600(15): IMO Ship Identification Number Scheme
