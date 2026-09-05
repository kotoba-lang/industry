#!/usr/bin/env python3
"""Thin launcher for the Hermes cron runner.

The runner executes .sh via bash and everything else via Python (ADR-2809042300),
so a .cljs script cannot be a cron `script:` on its own. The measurement itself is
nbb (.cljs) per the workspace script-host rule; this file only launches it and
passes the exit code through -- including exit 2, which means "could not answer".
"""
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = pathlib.Path(os.environ.get("PR_QUEUE_ROOT", "~/github/com-junkawasaki")).expanduser()
VERSIONED = ROOT / "scripts/hermes-pr-queue/pr_queue_scan.cljs"
SCAN = VERSIONED if VERSIONED.is_file() else HERE / "pr_queue_scan.cljs"


def main() -> int:
    if not SCAN.is_file():
        print("REFUSED\tpr_queue_scan.cljs is absent; the queue was not measured")
        return 2
    try:
        r = subprocess.run(["nbb", str(SCAN), *sys.argv[1:]], text=True, timeout=900)
    except FileNotFoundError:
        print("REFUSED\tnbb is not on PATH; the queue was not measured")
        return 2
    except subprocess.TimeoutExpired:
        print("REFUSED\tscan timed out after 900s; the queue was not measured")
        return 2
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())
