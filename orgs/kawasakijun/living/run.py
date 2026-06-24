"""Living System entry point. Cron-friendly. Defaults to --dry-run.

Usage:
  python kawasakijun/living/run.py                # dry-run
  python kawasakijun/living/run.py --execute      # actually emit actions
  python kawasakijun/living/run.py --collect-only # print sensor state only
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Make `kawasakijun` package importable
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from kawasakijun.living.collectors import collect_all  # noqa: E402
from kawasakijun.living.dispatcher import plan  # noqa: E402
from kawasakijun.living.actions import audit  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    g = p.add_mutually_exclusive_group()
    g.add_argument("--dry-run", action="store_true", default=True,
                   help="Plan actions but don't execute (default)")
    g.add_argument("--execute", action="store_true",
                   help="Actually execute actions")
    g.add_argument("--collect-only", action="store_true",
                   help="Print sensor state and exit")
    args = p.parse_args()

    dry_run = not args.execute

    print("# kawasakijun Living System")
    print(f"mode: {'EXECUTE' if not dry_run else 'DRY-RUN'}\n")

    print("## Step 1: collect sensor state")
    state = collect_all()
    print(json.dumps(state, indent=2, ensure_ascii=False))

    if args.collect_only:
        return

    print("\n## Step 2: plan actions")
    actions = plan(state)
    print(f"-> {len(actions)} action(s) planned\n")

    print("## Step 3: execute")
    for a in actions:
        result = a.execute(dry_run=dry_run)
        audit(a, result)
        print(f"- [{a.type}] target={a.target!r} -> {result}")


if __name__ == "__main__":
    main()
