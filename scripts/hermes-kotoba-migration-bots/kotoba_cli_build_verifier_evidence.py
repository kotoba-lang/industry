#!/usr/bin/env python3
"""Select one open build-cutover bot PR for independent verification."""
from __future__ import annotations

import json
import subprocess


def main() -> None:
    proc = subprocess.run(
        ["gh", "search", "prs",
         '"[bot][kotoba-cli-build]" in:title org:kotoba-lang is:open',
         "--limit", "20", "--json", "repository,title,url,updatedAt"],
        capture_output=True, text=True, timeout=120, check=False)
    if proc.returncode:
        print("REFUSED — GitHub PR search failed.")
        print((proc.stderr or proc.stdout)[-1200:])
        print("Do not guess a PR. Report and stop.")
        return
    try:
        prs = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        print(f"REFUSED — GitHub PR search returned invalid JSON: {exc}")
        return
    if not prs:
        print("SCANNED\t0 open [bot][kotoba-cli-build] PRs")
        print("No PR needs independent verification. Do not invent work.")
        return
    chosen = sorted(prs, key=lambda pr: pr.get("updatedAt", ""))[0]
    repo = chosen.get("repository", {}).get("nameWithOwner", "")
    print(f"SCANNED\t{len(prs)} open [bot][kotoba-cli-build] PRs")
    print(f"CHOSEN\t{repo}\t{chosen.get('url', '')}")
    print(f"TITLE\t{chosen.get('title', '')}")
    print("run-parent\t~/.itonami/worktrees/kotoba-cli-build-verifier\t(use a fresh clone)")
    print("kotoba-cli\t/opt/homebrew/opt/kotoba/bin/kotoba")
    print("amu-bin\t~/github/com-junkawasaki/orgs/kotoba-lang/amu/bin/amu")


if __name__ == "__main__":
    main()
