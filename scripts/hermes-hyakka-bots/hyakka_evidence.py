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

# Hourly-throttle: the cron fires every hour; a full iteration is due only
# once per cooldown window. A throttled run exits 0 with a [SILENT] banner
# (the bot runs and is told "not due", never silently skipped), and a
# throttled run marks nothing, so a blind tick never resets the clock.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from throttle import gate, lock, mark  # noqa: E402

JOB = os.environ.get("HERMES_JOB_NAME", "hyakka-evidence")
COOLDOWN_H = float(os.environ.get("HYAKKA_EVIDENCE_COOLDOWN_H", "24"))

gate(JOB, hours=COOLDOWN_H)

# Which worktree this run owns, in falling order of explicitness:
#
#   1. HYAKKA_BOT_WORKTREE — an operator saying it outright
#   2. the job's own cwd, when it is a git worktree. Hermes runs a cron
#      script with `cwd = job.workdir` (cron/scheduler.py, `_script_cwd`),
#      and passes no job id or job name in the environment — checked before
#      writing this — so a per-job `--workdir` is the only per-job signal
#      this script can see.
#   3. the legacy shared default, unchanged
#
# (2) exists because ONE script serves SEVERAL jobs, and a per-script
# default therefore hands them all the same tree. Measured 2026-08-30:
# four jobs shared this tree (hyakka-source-scout, which fires 8x a day, hyakka-ontology-scout, mg-equipment-schema, mg-equipment-source), and source-scout's 05:30 fire sat 15 minutes before mg-equipment-source's 05:45 while p90 run length was 21 minutes. An interrupted run leaves an uncommitted diff behind and every
# later fire in that tree fails the same `git checkout` refusal — the
# failure that blocked hyakka-source-scout for ~11 hours.
#
# A cwd that is NOT a worktree is ignored rather than trusted: before
# per-job workdirs were set that cwd was the shared superproject checkout,
# where several Claude sessions work at once.
def _work_root() -> str:
    explicit = os.environ.get("HYAKKA_BOT_WORKTREE")
    if explicit:
        return os.path.expanduser(explicit)
    cwd = os.getcwd()
    if os.path.exists(os.path.join(cwd, ".git")) and cwd != os.path.abspath(
            os.path.expanduser("~/github/com-junkawasaki")):
        return cwd
    return os.path.expanduser("~/.itonami/worktrees/hyakka-growth-bot")

WORKTREE = _work_root()
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
    with lock("/tmp/hyakka-growth-bot.iterlock", name=JOB):
        _main_locked()


def _main_locked() -> None:
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
    mark(JOB)  # only on a clean, SCANNED-bearing report


if __name__ == "__main__":
    main()
