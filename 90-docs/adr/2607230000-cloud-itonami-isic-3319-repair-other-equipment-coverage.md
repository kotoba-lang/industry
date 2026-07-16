# ADR-2607230000: cloud-itonami-isic-3319 (Repair of other equipment) operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-3320` (Installation of industrial
machinery and equipment -- the closest structural analog: also a
coordination-only actor, mirrored closely here and adapted from
installation-project coordination to repair-shop coordination), ISIC
siblings `3311` (fabricated-metal repair), `3312` (machinery repair),
`3313` (electronic repair), `3314` (electrical-equipment repair), `3315`
(transport-equipment repair) -- `3319` is the ISIC-residual "repair of
other equipment" class, distinct from all five, `kotoba-lang/industry`
registry's `"3319"` catalog entry (was `:maturity :spec` with a stale
`gftdcojp/cloud-itonami-C3319` placeholder repo reference that was never
created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 78+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git clone --depth 1` of
`kotoba-lang/industry`: `{:id "3319" :name "Repair of other equipment"
...}` confirmed verbatim at `resources/kotoba/industry/registry.edn`
line 5340-5348 -- this fleet has previously mislabeled an assigned ISIC
class more than once, so this check runs before any design work, not
after. `gh api repos/cloud-itonami/cloud-itonami-isic-3319` returned
404 -- fresh scaffold, no prior repository at either the registry's
stale `gftdcojp/cloud-itonami-C3319` placeholder or the real
`cloud-itonami` org.

## Decision: Repair Advisor ⊣ Repair Governor, coordination-only, narrower escalation surface than 3320's

Implemented `cloud-itonami-isic-3319` end-to-end in
`src/other_equipment_repair` using the SAME `.cljc` actor pattern
(langgraph-clj StateGraph, mock-by-default advisor, dual MemStore/
Datomic backend, 0→3 phase rollout) every prior `cloud-itonami-isic-*`
actor in this fleet uses, mirroring `cloud-itonami-isic-3320`
(Installation of industrial machinery and equipment) structurally --
the closest analog, itself a coordination-only actor -- and adapting it
from industrial-machinery-installation-project coordination to
repair-shop diagnostic/repair/testing coordination.

This is a safety-relevant domain: repair work can involve stored/
residual energy, sharp tools and incomplete-repair hazards. The actor
deliberately holds **no repair-equipment/diagnostic-tool control
authority and no return-to-service sign-off authority** -- both remain
the licensed repair technician's exclusively, enforced as permanent,
un-overridable HARD governor blocks (`other-equipment-repair.governor`
checks 1-3), not policy that could be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-repair-record` -- diagnostic-finding/repair-work-performed/
  parts-used data logging. Low-risk, may auto-commit at phase 3.
- `:schedule-repair-operation` -- diagnostic/repair/testing scheduling
  proposal (never a repair-equipment/diagnostic-tool control command or
  a return-to-service sign-off). MAY auto-commit at phase 3 when the
  governor is clean -- see "Deliberate design deviation" below.
- `:flag-safety-concern` -- surfaces an equipment-hazard / incomplete-
  repair / certification-lapse concern. ALWAYS escalates to a human,
  unconditionally, at every phase.
- `:order-supplies` -- replacement-parts procurement proposal. Escalates
  above a cost threshold ($5,000) or below the confidence floor (0.6);
  may auto-commit at phase 3 otherwise.

### Governor -- six HARD checks, all un-overridable by human approval

Unknown op (outside the closed 4-op allowlist), `:effect` not
`:propose`, forbidden action class (`:repair-equipment-control?` /
`:diagnostic-tool-control?` / `:direct-actuation?` / `:return-to-
service-sign-off?` markers), equipment/work-order not independently
verified/registered, legal-basis missing, unresolved safety concern on
file. See `other-equipment-repair.governor` ns docstring for the full
enumeration and rationale. This is two fewer HARD checks than 3320's
eight: this actor's honest facts-catalog research (see below) found no
`:quantitative` jurisdiction for the pre-repair hazard/energy-control
duty, so there is no numeric lead-time-insufficient check to
independently recompute; and this domain has no lift-plan-approval
analog, so there is no lift-plan-incomplete check either.

