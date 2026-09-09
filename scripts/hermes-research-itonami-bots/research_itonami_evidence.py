#!/usr/bin/env python3
"""Decision-free measurements for the research ecosystem Hermes bots."""
from __future__ import annotations
import argparse, datetime as dt, hashlib, os, subprocess
from pathlib import Path

READ_ROOT = Path(os.environ.get("RESEARCH_ITONAMI_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
HERE = Path(__file__).resolve().parent
VERSIONED = READ_ROOT / "scripts/hermes-research-itonami-bots/research-scope.edn"
SCOPE_FILE = VERSIONED if VERSIONED.is_file() else HERE / "research-scope.edn"
HYAKKA = READ_ROOT / "orgs/network-awai/app-hyakka"
ROOT_REMOTE = READ_ROOT
CONFIG = {
 "schema": (HYAKKA, Path("~/.itonami/worktrees/research-world-schema-bot").expanduser()),
 "research": (HYAKKA, Path("~/.itonami/worktrees/research-source-bot").expanduser()),
 "events": (HYAKKA, Path("~/.itonami/worktrees/research-events-bot").expanduser()),
 "funding": (HYAKKA, Path("~/.itonami/worktrees/research-funding-bot").expanduser()),
 "impact": (HYAKKA, Path("~/.itonami/worktrees/research-impact-bot").expanduser()),
 "itonami-collector": (ROOT_REMOTE, Path("~/.itonami/worktrees/itonami-research-collector-bot").expanduser()),
 "itonami-impact": (ROOT_REMOTE, Path("~/.itonami/worktrees/itonami-research-impact-bot").expanduser()),
}
TOKENS = {
 "schema": ["research-work", "research-project", "scholarly-society", "event-edition", "funding-award", "impact-observation"],
 "research": ["doi", "orcid", "ror", "dataset", "preprint", "peer-review"],
 "events": ["conference", "scholarly-society", "venue", "organizer", "event-edition"],
 "funding": ["funder", "grant-id", "sponsor", "sponsorship", "funding-award"],
 "impact": ["citation", "replication", "retraction", "policy-citation", "patent-citation", "standard-adoption"],
 "itonami-collector": ["app-kenkyusha", "app-public-fund", "app-crowdfunding", "source-url", "content-hash"],
 "itonami-impact": ["app-analytics", "mitooshi", "impact", "provenance", "observed-at"],
}

def run(args, cwd, timeout=300):
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=timeout, check=False)

def refuse(msg):
    print("REFUSED — do not propose research ontology, sources, or analysis from this run.")
    print(msg); print("Report the refusal and stop without changing files or opening a PR."); return 0

def tracked_text(root, paths):
    listed = run(["git", "ls-files", *paths], root, 60)
    if listed.returncode != 0: return ""
    parts=[]
    for rel in listed.stdout.splitlines():
        if rel.endswith((".clj", ".cljc", ".cljs", ".edn", ".md")) and "catalog.cljc" not in rel:
            try: parts.append((root/rel).read_text(errors="replace"))
            except OSError: pass
    return "\n".join(parts)

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("scope", choices=tuple(CONFIG)); args=parser.parse_args()
    source, worktree = CONFIG[args.scope]
    print("RESEARCH_ITONAMI_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    print(f"scope={args.scope}\nread_root={READ_ROOT} (read only)\nworktree={worktree}")
    if not SCOPE_FILE.is_file(): return refuse("research-scope.edn is absent")
    if not (source/".git").exists(): return refuse(f"source checkout is absent at {source}")
    if not (worktree/".git").exists(): return refuse(f"dedicated bot worktree is absent at {worktree}")
    st=run(["git","status","--porcelain"],worktree,60)
    if st.returncode or st.stdout.strip(): return refuse("dedicated worktree is unreadable or dirty; preserve it for inspection")
    if run(["git","fetch","--quiet","origin"],worktree).returncode: return refuse("git fetch origin failed")
    if run(["git","checkout","--quiet","--detach","origin/main"],worktree).returncode: return refuse("could not synchronize worktree to origin/main")
    head=run(["git","rev-parse","--short=12","HEAD"],worktree,60).stdout.strip()
    paths=["src","config","test"] if args.scope in {"schema","research","events","funding","impact"} else ["scripts","90-docs","manifest"]
    body=tracked_text(worktree,paths)
    if not body: return refuse("no tracked source/config/test text could be measured")
    print(f"scope_sha256={hashlib.sha256(SCOPE_FILE.read_bytes()).hexdigest()}\nhead={head}")
    for token in TOKENS[args.scope]: print(f"TOKEN name={token} present={'yes' if token.lower() in body.lower() else 'no'}")
    if args.scope.startswith("itonami"):
        for rel in ["orgs/cloud-itonami/app-public-fund","orgs/cloud-itonami/app-crowdfunding","orgs/cloud-itonami/app-analytics","orgs/cloud-itonami/mitooshi","orgs/kotoba-lang/app-kenkyusha"]:
            p=READ_ROOT/rel; print(f"CANDIDATE path={rel} present={'yes' if p.is_dir() else 'no'}")
    print("END_RESEARCH_ITONAMI_EVIDENCE_V1"); return 0

if __name__ == "__main__": raise SystemExit(main())
