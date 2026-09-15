#!/usr/bin/env python3
"""Launcher for the pr-drain hermes profile: pr_queue_scan over ALL authors.

Same launcher as pr_queue_scan.py, with the drain's defaults baked in because a
cron `script:` takes no arguments: every author (bots, dependabot, agents), a
bounded worklist of 15, and the workspace script host (kbb). Exit 2 passes
through and means "could not answer".
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(os.environ.get("PR_QUEUE_ROOT", "~/github/com-junkawasaki")).expanduser()
SCAN = ROOT / "scripts/hermes-pr-queue/pr_queue_scan.cljk"


def main() -> int:
    if not SCAN.is_file():
        print("REFUSED\tpr_queue_scan.cljk is absent; the queue was not measured")
        return 2
    args = ["kbb", "--backend", "sci", str(SCAN), "--author", "any", "--work", "15", *sys.argv[1:]]
    try:
        r = subprocess.run(args, text=True, timeout=900)
    except FileNotFoundError:
        print("REFUSED\tkbb is not on PATH; the queue was not measured")
        return 2
    except subprocess.TimeoutExpired:
        print("REFUSED\tscan timed out after 900s; the queue was not measured")
        return 2
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())
