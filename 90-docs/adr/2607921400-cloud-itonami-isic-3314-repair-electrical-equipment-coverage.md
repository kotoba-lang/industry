# ADR-2607921400: cloud-itonami-isic-3314 (Repair of electrical equipment) operations-coordination actor -- fresh scaffold

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: `cloud-itonami-isic-3319` (Repair of other equipment -- the
closest structural analog: also a coordination-only repair-shop actor
with the same closed op-allowlist and phase-3 schedule-op
auto-eligibility, mirrored closely here and adapted from the
ISIC-residual repair scope to electrical-equipment-specific repair),
`cloud-itonami-isic-3320` (Installation of industrial machinery and
equipment -- the actor 3319 itself mirrors), ISIC siblings `3311`
(fabricated-metal repair), `3312` (machinery repair), `3313` (electronic
repair), `3315` (transport-equipment repair), `3319` (residual "other
equipment" repair) -- `3314` is specifically electrical equipment repair
(motors, generators, transformers, switchgear), distinct from all five,
`kotoba-lang/industry` registry's `"3314"` catalog entry (was
`:maturity :spec` with a stale `gftdcojp/cloud-itonami-C3314` placeholder
repo reference that was never created; now `:implemented`)

## Context

This is part of an ongoing careful, smaller-batch ISIC-coverage rollout
(one class per agent, a capable model, mandatory verification) after a
prior 18-agent haiku batch produced a 61% defect rate; 84+ consecutive
agents on this stricter protocol had all succeeded before this one.

Before any work, the registry entry's `:id`/`:name` pair was
independently re-verified against a fresh `git clone` (via the GitHub
Contents API, not `raw.githubusercontent.com`, to avoid stale CDN
caching) of `kotoba-lang/industry`: `{:id "3314" :name "Repair of
electrical equipment" ...}` confirmed verbatim at
`resources/kotoba/industry/registry.edn` around line 5405 -- this fleet
has previously mislabeled an assigned ISIC class more than once, so this
check runs before any design work, not after. `gh api repos/cloud-itonami/cloud-itonami-isic-3314`
returned 404 -- fresh scaffold, no prior repository at either the
registry's stale `gftdcojp/cloud-itonami-C3314` placeholder or the real
`cloud-itonami` org.

## Decision: Repair Advisor ⊣ Repair Governor, coordination-only, electrical-domain re-energization block added

Implemented `cloud-itonami-isic-3314` end-to-end in
`src/electrical_equipment_repair` using the SAME `.cljc` actor pattern
(langgraph-clj StateGraph, mock-by-default advisor, dual MemStore/
Datomic backend, 0→3 phase rollout) every prior `cloud-itonami-isic-*`
actor in this fleet uses, structured after `cloud-itonami-isic-3319`
(Repair of other equipment) -- the closest analog: also a repair-shop
coordination-only actor with the identical closed 4-op allowlist and
`:schedule-repair-operation` phase-3 auto-eligibility shape -- narrowed
from ISIC-residual repair to electrical-equipment-specific repair
(motors, generators, transformers, switchgear).

This is a safety-relevant domain: electrical equipment repair can
involve stored/residual voltage, arc-flash hazards and incomplete-repair
risk. The actor deliberately holds **no repair-equipment/diagnostic-tool
control authority and no return-to-service OR re-energization sign-off
authority** -- both remain the licensed repair technician's exclusively,
enforced as permanent, un-overridable HARD governor blocks
(`electrical-equipment-repair.governor` checks 1-3), not policy that
could be relaxed by a future rollout phase.

### Closed op-allowlist (4 ops, all `:effect :propose`)

- `:log-repair-record` -- diagnostic-finding/repair-work-performed/
  parts-used data logging. Low-risk, may auto-commit at phase 3.
- `:schedule-repair-operation` -- diagnostic/repair/testing scheduling
  proposal (never a repair-equipment/diagnostic-tool control command or
  a return-to-service/re-energization sign-off). MAY auto-commit at
  phase 3 when the governor is clean -- see "Deliberate design decision"
  below.
