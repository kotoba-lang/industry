#!/usr/bin/env python3
"""Select one measured JVM-build repo for a Kotoba CLI cutover run.

The workspace's authoritative detector decides which repositories still use a
JVM build toolchain.  This wrapper only ranks those measured findings, skips
recent/in-flight work, and prints a fresh-clone destination for the agent.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

READ_ROOT = Path(os.environ.get(
    "KOTOBA_CLI_BUILD_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
RUN_PARENT = Path(os.environ.get(
    "KOTOBA_CLI_BUILD_RUN_PARENT",
    "~/.itonami/worktrees/kotoba-cli-build-bot")).expanduser()
STATE_PATH = Path(os.environ.get(
    "KOTOBA_CLI_BUILD_STATE",
    "~/.itonami/hermes-kotoba-cli-build-bot/seen.json")).expanduser()
NBB = os.environ.get("KOTOBA_CLI_BUILD_NBB", "/opt/homebrew/bin/nbb")
TTL_SECONDS = 14 * 24 * 60 * 60
DRY_RUN = os.environ.get("KOTOBA_CLI_BUILD_DRY_RUN") == "1"
FROZEN_BOOTSTRAP = {
    "orgs/kotoba-lang/kotoba",
    "orgs/kotoba-lang/amu",
    "orgs/kotoba-lang/kototama",
    "orgs/kotoba-lang/aiueos",
}


def refuse(why: str) -> None:
    print("REFUSED — no JVM-build candidate was gathered this run.")
    print(why)
    print("Do not edit a repository or invent a candidate. Report and stop.")
    sys.exit(0)


def run(args: list[str], cwd: Path, timeout: int = 600) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True,
                          timeout=timeout, check=False)


def load_seen() -> dict[str, float]:
    try:
        value = json.loads(STATE_PATH.read_text())
        return value if isinstance(value, dict) else {}
    except FileNotFoundError:
        return {}
    except (json.JSONDecodeError, OSError) as exc:
        refuse(f"seen ledger {STATE_PATH} is unreadable: {exc}")
        return {}


def github_repo(repo_path: Path) -> str | None:
    proc = run(["git", "-C", str(repo_path), "remote", "get-url", "origin"], READ_ROOT, 30)
    if proc.returncode:
        return None
    match = re.search(r"github\.com[/:]([^/]+/[^/]+?)(?:\.git)?\s*$", proc.stdout)
    return match.group(1) if match else None


def in_flight(repo: str) -> tuple[bool, str]:
    proc = run(["gh", "pr", "list", "--repo", repo, "--state", "open",
                "--search", "[bot][kotoba-cli-build] in:title",
                "--json", "title,url", "--limit", "20"], READ_ROOT, 60)
    if proc.returncode:
        return False, "UNKNOWN: gh could not check open PRs"
    try:
        prs = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return False, "UNKNOWN: gh returned invalid JSON"
    if prs:
        return True, prs[0].get("url", "open bot PR")
    return False, "clear"


def source_counts(repo_path: Path) -> tuple[int, int]:
    proc = run(["rg", "--files", str(repo_path), "-g", "*.kotoba", "-g", "*.cljk"], READ_ROOT, 60)
    kotoba = len([line for line in proc.stdout.splitlines() if line.strip()]) if proc.returncode in (0, 1) else 0
    proc = run(["rg", "--files", str(repo_path), "-g", "*.cljc", "-g", "*.cljs"], READ_ROOT, 60)
    portable = len([line for line in proc.stdout.splitlines() if line.strip()]) if proc.returncode in (0, 1) else 0
    return kotoba, portable


def main() -> None:
    detector = READ_ROOT / "scripts" / "verify-jvm-dependency-surface.cljs"
    if not detector.is_file():
        refuse(f"authoritative detector missing: {detector}")
    RUN_PARENT.mkdir(parents=True, exist_ok=True)

    proc = run([NBB, str(detector), "--findings", "--kind", "jvm-build"], READ_ROOT, 900)
    if proc.returncode not in (0, 1):
        refuse(f"JVM surface detector exited {proc.returncode}:\n{(proc.stderr or proc.stdout)[-1600:]}")
    if "SCANNED\t" not in proc.stdout:
        refuse("JVM surface detector produced no SCANNED line")

    measured: list[tuple[str, str]] = []
    for line in proc.stdout.splitlines():
        match = re.match(r"FINDING\twarn\tjvm-build:(\S+)\t(.+)", line)
        if match:
            measured.append((match.group(1), match.group(2)))
    if not measured:
        print("SCANNED\t0 JVM-build findings. No candidate this run.")
        return

    seen = load_seen()
    now = time.time()
    ranked: list[tuple[int, str, str, str, int, int, str]] = []
    skipped: list[str] = []
    for rel, detail in measured:
        if rel in FROZEN_BOOTSTRAP:
            skipped.append(f"{rel}: frozen bootstrap cutover; requires architectural review")
            continue
        if now - float(seen.get(rel, 0)) < TTL_SECONDS:
            skipped.append(f"{rel}: recently selected")
            continue
        repo_path = READ_ROOT / rel
        remote = github_repo(repo_path)
        if not remote:
            skipped.append(f"{rel}: no GitHub origin")
            continue
        flight, note = in_flight(remote)
        if flight:
            skipped.append(f"{rel}: already in flight {note}")
            continue
        kotoba, portable = source_counts(repo_path)
        # Repos already containing Kotoba are lowest-risk cutovers, followed by
        # portable CLJC/CLJS repos. Stable path ordering breaks ties.
        score = (0 if kotoba else 1000) + (0 if portable else 500) - min(kotoba, 100) * 4 - min(portable, 100)
        ranked.append((score, rel, remote, detail, kotoba, portable, note))

    if not ranked:
        print(f"SCANNED\t{len(measured)} JVM-build repos; no unclaimed candidate.")
        for item in skipped[:8]:
            print(f"SKIPPED\t{item}")
        return

    _, rel, remote, detail, kotoba, portable, note = sorted(ranked)[0]
    if not DRY_RUN:
        STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
        seen[rel] = now
        STATE_PATH.write_text(json.dumps(seen, sort_keys=True, indent=2) + "\n")

    print(f"SCANNED\t{len(measured)} repos with measured :jvm-build debt")
    print(f"CHOSEN\t{rel}\t{remote}")
    print(f"DETAIL\t{detail}")
    print(f"SOURCE_COUNTS\tkotoba/cljk={kotoba}\tcljc/cljs={portable}")
    print(f"OPEN_PR_CHECK\t{note}")
    print(f"read-root\t{READ_ROOT}\t(read only)")
    print(f"run-parent\t{RUN_PARENT}\t(always use a fresh timestamped clone)")
    print("kotoba-cli\t/opt/homebrew/opt/kotoba/bin/kotoba")
    print(f"amu-bin\t{READ_ROOT / 'orgs/kotoba-lang/amu/bin/amu'}")


if __name__ == "__main__":
    main()
