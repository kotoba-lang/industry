---
name: git-cleanup-conflict
description: Clean up unmerged Git branches, PRs, worktrees, and stashes, and resolve merge conflicts, in the com-junkawasaki/root west-managed superproject and its orgs/ child repos. Use whenever the user says "cleanup", asks to drain/retire stashes or branches, asks to merge PRs to main, asks to stash pop safely, or asks to resolve manifest/west.yml or other generated-file conflicts without losing WIP. Also use proactively any time you are about to `git stash drop` or `git branch -D` something yourself — do not free-hand it. Also trigger on "orphan repo", "west 未登録", "local only", ":local/root が壊れている", or when a fresh checkout cannot resolve a sibling dep. Also trigger on any request to LAND un-landed work in bulk — "すべて pr create, merge, cleanup", "全部着地させて", "WIP を全部 PR にして". Also trigger on any request to FIND un-landed work across the fleet — "どの repo に WIP が残っている", "merge されていない/PR が無い repo を特定", "未 push の作業を洗い出す", "landed していない変更", "deploy されていない変更".
---

# Git Cleanup Conflict

Runbook for `com-junkawasaki/root` cleanup, stash/branch retirement, merge-conflict
resolution, and **west-orphan inventory** (local/GitHub/west registration gaps).

Shared with the Codex-side skill of the same name
(`~/.codex/skills/git-cleanup-conflict/SKILL.md`) — same underlying files, same rules,
different agent.

## Required reading — read the WHOLE file, not just the section you think you need

- `manifest/cleanup-workflow.md` — the readable operating guide
- `manifest/cleanup-workflow.edn` — the machine-readable checklist; **this is the
  source of truth**. It has more sections than the immediate trigger suggests
  (`:workflow`, `:retirement`, `:stash-pop`, `:west-conflict`, `:west-orphan`) —
  read past the first matching section. A real incident (2026-07-04): a stash was
  correctly classified as superseded and dropped, but the `:retirement :archive`
  step was skipped because only the `:classify` section was consulted. The stash
  was still recoverable via `git fsck --unreachable`, but that is luck, not process
  — do not rely on it.

## Non-negotiable: archive before you drop anything

If you are about to `git stash drop` or `git branch -D` **for any reason** — including
"I already confirmed via content-containment that this is fully superseded" — you MUST
first export it to `.git/stash-archive-<date>/` (patch + untracked-file list for
stashes, diff + commit log for branches, per `:retirement :archive` in the edn) with an
`index.txt` entry. This is true even when you are highly confident the content is
landed. The archive step is what makes the decision reversible; skipping it because
you're confident is exactly the failure mode this note exists to catch.

## Run `west update` first — before any inventory

```bash
git fetch origin && git merge --ff-only origin/main   # superproject
west update --fetch smart                             # children (dirty ones are skipped, exit 1)
```

Two reasons, both measured 2026-08-04 (owner directive; SSoT: `:west-update` in the edn):

1. **A stale tree gives false verdicts.** Content-containment — the core of every classify
   decision — asks "do this branch's added lines exist in the child repo's *current*
   `origin/main`?" Without a fetch, that question cannot be answered. Measured: judged
   against **pins**, six repos looked ahead (abi +11, bitcoin-node +52, kotoba +19,
   kotobase +3, kagitaba +1, shell +5). Re-measured against **fetched `origin/main`**,
   bitcoin-node was 0 ahead (the pin was simply stale) and almost everything else was
   already landed — only 2 files in `kotoba` were genuinely un-landed. Skipping the
   update would have meant PR-ing already-landed content and rolling `main` back.
2. **west's skip set *is* the inventory.** west never destroys a dirty project; it skips
   it and exits 1. That skip set is the precise population of repos holding local-only
   work. Measured over 4,022 projects (~4h, full history): **87 skipped** — 83 untracked
   collisions, 17 tracked-file changes (13 both). Of those, **69 projects / 70 files**
   were byte-identical spill files (mostly `kotoba-lang/com-*`
   `schema/<name>.kotoba-schema`) that vanish once you verify the hash and delete;
   the remaining **18** were real local work.

Then: classify skips into untracked vs localchg; delete **only** untracked files whose
`git hash-object` matches the pin's blob; leave localchg untouched; and `git fetch origin`
inside each child repo before judging it.

**Traps.** west checkouts fetch into `refs/west/*`, so `origin/<branch>` remote-tracking
refs may not exist — check push state with `gh api repos/<slug>/branches/<branch>`, not
local refs (measured: kotobase's `agent/persist-execution-identities` looked unpushed and
was not). And huge repos take real time: `gftdcojp/apps-gftdcojp` alone ran ~1 hour (5.4
GiB pack, then `git index-pack` resolving deltas at 70% CPU) — the parent `git fetch`
showing 0% CPU is **not** a hang.