- `:flag-safety-concern` -- surfaces an electrical-hazard (insulation
  failure, arc-flash risk) / incomplete-repair concern. ALWAYS escalates
  to a human, unconditionally, at every phase.
- `:order-supplies` -- replacement-parts procurement proposal. Escalates
  above a cost threshold ($5,000) or below the confidence floor (0.6);
  may auto-commit at phase 3 otherwise.

### Governor -- six HARD checks, all un-overridable by human approval

Unknown op (outside the closed 4-op allowlist), `:effect` not
`:propose`, forbidden action class (`:repair-equipment-control?` /
`:diagnostic-tool-control?` / `:direct-actuation?` / `:return-to-
service-sign-off?` / `:re-energization-sign-off?` markers),
equipment/work-order not independently verified/registered, legal-basis
missing, unresolved safety concern on file. See `electrical-equipment-
repair.governor` ns docstring for the full enumeration and rationale.

### Electrical-domain-specific addition: `:re-energization-sign-off?` forbidden marker

The task brief for this class explicitly calls out "a return-to-service/
re-energization sign-off decision" as the hard-blocked authority (unlike
3319's brief, which only names "return-to-service"). Electrical
equipment repair law itself draws this same distinction: the JPN and DEU
legal-basis entries below both impose a *separate* pre-re-energization
confirmation duty distinct from the pre-work de-energization duty. This
actor's `forbidden-action-class-violations` check therefore adds a
`:re-energization-sign-off?` marker to the four markers every sibling
repair actor already checks (`:repair-equipment-control?` /
`:diagnostic-tool-control?` / `:direct-actuation?` / `:return-to-
service-sign-off?`) -- a genuine, domain-grounded extension rather than
a cosmetic rename, and grep-verified unique fleet-wide (no other repo in
`cloud-itonami`/`kotoba-lang` uses this marker or the
`:electrical-equipment-repair-governor` keyword).

### Deliberate design decision: `:schedule-repair-operation` may auto-commit despite higher intrinsic hazard than 3319

