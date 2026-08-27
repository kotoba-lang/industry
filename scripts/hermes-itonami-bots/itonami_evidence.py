#!/usr/bin/env python3
"""Hand the itonami growth bots their measurements.

Decision-free. It runs `scripts/itonami-growth-evidence.cljs` against the
populated superproject and prints what that produced. Every rule about what
counts as a maturity candidate or a coverage gap lives in that nbb script, the
maturity tick it calls, and the UN mirrors those read; none of it is repeated
here. This is Python because Hermes runs cron `--script` files as bash or
Python and nothing else -- the same reason `hyakka_evidence.py` is
(ADR-2608271450). A file that holds no decision costs nothing by being in
another language.

TWO ROOTS, ON PURPOSE.

  READ_ROOT   the real superproject. It is the only checkout where `orgs/` is
              populated, and the ISIC and COFOG mirrors live there. Read only.
  WORK_ROOT   a worktree of the same repository. The bot may branch and commit
              here. Nothing writes to READ_ROOT, because other sessions are
              working in it and CLAUDE.md forbids it.

THE EXIT CODE IS LOAD-BEARING IN ONE DIRECTION.

Hermes injects this script's stdout into the agent's prompt. An empty or
half-written report reaches the model as "there is nothing to raise" -- which
is the one answer that must never be produced by failure, because this fleet's
whole measurement problem is that unmeasured and clean look alike. So on any
refusal this prints a REFUSED banner and still exits 0: the bot has to run and
be told it is blind, not be silently skipped.
"""
import os
import subprocess
import sys

READ_ROOT = os.environ.get("ITONAMI_READ_ROOT",
                           os.path.expanduser("~/github/com-junkawasaki"))
WORK_ROOT = os.environ.get("ITONAMI_BOT_WORKTREE",
                           os.path.expanduser("~/.gftd/worktrees/itonami-growth-bot"))
NBB = os.environ.get("ITONAMI_NBB", "/opt/homebrew/bin/nbb")
CANDIDATES = os.environ.get("ITONAMI_CANDIDATES", "5")


def refuse(why: str) -> None:
    print("REFUSED — no evidence was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A maturity or coverage proposal built on an "
          "unread tree is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)  # 0: the bot must RUN and be told it is blind, not be skipped.


def git(root: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(("git", "-C", root) + args,
                          capture_output=True, text=True, timeout=300)


def main() -> None:
    if not os.path.isdir(os.path.join(READ_ROOT, "orgs")):
        refuse(f"{READ_ROOT} has no populated orgs/ — the ISIC and COFOG mirrors "
               f"cannot be read, and a gap list computed without them would be a "
               f"list of everything")

    if not os.path.exists(os.path.join(WORK_ROOT, ".git")):
        refuse(f"no bot worktree at {WORK_ROOT} (see the README for how it is made)")

    # The worktree reads the tip every run and never accumulates a branch of its
    # own. Proposals land as PRs from short-lived branches instead.
    if git(WORK_ROOT, "fetch", "--quiet", "origin").returncode != 0:
        refuse("git fetch failed in the bot worktree")
    reset = git(WORK_ROOT, "checkout", "--quiet", "--detach", "origin/main")
    if reset.returncode != 0:
        refuse(f"could not move the bot worktree to origin/main: "
               f"{reset.stderr.strip()[:400]}")

    head = git(WORK_ROOT, "rev-parse", "--short", "HEAD").stdout.strip()

    proc = subprocess.run(
        [NBB, "--classpath", f"{READ_ROOT}:{READ_ROOT}/scripts/nbb_compat",
         f"{READ_ROOT}/scripts/itonami-growth-evidence.cljs",
         "--root", READ_ROOT, "--candidates", CANDIDATES],
        cwd=READ_ROOT, capture_output=True, text=True, timeout=900)

    if proc.returncode == 2:
        refuse("the evidence collector refused:\n" + (proc.stdout.strip() or
                                                      proc.stderr.strip())[:1200])
    if proc.returncode != 0:
        refuse(f"the evidence collector exited {proc.returncode}:\n"
               + (proc.stderr.strip() or proc.stdout.strip())[:1200])
    if "SCANNED\t" not in proc.stdout:
        refuse("the collector produced no SCANNED line, so its output cannot be "
               "distinguished from a truncated run")

    print(f"read-root\t{READ_ROOT}\t(read only)")
    print(f"work-root\t{WORK_ROOT}\thead {head}")
    print(proc.stdout)


if __name__ == "__main__":
    main()
