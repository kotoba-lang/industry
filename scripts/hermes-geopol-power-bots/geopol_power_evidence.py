#!/usr/bin/env python3
"""Decision-free local measurements for the geopol-power Hermes bots."""

from __future__ import annotations

import argparse
import datetime as dt
import os
from pathlib import Path
import re
import subprocess


READ_ROOT = Path(os.environ.get("GEOPOL_POWER_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
VERSIONED_SCOPE_FILE = READ_ROOT / "scripts/hermes-geopol-power-bots/geopol-power-scope.edn"
INSTALLED_SCOPE_FILE = Path(__file__).resolve().with_name("geopol-power-scope.edn")
SCOPE_FILE = VERSIONED_SCOPE_FILE if VERSIONED_SCOPE_FILE.is_file() else INSTALLED_SCOPE_FILE

WIKI_TARGETS = ["orgs/network-awai/app-hyakka"]

SEARCH_TERMS = {
    "wiki": ["procurement", "appointment", "sanction", "partnership",
             "ownership", "contract", "award", "regulation",
             "observed-at", "source-url", "claim/layer", "model-inference",
             "model-prediction", "secondary-reported", "inference/basis"],
}

WIKI_REQUIRED_FIELDS = [
    "subject", "relation", "object", "claim/layer", "observed-at",
    "effective-at-or-stated-at", "source-url-or-basis-claim-ids",
    "source-publisher-or-model", "source-class",
    "content-hash-or-archive-receipt",
]

FORBIDDEN_TOKENS = []  # tokens that would be *allowed* analysis surface; scope names them only to forbid


def run(*args: str, cwd: Path | None = None) -> tuple[int, str]:
    proc = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=30, check=False)
    return proc.returncode, proc.stdout.strip()


def git_head(path: Path) -> str:
    code, output = run("git", "rev-parse", "--short=12", "HEAD", cwd=path)
    return output if code == 0 else "UNKNOWN"


def tracked_count(path: Path) -> str:
    code, output = run("git", "ls-files", cwd=path)
    return str(len(output.splitlines())) if code == 0 else "UNKNOWN"


def term_count(path: Path, term: str) -> str:
    code, output = run("rg", "-i", "-l", "--glob", "!node_modules/**",
                       "--glob", "!target/**", term, ".", cwd=path)
    if code not in (0, 1):
        return "UNKNOWN"
    return str(len(output.splitlines())) if output else "0"


def test_file_count(path: Path) -> str:
    count = 0
    for item in path.rglob("*"):
        if item.is_file() and re.search(r"(^|[-_.])(test|spec)([-_.]|$)", item.name, re.I):
            if "node_modules" not in item.parts and "target" not in item.parts:
                count += 1
    return str(count)


def print_wiki_measurements() -> None:
    for relative in WIKI_TARGETS:
        path = READ_ROOT / relative
        if not path.is_dir():
            print(f"REPO path={relative} present=no")
            continue
        counts = ",".join(f"{term.replace(' ', '_')}={term_count(path, term)}"
                          for term in SEARCH_TERMS["wiki"])
        print(
            f"REPO path={relative} present=yes head={git_head(path)} "
            f"tracked_files={tracked_count(path)} test_files={test_file_count(path)} "
            f"topic_files=[{counts}]"
        )
    wiki = READ_ROOT / WIKI_TARGETS[0]
    config = wiki / "config/knowledge-ingest.edn"
    if not config.is_file():
        print("WIKI_CONFIG present=no")
        return
    body = config.read_text(errors="replace")
    fields = ",".join(f"{field}={'yes' if field in body else 'no'}"
                      for field in WIKI_REQUIRED_FIELDS)
    print(f"WIKI_CONFIG present=yes required_field_tokens=[{fields}]")
    relation_files = []
    for p in wiki.rglob("*"):
        if p.is_file() and "node_modules" not in p.parts and re.search(
                r"procurement|appointment|sanction|partnership|ownership|relation", p.name, re.I):
            relation_files.append(str(p.relative_to(wiki)))
    print(f"WIKI_RELATION_FILES count={len(relation_files)} sample={relation_files[:12]}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("scope", choices=("wiki",))
    args = parser.parse_args()

    print("GEOPOL_POWER_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    print(f"scope={args.scope}")
    print(f"read_root={READ_ROOT}")
    if not READ_ROOT.is_dir():
        print("REFUSED read root is absent; do not infer readiness or gaps")
        return 0
    if not SCOPE_FILE.is_file():
        print("REFUSED geopol-power-scope.edn is absent; do not infer the requested boundary")
        return 0
    scope_body = SCOPE_FILE.read_text()
    forbidden_hits = [tok for tok in FORBIDDEN_TOKENS if tok in scope_body]
    if forbidden_hits:
        print(f"REFUSED scope file contains forbidden analysis tokens: {forbidden_hits}")
        return 0
    print(f"scope_sha256={run('shasum', '-a', '256', str(SCOPE_FILE))[1].split()[0]}")
    print_wiki_measurements()
    print("END_GEOPOL_POWER_EVIDENCE_V1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
