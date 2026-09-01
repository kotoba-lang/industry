#!/usr/bin/env python3
"""Hand the coverage scouts their measurements.

Same contract as hyakka_evidence.py, one difference: the scouts that use it
(wikidata-class-scout, sitelink-scout, commons-image-scout) propose SOURCES,
and their raw material is the source list in the config — so the evidence run
is `--offline` and the report is the same, but the worktree is per-job via
cwd (each job carries its own --workdir). Kept as a separate file rather
than parameterising hyakka_evidence.py so the two jobs' behavior stays
readable independently and a change to one cannot silently move the other.

Exit code is load-bearing in one direction, exactly as in hyakka_evidence.py:
on any refusal print a REFUSED banner and exit 0 — the bot must RUN and be
told it is blind, not be silently skipped.
"""
import os
import subprocess
import sys


def _work_root() -> str:
    explicit = os.environ.get("HYAKKA_BOT_WORKTREE")
    if explicit:
        return os.path.expanduser(explicit)
    cwd = os.getcwd()
    if os.path.exists(os.path.join(cwd, ".git")) and cwd != os.path.abspath(
            os.path.expanduser("~/github/com-junkawasaki")):
        return cwd
    return os.path.expanduser("~/.gftd/worktrees/hyakka-growth-bot")


WORKTREE = _work_root()
NBB = os.environ.get("HYAKKA_NBB", "/opt/homebrew/bin/nbb")


def refuse(why: str) -> None:
    print("REFUSED — no evidence was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A proposal built on an unread tree "
          "is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(("git", "-C", WORKTREE) + args,
                          capture_output=True, text=True, timeout=300)


def main() -> None:
    if not os.path.exists(os.path.join(WORKTREE, ".git")):
        refuse(f"no git worktree at {WORKTREE}")

    fetch = git("fetch", "--quiet", "origin")
    if fetch.returncode != 0:
        refuse(f"git fetch failed: {fetch.stderr.strip()[:400]}")
    reset = git("checkout", "--quiet", "--detach", "origin/main")
    if reset.returncode != 0:
        refuse(f"could not move to origin/main: {reset.stderr.strip()[:400]}")

    head = git("rev-parse", "--short", "HEAD").stdout.strip()

    proc = subprocess.run(
        [NBB, "--classpath", "src", "scripts/wiki_growth_evidence.cljs",
         "--root", ".", "--offline"],
        cwd=WORKTREE, capture_output=True, text=True, timeout=600)

    if proc.returncode == 2:
        refuse("the evidence collector refused:\n" + proc.stderr.strip()[:800])
    if proc.returncode != 0:
        refuse(f"the evidence collector exited {proc.returncode}:\n"
               + (proc.stderr.strip() or proc.stdout.strip())[:800])
    if "SCANNED\t" not in proc.stdout:
        refuse("the collector produced no SCANNED line, so its output cannot be "
               "distinguished from a truncated run")

    print(f"worktree\t{WORKTREE}")
    print(f"head\t{head}")
    print(proc.stdout)


if __name__ == "__main__":
    main()
