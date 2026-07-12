# ADR-2607122030: `gftdcojp/cloud-murakumo-market-analyst` — a governed market-analysis actor over `cloud-murakumo-market-intel`

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: new repo `gftdcojp/cloud-murakumo-market-analyst`

## Context

The user asked, alongside the market-size dataset (ADR-2607122000), for a
reusable **"market analysis actor" repo** — a generalization of the
one-off request into infrastructure that can keep doing market-sizing /
market-comparison analysis. This monorepo already has three actors of
the exact shape needed (`robotaxi-actor`: AR1 ⊣ SafetyGovernor,
`gftd-talent-actor`: HR-LLM ⊣ PolicyGovernor, `cloud-itonami`: ops-LLM ⊣
CertGovernor): **a contained intelligence node that only ever proposes**,
censored by an independent Governor before anything commits, on an
append-only ledger, expressed as a `langgraph-clj` `StateGraph` with one
run per operation (no unbounded inner loop).

A market-analysis actor has a distinctive failure mode none of the three
existing actors needs to guard against: **numeric claims that aren't
grounded in the facts it was given** (an LLM asserting a market size,
growth rate or ranking that doesn't trace back to a `market-intel`
entity, or silently mixing `:confidence :derived`/`:estimated` figures
into a claim presented as measured fact). The Governor for this actor is
therefore a **citation/grounding gate**, not a fairness or safety gate.

## Decision

- New repo: **`gftdcojp/cloud-murakumo-market-analyst`** (private). Role
  suffix per the repo-naming rule (no `-clj` suffix; "analyst" is the
  role, `cloud-murakumo` is the family this actor's first knowledge base
  — `cloud-murakumo-market-intel` — belongs to). Depends on
  `cloud-murakumo-market-intel` as a sibling west checkout
  (`{:local/root "../../gftdcojp/cloud-murakumo-market-intel"}`), the same
  way actors depend on `langgraph-clj`/`langchain-clj`.
- **StateGraph** (`src/analyst/operation.cljc`), one run = one analysis
  request (`:market/compare`, `:market/rank`, `:market/report`):
  `intake → advise → govern → decide → {commit | hold | request-approval}`,
  `interrupt-before #{:request-approval}` for human sign-off before a
  report is published externally — identical shape to
  `talent.operation`/robotaxi/cloud-itonami.
- **Analyst-LLM node** (`src/analyst/advisor.cljc`) — sealed, proposal-only,
  mirroring `talent.hrllm`: a `defprotocol Advisor` with a deterministic
  `mock-advisor` (default, offline-testable) and an `llm-advisor` wrapping
  `langchain.model`. Every proposal carries `:summary :rationale :cites
  :effect :stake :confidence`, where `:cites` is now a vector of
  **`:market/id` values actually read from the store**, not free-text —
  this is what the Governor checks.
- **MarketGovernor** (`src/analyst/policy.cljc`) — the citation/grounding
  gate, in priority order (hard violations force HOLD, no human override;
  soft violations escalate to a human approver):
  1. **Grounding** (hard) — every numeric claim in the proposal must cite
     at least one real `:market/id` present in the store. An un-cited
     number is fabrication and is rejected outright, the market-analyst
     analog of the fairness gate in `talent.policy`.
  2. **Confidence-transparency** (hard) — a proposal that cites a
     `:market/confidence :derived` or `:estimated` fact MUST say so in
     `:summary`/`:rationale` (checked by presence of the fact's confidence
     tag in the proposal's disclosed caveats); presenting a derived/estimated
     figure as `:measured` fact is rejected.
  3. **Staleness** (soft → escalate) — citing a fact whose `:market/as-of`
     is older than the actor's configured freshness window surfaces a
     human review rather than silently publishing outdated sizing.
  4. **Confidence floor** (soft → escalate) — proposal `:confidence` below
     threshold escalates, same as the other three actors.
  5. **Publish gate** (hard, always escalate — high-stakes) — any
     `:effect :publish-report` (i.e., leaves the actor's own graph and
     goes external) always requires human approval; it is never
     auto-committed regardless of confidence, mirroring `talent.policy`'s
     `high-stakes` set and robotaxi's Minimal Risk Condition philosophy
     ("the model doesn't get to auto-publish just because it was
     confident").
- **Store** (`src/analyst/store.cljc`) — same `Store` protocol /
  `MemStore` + `DatomicStore` (`langchain.db`) pair as `talent.store`,
  holding this actor's own append-only ledger and its committed
  reports/comparisons — a separate graph from `cloud-murakumo-market-intel`
  (the actor *reads* market-intel's facts via its own `Store`/`DataScriptStore`
  query surface, per ADR-2607122000; it does not co-mingle its ledger with
  the source dataset).
- **Phase 0 rollout** (`src/analyst/phase.cljc`), matching the other
  actors' 0→3 injected-phase convention: Phase 0 ships with the mock
  advisor + MemStore only (deterministic, offline, fully testable);
  wiring a real LLM and a real Datomic-backed market-intel store is a
  swap, not a rewrite, exactly like `talent.phase`.

## Consequences

- (+) The dataset (ADR-2607122000) and the actor that reasons over it are
  cleanly separated: one is a fact store with a query surface, the other
  is a governed reasoning loop. Either can be swapped/extended
  independently (a 2024/2026 market-size edition doesn't require touching
  the actor's Governor; a new analysis operation doesn't require touching
  the dataset schema).
- (+) The grounding gate is the generalizable contribution: any future
  "analysis actor" over a fact store in this monorepo (not just market
  sizing) can reuse the same citation-must-resolve-to-a-real-fact check.
- (−) Phase 0 only in this ADR — no real LLM wiring, no real Datomic
  Local/kotoba-server pointing, no published report yet. Those are
  explicit follow-ups, not silently deferred.
- Follow-up (not done by this ADR): `gh repo create` (gftdcojp, private) +
  push, west manifest registration
  (`nbb scripts/gen-west-manifest.cljs --entry cloud-murakumo-market-analyst`).

## Related

- ADR-2607122000 (`cloud-murakumo-market-intel`, this actor's primary knowledge base).
- `gftd-talent-actor` (`docs/adr/0001-architecture.md`, `docs/adr/0002-backend-seams.md`) — the direct structural model (Store/Advisor/Governor/Phase/StateGraph split).
- `cloud-itonami` (ops-LLM ⊣ CertGovernor), `robotaxi-actor` (AR1 ⊣ SafetyGovernor) — the other two actors of this shape referenced in `CLAUDE.md`'s "Actors" section.