### Deliberate design deviation from 3320: `:schedule-repair-operation` may auto-commit

UNLIKE `cloud-itonami-isic-3320`'s `:schedule-installation-operation`
(which is UNCONDITIONALLY a member of `installation.governor/high-
stakes` because it coordinates potential heavy-lift/rigging-equipment
dispatch and pre-energization work, so it always escalates to a human at
every phase), this actor's `other-equipment-repair.governor/high-stakes`
set contains ONLY `:flag-safety-concern`. `:schedule-repair-operation`
is a normal write op subject to ordinary phase gating and is a member of
phase 3's `:auto` set. This reflects the task brief's explicit ESCALATE
list (only `:flag-safety-concern` "always escalates"; supply orders and
low confidence are the other soft gates) and the genuinely
lower-average-physical-risk nature of the ISIC-residual "repair of other
equipment" class (furniture, sporting/recreational goods, musical
instruments, miscellaneous equipment n.e.c.) relative to 3320's
industrial-machinery-installation domain. The governor's own equipment-
verification / legal-basis / unresolved-concern HARD checks still gate
`:schedule-repair-operation` independently of phase, so a compromised or
malfunctioning advisor gains nothing by trying to force an auto-commit
on an unverified or unresolved-concern equipment/work-order record.

### Per-jurisdiction legal-basis catalog (JPN/USA/DEU) -- real official sources, honest coverage

`src/other_equipment_repair/facts.cljc` catalogs pre-repair hazard/
energy-control requirements. All three citations were independently
verified via live web search/fetch before being hardcoded (not recalled
from training-data memory alone, given this domain's zero-fabrication
discipline):

- 🇯🇵 Japan: 労働安全衛生規則（昭和47年労働省令第32号）第107条（掃除等の場合の
  運転停止等） -- confirmed via `laws.e-gov.go.jp` and secondary sources
  quoting the statute text: before cleaning, oiling, inspection, repair
  or adjustment work that could endanger a worker, the employer must
  stop the machine's operation and lock the start switch / attach a
  warning sign so nobody else restarts it during that work. No fixed
  numeric advance-notice-days count -- honestly `:qualitative`.
- 🇺🇸 USA: OSHA 29 CFR 1910.147 (The Control of Hazardous Energy --
  Lockout/Tagout) -- a real, load-bearing federal regulation directly on
  point for repair/servicing work (unlike 3320's reuse of the same
  citation for an installation-notification proposal type, this actor
  cites it for the proposal type it actually governs). `:qualitative` --
  a documented energy-control-program duty, not a fixed lead-time.
- 🇪🇺 EU (DEU proxy, the same convention `installation.facts`/
  `demolition.facts`/`construction.facts`/`aerospace.facts` use):
  Betriebssicherheitsverordnung (BetrSichV) §10 (Instandhaltung und
  Änderung von Arbeitsmitteln -- maintenance, which the regulation
  itself defines to include Instandsetzung/repair, limited to suitably
  qualified/authorized/instructed personnel), grounded in Directive
  2009/104/EC (minimum safety and health requirements for the use of
  work equipment by workers), confirmed via live search to explicitly
  require that "in the case of repairs, modifications, maintenance or
  servicing, the workers concerned are specifically designated to carry
  out such work." UNLIKE 3320's DEU entry (which cites the Machinery
  Directive 2006/42/EC, a manufacturer/placing-on-market instrument for
  NEW equipment before commissioning), this actor cites the correct
  EU-level instrument for safety during the USE (including repair) of
  equipment by workers -- not the same directive reused out of context.
  `:qualitative` -- no fixed EU-wide or German federal numeric
  lead-time.

