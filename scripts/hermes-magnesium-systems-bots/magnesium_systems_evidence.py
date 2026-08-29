#!/usr/bin/env python3
"""Decision-free local measurements for the magnesium systems Hermes bots."""

from __future__ import annotations

import argparse
import datetime as dt
import os
from pathlib import Path
import re
import subprocess


READ_ROOT = Path(os.environ.get("MG_SYSTEMS_READ_ROOT", "~/github/com-junkawasaki")).expanduser()
VERSIONED_SCOPE_FILE = READ_ROOT / "scripts/hermes-magnesium-systems-bots/system-scope.edn"
INSTALLED_SCOPE_FILE = Path(__file__).resolve().with_name("system-scope.edn")
SCOPE_FILE = VERSIONED_SCOPE_FILE if VERSIONED_SCOPE_FILE.is_file() else INSTALLED_SCOPE_FILE

TARGETS = {
    "kotoba": [
        "orgs/kotoba-lang/kami-engine-vehicle-designer",
        "orgs/kotoba-lang/kami-engine-echem",
        "orgs/kotoba-lang/kami-engine-motor",
        "orgs/kotoba-lang/kami-engine-cae-solver",
        "orgs/kotoba-lang/kami-engine-cad",
        "orgs/kotoba-lang/fea",
        "orgs/kotoba-lang/kami-flow",
    ],
    "itonami": [
        "orgs/cloud-itonami/cloud-itonami-isic-2432",
        "orgs/cloud-itonami/cloud-itonami-isic-2823",
        "orgs/cloud-itonami/cloud-itonami-isic-2720",
        "orgs/cloud-itonami/hydrogen-electrolysis",
        "orgs/cloud-itonami/igata",
        "orgs/cloud-itonami/cad",
    ],
    "wiki": ["orgs/network-awai/app-hyakka"],
}

SEARCH_TERMS = {
    "kotoba": ["magnesium", "hydrogen", "PEM", "fuel cell", "motor", "thermal", "pressure", "fatigue"],
    "itonami": ["magnesium", "die cast", "HPDC", "hydrogen", "PEM", "MES", "traceability", "used equipment"],
    "wiki": ["equipment", "manufacturer", "model", "condition", "price", "currency", "availability", "observed-at"],
}

WIKI_REQUIRED_FIELDS = [
    "manufacturer", "model", "equipment-class", "condition", "price", "currency",
    "availability", "location", "observed-at", "source-url", "seller",
]


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
    code, output = run("rg", "-i", "-l", "--glob", "!node_modules/**", "--glob", "!target/**", term, ".", cwd=path)
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


def print_repo_measurements(scope: str) -> None:
    for relative in TARGETS[scope]:
        path = READ_ROOT / relative
        if not path.is_dir():
            print(f"REPO path={relative} present=no")
            continue
        counts = ",".join(f"{term.replace(' ', '_')}={term_count(path, term)}" for term in SEARCH_TERMS[scope])
        print(
            f"REPO path={relative} present=yes head={git_head(path)} "
            f"tracked_files={tracked_count(path)} test_files={test_file_count(path)} topic_files=[{counts}]"
        )


def print_wiki_measurements() -> None:
    wiki = READ_ROOT / TARGETS["wiki"][0]
    config = wiki / "config/knowledge-ingest.edn"
    if not config.is_file():
        print("WIKI_CONFIG present=no")
        return
    body = config.read_text(errors="replace")
    fields = ",".join(f"{field}={'yes' if field in body else 'no'}" for field in WIKI_REQUIRED_FIELDS)
    equipment_files = []
    for path in wiki.rglob("*"):
        if path.is_file() and "node_modules" not in path.parts and re.search(r"equipment|manufacturer|offer", path.name, re.I):
            equipment_files.append(str(path.relative_to(wiki)))
    print(f"WIKI_CONFIG present=yes required_field_tokens=[{fields}]")
    print(f"WIKI_EQUIPMENT_FILES count={len(equipment_files)} sample={equipment_files[:12]}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("scope", choices=("kotoba", "itonami", "wiki"))
    args = parser.parse_args()

    print("MAGNESIUM_SYSTEMS_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    print(f"scope={args.scope}")
    print(f"read_root={READ_ROOT}")
    if not READ_ROOT.is_dir():
        print("REFUSED read root is absent; do not infer readiness or gaps")
        return 0
    if not SCOPE_FILE.is_file():
        print("REFUSED system-scope.edn is absent; do not infer the requested system boundary")
        return 0
    print(f"scope_sha256={run('shasum', '-a', '256', str(SCOPE_FILE))[1].split()[0]}")
    print_repo_measurements(args.scope)
    if args.scope == "wiki":
        print_wiki_measurements()
    print("END_MAGNESIUM_SYSTEMS_EVIDENCE_V1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
