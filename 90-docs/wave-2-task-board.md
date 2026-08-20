# Wave 2: quad-store/commit-dag Integration Task Board

**Timeline:** 2026-07-21 → 2026-08-05 (16 calendar days, 8-10 working days)
**Owner:** Jun Kawasaki
**Deadline:** 2026-08-05
**Critical Path:** Task 1 → Task 2 → Task 3 → Task 4 → Verification

## Wave 2 Objective

Resolve ADR-2607022610 Gap-1: Unify quad-store and commit-dag to eliminate semantic authority duplication. Establish single-source-of-truth pattern for commit verification across kotobase ecosystem (kekkai, kototama, kotoba-client).

## Task Breakdown

### Task 1: quad-store.verify-chain() Implementation [2-3 days]
**Dates:** 2026-07-21 → 2026-07-24
**Owner:** Jun Kawasaki
**Effort:** 2-3d

#### Description
Add cryptographic chain verification capability to kotoba-lang/quad-store. This is the core Gap-1 resolution: move verification logic from commit-dag into quad-store as a first-class method.

#### Acceptance Criteria
- [ ] `verify-chain(roots: Set<CID>, chain-head: CID, pubkey: ed25519) → Result<CID>` implemented in quad-store/src
- [ ] Signature verification against ed25519 public key working
- [ ] Integration with quad-store's existing 4-index (spo/pso/pos/ocp) tested
- [ ] Unit tests for valid/invalid signatures passing
- [ ] Test suite: `kotobase-peer/test/kotobase/quad_store_verify_test.cljc`
- [ ] CI/test pipeline green
- [ ] Documentation: README updated with verify-chain API

#### Dependency Inputs
- quad-store existing 4-index implementation (completed Wave 1)
- ed25519 library (kotoba-lang/ed25519 already available)
- commit-dag's verification semantics (ADR-2606041151)

#### Risk Mitigation
- If integration with 4-index has edge cases, use integration test (quad-store + prolly-tree + kotobase-peer) to validate
- Signature verification can be tested in isolation before full integration

---

### Task 2: commit-dag Adapter Shrinkage [1-2 days]
**Dates:** 2026-07-24 → 2026-07-26
**Owner:** Jun Kawasaki
**Effort:** 1-2d

#### Description
Reduce kotoba-lang/commit-dag to a thin adapter/validation factory role. The namespace shifts from a primary implementation to a wrapper around quad-store.verify-chain().

#### Acceptance Criteria
- [ ] commit-dag/src retains only: factory functions, validation helpers, Clojure test fixtures
- [ ] All production verification paths redirected to quad-store.verify-chain()
- [ ] commit-dag README explicitly states: "Adapter role, quad-store authority"
- [ ] No dual-namespace consumer code (verified by: `grep -r "commit-dag\|quad-store" orgs/kotoba-lang/*/src orgs/kekkai/src`)
- [ ] Unit tests: commit-dag test suite reduced to <50% of current (fixtures/adapters only)
- [ ] CI passing: commit-dag test suite
- [ ] Code review checkpoint: dual-namespace removal complete

#### Dependency Inputs
- Task 1 completion: quad-store.verify-chain() working

#### Risk Mitigation
- Preserve all existing commit-dag tests first, then audit which are still needed
- Use sed/refactor script to bulk-redirect imports (semi-automatic)
- Validate adapter functions via kekkai/kototama tests (cross-consumer check)

---

### Task 3: Consumer-Side Integration [3-4 days]
**Dates:** 2026-07-26 → 2026-07-31
**Owner:** Jun Kawasaki
**Effort:** 3-4d

#### Description
Integrate quad-store.verify-chain() into all downstream consumers: kekkai (authorization), kototama (mesh node), kotoba-client (browser verification). Eliminate dual-namespace dependency; achieve single-source-of-truth.

#### Sub-Tasks

**3a. kekkai integration [1d]**
- [ ] kekkai/src/kekkai/cacao.cljc audit: identify all verify() calls
- [ ] Redirect verify() to quad-store.verify-chain()
- [ ] Drama access control path tested (guild authorization scenario)
- [ ] Test: `kekkai/test/kekkai/cacao_test.cljc` all green
- [ ] Regression: no behavioral change in access control decisions

