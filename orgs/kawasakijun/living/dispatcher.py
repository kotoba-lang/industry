"""Pregel state → action emission.

Reads `reverse_topo_pregel.py` for the DAG, `repos.yaml` for the routing,
and the collected sensor state. Produces a list of `Action` objects to be
executed by `run.py`.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

# Allow importing reverse_topo_pregel from sibling directory
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from reverse_topo_pregel import GOALS, build_dag  # noqa: E402

from .actions import (  # noqa: E402
    Action,
    BlockCalendar,
    CreateIssue,
    DraftEmail,
    LogNote,
    existing_issues_for_label,
)

REPOS_YAML = Path(__file__).parent / "repos.yaml"


def load_repos() -> dict:
    return yaml.safe_load(REPOS_YAML.read_text())


def node_to_repo(node_id: str, repos_cfg: dict) -> dict | None:
    """Resolve which downstream repo handles a given DAG node."""
    for repo in repos_cfg["repos"]:
        if node_id in repo.get("handles_nodes", []):
            return repo
    return None


def is_local_node(node_id: str, repos_cfg: dict) -> bool:
    return node_id in repos_cfg.get("local_nodes", [])


def ready_nodes() -> list[str]:
    """Nodes whose predecessors are not yet handled and that can start."""
    dag = build_dag()
    # MVP: top-level start nodes (in-degree 0) only.
    return [n for n in GOALS if dag.in_degree(n) == 0]


def has_open_issue(repo_full_name: str, node_id: str) -> bool:
    issues = existing_issues_for_label(repo_full_name)
    needle = f"[{node_id}]"
    return any(needle in (i.get("title", "") or "") for i in issues)


def issue_payload_for(node_id: str, repo: dict) -> dict:
    g = GOALS[node_id]
    body = f"""## Auto-emitted by kawasakijun Living System

Top-level orchestrator: com-junkawasaki/kawasakijun

### Node
- **ID**: `{node_id}`
- **Title**: {g.title}
- **τ-scale**: {g.tau}
- **Domain**: {g.domain}
- **Estimated**: {g.est_years}y
- **W contribution**: {g.W_contribution}
{f"- **External dependency**: {g.external_dependency}" if g.external_dependency else ""}
{f"- **Fallback**: {g.fallback}" if g.fallback else ""}

### Request
Pick up this node. Implement / progress it in this repo. Tag this issue
as `completion: true` when done, or comment with progress updates.

### Tracking
This issue is tracked in `orgs/kawasakijun/living/.audit.jsonl` and
`orgs/kawasakijun/reverse_topo_pregel.py`.
"""
    return {
        "repo": repo["full_name"],
        "title": f"[{node_id}] {g.title}",
        "body": body,
        "labels": repo.get("issue_labels", []),
    }


def plan(state: dict) -> list[Action]:
    """Produce the action list for this Pregel super-step."""
    actions: list[Action] = []
    repos_cfg = load_repos()

    # Build set of nodes that already have known issues (from repos.yaml)
    known_existing: set[str] = set()
    for r in repos_cfg["repos"]:
        known_existing.update((r.get("existing_issues") or {}).keys())

    for node_id in ready_nodes():
        if node_id in known_existing:
            continue
        repo = node_to_repo(node_id, repos_cfg)
        if repo:
            if not has_open_issue(repo["full_name"], node_id):
                payload = issue_payload_for(node_id, repo)
                actions.append(CreateIssue(target=repo["full_name"], payload=payload))
        elif is_local_node(node_id, repos_cfg):
            actions.append(
                LogNote(
                    target="local",
                    payload={
                        "node": node_id,
                        "text": f"Local node ready: {GOALS[node_id].title}",
                    },
                )
            )

    # Always emit a daily summary as a log note
    actions.append(
        LogNote(
            target="local",
            payload={
                "text": f"Sensor state collected: {len(state)} keys",
                "state": state,
            },
        )
    )

    # Enforce per-run cap
    manifest_limit = 20
    return actions[:manifest_limit]
