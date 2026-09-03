#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
PROMPTS = {
    "relation-schema.prompt.md": ["layered epistemics",
                                  ":model-inference",
                                  ":model-prediction",
                                  "at most 2 model claims",
                                  "Never hand-edit ledgers",
                                  "IPLD archive plane referenced by CID only"],
    "relation-source.prompt.md": ["at most two non-duplicate sources",
                                  "fetched in this run",
                                  ":secondary-reported",
                                  "never upgrades to",
                                  "live query/readback",
                                  "Do not require the superproject's"],
}

failures = []
for filename, needles in PROMPTS.items():
    body = (ROOT / filename).read_text()
    for needle in needles:
        if needle not in body:
            failures.append(f"{filename}: missing {needle!r}")

scope = (ROOT / "geopol-power-scope.edn").read_text()
for needle in [":epistemic-layers", ":model-inference", ":model-prediction",
               ":secondary-reported", ":layer-upgrade-forbidden",
               ":wiki-required-fields", ":wiki-source-policy",
               ":no-direct-main-push", ":storage"]:
    if needle not in scope:
        failures.append(f"geopol-power-scope.edn: missing {needle}")

for token in [":analysis-boundary", ":no-power-ranking-or-inference"]:
    if token in scope:
        failures.append(f"geopol-power-scope.edn: stale interpretation-ban token {token}")

for wrapper in ["gp_wiki_evidence.py", "geopol_power_evidence.py"]:
    if not (ROOT / wrapper).is_file():
        failures.append(f"missing {wrapper}")

if failures:
    print("FAILED")
    print("\n".join(failures))
    sys.exit(1)
print("PASS geopol-power bot bundle: 2 prompts, layered-scope, evidence, 1 boundary")
