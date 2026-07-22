# Cleanup / Merge Conflict Workflow

Machine-readable workflow: [`manifest/cleanup-workflow.edn`](cleanup-workflow.edn).

Codex skill: `$git-cleanup-conflict`

Use this when cleaning unmerged branches/PRs, reconciling worktrees and stashes, or resolving
merge conflicts in this superproject and its `orgs/` child repos.

## Guardrails

- Do not discard owner WIP.
- Do not force-push shared branches.
- Do not rebase. If a stale branch cannot fast-forward, create a clean branch/worktree from current `origin/main` and replay only the needed commits or patch.
- Prefer a new branch from current `origin/main` when an old branch is stale.
- Treat `manifest/west.yml` as generated output. Resolve source files and regenerate it; do not hand-edit conflict markers.
- Keep failed `stash pop` entries. Git keeps the stash on failed pop; inspect it before applying manually.
- **GitHub push alone is not registration.** Repos under `orgs/` that consumers resolve via `:local/root` (or that are intentional fleet members) must also appear in west (`repos.edn` `:extra-projects` + `gen-west-manifest.cljs --entry`). See skill `new-project-scaffold`.

## West orphan inventory

`orgs/<org>/<repo>` can exist locally without being in west, or exist on GitHub without local/west. Mixing path-override leftovers with true orphans causes false registrations.

```bash
nbb scripts/west-orphan-audit.cljs
nbb scripts/west-orphan-audit.cljs --blocking
nbb scripts/west-orphan-audit.cljs --all
```

| Class | Action |
|---|---|
| `:local-root-broken` | **Blocking** — register the missing project or retarget the dep. |
| `:true-orphan-git` | Register or retire; report, never silent-delete. |
| `:path-override-leftover` | Do not re-register old path (new path is already in west). |
| `:worktree-scratch` | Session debris; remove only after unpushed-WIP check. |
| `:personal` | Out of west scope. |
| `:true-orphan-nongit` | Report; register only if it becomes a real repo. |

Incident reference (2026-07-12→17): `kotoba-lang/crm` pushed to GitHub; consumers
`cloud-itonami-isic-5820` / `-6201` / `-6202` use `{:local/root "../../kotoba-lang/crm"}`;
crm missing from west and often from the local tree → fresh checkout breaks.

**Repair / keep current (three planes):** see
[`manifest/west-triple-sync-workflow.md`](west-triple-sync-workflow.md) and
`nbb scripts/west-triple-sync.cljs` (ADR-2607173200).

## Standard Cleanup

1. Inventory each relevant repo:

   ```bash
   git worktree list --porcelain
   git branch --show-current
   git stash list
   git status --short --branch
   gh pr list --state open --json number,title,headRefName,baseRefName,url,mergeable,statusCheckRollup
   nbb scripts/west-orphan-audit.cljs
   ```

2. Classify what remains:

   - Unmerged branch with useful commits: replay onto current `origin/main`.
   - Stash only: inspect, then apply only if it will not overwrite dirty files.
   - Placeholder repo with no commits or missing remote: report as blocked, do not invent a PR.
   - Generated-file change: regenerate from source of truth before committing.
   - West orphan / `:local/root` broken edge: classify per table above; fix blocking edges before claiming cleanup done.

3. For stale branches, prefer a clean branch:

   ```bash
   git fetch origin main
   git switch -c cleanup-<date> origin/main
   git cherry-pick <needed-commit>
   ```

   If a cherry-pick turns into add/add conflicts, abort and apply the small diff manually.
   Do not switch to rebase to solve the conflict.

4. Commit cleanup work with:

   ```bash
   git commit -m "cleanup"   # Co-Authored-By trailer は実行中のハーネス既定の規約に従う
   ```

5. Create, inspect, and merge the PR:

   ```bash
   git push origin <branch>
   gh pr create --base main --head <branch> --title cleanup --body cleanup
   gh pr view <number> --json mergeable,statusCheckRollup,url
   ```

   If `gh pr merge` fails only because local `main` is checked out in another worktree, merge via GitHub API.

