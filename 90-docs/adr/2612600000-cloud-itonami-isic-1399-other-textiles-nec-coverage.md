# ADR-2612600000: cloud-itonami ISIC 1399 (other textiles n.e.c.) coverage

## Status

Accepted. `cloud-itonami-isic-1399` scaffolded fresh (no prior repo
existed for this ISIC class -- confirmed via `gh api
repos/cloud-itonami/cloud-itonami-isic-1399` returning 404 before this
work started) and promoted from `:spec` to `:implemented` in the
`kotoba-lang/industry` registry, following the verified fresh-scaffold
protocol (capable model + mandatory verification) established by 138+
consecutive prior agents in this fleet after an earlier 18-agent haiku
batch had a 61% defect rate.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carries `{:id "1399" :name "Manufacture of other textiles n.e.c." ...}`
at `:maturity :spec` (verified by reading the live GitHub blob
directly, via `grep -oE '\{:[^{}]*"1399"[^{}]*\}'` against a fresh
`git clone`, before any work began -- the `:name` field matched the
assigned ISIC class exactly, no mismatch). ISIC 1399 is a residual
"n.e.c." (not elsewhere classified) textile category -- e.g.
nonwovens, felt, tire-cord fabric, and other technical/industrial
textiles -- distinct from siblings 1392 (made-up textile articles,
already `:implemented`), 1393 (carpets and rugs), and 1394 (cordage,
rope, twine and netting, already `:implemented`). cloud-itonami
codifies each ISIC industry class as an autonomous governed "actor":
an LLM/advisor node sealed behind an independent Governor, a
langgraph-clj StateGraph run per operation, and an append-only audit
ledger.

This build's chosen reference/template is `cloud-itonami-isic-1394`
(Manufacture of cordage, rope, twine and netting), the closest
verified `13xx` textile-family sibling: both are back-office plant-
operations-coordination actors with real physical-safety-relevant
equipment, production-batch tracking against a quality/grade taxonomy,
and equipment maintenance scheduling with a permanent block on
directly operating that equipment. 1394's own module shape (advisor ⊣
governor ⊣ phase ⊣ store, full langgraph StateGraph via
`langgraph.graph`, `MemStore`-only backend, Phase 0->3 rollout with
exactly one phase-3 `:auto`-eligible op, `io.github.kotoba-lang/
langgraph` + `io.github.kotoba-lang/langchain` via `:local/root`, no
`-clj` suffix) is the fleet's current gold-standard pattern for this
class of actor, and was cloned + read in full before adaptation began.

Because ISIC 1399 is a residual "n.e.c." class with no single
canonical product, this build picks one concrete illustrative product
line, documented plainly in the child repo's own README: **nonwoven/
felt fabric manufacturing** (needle-punched, spunbond, or meltblown
forming lines bonded -- thermal, chemical, or mechanical -- into
nonwoven fabric or felt, including technical/industrial textiles such
as tire-cord fabric and geotextiles). Namespace prefix `nonwovenops`
(mirrors `cordageops`/`knittingops`, the fleet's per-actor
`Xops`-style short-domain-name convention; ADR-2607102200 addendum 14
forbids `-clj` suffixes, not domain-name prefixes).

## Decision

### Repository

