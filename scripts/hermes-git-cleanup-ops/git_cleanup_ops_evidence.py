#!/usr/bin/env python3
"""Decision-free inventory for the git-cleanup-ops hermes profile.

Runs the runbook's three local measurement tools (manifest/cleanup-workflow.edn,
:workflow :inspect + :retirement) and prints a compact, ranked report. It
DECIDES NOTHING and DOES NOTHING DESTRUCTIVE:

  1. superproject sync state (fetch + `merge --ff-only`; a refusal is a FINDING,
     never silently worked around -- especially the append-only-ledger-blocker)
  2. checkout-staleness.cljk  -- fleet population, local, zero network (~2-3 min)
  3. cleanup.cljk --unlanded  -- landing ladder per child repo (~5-15 min)
  4. west-orphan-audit.cljk --blocking -- exit 0/1/2 kept distinct
     (2 = "could not ask GitHub", which is NOT "no orphans")
  5. superproject stash list (SHA-keyed) and local branch list with the
     ancestry fact (is-ancestor of main) -- the retirement raw material.

Any phase that fails prints `REFUSED <phase> <reason>` and the run continues
with the phases that worked; if EVERYTHING failed the banner is REFUSED-ALL.
Exit code is always 0: the runner injects stdout into the prompt and the bot
must be able to read the refusal, not lose it.

This script is the READ side of the charter. Drops/archives go through
git_cleanup_retire.py (which enforces archive-before-drop itself).
"""
import concurrent.futures as cf
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(os.environ.get("GCO_ROOT", str(pathlib.Path.home() / "github/com-junkawasaki"))).expanduser()
KBB = ["kbb", "--backend", "sci"]
TIMEOUT_STALE = 420
TIMEOUT_UNLANDED = 1200
TIMEOUT_ORPHAN = 420
ENV = dict(os.environ, GIT_TERMINAL_PROMPT="0",
           GIT_SSH_COMMAND="ssh -o BatchMode=yes -o ConnectTimeout=10")


