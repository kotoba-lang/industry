# ADR-2607999700: cloud-itonami ISIC 1820 (Reproduction of recorded media) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607156500 (cloud-itonami-isic-2710), the child repo's own `docs/adr/0001-architecture.md`

## Status

Accepted. `cloud-itonami-isic-1820` scaffolded, implemented, tested
green, and pushed to `main` at
https://github.com/cloud-itonami/cloud-itonami-isic-1820. Part of the
ongoing careful, smaller-batch cloud-itonami ISIC-coverage rollout
(capable model + mandatory verification per agent, following the
prior 18-agent haiku batch's 61% defect rate).

## Context

`kotoba-lang/industry`'s `registry.edn` carried ISIC class 1820
("Reproduction of recorded media") as `:maturity :spec` with a
placeholder `:repo`/`:business-id` pointing at
`https://github.com/gftdcojp/cloud-itonami-C1820` /
`cloud-itonami-C1820` -- a repo that does not exist (`gh api
repos/gftdcojp/cloud-itonami-C1820` -> 404) and a naming convention
inconsistent with the rest of the fleet (siblings use
`cloud-itonami/cloud-itonami-isic-<NNNN>` /
`cloud-itonami-isic-<NNNN>`, e.g. `cloud-itonami-isic-2680`,
`cloud-itonami-isic-3512`, `cloud-itonami-isic-6310`). This ADR records
the fresh scaffold that promotes it to `:implemented` under the
fleet's standard naming.

ISIC 1820 covers mass-duplication/reproduction of pre-recorded content
(disc-replication lines, software-duplication lines, print-and-package
lines) onto already-manufactured blank media from an authorized
master -- distinct from ISIC 2680 (manufacture of the blank
magnetic/optical media substrate itself, already `:implemented` at
`cloud-itonami-isic-2680`, 77 tests / 211 assertions). This build
mirrors `cloud-itonami-isic-2680`'s governed-actor architecture
closely (same four-op shape, same two-entity verified/registered gate
structure, same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern) but adapts the domain to the actual ISIC 1820
economic activity and its distinct authority boundary: this actor is
never a copyright-licensing authority (it never authorizes reproducing
copyrighted content -- that authorization must already exist before
any batch is even logged), whereas 2680's equivalent permanent block
guards a rights-holder source-identification (IFPI SID) authorization
*mark*, not the underlying reproduction authorization itself. Full
domain adaptation rationale is recorded in the child repo's own
`docs/adr/0001-architecture.md`.

## Decision

Scaffold `cloud-itonami-isic-1820` as a governed actor
(`MediaReproductionAdvisor ⊣ Media Reproduction Plant Operations
Governor`, langgraph-clj StateGraph, append-only audit ledger),
mirroring the `cloud-itonami-isic-2680` reference implementation:

- **Ops** (closed allowlist, all `:effect :propose`):
  `:log-production-batch` (duplication/replication batch, output-
  quality data logging), `:schedule-maintenance` (duplication-line-
  equipment maintenance scheduling proposal), `:flag-safety-concern`
  (equipment-safety/quality-defect concern, ALWAYS escalates),
  `:coordinate-shipment` (outbound reproduced-media shipment
  coordination).
- **HARD invariants** (always `:hold`, no override, twelve concrete
  governor checks): plant/batch record must be independently
  verified/registered before any action; the caller's own request
  `:effect` must be `:propose`; `:op` must be in the closed
  four-op allowlist; the proposal's own `:effect` must be one of the
  four propose-shaped effects (no direct duplication-line-equipment
  control); directly actuating duplication-line equipment
  (`:actuate-equipment? true`) is a PERMANENT, unconditional block;
  self-issuing a copyright reproduction-license/clearance
  authorization (`:issue-reproduction-license? true`, any op) is a
  PERMANENT, unconditional block; a shipment may not push a batch's
  own recorded shipped quantity past its own logged production
  quantity (independently recomputed); no double-scheduling the same
  maintenance record; no fabricated content-type, disc-thickness-mm,
  or defect-rate-percent value on a production-batch patch.
- **ESCALATE** (always human sign-off): `:flag-safety-concern` always
  escalates regardless of confidence; low-confidence proposals.
- **Phase 0->3 rollout**: `:schedule-maintenance`/
  `:flag-safety-concern`/`:coordinate-shipment` are NEVER in any
  phase's `:auto` set; only `:log-production-batch` (no physical/
  financial risk) may auto-commit at phase 3 when governor-clean.