`cloud-itonami/cloud-itonami-isic-1399`
(https://github.com/cloud-itonami/cloud-itonami-isic-1399), created
fresh under the `cloud-itonami` GitHub org (matching the org used by
every other recently `:implemented` `13xx` sibling --
`cloud-itonami-isic-1391`/`1392`/`1394` -- rather than the registry's
stale `gftdcojp/cloud-itonami-C1399` pre-scaffold placeholder URL,
which this ADR's registry edit corrects, matching the same
de-placeholdering every other `13xx` sibling promotion has performed).

### Domain adaptation from the `cordageops` (1394) template

Substituted nonwoven/felt-specific ground truth for cordage/rope/
twine/netting-specific ground truth, closely mirroring 1394's module
shape:
- `length-meters` -> `area-square-meters` (nonwoven/felt fabric output
  is conventionally measured by roll area, not length alone)
- fibre-twisting machine, braiding machine, winding line -> forming
  line, bonding line (the task's own domain description:
  "forming/bonding-line-equipment")
- `breaking-strength-kn` (a single quality field on 1394) is replaced
  by TWO independent quality-control fields, both explicitly named in
  the task spec: `tensile-strength-kn` (from a tensile-strength test)
  AND `basis-weight-gsm` (from a basis-weight test, grams per square
  meter -- a nonwoven/felt-specific measurement with no analog on
  1394). This ADDS a TWELFTH governor hard-check
  (`nonwovenops.governor/invalid-basis-weight-violations`,
  `nonwovenops.registry/basis-weight-valid?`) beyond 1394's own eleven
  -- 1394 added one beyond 1391's ten for its own single
  breaking-strength field; this build adds one more beyond 1394's own
  eleven for the second, domain-distinct quality field.
- `:flag-safety-concern` with `:equipment-safety`/`:quality-defect`
  concern-types carried over unchanged (task's own domain description
  matches 1394's verbatim: "equipment-safety/quality-defect concern")
- cordage/rope/twine/netting plant -> nonwoven/felt technical-textiles
  plant; "plant coordinator"/"plant supervisor" terminology unchanged

### Governor keyword uniqueness

`:itonami.blueprint/governor` is
`:nonwoven-technical-textiles-plant-operations-governor` -- grep-
verified UNIQUE fleet-wide via `gh search code
"nonwoven-technical-textiles-plant-operations-governor" --owner
cloud-itonami` (zero hits before this repo was created).

### HARD invariants (per task spec, always `:hold`, no override)

1. Plant/batch record must be independently verified/registered
   before any action (equipment before maintenance scheduling, batch
   before shipment coordination) -- elaborated into
   `equipment-not-verified-violations` / `batch-not-verified-
   violations` / `shipment-area-exceeded-violations` in
   `nonwovenops.governor`.
2. `:effect` must be `:propose` only -- `no-propose-effect-
   violations`, evaluated first, unconditional.
3. Any proposal touching forming/bonding-line-equipment control is a
   hard, permanent block -- `equipment-control-blocked-violations`
   (closed proposal-`:effect` allowlist) AND `line-operate-blocked-
   violations` (`:direct-operate? true` on a maintenance proposal),
   two independent layers, neither overridable by human approval nor
   ever a member of any `nonwovenops.phase` `:auto` set.
4. Closed op-allowlist enforced -- `unknown-op-violations`,
   `#{:log-production-batch :schedule-maintenance :flag-safety-concern
   :coordinate-shipment}` only.

Plus (mirroring 1394's own elaboration): `already-scheduled-
violations` (double-schedule guard), `invalid-grade-violations`,
`invalid-tensile-strength-violations`, `invalid-basis-weight-
violations` (this build's own second domain-specific addition,
described above), `invalid-defect-rate-violations` -- twelve HARD
checks total, one soft confidence/high-stakes gate.

### ESCALATE (always human sign-off)

`:flag-safety-concern` always escalates regardless of confidence
(`high-stakes` set + `nonwovenops.phase`'s permanent absence of this
op from every phase's `:auto` set -- two independent layers agree);
low confidence (< 0.6) also escalates. Only `:log-production-batch`
may auto-commit, and only at phase 3 when governor-clean (no physical/
financial risk in administrative batch logging).

## Verification

Actor repo, from a fresh clone (`orgs/cloud-itonami/cloud-itonami-isic-1399`
sibling to `orgs/kotoba-lang/langgraph` + `orgs/kotoba-lang/langchain`,
matching `deps.edn`'s `:local/root "../../kotoba-lang/..."` paths):

```
Ran 81 tests containing 220 assertions.
0 failures, 0 errors.
```

`clojure -M:lint`: `linting took 659ms, errors: 0, warnings: 0`.
`clojure -M:dev:run` demo narrative exercises the three happy-path
escalate/approve flows plus all twelve HARD-hold scenarios directly
(not-propose-effect, unknown-op, equipment-not-verified, batch-not-
verified, shipment-area-exceeded, direct-operate-blocked,
already-scheduled, invalid-grade, invalid-tensile-strength,
invalid-basis-weight, invalid-defect-rate), confirmed via raw stdout
inspection -- no exceptions, every governor rule fires with the
expected `:rule` keyword.

All source is `.cljc`, no JVM-only interop; the actor graph is invoked
exclusively via `langgraph.graph/run*`.

Registry (`kotoba-lang/industry`): `"1399"` promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` de-placeholdered from the stale
`gftdcojp/cloud-itonami-C1399` scheme to
`cloud-itonami/cloud-itonami-isic-1399` / `cloud-itonami-isic-1399`,
`:operating-states` normalized to the `intake/design/produce/inspect/
package/audit` shape used by sibling implemented 13xx actors; landed
via a GitHub Contents-API single-file PUT (sha-checked optimistic
concurrency, freshly re-fetched content immediately before the PUT
per this fleet's hot-contention discipline), exact-block edit only.
`test/kotoba/industry_test.clj`'s own dedicated `"1399"` testing block
and `:implemented` count assertion were landed the same way, against
a freshly re-fetched copy immediately before each PUT attempt. Post-
merge fresh-clone re-verification and the exact registry-suite test
output are recorded in this task's final report.

## Consequences

(+) ISIC 1399 now has a real, tested, governed actor implementation
consistent with the fleet's current gold-standard `Xops` pattern
(advisor ⊣ governor ⊣ phase ⊣ store StateGraph), not merely a registry
stub.

(+) A residual "n.e.c." ISIC class now has a concrete, documented
illustrative product line (nonwoven/felt fabric manufacturing) rather
than remaining an abstract label -- the README states this choice
plainly so a future contributor knows this is one illustration, not an
exhaustive definition of ISIC 1399.

(-) Still a simulation/proposal layer, not a real plant-operations
control system -- no integration with real forming/bonding-line
telemetry, batch-tracking, or freight-dispatch systems.

## References

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  entry `{:id "1399" ...}`
- `cloud-itonami/cloud-itonami-isic-1399` repo,
  `docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `cloud-itonami/cloud-itonami-isic-1394` (template/reference)
- ADR-2607062330 (kototama actor-host ABI contract, itonami pattern origin)
- ADR-2607102200 addendum 14 (repo naming -- no `-clj` suffix)
