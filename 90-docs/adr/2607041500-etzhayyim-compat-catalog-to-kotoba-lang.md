# ADR-2607041500: etzhayyim `*-compat` catalog (1,027 repos) → kotoba-lang, one repo per vendor, reverse-domain naming

**Status**: accepted (rollout in progress — 788/1,027 landed: 8 pilot + batch1-26
alphabetical (`8th_wall-compat`..`roblox-compat`); remaining ~238 scheduled as
batched follow-up. One naming collision resolved: `com-cloudflare-compat` (this
catalog's `cloudflare-compat`) disambiguated from a pre-existing, unrelated
`kotoba-lang/com-cloudflare` real API client. Note: batch22's alphabetical range
included 4 nvidia_*-compat dirs already registered by the original pilot batch -
skipped, not re-migrated. Note: `post_quantum-compat` (in batch25's range) has an
anomalous structure (bare `methods/*.cljc`, no README/deps.edn/src/test) and was
skipped rather than migrated; `ratp_paris-compat` substituted to keep the batch at
30. `post_quantum-compat` needs manual handling as a follow-up.)
**Date**: 2026-07-04
**Related**: ADR-2606302300 (org taxonomy, kotoba-lang library-placement rule) · ADR-2607020130
(`kami-nv-compat` relocation precedent) · ADR-2607012200 (umbrella kotoba-lang TS→CLJC refactor —
different, smaller scope: the 8 `etzhayyim-sdk` facades, NOT this catalog)

## Context

`orgs/etzhayyim/root/20-actors/` contains **1,027 directories named `<vendor>-compat`**
(`adyen-compat`, `sentry-compat`, `mastercard_banknet-compat`, `nvidia_isaac-compat`, …). Each
is a **clean-room, API-compatible actor** for one external SaaS/vendor platform — not a client
binding that calls the real vendor's servers, but a from-scratch reimplementation of the
vendor's documented REST surface (CRUD + pagination + filtering + validation) over kotoba's own
Datom log, generated from the vendor's public OpenAPI spec. Structure is near-uniform across all
1,027:

```
<vendor>-compat/
  deps.edn  manifest.json  openapi.json  README.md
  schema/<vendor>.kotoba
  src/<vendor>/{main,actor}.cljc
  test(s)/<vendor>/{main,actor}_test.cljc
```

Per ADR-2606302300's kotoba-lang admission rule (pure `.cljc`, zero vendor SDK, zero network
I/O), these are **library-shaped code, not `etzhayyim` public-interest organism actors** —
`etzhayyim` is `:role :agent-centric :scope :public-interest` (UNSPSC/ISCO/COFOG organisms),
whereas a reusable per-vendor API-compat facade is exactly the shape ADR-2607020130 already
relocated once for NVIDIA (`kami-nv-compat`, moved from `etzhayyim-sdk/src/nv-compat/` — note
that facade is a **distinct**, larger NVIDIA-Omniverse/Isaac-Sim engine-mirror project; the four
`nvidia_isaac|cosmos|drive|nvml-compat` dirs in this 1,027-catalog are separate, smaller,
schema-CRUD actors and do not overlap with it).

### What a 2026-07-04 direct scan found (corrects the "these are all `.cljc` and done" assumption)

- **Source is already `.cljc`**: ~2,055 `.cljc` files across the catalog, one `main`/`actor` +
  one test per vendor. No TypeScript to port (unlike the ADR-2607012200 facades).
- **`deps.edn` is broken or vestigial in both observed shapes** — this is the load-bearing
  finding, and the reason the migration recipe below is not a pure `mv`:
  1. **Pseudo-format** (majority shape, e.g. `adyen-compat`, `anthropic-compat`, `sentry-compat`,
     `stripe-compat`, `nvidia_drive-compat`, `nvidia_nvml-compat`): `{:project {...}
     :dependencies {:kotoba "workspace" :kotodama-wasm "workspace" :datomic-client
     "workspace"}}` — **not valid `tools.deps` syntax** (no `:workspace` resolver exists); never
     consumed by the real Clojure CLI.
  2. **Stale relative path** (older shape, e.g. `nvidia_isaac-compat`, `nvidia_cosmos-compat`):
     real `tools.deps` syntax but `etzhayyim/kotoba {:local/root "../../40-engine/kotoba"}` —
     `40-engine/kotoba` **no longer exists** in `etzhayyim/root` today; already dead before this
     migration.
  - In both shapes the actual test entry point (`run_tests.sh`, or a `nbb.edn` `test` task)
    invokes **`nbb --classpath src:test(s) …`** directly and never touches `deps.edn` — the
    source genuinely requires only `clojure.string` + `clojure.test` + its own namespace, so the
    catalog already **runs and passes with zero external deps**; `deps.edn` is decorative. This
    means relocation is safe (nothing that currently works depends on the broken `deps.edn`),
    but each migrated repo's `deps.edn` must be **replaced** with a correct, self-contained
    `{:paths ["src" "test(s)"] :deps {org.clojure/clojure {:mvn/version "1.11.1"}}}` (or deleted
    in favor of `nbb.edn` alone) rather than carried over as-is.
  - Naming/layout is not fully uniform: `src/<v>/main.cljc` + `test/<v>/main_test.cljc` vs.
    `src/<v>/actor.cljc` + `tests/<v>/actor_test.cljc`. Both are fine `.cljc`; this migration does
    **not** force-normalize the split (separate follow-up if desired).
  - No `LICENSE` file in any sampled repo. Each already carries an SBOM (`manifest.json
    .capabilities.supplychain`) and (sometimes) a `NOTICE`; license placement is a follow-up, not
    a blocker for this pass.

## Decision

Relocate all `*-compat` directories under `etzhayyim/root/20-actors/` to standalone
`kotoba-lang` repos, one repo per vendor, following the `kami-nv-compat` precedent
(ADR-2607020130) and the org-taxonomy library-placement rule (ADR-2606302300). This pass lands
an **8-repo pilot**; the remaining ~1,019 are **scoped and scheduled**, not executed, here.

### Naming rule (deterministic, mechanical — reverse-domain form, owner-confirmed 2026-07-04)

```
new_name = "com-" + kebab(strip_suffix(old_dir, "-compat"))
```

1. Strip the `-compat` suffix.
2. Underscores → hyphens (`mastercard_banknet` → `mastercard-banknet`).
3. ASCII-fold diacritics (`amadeus_altéa` → `amadeus-altea`).
4. Prefix `com-` unconditionally — this matches the org's own existing reverse-domain
   convention (`com-etzhayyim-*` for etzhayyim.com actors, `com-junkawasaki` for
   junkawasaki.com), not a TLD-accurate reverse-DNS of each vendor's real domain (e.g. Sentry is
   actually `sentry.io`). Getting per-vendor TLDs right is out of scope for a 1,027-item
   mechanical pass; anyone may correct an individual repo's name later (GitHub rename + one
   `path-overrides` entry, same recipe as existing renames like `kenchi-actor`→`kenchi-clj`).
