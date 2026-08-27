#!/usr/bin/env python3
"""Keep the growth bots pointed at a free model that still exists.

Decision-free, like `hyakka_evidence.py`: every judgement lives in
`resolve_free_model.cljs`, and this only arranges for it to be asked. Python
because Hermes runs cron `--script` files as bash or Python and nothing else.

It runs as a `--no-agent` job, so the script IS the job and no model is
needed to keep the model current — which matters, because the case this
exists for is the one where the configured model has stopped answering.

`--if-stale` makes the daily cost one keyless request: probing happens only
when the installed model has fallen off OpenRouter's free list, or the
receipt has aged past the policy's window.

Exit codes are the resolver's, passed through unchanged:
  0  still current, or a new model was probed and installed
  1  nothing free passed the probe — murakumo-main stays primary
  2  REFUSED: could not find out

1 is not an emergency. The bots keep running on the fleet, which is what the
fallback chain in ~/.hermes/config.yaml is for. 2 means the answer is
unknown, and that is the one this must not report as either of the others.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESOLVER = os.environ.get(
    "HYAKKA_RESOLVER", os.path.join(HERE, "resolve_free_model.cljs"))
NBB = os.environ.get("HYAKKA_NBB", "/opt/homebrew/bin/nbb")
JOBS_DB = os.environ.get(
    "HYAKKA_JOBS_DB", os.path.expanduser("~/.hermes/cron/jobs.json"))

# Every agent-driven cron job, not a list of names.
#
# The first version named the two hyakka scouts. A day later the cron table
# held five jobs: another session had added `itonami-ingest-scout` and
# `itonami-coverage-scout`, built to the same pattern, and both went on running
# on `murakumo-main` because nothing here knew they existed. Measured
# 2026-08-28: one of them spent 57 minutes and 1,857,045 input tokens on the
# fleet overnight, the night after the fleet stopped being the default.
#
# A name list does not fail when a sixth bot appears. It leaves it behind, and
# looks identical to a run with nothing to do. So coverage is the default and a
# deliberate pin is expressed by opting out.
#
# Jobs that run WITHOUT an agent carry no model at all and are skipped. This
# job is one of them.
OPT_OUT = {n.strip() for n in os.environ.get("HYAKKA_MODEL_OPT_OUT", "").split(",") if n.strip()}


def bot_job_ids() -> str:
    """Every agent-driven job's id, minus the opt-outs. Says what it covers."""
    try:
        with open(JOBS_DB) as fh:
            jobs = json.load(fh).get("jobs", [])
    except Exception as exc:
        refuse(f"cannot read {JOBS_DB}: {exc}")
    if not jobs:
        refuse(f"{JOBS_DB} lists no cron jobs. Installing a model into an "
               "empty table would report success and change nothing.")

    targets, skipped = [], []
    for j in jobs:
        name = j.get("name") or j.get("id")
        if j.get("no_agent"):
            skipped.append(f"{name} (no-agent: runs a script, holds no model)")
        elif name in OPT_OUT:
            skipped.append(f"{name} (opted out)")
        elif not j.get("id"):
            refuse(f"a cron job named {name!r} has no id")
        else:
            targets.append(j)

    # Printed every run. A refresh that quietly narrowed its own scope would
    # look exactly like one that had nothing left to do.
    print(f"COVERS\t{len(targets)} agent job(s)")
    for j in targets:
        print(f"  + {j.get('name')}\t{j.get('model')}\t{j.get('provider')}")
    for line in skipped:
        print(f"  - {line}")

    if not targets:
        refuse("no agent-driven cron job is left to point at a model.")
    return ",".join(j["id"] for j in targets)


def refuse(why: str) -> "typing.NoReturn":  # noqa: F821
    print("REFUSED — the bots' model was not touched.")
    print(why)
    sys.exit(2)


def main() -> None:
    if not os.path.exists(RESOLVER):
        refuse(f"no resolver at {RESOLVER}")
    jobs = bot_job_ids()

    proc = subprocess.run(
        [NBB, RESOLVER, "--if-stale", "--write", "--jobs", jobs],
        capture_output=True, text=True, timeout=1800)

    # stderr carries the SCANNED line and the per-candidate probe trace; it is
    # the evidence for whatever stdout claims, so it is not discarded.
    sys.stderr.write(proc.stderr)
    print(proc.stdout, end="")
    sys.exit(proc.returncode)


if __name__ == "__main__":
    main()
