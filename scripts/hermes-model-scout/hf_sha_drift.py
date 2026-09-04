#!/usr/bin/env python3
"""model-scout weekly refresh — HF revision drift check for the taxonomy.

Reads resources/inference-optimization-taxonomy.edn from the app worktree,
re-measures each :base-repo's current HEAD sha via the HF API, and writes
/tmp/model-scout-sha-drift.json + a one-line stdout verdict.

Diff-only discipline: if every repo's sha matches :hf-revision, print
"no drift" and exit 0 — nothing to refresh. Non-zero exit = drift found
(or error), the weekly cron then does the full EDN refresh flow.

No credentials. UA header required (bare fetches are fine for the API).
"""
import json
import datetime
import re
import sys
import urllib.request

WORKTREE = "/Users/junkawasaki/github/wt/cm-app-scout"
EDN = f"{WORKTREE}/resources/inference-optimization-taxonomy.edn"
OUT = "/tmp/model-scout-sha-drift.json"


def current_shas(edn_text):
    repos = {}
    for m in re.finditer(r':base-repo "([^"]+)"\s*\n\s*:hf-revision "([^"]+)"', edn_text):
        repos[m.group(1)] = m.group(2)
    return repos


def fetch_sha(repo):
    req = urllib.request.Request(
        f"https://huggingface.co/api/models/{repo}",
        headers={"User-Agent": "Mozilla/5.0 (model-scout weekly drift check)"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.load(r).get("sha")


def main():
    try:
        edn = open(EDN).read()
    except OSError as e:
        print(f"ERROR: cannot read {EDN}: {e}")
        return 2
    pinned = current_shas(edn)
    if not pinned:
        print("ERROR: no :base-repo/:hf-revision pairs found in taxonomy EDN")
        return 2
    report = {}
    drift = []
    errors = []
    for repo, pin in sorted(pinned.items()):
        try:
            head = fetch_sha(repo)
        except Exception as e:  # noqa: BLE001 — report, don't crash the sweep
            errors.append({"repo": repo, "error": str(e)[:150]})
            continue
        report[repo] = {"pinned": pin, "head": head, "drift": head != pin}
        if head != pin:
            drift.append(repo)
    result = {
        "checked-at-utc": datetime.datetime.now(datetime.UTC).isoformat(),
        "repos": report,
        "drift": drift,
        "errors": errors,
    }
    with open(OUT, "w") as f:
        json.dump(result, f, indent=1)
    if errors:
        print(f"HF API errors on {len(errors)} repo(s): unknown は unknown と書く — {OUT}")
        return 2
    if drift:
        print(f"DRIFT on {len(drift)} repo(s): {', '.join(drift)} — refresh taxonomy flow")
        return 1
    print("no drift")
    return 0


if __name__ == "__main__":
    sys.exit(main())
