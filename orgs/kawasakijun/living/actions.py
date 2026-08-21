"""Write-only action emitters.

The dispatcher decides which actions to emit; actions here actually call out
to external systems (gh CLI, gcalendar, etc.). All actions support `dry_run`.
"""

from __future__ import annotations

import datetime as dt
import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
AUDIT_LOG = REPO / "kawasakijun" / "living" / ".audit.jsonl"


@dataclass
class Action:
    type: str
    payload: dict[str, Any] = field(default_factory=dict)
    target: str = ""  # repo or local

    def execute(self, *, dry_run: bool = True) -> dict:
        raise NotImplementedError


@dataclass
class CreateIssue(Action):
    type: str = "create_issue"

    def execute(self, *, dry_run: bool = True) -> dict:
        p = self.payload
        cmd = [
            "gh", "issue", "create",
            "--repo", p["repo"],
            "--title", p["title"],
            "--body", p["body"],
        ]
        for label in p.get("labels", []) + ["kawasakijun-orchestrated"]:
            cmd += ["--label", label]
        if dry_run:
            return {"dry_run": True, "would_run": cmd[:6] + ["..."]}
        out = subprocess.check_output(cmd, text=True).strip()
        return {"created": out}


@dataclass
class BlockCalendar(Action):
    type: str = "block_calendar"

    def execute(self, *, dry_run: bool = True) -> dict:
        # Stub. Real impl uses gcal MCP or `gcalcli` CLI.
        if dry_run:
            return {"dry_run": True, "title": self.payload.get("title")}
        return {"todo": "gcal integration pending"}


@dataclass
class DraftEmail(Action):
    type: str = "draft_email"

    def execute(self, *, dry_run: bool = True) -> dict:
        # Stub. Real impl uses Gmail MCP or `gam` CLI.
        if dry_run:
            return {"dry_run": True, "to": self.payload.get("to")}
        return {"todo": "gmail integration pending"}


@dataclass
class LogNote(Action):
    """Local note for the human to read."""
    type: str = "log_note"

    def execute(self, *, dry_run: bool = True) -> dict:
        return {"note": self.payload.get("text")}


def audit(action: Action, result: dict) -> None:
    AUDIT_LOG.parent.mkdir(parents=True, exist_ok=True)
    entry = {
        "ts": dt.datetime.now(dt.timezone.utc).isoformat(),
        "type": action.type,
        "target": action.target,
        "payload": action.payload,
        "result": result,
    }
    with AUDIT_LOG.open("a") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def existing_issues_for_label(repo: str, label: str = "kawasakijun-orchestrated") -> list[dict]:
    """Idempotency: list open issues we previously created."""
    try:
        out = subprocess.check_output(
            ["gh", "issue", "list",
             "--repo", repo,
             "--label", label,
             "--state", "open",
             "--json", "number,title"],
            text=True,
        )
        return json.loads(out)
    except subprocess.CalledProcessError:
        return []
