#!/usr/bin/env python3
"""Thin launcher for the Hermes cron runner (branch-drain profile).

The runner executes .sh via bash and everything else via Python, so the .cljk
measurement cannot be a cron `script:` on its own. This launches the versioned
scan through kbb (the workspace script host, ADR-2609112000) and passes the exit
code through -- including exit 2, which means "could not answer".

The cursor file lives in the profile so successive runs rotate through the roster.
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(os.environ.get("PR_QUEUE_ROOT", "~/github/com-junkawasaki")).expanduser()
SCAN = ROOT / "scripts/hermes-pr-queue/branch_queue_scan.cljk"
PROFILE = pathlib.Path(os.environ.get("HERMES_PROFILE_DIR", "~/.hermes/profiles/branch-drain")).expanduser()


def main() -> int:
    if not SCAN.is_file():
        print("REFUSED\tbranch_queue_scan.cljk is absent; the queue was not measured")
        return 2
    cursor = PROFILE / "cursor.edn"
    args = ["kbb", "--backend", "sci", str(SCAN), "--root", str(ROOT), "--cursor-file", str(cursor), *sys.argv[1:]]
    try:
        r = subprocess.run(args, text=True, timeout=1200)
    except FileNotFoundError:
        print("REFUSED\tkbb is not on PATH; the queue was not measured")
        return 2
    except subprocess.TimeoutExpired:
        print("REFUSED\tscan timed out after 1200s; the queue was not measured")
        return 2
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())
