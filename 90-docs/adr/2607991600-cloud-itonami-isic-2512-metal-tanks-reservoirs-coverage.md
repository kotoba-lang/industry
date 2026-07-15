# ADR-2607991600: cloud-itonami ISIC 2512 coverage — Manufacture of tanks, reservoirs and containers of metal

## Status

Accepted.

## Context

`kotoba-lang/industry`'s registry carries ISIC Rev.4/5 class `"2512"`
(`:name "Manufacture of tanks, reservoirs and containers of metal"`)
at `:maturity :spec` with a legacy `gftdcojp/cloud-itonami-C2512`
placeholder `:repo`. This ADR records promoting it to `:implemented`
with a real, tested, fresh-scaffold `cloud-itonami` actor repo, as part
of the ongoing careful, smaller-batch cloud-itonami ISIC-coverage
rollout (capable model + mandatory synchronous verification per class,
following the prior 18-agent haiku batch's 61% defect rate; 90+
consecutive agents on the stricter protocol have succeeded before this
one).

Confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-2512`
(404) before scaffold: no repo existed for this class under the
`cloud-itonami` org. The registry's own live `:name` for `"2512"` was
independently re-read via the GitHub Contents API (never
`raw.githubusercontent.com`, which can be CDN-stale) and confirmed
verbatim as "Manufacture of tanks, reservoirs and containers of metal"
before any code was written.

## Decision

Scaffolded `cloud-itonami/cloud-itonami-isic-2512` as a governed actor
mirroring the `cloud-itonami-isic-2599` (Manufacture of other
fabricated metal products n.e.c.) reference architecture — the closest
architectural sibling in the same ISIC 251-259 fabricated-metal-
products group, itself independently re-cloned and read in full before
this build began.

**MetalTankAdvisor ⊣ Metal Tank Plant Operations Governor**, a
langgraph-clj StateGraph actor with an append-only audit ledger,
coordinating back-office plant operations for a metal-tank/reservoir/
container fabrication shop (welding line -> pressure-testing line ->
forming line) producing storage tanks, reservoirs, metal containers,
gas cylinders, process vessels, and central-heating boilers.

**This is a plant OPERATIONS COORDINATION actor, NOT a welding-line
control authority, and NOT a pressure-vessel-certification authority**
(e.g. it never issues, claims, or proposes an ASME code stamp — that
remains exclusively a qualified third-party inspector/certification
body's authority).

### Four propose-only ops (closed allowlist)

- `:log-production-batch` — welding/fabrication batch, output-quality data logging
- `:schedule-maintenance` — welding-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a weld-integrity/pressure-test-failure/materials-safety concern; ALWAYS escalates
- `:coordinate-shipment` — outbound product shipment coordination

All four are `:effect :propose` only.

### Governor rules

Four HARD invariants (always `:hold`, no override), elaborated into
eleven concrete checks in `metaltankmfg.governor`:
1. Plant/batch record must be independently verified/registered before any action (equipment before maintenance scheduling, batch before shipment coordination); a shipment's weight must independently recompute within the batch's own logged weight
2. `:effect` must be `:propose` only
3. Any proposal touching welding-line-equipment control (`:actuate-welding-line? true`), OR a pressure-vessel-certification-authority decision such as an ASME code stamp (`:issue-code-stamp? true`), is a hard, permanent block — two independently-checked scope boundaries, deliberately not folded into one
4. Closed op-allowlist enforced

ESCALATE (always human sign-off, human may approve): `:flag-safety-
concern` always escalates; low-confidence proposals.

`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`
are NEVER in any Phase 0->3 rollout phase's `:auto` set (permanent);
only `:log-production-batch` (no physical/financial risk) may
auto-commit at phase 3 when governor-clean — the same pattern every
prior fleet actor establishes.

## Verification

Actor repo (`https://github.com/cloud-itonami/cloud-itonami-isic-2512`,
commit `0af557056603dca4b90b5c1db8e5b25ca32deee7` on `main`):

```
Ran 72 tests containing 199 assertions.
0 failures, 0 errors.
```

Re-verified from an INDEPENDENT fresh clone (separate temp dir, fresh
`kotoba-lang/langgraph` + `kotoba-lang/langchain` siblings) after the
initial push — same result:

```
Ran 72 tests containing 199 assertions.
0 failures, 0 errors.
```

`clojure -M:lint` (clj-kondo): `linting took 755ms, errors: 0,
warnings: 0`. `clojure -M:dev:run` demo narrative exercises proposal
submission, escalation, and every HARD-hold scenario directly
(not-propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-weight-exceeded, welding-line-actuate-blocked,
code-stamp-authority-blocked, already-scheduled, invalid-product-
category, invalid-defect-rate) — confirmed in the raw demo output's
audit-ledger `:basis` entries.

All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
JVM-only interop; the actor graph is invoked exclusively via
`langgraph.graph/run*`.

`kotoba-lang/industry` registry: `"2512"` promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` updated to the real repo, exact
in-place edit of only the `{:id "2512" ...}` block (verified via git
diff before push — no other entry touched). `industry_test.clj`'s
`:implemented` count assertion bumped to the true recomputed count from
`kotoba.industry/maturity-summary` on the live post-edit file, full
suite re-run green before push, and again after the registry PR landed
on `origin/main` (see registry repo's own commit history for the exact
`Ran N tests ...` outputs of both runs).

## Consequences

(+) ISIC 2512 now has a documented, governed, auditable plant-
operations coordination layer with a clear, code-enforced boundary
against both equipment-control authority and pressure-vessel-
certification authority — two independently-checked permanent blocks,
matching the domain's real dual risk (physical equipment actuation
AND certification-authority impersonation).

(+) Architecture is proven-compatible with the fleet's established
pattern (mirrors `cloud-itonami-isic-2599` closely), reducing review
burden and regression risk for future ISIC 251-259 siblings.

(-) Still a simulation/proposal layer — no integration with real plant-
management databases, telemetry, or third-party certification
registries.

(-) `cloud-itonami-isic-2513` (Manufacture of steam generators) remains
`:spec` in the registry as of this ADR — a distinct, not-yet-built
sibling in the same immediate ISIC neighborhood.
