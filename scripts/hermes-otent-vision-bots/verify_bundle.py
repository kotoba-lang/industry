#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parent
PROMPTS={
 "otent-geo-schema.prompt.md":["Pixel is not ground truth","signed claim/commit DAG","no face identity"],
 "otent-earth-imagery.prompt.md":["at most one source","Google Street View","object readback"],
 "otent-earth-vision.prompt.md":["model ID/version/artifact hash","not ground truth","derived-table readback"],
 "otent-street-imagery.prompt.md":["at most one source/area","Google Street View","No person/vehicle tracking"],
 "otent-street-vision.prompt.md":["licence-plate OCR","not identity","derived-table readback"],
 "otent-hyakka-publish.prompt.md":["not raw imagery","privacy","live query/readback"],
}
fail=[]
for f,needles in PROMPTS.items():
 body=(ROOT/f).read_text()
 for n in needles:
  if n not in body: fail.append(f"{f}: missing {n!r}")
 for n in ["Never push main","force-push","open at most one PR"]:
  if n not in body: fail.append(f"{f}: missing invariant {n!r}")
scope=(ROOT/"otent-vision-scope.edn").read_text()
for n in [":imagery-asset",":model-run",":spatial-uncertainty",":google-street-view-persistence-without-explicit-rights",":no-reidentification"]:
 if n not in scope: fail.append(f"otent-vision-scope.edn: missing {n}")
for f in ["otent_geo_schema_evidence.py","otent_earth_imagery_evidence.py","otent_earth_vision_evidence.py","otent_street_imagery_evidence.py","otent_street_vision_evidence.py","otent_hyakka_publish_evidence.py"]:
 if not (ROOT/f).is_file(): fail.append(f"missing {f}")
if fail: print("FAILED\n"+"\n".join(fail)); sys.exit(1)
print("PASS Otent vision bot bundle: 6 prompts, 6 isolated evidence scopes, 1 governed ontology boundary")
