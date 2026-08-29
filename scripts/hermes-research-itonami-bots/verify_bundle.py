#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parent
PROMPTS={
 "research-schema.prompt.md":["signed claim/commit DAG","Funding is not endorsement","Do not rank researchers"],
 "research-source.prompt.md":["at most two","Publication is not validation","query/readback"],
 "research-events.prompt.md":["event edition","venue permanence","query/readback"],
 "research-funding.prompt.md":["Never infer sponsorship","Funding is not endorsement","query/readback"],
 "research-impact.prompt.md":["Correlation is not causation","Never rank researchers","query/readback"],
 "itonami-research-collector.prompt.md":["exactly one existing candidate","fresh isolated clone","readback shape"],
 "itonami-research-impact.prompt.md":["Do not build a universal score","Never rank researchers","query/readback"],
}
fail=[]
for f,needles in PROMPTS.items():
 body=(ROOT/f).read_text()
 for n in needles:
  if n not in body: fail.append(f"{f}: missing {n!r}")
 for n in ["Never push main","force-push","open at most one PR"]:
  if n not in body: fail.append(f"{f}: missing invariant {n!r}")
scope=(ROOT/"research-scope.edn").read_text()
for n in [":scholarly-society",":event-edition",":funding-award",":impact-observation",":inferred-causality",":no-researcher-ranking"]:
 if n not in scope: fail.append(f"research-scope.edn: missing {n}")
wrappers=["research_schema_evidence.py","research_source_evidence.py","research_events_evidence.py","research_funding_evidence.py","research_impact_evidence.py","itonami_research_collector_evidence.py","itonami_research_impact_evidence.py"]
for f in wrappers:
 if not (ROOT/f).is_file(): fail.append(f"missing {f}")
if fail:
 print("FAILED\n"+"\n".join(fail)); sys.exit(1)
print("PASS research itonami bot bundle: 7 prompts, 7 isolated evidence scopes, 1 governed ontology boundary")
