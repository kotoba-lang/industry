---
name: disk-space-cleanup
description: Audit and safely reclaim regenerable disk space on the user's macOS workstation. Use when asked to clean disk space, free storage, or investigate a nearly full Mac; do not use for Git branch, stash, or worktree retirement.
---

# Disk Space Cleanup

Recover useful space without treating source, history, user data, or active work as disposable.

For disk-wide mapping or individual review of a path outside this skill's fixed
allowlist, use `disk-space-audit` first. This skill starts only after a
regenerable class has already been selected.

## Workflow

1. Run `scripts/mac_disk_cleanup.zsh audit`. Avoid a whole-home recursive scan: this workspace has thousands of Git/DataLad trees and broad traversal is slow.
2. Report the current free space, exact candidate classes and measured sizes, reversibility, and exclusions before deletion.
3. Run `scripts/mac_disk_cleanup.zsh apply` for bounded package/build/browser
   caches. Maven and Gradle dependency caches are audit-only because a running
   JVM may already have resolved files beneath them; deleting the directory can
   invalidate its classpath mid-process. When pressure remains severe and the
   user asked for cleanup, run `apply-extended` for inactive browser/meeting-app
   model caches too; those may need downloading again.
4. Re-run `audit` after the active tools have had time to recreate required caches. Report the stable before/after free space. A command completing—or a transient increase before immediate redownload—is not evidence of durable reclaimed space; `df` is.
5. If less than roughly 10 GiB remains, inspect only the largest targeted areas next. Report large non-regenerable areas rather than deleting them.

## Safety boundary

Never delete or thin these merely to gain space:

- repositories, `.git` objects, branches, stashes, worktrees, or DataLad/git-annex content;
- Codex sessions, archived sessions, memories, evidence, documents, Downloads, databases, browser profiles, cookies, IndexedDB, workspace/global storage, or cloud-sync data;
- Docker volumes/containers, resident releases, installed toolchains, or active services unless the user separately puts that exact class in scope.

Do not infer that an untracked worktree-like directory is disposable. If Git cleanup is actually requested, use `git-cleanup-conflict` and its archive-before-delete rules.

Do not inspect process command lines: they can expose API keys. To decide whether a cache is active, prefer app/service state or open-file checks scoped to the exact directory.

## macOS-specific choices

- Simulator runtimes: list with `xcrun simctl runtime list`; delete only a runtime with no devices and only through `xcrun simctl runtime delete <UUID>`.
- Docker: inspect with `docker system df`; prune build cache only. Volumes and containers remain out of scope.
- APFS snapshots: distinguish update snapshots from Time Machine snapshots. Never remove OS update snapshots ad hoc.
- Permission-denied cache entries: leave them. Do not escalate merely to erase protected cache residue.
- Homebrew: `brew cleanup -s --prune=all` may remove obsolete kegs/downloads, but its many "most recent version not installed" warnings are not reclaimed-space evidence.
- Active runtime dependencies: Maven (`~/.m2/repository`), Gradle
  (`~/.gradle/caches`), and Codex runtime dependencies can be referenced by
  already-running processes. Measure and report them, but do not delete them
  from the unattended `apply` / `apply-extended` modes or from inside an active
  Codex session.

The helper is intentionally dry-run by default and validates that every deletion target is beneath the current `/Users/<name>` home before invoking `rm`.