## Finding un-landed work across the fleet (UNLANDED inventory)

When the question is "which child repos still have work that hasn't landed?", run:

```bash
nbb scripts/cleanup.cljs --unlanded      # only repos with un-landed work
nbb scripts/cleanup.cljs                 # full survey (also lists quiet repos)
nbb scripts/cleanup.cljs --subrepos      # superproject only (fast)
```

It reports, per child repo, a **landing ladder** — left is more dangerous because git
protects it less:

| Marker | Meaning | Why it matters |
|---|---|---|
| `untracked=N` | **not committed at all** | On no branch, on no remote. One `git checkout` in the shared west checkout destroys it. |
| `dirty=N` | tracked, uncommitted | Survives branch switches only by accident. |
| `unpushed=B:N` | branch `B` is N commits ahead of `origin/B` (or `no-remote`) | Exists only on this machine. |
| `nopr=B` | pushed, not merged into the default branch, **no open PR** | Not on anyone's review path; will rot. |
| `nopr=?(N branches…)` | PR lookup capped | Branch farms (webgpu ≈80, slides ≈90 local branches) would need one API round-trip each. Reported, never silently dropped. |

`untracked` is the marker that matters most, and it is the one the pre-2026-07-25
script could not surface.

**Real incident (2026-07-25).** `orgs/gftdcojp/cloud-itonami` held the entire
Workspace suite — Directory / Mail / Drive / backup / domain-proof / projection-outbox,
~4,000 lines with tests and its own ADR — as **untracked files in the shared west
checkout**, on a `rescue/wip-20260718` branch that was 1381 commits behind
`origin/main`. It was on no branch, on no remote, and not deployed (live
`itonami.cloud` still answered `502 api-route-html-leak`). The old survey printed
`dirty=57` and nothing else, which is indistinguishable from a one-line edit in some
other repo. Landed as PR #488 after replaying onto current `origin/main` in a clean
worktree.

Two lessons baked into the tooling:

1. **`git status` alone under-reports danger.** Split untracked from dirty and rank by
   how little git protects it.
2. **PRs must be checked per child repo, not on the superproject.** The old script only
   ran `gh pr list` against `com-junkawasaki/root`, so a child repo with pushed-but-
   un-PR'd branches looked clean. `--unlanded` now queries each child repo's own slug.

Un-landed ≠ deployed. Even after a merge, check whether the change actually reached
production — cloud-itonami's live Pages Function had been serving a build that predated
the merged source. `git log` says nothing about that; probe the live surface.

## Landing it — `scripts/cleanup-land.cljs`

Survey is read-only; this is the write side.

```bash
nbb scripts/cleanup-land.cljs                      # dry-run plan (default)
nbb scripts/cleanup-land.cljs --apply              # execute
nbb scripts/cleanup-land.cljs --apply --names a,b  # limit to named repos
nbb scripts/cleanup-land.cljs --apply --max 20     # cap; the rest is reported, not hidden
```

**Never merge all UNLANDED work as one class.** Split by *whether it can break `main`*,
not by how dangerous it is to lose:

| Class | What | Action |
|---|---|---|
| `:additive` | untracked files only | commit → PR → **merge**. No such path exists on the default branch, so no existing line is rewritten. Not landing it is the greater risk — it lives on no branch and no remote. |
| `:review` | changes to tracked files | commit → PR, **never auto-merge**. A stale base silently rolls `main` back. |
| `:branches` | existing local branches | push if unpushed (preserve); open a PR if pushed with none. **Never auto-merge** — abandoned experiments, deliberate forks and force-pushed histories all look alike. |

The `:review` rule is not hypothetical. cloud-itonami's working tree was 1381 commits
behind `main`; applying its `legal/terms.md` would have reverted owner-approved public
legal pages to a 2026-07-18 DRAFT.

**`:additive`'s argument fails under a rename, and it fails silently.** "No such path
exists on the default branch" is exactly what a rename makes true of the *old* path. On
`net-kotobase/control-plane`, `worker/` moved on 08-03 and `clj-edge/` on 08-04; on 08-05
and 08-06 two `cleanup: land untracked WIP` passes put 17 files back under those dead
names, including a file byte-identical to the revision immediately before the commit that
superseded its design, and a build artifact `.gitignore` could only exclude at its live
path. `scripts/rename_residue.cljs` now classifies such candidates before they reach
`:additive`: proven residue (bytes already in the object database, or ignored at the live
path) is dropped and reported with its live path; a dead path holding content that is *not*
in history is demoted to `:review` rather than dropped. Nothing is deleted from the working
tree. Full argument and measurements: `:residue-gate` in the edn.

