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
# Named, not id-pinned. Hermes job ids change when a job is recreated, and a
# hard-coded id would then keep succeeding while updating nothing — the same
# silent shape this whole directory is built against.
BOT_NAMES = os.environ.get(
    "HYAKKA_BOT_NAMES", "hyakka-source-scout,hyakka-ontology-scout").split(",")


def bot_job_ids() -> str:
    """Resolve the bots' current job ids by name, or refuse."""
    try:
        with open(JOBS_DB) as fh:
            jobs = json.load(fh).get("jobs", [])
    except Exception as exc:
        refuse(f"cannot read {JOBS_DB}: {exc}")
    by_name = {j.get("name"): j.get("id") for j in jobs}
    missing = [n for n in BOT_NAMES if not by_name.get(n)]
    if missing:
        refuse("no cron job named " + ", ".join(missing) +
               f" in {JOBS_DB}. Installing a model into jobs that are not "
               "there would report success and change nothing.")
    return ",".join(by_name[n] for n in BOT_NAMES)


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
