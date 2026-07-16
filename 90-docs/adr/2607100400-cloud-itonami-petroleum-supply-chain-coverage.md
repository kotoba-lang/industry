# ADR-2607100400: cloud-itonami petroleum supply-chain actor fleet (ISIC 0610/0620/1920/4950/5210/5020/4671/4730) -- 8 new verticals covering upstream -> downstream

## Status

Accepted, 2026-07-09.

## Related

- ADR-2607011000 (cloud-itonami fleet standalone -- repos.edn/west.yml UNCHANGED)
- ADR-2607012100 (cloud-itonami org, public/AGPL, direct `gh repo create`)
- ADR-2607100300 (9900 -- fleet-wide `:blueprint` tier reached zero; these 8 are NEW ISICs beyond the prior 98 implemented)
- langgraph ADR-0001 (governed-actor architecture, referenced via each actor's own `docs/adr/0001-architecture.md`)

## Context

A pre-flight coverage scan of the cloud-itonami fleet found petroleum/energy supply-chain coverage at **zero**: only `isic-3512` (renewables transmission/distribution) and `isic-3600` (water) were `:implemented`. Every core petroleum ISIC -- crude extraction (0610), natural-gas extraction (0620), refining (1920), pipeline transport (4950), terminal/depot storage (5210), marine tanker (5020), fuel wholesale (4671), forecourt retail (4730) -- was absent fleet-wide. The fleet actor pattern (sealed `<Domain>Advisor` + independent `<Domain> Governor` + append-only audit ledger on the langgraph-clj StateGraph, established across 98 prior actors) was directly applicable; the gap was domain content only.

## Decision

Build **8 actors, ISIC 1 code = 1 actor, upstream -> downstream**. Each is a faithful structural replication of `cloud-itonami-isic-0610` (crude, the reference build) with domain diffs only -- no new architecture invented.

| ISIC | ns | governor | entity | stage |
|---|---|---|---|---|
| 0610 | crude | `:well-safety-governor` | `:well` | upstream |
| 0620 | gasfield | `:gas-well-safety-governor` | `:gas-well` | upstream |
| 1920 | refining | `:refinery-safety-governor` | `:refinery-batch` | midstream |
| 4950 | pipeline | `:pipeline-integrity-governor` | `:pipeline-batch` | transport |
| 5210 | terminal | `:terminal-storage-governor` | `:terminal-stock` | midstream (custody) |
| 5020 | tanker | `:marine-cargo-governor` | `:vessel-shipment` | transport |
| 4671 | fueltrade | `:fuel-trading-governor` | `:fuel-order` | downstream (wholesale) |
| 4730 | forecourt | `:forecourt-safety-governor` | `:fuel-sale` | downstream (retail) |

**Kept structure (every actor, unchanged from the fleet skeleton):** StateGraph `intake -> advise -> govern -> decide -> (commit | request-approval | hold)`; 1 run = 1 op (a well/batch/vessel's life advances via many independent runs, no unbounded inner loop); `interrupt-before #{:request-approval}` HITL; Store protocol `MemStore || DatomicStore` (langchain.db `:db-api`); phase 0->3 rollout with actuation ops **never** in any `:auto` set (two-layer guard: phase gate + governor high-stakes); governor HARD/SOFT + dedicated-boolean double-actuation guards; registry range-check pure fns + unsigned-VC drafts; facts 4-jurisdiction honest catalog; advisor `mock || llm-advisor` swap; `.cljc` `#?(:clj :cljs)` portability; CACAO/identity via the common base (`cloud_itonami/edge/cacao.cljc`), not actor-local.

**Domain-specific HARD checks (HSE-critical, grounded in real regulation):**
- **0610 crude:** reservoir-pressure two-sided window, well-integrity annular/MAASP (true blowout precursor -- replaces a naive flow-rate check), water-cut/BS&W, H2S/NIOSH-IDLH, integrity-flag-unresolved. Spec-basis: BSEE 30 CFR 250 / OSHA PSM / UK HSE OSD / Norwegian PSA / METI 鉱山保安.
- **0620 gasfield:** pressure two-sided, H2S (vol%, NIOSH IDLH converted honestly to 0.005 vol%), CO2-corrosion (sour-acid gas), well-integrity annular/MAASP.
- **1920 refining:** unit-temp & unit-pressure two-sided windows, yield-rate (measured/required ratio), contamination-flag, **flare-system-inoperational** (overpressure relief path; Texas City BP explosion root).
- **4950 pipeline:** line-pressure two-sided, POD-chain-integrity (custody-proof chain, freightops/4920 discipline reapplied), product-contamination, **integrity-assessment-stale** (ILI/pigging/hydrotest interval; PHMSA recurring duty), bonding-grounding.
- **5210 terminal:** receipt-POD-chain, **overfill-risk** (Buncefield-type, level+ullage vs planned receipt), **tank-integrity-assessment-stale** (API 653), bonding-grounding. Spec-basis: API 2350 / API 653 / COMAH / 消防法危険物.
- **5020 tanker:** **IMO-number check-digit** (7-digit structural validation, self-contained pure fn), B/L verification, cargo-grade mismatch, vessel-overload, **inert-gas-o2-excessive** (O2 > 8 vol% during loading/discharging/crude-washing; **SOLAS-mandatory IGS -- the tanker actor's HSE-completeness gate**), bonding-grounding. Spec-basis: SOLAS II-2 Reg 4.5 / MARPOL Annex I / USCG 33 CFR.
- **4671 fueltrade:** credit-uncleared, contract-missing, **counterparty-sanctions-flag-unresolved** (OFAC/equivalent). Spec-basis: 関税法 / METI 輸出貿易管理令 / OFAC / IRS fuel excise / HMRC.
- **4730 forecourt:** meter-uncertified (legal verification expiry), price-anomaly (price band), overfill-risk (underground-tank ullage), vapor-recovery-inoperational (jurisdiction-mandated split, construction threshold-model discipline).

LEL/UEL/flashpoint are NOT separate runtime checks -- they are spec-basis/evidence (the runtime boolean gates are IGS/temperature/pressure/water-cut etc.), matching the fleet norm that attribute thresholds live in spec-basis and only operationalizable gates are boolean HARD checks.

**Scope decisions:**
- **0910 (drilling support) folded into 0610's evidence-incomplete check** (casing-integrity log / BOP test record / cementing record) -- same well-safety regulator and physics; avoids a duplicate upstream actor.
- **5210 (terminal) ADDED** beyond the user's initial 7 -- the custody-transfer point (terminal receipt -> gauge/mass-balance -> storage -> custody hand-off) is where fraud/loss concentrates in practice, and its HSE (Buncefield overfill, API 653 tank integrity, vapor) is independent. A "complete chain" claim is not credible without it.
- **2011 (petrochemicals) / 1910 (coke) / 3520 (gas distribution) OUT OF SCOPE** -- different feedstock/industry division (2011 is division 20 petrochemicals broadly; 1910 is coal-based; 3520 is gas, a different chain with different physics like compressible-flow/m3 units).
- **0620 (natural gas) KEPT** -- associated gas shares the wellhead and well-safety physics (H2S, MAASP, blowout) with crude; the same well-safety regulator governs. Gas downstream (3520 city distribution) exits the liquid chain here, matching industry reality (associated gas is reinjected/flared/diverted).

**Cross-actor coupling:** each actor is an independent ledger; upstream provenance (e.g. 1920's `api-gravity-in` originating from 0610's lift record) is asserted as a **cited VC draft** in `:cites` and verified by the governor's evidence-incomplete check for presence + spec-basis -- NOT a live cross-actor lookup. This matches the fleet architecture (no actor reads another's ledger; provenance flows via unsigned VC drafts + CACAO identity at the edge).

## Consequences

- The petroleum supply-chain full chain (upstream -> midstream -> downstream) is now actor-covered: 8 new public AGPL-3.0 repos in the `cloud-itonami` org, each `:implemented`, lint clean, contract tests green, **standalone** (`repos.edn`/`west.yml` UNCHANGED per fleet convention ADR-2607011000).
- HSE regulation cited honestly per jurisdiction (BSEE / OSHA / HSE / PSA / METI / SOLAS / MARPOL / PHMSA / API / NFPA / OFAC / USCG / MCA etc.) -- no fabricated regimes or numbers (NIOSH H2S IDLH = 50 ppm; SOLAS IGS O2 < 8 vol%; IMO 7-digit check-digit; API 653/2350 intervals).
- These 8 are NEW ISICs beyond the prior 98-implemented fleet (ADR-2607100300's "blueprint tier reaches zero" milestone was about the prior registry; these add net-new verticals). `kotoba-lang/industry` registry entries for 0610/0620/1920/4950/5210/5020/4671/4730 are a **follow-up** (not required for the actors to run -- each is self-contained with its own `blueprint.edn :isic-rev5`), tracked separately -- **completed 2026-07-10, see Addendum below**.
- Standing-authorization guardrails held throughout: cp+adapt (no generator dependency -- each actor's prose docstrings, facts catalogs, and governor-check narratives are hand-authored per actor, the same discipline as every prior sibling); no manifest change (the 8 actor repos themselves remain standalone/unregistered, per ADR-2607011000; the one `manifest/west.yml` touch in the Addendum below is `kotoba-lang/industry`'s own pre-existing pin, not a new registration); no force-push; grep-verified unique ns/governor/entity per actor; honest coverage (no fabricated regulations); ADR filed.
- Build method note: actors #3-#8 were produced via a Workflow (explicit user opt-in) parallel fan-out over the per-actor domain spec, each agent cp'ing the reference 0610 build and applying its domain diff to green lint/test + push; a transient API rate-limit (429) interrupted the first attempt and was resumed after the limit window reset. The reference build (#1 0610) and #2 0620 were produced via dedicated subagents. `isic-4730` (forecourt) was the slowest to close: source/tests/docs landed first but the repo itself (`git init` + `gh repo create` + push) and a docs/adr de-coupling commit (removing hardcoded sibling-repo-name references, for standalone forkability) followed in a later session pass.

## Addendum (2026-07-10): kotoba-lang/industry registry follow-up completed

- Registered all 8 actors in `kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`: promoted 0610/0620/1920/4730/5210 from `:spec` to `:implemented` (repo/business-id updated off the legacy `gftdcojp/cloud-itonami-<Letter><code>` placeholders that predate this ADR); added 3 new `:implemented` entries for 4671/4950/5020. `industry_test.clj` updated to match (98->106 `:implemented`, +8 assertions), lint clean, tests green. Commit `5b49dbd2` pushed to `kotoba-lang/industry`; `manifest/west.yml`'s pre-existing `industry` pin advanced to match (single-entry diff, server-side pin-verification passed) and landed on `com-junkawasaki/root` main via a feature-branch + GitHub API merge (`origin/main` was advancing fast under concurrent fleet activity at the time).
- **Finding: 3 of the 8 actors' ISIC codes do not match real ISIC Rev.4 classification**, which `kotoba-lang/industry`'s registry is otherwise scoped to (verified against the registry's own pre-existing entries, the in-repo source of truth): `4671` "fuel wholesale" -- the real Rev.4 code for that description is **4661**; `4950` "pipeline" -- real Rev.4 code is **4930** ("Transport via pipeline", already a registry entry); `5020` "tanker" -- real Rev.4 splits this into **5012**/**5022** (sea/coastal vs inland freight water transport). `5210` (terminal) is a real Rev.4 code but a class-level ambiguity: 5210 is generic "Warehousing and storage" in Rev.4, specialized here to petroleum terminal storage.
- Decision (owner-confirmed): register the 3 mismatched actors under their **built/published codes** (4671/4950/5020, matching the already-public GitHub repo names and each actor's own `blueprint.edn :isic-rev5` declaration) rather than renaming the already-pushed public repos. Documented in the registry.edn entries themselves as project-internal "isic-rev5" extensions beyond real Rev.4; the real-Rev.4 entries (4661/4930/5012/5022) remain separately registered at `:spec`, untouched. Not treated as a bug to fix retroactively -- the repos and their ADR text are left as-is; this is a data-integrity note for anyone consuming the registry expecting strict Rev.4 fidelity.
