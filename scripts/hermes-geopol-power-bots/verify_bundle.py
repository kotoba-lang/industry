#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
PROMPTS = {
    "relation-schema.prompt.md": ["one schema/connector change per run",
                                  "NEVER records power rankings",
                                  "Never hand-edit ledgers"],
    "relation-source.prompt.md": ["at most two non-duplicate sources",
                                  "fetched in this run",
                                  "live query/readback",
                                  "No rankings, no influence scores, no allegiance",
                                  "Do not require the superproject's"],
}

failures = []
for filename, needles in PROMPTS.items():
    body = (ROOT / filename).read_text()
    for needle in needles:
        if needle not in body:
            failures.append(f"{filename}: missing {needle!r}")

scope = (ROOT / "geopol-power-scope.edn").read_text()
for needle in [":wiki-required-fields", ":wiki-source-policy",
               ":analysis-boundary", ":no-direct-main-push",
               ":no-power-ranking-or-inference"]:
    if needle not in scope:
        failures.append(f"geopol-power-scope.edn: missing {needle}")

for forbidden in ["power-ranking: \"allowed\"", "influence-score: \"allowed\""]:
    if forbidden in scope:
        failures.append(f"geopol-power-scope.edn: forbidden token {forbidden}")

if not (ROOT / "gp_wiki_evidence.py").is_file():
    failures.append("missing gp_wiki_evidence.py")
if not (ROOT / "geopol_power_evidence.py").is_file():
    failures.append("missing geopol_power_evidence.py")

if failures:
    print("FAILED")
    print("\n".join(failures))
    sys.exit(1)
print("PASS geopol-power bot bundle: 2 prompts, evidence scope, 1 boundary")