Like `cloud-itonami-isic-3319`'s `:schedule-repair-operation` (and UNLIKE
`cloud-itonami-isic-3320`'s `:schedule-installation-operation`, which is
UNCONDITIONALLY a member of `installation.governor/high-stakes` because
it coordinates potential heavy-lift/rigging-equipment dispatch and
pre-energization work), this actor's `electrical-equipment-repair.
governor/high-stakes` set contains ONLY `:flag-safety-concern`.
`:schedule-repair-operation` is a normal write op subject to ordinary
phase gating and is a member of phase 3's `:auto` set. This matches the
task brief's explicit ESCALATE list (only `:flag-safety-concern` "always
escalates"; supply orders above a cost threshold and low confidence are
the other soft gates -- the brief conspicuously does NOT list
`:schedule-repair-operation` as always-escalating).

This is a considered decision, not a rote copy of 3319's shape: electrical
equipment (motors, generators, transformers, switchgear) carries a
genuinely HIGHER intrinsic electrical hazard (arc-flash, stored/residual
voltage in capacitors, insulation breakdown) than 3319's residual scope
(furniture, sporting/recreational goods, musical instruments). The
schedule op itself, however, is still only ever a proposed
diagnostic/repair/testing WINDOW -- never a live-work authorization, a
repair-equipment/diagnostic-tool control command, or a return-to-service/
re-energization sign-off -- and this actor's own HARD checks
(equipment-verification, legal-basis-on-file citing the jurisdiction's
own de-energization-before-repair duty, no-unresolved-safety-concern)
PLUS its `:re-energization-sign-off?` forbidden-action-class block
already gate the actual electrical-hazard surface independently of
phase. The higher hazard is answered by a stricter, electrical-specific
governor check (the added re-energization marker) rather than by
escalating the coordination-scheduling op itself.

### Per-jurisdiction legal-basis catalog (JPN/USA/DEU) -- real official sources, honest coverage, electrical-specific citations

`src/electrical_equipment_repair/facts.cljc` catalogs pre-repair
de-energization/re-energization requirements. All three citations were
independently verified via live web search/fetch before being
hardcoded (not recalled from training-data memory alone, given this
domain's zero-fabrication discipline), and are electrical-installation-
specific instruments rather than the generic machine-stop/maintenance
citations 3319's catalog uses:

- 🇯🇵 Japan: 労働安全衛生規則（昭和47年労働省令第32号）第339条（停電作業を
  行なう場合の措置） -- confirmed via `laws.e-gov.go.jp` and secondary
  sources quoting the statute text: when opening an electric circuit for
  installation/inspection/repair/painting work, the employer must lock
  the switch or post a notice/station a supervisor, discharge residual
  charge, and verify de-energization with a test device plus
  short-circuit grounding for high-voltage circuits. Paragraph 2
  additionally requires, BEFORE re-energizing (通電) the opened circuit,
  confirming no electric-shock hazard to workers AND that grounding
  equipment has been removed -- an explicit pre-re-energization duty
  distinct from the pre-work duty, which is what motivated adding the
  `:re-energization-sign-off?` forbidden marker above. No fixed numeric
  advance-notice-days count -- honestly `:qualitative`.
- 🇺🇸 USA: OSHA 29 CFR 1910.333 (Selection and use of work practices --
  Electrical safety-related work practices) -- the electrical-specific
  sibling of 3319's generic 29 CFR 1910.147 (Lockout/Tagout) citation:
  live parts must be deenergized before work (narrow infeasibility/
  increased-hazard exceptions apply), and a qualified person must verify
  the deenergized condition with test equipment (including checking for
  induced voltage/backfeed) before work starts. `:qualitative` -- a
  documented verification duty, not a fixed lead-time.
- 🇪🇺 EU (DEU proxy, the same convention `installation.facts`/
  `demolition.facts`/`construction.facts`/`other-equipment-repair.facts`
  use): DGUV Vorschrift 3 "Elektrische Anlagen und Betriebsmittel" §3
  (Prüfungen) -- the electrical-installation-specific instrument, UNLIKE
  3319's generic BetrSichV §10 (Instandhaltung) citation: electrical
  installations/equipment must be inspected before first commissioning
  AND after any repair or change, before being put back into operation
  (vor der Wiederinbetriebnahme), by a qualified electrician
  (Elektrofachkraft) -- an explicit pre-return-to-service testing duty,
  grounded in the same Directive 2009/104/EC 3319's DEU entry cites.
  DGUV Vorschrift 3 is an accident-prevention regulation
  (Unfallverhütungsvorschrift) issued by the German statutory accident
  insurance body rather than a directly-enacted federal statute, but
  legally binding on employers as a condition of statutory accident-
  insurance membership -- this distinction is noted honestly in the ns
  docstring. `:qualitative` -- no fixed EU-wide or German federal
  numeric lead-time.

`electrical-equipment-repair.facts/notification-lead-insufficient?` is
three-valued (`:qualitative`/`nil`, never a fabricated `true`/`false` in
this catalog -- see ns docstring), the same honest-coverage discipline
every sibling actor's facts catalog uses. This catalog's honest research
found ZERO `:quantitative` jurisdictions among JPN/USA/DEU for the
pre-repair de-energization/re-energization duty itself, the same
honest-absence finding `other-equipment-repair.facts` reports for its
own (generic) duty -- reported honestly rather than fabricated.

### No JVM-only interop anywhere in `src/`

Per this workspace's cljs-first `.cljc` runtime-priority rule (`kotoba
wasm` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb` downgraded to a
last resort) and this build's explicit mandate, `electrical-equipment-
repair.notify` ships **only** the deterministic mock `Notifier` protocol
implementation -- no real Resend/Twilio transport. A real transport can
be added later behind the same `Notifier` protocol via a portable
(cljs/nbb) HTTP client without changing this actor's shape. The standard
portable `catch #?(:clj Exception :cljs :default)` idiom and `ex-message`
(a portable core function, not a `.method` call) are used instead of any
JVM-specific interop.

## What this actor does NOT do