5. Collision check against `manifest/repos.edn` before creating; disambiguate with a product
   suffix if two vendors collapse to the same slug (none observed in the pilot batch).

Examples: `adyen-compat` → `com-adyen`, `nvidia_isaac-compat` → `com-nvidia-isaac`,
`mastercard_banknet-compat` → `com-mastercard-banknet`.

### Per-repo migration recipe (mechanical; scripted — see `scripts/migrate-etzhayyim-compat.sh`)

1. Copy `orgs/etzhayyim/root/20-actors/<old>-compat/` content into a scratch dir (outside the
   superproject; the source checkout is read-only for this step).
2. Replace `deps.edn` with a correct, self-contained `tools.deps` file (see finding above).
3. Append a **Provenance** section to `README.md` (relocated-from / ADR reference — same pattern
   as `kami-nv-compat`'s README).
4. `git init`, one commit (`Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`).
5. `gh repo create kotoba-lang/<new-name> --public --source=. --push` (kotoba-lang org is
   `:scope :platform`, public per `manifest/repos.edn` `:orgs` policy).
6. `git clone` the pushed repo into `orgs/kotoba-lang/<new-name>` (working tree needed locally —
   `:extra-projects` requires a cloned tree with a HEAD).
7. Add `"orgs/kotoba-lang/<new-name>"` to `manifest/repos.edn` `:extra-projects` (one line +
   provenance comment).
8. `nbb scripts/gen-west-manifest.cljs --entry <new-name>[,<new-name>...]` (minimal diff, per-entry
   pin verification per ADR-2607022900), then `--check`.
9. `nbb test` (or the repo's own `run_tests.sh`) green in the new location.
10. Commit `manifest/repos.edn` + `manifest/west.yml` to the superproject
    (`chore(manifest): register kotoba-lang/<new-name> …`), synced to `origin/main` first.

**Old `etzhayyim/root/20-actors/<old>-compat/` directories are NOT deleted by this ADR.** They
stay in place until (a) the batched rollout below completes and (b) it's confirmed nothing in
`etzhayyim/root` actually requires the in-tree copy at build/run time (a first grep pass found no
in-tree consumer referencing `20-actors/*-compat` by relative path outside the directories
themselves — same "vendored but unconsumed" situation `kami-nv-compat` was in before its own
relocation). Deletion is a separate follow-up PR once the full catalog has landed in kotoba-lang.

### Pilot batch (this ADR, 8 repos)

| old (`etzhayyim/root/20-actors/`) | new (`kotoba-lang/`) | domain |
|---|---|---|
| `nvidia_isaac-compat` | `com-nvidia-isaac` | robotics/sim (Isaac-style) |
| `nvidia_cosmos-compat` | `com-nvidia-cosmos` | world-model/physical-AI |
| `nvidia_drive-compat` | `com-nvidia-drive` | autonomous-driving sim |
| `nvidia_nvml-compat` | `com-nvidia-nvml` | GPU telemetry/management |
| `adyen-compat` | `com-adyen` | payments |
| `stripe-compat` | `com-stripe` | payments |
| `anthropic-compat` | `com-anthropic` | AI/ML inference |
| `sentry-compat` | `com-sentry` | observability/error-tracking |

Chosen for domain diversity (payments, observability, AI, robotics/sim, GPU infra) to exercise
the recipe across shapes, not because these are otherwise prioritized.

### Rollout plan for the remaining ~1,019

- Batch size: **~25–50 repos per session/workflow pass** (GitHub API rate-limit and review-load
  pacing; not a hard technical ceiling).
- Ordering: alphabetical by old dir name (simplest resumable cursor; no domain-priority claimed).
- Progress ledger: track completed old→new pairs as `manifest/repos.edn` entries accrue (the
  `:extra-projects` set / generated `west.yml` **is** the ledger — a repo is "done" iff it has a
  `kotoba-lang/com-*` entry with pin == HEAD). No separate tracking file needed.
- Per-batch verification: same 10-step recipe + `gen-west-manifest.cljs --check` clean for the
  batch's entries specifically (the wider repo already carries pre-existing, unrelated stale
  entries — not this migration's concern to fix).
- Open question (not decided here): whether `kotoba-lang` should really grow to ~1,027 repos, or
  whether a subset should collapse into fewer multi-vendor repos. Decided to proceed with the
  established one-repo-per-lib pattern (matches `ipfs`/`pqh`/`kami-nv-compat`/etc.) for
  consistency; revisit if repo-count operational overhead becomes a real problem.

## Verification (pilot)

1. Each of the 8 new repos: `nbb test` green, zero tracked `.ts`/`node_modules`, `deps.edn` is
   valid `tools.deps` (no more `"workspace"` pseudo-deps, no dead relative paths).
2. `nbb scripts/gen-west-manifest.cljs --check`: the 8 new entries are absent from the stale diff;
   pin == repo HEAD for each.
3. `orgs/etzhayyim/root/20-actors/<old>-compat/` untouched (git status clean in that checkout).


## Session closing summary (2026-07-05, session paused here)

This ADR's rollout ran as a self-paced `/loop` (30-min cadence) across 26 batches
following the pilot. Landed **788/1,027 (~77%)** before this session closed out:

| batch | old-dir range | landed |
|---|---|---|
| pilot | (scattered) nvidia_isaac/cosmos/drive/nvml, adyen, stripe, anthropic, sentry | 8 |
| batch1 | `8th_wall-compat`..`amadeus_gds-compat` | 30 |
| batch2 | `amd_rocm-compat`..`astm_codes-compat` | 30 |
| batch3 | `athenahealth-compat`..`bentley_projectwise-compat` | 30 |
| batch4 | `bentley-compat`..`boeing_health-compat` | 30 |
| batch5 | `boeing_tap-compat`..`cbot_agri-compat` | 30 |
| batch6 | `cdc-compat`..`climate_fieldview-compat` | 30 |
| batch7 | `clio-compat`..`coupang-compat` | 30 (1 naming collision resolved: `com-cloudflare-compat`) |
| batch8 | `covermymeds-compat`..`ddbj-compat` | 30 |
| batch9 | `deel-compat`..`drone_deploy-compat` | 30 |
| batch10 | `dropbox-compat`..`emotiv-compat` | 30 |
| batch11 | `encompass-compat`..`fawry-compat` | 30 |
| batch12 | `fbi_ucr-compat`..`fortinet-compat` | 30 |
| batch13 | `freebsd-compat`..`gong-compat` | 30 |
| batch14 | `goodrx-compat`..`heroku-compat` | 30 |
| batch15 | `hike_messenger-compat`..`iea_stats-compat` | 30 |
| batch16 | `iea-compat`..`isara-compat` | 30 |
| batch17 | `isic_codes-compat`..`kingdee-compat` | 30 |
| batch18 | `klarna-compat`..`logikcull-compat` | 30 |
| batch19 | `logingov-compat`..`mavlink_swarm-compat` | 30 |
| batch20 | `maxar_technologies-compat`..`mosip-compat` | 30 |
| batch21 | `mpesa-compat`..`neoncrm-compat` | 30 |
| batch22 | `netcracker-compat`..`oceaneering-compat` | 30 (4 nvidia_*-compat in range skipped — already in pilot) |
| batch23 | `ocpp_charging-compat`..`oracle_xstore-compat` | 30 |
| batch24 | `oracle-compat`..`planetscale-compat` | 30 |
| batch25 | `planview_innotas-compat`..`ratp_paris-compat` | 30 (`post_quantum-compat` skipped — anomalous structure, `ratp_paris-compat` substituted) |
| batch26 | `ray-compat`..`roblox-compat` | 30 |

**Cursor for resuming**: next batch starts alphabetically **after `roblox-compat`**
(`find orgs/etzhayyim/root/20-actors -maxdepth 1 -iname '*-compat' -printf '%f\n' | sort`,
skip anything already present in `manifest/repos.edn`'s `:extra-projects`).

**Outstanding items for the next session/batch**:
- ~238 `*-compat` directories remain unmigrated (alphabetically from ~`roblox`-adjacent
  onward through the end of the catalog).
- `post_quantum-compat` needs bespoke, manual migration — its source is a bare
  `methods/*.cljc` directory with no `README.md`/`deps.edn`/`manifest.json`/`schema/`/
  `src/`/`test/`, incompatible with `scripts/migrate-etzhayyim-compat.sh`'s assumptions.
  Recommend either hand-authoring the standard scaffold around the existing `methods/*.cljc`
  content, or confirming with the source repo's owner what the intended shape was before
  migrating.
- No PR was opened for any of this work — every batch landed via the GitHub Contents API
  single-entry commit path directly to `origin/main` (per `manifest/repos.edn`
  `:manifest-workflow`), which is this repo's canonical path for `manifest/repos.edn` /
  `manifest/west.yml` changes and does not go through a branch/PR. There is therefore no
  open branch from this work to convert into a PR.
- The reusable migration script (`scripts/migrate-etzhayyim-compat.sh`) and the batch
  recipe (fetch remote sha → splice by exact-name regex → structural validation →
  API PUT → re-verify) are stable and were exercised successfully across 26 consecutive
  batches, including recovering cleanly from one real `west.yml` corruption (an off-by-one
  in an earlier line-range extraction, found and fixed in batch2) and multiple 409
  optimistic-lock conflicts from concurrent fleet sessions editing the same manifest files.