**3b. kototama integration [1d]**
- [ ] kototama/src mesh node state: identify commit verification points
- [ ] Re-anchor state machine to quad-store base (vs commit-dag adapter)
- [ ] Test: `kototama/test/kototama/mesh_node_test.cljc`
- [ ] Fixture validation: all mesh node scenarios produce identical state trees

**3c. kotoba-client (browser) integration [1d]**
- [ ] kotoba-client/src browser-side verification
- [ ] WASM/WebAssembly boundary: quad-store.verify-chain() callable from JS guest
- [ ] Test: browser E2E with signature verification
- [ ] Regression: block download + verification roundtrip still works

**3d. Cross-consumer regression [0.5-1d]**
- [ ] Run full kekkai test suite (guild scenarios, multi-user authorization)
- [ ] Run kototama test suite (mesh node state transitions)
- [ ] Run kotoba-client test suite (browser block ingest + verify)
- [ ] Integration test: kekkai guild authorization → kototama mesh state → kotoba-client browser display

#### Acceptance Criteria (Overall)
- [ ] All consumer tests passing (kekkai, kototama, kotoba-client)
- [ ] No commits in Wave 2 that have `commit-dag` in dependent import paths
- [ ] ADR-2607022700 (semantic authority single-source) compliance verified
- [ ] Regression test suite (gold standard: previous behavior replicated)

#### Dependency Inputs
- Task 1 & 2 completion
- kekkai/kototama/kotoba-client existing test suites

#### Risk Mitigation
- If browser WASM boundary is complex, isolate quad-store.verify-chain() as a pure Clojure function without side effects
- If kekkai tests fail, fall back to kekkai-specific adapter (smaller scope) vs full refactor
- Use feature flags to gradually roll out: old commit-dag path + new quad-store path in parallel, then flip

---

### Task 4: Cold Rebuild Integration Planning [2-3 days]
**Dates:** 2026-08-01 → 2026-08-03
**Owner:** Jun Kawasaki
**Effort:** 2-3d

#### Description
Plan integration of cold-rebuild (prolly-tree CID → re-fetch + rebuild index) with quad-store. This is deferred implementation scope for Wave 3.5, but Wave 2 must clarify the contract.

#### Acceptance Criteria
- [ ] Prolly-tree cold path contract defined: `cold-rebuild(root-cid) → futures-of-quad-store-index`
- [ ] quad-store scope boundary: what does cold-rebuild *not* touch (answer: verify-chain logic)
- [ ] Wave 3.5 task list created: identify exact steps to integrate cold-rebuild
- [ ] Documentation: README/ADR updated with cold-rebuild plan
- [ ] Cross-repo dependency: if kotobase-peer needs prolly-tree types, dependency verified
- [ ] NO implementation: this is planning & design, not execution (defer to Wave 3.5)

#### Dependency Inputs
- quad-store implementation (Task 1)
- prolly-tree design (existing, from Wave 1)

#### Risk Mitigation
- If cold-rebuild scope is too large, break into sub-tasks for Wave 3.5 (micro-tasks)
- Document assumptions about prolly-tree layout (CID routing, child boundaries)

---

### Task 5: Wave 2 Verification & Exit [2-3 days]
**Dates:** 2026-08-03 → 2026-08-05
**Owner:** Jun Kawasaki
**Effort:** 2-3d

#### Description
Final verification that all Wave 2 exit criteria are met. Prepare for Wave 3 kickoff.

#### Acceptance Criteria
- [ ] All 5 tasks green: unit tests, regression tests, CI passing
- [ ] ADR-2607022610 Wave 2 exit criteria checklist complete:
  - quad-store.verify-chain() implemented & tested ✓
  - commit-dag adapter shrunk ✓
  - consumer dual-namespace deleted ✓
  - cold-rebuild path planned ✓
- [ ] Manifest canonical verification: `nbb scripts/gen-west-manifest.cljs --check` passing
- [ ] No regressions in kekkai/drama access control (production scenario)
- [ ] No regressions in kototama mesh node state (fixture validation)
- [ ] Commit all changes: PR → main
- [ ] ADR-2607022610 Wave 2 status updated to `done`
- [ ] Wave 3 kickoff preparation: kqe triple-pattern audit scheduled

#### Risk Mitigation
- Build 1-2 day buffer before 2026-08-05 deadline for final fixes

---

## Parallel Work & Dependencies

