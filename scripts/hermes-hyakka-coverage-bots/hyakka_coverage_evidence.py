#!/usr/bin/env python3
"""Hand the two hyakka coverage bots (vuln-coverage-scout, osm-coverage-scout)
their measurements. Decision-free, like hyakka_evidence.py: this syncs a bot
worktree to origin/main and runs coverage_growth_evidence.cljs there. Every
rule about what counts as a valid raise lives in that script and in
verify-coverage-proposal.cljs; none of it is repeated here.

A DEDICATED worktree, not hyakka-source-scout/-ontology-scout's shared one.
Sharing across bot families is exactly the failure mode that blocked
hyakka-source-scout for ~11 hours (an interrupted run's uncommitted diff sat
in the shared worktree and every subsequent fire failed the same
`git checkout` refusal until someone found it by hand). Isolating this
family's worktree means an interrupted coverage-bot run cannot touch the
source/ontology bots' worktree, and vice versa.

THE EXIT CODE IS LOAD-BEARING IN ONE DIRECTION. Hermes injects this script's
stdout into the agent's prompt. An empty report reads as "there is nothing to
raise" -- the one answer that must never come from a failure. So on any
refusal this prints a REFUSED banner and still exits 0.
"""
import os
import subprocess
import sys

READ_ROOT = os.environ.get("HYAKKA_COVERAGE_READ_ROOT",
                           os.path.expanduser("~/github/com-junkawasaki"))
WORK_ROOT = os.environ.get("HYAKKA_COVERAGE_BOT_WORKTREE",
                           os.path.expanduser("~/.gftd/worktrees/hyakka-coverage-bot"))
NBB = os.environ.get("HYAKKA_COVERAGE_NBB", "/opt/homebrew/bin/nbb")


def refuse(why: str) -> None:
    print("REFUSED — no evidence was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A coverage proposal built on an unread "
          "tree is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)  # 0: the bot must RUN and be told it is blind, not be skipped.


def git(root: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(("git", "-C", root) + args,
                          capture_output=True, text=True, timeout=300)


def main() -> None:
    if not os.path.isdir(os.path.join(READ_ROOT, "orgs", "network-awai", "app-hyakka")):
        refuse(f"{READ_ROOT} has no orgs/network-awai/app-hyakka checkout")

    if not os.path.exists(os.path.join(WORK_ROOT, ".git")):
        refuse(f"no bot worktree at {WORK_ROOT}. Create it first: "
               f"git -C {READ_ROOT}/orgs/network-awai/app-hyakka worktree add "
               f"--detach {WORK_ROOT} origin/main")

    if git(WORK_ROOT, "fetch", "--quiet", "origin").returncode != 0:
        refuse("git fetch failed in the bot worktree")
    reset = git(WORK_ROOT, "checkout", "--quiet", "--detach", "origin/main")
    if reset.returncode != 0:
        refuse(f"could not move the bot worktree to origin/main: "
               f"{reset.stderr.strip()[:400]}")

    head = git(WORK_ROOT, "rev-parse", "--short", "HEAD").stdout.strip()

    gate = os.path.join(READ_ROOT, "scripts", "hermes-hyakka-coverage-bots",
                        "verify-coverage-proposal.cljs")
    proc = subprocess.run(
        [NBB, os.path.join(READ_ROOT, "scripts", "hermes-hyakka-coverage-bots",
                           "coverage_growth_evidence.cljs"),
         "--root", WORK_ROOT],
        cwd=WORK_ROOT, capture_output=True, text=True, timeout=120)

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
    print(f"gate\t{gate}")
    print(proc.stdout)


if __name__ == "__main__":
    main()
