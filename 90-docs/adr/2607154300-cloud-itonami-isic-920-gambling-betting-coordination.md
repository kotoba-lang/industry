# ADR-2607154300: cloud-itonami ISIC-920 Gambling and Betting Activities — Back-Office Facility Administration & Responsible-Gambling Referral Coordination Actor

**Status**: accepted  
**Date**: 2026-07-15  
**Deciders**: Jun Kawasaki  
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-920`, `orgs/kotoba-lang/industry` registry

## Context

ISIC 920 (Gambling and betting activities) is a Wave-4 service in the cloud-itonami fleet's rollout sequence (ADR-2607121000). This division covers casinos, betting operations, and lottery operators — but **this actor's scope is STRICTLY limited to back-office facility administration and responsible-gambling referral logistics only**.

This is the **seventeenth Wave-4 batch actor scaffold today, fourth in the arts/entertainment/recreation cluster** (after isic-900, isic-931, isic-932, all landed).

**CRITICAL DOMAIN CONSTRAINT**: Real-money wagering + problem-gambling harm risk + AML regulatory exposure demands **MAXIMALLY CONSERVATIVE scope enforcement**. The actor must never touch wagering transactions, odds-setting, payout decisions, currency/chips, age/identity verification determinations, AML/KYC decisions, or any clinical determination about whether a specific patron has a gambling problem.

Modeled closely on isic-932's module shape (store/advisor/governor/phase/operation/sim, all `.cljc`, langgraph-clj StateGraph) but with **HARD scope gates** that reject any proposal touching restricted territory.

## Decision

Build and land `cloud-itonami-isic-920` as a public actor repo with:

### Operations (Closed Allowlist — Back-Office/Facility/Referral Only)

- **`:schedule-facility-maintenance`** — Non-gaming-equipment/facility maintenance scheduling (housekeeping, HVAC, general facility upkeep). Explicitly NOT gaming-equipment calibration, certification, or inspection sign-off.
- **`:coordinate-responsible-gambling-referral`** — Administrative logistics of connecting a patron to responsible-gambling support resources (hotline info, self-exclusion program paperwork routing). Explicitly NEVER a determination that someone has a gambling problem; only triggered by the patron's own request or an already-independently-flagged concern; purely logistical.
- **`:coordinate-supply-request`** — Non-gaming consumables (F&B, housekeeping, office supplies). Explicitly NOT gaming chips/tokens/currency or anything wagering-related.
- **`:schedule-staff-shift-proposal`** — Administrative shift PROPOSAL only, never a dealer-certification/gaming-license/table-assignment decision, never binding.
- **`:flag-safety-concern`** — Facility safety concerns AND observed problem-gambling risk signals (e.g., a patron displaying distress) for HUMAN follow-up. ALWAYS escalates, never auto-commits, and is explicitly NOT itself a clinical determination.

### Three HARD, Permanent, Un-Overridable Governor Checks

1. **Facility-resource/referral-record unverified**: Target must exist in store AND be independently `:registered?`/`:verified?`, re-derived every time.
2. **Effect not `:propose`**: Rejected outright if not exactly `:propose`.
3. **Scope exclusion (highest priority)**: Any proposal (any op) touching:
   - Wager acceptance/rejection
   - Odds-setting
   - Payout decisions
   - Currency/chip handling
   - Age or identity verification determinations
   - AML/KYC compliance determinations
   - Gaming-license/regulatory-authority decisions
   - Clinical/diagnostic determination that a patron has a gambling disorder
   
   Uses EN+JA substring scan, qualified so a legitimate `:flag-safety-concern` or `:coordinate-responsible-gambling-referral` proposal isn't self-blocked merely for mentioning "gambling" or "problem" in a purely referral/logistics context. Mirror isic-932's exact qualification technique, but be even more conservative given the stakes here.

### Modules (All `.cljc`)

- **store** — MemStore (facility directory, referral resources, demo data)
- **advisor** — Proposal generation with confidence/reasoning (deterministic demo)
- **governor** — Three HARD checks, no overrides
- **operation** — langgraph-clj StateGraph (intake → advise → govern → decide → commit | hold | escalate)
- **phase** — Rollout phases 0–3 (read-only → auto-commit with escalation)
- **sim** — Demo driver (5 scenarios covering happy path, hard checks, scope violations, escalation)
- **test** — Comprehensive test suite (store, governor, hard checks, operations, phases, scope-exclusion edge cases)

### Repo Visibility

PUBLIC (`--public` flag, verified `private: false, visibility: "public"`).

### Registry Integration

Update `kotoba-lang/industry` registry.edn entry 920:
- `:maturity` from `:spec` → `:implemented`
- Add `:repo` field
- Add `:business-id` field
- **Validate with real EDN parser (nbb) before and after edit** (critical lesson from earlier batches)

## Consequences

- (+) ISIC-920 actor joins Wave-4 arts/entertainment/recreation tier with MAXIMALLY CONSERVATIVE scope boundaries for real-money wagering domain.
- (+) Three HARD governor checks prevent scope violations structurally at the language level (no human override path).
- (+) Scope exclusion check scans proposals for wager/odds/payout/currency/identification/clinical content and rejects unconditionally.
- (+) All operations auto-escalate safety concerns even if governance passes, with explicit NOT-a-clinical-determination semantics.
- (+) Comprehensive test suite exercises happy path, all hard checks, edge cases (legitimate referral ops must not self-block).
- (+) Public GitHub repo, AGPL-3.0 licensed, ready for external registration/integration.
- (−) Deterministic advisor is demo-only; production requires real LLM with prompt injection safeguards specific to wagering domain.
- (−) MemStore is in-memory; production requires persistent backing store (EDN file, database, ledger).
- (−) Shift proposals are administrative only; actual gaming-license/table-assignment decisions require separate authority gate (NEVER this actor).
- (−) Responsible-gambling referral is logistics only; determination that patron has problem gambling is EXCLUSIVELY a clinical/human expert decision (actor never makes this determination).

## References

- ADR-2607121000 (cloud-itonami Wave-4 rollout plan: ISIC 90-98 tier, gambling/betting under 900-series)
- ADR-2607152500 (Wave-4 amendment, ADR slot allocation)
- ADR-2607154200 (isic-932 design reference, module shape pattern — THREE HARD checks, scope exclusion, escalation)
- ADR-2607154100 (isic-900 design pattern)
- CLAUDE.md: Actors pattern (build-actor skill), new-project-scaffold, registry verification workflow, real-money wagering harm risk minimization
- cloud-itonami-isic-932 GitHub repo (module shape reference: store/advisor/governor/phase/operation/sim)

## Artifacts

- GitHub public repo: `cloud-itonami/cloud-itonami-isic-920`
- `blueprint.edn`: operations (5-item closed allowlist), governor hard checks, phases 0–3, scope-exclusion keywords
- `src/gamblingfacilityops/{store,advisor,governor,operation,phase,sim}.cljc` (all `.cljc`)
- `test/gamblingfacilityops/test.cljc` (store, governor 3 hard checks, operations, phases, scope-exclusion edge cases)
- `deps.edn`: clojure 1.12.0, clojurescript 1.10.914
- `LICENSE` (AGPL-3.0), README (HARM MINIMIZATION), CODE_OF_CONDUCT, CONTRIBUTING, GOVERNANCE, SECURITY
- `kotoba-lang/industry` registry.edn: entry 920 updated to `:implemented`
- This ADR (edn+md pair)

## Addendum (2026-07-15): post-landing defects found and fixed by independent verification

Direct code review (not self-report) of the landed `governor.cljc` found the
EN-language half of `contains-forbidden-word?` was computed but its result
discarded (dead code) — only the Japanese keyword list actually gated the
scope-exclusion HARD check, so English forbidden terms like "wager"/"odds"/
"payout" were not being blocked for non-legitimate ops. Real test execution
(via `nbb`, not trusting the "16 tests, all green" self-report) then
surfaced two more defects: `sim.cljc`/`test.cljc` used `clojure.core/format`,
which does not exist under ClojureScript/nbb, breaking this `.cljc` module's
portability; and `store/facility-verified?` returned `nil` instead of an
explicit `false` for an unknown facility, failing a strict test assertion.
All three fixed and independently re-verified (16/16 tests, 5/5 demo
scenarios, both actually run). Also fixed separately: the registry entry's
`:required-technologies` had not had `:robotics` stripped, unlike every
sibling coordination-only actor. `:repo`/`:business-id`/`:maturity` were
correct from the original landing. Registry EDN validity (648 industries)
confirmed before and after every fix via a real EDN parser.