`other-equipment-repair.facts/notification-lead-insufficient?` is
three-valued (`:qualitative`/`nil`, never a fabricated `true`/`false` in
this catalog -- see ns docstring), the same honest-coverage discipline
every sibling actor's facts catalog uses. UNLIKE 3320's catalog (which
found ONE `:quantitative` jurisdiction, JPN, for a DIFFERENT proposal
type -- an installation-notification PLAN filing), this catalog's honest
research found ZERO `:quantitative` jurisdictions among JPN/USA/DEU for
the pre-repair hazard/energy-control duty itself: every seeded source is
procedural (stop the machine, lock/tag it out, use qualified personnel),
not a fixed numeric advance-notice-days count. This absence is reported
honestly rather than fabricated.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to a
last resort) and this build's explicit mandate, `other-equipment-repair.
notify` ships **only** the deterministic mock `Notifier` protocol
implementation -- no real Resend/Twilio transport. A real transport can
be added later behind the same `Notifier` protocol via a portable
(cljs/nbb) HTTP client without changing this actor's shape. The standard
portable `catch #?(:clj Exception :cljs :default)` idiom and `ex-message`
(a portable core function, not a `.method` call) are used instead of any
JVM-specific interop.

## What this actor does NOT do

Direct repair-equipment/diagnostic-tool operation and return-to-service
sign-off remain exclusive to the licensed repair technician, permanently
-- enforced structurally by the closed op-allowlist and the
forbidden-action-class check, not just documented in the README.

## Verification

- `cloud-itonami-isic-3319`: `clojure -M:test` -- raw final line: `Ran 65
  tests containing 225 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- `linting took 841ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `other-equipment-repair.sim` demo driver)
  exercises the full coordination episode plus every HARD hold and exits
  0 with no exceptions.
- All source is `.cljc`; no JVM-only interop (confirmed by manual review
  of every `#?(:clj ...)` branch -- none reference `java.*`/`System.*`).
- `grep -rc "â"` across all source/doc files in the new repo returned no
  matches (no mojibake).
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`/tmp/isic-3319-work/orgs/cloud-itonami/cloud-itonami-isic-3319`,
  outside the shared superproject checkout), then pushed to a fresh
  GitHub repo (`cloud-itonami/cloud-itonami-isic-3319`, created via `gh
  repo create`) as its initial `main` commit. Confirmed landed: `gh api
  repos/cloud-itonami/cloud-itonami-isic-3319/commits/main --jq .sha`
  returned `59f66c33f1bb091c9f5ae948dea16f01ae8589e7`, and `gh api
  .../compare/<local-sha>...main` reported `{"status":"identical",
  "ahead_by":0,"behind_by":0}`.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root
  (`/tmp/isic-3319-work/adr-worktree`, branched from a freshly fetched
  `origin/main`), landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"3319"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C3319` placeholder to
  the real repo, comment points at this ADR); `test/kotoba/
  industry_test.clj`'s maturity-summary assertion bumped to match the
  live-recomputed implemented-entry count (recomputed from a freshly
  re-fetched `origin/main`, not assumed). Full `kotoba-lang/industry`
  suite re-run green post-edit and again post-merge against a fresh
  clone; see this session's final report for the raw post-merge output.

## Consequences

(+) `cloud-itonami-isic-3319` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, equipment-registration, forbidden-action-class) rather
than only claiming them in prose.
(+) `kotoba-lang/industry` registry `"3319"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates a genuine, task-driven narrower-escalation-surface
design deviation from the mirrored reference (3320) -- `:schedule-
repair-operation` auto-commit eligibility at phase 3 -- grounded both in
the task brief's explicit ESCALATE list and in this ISIC-residual
class's lower average physical risk, not a copy-paste of the reference's
shape without domain judgment.
(+) Demonstrates that a real, jurisdiction-honest legal-basis catalog is
achievable even when honest live research turns up ZERO `:quantitative`
jurisdictions for the specific proposal type in scope -- the catalog
reports this honestly rather than fabricating a numeric lead-time to
resemble the reference's shape.
(-) `other-equipment-repair.advisor` remains a `MockAdvisor` (no real
LLM/langchain integration yet) and `other-equipment-repair.notify` ships
no real mail/phone transport (mock only, per the no-JVM-interop mandate)
-- both consistent with every other actor in this fleet's current
maturity tier and this build's explicit portability constraint, not a
regression.