### Wave 3 Preparation (Non-Blocking)
- **Timeline:** 2026-08-01 → 2026-08-05 (parallel with Task 4 & 5)
- **Activity:** Audit kekkai/drama actual query patterns (for Wave 3 triple-pattern scope)
- **Owner:** Jun Kawasaki (async, can overlap)
- **Input to:** Wave 3 scope definition (verify triple-pattern is sufficient)

### No External Dependencies
Wave 2 has no blocking external dependencies. All required repos (quad-store, commit-dag, kekkai, kototama, kotoba-client) are under this workspace.

---

## Risk Register

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| quad-store + prolly-tree integration has hidden edge cases | Medium | High | Integration test with kotobase-peer; fallback: feature flag old path |
| Dual-namespace grep misses deep imports (e.g., dynamic requires) | Low | Medium | Manual code review of consumer files; test coverage for all paths |
| kekkai/drama regression (guild authorization behaves differently) | Low | High | Regression test suite + manual verification of 3-5 drama scenarios |
| Browser WASM boundary complexity (kotoba-client integration) | Medium | Medium | Isolate verify-chain as pure function; fallback: adapter layer in JS |
| Cold-rebuild scope underestimated (Wave 3.5 planning bloated) | Low | Low | Keep Task 4 as *design* not implementation; micro-task breakdown for Wave 3.5 |
| Single point of failure (Jun Kawasaki only) | High | High | Checkpoint review at Wave 2 midpoint (2026-07-28); pair programming with external reviewer |

---

## Tracking & Communication

### Daily Standup
- **Start:** 2026-07-21, 09:00 JST
- **Duration:** 15 min
- **Format:** Task status (on-track / at-risk / blocked), blockers
- **Audience:** Jun Kawasaki, optional external reviewer

### Checkpoint Reviews
- **Week 1 checkpoint (2026-07-28):** Task 1–2 complete, Task 3 kickoff
- **Week 2 checkpoint (2026-08-04):** Task 3–4–5 in progress, any regressions
- **Wave 2 complete (2026-08-05):** Final verification, PR merge, Wave 3 kickoff

### Issue Tracking
- **GitHub Issues:** One issue per task (5 issues total), labeled `wave-2`
- **Status:** Open → In Progress → Review → Closed
- **Burndown:** Updated daily

### Artifact Outputs
- **Task 1 output:** `orgs/kotoba-lang/quad-store/src/kotoba/quad_store.cljc` (verify-chain method)
- **Task 2 output:** `orgs/kotoba-lang/commit-dag/src` (adapter-only, reduced)
- **Task 3 output:** `orgs/kekkai/src`, `orgs/kotoba-lang/kototama/src`, `orgs/kotoba-lang/kotoba-client/src` (unified)
- **Task 4 output:** `90-docs/adr/*` (cold-rebuild plan, Wave 3.5 scope)
- **Task 5 output:** ADR-2607022610 updated, manifest verified, PR merged to main

---

## Wave 2 Success Definition

✅ **Success:** All tasks complete by 2026-08-05, with:
- Zero regressions in kekkai/drama or kototama mesh node
- quad-store as single authority for commit verification
- Wave 3 ready to kickoff (kqe triple-pattern audit complete)
- All changes merged to main

❌ **Failure:** Any of the following:
- Critical regression in kekkai/drama authorization
- Manifest canonical verification fails
- Task 5 incomplete by 2026-08-05 EOD
- Wave 3 kickoff delayed due to Wave 2 unfinished business

---

## Gantt Summary

```
2026-07-21 ├─ Task 1: quad-store.verify-chain() ────────────┤ 2026-07-24
           ├─ Task 2: commit-dag adapter ─────────────────┤ 2026-07-26
           │
2026-07-26 ├─ Task 3: Consumer integration ──────────────────────────────┤ 2026-07-31
           │
2026-08-01 ├─ Task 4: Cold-rebuild plan ──────────────┤ 2026-08-03
           ├─ Task 3 continued ────────────────────────────────┤ (if running over)
           │
2026-08-03 ├─ Task 5: Verification & Wave 3 prep ────────────┤ 2026-08-05
           │
2026-08-05 └─ Wave 2 complete, main merged, Wave 3 kickoff
```

---

**Document Version:** 1.0
**Last Updated:** 2026-07-21
**Author:** Jun Kawasaki (with Claude Code)
