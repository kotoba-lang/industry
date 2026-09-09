#!/usr/bin/env python3
"""Decision-free measurements for the startup-capital Hermes bots."""
from __future__ import annotations
import argparse, datetime as dt, hashlib, os, subprocess
from pathlib import Path

READ_ROOT = Path(os.environ.get("STARTUP_CAPITAL_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
HERE = Path(__file__).resolve().parent
VERSIONED = READ_ROOT / "scripts/hermes-startup-capital-bots/capital-scope.edn"
SCOPE_FILE = VERSIONED if VERSIONED.is_file() else HERE / "capital-scope.edn"
HYAKKA = READ_ROOT / "orgs/network-awai/app-hyakka"
CONFIG = {
 "schema": (HYAKKA, Path("~/.itonami/worktrees/startup-capital-ontology-bot").expanduser()),
 "company": (HYAKKA, Path("~/.itonami/worktrees/startup-company-source-bot").expanduser()),
 "fund": (HYAKKA, Path("~/.itonami/worktrees/venture-fund-source-bot").expanduser()),
 "lp": (HYAKKA, Path("~/.itonami/worktrees/venture-lp-source-bot").expanduser()),
 "manager": (HYAKKA, Path("~/.itonami/worktrees/venture-manager-source-bot").expanduser()),
 "round": (HYAKKA, Path("~/.itonami/worktrees/venture-round-source-bot").expanduser()),
 "analysis": (READ_ROOT, Path("~/.itonami/worktrees/itonami-capital-analysis-bot").expanduser()),
}
TOKENS = {
 "schema": ["startup", "venture-firm", "investment-fund", "limited-partner", "financing-round"],
 "company": ["company-registration", "legal-entity", "startup", "jurisdiction", "source-url"],
 "fund": ["venture-firm", "fund-vehicle", "general-partner", "fund-close", "regulator"],
 "lp": ["limited-partner", "commitment", "institutional", "board-record", "observed-at"],
 "manager": ["fund-manager", "professional-role", "management-company", "public-professional"],
 "round": ["financing-round", "investment", "portfolio-relationship", "valuation", "exit"],
 "analysis": ["app-public-fund", "app-crowdfunding", "app-analytics", "mitooshi", "provenance"],
}

def run(args, cwd, timeout=300):
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=timeout, check=False)

def refuse(msg):
    print("REFUSED — do not propose startup-capital ontology, sources, or analysis from this run.")
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
    print("STARTUP_CAPITAL_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    print(f"scope={args.scope}\nread_root={READ_ROOT} (read only)\nworktree={worktree}")
    if not SCOPE_FILE.is_file(): return refuse("capital-scope.edn is absent")
    if not (source/".git").exists(): return refuse(f"source checkout is absent at {source}")
    if not (worktree/".git").exists(): return refuse(f"dedicated bot worktree is absent at {worktree}")
    st=run(["git","status","--porcelain"],worktree,60)
    if st.returncode or st.stdout.strip(): return refuse("dedicated worktree is unreadable or dirty; preserve it for inspection")
    if run(["git","fetch","--quiet","origin"],worktree).returncode: return refuse("git fetch origin failed")
    if run(["git","checkout","--quiet","--detach","origin/main"],worktree).returncode: return refuse("could not synchronize worktree to origin/main")
    head=run(["git","rev-parse","--short=12","HEAD"],worktree,60).stdout.strip()
    paths=["src","config","test"] if args.scope != "analysis" else ["scripts","90-docs","manifest"]
    body=tracked_text(worktree,paths)
    if not body: return refuse("no tracked source/config/test text could be measured")
    print(f"scope_sha256={hashlib.sha256(SCOPE_FILE.read_bytes()).hexdigest()}\nhead={head}")
    for token in TOKENS[args.scope]: print(f"TOKEN name={token} present={'yes' if token.lower() in body.lower() else 'no'}")
    if args.scope == "analysis":
        for rel in ["orgs/cloud-itonami/app-public-fund","orgs/cloud-itonami/app-crowdfunding","orgs/cloud-itonami/app-analytics","orgs/cloud-itonami/mitooshi"]:
            print(f"CANDIDATE path={rel} present={'yes' if (READ_ROOT/rel).is_dir() else 'no'}")
    print("END_STARTUP_CAPITAL_EVIDENCE_V1"); return 0

if __name__ == "__main__": raise SystemExit(main())
