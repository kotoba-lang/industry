# ADR-2790003342: cloud-itonami-isco-3342 — Legal Secretarial Practice Coordination

**Status**: accepted
**Date**: 2026-07-17
**Deciders**: Jun Kawasaki (+ Claude, standing authorization per CLAUDE.md)
**Scope**: `orgs/kotoba-lang/occupation`, `cloud-itonami/cloud-itonami-isco-3342`
**Builds on**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
rollout plan), ADR-2607012000 (cloud-itonami ISCO occupation blueprints)
**Related**: `cloud-itonami/cloud-itonami-isco-3313` (Accounting Associate
Professionals — the verified structural reference this repo mirrors)

## Status note: first-of-kind for the ISCO occupation track

This is the first `cloud-itonami-isco-*` actor landed under the ISIC/ISCO
reverse-toposort plan's occupation half (ADR-2607121000 §3, ISCO 5-wave
table). Legal secretaries (minor group 334, sub-major 33) sit in Wave 2
(coordination) per that table's `33` entry. No prior sibling ISCO actor in
this specific batch had landed an ADR at the time of writing, so this ADR
also records a few conventions observed live for the benefit of later
sibling agents (see "Notes for future ISCO-track agents" below).

## Context

ISCO-08 3342 (Legal Secretaries) was a `:spec`-only entry in
`kotoba-lang/occupation`'s registry (`resources/kotoba/occupation/
registry.edn`) with no `:repo`/`:business-id`. Identity independently
verified against a fresh clone of `kotoba-lang/occupation` before any work
began: `(kotoba.occupation/get-occupation "3342")` returns `{:id 3342, :name
"Legal Secretaries", :maturity :spec, :required-technologies [:robotics
:identity :audit-ledger], :optional-technologies [], :operating-states
[:intake :propose :approve :execute :audit]}` — name matches exactly, no
mismatch found. No prior `cloud-itonami-isco-3342` GitHub repository existed
at the `cloud-itonami` org (404 confirmed via `gh api`/`gh repo view` before
any work began).

**Domain**: legal secretaries manage a supervising attorney's document
intake, case-file filing/docketing, court-operation scheduling and office
procurement, but carry a direct attorney-client-privilege/confidentiality
dimension distinct from most other coordination-actor verticals covered so
far: a legal secretarial practice routinely handles privileged/confidential
case documents with **no authority whatsoever** to decide what happens to
them. Per this fleet's own guardrail for that dimension, the closed op
allowlist NEVER includes any op that directly finalizes disclosure of
privileged/confidential case information to a third party, provides legal
advice, or determines a filing deadline/legal strategy without attorney
sign-off — always a hard, permanent block, never auto-commit-eligible. This
actor coordinates DOCUMENT/CASE COORDINATION ONLY; it never makes a legal or
strategic decision on the attorney's behalf.

**Scope**: coordination only, mirrored closely on the verified reference
`cloud-itonami/cloud-itonami-isco-3313` (Accounting Associate Professionals)
module shape (`langgraph.graph`-based actor: `:intake -> :advise -> :govern
-> :decide -+-> :commit +-> :request-approval +-> :hold`, Advisor/Governor/
Store split, in-memory `Store` protocol, `mock-advisor`/`llm-advisor` seam).
Domain-adapted: `legalsecretary.*` namespace in place of
`accountingsupport.*`, an attorney/case entity pair in place of a
client/account entity pair, document-log/court-schedule/supply-order ops in
place of transaction-posting/period-close ops, and a
`:flag-confidentiality-concern` op (new relative to the 3313 reference,
carried over conceptually from this fleet's other confidentiality-sensitive
verticals such as `cloud-itonami-isic-8211`) in place of a bare
over-ceiling-posting escalate op.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-document-record` — document intake/filing/case-management data
  logging
- `:schedule-court-operation` — filing-deadline/appointment scheduling
  proposal (never determines the deadline itself — logs an
  attorney-supplied deadline only; `:attorney-supplied? false` is a HARD
  block, not merely low-confidence)
