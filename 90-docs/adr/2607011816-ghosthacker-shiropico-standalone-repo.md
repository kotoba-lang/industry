# ADR 2607011816: SHIRO & PICO (ghosthacker-shiropico) as a standalone west-managed repo

## Status
Accepted

## Context

While reviewing Ghost Hacker manga progress, `orgs/gftdcojp/ai-gftd-mangaka`
(itself just split out of `ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-mangaka`
the same day) turned out to hold two unrelated things under `data/`:

- `data/ghosthacker/` — Ren's Ghost Hacker line, style-iteration history
  (`v5plus`/`v8plus`/`v10`) and lettered/color sample PDFs for Arc 0-1,
  reusing the engine + world assets that live in this same repo.
- `data/ghosthacker-shiropico/` — **SHIRO & PICO**, a sibling series in the
  same GFTD cyber universe ("information is physical" / Landauer's
  principle) but a materially different product: global kids' audience
  (India / Gulf-MENA), 7+/PG/TV-Y7-FV rating, VOICEVOX-voiced anime shorts
  rather than manga panels. `SERIES-BIBLE.md` documents it as a "recovered
  design" from session `be34e38d` (2026-06-18), independently conceived
  from Ren's line and cross-over-linked only at the universe level.

Inspecting the actual tracked files (`git ls-files`) showed
`data/ghosthacker-shiropico/` was materially more complete than its own
`SERIES-BIBLE.md` claimed (which only documents episode 1 as scripted): a
full `episodes/` directory holds all 12 episode scripts plus ar/en/es/hi
translations, and episode 11-12 shot lists exist in 10 languages
(ar/bn/de/en/es/fr/hi/pt/ta/zh). This is a complete, independent narrative
asset, not pipeline scaffolding — its natural lifecycle (writing, i18n,
episode additions) is decoupled from `ai-gftd-mangaka`'s (render engine,
ComfyUI/fleet topology, D1/R2/Worker infra).

Binary assets for this series (character refs, generated panels, motion
comic) were never inside this repo to begin with — `ASSETS-DATALAD.md`
already routes them to a separately-named DataLad dataset
(`~/gftdcojp/ghosthacker-shiropico-assets`, B2 + IPFS), independent of
whichever repo holds the text/data corpus.

Ren's line already gets this separation: it lives partly in its own
long-standing repo (`orgs/com-junkawasaki/ghosthacker`) rather than solely
inside the render-engine repo. SHIRO & PICO had no equivalent home.

## Decision

Split `data/ghosthacker-shiropico/` out of `ai-gftd-mangaka` into its own
west-managed repo, `orgs/gftdcojp/ai-gftd-ghosthacker-shiropico`, using the
exact same mechanical pattern `ai-gftd-mangaka` itself was split with
minutes earlier (plain copy, single commit, `README.md.edn` recording
`:source` + a one-line `:note`, private GitHub repo under `gftdcojp`):

1. Copy all 85 git-tracked files (`SERIES-BIBLE.md`, `episode-01.md`,
   `episodes/*.{md,ar.md,en.md,es.md,hi.md}`, `episode-{11,12}-shotlist-v2.*.edn`
   in 10 languages, `character-design-spec.json`, `henshin-bank.json`,
   `pipeline-specs.json`, `ASSETS-DATALAD.md`) into the new repo root.
2. `git init` + one commit + `gh repo create gftdcojp/ai-gftd-ghosthacker-shiropico
   --private --source=. --push`.
3. `git rm -r data/ghosthacker-shiropico` in `ai-gftd-mangaka`, commit, push.
   Ren's `data/ghosthacker/` is untouched — no code in `ai-gftd-mangaka/clj`
   referenced `ghosthacker-shiropico` paths (confirmed via grep before the
   move), so nothing else needed updating.
4. Register in the superproject manifest (`manifest/repos.edn`
   `:extra-projects` + `manifest/west.yml` entry, `pin == child repo HEAD`
   verified: `09cbea7ff5107e41387577a1450213d0c1c734f6`).

Step 4 was done via the GitHub API single-entry-commit method
(`repos.edn :manifest-workflow :canonical :api-single-entry`) rather than a
local edit + push, for two compounding reasons observed live during this
session, not just as a matter of following policy: (a) `git fetch` on the
superproject repeatedly showed `(forced update)` on `origin/main`, which
`gh api repos/.../compare` confirmed in each case was a false positive
(`status: ahead, behind_by: 0`, i.e. a plain fast-forward misread through a
fresh shallow-clone graft boundary — the exact failure mode CLAUDE.md's
Git-operations section was updated to document during this same session);
(b) the local superproject checkout's `main` tip kept moving between
commands, evidence of a concurrent fleet agent committing to the same
shared working tree. Editing the manifest files via the Contents API against
the current `origin/main` tip (blob-SHA-matched PUT) sidesteps both: no
local 3-way merge, no race with a concurrent writer of the same checkout.
`nbb scripts/gen-west-manifest.cljs --check` was deliberately **not** run to
completion as a write step in this session — the local checkout's ~90 other
child-repo HEADs had drifted ahead of what's committed to `west.yml` (other
fleet work in flight), and regenerating from that transient local state
would have force-advanced ~90 unrelated pins in the same commit as this
one-line registration (the "pin regression trap" the workflow doc warns
about). `west update ai-gftd-ghosthacker-shiropico` was used instead to
confirm the new entry resolves and checks out correctly.

