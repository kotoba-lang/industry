---
name: disk-space-audit
description: Map macOS disk usage, investigate large paths one at a time, and produce evidence-bounded cleanup candidates. Use for disk-wide storage investigations and candidate selection; use disk-space-cleanup only after a regenerable class has been selected for deletion.
---

# Disk Space Audit

Build a disk map first, then review large areas individually. This skill is
read-only: it proposes cleanup candidates but never deletes them.

## Workflow

1. Run `scripts/mac_disk_wide_audit.zsh overview`. Treat `df` on the Data
   volume as capacity truth; APFS volume figures and directory totals explain
   usage but need not add up exactly.
2. Measure the fixed major areas from `overview` one at a time with
   `scripts/mac_disk_wide_audit.zsh measure <absolute-path> [depth] [limit]`.
   Do not start with a whole-home traversal: this workspace contains thousands
   of Git/DataLad trees and broad scans can run for many minutes. Record a
   timeout or permission failure as `unverified` rather than blocking the run.
3. Descend one level into the largest unresolved measured area. For each
   possible target, run
   `scripts/mac_disk_wide_audit.zsh candidate <absolute-path>`. Review one
   exact path at a time; never infer that an old or untracked directory is
   disposable.
4. Classify each exact candidate as one of:
   `reclaimable`, `review-required`, `preserve`, or `unverified`. Record size,
   age, open-file result, Git/worktree evidence, regeneration or recovery
   evidence, and the proposed action.
5. Present the ordered candidate ledger and ask for or apply only the exact
   selection within the task's authority. For the existing fixed cache
   allowlist, invoke `disk-space-cleanup`; anything else needs its own reviewed
   cleanup path.
6. After mutation, re-run `overview` and report stable before/after `df`
   values. A completed command or directory-size estimate is not reclamation
   evidence.

Read [references/macos-map.md](references/macos-map.md) when interpreting APFS
or choosing the next area. Read
[references/candidate-ledger.md](references/candidate-ledger.md) when producing
a Bot- or human-reviewable ledger.

## Safety boundary

- Never delete repositories, Git history, stashes, worktrees, DataLad or
  git-annex content, sessions, memories, evidence, documents, databases,
  browser profiles, cloud-sync data, Docker data, resident releases, installed
  toolchains, swapfiles, or OS update snapshots merely to gain space.
- A matching temp-directory name, age, detached HEAD, lack of a root `.git`,
  or absence from `git worktree list` is insufficient deletion authority.
  Nested repositories and unrecoverable build/release artifacts still matter.
- Do not inspect process command lines. Use open-file checks scoped to the
  exact candidate; report permission failures as `unverified` and do not
  escalate just to inspect or erase protected residue.
- Keep observation, proposal, selection, mutation, and verified reclamation as
  separate states. A Bot may autonomously observe and organize; it may mutate
  only through an independently admitted cleanup capability.
