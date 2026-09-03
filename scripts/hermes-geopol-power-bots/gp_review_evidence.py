#!/usr/bin/env python3
"""Decision-free measurements for the gp-review bot: open PR inventory only."""
import datetime as dt
import json
import subprocess
import sys

print("GP_REVIEW_EVIDENCE_V1")
print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")

def gh(*args):
    proc = subprocess.run(["gh"] + list(args), text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=60, check=False)
    return proc.returncode, proc.stdout.strip()

code, out = gh("pr", "list", "-R", "network-awai/app-hyakka", "--state", "open",
               "--limit", "20", "--json", "number,title,headRefName,author,headRefOid")
if code != 0:
    print(f"REFUSED gh pr list failed: {out[:200]}")
    sys.exit(0)

prs = json.loads(out)
gp = [p for p in prs if p["headRefName"].startswith(("gp-schema-", "bot/gp-"))]
print(f"open_prs={len(prs)} gp_family={len(gp)}")
for p in prs:
    tag = "GP" if p["headRefName"].startswith(("gp-schema-", "bot/gp-")) else "other"
    print(f"PR number={p['number']} tag={tag} branch={p['headRefName']} head={p['headRefOid'][:12]} author={p['author']['login']} title={p['title'][:90]}")
print("END_GP_REVIEW_EVIDENCE_V1")
