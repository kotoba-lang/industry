# ADR-2790003344: cloud-itonami-isco-3344 — Medical Secretarial Scheduling & Filing Coordination Practice

**Status**: accepted
**Date**: 2026-07-17
**Deciders**: Jun Kawasaki (+ Claude, standing authorization per CLAUDE.md)
**Scope**: `kotoba-lang/occupation`, `cloud-itonami/cloud-itonami-isco-3344`
**Builds on**: ADR-2607121000 (cloud-itonami global ISIC/ISCO reverse-toposort
rollout plan), ADR-2607012000 (cloud-itonami ISCO occupation blueprints)
**Related**: `cloud-itonami/cloud-itonami-isco-3313` (Accounting Associate
Professionals — the verified structural reference this repo mirrors),
ADR-2790003342 (`cloud-itonami-isco-3342` Legal Secretaries — the
concurrent sibling ISCO-track actor landed in the same batch; several
conventions below were cross-checked against its notes)

## Context

ISCO-08 3344 (Medical Secretaries) was a `:spec`-only entry in
`kotoba-lang/occupation`'s registry (`resources/kotoba/occupation/
registry.edn`) with no `:repo`/`:business-id`. Identity independently
verified against a fresh clone of `kotoba-lang/occupation` before any work
began, via `clojure -M -e "(require 'kotoba.occupation) (println
(kotoba.occupation/get-occupation \"3344\"))"` (not hand-parsing the
tx-data blob): returned `{:id "3344", :name "Medical Secretaries",
:maturity :spec, :required-technologies [:robotics :identity
:audit-ledger], :optional-technologies [], :operating-states [:intake
:propose :approve :execute :audit]}` — name matches "Medical Secretaries"
exactly, no mismatch found. No prior `cloud-itonami-isco-3344` GitHub
repository existed at the `cloud-itonami` org (404 confirmed via `gh api`
before any work began).

**Domain / PHI-privacy dimension — the strictest guardrail in this
batch**: medical secretaries handle a supervising provider's patient
appointment scheduling and chart-filing logistics, which routinely touches
protected health information (PHI) even though this actor's own stored
data is scheduling/filing metadata only. Per this fleet's guardrail for
this dimension, the closed op allowlist NEVER includes any op that
directly finalizes disclosure of a patient's medical record to any third
party, provides medical/clinical judgment or advice, or authorizes a
prescription/refill — always a hard, permanent block, never
auto-commit-eligible. This actor coordinates SCHEDULING/FILING LOGISTICS
ONLY; it never stores or exposes actual clinical content, and any "flag a
concern" op always escalates to a human and is never auto-commit-eligible.

**Scope**: coordination only, mirrored closely on the verified reference
`cloud-itonami/cloud-itonami-isco-3313` (Accounting Associate
Professionals) module shape (`langgraph.graph`-based actor: `:intake ->
:advise -> :govern -> :decide -+-> :commit +-> :request-approval +->
:hold`, Advisor/Governor/Store split, in-memory `MemStore` Store protocol,
`mock-advisor`/`llm-advisor` seam). Domain-adapted: `medsecretary.*`
namespace in place of `accountingsupport.*`, a provider/appointment entity
pair in place of a client/account entity pair, appointment-filing/
scheduling/supply-order ops in place of transaction-posting/period-close
ops, and a `:flag-privacy-concern` op (new relative to the 3313 reference,
same pattern as the concurrent 3342 sibling's `:flag-confidentiality-
concern`) in place of a bare over-ceiling-posting escalate op.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-appointment-record` — appointment/chart-filing-status data
  logging (metadata only — filing-status keyword, appointment reference —
  never clinical content)
- `:schedule-patient-operation` — patient appointment scheduling proposal
  (does not require a pre-existing appointment record — scheduling
  CREATES one; contrast with the filing op below, which does)
- `:flag-privacy-concern` — surface a PHI-disclosure-risk or consent
  concern — **ALWAYS escalates**, never auto-commit-eligible
- `:coordinate-supply-order` — medical-office supply procurement proposal

An op outside this closed set is a hard, permanent block
(`:op-not-allowlisted`), the same un-overridable failure mode as a
proposal that smuggles forbidden scope through free text (check 5 below).
Three ops are additionally named and permanently excluded even though
they would already fail the closed-allowlist check —
`:finalize-record-disclosure`, `:provide-clinical-judgment`,
`:authorize-prescription-refill` — so the block reads as an intentional
scope exclusion (`:scope-excluded-op`) rather than an incidental unknown
op.

### 2. Governor rules: six HARD checks (permanent, un-overridable), plus escalation

1. **Provider/practice unregistered** — the provider or practice record
   must be independently verified/registered in the store before ANY
   action.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance.
3. **Closed op allowlist** — `:op` must be one of the four ops above.
4. **Scope-excluded op** — `:finalize-record-disclosure`,
   `:provide-clinical-judgment`, `:authorize-prescription-refill` are a
   hard, permanent block regardless of confidence or human approval.
5. **Scope-excluded language (defense-in-depth)** — the proposal's free-text
   `:rationale` must not contain a finalization/execution ACTION-phrase
   (e.g. "finalize the disclosure of the patient record", "provide
   clinical judgment", "authorize a prescription refill") — never a bare
   noun like "medical record" alone, which would false-positive on
   entirely ordinary rationale text. This catches an *allowed* op (e.g.
   `:log-appointment-record`) reused to smuggle a forbidden action through
   free text, independent of check 4's exact-keyword match.
6. **Appointment basis** — `:log-appointment-record` must cite a
   REGISTERED appointment belonging to this provider
   (`:unknown-appointment` / `:appointment-wrong-provider`);
   `:schedule-patient-operation` does not require one (it creates the
   appointment).

**Known self-tripping bug class, guarded against by a dedicated
regression test.** This fleet has repeatedly discovered the same bug
class on other verticals (most recently the concurrent 3342 sibling): a
governor scope-exclusion term list phrased as a bare noun can accidentally
match inside the mock advisor's own default rationale/disclaimer text for
a legitimate, allowed proposal, causing the actor to self-block on its own
happy path. `medsecretary.governor`'s `scope-exclusion-terms` are
deliberately phrased as multi-word finalization/execution action-phrases
(never a bare noun such as "medical record", "disclosure", "prescription"
in isolation), and `medsecretary.advisor`'s mock rationale template
(`"proposed " (name op) " for provider " provider-id`) never contains
those phrases by construction. A dedicated test,
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`governor_test.clj`, asserts every default mock-advisor proposal for all
four allowed ops clears the governor with rule `:scope-excluded-language`
absent from `:violations` — proven, not assumed.

Escalation (SOFT, always human sign-off, only reached when the governor is
otherwise clean):

- `:flag-privacy-concern` — always, regardless of confidence; structurally
  never a member of any auto-commit path (`always-escalate-ops`
  unconditionally includes it).
- `:coordinate-supply-order` above `governor/supply-cost-ceiling` (500) —
  always, regardless of confidence.
- Low advisor confidence (`< governor/confidence-floor` = 0.6).

### 3. Module shape

`medsecretary.store` (MemStore, string-keyed `providers`/`appointments`
directories, append-only audit ledger), `medsecretary.advisor`
(MedicalSecretaryAdvisor, mock + a real-LLM seam), `medsecretary.governor`
(MedicalSecretaryGovernor), `medsecretary.actor` (the `langgraph.graph`
StateGraph: intake → advise → govern → decide → commit | hold |
request-approval). All source is portable `.cljc` with no JVM-only interop
anywhere in `src/` (mock-only advisor, cljs-compatible; test files are
`.clj`, matching the verified reference exactly).

## Consequences

- Registry entry corrected in place via a byte-verified targeted edit of
  the existing `{:id "3344", :name "Medical Secretaries", ...}` block only
  (prefix/suffix of the surrounding tx-data blob confirmed byte-identical
  before/after, no adjacent entry touched): `:name` /
  `:required-technologies` / `:optional-technologies` /
  `:operating-states` left exactly as-is (matching sibling `:implemented`
  entries' own convention of keeping the registry's generic
  `[:intake :propose :approve :execute :audit]` shape rather than the
  actor's literal StateGraph node names), `:maturity` `:spec` →
  `:implemented`, `:repo`
  `https://github.com/cloud-itonami/cloud-itonami-isco-3344` and
  `:business-id "cloud-itonami-isco-3344"` added. Landed via the GitHub
  Contents API (`registry.edn` commit
  `886004b39387a2032f563d99c77697b93ab9dbdf`) — `gh api -f
  content=@file` rejected the base64 payload as "not valid Base64" for
  this file size even though the encoding round-tripped correctly
  locally; switched to `gh api --input <json-payload-file>` with the
  base64 embedded via a proper JSON string, which succeeded (see "Notes
  for future ISCO-track agents" below).
- `test/kotoba/occupation_test.clj`'s `maturity-summary` numeric
  assertions bumped (`:spec` 220 → 219, `:implemented` 216 → 217,
  `:total` 436 unchanged) — the true post-promotion counts were
  live-recomputed via `(kotoba.occupation/maturity-summary)` against a
  freshly re-fetched `origin/main` immediately before editing, not
  assumed from a stale local clone. Hot-contention file re-fetched
  (fresh `sha`) immediately before the PUT per this fleet's discipline;
  landed commit `eac234b3f81e33060f90125c2a5798f43d356856`.
- Actor repo `cloud-itonami/cloud-itonami-isco-3344` scaffolded (fresh —
  no prior repository existed, 404 confirmed before any work began) and
  pushed to `main` (commit `e06e573d34911b62b24ac7b1c63ff431c0eb1e9c`,
  final state after a small follow-up doc fix — see notes below).
- Test suite, run directly by this session (not agent self-report), on a
  brand-new fresh clone of the pushed actor repo: **`Ran 25 tests
  containing 58 assertions. 0 failures, 0 errors.`** (`clojure -M:test`).
- Final post-merge re-verification of the registry (brand-new scratch
  directory, fresh `kotoba-lang/occupation` clone plus a fresh
  `kotoba-lang/technology` sibling at the correct `../technology` relative
  path): `clojure -M:test` re-run — **`Ran 14 tests containing 948
  assertions. 0 failures, 0 errors.`**;
  `(kotoba.occupation/get-occupation "3344")` confirmed `:maturity
  :implemented` with the correct `:repo`/`:business-id`;
  `(kotoba.occupation/maturity-summary)` confirmed `{:total 436, :spec
  219, :blueprint 0, :implemented 217}`; `registry.edn` scanned and
  confirmed valid UTF-8 with zero Unicode replacement-character
  occurrences (no mojibake).

## Notes for future ISCO-track agents (observed live this session)

- Confirms the concurrent 3342 sibling's finding: `kotoba-lang/
  occupation`'s own `deps.edn` declares `io.github.kotoba-lang/technology
  {:local/root "../technology"}`, but the verified `cloud-itonami-
  isco-3313` reference actor repo's `deps.edn` does **not** depend on
  `kotoba-lang/technology` at all. This repo's `deps.edn` mirrors the
  verified reference exactly (no `technology` dependency) — the
  `technology` sibling is only needed when working inside the
  `kotoba-lang/occupation` checkout itself.
- Confirms the reference repo's `CONTRIBUTING.md` documents `clojure
  -M:dev:test` and `clojure -M:lint`, but its own `deps.edn` defines
  neither a `:dev` nor a `:lint` alias (only `:test`) — carried through
  unchanged in this repo (matching the reference's own doc/build drift,
  as the 3342 sibling ADR also independently found and chose to correct
  in its own copy; this repo left `clojure -M:dev:test`/`clojure -M:lint`
  in `CONTRIBUTING.md` as-is per the "mirror closely" instruction, so a
  future cleanup pass across the whole ISCO batch may want to fix this
  doc/build mismatch consistently rather than per-repo).
- The reference repo's `SECURITY.md`/`CODE_OF_CONDUCT.md` both say
  "gftdcojp organization" for private-contact routing (stale — that repo
  is published under `cloud-itonami`). This repo's copies say
  "cloud-itonami organization" instead, matching the fix the 3342 sibling
  independently made — landed as a small follow-up commit
  (`e06e573`) after the initial scaffold push, once the sibling ADR
  surfaced the issue.
- **Correction to the 3342 sibling ADR's claim** that `occupation_test.clj`
  has "no standing `:implemented`-count numeric assertion to bump": as of
  this session, the file's `maturity-tier` test's `maturity-summary counts
  tiers` `testing` block DOES carry standing numeric assertions —
  `(is (= 0 (:blueprint m)))`, `(is (= 220 (:spec m)))` (bumped to 219 by
  this ADR's promotion), `(is (= 216 (:implemented m)))` (bumped to 217)
  — immediately above the append-only prose comment log, not merely a
  `(is (= 436 (:total m)))` invariant. This ADR's registry work bumped
  those two assertions directly (confirmed present and bumped correctly
  via a fresh clone re-run, 948 assertions green) — either the file
  gained these assertions between the 3342 and 3344 sessions, or the 3342
  note was simply inaccurate; either way, future agents should check the
  live file rather than trust either ADR's claim in isolation.
- `gh api repos/<org>/<repo>/contents/<path> -f content=@<file>` (the `-f
  key=@file` convenience syntax) failed with `422 content is not valid
  Base64` for this session's ~170KB `registry.edn` payload, even though
  the same base64 file round-tripped correctly through both `base64 -D`
  and Python's strict `base64.b64decode(..., validate=True)` locally.
  Building the full JSON request body explicitly (message/content/sha/
  branch as real JSON string fields, no shell-level file-reference
  sugar) and sending it via `gh api --input <payload.json>` succeeded on
  the first attempt. Future agents hitting the same "not valid Base64"
  error on a Contents API PUT should suspect the `-f key=@file` field
  encoding path itself, not their base64 encoding.

## References

- `cloud-itonami-isco-3313/` (module-shape mirror — verified working
  reference for the coordination-actor pattern; its own registry
  `:maturity` is `:implemented`)
- ADR-2790003342 (`cloud-itonami-isco-3342`, the concurrent ISCO-track
  sibling — same coordination-only shape, same confidentiality/privacy
  guardrail pattern this actor also applies)
- `kotoba-lang/occupation` `resources/kotoba/occupation/registry.edn`
  `"3344"` entry
- ADR-2607121000 (Wave definition, reverse-toposort rollout plan — ISCO
  Wave 2, sub-major 33 coordination)
