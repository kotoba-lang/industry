# ADR 2700003341: cloud-itonami ISCO-08 3341 office-supervisors coverage

## Status

Accepted

## Context

This is the first `cloud-itonami-isco-*` (ISCO-08 occupation-classification)
actor built in this session, sibling to the just-completed
`cloud-itonami-isic-*` (ISIC industry-classification) rollout under the same
governing ADR-2607121000 (reverse-topological rollout plan) and
ADR-2607012000 (`cloud-itonami-isco-occupation-blueprints`, the ISCO track's
own founding decision). This ADR covers **ISCO-08 class 3341 — Office
Supervisors** only.

The `kotoba-lang/occupation` registry's live `{:id "3341" ...}` entry was
verified against a fresh clone (with `kotoba-lang/technology` as the
`../technology` sibling dependency `kotoba.occupation` requires) before any
work began:

```clojure
{:id 3341, :name Office Supervisors, :maturity :spec,
 :required-technologies [:robotics :identity :audit-ledger],
 :optional-technologies [],
 :operating-states [:intake :propose :approve :execute :audit]}
```

(`:name` prints without quotes because `println` uses human-readable, not
machine-readable, string formatting — the underlying value is the string
`"Office Supervisors"`, confirmed against the registry's raw EDN.) Prior to
this ADR the entry's `:maturity` was `:spec` with no `:repo`/`:business-id`;
no repository existed at `cloud-itonami/cloud-itonami-isco-3341` (`gh api`
404-confirmed before scaffolding). This ADR and its companion registry edit
promote the `3341` entry to `:implemented`.

The reference implementation mirrored is
[`cloud-itonami/cloud-itonami-isco-3313`](https://github.com/cloud-itonami/cloud-itonami-isco-3313)
(Accounting Associate Professionals), independently re-verified working in
this session, adapted to office-supervision coordination (task-record
filing, shift/task scheduling, HR-concern surfacing, office-supply
ordering).

## Decision

Publish `cloud-itonami/cloud-itonami-isco-3341` as an OSS operations-
coordination actor for office supervision, following the SAME governed-actor
architecture as every prior `cloud-itonami-isic-*`/`cloud-itonami-isco-*`
actor in this fleet: **Office Supervision Advisor ⊣
OfficeSupervisionGovernor** on a `langgraph.graph` (`io.github.kotoba-lang/
langgraph`, git-sha-pinned — NOT `langgraph-clj`, which is the ISIC track's
dependency coordinate) StateGraph, with an independent Governor,
human-in-the-loop approval via `interrupt-before`, and an append-only audit
ledger.

This actor is deliberately **coordination only, never disciplinary
authority**:

### Closed proposal-op allowlist (all `:effect :propose`)

- `:log-workflow-record` — task assignment/completion data logging
- `:schedule-staff-operation` — clerical-staff shift/task scheduling
  proposal
- `:flag-hr-concern` — surface a performance/conduct concern, ALWAYS
  escalates
- `:coordinate-supply-order` — office-supply procurement proposal

### Five HARD governor checks (permanent, un-overridable)

1. **Office provenance** — the requesting office must be registered.
2. **No-actuation** — proposal `:effect` must be `:propose`.
3. **Closed op-allowlist** — `:op` must be one of the four ops above.
   Nothing that finalizes a disciplinary action, termination or
   performance-review determination is ever a member of this set.
4. **Workflow/staff basis** — a `:log-workflow-record`/
   `:coordinate-supply-order` proposal must cite a REGISTERED workflow
   belonging to the requesting office; a `:schedule-staff-operation`/
   `:flag-hr-concern` proposal must cite a REGISTERED staff member
   belonging to the requesting office.
5. **Finalization-language block** — a proposal whose rationale describes
   actually TAKING a finalizing action ("finalize the termination",
   "finalize the disciplinary action", "finalize the performance review
   determination", etc.) is a hard, permanent block regardless of which
   `:op` it is nominally filed under. This is defense-in-depth on top of
   invariant 3.

### Three ESCALATE (SOFT) gates

- `:flag-hr-concern` — ALWAYS escalates to a human, regardless of
  confidence (in the governor's `always-escalate-ops` set; never
  auto-commit-eligible).
- `:coordinate-supply-order` whose `:cost` exceeds the workflow's
  registered `:max-supply-cost` ceiling — always needs human sign-off
  (an over-ceiling order is refused-then-escalated, not refused outright —
  it may be a legitimate one-off purchase, mirroring
  cloud-itonami-isco-3313's `:max-transaction-amount` ceiling pattern, but
  unlike that repo's HARD ceiling this one is a SOFT escalate per this
  actor's domain spec).
- Low confidence (< `confidence-floor` 0.6) also escalates.

### Self-trip guard (fleet-known bug class)

Multiple sibling actors in this fleet independently discovered the same bug
class: a governor's scope-exclusion term list phrased as a bare noun (e.g.
"disciplinary", "termination", "performance review") can accidentally match
inside the mock advisor's own default rationale text for a legitimate
proposal, self-blocking the happy path — this matters doubly here because
`:flag-hr-concern`'s own job is to discuss exactly those topics. This
actor's `officesupervision.governor/finalization-phrases` is phrased
exclusively as finalization/execution ACTIONS ("finalize the termination",
"finalize a disciplinary action", "finalize the performance review
determination"), never bare nouns, and
`officesupervision.governor-test/never-self-trips-on-default-mock-advisor-proposals`
is a dedicated regression test asserting every op the default mock advisor
can generate (across both `:conduct`/`:disciplinary`/`:performance`
concern categories) never trips `:finalization-language-blocked` or
`:op-not-allowed`. A companion actor-level test
(`officesupervision.actor-test/holds-a-finalization-language-proposal-regardless-of-nominal-op`)
exercises the same guard through the full `langgraph.graph` run, confirming
a rogue advisor proposing finalization language under a legitimate,
allowlisted op still goes straight to `:hold` and never reaches
`:request-approval`.

## Consequences

- `kotoba-lang/occupation` registry's `{:id "3341" ...}` entry is promoted
  from `:spec` to `:implemented`, with `:repo`/`:business-id` set to the
  real repository (`https://github.com/cloud-itonami/cloud-itonami-isco-3341`
  / `cloud-itonami-isco-3341`), and `maturity-summary`'s implemented count
  bumped accordingly (recomputed from the live registry via
  `kotoba.occupation/maturity-summary`, not by hand-derivation).
- Full test suite (`clojure -M:test`): 20 tests, 61 assertions, 0 failures,
  0 errors — raw output captured in the task report and reproduced on a
  fresh re-clone after push.
- No `manifest/west.yml` changes — this actor is registered purely via the
  `kotoba-lang/occupation` capability registry, consistent with sibling
  `cloud-itonami-isic-*`/`cloud-itonami-isco-*` actors.

## Alternatives considered

- A bare-noun scope-exclusion term list (e.g. `["disciplinary"
  "termination" "performance review"]`): rejected outright per the
  fleet-known self-trip bug class described above — would have
  self-blocked this actor's own `:flag-hr-concern` happy path, which
  structurally must discuss disciplinary/termination/performance-review
  topics to do its job.
- Making the supply-order cost ceiling a HARD block (mirroring
  cloud-itonami-isco-3313's `:max-transaction-amount` treatment exactly):
  rejected per this actor's domain spec, which explicitly places "supply
  orders above a cost threshold" under ESCALATE, not HARD — an over-budget
  office-supply order may be a legitimate one-off purchase a human should
  be able to approve, unlike an unauthorized ledger posting.
- Relying solely on the closed op-allowlist (invariant 3) without the
  separate finalization-language check (invariant 5): rejected — the
  allowlist alone would not catch a rogue advisor that files a
  finalization action under a legitimate op's name (e.g. proposing
  `:log-workflow-record` with a rationale that actually narrates
  finalizing a termination); invariant 5 is defense-in-depth against
  exactly that.
