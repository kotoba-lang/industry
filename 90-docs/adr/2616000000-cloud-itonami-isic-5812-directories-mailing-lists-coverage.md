# ADR-2616000000: cloud-itonami ISIC 5812 (Publishing of directories and mailing lists) actor coverage

## Status

Accepted. `cloud-itonami-isic-5812` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-5812`
404 before this work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-5812`, following
the verified fresh-scaffold protocol established across this fleet
(closest architectural sibling: `cloud-itonami-isic-873`, residential
care activities for the elderly and disabled — same coordination-only,
independent-Governor, Phase 0→3 rollout shape). Part of the fleet's
Wave 4 batch (human-facing/personal services, ISIC sections I/P/Q/R/S/T,
ADR-2607152500), which was authorized to proceed in parallel with the
just-completed Wave 3 (production/robotics, ADR-2607121000).

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carried `{:id "5812", :name "Publishing of directories and mailing
lists", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-J5812`
/ `cloud-itonami-J5812`) predating the current `cloud-itonami-isic-<id>`
naming convention. The live `:name` was independently re-verified
against a fresh clone before any work began, per this fleet's ID/name-
mismatch caution, and confirmed to match "Publishing of directories and
mailing lists" exactly. This work implements the blueprint as a real,
tested `cloud-itonami-isic-<id>` actor repo and corrects the registry
entry's `:repo`/`:business-id` fields to match.

ISIC 5812 covers publishing of directories (telephone directories,
business/trade directories, other compilations of facts) and mailing
lists (subscriber lists, address lists sold or licensed for direct-mail
or other commercial use). This activity touches personal-data handling
and privacy/consent decisions directly — directory entries and mailing-
list records routinely contain personally identifiable information
(names, addresses, phone numbers), and publishers are frequently the
subject of data-subject opt-out, deletion, and accuracy-dispute
requests. Per this fleet's Wave-4 person-facing-service safety
guardrail (ADR-2607152500), the closed op allowlist for this actor
NEVER includes any op that directly finalizes a data-privacy-compliance
decision (deciding a listing is GDPR/CCPA/opt-out compliant, resolving
a data-subject deletion/erasure request, granting a "right to be
forgotten" claim) — those are always either a hard, permanent block or
an always-escalate op, never auto-commit-eligible.

## Decision

Scaffold `cloud-itonami-isic-5812` mirroring the `cloud-itonami-isic-873`
(residential care) architecture closely — same langgraph-clj StateGraph
+ independent Governor + Phase 0→3 rollout pattern, same
record-verification/effect-propose/scope-exclusion gate structure — but
retargeted to directory/mailing-list publishing's own domain and
privacy-safety profile:

- Namespace prefix `dirmailops` (module `dirmailops`).
- Governor keyword `:dirmail-governor`.
- Closed 4-op allowlist, all `:effect :propose` only:
  `:log-listing-record` (directory-entry/subscriber-list data logging),
  `:schedule-publication-operation` (compilation/update/print-run
  scheduling proposal), `:coordinate-distribution` (outbound
  directory/list distribution coordination), `:flag-privacy-concern`
  (surface a data-consent/opt-out/accuracy concern — ALWAYS escalates).
  None of these four ops directly finalizes a data-privacy-compliance
  decision, satisfying the Wave-4 guardrail by construction.
- Three HARD governor checks, all permanent and un-overridable by any
  human approval: (1) the target listing/subscriber record must exist
  AND be independently `:registered?`/`:verified?` in the store before
  ANY proposal for it may commit or even escalate — re-derived from the
  record's own store state, never trusting a proposal's own claim; (2)
  `:effect` must always be `:propose` — any other value is a claim to
  directly actuate outside governance; (3) scope exclusion — a fourth
  check folded into the same rule as the closed-allowlist check — any
  proposal (regardless of op) whose text touches directly finalizing a
  data-privacy-compliance decision (GDPR/CCPA compliance determination,
  a resolved deletion/erasure request, a granted "right to be
  forgotten" claim, a consent-compliance ruling) is a HARD, PERMANENT
  block, evaluated unconditionally on every proposal.
- One SOFT escalation gate: LLM confidence below the floor (0.6), OR
  the op is `:flag-privacy-concern` — ALWAYS escalates to a human,
  regardless of confidence. `dirmailops.phase` independently agrees:
  `:flag-privacy-concern` is structurally absent from every phase's
  `:auto` set, including phase 3 — a permanent fact, not a rollout
  milestone still to come, matching the Wave-4 guardrail's "any 'flag a
  concern' op must always escalate and must never be in any phase's
  `:auto` set" requirement.
- `:log-listing-record`, `:schedule-publication-operation`, and
  `:coordinate-distribution` MAY auto-commit at phase 3 when the
  governor is completely clean — matching `cloud-itonami-isic-873`'s
  own shape (care-note logging / family-visit / supply-request / staff-
  shift proposals all auto-eligible, only the safety-concern flag
  permanently excluded).
- Fully portable `.cljc`, zero JVM-only interop anywhere in `src/`.

## Verification

Actor repo built from a uniquely-named scratch dir
(`/private/tmp/.../scratchpad/5812-work/cloud-itonami-isic-5812`),
never touching the shared `orgs/kotoba-lang/industry` checkout:

```
$ clojure -M:test
Ran 43 tests containing 132 assertions.
0 failures, 0 errors.
```

Pushed to `main` at commit `0a925b056bb049ce2d9c9edecc00194c8e058ae4` (`cloud-itonami/cloud-itonami-isic-5812`).
Re-verified from an INDEPENDENT fresh clone — see the task's final
report for the exact re-verification `clojure -M:test` output.

`kotoba-lang/industry` registry: `"5812"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-J5812` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-5812` /
`cloud-itonami-isic-5812`, exact in-place edit of only the `"5812"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the task's final report for the exact
before/after counts and merge SHA.

## Consequences

(+) ISIC 5812 now has a documented, governed, auditable directory/
mailing-list-publishing-operations-coordination actor, following this
fleet's established pattern.

(+) The "coordination, not data-privacy-compliance authority" boundary
is a hard, permanent, unconditional governor block (any proposal
directly finalizing a GDPR/CCPA compliance decision, a deletion/erasure
resolution, or a "right to be forgotten" grant) plus a structural
phase-table absence for `:flag-privacy-concern` — two independent
layers agree, not just asserted in prose, satisfying ADR-2607152500's
Wave-4 person-facing-service safety guardrail.

(+) Portable `.cljc`, zero JVM-only constructs.

(-) Still a simulation/proposal layer — single `MemStore` backend, mock
advisor, no real directory-management-system integration. Same
limitation as every sibling actor in this fleet at this stage.

(-) The privacy-compliance-decision scope-exclusion term list is a
STARTING catalog (GDPR/CCPA/right-to-be-forgotten/DSAR terminology in
English and Japanese), not an exhaustive survey of every jurisdiction's
data-protection-law vocabulary — documented plainly as a scope
limitation in `dirmailops.governor`'s own docstring, not silently
generalized.
