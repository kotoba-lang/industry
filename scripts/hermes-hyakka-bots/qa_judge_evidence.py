#!/usr/bin/env python3
"""qa-judge evidence — no decisions, measurements only.

Reads the endpoint-health resident's statement (synced from gad) plus a fresh
--all --cheap-only run on this machine, and prints a bounded report that the
qa-judge bot reads. Empty report = everything kept its promise; the bot is
told so explicitly (it must never invent a finding from silence).

Exit codes: 0 always (REFUSED banner on any internal error, like
hyakka_evidence.py — the bot must run and be told it is blind, not skip).
"""
import json, os, subprocess, sys, datetime

ROOT = os.environ.get("QA_ROOT", "/Users/junkawasaki/github/com-junkawasaki")
STATEMENT = os.path.expanduser("~/.gftd/endpoint-health/statement.edn")

def run(cmd, timeout=240):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                              cwd=ROOT).stdout
    except Exception as e:
        return f"SCRIPT-ERROR {e}"

print(f"QA-EVIDENCE {datetime.datetime.now().isoformat(timespec='seconds')}")
print(f"root={ROOT}")

# 1) fresh full run (all probes, cheap only) — the live promise check
print("\n== fresh verify-endpoint-health (--all --cheap-only) ==")
out = run(["nbb", "scripts/verify-endpoint-health.cljs", ".", "--all", "--cheap-only", "--findings"])
print(out[-3000:] if out else "(no output)")
if "SCRIPT-ERROR" in out or "REFUSING" in out:
    print("REFUSED: probe run failed; the bot must not propose findings today")
    sys.exit(0)

# 2) trailing-window statement from the gad resident, if synced locally
print("\n== resident statement (24h window from gad) ==")
if os.path.exists(STATEMENT):
    txt = open(STATEMENT, encoding="utf-8").read()
    # bounded: emit per-target status/ratio/incident-count only
    import re
    for m in re.finditer(r":target \"(:[a-z/-]+)\".*?:status :([a-z-]+).*?:ratio ([0-9.]+).*?(?:incidents \[(.*?)\])?\}(?= #\{:availability|\}\]\})",
                         txt, flags=re.S):
        target, status, ratio = m.group(1), m.group(2), m.group(3)
        inc = m.group(4) or ""
        n_inc = inc.count(":incident/") // 2 if inc else 0
        print(f"  {target:45s} {status:12s} ratio={ratio} incidents={n_inc}")
else:
    print(f"  (statement not synced at {STATEMENT} — run scripts/endpoint-health-pull.cljs)")

# 3) the one-line contract for the judge
print("\n== judge contract ==")
print("Probe rows above are MEASUREMENTS. A probe row marked DEGRADED,")
print("ANSWERED-BADLY or UNANSWERED is the only thing you may turn into a finding.")
print("Findings go to 90-docs/qa/<date>-<probe-id>.edn with: probe id, observed")
print("values quoted from this report, the promise (manifest :note), a one-command")
print("repro (nbb scripts/verify-endpoint-health.cljs . --only <id>), severity, owner.")
print("Never propose a finding you cannot quote from this output. Zero bad rows")
print("=> write nothing, report 'all promises held', and stop.")