**Write path is the GitHub git API** (blob → tree with `base_tree` → commit → ref), not a
local worktree — at ~100-repo scale, per-repo full checkouts are impractical, and the
shared checkout is often parked on a stale branch. Same server-side single-commit shape
`CLAUDE.md` already mandates for `manifest/west.yml`.

**Two traps this hit, both silent:**

- **Renamed repos.** GitHub redirects GETs and `-f`-style POSTs, but returns **HTTP 307
  to `--input` POSTs, and `gh` does not follow it**. Reads succeed while writes fail, so
  it presents as "commits mysteriously fail." The script resolves every slug through
  `gh api repos/<slug> --jq .full_name` first. Measured: `kotoba-lang/kotoba-git` is now
  `kotoba-lang/bonsai`; the local remote URL still said the old name.
- **`--jq` returning a bare scalar is not JSON.** `6dc20b…` parsed as JSON yields nil, so
  the first implementation failed every commit while reporting success paths normally.
  Use a string-returning helper for `--jq` scalars.

`:branches` runs only with `--branches` (it pushes, so it is opt-in). Branch farms are
capped at 20 live branches per repo and reported, not silently skipped — measured:
kotoba-lang/webgpu has 67 local branches, slides over 90.

Skipped and always reported, never silently dropped: credential-looking paths
(`.env`, `*.pem`, `*.key`, `identity.edn`, `*secret*`, `.kagi/`, …), build junk, files
over 2 MB, and git-annex/DataLad datasets. Executable bits are preserved (`100755`), or
`bin/*` lands unusable.

**Deletions are never applied.** A `git status` ` D ` entry means the file is gone from a
working tree that may be far behind; replaying that onto the default branch can delete
work someone else added. They are counted and named in the report, and that is all.

**Re-runs must be idempotent, because landing does not delete the local copy.** The same
untracked files reappear on the next run. Compare each local blob sha against the base
tree and drop what already matches — measured: after `kotoba-lang/bonsai` PR #3 merged 15
files, the next dry-run planned all 15 again.

Nothing is ever deleted: archive to `.git/stash-archive-<date>/` first, then add.

### Three kinds of "no remote"

`--apply` creates the missing repo (org-default visibility per `repos.edn :orgs` — this is
new-repo creation under `new-project-scaffold`'s standing authorization, *not* the
"公開リポ化" that needs prior confirmation). But the three cases need different handling:

| Case | Handling |
|---|---|
| has commits, no GitHub repo | `gh repo create --source --push`, then land normally |
| **no commits at all** (placeholder branch) | `--push` has nothing to push. Create the empty repo, then land via a **parentless root commit** through the git API — the local checkout is never touched. |
| **repo exists upstream, local lost its `origin`** | Don't create anything — reattach. Measured: `kotoba-lang/org-threejs` looked local-only but existed on GitHub with 10 of its 12 files already landed. |

Check for the existing repo *before* creating one, or you mint duplicates.

## Core rules

- Preserve owner WIP. Never discard dirty files, untracked repos, or stashes without
  archiving first.
- Do not force-push shared branches. Do not rebase to resolve staleness — branch fresh
  from `origin/main` and replay/cherry-pick the needed commits instead.
- `manifest/west.yml` is generated. Resolve `manifest/repos.edn` + generator + child
  repo checkouts first, then regenerate (`nbb scripts/gen-west-manifest.cljs --check`) —
  never hand-edit conflict markers in it.
- If `git stash pop` fails because local changes would be overwritten, that's a safe
  stop: leave the stash intact, inspect both the current diff and the stash patch
  before deciding.
- Classify stashes/branches by **content containment** (do the added lines already
  exist in current `main`?), not by patch-id — content containment is robust even
  though local checkouts are full history now (shallow retired 2026-07-21,
  ADR-2607211600).
- **Push to GitHub alone is not done.** A repo that other west projects consume via
  `:local/root` (or that belongs under `orgs/<org>/<repo>`) must also land in
  `manifest/repos.edn` `:extra-projects` + `nbb scripts/gen-west-manifest.cljs --entry
  <name>` (see skill `new-project-scaffold`). Incomplete = GH-only orphan.

## West orphan inventory (mandatory on cleanup / registration-gap reports)

Real incident (2026-07-12→17): `kotoba-lang/crm` was pushed to GitHub and three
west-registered consumers (`cloud-itonami-isic-5820`, `-6201`, `-6202`) depend on it
via `{:local/root "../../kotoba-lang/crm"}`, but **crm was never added to west** and
is often **missing from the local tree**. Fresh checkout / CI cannot resolve the dep.

