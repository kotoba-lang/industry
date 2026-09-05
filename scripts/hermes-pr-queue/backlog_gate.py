#!/usr/bin/env python3
"""Producer-side open-PR backlog gate.

A producing bot that opens one PR per hour into a repository nothing drains does
not produce work -- it produces a queue. Measured 2026-09-05: cloud-itonami/otent
carried 22 open PRs, 21 of them CONFLICTING, because six hourly jobs each branched
from main and touched the same files while the drain bots were being killed
mid-run.

This gate is measured, not advisory: it counts the bot's own open PRs in the
target repository and, over cap, tells the run to drain one instead of opening
another. It decides nothing else and never merges, closes or writes.

Fail-closed on purpose: a count that could not be taken prints `status=unknown`
and blocks the same way `over` does. "Could not measure the queue" must not read
the same as "the queue is short" (ADR-2608136000).

Use from a producer's evidence script:

    from backlog_gate import print_gate
    blocked = print_gate("cloud-itonami/otent")     # True -> this run must not open a PR
"""
from __future__ import annotations

import datetime as dt
import json
import os
import subprocess
from typing import List, Tuple

DEFAULT_CAP = int(os.environ.get("PR_BACKLOG_CAP", "5"))
AUTHOR = os.environ.get("PR_BACKLOG_AUTHOR", "com-junkawasaki")
# Only PRs older than this count against the cap. A burst is not a backlog: measured
# 2026-09-05, network-awai/app-hyakka held 13 open PRs at one moment and merged over
# 100 in the surrounding 24 hours, while cloud-itonami/otent held 22 whose median age
# was days. Counting open PRs alone would have throttled the healthy loop and the
# stuck one identically.
STALE_HOURS = float(os.environ.get("PR_BACKLOG_STALE_HOURS", "6"))


def _gh(args: List[str], timeout: int = 90) -> Tuple[bool, str]:
    try:
        r = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as e:
        return False, str(e)[:200]
    if r.returncode != 0:
        return False, (r.stderr or "").strip()[:200]
    return True, r.stdout


def measure(repo: str, cap: int = DEFAULT_CAP) -> dict:
    """{'status': over|under|unknown, 'open': n|None, 'cap': cap, 'prs': [...], 'why': str}

    The repository is resolved first, on purpose. Measured 2026-09-05:
    `gh pr list --repo <missing> --author X --json ...` exits 0 and prints `[]`
    -- adding --author routes the query through search, which reports an
    unreachable repository as "no open PRs". Without this probe an unreachable
    repo (renamed, deleted, or outside the token's reach) reads exactly like a
    short queue and the gate opens.
    """
    ok, out = _gh(["api", f"repos/{repo}", "--jq", ".full_name"])
    if not ok:
        return {"status": "unknown", "open": None, "cap": cap, "prs": [],
                "why": f"repository did not resolve: {out or 'gh failed'}"}
    ok, out = _gh([
        "pr", "list", "--repo", repo, "--author", AUTHOR, "--state", "open",
        "--limit", "100", "--json", "number,createdAt,mergeable,isDraft,title",
    ])
    if not ok:
        return {"status": "unknown", "open": None, "cap": cap, "prs": [], "why": out or "gh failed"}
    try:
        prs = json.loads(out)
    except json.JSONDecodeError:
        return {"status": "unknown", "open": None, "cap": cap, "prs": [], "why": "unparseable gh output"}
    prs.sort(key=lambda p: p.get("createdAt") or "")
    cutoff = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=STALE_HOURS)).isoformat()
    stale = [p for p in prs if (p.get("createdAt") or "9999") < cutoff]
    return {
        "status": "over" if len(stale) >= cap else "under",
        "open": len(prs),
        "stale": len(stale),
        "stale_hours": STALE_HOURS,
        "cap": cap,
        "prs": stale or prs,
        "why": "",
    }


def print_gate(repo: str, cap: int = DEFAULT_CAP, show: int = 8) -> bool:
    """Print the gate block. Returns True when this run must NOT open a new PR."""
    m = measure(repo, cap)
    print(f"BACKLOG repo={repo} open={m['open'] if m['open'] is not None else 'unknown'} "
          f"stale={m.get('stale', 'unknown')} (older than {m.get('stale_hours', STALE_HOURS)}h) "
          f"cap={m['cap']} status={m['status']}")
    if m["status"] == "under":
        return False
    if m["status"] == "unknown":
        print(f"BACKLOG-BLOCK the open-PR count could not be measured ({m['why']}); "
              "unknown is not under. Do not open a new PR this run.")
        return True
    print("BACKLOG-BLOCK this repository is at or over its cap of PRs that nobody has drained. "
          "Do NOT open a new PR "
          "this run. Drain exactly one of the PRs below instead: merge origin/main INTO the PR "
          "branch (never rebase, never force-push), run the repo's own suite, and merge it; or, "
          "if its content is already in main, close it with the containment evidence. Report what "
          "you drained.")
    for p in m["prs"][:show]:
        print(f"BACKLOG-PR {repo}#{p['number']} created={(p.get('createdAt') or '')[:10]} "
              f"mergeable={p.get('mergeable')} draft={'yes' if p.get('isDraft') else 'no'} "
              f"title={(p.get('title') or '')[:70]}")
    return True


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print("usage: backlog_gate.py <owner/repo> [cap]")
        raise SystemExit(64)
    cap = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_CAP
    raise SystemExit(0 if not print_gate(sys.argv[1], cap) else 1)
