# ADR-2607158000: cloud-itonami-isic-4330 — Building Completion and Finishing Operations-Coordination Actor

**Status**: ACCEPTED

**Context**

ISIC Rev.5 4330 ("Building completion and finishing" — plastering,
painting, glazing, floor/wall tiling, finish carpentry) had a `:spec`-only
placeholder entry in `kotoba-lang/industry`'s registry pointing at a
never-created `gftdcojp/cloud-itonami-F4330` repo. Confirmed via `gh api`
404 before any work began (per this fleet's ID/name-mismatch caution) that
no repo exists at either that stale placeholder or the real `cloud-itonami`
org — this is a fresh scaffold, not a fix to an existing broken attempt.

Building-completion/finishing trades (plastering, painting, glazing,
tiling, finish carpentry) are safety-relevant but materially lower-risk
than the demolition (`cloud-itonami-isic-4311`) and road/rail heavy-civil
construction (`cloud-itonami-isic-4210`) siblings this actor mirrors
structurally: no heavy equipment operating near public traffic, no
structural-collapse risk from the actor's own coordinated work. The
relevant real-world hazards are scaffold/fall risk and pre-existing
materials hazards (lead-based paint, asbestos in old finishes) disturbed
by renovation work, plus the general trade-crew/materials-procurement
coordination every sibling actor in this fleet already handles.

**Decision**

Scaffold and implement `cloud-itonami-isic-4330` (Building completion and
finishing) as a full actor, mirroring the `cloud-itonami-isic-4311`
(Demolition) coordination-only pattern module-for-module, adapted to the
lower-risk trade-finishing domain:

1. **Advisor** (`finishing.advisor`): drafts site-record-log,
   finishing-operation-schedule, safety-concern-flag and supply-order
   proposals (deterministic mock advisor; extensible to a real LLM via
   the same `Advisor` protocol seam every sibling actor uses).

2. **Governor** (`finishing.governor`): EIGHT HARD gates that cannot be
   overridden by human approval — unknown op (closed 4-op allowlist),
   `:effect` not `:propose`, forbidden action class (trade-equipment-
   control / direct-actuation / structural-completion-sign-off-
   finalization markers), site not independently verified/registered,
   legal-basis missing, pre-work hazmat survey incomplete, fall-
   protection noncompliant (quantitative jurisdictions only), unresolved
   safety concern on file.

3. **Store** (`finishing.store`): MemStore (demo) and DatomicStore
   (`langchain.db` + `langchain-store.core`, ADR-2607141600) backends,
   same protocol contract proven via a shared contract test, immutable
   append-only audit ledger.

4. **Facts** (`finishing.facts`): per-jurisdiction (JPN, USA, DEU-as-EU-
   proxy) pre-work hazmat-survey and fall-protection legal-basis catalog.
   Every citation was independently verified via WebFetch/WebSearch
   against its official source before being written (osha.gov, ecfr.gov,
   laws.e-gov.go.jp, eur-lex.europa.eu, baua.de) — see "Legal-basis
   citations" below. Honest coverage reporting (3 of ~194 jurisdictions
   seeded, starting catalog, same discipline as ADR-2607022900).

5. **Phase** (`finishing.phase`): 0→3 rollout (read-only → assisted-
   logging → assisted-coordination → supervised-coordination).

6. **Notify** (`finishing.notify`): mail+phone safety-concern-notice
   dispatch to a site's supervisor/safety-officer roster, fired only
   after human approval.

7. **Registry** (`finishing.registry`): pure-function record
   construction (site-record-log / schedule-proposal / safety-concern-
   flag / supply-order-proposal drafts) + safety-concern-notice document
   rendering.

8. **Operation** (`finishing.operation`): the langgraph-clj StateGraph
   wiring advisor → governor → phase-gate → commit/hold/human-approval,
   same shape every sibling actor in this fleet uses.

9. **Tests**: 69 tests / 248 assertions across governor contract (all
   eight HARD checks, effect/forbidden-action-class defense-in-depth,
   ledger discipline, approval rejection), phase invariants, facts
   coverage + citation-honesty, store contract (MemStore ≡ DatomicStore
   parity), registry record construction, notify fan-out/isolation.

**Implementation details — deliberate differences from the 4311/4210 reference**

