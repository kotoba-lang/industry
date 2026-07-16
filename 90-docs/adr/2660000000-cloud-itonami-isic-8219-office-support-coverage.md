# ADR-2660000000: cloud-itonami-isic-8219 — Office Support Services Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8219` promoted from no `:maturity` key
(resolves to `:blueprint` via the `kotoba.industry/maturity-of` fallback,
since the entry already carries a `:repo`) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

This is part of a 6-target blueprint-tier cleanup batch (alongside ISIC
7911, 7912, 8121, 8130, 8220) intended to bring the `kotoba-lang/industry`
registry's remaining nil-maturity/blueprint-tier gap to zero. Identity
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began: the live `{:id "8219" ...}` entry's `:name` was
truncated with a literal `"..."` (a known ~10% pre-existing seed-data bug)
— `"Photocopying, document preparation and other specialized of..."` — the
full ISIC-08 name "Photocopying, document preparation and other
specialized office support activities" is a prefix-match, confirming this
is the correct entry, not a mismatched premise.

**Not a fresh scaffold.** `cloud-itonami/cloud-itonami-isic-8219` already
existed as a legitimate `:blueprint`-tier repo (published by an earlier
bulk-scaffolding pass, commit `e00f8b3`:
`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/business-model.md`/
`docs/operator-guide.md` — no `deps.edn`, no `src`, no `test`). That
blueprint already frames the vertical carefully: a `Scope note` in the
pre-existing README distinguishes retail office/business support services
(copy shops, document-typing/preparation, mailing/business-support) from
`cloud-itonami-isic-1812`'s industrial pre-press/bindery services, and
names the domain's real compliance concerns — unauthorized-practice-of-law
statutes (California Legal Document Assistant registration for fee-based
legal-document preparation), copyright/fair-use awareness, and customer
data-privacy obligations over the personal/financial documents customers
bring in. This ADR's changes preserve that framing as-is: none of the
existing boilerplate docs or `blueprint.edn` narrative were rewritten or
removed.

**Scope of the actor implemented here**: COORDINATION ONLY, mirrored
closely on the verified, independently-re-tested sibling
`cloud-itonami-isic-8020` (Security Systems Services Operations
Coordination) module shape (facts/store/registry/governor/ofsupllm
advisor/operation/phase/sim, `langgraph`/`langchain` StateGraph,
independent Governor, phase 0→3 rollout, append-only audit ledger).
Domain-adapted for office-support-services operations run on behalf of
client customers: job/document-count/turnaround data logging, job/
equipment scheduling coordination, paper/toner/equipment supply-order
coordination, and document-confidentiality/data-breach concern flagging —
never finalizing a data-privacy-compliance decision over a client's
documents or releasing/disclosing a client's documents. This domain
touches customers' personal and financial documents directly, so the
closed op allowlist never includes an op that directly finalizes a
data-privacy-compliance decision — always a hard permanent block (see
Decision §2) — matching the same person-facing-service safety discipline
ADR-2607152500 (Wave 4 rollout amendment) establishes for domains that
touch personal-data/consent decisions directly, applied here on this
actor's own merits even though ISIC 8219 sits in Wave 3, not Wave 4.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — job/document-count/turnaround data logging
- `:schedule-service-operation` — job/equipment scheduling proposal
- `:flag-confidentiality-concern` — surface a document-confidentiality/
  data-breach concern — **ALWAYS escalates**
- `:coordinate-supply-order` — paper/toner/equipment procurement
  coordination

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (structural + textual
scope-exclusion, see check below), not a separate "unknown op" carve-out.

### 2. Governor rules: ten HARD checks (permanent, un-overridable), plus a differentiated escalation model

The closed op/action allowlist structurally excludes any op/action that
directly finalizes a data-privacy-compliance decision over a client's
documents or releases/discloses a client's documents — that is always a
hard permanent block (`action-allowlist-violations`/`op-allowlist-
violations`), reinforced by an independent textual scan
(`scope-exclusion-violations`) of the proposal's own rationale/summary for
finalization/execution ACTION phrases (never a bare noun — see the
self-tripping-bug note below). Additional HARD checks, mirroring
`cloud-itonami-isic-8020`'s own governor discipline: `effect-not-propose`
(structural), `spec-basis` (a `:log-service-record` must cite an OFFICIAL
per-jurisdiction document-handling/data-privacy registration source, never
invent one), `record-not-verified` (a client/job record must be
independently verified/registered — via a committed `:log-service-record`
— before ANY of the other three ops, not only the highest-stakes one),
`registration-unconfirmed` (a fee-based legal-document-preparation job
needs its own document-preparer registration independently confirmed
before scheduling), `open-confidentiality-concern` (an unresolved
confidentiality concern blocks scheduling/supply-coordination),
`already-scheduled`/`already-coordinating` (dedicated double-actuation
guards, never a `:status` value).

UNLIKE `cloud-itonami-isic-8020` (where every non-logging write op is
unconditionally high-stakes, because a security-systems installation
dispatch always carries real dispatch/safety weight), this domain's own
design deliberately differentiates the escalation model:

