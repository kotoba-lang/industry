---
name: git-cleanup-conflict
description: Clean up unmerged Git branches, PRs, worktrees, and stashes, and resolve merge conflicts, in the com-junkawasaki/root west-managed superproject and its orgs/ child repos. Use whenever the user says "cleanup", asks to drain/retire stashes or branches, asks to merge PRs to main, asks to stash pop safely, or asks to resolve manifest/west.yml or other generated-file conflicts without losing WIP. Also use proactively any time you are about to `git stash drop` or `git branch -D` something yourself — do not free-hand it. Also trigger on "orphan repo", "west 未登録", "local only", ":local/root が壊れている", or when a fresh checkout cannot resolve a sibling dep.
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
  exist in current `main`?), not by patch-id or shallow ancestry — shallow clones give
  false ancestry signals.
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

1. Inventory: `git worktree list --porcelain`, `git branch --show-current`,
   `git stash list`, `git status --short --branch`,
   `gh pr list --state open --json number,title,headRefName,baseRefName,url,mergeable,statusCheckRollup`,
   **`nbb scripts/west-orphan-audit.cljs`** (and `--blocking` if any dep failure is
   in scope).
2. Classify each stash/branch per `:retirement :classify` in the edn (landed /
   landed-reworded / superseded / unlanded). Classify west orphans per the table above
   (and `:west-orphan` in the edn).
3. **Archive everything** (stashes and branches alike) to `.git/stash-archive-<date>/`
   before touching anything — see the non-negotiable rule above.
4. Landed/superseded → drop/delete. Unlanded → rescue to a pushed branch (never back
   into a stash) in a sparse worktree outside the superproject, per `:retirement
   :rescue`.
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
