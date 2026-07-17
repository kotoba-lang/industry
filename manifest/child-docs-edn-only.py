#!/usr/bin/env python3
"""Migrate a repository's 90-docs (or any docs dir) to EDN-only SSoT.

Usage:
  python3 manifest/child-docs-edn-only.py <docs-dir> [--verify-only]

Does not git commit/push — caller owns VCS.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


def edn_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def split_md(text: str) -> tuple[dict, str]:
    text = text.replace("\r\n", "\n")
    fm: dict = {}
    if text.startswith("---"):
        rest = text[3:]
        end = rest.find("\n---")
        if end >= 0:
            yaml = rest[:end]
            body = rest[end + 4 :]
            if body.startswith("\n"):
                body = body[1:]
            # minimal YAML-ish: key: value and key: + list items
            key = None
            buf: list[str] = []
            list_mode = False
            for raw in yaml.splitlines():
                line = raw.replace("\t", "  ")
                trimmed = line.strip()
                if not trimmed or trimmed.startswith("#"):
                    continue
                if list_mode and re.match(r"^\s+-\s+", line):
                    item = re.sub(r"^\s*-\s+", "", line).strip().strip('"').strip("'")
                    buf.append(item)
                    continue
                m = re.match(r"^([A-Za-z0-9_./-]+):\s*(.*)$", trimmed)
                if m:
                    if key and list_mode:
                        fm[key] = buf
                    k, restv = m.group(1), m.group(2).strip()
                    key = k
                    if restv == "":
                        list_mode = True
                        buf = []
                    else:
                        list_mode = False
                        if restv in ("true", "false"):
                            fm[k] = restv == "true"
                        elif (restv.startswith('"') and restv.endswith('"')) or (
                            restv.startswith("'") and restv.endswith("'")
                        ):
                            fm[k] = restv[1:-1]
                        else:
                            fm[k] = restv
                elif key and not list_mode:
                    fm[key] = str(fm.get(key, "")) + " " + trimmed
            if key and list_mode:
                fm[key] = buf
            return fm, body.rstrip() + ("\n" if body else "")
    return {}, text.rstrip() + ("\n" if text else "")


def safe_name(s: str) -> str:
    n = re.sub(r"[^A-Za-z0-9*+!\-_'.?]", "-", str(s))
    n = re.sub(r"^-+", "", n)
    if re.match(r"[0-9]", n or "x"):
        n = "n-" + n
    return n or "x"


def md_to_entity(md_path: Path, docs_root: Path, repo_label: str) -> str:
    text = md_path.read_text(encoding="utf-8", errors="replace")
    fm, body = split_md(text)
    rel = str(md_path.relative_to(docs_root.parent) if docs_root.name == "90-docs" else md_path)
    # prefer path relative to repo root (parent of 90-docs)
    try:
        repo_root = docs_root.parent
        rel = str(md_path.relative_to(repo_root))
    except Exception:
        rel = str(md_path)

    stem = md_path.stem
    title = fm.get("title") or (
        m.group(1).strip() if (m := re.search(r"(?m)^#\s+(.+)$", body)) else stem
    )
    status = str(fm.get("status") or "active")
    if isinstance(status, bool):
        status = "active"
    status = str(status).split()[0].lower().strip(".,;")
    doc_type = str(fm.get("doc_type") or ("adr" if "/adr/" in rel or re.match(r"^\d{4}", stem) else "doc"))
    id_ = str(fm.get("id") or f"{'adr' if doc_type == 'adr' else 'doc'}-{stem}")
    topic = fm.get("topic")
    date = fm.get("last_verified") or fm.get("date")

    attrs = {
        "id": id_,
        "title": title,
        "status": status,
        "doc_type": doc_type,
        "source-format": "edn",
        "body": body,
        "path": rel.replace(".md", ".edn"),
        "source-repo": repo_label,
    }
    if topic:
        attrs["topic"] = topic
    if date:
        attrs["last_verified"] = str(date)
        attrs["date"] = str(date)
    if "authoritative" in fm:
        attrs["authoritative"] = bool(fm["authoritative"]) if isinstance(fm["authoritative"], bool) else str(fm["authoritative"]).lower() == "true"
    for k in ("related", "authoritative_for", "supersedes", "superseded_by", "deciders"):
        if k in fm and isinstance(fm[k], list):
            attrs[k] = fm[k]

    ns = "adr" if doc_type == "adr" else "doc"
    parts = [":db/id -1"]
    for k, v in attrs.items():
        kk = f":{ns}/{safe_name(k)}"
        if isinstance(v, bool):
            parts.append(f"{kk} {'true' if v else 'false'}")
        elif isinstance(v, list):
            items = " ".join(edn_str(str(x)) for x in v)
            parts.append(f"{kk} [{items}]")
        else:
            parts.append(f"{kk} {edn_str(str(v))}")
    return "[{" + ", ".join(parts) + "}]\n"


def is_tx_data(text: str) -> bool:
    st = text.lstrip()
    while st.startswith(";"):
        st = "\n".join(st.split("\n")[1:]).lstrip()
    return st.startswith("[{") and ":db/id" in text


def wrap_existing_edn(text: str, ns: str = "adr") -> str | None:
    st = text.lstrip()
    while st.startswith(";"):
        # keep comments? drop for tx-data purity
        lines = st.split("\n")
        i = 0
        while i < len(lines) and (not lines[i].strip() or lines[i].lstrip().startswith(";")):
            i += 1
        st = "\n".join(lines[i:])
    if is_tx_data(text):
        return None
    if st.startswith("{") and not st.startswith("[{"):
        # frontmatter form or flat map
        if ":db/id" not in st:
            st = "{:db/id -1, " + st[1:]
        return "[" + st.rstrip() + "]\n"
    if st.startswith("[{") and ":db/id" not in st:
        return st.replace("{", "{:db/id -1, ", 1)
    return None


def migrate_docs_dir(docs_dir: Path, repo_label: str) -> dict:
    stats = {
        "md_converted": 0,
        "md_deleted": 0,
        "md_edn_renamed": 0,
        "edn_wrapped": 0,
        "path_rewrites": 0,
        "errors": [],
    }
    if not docs_dir.is_dir():
        stats["errors"].append(f"missing {docs_dir}")
        return stats

    # 1) rename *.md.edn -> *.edn (already EDN content)
    for f in list(docs_dir.rglob("*.md.edn")):
        target = f.with_name(f.name[: -len(".md.edn")] + ".edn")
        if target.exists():
            # keep both content: prefer target, delete .md.edn if identical-ish
            f.unlink()
            stats["md_edn_renamed"] += 1
            continue
        f.rename(target)
        stats["md_edn_renamed"] += 1

    # 2) convert remaining pure .md
    for md in list(docs_dir.rglob("*.md")):
        try:
            edn = md.with_suffix(".edn")
            if not edn.exists():
                edn.write_text(md_to_entity(md, docs_dir, repo_label), encoding="utf-8")
                stats["md_converted"] += 1
            else:
                # merge body if missing
                et = edn.read_text(encoding="utf-8", errors="replace")
                if ":adr/body" not in et and ":doc/body" not in et and ":body" not in et:
                    body = split_md(md.read_text(encoding="utf-8", errors="replace"))[1]
                    # inject body after first {
                    if et.lstrip().startswith("[{"):
                        idx = et.find("{")
                        et = (
                            et[: idx + 1]
                            + f":adr/body {edn_str(body)}, "
                            + et[idx + 1 :]
                        )
                        edn.write_text(et, encoding="utf-8")
            md.unlink()
            stats["md_deleted"] += 1
        except Exception as e:
            stats["errors"].append((str(md), str(e)))

    # 3) wrap bare maps to tx-data
    for f in docs_dir.rglob("*.edn"):
        # skip deps.edn-like project maps if named deps.edn
        if f.name == "deps.edn":
            continue
        try:
            t = f.read_text(encoding="utf-8", errors="replace")
            wrapped = wrap_existing_edn(t)
            if wrapped is not None:
                f.write_text(wrapped, encoding="utf-8")
                stats["edn_wrapped"] += 1
        except Exception as e:
            stats["errors"].append((str(f), str(e)))

    # 4) seal path refs + source-format inside this docs dir
    path_md = re.compile(r"(90-docs/[A-Za-z0-9_./+-]+)\.md\b")
    path_md_edn = re.compile(r"(90-docs/[A-Za-z0-9_./+-]+)\.md\.edn\b")
    source_fmt = re.compile(
        r":(adr|doc)/source-format\s+\"(?:md-migrated|edn\+md-merged)\""
    )
    inv_kw = re.compile(r":([A-Za-z][A-Za-z0-9._-]*)/([0-9][A-Za-z0-9*+!\-'.?]*)")
    for f in docs_dir.rglob("*.edn"):
        if f.name == "deps.edn":
            continue
        t = f.read_text(encoding="utf-8", errors="replace")
        t2 = path_md_edn.sub(lambda m: m.group(1) + ".edn", t)
        t2 = path_md.sub(lambda m: m.group(1) + ".edn", t2)
        t2 = source_fmt.sub(lambda m: f':{m.group(1)}/source-format "edn"', t2)
        t2 = inv_kw.sub(r":\1/n-\2", t2)
        t2 = re.sub(r"README\.md\b", "README.edn", t2)
        if t2 != t:
            f.write_text(t2, encoding="utf-8")
            stats["path_rewrites"] += 1

    return stats


def verify_docs_dir(docs_dir: Path) -> dict:
    md = list(docs_dir.rglob("*.md"))
    edn = [f for f in docs_dir.rglob("*.edn") if f.name != "deps.edn"]
    path_md = re.compile(r"90-docs/[A-Za-z0-9_./+-]+\.md\b")
    bad_sf = re.compile(r':(?:adr|doc)/source-format\s+"(?:md-migrated|edn\+md-merged)"')
    path_hits = []
    sf_hits = []
    for f in edn:
        t = f.read_text(encoding="utf-8", errors="replace")
        if path_md.search(t):
            path_hits.append(str(f))
        if bad_sf.search(t):
            sf_hits.append(str(f))
    return {
        "md": len(md),
        "edn": len(edn),
        "path_md_refs": len(path_hits),
        "source_format_residue": len(sf_hits),
        "ok": len(md) == 0 and len(path_hits) == 0 and len(sf_hits) == 0,
    }


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: child-docs-edn-only.py <docs-dir> [--verify-only]", file=sys.stderr)
        return 2
    docs = Path(argv[1]).resolve()
    verify_only = "--verify-only" in argv
    repo_label = docs.parent.name if docs.name == "90-docs" else docs.name
    if not verify_only:
        stats = migrate_docs_dir(docs, repo_label)
        print("migrate", docs, stats)
    v = verify_docs_dir(docs)
    print("verify", v)
    return 0 if v["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