- `:flag-confidentiality-concern` — always escalates, unconditionally
  (TWO independent layers: an unconditional `high-stakes` stake, AND
  absence from every phase's `:auto` set, at any phase).
- `:coordinate-supply-order` — escalates ONLY when its own estimated cost
  exceeds a governor-owned cost threshold (`ofsup.governor/high-cost-
  supply-threshold-usd`, 500 USD); a routine paper/toner reorder is
  auto-eligible when clean, a large equipment/procurement commitment
  always needs a human. A missing/non-numeric estimate is treated
  conservatively as high-cost.
- `:log-service-record`/`:schedule-service-operation` — carry no
  data-privacy-finalization/document-release weight of their own, so
  neither sets a `high-stakes` stake; both are phase-3 auto-eligible when
  the governor is otherwise clean and confidence is high.

Per this fleet's known self-tripping bug class — a governor's own
scope-exclusion term list phrased as a bare noun (e.g. "release",
"disclosure", "compliant") accidentally matching inside the mock
advisor's own DEFAULT rationale/disclaimer text for a legitimate, allowed
proposal — every term in `ofsup.governor/scope-exclusion-actions` is
phrased as the full finalization/execution ACTION (e.g. "finalize the
data-privacy-compliance determination for these documents", "release
these client documents without client authorization"), never a bare noun.
A dedicated regression test, `ofsup.governor-self-trip-test`, runs the
default mock advisor's own proposal for every allowed op (including
`:flag-confidentiality-concern`, whose entire job is to talk about
confidentiality concerns, and `:coordinate-supply-order` at both
above/below the cost threshold) across every seeded job, and asserts none
of them ever trip `:scope-exclusion-violation`.

### 3. Module shape

`ofsup.facts` (per-jurisdiction data-privacy/document-handling
registration catalog — California Legal Document Assistants Act, UK
ICO/GDPR data-controller registration, Japan APPI), `ofsup.store`
(MemStore + DatomicStore parity, `job` entity), `ofsup.registry` (pure
service-schedule/supply-order draft-record construction), `ofsup.governor`
(Office Support Governor, ten HARD checks), `ofsup.ofsupllm`
(OfficeSupport-LLM advisor, mock + real-LLM seam), `ofsup.phase` (0→3
rollout), `ofsup.operation` (the `langgraph` StateGraph: intake → advise →
govern → decide → commit | hold | request-approval), `ofsup.sim` (demo
driver, `clojure -M:dev:run`).

## Consequences

- Actor repo `cloud-itonami/cloud-itonami-isic-8219` (pre-existing
  `:blueprint`-tier repo, NOT freshly scaffolded) filled in: `deps.edn`,
  `.gitignore`, `docs/adr/0001-architecture.md`, full `src/ofsup/*.cljc` +
  `test/ofsup/*.clj` module set added on top of the existing boilerplate
  docs/`blueprint.edn`, which are preserved unchanged. Committed and
  pushed directly to `main` (fast-forward from `e00f8b3`, no divergence):
  `96ee75ab0345eca4b8fa35c73c3914397e2c404d`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 43 tests containing 405 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`), independently re-verified against a fresh clone
  with the same result. `clj-kondo` (`clojure -M:lint`) clean: 0 errors,
  0 warnings. `clojure -M:dev:run` demo driver exercised end to end: two
  clean auto-commits (record log, service schedule), one clean low-cost
  auto-commit (supply order), one high-cost escalate-then-approve flow
  (supply order, different job), one always-escalate confidentiality-
  concern flow, and all seven HARD-hold scenarios (no-spec-basis,
  unregistered record on two different ops, unconfirmed document-
  preparer registration, an open confidentiality concern on two different
  ops, an already-open supply-order coordination, a double-schedule, and
  a double supply-order coordination) all resolved as designed.
- Registry entry updated in place (exact-text edit of the existing
  `{:id "8219" ...}` block only, not appended, not touching any other
  entry): `:maturity :implemented` added (previously absent — the entry
  resolved to `:blueprint` only via the `:repo`-present fallback in
  `kotoba.industry/maturity-of`), and `:name` de-truncated to the full
  ISIC-08 name. `:repo` and `:business-id` were already correct
  (`https://github.com/cloud-itonami/cloud-itonami-isic-8219` /
  `cloud-itonami-8219`, matching the existing `blueprint.edn`'s own
  `:itonami.blueprint/id` and the same non-`isic-`-infixed business-id
  convention already used by sibling `cloud-itonami-isic-8020`/
  `cloud-itonami-isic-8220` and ~90 other registry entries) and were left
  unchanged, as were `:required-technologies`/`:optional-technologies`/
  `:operating-states`.

## References

- `cloud-itonami-isic-8020/` (module-shape mirror, ADR at
  `docs/adr/0001-architecture.md` in that repo — verified working,
  independently re-tested precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"8219"`
  entry
- ADR-2607152500 (Wave 4 rollout amendment; the person-facing-service
  safety guardrail this actor's own scope-exclusion design independently
  matches, on its own domain merits)
- `90-docs/adr/2651082200-cloud-itonami-isic-8220-call-centres-coverage.md`
  (sibling target in the same 6-target cleanup batch; independently
  confirms the `cloud-itonami-8219` non-`isic-`-infixed business-id
  convention)
- skill `build-actor` (advisor/governor/StateGraph/audit-ledger actor
  pattern)
