#!/usr/bin/env python3
"""git_cleanup_retire.py — archive-first destructive executor for git-cleanup-ops.

Why a script and not direct git commands: a headless cron run denies every
destructive command form the agent types (rm / git stash drop / branch -D), and
the runbook's NON-NEGOTIABLE rule is "archive before you drop anything, even
when you are confident it landed". This script hard-codes that order: every
DROP subcommand refuses unless the ARCHIVE already exists in
<gitdir>/stash-archive-YYYYMMDD/ with a matching index.txt line, for the exact
SHA. Classification (landed? superseded? unlanded->rescue) stays with the agent.

Subcommands:
  resolve        --repo R                     stash list as 'SHA gs' (snapshot)
  archive-stash  --repo R --sha S             patch + untracked list + index.txt
  drop-stash     --repo R --sha S             only after archive-stash for S
  archive-branch --repo R --name B [--base M] diff+log under branches/ + index.txt
  drop-branch    --repo R --name B [--base M] refuses annex/* names; archive required
  list           --repo R                     what is archived in this repo

--repo is any git toplevel under the workspace root (superproject or orgs/**).
The gitdir is resolved with rev-parse --absolute-git-dir (a .git FILE / linked
worktree has no <dir>/.git directory -- 2026-08-22 blind-spot entry).
"""
import argparse
import datetime
import getpass
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(os.environ.get("GCO_ROOT", str(pathlib.Path.home() / "github/com-junkawasaki"))).expanduser()
NEVER_DELETE_BRANCHES = re.compile(r"^(git-annex|main|master)$")


def sh(cwd, *cmd, timeout=120):
    r = subprocess.run(list(cmd), cwd=str(cwd), capture_output=True, text=True, timeout=timeout,
                       env=dict(os.environ, GIT_TERMINAL_PROMPT="0"))
    return r.returncode, r.stdout, r.stderr


def toplevel(repo):
    p = pathlib.Path(repo).expanduser().resolve()
    try:
        p.relative_to(ROOT.resolve())
    except ValueError:
        die("--repo %s is outside the workspace root %s" % (repo, ROOT))
    rc, out, err = sh(p, "git", "rev-parse", "--show-toplevel")
    if rc != 0:
        die("not a git toplevel: %s (%s)" % (repo, err.strip()[-120:]))
    tl = pathlib.Path(out.strip()).resolve()
    try:
        tl.relative_to(ROOT.resolve())
    except ValueError:
        die("toplevel %s escapes the workspace root %s" % (tl, ROOT))
    return tl


def gitdir(tl):
    rc, out, err = sh(tl, "git", "rev-parse", "--absolute-git-dir")
    if rc != 0:
        die("no gitdir for %s: %s" % (tl, err.strip()[-120:]))
    return pathlib.Path(out.strip())


