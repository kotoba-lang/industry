# Cleanup / Merge Conflict Workflow

Machine-readable workflow: [`manifest/cleanup-workflow.edn`](cleanup-workflow.edn).

Codex skill: `$git-cleanup-conflict`

Use this when cleaning unmerged branches/PRs, reconciling worktrees and stashes, or resolving
merge conflicts in this superproject and its `orgs/` child repos.

## Guardrails

- Do not discard owner WIP.
- Do not force-push shared branches.
- Do not rebase. If a stale branch cannot fast-forward, create a clean branch/worktree from current `origin/main` and replay only the needed commits or patch.
- Prefer a new branch from current `origin/main` when an old branch is stale or shallow ancestry is unreliable.
- Treat `manifest/west.yml` as generated output. Resolve source files and regenerate it; do not hand-edit conflict markers.
- Keep failed `stash pop` entries. Git keeps the stash on failed pop; inspect it before applying manually.

## Standard Cleanup

1. Inventory each relevant repo:

   ```bash
   git worktree list --porcelain
   git branch --show-current
   git stash list
   git status --short --branch
   gh pr list --state open --json number,title,headRefName,baseRefName,url,mergeable,statusCheckRollup
   ```

2. Classify what remains:

   - Unmerged branch with useful commits: replay onto current `origin/main`.
   - Stash only: inspect, then apply only if it will not overwrite dirty files.
   - Placeholder repo with no commits or missing remote: report as blocked, do not invent a PR.
   - Generated-file change: regenerate from source of truth before committing.

3. For stale branches, prefer a clean branch:

   ```bash
   git fetch --depth 1 origin main
   git switch -c cleanup-<date> origin/main
   git cherry-pick <needed-commit>
   ```

   If shallow history turns a small cherry-pick into add/add conflicts, abort and apply the small diff manually.
   Do not switch to rebase to solve the conflict.

4. Commit cleanup work with:

   ```bash
   git commit -m "cleanup" -m "Co-Authored-By: Claude Opus 4.8 (1M context)"
   ```

5. Create, inspect, and merge the PR:

   ```bash
   git push origin <branch>
   gh pr create --base main --head <branch> --title cleanup --body cleanup
   gh pr view <number> --json mergeable,statusCheckRollup,url
   ```

   If `gh pr merge` fails only because local `main` is checked out in another worktree, merge via GitHub API.

## Conflict Resolution

Inspect first:

```bash
git diff --name-only --diff-filter=U
rg -n '<<<<<<<|=======|>>>>>>>' <paths>
git diff --stat -- <paths>
```

Resolve by file class:

- `manifest/west.yml`: resolve `manifest/repos.edn`, generator code, and child repo checkouts first; then run `bb scripts/gen-west-manifest.bb`.
- EDN files: keep both logically distinct additions and validate by running the relevant babashka/Clojure reader or generator.
- Markdown policy files: preserve current `main` policy and add only the missing procedure/reference text.
- Stash conflicts: do not drop the stash; inspect `git stash show --stat` and `git show 'stash@{0}' -- <paths>`.

After resolving:

```bash
rg -n '<<<<<<<|=======|>>>>>>>' <changed-files> || true
bb scripts/gen-west-manifest.bb --check
```

Run any domain-specific verification touched by the change, for example:

```bash
bb scripts/kotoba-boundary-audit.bb
```

## Stash Pop Failures

If `git stash pop` says local changes would be overwritten, that is a safe stop. The stash entry is kept.

Do this next:

```bash
git diff --stat -- <paths>
git diff -- <paths>
git stash show --stat stash@{0}
git show 'stash@{0}' -- <paths>
```

Then choose one:

- Apply the stash change manually if it is independent.
- Commit or stash the current local work first.
- Leave the stash and report the conflict if applying it would regress a generated pin.

## Final Report

End every cleanup with:

```bash
gh pr list --state open --json number,title,headRefName,baseRefName,url
git worktree list --porcelain
git branch --show-current
git stash list
git status --short -- <relevant-paths>
```

Report merged PRs, closed/superseded PRs, deleted remote branches, preserved stashes, and untracked placeholder repos.