- Full module set (`advisor.cljc`/`governor.cljc`/`operation.cljc`/
  `phase.cljc`/`registry.cljc`/`sim.cljc`/`store.cljc`), all `.cljc`
  (cljs-first, no JVM-only interop), `deps.edn` pinning
  `io.github.kotoba-lang/langgraph` and `io.github.kotoba-lang/
  langchain` via top-level `:local/root` (bare `clojure -M:test`
  resolves offline in-monorepo), `blueprint.edn`, LICENSE
  (AGPL-3.0-or-later), README, GOVERNANCE.md, CODE_OF_CONDUCT.md,
  CONTRIBUTING.md, SECURITY.md, `docs/adr/0001-architecture.md`.
- Repo: https://github.com/cloud-itonami/cloud-itonami-isic-1820
  (main, commit `2c9b62d360131fdc5ac3dba7c8fa39a71f3fe081`).

Registry `kotoba-lang/industry`'s `{:id "1820" ...}` entry is updated
in place (exact-text block edit only, 3 lines changed: `:repo`/
`:business-id`/`:maturity`, verified via diff against the pre-edit
fetch) from `:maturity :spec` to `:maturity :implemented`, `:repo`
corrected to `https://github.com/cloud-itonami/cloud-itonami-isic-1820`,
`:business-id` corrected to `cloud-itonami-isic-1820` -- landed via a
GitHub Contents API single-file PUT (sha-checked optimistic
concurrency, succeeded on the first attempt) at commit
`359e344f874f0529ceff6dfac04b46b26a201d80`. `test/kotoba/
industry_test.clj`'s own corroboration-testing catch-up (adds a
`(testing "cloud-itonami-isic-1820 ...")` block asserting `:implemented`
and bumps the live `:implemented` count assertion) landed via a
sibling-branch + GitHub API server-side merge (retried across a fresh
branch off the latest `origin/main` on each `409 Merge conflict`, per
this fleet's highly concurrent landing discipline -- no `git rebase`,
no force-push) at merge commit
`c8f8745d6517592c8a13b7a6d0a56e07083a0e02`.

## Consequences

(+) ISIC 1820 now has a real, tested, governed coordination actor
consistent with the fleet's `cloud-itonami-isic-*` naming and
architecture conventions, replacing a `:spec`-only placeholder entry
that pointed at a non-existent repo under an inconsistent name.

(+) The domain's two-sided authority boundary (no direct duplication-
line-equipment control, no self-issued copyright reproduction
license) is enforced by independent, unconditional, permanent governor
checks -- not merely documented policy.

(-) Still a simulation/proposal layer (`MemStore` only, mock advisor
by default); no integration with real plant-management databases,
freight dispatch, or rights-management/licensing-body APIs.

## Verification

- `cloud-itonami-isic-1820`: `clojure -M:test` from the pushed repo --
  raw output:

  ```
  Ran 77 tests containing 211 assertions.
  0 failures, 0 errors.
  ```

  Re-verified identically from an independent fresh clone (new temp
  dir, fresh `kotoba-lang/langgraph`/`kotoba-lang/langchain` sibling
  clones) after the push landed on `origin/main`.
- `clojure -M:lint`: `linting took 805ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  copyright-license-authority-blocked, already-scheduled,
  invalid-product-type, invalid-disc-thickness-mm,
  invalid-defect-rate) -- all fire with the expected `:basis` rule
  keywords.
- `:itonami.blueprint/governor` keyword
  `:media-reproduction-plant-operations-governor` grep-verified UNIQUE
  fleet-wide before this repo was created (`gh search code
  "media-reproduction-plant-operations-governor" --owner
  cloud-itonami` -> zero hits).
- Push landed on `origin/main`: `git merge-base --is-ancestor
  2c9b62d360131fdc5ac3dba7c8fa39a71f3fe081 origin/main` confirmed true
  against `cloud-itonami/cloud-itonami-isic-1820`.
- `kotoba-lang/industry` re-verified from a brand-new fresh clone
  (plus a fresh `../technology` sibling clone) after both registry
  commits landed on `origin/main` (tip
  `94864a6...` at re-verification time, one further concurrent
  sibling count-bump beyond this ADR's own two commits): `clojure
  -M:test` raw output:

  ```
  Ran 15 tests containing 998 assertions.
  0 failures, 0 errors.
  ```

  and zero UTF-8 mojibake (`grep -c "â"
  resources/kotoba/industry/registry.edn` = 0). The `"1820"` entry
  re-read via `(kotoba.industry/get-industry "1820")` confirmed
  `:maturity :implemented`, `:repo
  "https://github.com/cloud-itonami/cloud-itonami-isic-1820"`,
  `:business-id "cloud-itonami-isic-1820"` survived intact.
- This superproject ADR pair landed on `com-junkawasaki/root`
  `origin/main` via a sibling worktree + server-side merge (no direct
  edits to the shared checkout, no force-push, no rebase).
