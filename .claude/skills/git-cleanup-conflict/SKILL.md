---
name: git-cleanup-conflict
description: Clean up unmerged Git branches, PRs, worktrees, and stashes, and resolve merge conflicts, in the com-junkawasaki/root west-managed superproject and its orgs/ child repos. Use whenever the user says "cleanup", asks to drain/retire stashes or branches, asks to merge PRs to main, asks to stash pop safely, or asks to resolve manifest/west.yml or other generated-file conflicts without losing WIP. Also use proactively any time you are about to `git stash drop` or `git branch -D` something yourself — do not free-hand it.
---

# Git Cleanup Conflict

Runbook for `com-junkawasaki/root` cleanup, stash/branch retirement, and merge-conflict
resolution. Shared with the Codex-side skill of the same name
(`~/.codex/skills/git-cleanup-conflict/SKILL.md`) — same underlying files, same rules,
different agent.

## Required reading — read the WHOLE file, not just the section you think you need

- `manifest/cleanup-workflow.md` — the readable operating guide
- `manifest/cleanup-workflow.edn` — the machine-readable checklist; **this is the
  source of truth**. It has more sections than the immediate trigger suggests
  (`:workflow`, `:retirement`, `:stash-pop`, `:west-conflict`) — read past the first
  matching section. A real incident (2026-07-04): a stash was correctly classified as
  superseded and dropped, but the `:retirement :archive` step was skipped because only
  the `:classify` section was consulted. The stash was still recoverable via
  `git fsck --unreachable`, but that is luck, not process — do not rely on it.

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

## Minimum workflow

1. Inventory: `git worktree list --porcelain`, `git branch --show-current`,
   `git stash list`, `git status --short --branch`,
   `gh pr list --state open --json number,title,headRefName,baseRefName,url,mergeable,statusCheckRollup`.
2. Classify each stash/branch per `:retirement :classify` in the edn (landed /
   landed-reworded / superseded / unlanded).
3. **Archive everything** (stashes and branches alike) to `.git/stash-archive-<date>/`
   before touching anything — see the non-negotiable rule above.
4. Landed/superseded → drop/delete. Unlanded → rescue to a pushed branch (never back
   into a stash) in a sparse worktree outside the superproject, per `:retirement
   :rescue`.
5. Resolve any real merge conflicts by file class (`:resolve-conflicts` in the edn),
   regenerating `manifest/west.yml` rather than editing markers.
6. Verify: conflict-marker search, `nbb scripts/gen-west-manifest.cljs --check`, and any
   domain-specific script touched by the change.
7. Create/merge PRs when mergeable; report external CI failures (billing/spending
   limits) as external to the code.
8. Final report: open PRs, worktrees, branch, stash list, and anything intentionally
   left behind — including what you archived and where.
