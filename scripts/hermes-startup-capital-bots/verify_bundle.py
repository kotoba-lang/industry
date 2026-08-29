#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parent
PROMPTS={
 "startup-capital-ontology.prompt.md":["signed claim/commit DAG","LP commitment is not current NAV/ownership","query/readback"],
 "startup-company-source.prompt.md":["time-bounded startup","discovery-only","query/readback"],
 "venture-fund-source.prompt.md":["venture firm is not a fund vehicle","announced target is not closed size","query/readback"],
 "venture-lp-source.prompt.md":["Never infer undisclosed LPs","LP commitment is not current NAV","query/readback"],
 "venture-manager-source.prompt.md":["public fund-manager-role","personal wealth","query/readback"],
 "venture-round-source.prompt.md":["Announced round is not cash received","portfolio page is an observed claim","query/readback"],
 "itonami-capital-analysis.prompt.md":["exactly one existing candidate","No investment advice","query/readback"],
}
fail=[]
for f,needles in PROMPTS.items():
 body=(ROOT/f).read_text()
 for n in needles:
  if n not in body: fail.append(f"{f}: missing {n!r}")
 for n in ["Never push main","force-push","open at most one PR"]:
  if n not in body: fail.append(f"{f}: missing invariant {n!r}")
scope=(ROOT/"capital-scope.edn").read_text()
for n in [":venture-firm",":investment-fund",":limited-partner",":fund-manager",":financing-round",":no-investment-advice",":worldwide-is-a-coverage-goal-not-a-completeness-claim"]:
 if n not in scope: fail.append(f"capital-scope.edn: missing {n}")
wrappers=["startup_capital_ontology_evidence.py","startup_company_source_evidence.py","venture_fund_source_evidence.py","venture_lp_source_evidence.py","venture_manager_source_evidence.py","venture_round_source_evidence.py","itonami_capital_analysis_evidence.py"]
for f in wrappers:
 if not (ROOT/f).is_file(): fail.append(f"missing {f}")
if fail:
 print("FAILED\n"+"\n".join(fail)); sys.exit(1)
print("PASS startup capital bot bundle: 7 prompts, 7 isolated evidence scopes, 1 governed ontology boundary")