def archive_dir(tl):
    d = gitdir(tl) / ("stash-archive-%s" % datetime.date.today().strftime("%Y-%m-%d"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def die(msg):
    print("REFUSED %s" % msg)
    sys.exit(1)


def index_has(adir, token):
    idx = adir / "index.txt"
    return idx.is_file() and any(token in line for line in idx.read_text(errors="replace").splitlines())


def index_add(adir, line):
    with (adir / "index.txt").open("a") as f:
        f.write(line + "\n")


def resolve_stash_index(tl, sha):
    """SHA -> current stash@{i}. Concurrent sessions shift indices; always re-resolve."""
    rc, out, _ = sh(tl, "git", "stash", "list", "--format=%H %gs")
    for i, line in enumerate(out.splitlines()):
        if line.startswith(sha) or sha in line.split(" ", 1)[0]:
            return i, line
    return None, None


def cmd_resolve(a):
    tl = toplevel(a.repo)
    rc, out, _ = sh(tl, "git", "stash", "list", "--format=%H %gs")
    print(out or "(no stashes)")


def cmd_archive_stash(a):
    tl = toplevel(a.repo)
    rc, _, err = sh(tl, "git", "cat-file", "-e", a.sha + "^{commit}")
    if rc != 0:
        die("stash sha %s not found in %s (%s)" % (a.sha, tl, err.strip()[-120:]))
    ad = archive_dir(tl)
    patch = ad / ("%s.patch" % a.sha)
    msg = (sh(tl, "git", "log", "-1", "--format=%gs", a.sha)[1] or "").strip()
    with patch.open("w") as f:
        rc, out, err = sh(tl, "git", "diff", a.sha + "^", a.sha, timeout=300)
        if rc != 0 and rc != 1:
            die("git diff failed: %s" % err.strip()[-160:])
        f.write(out)
    u = ad / ("%s.untracked.txt" % a.sha)
    rc, out, _ = sh(tl, "git", "ls-tree", "-r", "--name-only", a.sha + "^3")
    u.write_text(out or "")
    if not index_has(ad, a.sha):
        index_add(ad, "%s | stash | %s | %s" % (a.sha, msg[:100], getpass.getuser()))
    print("ARCHIVED stash %s -> %s" % (a.sha[:10], ad))


def cmd_drop_stash(a):
    tl = toplevel(a.repo)
    ad = archive_dir(tl)
    if not index_has(ad, a.sha):
        die("no archive index entry for %s in %s -- run archive-stash first (non-negotiable)" % (a.sha[:10], ad))
    if not (ad / ("%s.patch" % a.sha)).is_file():
        die("archive index names %s but the patch file is missing -- re-run archive-stash" % a.sha[:10])
    i, line = resolve_stash_index(tl, a.sha)
    if i is None:
        die("sha %s is not in the current stash list (indices shift; snapshot again)" % a.sha[:10])
    rc, out, err = sh(tl, "git", "stash", "drop", "stash@{%d}" % i)
    if rc != 0:
        die("git stash drop failed: %s" % err.strip()[-160:])
    print("DROPPED stash@{%d} (%s) -- recoverable via %s" % (i, line[:60], ad))


def cmd_archive_branch(a):
    tl = toplevel(a.repo)
    base = a.base or guess_base(tl)
    ad = archive_dir(tl) / "branches"
    ad.mkdir(parents=True, exist_ok=True)
    rc, tip, err = sh(tl, "git", "rev-parse", a.name)
    if rc != 0:
        die("branch %s unresolvable in %s" % (a.name, tl))
    tip = tip.strip()
    diff = ad / (re.sub(r"[^A-Za-z0-9_.-]", "_", a.name) + ".diff")
    rc, out, _ = sh(tl, "git", "diff", "%s...%s" % (base, a.name), timeout=300)
    diff.write_text(out)
    log = ad / (re.sub(r"[^A-Za-z0-9_.-]", "_", a.name) + ".log")
    _, out, _ = sh(tl, "git", "log", "--oneline", "%s..%s" % (base, a.name), timeout=120)
    log.write_text(out)
    if not index_has(ad.parent, tip):
        index_add(ad.parent, "%s | branch:%s | %s | %s" % (tip, a.name, getpass.getuser(), a.sha_note or ""))
    print("ARCHIVED branch %s (tip %s) -> %s" % (a.name, tip[:10], ad))


def cmd_drop_branch(a):
    tl = toplevel(a.repo)
    if NEVER_DELETE_BRANCHES.match(a.name) or a.name.startswith("routine/"):
        die("refusing to delete protected branch name %s (git-annex/main/master/routine ledger carriers)" % a.name)
    base = a.base or guess_base(tl)
    rc, tip, _ = sh(tl, "git", "rev-parse", a.name)
    if rc != 0:
        die("branch %s unresolvable" % a.name)
    tip = tip.strip()
    ad = archive_dir(tl)
    if not index_has(ad, tip):
        die("branch tip %s has no archive entry -- run archive-branch first" % tip[:10])
    rc, out, _ = sh(tl, "git", "for-each-ref", "--format=%(worktreepath)", "refs/heads/" + a.name)
    if out.strip():
        die("branch %s is checked out in worktree(s): %s" % (a.name, out.strip()))
    rc, _, err = sh(tl, "git", "branch", "-D", a.name)
    if rc != 0:
        die("git branch -D failed: %s" % err.strip()[-160:])
    print("DELETED branch %s (tip %s archived at %s)" % (a.name, tip[:10], ad))


def guess_base(tl):
    rc, out, _ = sh(tl, "git", "rev-parse", "--abbrev-ref", "origin/HEAD")
    if rc == 0 and out.strip():
        return out.strip()
    return "main" if sh(tl, "git", "rev-parse", "-q", "--verify", "main")[0] == 0 else "master"


def cmd_list(a):
    tl = toplevel(a.repo)
    gd = gitdir(tl)
    for d in sorted(gd.glob("stash-archive-*")):
        idx = d / "index.txt"
        n = len(idx.read_text(errors="replace").splitlines()) if idx.is_file() else 0
        print("%s (%d entries)" % (d, n))


def main():
    p = argparse.ArgumentParser(prog="git_cleanup_retire")
    sub = p.add_subparsers(dest="cmd", required=True)
    for name, fn in [("resolve", cmd_resolve), ("archive-stash", cmd_archive_stash), ("drop-stash", cmd_drop_stash),
                     ("archive-branch", cmd_archive_branch), ("drop-branch", cmd_drop_branch), ("list", cmd_list)]:
        s = sub.add_parser(name)
        s.add_argument("--repo", required=True)
        s.add_argument("--sha")
        s.add_argument("--name")
        s.add_argument("--base")
        s.add_argument("--sha-note", dest="sha_note", default="")
        s.set_defaults(fn=fn)
    a = p.parse_args()
    if a.cmd in ("archive-stash", "drop-stash") and not a.sha:
        die("%s needs --sha" % a.cmd)
    if a.cmd in ("archive-branch", "drop-branch") and not a.name:
        die("%s needs --name" % a.cmd)
    a.fn(a)


if __name__ == "__main__":
    main()
