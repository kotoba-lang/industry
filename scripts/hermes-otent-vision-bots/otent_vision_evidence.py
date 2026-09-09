#!/usr/bin/env python3
"""Decision-free measurements for Otent geospatial vision bots."""
from __future__ import annotations
import argparse, datetime as dt, hashlib, os, subprocess, sys, time, fcntl
from pathlib import Path
sys.path.insert(0, "/Users/junkawasaki/.hermes/profiles/hyakka/scripts")
from throttle import gate, mark  # noqa: E402

READ_ROOT=Path(os.environ.get("OTENT_VISION_READ_ROOT","~/github/com-junkawasaki")).expanduser()
HERE=Path(__file__).resolve().parent
VERSIONED=READ_ROOT/"scripts/hermes-otent-vision-bots/otent-vision-scope.edn"
SCOPE_FILE=VERSIONED if VERSIONED.is_file() else HERE/"otent-vision-scope.edn"
HYAKKA=READ_ROOT/"orgs/network-awai/app-hyakka"; OTENT=READ_ROOT/"orgs/cloud-itonami/otent"
# Which GitHub repository this scope opens PRs against — the backlog gate counts there.
GATE_REPO={"schema":"network-awai/app-hyakka","earth-ingest":"cloud-itonami/otent",
 "earth-vision":"cloud-itonami/otent","street-ingest":"cloud-itonami/otent",
 "street-vision":"cloud-itonami/otent","publish":"network-awai/app-hyakka"}
CONFIG={
 "schema":(HYAKKA,Path("~/.itonami/worktrees/otent-geo-ontology-bot").expanduser()),
 "earth-ingest":(OTENT,Path("~/.itonami/worktrees/otent-earth-imagery-bot").expanduser()),
 "earth-vision":(OTENT,Path("~/.itonami/worktrees/otent-earth-vision-bot").expanduser()),
 "street-ingest":(OTENT,Path("~/.itonami/worktrees/otent-street-imagery-bot").expanduser()),
 "street-vision":(OTENT,Path("~/.itonami/worktrees/otent-street-vision-bot").expanduser()),
 "publish":(HYAKKA,Path("~/.itonami/worktrees/otent-hyakka-publish-bot").expanduser()),
}
TOKENS={
 "schema":["imagery-asset","model-run","image-detection","spatial-uncertainty","derived-from"],
 "earth-ingest":["blue-marble","source-sha256","licence","coverage","capture"],
 "earth-vision":["model-version","model-artifact-hash","confidence","derived-from","change-observation"],
 "street-ingest":["mapillary","street","image","access-token","detections"],
 "street-vision":["face","licence-plate","confidence","taxonomy","spatial-uncertainty"],
 "publish":["imagery-asset","image-detection","derived-from","query/readback","source-class"],
}

def run(args,cwd,timeout=300): return subprocess.run(args,cwd=cwd,text=True,capture_output=True,timeout=timeout,check=False)
def refuse(msg):
 print("REFUSED — do not propose imagery ingest, analysis, or Hyakka claims from this run."); print(msg)
 print("Report the refusal and stop without changing files or opening a PR."); return 0
def tracked_text(root):
 ls=run(["git","ls-files","src","bin","scripts","config","test","docs","README.md"],root,60)
 if ls.returncode: return ""
 out=[]
 for rel in ls.stdout.splitlines():
  if rel.endswith((".clj",".cljc",".cljs",".edn",".md",".py")) and "catalog.cljc" not in rel:
   try: out.append((root/rel).read_text(errors="replace"))
   except OSError: pass
 return "\n".join(out)
def main():
 p=argparse.ArgumentParser(); p.add_argument("scope",choices=tuple(CONFIG)); a=p.parse_args(); source,work=CONFIG[a.scope]
 gate(f"otent-{a.scope}", hours=float(os.environ.get("OTENT_EVIDENCE_COOLDOWN_H","24")))
 print("OTENT_VISION_EVIDENCE_V1")
 print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}\nscope={a.scope}\nread_root={READ_ROOT} (read only)\nworktree={work}")
 if not SCOPE_FILE.is_file(): return refuse("otent-vision-scope.edn is absent")
 if not (source/".git").exists(): return refuse(f"source checkout is absent at {source}")
 if not (work/".git").exists(): return refuse(f"dedicated worktree is absent at {work}")
 st=run(["git","status","--porcelain"],work,60)
 if st.returncode or st.stdout.strip(): return refuse("dedicated worktree is unreadable or dirty; preserve it")
 if run(["git","fetch","--quiet","origin"],work).returncode: return refuse("git fetch origin failed")
 if run(["git","checkout","--quiet","--detach","origin/main"],work).returncode: return refuse("could not synchronize to origin/main")
 body=tracked_text(work)
 if not body: return refuse("no tracked source/config/test text could be measured")
 head=run(["git","rev-parse","--short=12","HEAD"],work,60).stdout.strip()
 print(f"scope_sha256={hashlib.sha256(SCOPE_FILE.read_bytes()).hexdigest()}\nhead={head}")
 # Open-PR backlog gate: over cap this run drains instead of producing. A bot that
 # opens one PR an hour into a repo nothing drains produces a queue, not work
 # (measured 2026-09-05: otent carried 22 open PRs, 21 of them CONFLICTING).
 sys.path.insert(0, str(READ_ROOT/"scripts/hermes-pr-queue"))
 sys.path.insert(0, str(HERE))
 try:
  from backlog_gate import print_gate  # noqa: E402
  print_gate(GATE_REPO[a.scope])
 except ImportError as e:
  print(f"BACKLOG repo={GATE_REPO[a.scope]} open=unknown status=unknown")
  print(f"BACKLOG-BLOCK backlog_gate is unavailable ({e}); unknown is not under. "
        "Do not open a new PR this run.")
 for t in TOKENS[a.scope]: print(f"TOKEN name={t} present={'yes' if t.lower() in body.lower() else 'no'}")
 for rel in ["orgs/cloud-itonami/app-otent","orgs/kotoba-lang/com-mapillary-graph-api","orgs/kotoba-lang/org-openstreetmap-overpass"]:
  print(f"CANDIDATE path={rel} present={'yes' if (READ_ROOT/rel).is_dir() else 'no'}")
 print("END_OTENT_VISION_EVIDENCE_V1"); mark(f"otent-{a.scope}"); return 0
if __name__=="__main__": raise SystemExit(main())
