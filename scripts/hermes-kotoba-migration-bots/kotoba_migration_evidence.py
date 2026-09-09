#!/usr/bin/env python3
"""Hand the kotoba-migration-scout bot its one candidate for this run.

Decision-free, like hyakka_evidence.py and itonami_evidence.py: this runs
`scripts/hermes-kotoba-migration-bots/candidates.cljs` against the populated
superproject and prints what that produced. Every rule about what counts as a
candidate — already migrated, host-mechanism path, too big, already proposed
— lives in that script; none of it is repeated here.

ONE ROOT, NOT TWO — and that is the point, not an oversight.

hyakka_evidence.py and itonami_evidence.py each sync a FIXED worktree of ONE
target repo before handing off, because their bots always edit the same repo.
This bot's candidates come from ~2,000 different kotoba-lang repos, chosen a
fresh one each run — there is no single repo to keep a persistent worktree
of. So this script only reads (READ_ROOT, the populated superproject,
read-only, same as the other two) and names a scratch parent directory
(RUN_PARENT) under which the AGENT clones a throwaway, timestamped copy of
whichever repo candidates.cljs picked. Nothing here shares that copy across
runs, and nothing here deletes it either.

That "nothing shares it" is not a stylistic choice — it is the fix for a bug
this family already had once. hyakka's shared bot worktree sat on an
uncommitted diff from an interrupted run for ~11 hours, and every scheduled
fire after it failed with the exact same "your local changes would be
overwritten" error until someone noticed and cleaned it up by hand (see
scripts/hermes-hyakka-bots's incident notes). A fresh clone per run cannot
develop that failure mode: an interrupted migration run leaves its own
directory dirty and abandoned, and the NEXT run gets a brand new one. The
cost is a `git clone` per run instead of a `git fetch`; for a single repo
that is seconds, and it buys structural immunity to the failure that actually
happened here once already.

THE EXIT CODE IS LOAD-BEARING IN ONE DIRECTION.

Hermes injects this script's stdout into the agent's prompt. An empty or
half-written report reads as "there is nothing to migrate", which is the one
answer that must never come from a failure. So on any refusal this prints a
REFUSED banner and still exits 0 — the bot has to run and be told it is
blind, not be silently skipped.
"""
import os
import subprocess
import sys

READ_ROOT = os.environ.get("KOTOBA_MIGRATION_READ_ROOT",
                           os.path.expanduser("~/github/com-junkawasaki"))
RUN_PARENT = os.environ.get("KOTOBA_MIGRATION_RUN_PARENT",
                            os.path.expanduser("~/.itonami/worktrees/kotoba-migration-bot"))
NBB = os.environ.get("KOTOBA_MIGRATION_NBB", "/opt/homebrew/bin/nbb")
GATE = os.environ.get(
    "KOTOBA_MIGRATION_GATE",
    os.path.expanduser("~/.hermes/scripts/verify-kotoba-migration.cljs"))


def refuse(why: str) -> None:
    print("REFUSED — no candidate was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A migration proposal built on an unread "
          "tree is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)  # 0: the bot must RUN and be told it is blind, not be skipped.


def main() -> None:
    if not os.path.isdir(os.path.join(READ_ROOT, "orgs", "kotoba-lang")):
        refuse(f"{READ_ROOT} has no populated orgs/kotoba-lang/ — there is "
               f"nothing to scan for candidates.")
    if not os.path.isfile(GATE):
        refuse(f"installed migration gate is missing: {GATE}")

    os.makedirs(RUN_PARENT, exist_ok=True)

    proc = subprocess.run(
        [NBB, f"{READ_ROOT}/scripts/hermes-kotoba-migration-bots/candidates.cljs",
         "--root", READ_ROOT, "--top", "1"],
        cwd=READ_ROOT, capture_output=True, text=True, timeout=600)

    if proc.returncode == 2:
        refuse("the candidate scanner refused:\n" +
               (proc.stdout.strip() or proc.stderr.strip())[:1200])
    if proc.returncode != 0:
        refuse(f"the candidate scanner exited {proc.returncode}:\n" +
               (proc.stderr.strip() or proc.stdout.strip())[:1200])
    if "SCANNED\t" not in proc.stdout:
        refuse("the scanner produced no SCANNED line, so its output cannot be "
               "distinguished from a truncated run")

    print(f"read-root\t{READ_ROOT}\t(read only — never edit inside this tree)")
    print(f"run-parent\t{RUN_PARENT}\t(clone the chosen repo into a FRESH "
          f"timestamped subdirectory here — never reuse a directory across runs)")
    print(f"amu-bin\t{os.path.join(READ_ROOT, 'orgs', 'kotoba-lang', 'amu', 'bin', 'amu')}")
    print(f"gate\t{GATE}")
    print(proc.stdout)


if __name__ == "__main__":
    main()