- **`:schedule-finishing-operation` is NOT a permanent `high-stakes`
  member**, unlike `demolition.governor`/`roadrail.governor`'s own
  schedule ops (which ALWAYS escalate at every phase, because they
  coordinate potential HEAVY-equipment dispatch adjacent to structural-
  collapse/public-traffic/buried-utility risk). Trade-finishing
  scheduling is a materially lower-stakes coordination artifact — once
  all eight HARD checks clear and confidence is high, it MAY auto-commit
  at phase 3, the same treatment `:order-supplies` gets. All eight HARD
  checks still apply unconditionally regardless of phase; only the
  routing to a human vs. auto-commit changes. `:flag-safety-concern`
  remains an unconditional `high-stakes` member (always escalates),
  matching every sibling actor.
- **No demolition-notification-lead-time analog exists for finishing
  work** (there is no fixed-day advance regulatory filing the way
  demolition has). Replaced with a genuinely real, independently-
  recheckable numeric trigger for this domain: fall-protection trigger
  height (`:scaffold-working-height-m` / `:fall-protection-installed?`
  ground-truth fields, `finishing.facts/fall-protection-noncompliant?`
  recomputes the same three-valued quantitative/qualitative/nil shape
  `demolition.facts/notification-lead-insufficient?` established).
- **Fully portable `.cljc`, NO JVM interop anywhere in `src/`** (mandatory
  for this task, stricter than some earlier sibling actors). Unlike
  `demolition.notify`/`roadrail.notify`, which embed a `#?(:clj ...)`
  `java.net.http` Resend/Twilio client, `finishing.notify`'s real-
  transport seam (`fn-notifier`) takes caller-injected plain functions —
  the actor itself never touches a platform HTTP client, so it runs
  unmodified on JVM Clojure, ClojureScript, `nbb`, and `kotoba wasm`/
  `clojurewasm` alike, per this workspace's `.cljc`/`.kotoba` runtime
  priority (cljs-first) rule.
- **No robotics, no new Rust**: `:itonami.blueprint/robotics false`,
  honestly — this actor holds no trade-equipment-control or structural-
  completion-sign-off authority (both permanent HARD governor blocks),
  and no Rust source was written (this actor only coordinates; it never
  actuates), consistent with the `cloud-itonami-isic-4211`/`4311`/`4210`
  robotics-premise reference pattern.

**Legal-basis citations (independently verified before writing)**

| Jurisdiction | Pre-work hazmat-survey basis | Fall-protection basis |
|---|---|---|
| JPN | 石綿障害予防規則第3条 — laws.e-gov.go.jp/law/417M60000100021 | 労働安全衛生規則第518条（高さ2m以上）— laws.e-gov.go.jp/law/347M50002000032 |
| USA | 40 CFR Part 745 Subpart E (EPA Lead RRP Rule, pre-1978) — ecfr.gov | 29 CFR 1926.501 (OSHA, 6ft/1.8m trigger) — osha.gov |
| DEU (EU proxy) | Directive 2009/148/EC Art.11 + GefStoffV/TRGS 519 — eur-lex.europa.eu | TRBS 2121 (qualitative, risk-assessment-based) — baua.de |

**Consequences**

- Certified building-finishing operators can fork and deploy
  independently, with auditable coordination records and hard safety
  gates that never permit trade-equipment control or a structural-
  completion sign-off to slip through, even with human approval.
- Establishes the fleet's first coordination-only actor whose own
  schedule op is deliberately lower-stakes than its structural
  siblings' — a template for future trade-craft (as opposed to heavy-
  civil) construction verticals to differentiate risk tiers honestly
  instead of copying the strictest sibling's posture uniformly.
- Establishes the fleet's first actor built under the stricter "no JVM
  interop anywhere in `src/`" mandate — `finishing.notify`'s injected-
  function transport seam is a reusable pattern for any future actor
  that needs a real external-transport hook without embedding platform
  interop in the actor repo itself.
- `kotoba-lang/industry` registry entry `"4330"` promoted `:spec` →
  `:implemented`, `:repo`/`:business-id` corrected from the stale
  `gftdcojp/cloud-itonami-F4330` placeholder to
  `cloud-itonami/cloud-itonami-isic-4330`.

---

Repo: [`cloud-itonami/cloud-itonami-isic-4330`](https://github.com/cloud-itonami/cloud-itonami-isic-4330)
Registry: [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
