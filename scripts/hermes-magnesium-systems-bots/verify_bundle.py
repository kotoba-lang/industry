#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
PROMPTS = {
    "kotoba-design.prompt.md": ["one existing `kotoba-lang` library", "Never push main", "unmeasured"],
    "itonami-design.prompt.md": ["one existing `cloud-itonami` actor", "Never push main", "human approval"],
    "equipment-schema.prompt.md": ["signed claim/commit DAG", "dealer-owned first-party inventory", "query/readback"],
    "equipment-source.prompt.md": ["at most two", "fetched in this run", "live query/readback"],
}

failures = []
for filename, needles in PROMPTS.items():
    body = (ROOT / filename).read_text()
    for needle in needles:
        if needle not in body:
            failures.append(f"{filename}: missing {needle!r}")

scope = (ROOT / "system-scope.edn").read_text()
for needle in [":kotoba-targets", ":itonami-targets", ":wiki-required-fields", ":no-direct-main-push"]:
    if needle not in scope:
        failures.append(f"system-scope.edn: missing {needle}")

for wrapper in ["mg_kotoba_evidence.py", "mg_itonami_evidence.py", "mg_wiki_evidence.py"]:
    if not (ROOT / wrapper).is_file():
        failures.append(f"missing {wrapper}")

if failures:
    print("FAILED")
    print("\n".join(failures))
    sys.exit(1)
print("PASS magnesium systems bot bundle: 4 prompts, 3 evidence scopes, 1 system boundary")