- `:flag-confidentiality-concern` — surface a privilege/disclosure-risk
  concern — **ALWAYS escalates**, and is never a member of
  `governor/auto-commit-ops`
- `:coordinate-supply-order` — office/case-material procurement proposal

An op outside this closed set is a HARD block (`:unknown-op`), the same
un-overridable failure mode as a proposal that drifts into forbidden scope
via free text (see check 6 below).

### 2. Governor rules: six HARD checks (permanent, un-overridable), plus escalation

1. **Attorney unregistered** — the supervising attorney must be
   independently registered in the store before ANY action.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance.
3. **Closed op allowlist** — `:op` must be one of the four ops above. No op
   that could finalize disclosure of privileged/confidential case
   information, provide legal advice, or determine a filing
   deadline/legal strategy unilaterally is ever a member of this set —
   adding such an op would be a permanent-block design violation, not a
   policy toggle.
4. **Case basis** — a case-scoped proposal must cite a REGISTERED case
   belonging to this attorney (`:unknown-case` / `:case-wrong-attorney`).
5. **Attorney-supplied deadline** — a `:schedule-court-operation` proposal
   must carry `:attorney-supplied? true`. Setting a filing deadline the
   attorney did not actually supply is exactly the unsupervised
   legal-strategy decision this actor must never make — HARD, not merely
   escalate.
6. **Scope-exclusion action check** — the proposal's free text
   (`:rationale`/`:concern-detail`) must not describe FINALIZING a
   privileged disclosure, providing legal advice, or determining a
   deadline/strategy without sign-off. This is defense-in-depth behind
   check 3: it catches an *allowed* op (e.g. `:log-document-record`)
   reused to smuggle a forbidden action through free text.

   Per this fleet's known self-tripping bug class: every scope-excluded
   term is phrased as the finalization/execution ACTION (e.g. "finalize
   the privileged-document disclosure", "provide legal advice"), never as
   a bare noun ("privilege", "advice", "deadline") that would accidentally
   match inside this same namespace's own default mock-advisor rationale
   text for a legitimate, allowed `:flag-confidentiality-concern` proposal
   (whose whole job is to talk about privilege/confidentiality concerns).
   A dedicated regression test,
   `default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
   `governor_test.clj`, asserts every default mock-advisor proposal for
   every allowed op — including `:flag-confidentiality-concern` with a
   `:concern-detail` that deliberately contains the bare word "privilege"
   — clears the governor with `:hard?` false; companion tests
   `hard-on-scope-exclusion-disclosure-language` /
   `hard-on-scope-exclusion-legal-advice-language` confirm the term list
   genuinely does trip on real forbidden-action phrasing (so the check is
   not vacuously non-matching).

Escalation (SOFT, always human sign-off, only reached when the governor is
otherwise clean):

- `:flag-confidentiality-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above the case's registered `:max-supply-cost`
  ceiling — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`governor/auto-commit-ops` (`(into #{} (remove always-escalate-ops
known-ops))`) independently agrees: `:flag-confidentiality-concern` is
computed OUT of the auto-commit set by construction, not by a hand-maintained
list that could drift — exercised directly by
`auto-commit-ops-never-include-flag-confidentiality-concern`.

### 3. Module shape

`legalsecretary.store` (MemStore, string-keyed `attorneys`/`cases`
directories, append-only audit ledger), `legalsecretary.advisor`
(LegalSecretaryAdvisor, mock + a real-LLM seam), `legalsecretary.governor`
(LegalSecretaryGovernor), `legalsecretary.actor` (the `langgraph.graph`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval). All source is portable `.cljc` with no JVM-only interop
anywhere in `src/` (mock-only advisor, cljs-compatible).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "3342" ...}` block only, matching the field order/shape of sibling
  `:implemented` entries such as `"3313"`):
  `:name` / `:required-technologies` / `:optional-technologies` /
  `:operating-states` left exactly as-is (already correct and, per the
  registry's own convention, not literal StateGraph node names —
  `"3313"`'s own `:implemented` entry keeps the same generic
  `[:intake :propose :approve :execute :audit]` shape), `:maturity`
  `:spec` → `:implemented`, `:repo`
  `https://github.com/cloud-itonami/cloud-itonami-isco-3342` and
  `:business-id "cloud-itonami-isco-3342"` added.
