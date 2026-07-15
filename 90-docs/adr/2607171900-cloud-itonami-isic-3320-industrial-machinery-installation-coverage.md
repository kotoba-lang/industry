# ADR-2607171900: cloud-itonami-isic-3320 (Installation of industrial machinery and equipment) operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-4311` (Demolition -- the closest
structural analog: a coordination-only actor deliberately narrower than
the `cloud-itonami-isic-4211` robotics-premise reference this fleet's
other coordination-only actors also follow), `cloud-itonami-isic-4210`
(Roads/railways construction -- same coordination-only shape), `kotoba-
lang/industry` registry's `"3320"` catalog entry (was `:maturity :spec`
with a placeholder `gftdcojp/cloud-itonami-C3320` repo reference that was
never created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 36+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git clone --depth 1` of
`kotoba-lang/industry`: `{:id "3320" :name "Installation of industrial
machinery and equipment" ...}` confirmed verbatim at
`resources/kotoba/industry/registry.edn:4765-4773` -- this fleet has
previously mislabeled an assigned ISIC class more than once (e.g. `0892`
assumed=salt, actually peat; `0144` assumed=swine, actually
sheep-goats), so this check runs before any design work, not after.
`gh api repos/cloud-itonami/cloud-itonami-isic-3320` returned 404 --
fresh scaffold, no prior repository at either the registry's stale
`gftdcojp/cloud-itonami-C3320` placeholder or the real `cloud-itonami`
org.

## Decision: Installation Advisor ⊣ Installation Governor, coordination-only, narrower than the 4211 robotics premise

Implemented `cloud-itonami-isic-3320` end-to-end in `src/installation`
using the SAME `.cljc` actor pattern (langgraph-clj StateGraph,
mock-by-default advisor, dual MemStore/Datomic backend, 0→3 phase
rollout) every prior `cloud-itonami-isic-*` actor in this fleet uses,
mirroring `cloud-itonami-isic-4311` (Demolition) structurally -- the
closest analog, itself a coordination-only actor explicitly narrower
than the `cloud-itonami-isic-4211` robotics-premise reference named in
this workspace's Actors documentation.

This is a safety-critical domain: heavy lifting/rigging, alignment work,
and commissioning of potentially energized machinery. The actor
deliberately holds **no heavy-lift/rigging-equipment-control authority
and no commissioning-energization sign-off authority** -- both remain
the licensed engineer / site supervisor's exclusively, enforced as
permanent, un-overridable HARD governor blocks (`installation.governor`
checks 1-4), not policy that could be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-installation-record` -- rigging-plan/alignment/progress data
  logging. Low-risk, may auto-commit at phase 3.
- `:schedule-installation-operation` -- rigging/alignment/commissioning-
  test scheduling proposal (never a finalized rigging plan or
  commissioning-energization sign-off). ALWAYS escalates to a human, at
  every phase, unconditionally.
- `:flag-safety-concern` -- surfaces a rigging / lockout-tagout /
  energization-hazard concern. ALWAYS escalates to a human, unconditionally.
- `:order-supplies` -- rigging-hardware/spare-parts procurement proposal.
  Escalates above a cost threshold ($5,000) or below the confidence
  floor (0.6); may auto-commit at phase 3 otherwise.

### Governor -- eight HARD checks, all un-overridable by human approval

Unknown op (outside the closed 4-op allowlist), `:effect` not
`:propose`, forbidden action class (`:heavy-lift-equipment-control?` /
`:direct-actuation?` / `:commissioning-energization-sign-off?` markers),
site not independently verified/registered, legal-basis missing, lift
plan incomplete, notification lead time insufficient (quantitative
jurisdictions only), unresolved safety concern on file. See
`installation.governor` ns docstring for the full enumeration and
rationale.

### Per-jurisdiction legal-basis catalog (JPN/USA/DEU) -- real official sources, honest coverage

`src/installation/facts.cljc` catalogs lift-plan and installation-
notification requirements. All three citations were independently
verified via live web search/fetch before being hardcoded (not recalled
from training-data memory alone, given this domain's zero-fabrication
discipline):

- 🇯🇵 Japan: クレーン等安全規則（昭和47年労働省令第34号）第74条の2 (crane
  work-plan requirement) for the lift-plan basis; 労働安全衛生法（昭和47年
  法律第57号）第88条第1項 for the installation-notification basis --
  confirmed via `jaish.gr.jp`/Wikibooks quoting the statute text: an
  installation/relocation/major-structural-modification plan for
  specified hazardous machinery must be filed with the Chief of the
  Labour Standards Inspection Office no later than **30 calendar days**
  before work begins. This is the sole `:quantitative` jurisdiction in
  this catalog.
- 🇺🇸 USA: OSHA 29 CFR Part 1926 Subpart CC (Cranes and Derricks in
  Construction -- qualified-rigger/rigging-plan duty) for the lift-plan
  basis; OSHA 29 CFR 1910.147 (Control of Hazardous Energy --
  Lockout/Tagout) for the installation-notification basis. Both are
  real, load-bearing federal regulations, but neither states a fixed
  advance-notice-days count for an installation PLAN filing (unlike
  `demolition.facts`'s USA entry, which found a real 10-working-day
  NESHAP notice for a *different* proposal type) -- honestly
  `:qualitative` here rather than reusing a citation for an unrelated
  requirement.
- 🇪🇺 EU (DEU proxy, the same convention `demolition.facts`/
  `construction.facts`/`aerospace.facts` use): Betriebssicherheits-
  verordnung (BetrSichV) §15 (Prüfung vor Inbetriebnahme -- inspection
  before commissioning) for the lift-plan basis; EU Machinery Directive
  2006/42/EC Annexes VI/VII (assembly instructions + technical
  documentation before putting into service) for the installation-
  notification basis. `:qualitative` -- no fixed EU-wide or German
  federal numeric lead-time for this proposal type.

`installation.facts/notification-lead-insufficient?` is three-valued
(`true`/`false`/`:qualitative`/`nil`), the same honest-coverage
discipline every sibling actor's facts catalog uses -- a jurisdiction
not in the table has no fabricated spec-basis, and a `:qualitative`
jurisdiction never gets an invented numeric verdict.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to a
last resort) and this build's explicit mandate, `installation.notify`
ships **only** the deterministic mock `Notifier` protocol implementation
-- unlike sibling actors `demolition.notify`/`construction.notify`,
which ship real `java.net.http`-backed Resend/Twilio transports behind
`#?(:clj ...)` guards, this repo ships no real transport at all. A real
transport can be added later behind the same `Notifier` protocol via a
portable (cljs/nbb) HTTP client without changing this actor's shape. The
standard portable `catch #?(:clj Exception :cljs :default)` idiom (used
throughout every sibling actor's `.cljc` for cross-platform exception
handling) and `ex-message` (a portable core function, not a `.method`
call) are used instead of any JVM-specific interop.

## What this actor does NOT do

Direct heavy-lift/rigging-equipment operation and commissioning-
energization sign-off remain exclusive to the licensed engineer / site
supervisor, permanently -- enforced structurally by the closed
op-allowlist and the forbidden-action-class check, not just documented
in the README.

## Verification

- `cloud-itonami-isic-3320`: `clojure -M:test` -- raw final line: `Ran 64
  tests containing 241 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- `linting took 515ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `installation.sim` demo driver) exercises the
  full coordination episode plus every HARD hold and exits 0.
