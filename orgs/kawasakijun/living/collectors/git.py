"""Git activity collector (read-only)."""

from __future__ import annotations

import datetime as dt
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]


def commits_in_paths(paths: list[str], days: int = 7) -> int:
    """Count commits in the given paths within the last `days` days."""
    since = (dt.date.today() - dt.timedelta(days=days)).isoformat()
    try:
        out = subprocess.check_output(
            ["git", "-C", str(REPO), "log", f"--since={since}", "--oneline", "--"]
            + paths,
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError:
        return 0
    return sum(1 for line in out.splitlines() if line.strip())


def collect() -> dict:
    """Per-domain commit counts."""
    return {
        "physics.commits_30d": commits_in_paths(
            ["projects/2604-linde", "kawasakijun"], days=30
        ),
        "kawasakijun.commits_7d": commits_in_paths(["kawasakijun"], days=7),
        "lean.commits_30d": commits_in_paths(
            ["projects/2604-linde/*.lean"], days=30
        ),
    }


if __name__ == "__main__":
    import json
    print(json.dumps(collect(), indent=2, ensure_ascii=False))
