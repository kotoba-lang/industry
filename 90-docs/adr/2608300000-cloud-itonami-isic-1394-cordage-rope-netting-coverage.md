# ADR-2608300000: cloud-itonami ISIC 1394 (cordage, rope, twine and netting) coverage

## Status

Accepted. `cloud-itonami-isic-1394` scaffolded fresh (no prior repo
existed for this ISIC class -- confirmed via `gh api
repos/gftdcojp/cloud-itonami-C1394` and
`repos/cloud-itonami/cloud-itonami-isic-1394` both 404 before this
work started) and promoted from `:spec` to `:implemented` in the
`kotoba-lang/industry` registry, following the verified fresh-scaffold
protocol (capable model + mandatory verification) established by 120+
consecutive prior agents in this fleet after an earlier 18-agent haiku
batch had a 61% defect rate.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries `{:id "1394" :name "Manufacture of cordage, rope, twine and
netting" ...}` at `:maturity :spec` (verified by reading the live
GitHub blob directly, not the CDN-cached `raw.githubusercontent.com`
mirror, before any work began -- the `:name` field matched the
assigned ISIC class exactly, no mismatch). cloud-itonami codifies each
ISIC industry class as an autonomous governed "actor": an LLM/advisor
node sealed behind an independent Governor, a langgraph-clj StateGraph
run per operation, and an append-only audit ledger.

This build's chosen reference/template is `cloud-itonami-isic-1391`
(Manufacture of knitted and crocheted fabrics), the most recently
created `:implemented` `13xx` textile-family actor in the registry at
the time of this work (created 2026-07-15, after the also-`:implemented`
`cloud-itonami-isic-1312` created 2026-07-14) and confirmed to use the
current, corrected dependency-naming convention:
`io.github.kotoba-lang/langgraph` + `io.github.kotoba-lang/langchain`
via `:local/root` (no `-clj` suffix, no stale
`com-junkawasaki/langgraph-clj` git-coordinate reference -- that repo
is now a GitHub rename-redirect to `kotoba-lang/langgraph`, confirmed
via `gh api repos/com-junkawasaki/langgraph-clj` returning the same
`created_at`/`pushed_at` as `repos/kotoba-lang/langgraph`). 1391's own
op set (`:log-production-batch` / `:schedule-maintenance` /
`:flag-safety-concern` / `:coordinate-shipment`, all `:effect
:propose`) is IDENTICAL to this task's assigned op set, and its module
shape (advisor ⊣ governor ⊣ phase ⊣ store, full langgraph StateGraph
via `langgraph.graph`, `MemStore`-only backend, Phase 0->3 rollout with
exactly one phase-3 `:auto`-eligible op) is the fleet's current
gold-standard pattern for this class of "plant operations
coordination" actor -- richer than the earlier `cloud-itonami-isic-1312`
(Weaving of textiles) template, which predates the StateGraph
integration and the corrected dependency naming.

## Decision

### Repository