- All source is `.cljc`; no JVM-only interop (confirmed by manual review
  of every `#?(:clj ...)` branch -- none reference `java.*`/`System.*`).
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`/tmp/isic-3320-work/orgs/cloud-itonami/cloud-itonami-isic-3320`,
  outside the shared superproject checkout), then pushed to a fresh
  GitHub repo (`cloud-itonami/cloud-itonami-isic-3320`, created via `gh
  repo create`) as its initial `main` commit. Confirmed landed: `gh api
  repos/cloud-itonami/cloud-itonami-isic-3320/commits/main --jq .sha`
  returned `f9f5c77898a8cd12613226a96470207318c09952`, matching the local
  push SHA exactly.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root
  (`/tmp/isic-3320-work/root-adr-worktree`, branched from a freshly
  fetched `origin/main`), landed via a server-side merge
  (`gh api repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"3320"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C3320` placeholder to
  the real repo, comment points at this ADR); `test/kotoba/
  industry_test.clj`'s maturity-summary assertion bumped to match the
  live-recomputed implemented-entry count (recomputed from a freshly
  re-fetched `origin/main`, not assumed). Full `kotoba-lang/industry`
  suite re-run green post-edit and again post-merge against a fresh
  clone; see this session's final report for the raw post-merge output.

## Consequences

(+) `cloud-itonami-isic-3320` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, site-registration, forbidden-action-class) rather than
only claiming them in prose.
(+) `kotoba-lang/industry` registry `"3320"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates that a real, jurisdiction-honest legal-basis catalog is
achievable even when (unlike `demolition.facts`) live research turns up
only ONE `:quantitative` jurisdiction for the specific proposal type in
scope -- the catalog reports this honestly rather than reusing a
citation for a different (however real) requirement to inflate apparent
coverage.
(-) `installation.advisor` remains a `MockAdvisor` (no real LLM/langchain
integration yet) and `installation.notify` ships no real mail/phone
transport (mock only, per the no-JVM-interop mandate) -- both consistent
with every other actor in this fleet's current maturity tier and this
build's explicit portability constraint, not a regression.