- Actor repo `cloud-itonami/cloud-itonami-isco-3342` scaffolded (fresh — no
  prior repository existed, 404 confirmed before any work began) and
  pushed to `main` (commit `9be3e7ce34181962d1cf3540da4d7ae7e85fd455`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 22 tests containing 50 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone with
  the same result.

## Notes for future ISCO-track agents (observed live this session)

- `kotoba-lang/occupation`'s `deps.edn` already declares
  `io.github.kotoba-lang/technology {:local/root "../technology"}` at the
  registry-repo level, but the verified `cloud-itonami-isco-3313` reference
  actor repo's own `deps.edn` does **not** depend on `kotoba-lang/technology`
  at all (no `require` of `kotoba.technology` anywhere in its `src`/`test`).
  The technology sibling is only needed when working inside the
  `kotoba-lang/occupation` checkout itself (e.g. running
  `kotoba.occupation/technology-stack`), not inside the standalone actor
  repo. This new repo's `deps.edn` mirrors the verified reference exactly
  (no `technology` dependency).
- The reference repo (`cloud-itonami-isco-3313`) has two latent doc/build
  drifts not replicated here: (1) its `CONTRIBUTING.md` documents `clojure
  -M:dev:test` and `clojure -M:lint`, but its own `deps.edn` defines neither
  a `:dev` nor a `:lint` alias (only `:test`) — this repo's `CONTRIBUTING.md`
  documents only the alias that actually exists (`clojure -M:test`). (2) its
  `README.md`/`GOVERNANCE.md` link to `docs/samples/operator-console.html`
  and `docs/adr/` respectively, neither of which exists in that repo — this
  repo's docs omit the dangling sample-page link (the `docs/adr/`
  convention-statement line was kept, since it is not a broken markdown
  link, only a directory-naming convention).
- `SECURITY.md`/`CODE_OF_CONDUCT.md` in the `cloud-itonami-isco-3313`
  reference both still say "gftdcojp organization" for private-contact
  routing even though that repo is published under the `cloud-itonami` org
  (stale org name carried over from an earlier template). This repo's
  copies say "cloud-itonami organization" instead.
- `kotoba-lang/occupation`'s registry.edn stores registry entries as
  Datomic/DataScript tx-data wrapping a `pr-str`-encoded `:occupations`
  blob (see `src/kotoba/occupation.clj`'s `unblob`/`reconstitute-entity`).
  There is no per-entry `:adr` field anywhere in the live registry (checked
  via grep across all 436 entries) — ADR references for a promotion live
  only in the `occupation_test.clj` append-only comment log (see that
  file's promotion-history comments above the `maturity-summary counts
  tiers` test), not in the registry entry itself. This ADR follows that
  convention: no `:adr` key was added to the `"3342"` registry entry.
- `test/kotoba/occupation_test.clj`'s only registry-wide numeric assertion
  is `(is (= 436 (:total m)))` inside the `maturity-summary counts tiers`
  test — this does NOT change when promoting an existing `:spec` entry to
  `:implemented` (`:total` is invariant under promotion; only the
  `:spec`/`:implemented` split shifts). There is no standing
  `:implemented`-count numeric assertion to bump; the file's convention is
  per-ID `(is (= :implemented (occupation/maturity "XXXX")))` assertions
  plus a running prose comment log of each promotion's before/after counts.

## References

- `cloud-itonami-isco-3313/` (module-shape mirror — verified working
  reference for the coordination-actor pattern; its own registry
  `:maturity` is `:implemented`)
- `kotoba-lang/occupation` `resources/kotoba/occupation/registry.edn`
  `"3342"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — ISCO
  Wave 2, sub-major 33 coordination)
