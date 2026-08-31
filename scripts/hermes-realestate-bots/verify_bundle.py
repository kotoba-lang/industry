#!/usr/bin/env python3
"""Checks that this bundle still says what it is supposed to say.

Every needle below is a boundary that took an argument to arrive at, and each
is the kind of sentence that gets softened by a later edit without anyone
noticing the softening. `realestate_evidence.py` measures the world; this
measures the bundle.
"""
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parent
PROMPTS = {
 "realestate-ontology.prompt.md": ["signed claim/commit DAG",
                                   "A listing asking price is not a transaction price",
                                   "natural-person owner", "query/readback"],
 "realestate-registry-source.prompt.md": ["Registered title is not beneficial ownership",
                                          "discovery-only", "natural person", "query/readback"],
 "realestate-transaction-source.prompt.md": ["A price index is not a property-level price",
                                             "automated valuation is not a verified valuation",
                                             "natural person", "query/readback"],
 "realestate-investment-source.prompt.md": ["portfolio disclosure is an observed claim",
                                            "announced acquisition is not a completed transfer",
                                            "query/readback"],
 "realestate-procedure-source.prompt.md": ["A zoning designation is not permission to build",
                                           "Missing is unmeasured", "query/readback"],
 "itonami-realestate-analysis.prompt.md": ["exactly one existing candidate",
                                           "No investment advice", "query/readback"],
}
WRAPPERS = {
 "realestate_ontology_evidence.py": "schema",
 "realestate_registry_source_evidence.py": "registry",
 "realestate_transaction_source_evidence.py": "transaction",
 "realestate_investment_source_evidence.py": "investment",
 "realestate_procedure_source_evidence.py": "procedure",
 "itonami_realestate_analysis_evidence.py": "analysis",
}
SCOPE_NEEDLES = [":land-right", ":title-record", ":recorded-transaction", ":official-valuation",
                 ":reit", ":purchase-procedure-step", ":foreign-ownership-restriction",
                 ":no-investment-advice", ":no-natural-person-owner-identification",
                 ":listing-asking-price-is-not-a-transaction-price",
                 ":worldwide-is-a-coverage-goal-not-a-completeness-claim"]

fail = []
for f, needles in PROMPTS.items():
    p = ROOT / f
    if not p.is_file():
        fail.append(f"missing {f}"); continue
    body = p.read_text()
    for n in needles:
        if n not in body: fail.append(f"{f}: missing {n!r}")
    for n in ["Never push main", "force-push", "open at most one PR"]:
        if n not in body: fail.append(f"{f}: missing invariant {n!r}")

scope = ROOT / "realestate-scope.edn"
if not scope.is_file():
    fail.append("realestate-scope.edn is absent")
else:
    text = scope.read_text()
    for n in SCOPE_NEEDLES:
        if n not in text: fail.append(f"realestate-scope.edn: missing {n}")

# A wrapper naming a scope the shared module does not define would refuse at
# argparse time, every night, with the bot reading it as a normal refusal.
shared = ROOT / "realestate_evidence.py"
if not shared.is_file():
    fail.append("realestate_evidence.py is absent")
else:
    defined = set(re.findall(r'^\s"(\w+)":\s+\(', shared.read_text(), re.M))
    for f, scope_name in WRAPPERS.items():
        p = ROOT / f
        if not p.is_file():
            fail.append(f"missing {f}"); continue
        if f'["{scope_name}"]' not in p.read_text():
            fail.append(f"{f}: does not select scope {scope_name!r}")
        if scope_name not in defined:
            fail.append(f"realestate_evidence.py: no CONFIG entry for scope {scope_name!r}")

if fail:
    print("FAILED\n" + "\n".join(fail)); sys.exit(1)
print(f"PASS real-estate bot bundle: {len(PROMPTS)} prompts, {len(WRAPPERS)} isolated evidence scopes, "
      f"{len(SCOPE_NEEDLES)} scope boundaries, 1 governed ontology boundary")
