#!/usr/bin/env python3
"""Audit manifest declarations against contracts in the flat west workspace."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

SUPER_ROOT = Path(__file__).resolve().parents[1]
WEST_ROOT = Path(os.environ.get("COM_JUNKAWASAKI_WEST_ROOT", SUPER_ROOT)).resolve()
WEST_MANIFEST = SUPER_ROOT / "manifest" / "west.yml"
ROOT_REGISTRY = SUPER_ROOT / "manifest" / "lexicon-root-ownership.edn"
ROOT_COMPAT_LEXICONS = WEST_ROOT / "orgs" / "etzhayyim" / "root" / "00-contracts" / "lexicons"
NSID_RE = re.compile(r"^[a-z][a-zA-Z0-9-]*(?:\.[a-zA-Z][a-zA-Z0-9-]*){2,}$")


def west_etzhayyim_paths(path: Path = WEST_MANIFEST) -> list[Path]:
    """Read exact flat actor paths generated into west.yml."""
    if not path.is_file():
        return []
    paths = []
    for raw in path.read_text().splitlines():
        match = re.match(r"^\s+path:\s+(orgs/etzhayyim/com-etzhayyim-[^\s#]+)\s*$", raw)
        if match:
            paths.append(Path(match.group(1)))
    return sorted(set(paths))


def find_manifests() -> list[Path]:
    """Discover one manifest per exact west project path; JSON-LD wins."""
    result = []
    for relative in west_etzhayyim_paths():
        repo = WEST_ROOT / relative
        jsonld, edn = repo / "manifest.jsonld", repo / "manifest.edn"
        if jsonld.is_file():
            result.append(jsonld)
        elif edn.is_file():
            result.append(edn)
    return result


def contract_paths(manifest: Path, nsid: str) -> list[Path]:
    leaf = nsid.rsplit(".", 1)[-1]
    owner = manifest.parent
    parts = nsid.split(".")
    qualified = ".".join(parts[-2:])
    return [
        owner / "wire" / "lex" / f"{leaf}.json",
        owner / "wire" / "lexicons" / f"{leaf}.json",
        owner / "wire" / "lexicons" / f"{qualified}.json",
        owner / "wire" / "contracts" / "lexicons" / f"{leaf}.json",
        owner / "lexicons" / Path(*parts[:-1]) / f"{leaf}.json",
        ROOT_COMPAT_LEXICONS / Path(*parts[:-1]) / f"{leaf}.json",
    ]


def resolve_contract(manifest: Path, nsid: str) -> Path:
    candidates = contract_paths(manifest, nsid)
    return next((path for path in candidates if path.is_file()), candidates[0])


def _tokens(text: str) -> list[tuple[str, str]]:
    tokens, i = [], 0
    while i < len(text):
        char = text[i]
        if char in " \t\r\n,":
            i += 1
        elif char == ";":
            i = text.find("\n", i)
            if i < 0:
                break
        elif char == '"':
            i += 1
            value = []
            while i < len(text) and text[i] != '"':
                if text[i] == "\\" and i + 1 < len(text):
                    i += 1
                    value.append({"n": "\n", "r": "\r", "t": "\t"}.get(text[i], text[i]))
                else:
                    value.append(text[i])
                i += 1
            i += 1
            tokens.append(("string", "".join(value)))
        elif char in "[]{}()":
            tokens.append(("delimiter", char))
            i += 1
        else:
            end = i
            while end < len(text) and text[end] not in ' \t\r\n,;"[]{}()':
                end += 1
            tokens.append(("symbol", text[i:end]))
            i = end
    return tokens


def _vector_strings_after(tokens: list[tuple[str, str]], index: int) -> list[str]:
    result, depth, i = [], 0, index
    while i < len(tokens):
        kind, value = tokens[i]
        if kind == "delimiter" and value == "[":
            depth += 1
        elif kind == "delimiter" and value == "]":
            depth -= 1
            if depth == 0:
                break
        elif depth and kind == "string" and NSID_RE.fullmatch(value):
            result.append(value)
        i += 1
    return result


def edn_nsids(text: str) -> list[str]:
    tokens = _tokens(text)
    keys = {"lexicons", "lexiconNamespaces", ":lexicons", ":lexiconNamespaces",
            ":actor/lexicons", ":actor/lexiconNamespaces"}
    result = []
    for index, (kind, value) in enumerate(tokens[:-1]):
        if kind in {"string", "symbol"} and value in keys and tokens[index + 1] == ("delimiter", "["):
            result.extend(_vector_strings_after(tokens, index + 1))
    return list(dict.fromkeys(result))


def declared_nsids(manifest: Path) -> list[str]:
    if manifest.suffix == ".edn":
        return edn_nsids(manifest.read_text())
    data = json.loads(manifest.read_text())
    result = []
    for key in ("lexicons", "lexiconNamespaces"):
        for value in data.get(key, []):
            if isinstance(value, str):
                result.append(value)
            elif isinstance(value, dict) and isinstance(value.get("id"), str):
                result.append(value["id"])
    return list(dict.fromkeys(result))


def lexicon_file_nsid(path: Path, prefix: str) -> str:
    """Read map or EAVT-vector identity; ambiguous vectors stay visible."""
    try:
        data = json.loads(path.read_text())
        if isinstance(data, dict) and isinstance(data.get("id"), str):
            return data["id"]
        ids = set()
        def collect(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    if isinstance(key, str) and key.endswith("/id") and isinstance(child, str) and NSID_RE.fullmatch(child):
                        ids.add(child)
                    collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)
        if isinstance(data, list):
            collect(data)
            if len(ids) == 1:
                return next(iter(ids))
    except (OSError, json.JSONDecodeError):
        pass
    return f"{prefix}.{path.stem}"


def root_owned_nsids(path: Path = ROOT_REGISTRY) -> set[str]:
    if not path.is_file():
        return set()
    tokens = _tokens(path.read_text())
    for index, token in enumerate(tokens[:-1]):
        if token == ("symbol", ":registry/lexicons") and tokens[index + 1] == ("delimiter", "["):
            return set(_vector_strings_after(tokens, index + 1))
    return set()


def audit() -> dict[str, object]:
    manifests = find_manifests()
    declared, missing, invalid = set(), [], []
    owned_dirs: dict[Path, set[str]] = {}
    for manifest in manifests:
        for nsid in declared_nsids(manifest):
            if not NSID_RE.fullmatch(nsid):
                invalid.append((manifest, nsid))
                continue
            declared.add(nsid)
            contract = resolve_contract(manifest, nsid)
            owned_dirs.setdefault(contract.parent, set()).add(nsid)
            if not contract.is_file():
                missing.append((manifest, nsid, contract))
    orphans = set()
    root_owned = root_owned_nsids()
    for directory, nsids in owned_dirs.items():
        if not directory.is_dir():
            continue
        prefix = next(iter(nsids)).rsplit(".", 1)[0]
        for wire in directory.glob("*.json"):
            nsid = lexicon_file_nsid(wire, prefix)
            if nsid not in declared and nsid not in root_owned:
                orphans.add(nsid)
    return {"manifests": manifests, "declared": declared, "missing": missing,
            "invalid": invalid, "orphans": orphans, "root_owned": root_owned}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    result = audit()
    print(f"West actor manifests scanned: {len(result['manifests'])}")
    print(f"Lexicons declared: {len(result['declared'])}")
    print(f"Root-owned contracts: {len(result['root_owned'])}")
    print(f"Invalid NSIDs: {len(result['invalid'])}")
    print(f"Undeclared orphan lexicons: {len(result['orphans'])}")
    for manifest, nsid, expected in result["missing"]:
        print(f"MISSING {nsid}: {manifest} -> {expected}")
    for nsid in sorted(result["orphans"]):
        print(f"ORPHAN {nsid}")
    print(f"Manifest declarations missing wire JSON: {len(result['missing'])}")
    return 1 if args.strict and (result["missing"] or result["invalid"] or result["orphans"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
