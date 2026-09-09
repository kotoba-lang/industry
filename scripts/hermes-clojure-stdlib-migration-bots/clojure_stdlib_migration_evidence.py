#!/usr/bin/env python3
"""Hand the clojure-stdlib-migration-scout bot its one candidate for this run.

Decision-free, like scripts/hermes-kotoba-migration-bots/kotoba_migration_evidence.py
(which this is deliberately modeled on — same shape, DIFFERENT program): runs
scripts/hermes-clojure-stdlib-migration-bots/candidates.cljs against the
populated superproject and prints what that produced. Every rule about what
counts as a candidate — meta-repo, :local/root, already-in-flight,
recently-proposed, deliberately-skipped — lives in that script; none of it
is repeated here.

This bot rewires an existing (:require [clojure.<ns> ...]) to its
kotoba-lang.* replacement (adr-2809061500-clojure-namespace-to-kotoba-stdlib).
It is NOT the kotoba-migration-scout bot (which moves .clj/.cljc product
semantics to .kotoba/.cljk, ADR-2607279200/2608261100) — different domain,
different candidate set, different seen-ledger, so the two families never
claim the same file.

ONE ROOT, NOT TWO, same reasoning as kotoba_migration_evidence.py: this
bot's candidates come from ~2,000 different kotoba-lang repos, a fresh one
each run. There is no single repo to keep a persistent worktree of, so
there is nothing to reuse across runs and nothing to leave dirty for the
next run to trip over. This script only reads (READ_ROOT, the populated
superproject, read-only) and names a scratch parent directory
(RUN_PARENT) under which the AGENT clones a throwaway, timestamped copy of
whichever repo candidates.cljs picked.

THE EXIT CODE IS LOAD-BEARING IN ONE DIRECTION. Hermes injects this
script's stdout into the agent's prompt. An empty or half-written report
reads as "there is nothing to rewire", which must never be the silent
result of a failure. So on any refusal this prints a REFUSED banner and
still exits 0 — the bot has to run and be told it is blind, not be
silently skipped.
"""
import os
import subprocess
import sys

READ_ROOT = os.environ.get("CLOJURE_STDLIB_READ_ROOT",
                           os.path.expanduser("~/github/com-junkawasaki"))
RUN_PARENT = os.environ.get("CLOJURE_STDLIB_RUN_PARENT",
                            os.path.expanduser("~/.itonami/worktrees/clojure-stdlib-migration-bot"))
NBB = os.environ.get("CLOJURE_STDLIB_NBB", "/opt/homebrew/bin/nbb")
GATE = os.environ.get(
    "CLOJURE_STDLIB_GATE",
    os.path.expanduser("~/.hermes/scripts/verify-clojure-stdlib-migration.cljs"))


def refuse(why: str) -> None:
    print("REFUSED — no candidate was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A rewire proposal built on an unread "
          "tree is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)  # 0: the bot must RUN and be told it is blind, not be skipped.


def main() -> None:
    if not os.path.isdir(os.path.join(READ_ROOT, "orgs", "kotoba-lang")):
        refuse(f"{READ_ROOT} has no populated orgs/kotoba-lang/ — there is "
               f"nothing to scan for candidates.")
    if not os.path.isfile(GATE):
        refuse(f"installed rewire gate is missing: {GATE}")

    os.makedirs(RUN_PARENT, exist_ok=True)

    proc = subprocess.run(
        [NBB, f"{READ_ROOT}/scripts/hermes-clojure-stdlib-migration-bots/candidates.cljs",
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
    print("adr\t90-docs/adr/2809061500-clojure-namespace-to-kotoba-stdlib.edn"
          "\t(read :adr/consequences for what has already landed this session"
          " — do not repeat a wave that is already recorded as merged)")
    print("proposal-adr\t90-docs/adr/2809070100-clojure-java-io-capability-boundary-proposal.edn"
          "\t(clojure.java.io — status 'proposed', NOT accepted. Never attempt this namespace.)")
    print(proc.stdout)


if __name__ == "__main__":
    main()
