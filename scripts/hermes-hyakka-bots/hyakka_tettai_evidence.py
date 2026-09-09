#!/usr/bin/env python3
"""Hand the growth bots their measurements.

Decision-free. It syncs the bot's worktree to the tip of the wiki's default
branch and runs `scripts/wiki_growth_evidence.cljs` there, and its whole
contribution is deciding nothing: the evidence rules live in that nbb script,
the admission rules in `hyakka.corpus.registry` and `hyakka.ingest`. This is
Python because Hermes runs cron `--script` files as bash or Python and nothing
else — the same reason `plugins/dashboard_auth/did` is Python. A file that
holds no decision costs nothing by being in another language; a second copy of
a decision would cost the usual thing.

The exit code is load-bearing. Hermes injects stdout into the agent's prompt,
so an empty or half-written report reaches the model as "nothing is missing",
which is the one answer that must never be produced by failure. On any refusal
(exit 2) or crash from the collector, this prints a REFUSED banner instead of a
report, so the bot is told it is blind rather than told the wiki is complete.
"""
import os
import subprocess
import sys

WORKTREE = os.environ.get("HYAKKA_TETTAI_BOT_WORKTREE",
                          os.path.expanduser("~/.itonami/worktrees/hyakka-tettai-bot"))
NBB = os.environ.get("HYAKKA_NBB", "/opt/homebrew/bin/nbb")
DAYS = os.environ.get("HYAKKA_EVIDENCE_DAYS", "14")


def refuse(why: str) -> "typing.NoReturn":  # noqa: F821
    print("REFUSED — no evidence was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A growth proposal built on an unread tree "
          "is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)  # 0: the bot must RUN and be told it is blind, not be skipped.


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(("git", "-C", WORKTREE) + args,
                          capture_output=True, text=True, timeout=300)


def main() -> None:
    if not os.path.isdir(os.path.join(WORKTREE, ".git")) and not os.path.exists(
            os.path.join(WORKTREE, ".git")):
        refuse(f"no git worktree at {WORKTREE}")

    fetch = git("fetch", "--quiet", "origin")
    if fetch.returncode != 0:
        refuse(f"git fetch failed: {fetch.stderr.strip()[:400]}")
    # Detached on origin/main: the bot reads the tip every run and never
    # accumulates a branch of its own. Its proposals land as PRs from
    # short-lived branches instead.
    reset = git("checkout", "--quiet", "--detach", "origin/main")
    if reset.returncode != 0:
        refuse(f"could not move to origin/main: {reset.stderr.strip()[:400]}")

    head = git("rev-parse", "--short", "HEAD").stdout.strip()

    proc = subprocess.run(
        [NBB, "--classpath", "src", "scripts/wiki_growth_evidence.cljs",
         "--root", ".", "--days", DAYS],
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