## Consequences

- SHIRO & PICO's writing/i18n lifecycle is decoupled from
  `ai-gftd-mangaka`'s render-engine/infra churn, matching the precedent
  already set for Ren's line (`orgs/com-junkawasaki/ghosthacker`).
- `ai-gftd-mangaka` now holds only Ren-line Ghost Hacker data
  (`data/ghosthacker/`) plus the generic render/publish pipeline; no
  dangling references remain (verified empty before deleting).
- A future publish actor for this series (the same `com-etzhayyim-tsumugu`-
  style self-sovereign atproto pattern used for Spirit in Physics, per
  ADR-2607011500) has a single, independent repo to point at, rather than a
  `data/` subtree of an engine repo.
- The superproject's other ~90 drifted child-repo pins were left untouched
  by this change — `west.yml` still reflects whatever state those repos
  were in as of the last time someone ran the full generator and committed
  it. Advancing them is out of scope here and left to whichever workflow
  normally owns that (or a future dedicated pin-advance pass).

## Alternatives considered

- **Leave it nested under `ai-gftd-mangaka/data/`**: rejected — couples an
  independently-written narrative IP's commit history and issue tracking to
  an unrelated render-engine/infra repo's churn, and is inconsistent with
  the standalone treatment Ren's line already has.
- **History-preserving `git subtree split` / `filter-repo` instead of a
  plain copy**: rejected for consistency with the precedent this ADR is
  following — `ai-gftd-mangaka`'s own split from
  `ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-mangaka` minutes earlier
  was a plain copy + single commit (`README.md.edn` records provenance
  instead of preserved history). Introducing a different, heavier tool for
  this split would break that pattern without a concrete need (no one has
  asked to `git blame` past ghosthacker-shiropico commits from inside the
  monorepo).
- **Regenerate and commit `west.yml` in full during this session**:
  rejected — the local checkout's other ~90 child pins had drifted from
  fleet activity concurrent with this session; a full regen would have
  silently bundled ~90 unrelated pin advances into a one-line registration
  commit. Used the single-entry API method instead.

## Correction (2026-07-01, same day)

The original Context/Decision above framed `ai-gftd-mangaka` as the
(accidental) parent and didn't name an intended production engine.
`orgs/gftdcojp/ai-gftd-mangaka/data/ghosthacker-shiropico/pipeline-specs.json`
was in fact authored against `ai-gftd-mangaka`'s `storyboardFromPrompt` (2D
panel) convention — but SHIRO & PICO is structurally an **anime**, not a
manga: 11-minute episodes, VOICEVOX voice acting, an OP/ED, and a motion
comic, per `SERIES-BIBLE.md` and `episode-01.md`.

`orgs/gftdcojp/ai-gftd-animeka` (also split from `ai-gftd-apps-gftdcojp`
the same day, structure mirrors `ai-gftd-mangaka`) is a materially better
structural fit: its own `CLAUDE.md` describes it as "`mangaka`（manga）の
anime 版" — same team-based production-appview shape, but the creative atom
is `cut` (a shot on a time axis) rather than `page/panel`, over a
`work → episode → scene → cut` domain model
(`clj/src/animeka/domain.cljc`) and a 12-stage pipeline (script →
storyboard → layout → keyAnim → inbetween → colorDesign → finish →
background → composite → edit → sound → delivery). Stronger still:
animeka's own test fixture (`clj/test/animeka/core_test.cljc`) already uses
`{:title "Shiro Pico" :id "work-shiro"}` as its canonical round-trip
example — independent evidence that this series was already understood
elsewhere in the codebase as animeka's, not mangaka's, reference content.

This does not change the decision to split `ghosthacker-shiropico` into
its own repo (content remains engine-agnostic and correctly independent
either way). It corrects the record on which engine is the intended
consumer: **`ai-gftd-animeka`, not `ai-gftd-mangaka`**, is primary;
`pipeline-specs.json`'s mangaka-shaped spec is a legacy/secondary path
pending reconciliation to animeka's cut-based model. Landed via
`orgs/gftdcojp/ai-gftd-ghosthacker-shiropico` PR #1
(`docs/animeka-consumer-note`), which amends `README.md.edn` accordingly.
