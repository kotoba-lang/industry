#!/usr/bin/env python3
"""Decision-free measurements for the worldwide real-estate Hermes bots.

Holds no judgement. It syncs a bot's dedicated worktree to origin/main, reads
the tracked text there, and reports whether each of that bot's vocabulary
tokens is present. The bot decides; this file only measures.

Its exit code is load-bearing in one direction. Hermes injects stdout into the
prompt, so an empty report reaches the model as *nothing is missing* — the one
answer that must never be produced by failure. Every refusal therefore prints a
REFUSED banner and still exits 0: the bot has to run and be told it is blind,
not be silently skipped.
"""
from __future__ import annotations
import argparse, datetime as dt, hashlib, os, subprocess
from pathlib import Path

READ_ROOT = Path(os.environ.get("REALESTATE_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
HERE = Path(__file__).resolve().parent
VERSIONED = READ_ROOT / "scripts/hermes-realestate-bots/realestate-scope.edn"
SCOPE_FILE = VERSIONED if VERSIONED.is_file() else HERE / "realestate-scope.edn"
HYAKKA = READ_ROOT / "orgs/network-awai/app-hyakka"
CANDIDATES = ["orgs/cloud-itonami/real-estate",
              "orgs/cloud-itonami/mortgage-registry",
              "orgs/cloud-itonami/cloud-itonami-isic-6810",
              "orgs/cloud-itonami/cloud-itonami-isic-6820",
              "orgs/cloud-itonami/cloud-itonami-isco-3334"]
CONFIG = {
 "schema":      (HYAKKA, Path("~/.itonami/worktrees/realestate-ontology-bot").expanduser()),
 "registry":    (HYAKKA, Path("~/.itonami/worktrees/realestate-registry-source-bot").expanduser()),
 "transaction": (HYAKKA, Path("~/.itonami/worktrees/realestate-transaction-source-bot").expanduser()),
 "investment":  (HYAKKA, Path("~/.itonami/worktrees/realestate-investment-source-bot").expanduser()),
 "procedure":   (HYAKKA, Path("~/.itonami/worktrees/realestate-procedure-source-bot").expanduser()),
 "analysis":    (READ_ROOT, Path("~/.itonami/worktrees/itonami-realestate-analysis-bot").expanduser()),
}
TOKENS = {
 "schema":      ["parcel", "land-right", "title-record", "recorded-transaction", "official-valuation"],
 "registry":    ["land-registry", "cadastre", "title-number", "tenure", "registrar"],
 "transaction": ["price-paid", "recorded-transaction", "official-valuation", "price-index", "observed-at"],
 "investment":  ["reit", "fund-vehicle", "portfolio-disclosure", "management-company", "regulator"],
 "procedure":   ["purchase-procedure", "transfer-tax", "foreign-ownership", "notarial", "required-document"],
 "analysis":    ["real-estate", "mortgage-registry", "isic-6810", "isic-6820", "isco-3334"],
}

def run(args, cwd, timeout=300):
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=timeout, check=False)

def refuse(msg):
    print("REFUSED — do not propose real-estate ontology, sources, or analysis from this run.")
    print(msg); print("Report the refusal and stop without changing files or opening a PR."); return 0

def tracked_text(root, paths):
    """The tracked text of those paths, and how many files it came from.

    The count is returned rather than inferred from the text, because a scan
    that read nothing and a scan that read files saying nothing produce the
    same empty token table, and only one of them is a measurement."""
    listed = run(["git", "ls-files", *paths], root, 60)
    if listed.returncode != 0: return "", 0
    parts = []
    for rel in listed.stdout.splitlines():
        if rel.endswith((".clj", ".cljc", ".cljs", ".edn", ".md")) and "catalog.cljc" not in rel:
            try: parts.append((root / rel).read_text(errors="replace"))
            except OSError: pass
    return "\n".join(parts), len(parts)

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("scope", choices=tuple(CONFIG))
    args = parser.parse_args()
    source, worktree = CONFIG[args.scope]
    print("REALESTATE_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    print(f"scope={args.scope}\nread_root={READ_ROOT} (read only)\nworktree={worktree}")
    if not SCOPE_FILE.is_file(): return refuse("realestate-scope.edn is absent")
    if not (source / ".git").exists(): return refuse(f"source checkout is absent at {source}")
    if not (worktree / ".git").exists(): return refuse(f"dedicated bot worktree is absent at {worktree}")
    st = run(["git", "status", "--porcelain"], worktree, 60)
    if st.returncode or st.stdout.strip(): return refuse("dedicated worktree is unreadable or dirty; preserve it for inspection")
    if run(["git", "fetch", "--quiet", "origin"], worktree).returncode: return refuse("git fetch origin failed")
    if run(["git", "checkout", "--quiet", "--detach", "origin/main"], worktree).returncode: return refuse("could not synchronize worktree to origin/main")
    head = run(["git", "rev-parse", "--short=12", "HEAD"], worktree, 60).stdout.strip()
    paths = ["src", "config", "test"] if args.scope != "analysis" else ["scripts", "90-docs", "manifest"]
    body, files = tracked_text(worktree, paths)
    if not files: return refuse("no tracked source/config/test text could be measured")
    print(f"scope_sha256={hashlib.sha256(SCOPE_FILE.read_bytes()).hexdigest()}\nhead={head}")
    print(f"files_measured={files}")
    for token in TOKENS[args.scope]:
        print(f"TOKEN name={token} present={'yes' if token.lower() in body.lower() else 'no'}")
    if args.scope == "analysis":
        for rel in CANDIDATES:
            print(f"CANDIDATE path={rel} present={'yes' if (READ_ROOT / rel).is_dir() else 'no'}")
    print("END_REALESTATE_EVIDENCE_V1"); return 0

if __name__ == "__main__": raise SystemExit(main())