`cloud-itonami/cloud-itonami-isic-1394`
(https://github.com/cloud-itonami/cloud-itonami-isic-1394), created
fresh under the `cloud-itonami` GitHub org (matching the org used by
every other recently `:implemented` `13xx` sibling --
`cloud-itonami-isic-1311`/`1312`/`1391` -- rather than the registry's
stale `gftdcojp/cloud-itonami-C1394` pre-scaffold placeholder URL,
which this ADR's registry edit corrects). Namespace prefix
`cordageops` (mirrors `knittingops`/`weaving`, the fleet's per-actor
`Xops`-style short-domain-name convention; ADR-2607102200 addendum 14
forbids `-clj` suffixes, not domain-name prefixes).

### Domain adaptation from the `knittingops` (1391) template

Substituted knitting/crocheting-specific ground truth for cordage/
rope/twine/netting-specific ground truth, closely mirroring 1391's
module shape:
- `volume-yards` -> `length-meters` (cordage/rope/netting output is
  measured in length, not weight or area)
- circular/flat-knitting machine, crocheting line -> fibre-twisting
  machine, braiding machine, winding line (the task's own domain
  description: "fibre-twisting/braiding/winding lines")
- `fabric-weight-gsm` (knitting-specific finish-quality field)
  dropped; ADDED `breaking-strength-kn`, a breaking-strength-test
  reading in kilonewtons -- the task's own explicit domain requirement
  ("output-quality (breaking-strength test) data logging"). This adds
  an ELEVENTH governor hard-check
  (`cordageops.governor/invalid-breaking-strength-violations`,
  `cordageops.registry/breaking-strength-valid?`) beyond 1391's own
  ten, validating the reading is a strictly positive, physically
  plausible kilonewton value (> 0, <= 20000.0 -- generous enough to
  cover light twine through heavy synthetic-fibre marine mooring rope,
  informed by the ISO 2307 fibre-rope breaking-strength test standard
  cited informationally in the child-repo ADR).
- `:flag-fabric-quality`/materials-safety concern-types ->
  `:flag-safety-concern` with `:equipment-safety`/`:quality-defect`
  concern-types (task's own domain description: "equipment-safety/
  quality-defect concern")
- knitting/crocheting-mill -> cordage/rope/twine/netting plant;
  "mill coordinator"/"mill supervisor" -> "plant coordinator"/"plant
  supervisor" throughout

### Governor keyword uniqueness

`:itonami.blueprint/governor` is
`:cordage-netting-plant-operations-governor` -- grep-verified UNIQUE
fleet-wide via `gh search code
"cordage-netting-plant-operations-governor" --owner cloud-itonami`
(zero hits before this repo was created).

### HARD invariants (per task spec, always `:hold`, no override)

1. Plant/batch record must be independently verified/registered
   before any action (equipment before maintenance scheduling, batch
   before shipment coordination) -- elaborated into
   `equipment-not-verified-violations` / `batch-not-verified-
   violations` / `shipment-volume-exceeded-violations` in
   `cordageops.governor`.
2. `:effect` must be `:propose` only -- `no-propose-effect-
   violations`, evaluated first, unconditional.
3. Any proposal touching twisting/braiding-line-equipment control is a
   hard, permanent block -- `equipment-control-blocked-violations`
   (closed proposal-`:effect` allowlist) AND `line-operate-blocked-
   violations` (`:direct-operate? true` on a maintenance proposal),
   two independent layers, neither overridable by human approval nor
   ever a member of any `cordageops.phase` `:auto` set.
4. Closed op-allowlist enforced -- `unknown-op-violations`,
   `#{:log-production-batch :schedule-maintenance :flag-safety-concern
   :coordinate-shipment}` only.

Plus (mirroring 1391's own elaboration): `already-scheduled-
violations` (double-schedule guard), `invalid-grade-violations`,
`invalid-breaking-strength-violations` (this build's own domain-
specific addition), `invalid-defect-rate-violations` -- eleven HARD
checks total, one soft confidence/high-stakes gate.

### ESCALATE (always human sign-off)

`:flag-safety-concern` always escalates regardless of confidence
(`high-stakes` set + `cordageops.phase`'s permanent absence of this op
from every phase's `:auto` set -- two independent layers agree); low
confidence (< 0.6) also escalates. Only `:log-production-batch` may
auto-commit, and only at phase 3 when governor-clean (no physical/
financial risk in administrative batch logging).

## Verification

Actor repo, from a fresh clone (`orgs/cloud-itonami/cloud-itonami-isic-1394`
sibling to `orgs/kotoba-lang/langgraph` + `orgs/kotoba-lang/langchain`,
matching `deps.edn`'s `:local/root "../../kotoba-lang/..."` paths):

```
Ran 76 tests containing 207 assertions.
0 failures, 0 errors.
```

`clojure -M:lint`: `linting took 792ms, errors: 0, warnings: 0`.
`clojure -M:dev:run` demo narrative exercises the three happy-path
escalate/approve flows plus all eleven HARD-hold scenarios directly
(not-propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-volume-exceeded, direct-operate-blocked,
already-scheduled, invalid-grade, invalid-breaking-strength,
invalid-defect-rate), confirmed via raw stdout inspection -- no
exceptions, every governor rule fires with the expected `:rule`
keyword.

All source is `.cljc`, no JVM-only interop; the actor graph is invoked
exclusively via `langgraph.graph/run*`.

Registry (`kotoba-lang/industry`): `"1394"` promoted `:spec` ->
`:implemented`, `:repo` corrected to
`https://github.com/cloud-itonami/cloud-itonami-isic-1394`,
`:business-id` corrected to `cloud-itonami-isic-1394` (dropping the
stale `cloud-itonami-C1394` scheme), landed via server-side merge
(`gh api repos/kotoba-lang/industry/merges`); post-merge fresh-clone
re-verification and the exact registry-suite test output are recorded
in the child repo's own commit history and this task's final report.

## Consequences

(+) ISIC 1394 now has a real, tested, governed actor implementation
consistent with the fleet's current gold-standard `Xops` pattern
(advisor ⊣ governor ⊣ phase ⊣ store StateGraph), not merely a registry
stub.

(+) The registry's stale pre-scaffold placeholder URL
(`gftdcojp/cloud-itonami-C1394`) and business-id scheme
(`cloud-itonami-C1394`) are corrected to the current `cloud-itonami`
org / `cloud-itonami-isic-1394` naming convention as part of this same
edit, consistent with how `cloud-itonami-isic-1311`/`1312`/`1391` were
each corrected in their own registry promotions.

(-) Still a simulation/proposal layer, not a real plant-operations
control system -- no integration with real twisting/braiding/winding-
line telemetry, batch-tracking, or freight-dispatch systems.

## References

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  entry `{:id "1394" ...}`
- `cloud-itonami/cloud-itonami-isic-1394` repo,
  `docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `cloud-itonami/cloud-itonami-isic-1391` (template/reference)
- ADR-2607062330 (kototama actor-host ABI contract, itonami pattern origin)
- ADR-2607102200 addendum 14 (repo naming -- no `-clj` suffix)
