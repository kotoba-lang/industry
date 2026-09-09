#!/usr/bin/env python3
"""Decision-free measurements for the worldwide legal Hyakka bot family."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import os
from pathlib import Path
import subprocess
import sys

READ_ROOT = Path(os.environ.get("GLOBAL_LEGAL_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
SCOPE_ROOT = READ_ROOT / "scripts/hermes-global-legal-bots"
INSTALLED_ROOT = Path(__file__).resolve().parent
SCOPE_FILE = (SCOPE_ROOT / "world-legal-scope.edn"
              if (SCOPE_ROOT / "world-legal-scope.edn").is_file()
              else INSTALLED_ROOT / "world-legal-scope.edn")
HYAKKA = READ_ROOT / "orgs/network-awai/app-hyakka"
WORKTREES = {
    "schema": Path("~/.itonami/worktrees/legal-world-schema-bot").expanduser(),
    "legislation": Path("~/.itonami/worktrees/legal-legislation-source-bot").expanduser(),
    "cases": Path("~/.itonami/worktrees/legal-cases-source-bot").expanduser(),
    "profession": Path("~/.itonami/worktrees/legal-profession-source-bot").expanduser(),
    "news": Path("~/.itonami/worktrees/legal-news-source-bot").expanduser(),
}

TOKENS = {
    "schema": ["world/class/law", "bill", "jurisdiction", "case-number", "docket-number",
               "lawyer", "judge", "legal-news-report"],
    "legislation": ["official-legislature", "official-gazette", "official-identifier", "effective-at"],
    "cases": ["official-court", "case-number", "docket-number", "procedural-posture", "disposition"],
    "profession": ["official-bar", "public-register-id", "lawyer", "judge", "judicial-office"],
    "news": ["legal-news", "publisher", "headline", "published-at", "byline"],
}


def run(args: list[str], cwd: Path, timeout: int = 300) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=timeout, check=False)


def refuse(message: str) -> int:
    print("REFUSED — no legal-corpus decision may be made from this run.")
    print(message)
    print("Report the refusal and stop without changing files or opening a PR.")
    return 0


def tracked_text(root: Path) -> str:
    files = run(["git", "ls-files", "src", "config", "test"], root, 60)
    if files.returncode != 0:
        return ""
    chunks: list[str] = []
    for relative in files.stdout.splitlines():
        if relative.endswith((".clj", ".cljc", ".cljs", ".edn", ".md")) and "catalog.cljc" not in relative:
            path = root / relative
            try:
                chunks.append(path.read_text(errors="replace"))
            except OSError:
                pass
    return "\n".join(chunks)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("scope", choices=tuple(WORKTREES))
    args = parser.parse_args()
    worktree = WORKTREES[args.scope]

    print("GLOBAL_LEGAL_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    print(f"scope={args.scope}")
    print(f"read_root={READ_ROOT} (read only)")
    print(f"worktree={worktree}")
    if not SCOPE_FILE.is_file():
        return refuse("world-legal-scope.edn is absent")
    if not (HYAKKA / ".git").exists():
        return refuse(f"Hyakka checkout is absent at {HYAKKA}")
    if not (worktree / ".git").exists():
        return refuse(f"dedicated bot worktree is absent at {worktree}")

    status = run(["git", "status", "--porcelain"], worktree, 60)
    if status.returncode != 0 or status.stdout.strip():
        return refuse("dedicated bot worktree is unreadable or dirty; preserve it for inspection")
    fetched = run(["git", "fetch", "--quiet", "origin"], worktree)
    if fetched.returncode != 0:
        return refuse("git fetch origin failed in the dedicated bot worktree")
    moved = run(["git", "checkout", "--quiet", "--detach", "origin/main"], worktree)
    if moved.returncode != 0:
        return refuse("could not synchronize the dedicated bot worktree to origin/main")

    head = run(["git", "rev-parse", "--short=12", "HEAD"], worktree, 60).stdout.strip()
    body = tracked_text(worktree)
    if not body:
        return refuse("no tracked Hyakka source/config/test text could be measured")
    digest = hashlib.sha256(SCOPE_FILE.read_bytes()).hexdigest()
    print(f"scope_sha256={digest}")
    print(f"hyakka_head={head}")
    for token in TOKENS[args.scope]:
        print(f"TOKEN name={token} present={'yes' if token.lower() in body.lower() else 'no'}")
    legal_files = run(["git", "ls-files", "src/hyakka/corpus", "test/hyakka"], worktree, 60)
    names = [p for p in legal_files.stdout.splitlines()
             if any(x in p.lower() for x in ("law", "legal", "court", "case", "judge", "lawyer"))]
    print(f"LEGAL_FILES count={len(names)} sample={names[:20]}")
    print("END_GLOBAL_LEGAL_EVIDENCE_V1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
