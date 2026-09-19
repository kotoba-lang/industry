#!/usr/bin/env python3
"""Thin launcher for the Hermes cron runner.

The runner executes .sh via bash and everything else via Python (ADR-2809042300),
so a .cljk script cannot be a cron `script:` on its own. This file only launches
it via kbb (the only engine that resolves .cljk requires, ADR-2609111700) and
passes the exit code through -- including exit 2, which means "could not answer".
"""
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = pathlib.Path(os.environ.get("OPENROUTER_GATES_ROOT", "~/github/com-junkawasaki")).expanduser()
SCAN = ROOT / "scripts/hermes-openrouter-gates/progress_scan.cljk"
FALLBACK = HERE / "progress_scan.cljk"


def main() -> int:
    scan = SCAN if SCAN.is_file() else FALLBACK
    if not scan.is_file():
        print("REFUSED\tprogress_scan.cljk is absent; gates were not measured")
        return 2
    try:
        r = subprocess.run(
            ["kbb", "--backend", "sci", str(scan), *sys.argv[1:]],
            text=True,
            timeout=120,
            cwd=str(ROOT) if ROOT.is_dir() else None,
        )
    except FileNotFoundError:
        print("REFUSED\tkbb is not on PATH; gates were not measured")
        return 2
    except subprocess.TimeoutExpired:
        print("REFUSED\tscan timed out after 120s; gates were not measured")
        return 2
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())