Direct repair-equipment/diagnostic-tool operation and return-to-service/
re-energization sign-off remain exclusive to the licensed repair
technician, permanently -- enforced structurally by the closed
op-allowlist and the forbidden-action-class check (including the new
`:re-energization-sign-off?` marker), not just documented in the README.

## Verification

- `cloud-itonami-isic-3314`: `clojure -M:test` -- raw final line: `Ran 65
  tests containing 227 assertions.` / `0 failures, 0 errors.` Re-run
  green a second time against a fresh `git clone` after pushing to
  `main` (see below).
- `clojure -M:lint` -- `linting took 788ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` (the `electrical-equipment-repair.sim` demo
  driver) exercises the full coordination episode plus every HARD hold
  and exits 0 with no exceptions (the only occurrences of the substring
  "exception" in its output are the OSHA citation text
  "infeasibility/increased-hazard exceptions", not a thrown error).
- All source is `.cljc`; no JVM-only interop (confirmed by manual review
  of every `#?(:clj ...)` branch -- none reference `java.*`/`System.*`).
- `grep -rc "â"` across all source/doc files in the new repo returned no
  matches (no mojibake).
- Repo scaffolded and tested from a uniquely-named scratch directory
  (`/private/tmp/.../scratchpad/build-3314/cloud-itonami/
  cloud-itonami-isic-3314`, outside the shared superproject checkout),
  then pushed to a fresh GitHub repo (`cloud-itonami/
  cloud-itonami-isic-3314`, created via `gh repo create`) as its initial
  `main` commit. Confirmed landed: `gh api repos/cloud-itonami/
  cloud-itonami-isic-3314/commits/main --jq .sha` returned
  `09e8d4deccb12764cb5ab4b00d03058bd58c3757`.
- This ADR itself was authored and committed from a sibling `git
  worktree` outside the superproject root, branched from a freshly
  fetched `origin/main`, landed via a server-side merge (`gh api
  repos/com-junkawasaki/root/merges`), per this workspace's
  concurrent-session worktree discipline.
- `kotoba-lang/industry` registry `"3314"` entry updated in place
  (`:maturity` `:spec` -> `:implemented`, `:repo`/`:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C3314` placeholder to
  the real repo, comment points at this ADR); `test/kotoba/
  industry_test.clj`'s maturity-summary assertion bumped to match the
  live-recomputed implemented-entry count (recomputed from a freshly
  re-fetched `origin/main`, not assumed). Full `kotoba-lang/industry`
  suite re-run green post-edit and again post-merge against a fresh
  clone; see this session's final report for the raw post-merge output.

## Consequences

(+) `cloud-itonami-isic-3314` now exists with a genuinely green,
independently re-verified test suite and a Governor that structurally
enforces its documented invariants (closed op-allowlist, `:effect
:propose`-only, equipment-registration, forbidden-action-class including
the electrical-specific re-energization block) rather than only claiming
them in prose.
(+) `kotoba-lang/industry` registry `"3314"` entry promoted to
`:maturity :implemented`, with its stale placeholder repo reference
corrected.
(+) Demonstrates a genuine, domain-grounded extension of the mirrored
reference's (3319) governor shape -- the `:re-energization-sign-off?`
forbidden-action-class marker -- motivated directly by the task brief's
explicit "return-to-service/re-energization" phrasing and by the actual
legal citations found (JPN Article 339 paragraph 2, DGUV Vorschrift 3
§3), not a copy-paste of the reference's shape without domain judgment.
(+) Demonstrates that the higher intrinsic hazard of this class relative
to its mirrored reference (3319) can be answered with a stricter,
targeted governor check rather than by reflexively escalating the
coordination-scheduling op itself -- a considered design decision
grounded in the task brief's own explicit ESCALATE list.
(-) `electrical-equipment-repair.advisor` remains a `MockAdvisor` (no
real LLM/langchain integration yet) and `electrical-equipment-repair.
notify` ships no real mail/phone transport (mock only, per the
no-JVM-interop mandate) -- both consistent with every other actor in
this fleet's current maturity tier and this build's explicit portability
constraint, not a regression.
