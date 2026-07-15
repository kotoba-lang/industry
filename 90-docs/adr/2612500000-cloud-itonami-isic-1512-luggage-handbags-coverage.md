# ADR-2612500000: cloud-itonami-isic-1512 (Manufacture of luggage, handbags and the like, saddlery and harness) plant-operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-1420 (Manufacture of articles of fur --
the reference module shape this actor mirrors, independently re-read in
full before use), the `kotoba-lang/industry` registry's `"1512"` catalog
entry (previously `:spec` with a stale placeholder
`gftdcojp/cloud-itonami-C1512` repo link that was never populated, plus a
pre-existing, unrelated seed-data truncation bug in its `:name` field)

## Context

`kotoba-lang/industry`'s registry carried a `"1512"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-C1512` -- a
placeholder repo link with no actual implementation behind it (confirmed
404 via `gh api` for `cloud-itonami/cloud-itonami-isic-1512` itself before
any work began). This is part of an ongoing careful, smaller-batch rollout
(one ISIC class per agent, a capable model, mandatory verification) after
a prior 18-agent haiku batch produced a 61% defect rate on other ISIC
classes; 138+ consecutive agents on this stricter protocol had all
succeeded since. This ADR covers ISIC 1512 only.

Before any work began, the registry entry's identity was independently
verified against a fresh clone of `kotoba-lang/industry`, per this
fleet's caution against ID/name mismatches (prior agents in this fleet
had mislabeled other ISIC classes). The live `:name` field for `"1512"`
was found truncated with a literal `"..."` -- `"Manufacture of luggage,
handbags and the like, saddlery and..."` -- a known, pre-existing
seed-data bug affecting roughly 10% of registry entries, unrelated to
this task. The correct full ISIC-08 name for class 1512, **"Manufacture
of luggage, handbags and the like, saddlery and harness"**, was confirmed
against the truncated prefix (an exact match up to the truncation point,
confirming the correct entry) and de-truncated as part of this ADR's
registry edit.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-1512` as a leather-goods PLANT
OPERATIONS COORDINATION actor (not direct cutting/stitching-line control
authority), mirroring `cloud-itonami-isic-1420`'s verified module shape
(`facts`/`registry`/`store`/`governor`/`phase`/`advisor`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/
CONTRIBUTING/SECURITY) with fresh, leather-goods-specific domain logic
under the `luggage` namespace. ISIC 1512 covers cutting/stitching/
assembly lines that turn leather (or leather-substitute synthetic)
material into finished luggage, handbags, wallets, belts, saddles and
harness goods; tanning and dressing of leather is a distinct upstream
ISIC class (1511) and is explicitly out of scope here, matching 1420's
own upstream-dressing/downstream-manufacturing scope split.

1. **`luggage.facts`** -- per-jurisdiction (USA/Italy/Canada) leather-
   goods compliance catalog with real official spec-basis citations: FTC
   Guides for Select Leather and Imitation Leather Products (16 CFR Part
   24) for US leather-goods labeling; the Endangered Species Act/CITES
   implementing regulations (50 CFR Part 23) for exotic-skin (alligator/
   crocodile/python) sourcing; Italy's Legge 8 aprile 2010, n. 55 ("Made
   in Italy" labeling for `pelletteria`/leather-goods products) and the
   REACH Regulation (EC) No 1907/2006, Annex XVII, Entry 47 (Chromium VI
   content restriction in leather articles that contact skin) -- both
   genuinely specific to leather-goods manufacturing, distinct from
   1420's fur-specific catalog; Council Regulation (EC) No 338/97 for
   wildlife-trade protection; Canada's Consumer Packaging and Labelling
   Act (R.S.C. 1985, c. C-38) for general consumer-product labelling
   (chosen over the Textile Labelling Act 1420 cited for fur, since pure
   leather goods are not textile-fibre products); labor-standards
   citations (FLSA/OSHA, Decreto Legislativo 81/2008, Canada Labour Code)
   reused verbatim from 1420 where the underlying law is domain-agnostic.
2. **`luggage.store`** -- in-memory plant/production-batch/shipment/
   maintenance-log store with a verified plant (`plant-001`, Italy), a
   verified cowhide batch and an unverified exotic-skin (python) batch,
   mirroring 1420's shape (fur pelts -> leather/synthetic hides).
3. **`luggage.registry`** -- the closed `allowed-ops` four-op allowlist
   (`:proposal/log-production-batch`, `:proposal/schedule-maintenance`,
   `:proposal/flag-safety-concern`, `:actuation/coordinate-shipment`, all
   `:effect :propose`) plus hard-invariant helpers and proposal draft
   constructors, identical in shape to 1420's own closed allowlist.
4. **`luggage.governor`** -- 6 HARD checks (spec-basis, effect-not-
   propose, op-not-allowlisted, plant-not-verified, batch-not-verified,
   process-control-forbidden) plus 1 unconditional escalation (safety-
   concern) and 1 soft confidence/actuation gate, mirroring 1420's
   governor module-for-module. `process-control-keywords` covers both
   generic cutting/stitching-line terms (speed/tension/needle/stitch/
   cutting/sewing/stitching/blade/thread/etc.) and leather-goods-specific
   process terms (skiving an edge, positioning a piece on a clicking
   press, riveting, punching a stitch hole) -- the leather trade's own
   vocabulary, playing the same role as 1420's fur-specific
   nailing/blocking/stretching terms. The shipment->batch indirection
   resolver (`resolve-batch-id`) is reused verbatim from 1420, since a
   shipment-coordination proposal's `:subject` is a shipment ID, not a
   batch ID directly.
5. **`luggage.phase`** -- `:spec -> :design -> :produce -> :inspect ->
   :package -> :audit`, the registry's own already-registered
   `:operating-states` for `"1512"`, confirmed meaningful and adopted
   verbatim (identical to 1420's own sequence).
6. **`deps.edn` / `blueprint.edn` / docs** -- mirror
   `cloud-itonami-isic-1420`'s shape (`:test`/`:lint`/`:run`/`:dev`
   aliases, `itonami.blueprint/*` metadata, scope/design/testing README
   sections). `:itonami.blueprint/robotics` is `true`, matching 1420
   (a robotics-assisted cutting/stitching station may perform the
   physical work under this actor's coordination, gated by the
   governor).

### Portability

`luggage.governor`'s process-control-keyword scan uses
`clojure.string/lower-case` rather than the reference 1420 module's
`.toLowerCase` Java-interop call -- both are cross-host-compatible in
practice (JS strings also expose `toLowerCase`), but `clojure.string/
lower-case` is the more recent, stricter fleet convention (established by
`cloud-itonami-isic-1430`'s own ADR note) and this task's explicit
cljs-first/no-JVM-interop mandate. All other source is `.cljc` with no
JVM-only interop.

### What this actor does NOT do

Cutting, skiving, stitching and assembling leather/synthetic goods remain
the exclusive authority of the licensed plant production engineer or
robotics-assisted cutting/stitching station, permanently, with no actor or
human-approval override path -- enforced by the closed operation
allowlist (which contains no direct equipment-control op at all) plus the
`process-control-forbidden` keyword-scan hard block, not just documented.
This actor also does not approve raw-material or tanning quality (the
upstream tannery's/material supplier's responsibility) and does not tan
or dress leather (ISIC 1511, a distinct upstream class).

## Verification

- `cloud-itonami-isic-1512`: `clojure -M:test` -- raw final line: `Ran 34
  tests containing 108 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors (37 `deftest`-docstring warnings, an
  artifact of `deftest` not being recognized by clj-kondo as accepting a
  docstring the way `defn` does; the 1420 reference repo has the
  identical 37-warning/0-error signature, confirmed side-by-side).
- `clojure -M:dev:run` -- all six demo scenarios (clean batch log,
  always-escalating safety concern, high-stakes clean shipment,
  maintenance scheduling, unallowlisted-op block, non-`:propose`-effect
  block) produced the expected APPROVED/ESCALATE/HOLD outcomes.
- All source under `src/`/`test/` is `.cljc` with no JVM-only interop.
- Repo created fresh (`gh repo create` + push), initial commit
  `9d6b8a95a601ab03e045a535b881755a8e015c4b` on
  `cloud-itonami-isic-1512`'s `main` (no prior history).
- `kotoba-lang/industry` registry `"1512"` entry updated in place (exact-
  text edit of the literal `{:id "1512" ...}` block only, no other
  entry's block touched): `:name` de-truncated from `"Manufacture of
  luggage, handbags and the like, saddlery and..."` to the correct full
  ISIC-08 name `"Manufacture of luggage, handbags and the like, saddlery
  and harness"`; `:repo`/`:business-id` corrected from the never-
  populated `gftdcojp/cloud-itonami-C1512` placeholder to
  `cloud-itonami/cloud-itonami-isic-1512` / `cloud-itonami-isic-1512`;
  `:maturity` `:spec` -> `:implemented`; `:required-technologies`/
  `:optional-technologies`/`:operating-states` left as already-
  registered.
- `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before the edit, not assumed --
  this is an extremely hot, high-concurrency shared file).
- Full `kotoba-lang/industry` suite re-run green after the registry edit
  (fresh clone, plus a fresh `kotoba-lang/technology` sibling clone for
  `deps.edn` resolution).
- Final post-merge re-verification (brand-new scratch directory, fresh
  `origin/main` clone of both `cloud-itonami-isic-1512` and
  `kotoba-lang/industry`, plus a fresh `kotoba-lang/technology` sibling):
  `clojure -M:test` re-run green in the actor repo; the registry's
  `"1512"` entry confirmed `:maturity :implemented` with the corrected/
  de-truncated `:name` and corrected `:repo`/`:business-id`, and a sample
  of 2-3 unrelated entries confirmed untouched. Checked for file-wide
  UTF-8 mojibake in `registry.edn` (none found).

## Consequences

(+) `cloud-itonami-isic-1512` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"1512"` entry promoted to `:maturity
:implemented`, and its long-standing truncated `:name` field is fixed to
the correct full ISIC-08 name -- a narrow, targeted fix scoped to this one
entry's `:name` only, not a wholesale pass over the other ~10% of
registry entries known to share this seed-data bug.
(+) `luggage.facts`'s Italy catalog (Legge 55/2010 "Made in Italy"
`pelletteria` labeling + REACH Annex XVII Entry 47 Chromium VI
restriction) demonstrates two compliance citations genuinely specific to
leather-goods manufacturing that have no analog in 1420's fur catalog --
a reusable pattern for any future ISIC class in this fleet's leather/
apparel cluster that needs a REACH-style chemical-content restriction or
a "Made in Italy" full-production-chain labeling requirement.
(+) `luggage.governor`'s leather-goods-specific process-control keywords
(skiving/clicking/riveting/punching) demonstrate the same domain-
vocabulary-substitution pattern 1420 established with its fur-specific
nailing/blocking/stretching terms.
(-) None known -- this actor's scope (plant operations coordination, not
equipment control) is intentionally narrow, matching the fleet-wide
cloud-itonami actor pattern.