```bash
nbb scripts/west-orphan-audit.cljs              # summary + true-orphan-git sample
nbb scripts/west-orphan-audit.cljs --blocking   # only :local/root broken edges
nbb scripts/west-orphan-audit.cljs --all        # full lists
```

**Classify before you register or delete** (do not treat every unregistered dir as a
new project):

| Class | Meaning | Action |
|---|---|---|
| `:local-root-broken` | deps.edn points at missing or not-in-west project | **Blocking.** Register missing commons (clone → `:extra-projects` → `--entry`) or retarget deps to a west path / git dep. |
| `:true-orphan-git` | local git under `orgs/` not in west | Register (if intentional fleet member) or retire/archive — report, don't silent-delete. |
| `:path-override-leftover` | old path after rename; new path is in west | Do **not** re-register the old name. Optional cleanup of leftover dir after content-containment. |
| `:worktree-scratch` | `_wt-*`, `*-current`, `*-boundary`, `_intake` | Session debris — remove only after confirming no unpushed WIP. |
| `:personal` | `orgs/personal/*` | Out of west scope. Never auto-register. |
| `:true-orphan-nongit` | bare folder | Scaffold residue or data drop — report; register only if it becomes a real repo. |

**Completion gate for any new/shared library:** GitHub remote exists **and** west pin
exists **and** every consumer `:local/root` resolves under a west `path:`. Running
`west-orphan-audit` with exit 0 (no blocking edges) is the check.

## Triple-plane sync (GitHub · local · west) — repair & keep current

When the goal is not only to *detect* orphans but to **align** the three planes,
use the dedicated workflow (ADR-2607173200):

```bash
nbb scripts/west-triple-sync.cljs plan --scope blocking   # dry-run
nbb scripts/west-triple-sync.cljs apply --scope blocking  # clone/register/ff/pin
nbb scripts/west-triple-sync.cljs apply --names crm
nbb scripts/west-triple-sync.cljs verify --scope blocking
```

- SSoT: `manifest/west-triple-sync-workflow.edn` (+ readable `.md`)
- Orchestrator: `scripts/west-triple-sync.cljs` (plan default; `--apply` via `apply` cmd)
- Does **not** mass-clone all west projects; scopes are `blocking` | `managed` | `names`
- Still defers dep *retarget* (old `-clj` paths) to a report — does not rewrite deps.edn

## Minimum workflow

1. Inventory (**after `west update` — see the section above**): `git fetch origin && git merge --ff-only origin/main`, `west update --fetch smart`,
   `git worktree list --porcelain`, `git branch --show-current`,
   `git stash list`, `git status --short --branch`,
   `gh pr list --state open --json number,title,headRefName,baseRefName,url,mergeable,statusCheckRollup`,
   **`nbb scripts/cleanup.cljs --unlanded`** (child-repo landing ladder — untracked /
   unpushed / no-PR; see the UNLANDED section above),
   **`nbb scripts/west-orphan-audit.cljs`** (and `--blocking` if any dep failure is
   in scope).
2. Classify each stash/branch per `:retirement :classify` in the edn (landed /
   landed-reworded / superseded / unlanded). Classify west orphans per the table above
   (and `:west-orphan` in the edn).
3. **Archive everything** (stashes and branches alike) to `.git/stash-archive-<date>/`
   before touching anything — see the non-negotiable rule above.
4. Landed/superseded → drop/delete. Unlanded → rescue to a pushed branch (never back
   into a stash) in a sparse worktree outside the superproject, per `:retirement
   :rescue`. At fleet scale use **`nbb scripts/cleanup-land.cljs --apply`** instead of
   doing this by hand — it archives, then lands `:additive` and opens PRs for
   `:review`/`:branches` without merging them (see the section above).
5. Resolve any real merge conflicts by file class (`:resolve-conflicts` in the edn),
   regenerating `manifest/west.yml` rather than editing markers.
6. For blocking orphans: finish registration (`new-project-scaffold` / `--entry`) or
   retarget deps — do not leave GH-only + `:local/root` consumers.
7. Verify: conflict-marker search, `nbb scripts/gen-west-manifest.cljs --check`,
   `nbb scripts/west-orphan-audit.cljs --blocking`, and any domain-specific script
   touched by the change.
8. Create/merge PRs when mergeable; report external CI failures (billing/spending
   limits) as external to the code.
9. Final report: open PRs, worktrees, branch, stash list, **west-orphan summary**
   (blocking + true-orphan-git counts), and anything intentionally left behind —
   including what you archived and where.
