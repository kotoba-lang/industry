# ADR-2607011345: Agent worktrees must fix west topdir (outside superproject root + `west init -l manifest`)

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

Multiple agents (codex sessions, cleanup fleet, etc.) work on the superproject
concurrently. To avoid disturbing each other's working tree, the natural move is
for each agent to use its own `git worktree`. However, during the clj-wgsl
migration session, an agent working in a `.claude/worktrees/<name>` worktree ran
`west update kami-webgpu` and observed:

- the child repo did **not** materialise inside the worktree (`orgs/...` absent),
- instead a **different agent's** uncommitted `M src/kami/dance.cljc` in the
  superproject-main checkout was reported as a dirty blocker, and
- `west update` skipped / failed on repos the main checkout had WIP in.

Root cause: **west resolves `topdir` by walking up from CWD looking for a
`.west/` directory.** A `git worktree` created *under* the superproject root
(the default `.claude/worktrees/<name>` location) is a subdirectory of the
superproject, so the upward walk finds the superproject's own `.west/` and west
concludes `topdir = <superproject root>`. All `west update` / `west list` /
child-repo checkouts then target the **superproject's** `orgs/` — shared across
every agent — re-introducing the exact WIP collision the worktree was meant to
isolate.

`WEST_TOPDIR` env-var override does **not** fix this (`.west/` discovery wins),
and `west init -l manifest` inside the nested worktree refuses with
"already initialized in <superproject>" (it sees the superproject's `.west/`).

## Decision

**An agent that needs to run `west` in its own worktree must (a) create the
worktree *outside* the superproject root (a sibling path), and (b) run
`west init -l manifest` inside it to mint a worktree-local `.west/` that fixes
`topdir` to that worktree.**

```bash
git worktree add -b <agent-branch> /tmp/root-<agent-name> origin/main
cd /tmp/root-<agent-name>
west init -l manifest          # worktree-local .west/ → topdir fixed here
west update --fetch smart <repo>
```

Worktrees created under `.claude/worktrees/` (inside the superproject) are fine
for **doc/script/manifest-only edits that never invoke `west`**, but must not be
used for child-repo work.

### Verified (2026-07-01)

| setup | `west topdir` returns | `west update` lands in |
|---|---|---|
| worktree under `.claude/worktrees/` (inside super) | superproject root | superproject `orgs/` (shared — WRONG) |
| worktree under `/tmp/...` (outside super) + `west init -l manifest` | the worktree | worktree `orgs/` (isolated — CORRECT) |

`WEST_TOPDIR=<worktree> west topdir` still returned the superproject root —
env-var override is not a fix; only the outside + `init -l` combo works.

## Consequences

- Each agent's child-repo checkouts are fully isolated; one agent's uncommitted
  WIP can no longer block another agent's `west update`.
- Cost: child repos are duplicated per agent (disk/bandwidth). Mitigated by
  `--fetch smart` + shallow default; heavy repos stay on DataLad/B2.
- **Does not prevent upstream force-push effects**: `origin/main` force-rewrites
  and child-repo remote force-rewrites (pin rollback, `upload-pack: not our ref`)
  are upstream problems no worktree layout can fix. Those require the
  force-push ban already in CLAUDE.md to be honoured upstream.
- CLAUDE.md "リポジトリ構成" section gains a subsection recording this rule.

## Related

- `CLAUDE.md` §リポジtori構成 / "agent 専用 worktree で west を動かすときの topdir 固定"
- `90-docs/adr/2606241600-shallow-depth1-git-default.md`
- `90-docs/adr/2606272237-manifest-workflow-single-entry-commit.md` (force-push ban)
- `90-docs/adr/2607010930-clj-wgsl-migration.md` (the session that surfaced this)