def run(cmd, timeout, cwd=ROOT):
    try:
        r = subprocess.run(cmd, cwd=str(cwd), env=ENV, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (("\n" + r.stderr[-2000:]) if r.stderr else "")
    except subprocess.TimeoutExpired:
        return None, "timeout after %ss" % timeout
    except FileNotFoundError as e:
        return None, str(e)


def sync_superproject():
    """fetch + ff-only merge; report, never force."""
    out = []
    rc, o = run(["git", "fetch", "-q", "origin"], 180)
    if rc != 0:
        return ["REFUSED sync: fetch failed: " + o.strip()[-200:]], None
    rc, o = run(["git", "merge", "--ff-only", "origin/main"], 120)
    if rc != 0:
        blockers = re.findall(r"(?:would be overwritten by merge|conflicting files)\n((?:[^\n]+\n?)+)", o)
        out.append("FF-MERGE-BLOCKED: local main cannot fast-forward (report blockers; do NOT stash to force -- ledger append lines land via scripts/ledger-land.cljk):")
        for line in o.strip().splitlines()[-8:]:
            out.append("  " + line)
    else:
        sha = run(["git", "rev-parse", "--short", "HEAD"], 10)[1].strip()
        out.append("FF-MERGED ok at %s" % sha)
    _, status = run(["git", "status", "--porcelain"], 30)
    dirty = [l for l in status.splitlines() if l.strip()]
    out.append("SUPERPROJECT-DIRTY %d entries (owner/session WIP -- never touch)" % len(dirty))
    for l in dirty[:6]:
        out.append("  " + l)
    return out, status


def phase_staleness():
    rc, o = run(KBB + ["scripts/checkout-staleness.cljk"], TIMEOUT_STALE)
    if rc is None:
        return "REFUSED staleness: %s" % o
    lines = o.splitlines()
    keep = [l for l in lines if re.match(r"^(total checkouts|  current|  behind only|  ahead only|  diverged|  shallow|dirty working tree|AHEAD \+ dirty|ahead WHILE ON|HEAD detached)", l)]
    return ["[staleness] " + (k.strip() or "n/a") for k in keep[:12]] or ["REFUSED staleness: no summary block (rc=%s)" % rc]


def phase_unlanded():
    rc, o = run(KBB + ["scripts/cleanup.cljk", "--unlanded"], TIMEOUT_UNLANDED)
    if rc is None:
        return "REFUSED unlanded: %s" % o, []
    rows = []
    for l in o.splitlines():
        m = re.match(r"^(orgs/\S+)\s+UNLANDED;\s*(.*)$", l.strip())
        if m:
            rows.append((m.group(1), m.group(2)))
    def rank(item):
        rest = item[1]
        if "untracked=" in rest: return 1
        if "dirty=" in rest: return 2
        if "unpushed=" in rest: return 3
        return 4
    rows.sort(key=rank)
    out = ["[unlanded] %d repos (ladder order: untracked > dirty > unpushed > nopr)" % len(rows)]
    for repo, rest in rows[:20]:
        out.append("  WORK LADDER %s ; %s" % (repo, rest[:160]))
    if len(rows) > 20:
        out.append("  ... +%d more repos (named in full log; never dropped silently)" % (len(rows) - 20))
    if "=?" in "\n".join(x[1] for x in rows) or "=!" in "\n".join(x[1] for x in rows):
        out.append("  CAP/WARN present (=? truncated / =! lookup failed): 'not looked' is not 'no PR' -- report, never drop silently")
    tail = [l for l in o.splitlines() if "recovered" in l.lower() or "round-trips" in l.lower()]
    out += ["  " + t.strip() for t in tail[:3]]
    return "\n".join(out), rows


def phase_orphans():
    rc, o = run(KBB + ["scripts/west-orphan-audit.cljk", "--blocking"], TIMEOUT_ORPHAN)
    if rc is None:
        return ["REFUSED orphan-audit: %s" % o]
    if rc == 0:
        return ["[west-orphan] exit 0 -- no blocking :local/root edges"]
    if rc == 2:
        return ["[west-orphan] exit 2 -- COULD NOT ASK GitHub (rate limit / auth). NOT a verdict of zero. Never register/retire on a row from this run."]
    bad = [l for l in o.splitlines() if ("local-root-broken" in l or ":local-root-broken" in l)]
    out = ["[west-orphan] exit %s -- BLOCKING edges exist (fix before claiming cleanup done):" % rc]
    out += ["  " + b.strip()[:160] for b in bad[:8]]
    if "true-orphan" in o:
        n = o.count("true-orphan")
        out.append("  true-orphan mentions: %d (register via west-triple-sync plan/apply --scope orphans, never silent-delete)" % n)
    return out


def superproject_retirement_material():
    out = []
    rc, o = run(["git", "stash", "list", "--format=%H %gs"], 30)
    stashes = [l for l in o.splitlines() if l.strip()]
    out.append("[stashes] %d entries (SHA-keyed; indices shift under concurrency)" % len(stashes))
    for s in stashes[:10]:
        out.append("  STASH " + s[:120])
    rc, o = run(["git", "rev-parse", "--abbrev-ref", "origin/HEAD"], 15)
    mainref = o.strip() if rc == 0 and o.strip() else "origin/main"
    rc2, heads = run(["git", "for-each-ref", "--format=%(refname:short)", "refs/heads/"], 15)
    branches = [b.strip() for b in heads.splitlines() if b.strip() and b.strip() not in ("main",)]
    out.append("[branches] %d local non-main (never delete git-annex or a branch another session uses)" % len(branches))
    for b in branches[:25]:
        rc, _ = run(["git", "merge-base", "--is-ancestor", b, mainref], 15)
        fact = "LANDED" if rc == 0 else "NOT-ANCESTOR"
        rc, n = run(["git", "rev-list", "--count", "%s..%s" % (mainref, b)], 15)
        out.append("  BRANCH %s %s ahead=%s" % (b, fact, (n.strip() or "?")))
    return out


def main():
    print("GIT_CLEANUP_OPS_INVENTORY_V1")
    print("root=%s" % ROOT)
    if not ROOT.is_dir():
        print("REFUSED-ALL root checkout absent; nothing was measured")
        return 0
    sync_out, _ = sync_superproject()
    print("\n".join(sync_out))

    with cf.ThreadPoolExecutor(max_workers=3) as pool:
        fs = {pool.submit(phase_staleness): "staleness",
              pool.submit(phase_unlanded): "unlanded",
              pool.submit(phase_orphans): "orphan"}
        results = {}
        for f in cf.as_completed(fs, timeout=max(TIMEOUT_UNLANDED, TIMEOUT_STALE, TIMEOUT_ORPHAN) + 240):
            results[fs[f]] = f.result()

    print("\n".join(results.get("staleness", ["REFUSED staleness: crashed"])))
    unl = results.get("unlanded", ["REFUSED unlanded: crashed", []])
    if isinstance(unl, tuple):
        print(unl[0])
    else:
        print(unl)
    print("\n".join(results.get("orphan", ["REFUSED orphan: crashed"])))

    print("\n".join(superproject_retirement_material()))
    print("END-INVENTORY (measurement only; retirement goes through git_cleanup_retire.py, archive first, non-negotiable)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # never claim completion from a crash
        print("REFUSED-ALL unexpected: %r" % e)
        sys.exit(0)