## Stash / Branch Retirement

Use this to drain an accumulated `git stash list` / local branch list without losing work.
Prevention lives in `CLAUDE.md` § 並行エージェント運用 (worktree-per-agent, no stash
accumulation); this section is the recovery path. Verified in practice 2026-07-02:
20 stashes + 8 branches drained, 2 genuinely unlanded items rescued.

1. **Snapshot by SHA first.** Concurrent sessions push/pop stashes, so indices shift.
   Record `git stash list --format='%H %gs'` once, and before every drop re-resolve the
   SHA to its current index.

2. **Classify each stash by content containment, not by patch-id or ancestry.**
   Exclude generated files (`manifest/west.yml`) from the check — their stashed content
   is disposable by definition. For the remaining files, test whether each added line of
   the stash diff exists in current `main`'s version of that file:

   - all lines present → landed; safe to retire.
   - only `;;` comment/wording lines missing → landed with rewording; safe to retire.
   - substantive lines missing (repo registrations, code) → unlanded; rescue.
   - same-file-set stashes in a numbered series ("round N") where the latest round is
     fully landed → earlier rounds are superseded intermediates; safe to retire.

3. **Archive everything before dropping.** Export each stash as a patch (plus its
   untracked-file list from `stash^3` when present) into
   `.git/stash-archive-<date>/` with an `index.txt` of `SHA | message`. Dropping is then
   fully reversible without relying on reflog/gc timing.

4. **Rescue unlanded content to a branch, not back into a stash.** Build the branch in a
   sparse worktree outside the superproject (full checkout is slow):

   ```bash
   git worktree add --no-checkout -b stash-rescue-<date> /tmp/root-stash-rescue origin/main
   cd /tmp/root-stash-rescue && git sparse-checkout set --no-cone <paths> && git checkout
   git apply -3 --include='<path>' <archive>/<sha>.patch   # 3-way, per rescued file
   git commit && git push origin stash-rescue-<date>        # push; merge is owner's call
   ```

5. **Retire branches with the same discipline.** Per branch: check
   `git merge-base --is-ancestor <branch> main` (full history is the default — ADR-2607211600 —
   so this resolves directly; no shallow deepening needed). Non-ancestors get the added-line containment check (step 2), and
   unrelated-history branches (no merge base) get a remote-preservation check
   (`gh api repos/<org>/<repo>/commits/<tip>` — if the tip exists in the successor repo,
   the branch is preserved remotely). Archive `git diff main...<branch>` + a commit log to
   `.git/stash-archive-<date>/branches/`, then `git branch -D`. Never touch `git-annex`
   (annex metadata) or branches another session is actively using.

## Conflict Resolution

Inspect first:

```bash
git diff --name-only --diff-filter=U
rg -n '<<<<<<<|=======|>>>>>>>' <paths>
git diff --stat -- <paths>
```

Resolve by file class:

- `manifest/west.yml`: resolve `manifest/repos.edn`, generator code, and child repo checkouts first; then run `nbb scripts/gen-west-manifest.cljs`.
- EDN files: keep both logically distinct additions and validate by running the relevant babashka/Clojure reader or generator.
- Markdown policy files: preserve current `main` policy and add only the missing procedure/reference text.
- Stash conflicts: do not drop the stash; inspect `git stash show --stat` and `git show 'stash@{0}' -- <paths>`.

After resolving:

```bash
rg -n '<<<<<<<|=======|>>>>>>>' <changed-files> || true
nbb scripts/gen-west-manifest.cljs --check
```

Run any domain-specific verification touched by the change, for example:

```bash
nbb scripts/kotoba-boundary-audit.cljs
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
nbb scripts/west-orphan-audit.cljs --blocking
```

Report merged PRs, closed/superseded PRs, deleted remote branches, preserved stashes,
untracked placeholder repos, and **west-orphan summary** (blocking count +
true-orphan-git count, with any intentional deferrals named).
