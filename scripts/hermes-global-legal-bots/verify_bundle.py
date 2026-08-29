#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
PROMPTS = {
    "legal-schema.prompt.md": ["bill is not law", "signed claim/commit DAG", "public professional-role data only"],
    "legal-legislation-source.prompt.md": ["at most two", "A bill is not law", "query/readback"],
    "legal-cases-source.prompt.md": ["Never infer guilt", "juvenile", "query/readback"],
    "legal-profession-source.prompt.md": ["people-search brokers", "personal risk scores", "query/readback"],
    "legal-news-source.prompt.md": ["publisher reported", "full copyrighted articles", "query/readback"],
}
failures = []
for filename, needles in PROMPTS.items():
    body = (ROOT / filename).read_text()
    for needle in needles:
        if needle not in body:
            failures.append(f"{filename}: missing {needle!r}")
    for invariant in ["Never push main", "force-push", "open at most one PR"]:
        if invariant not in body:
            failures.append(f"{filename}: missing invariant {invariant!r}")

scope = (ROOT / "world-legal-scope.edn").read_text()
for needle in [":coverage-unit", ":legislation-allow", ":cases-allow", ":profession-allow",
               ":news-allow", ":bill-is-not-law", ":public-professional-data-only"]:
    if needle not in scope:
        failures.append(f"world-legal-scope.edn: missing {needle}")

for wrapper in ["legal_schema_evidence.py", "legal_legislation_evidence.py",
                "legal_cases_evidence.py", "legal_profession_evidence.py",
                "legal_news_evidence.py"]:
    if not (ROOT / wrapper).is_file():
        failures.append(f"missing {wrapper}")

if failures:
    print("FAILED")
    print("\n".join(failures))
    sys.exit(1)
print("PASS global legal bot bundle: 5 prompts, 5 isolated evidence scopes, 1 governed boundary")
