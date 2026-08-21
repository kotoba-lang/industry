#!/usr/bin/env python3
"""Generate the mechanical return tables for the independent reconstruction.

Covers 04_INPUT_HASH_AUDIT (all 26 datasets), 06_SOFTWARE_ENVIRONMENT, and the
FIG1 rows of 01_INDEPENDENT_ARTIFACT_RESULTS. The deviation log, visual QA and
AI assistance log are authored by hand because they record judgements, not
computations.
"""

import csv
import hashlib
import json
import pathlib
import platform
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PKG = ROOT / "reconstruction-package-a-v1.1"
OUT = ROOT / "audit/return/KAWASAKI_PHASE5C_INDEPENDENT_RETURN"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_expected():
    """Map package-relative path -> expected sha256 from SHA256SUMS.txt."""
    expected = {}
    with open(PKG / "SHA256SUMS.txt") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            digest, _, rel = line.partition("  ")
            expected[rel.strip().lstrip("./")] = digest.strip()
    return expected


def input_hash_audit():
    expected = load_expected()
    rows = []
    with open(PKG / "04_DATA_PAYLOAD/00_INDEX/DATASET_MASTER_INDEX.tsv") as fh:
        for rec in csv.DictReader(fh, delimiter="\t"):
            ds, rel = rec["dataset_id"], rec["relative_path"]
            path = PKG / rel
            exp = expected.get(rel, "")
            if not path.exists():
                rows.append((ds, rel, exp, "", "MISSING", "file not present in package"))
                continue
            obs = sha256(path)
            if not exp:
                status, note = "NO_EXPECTED_VALUE", "path absent from SHA256SUMS.txt"
            elif obs == exp:
                status, note = "MATCH", ""
            else:
                status, note = "MISMATCH", "observed differs from SHA256SUMS.txt"
            rows.append((ds, rel, exp, obs, status, note))

    with open(OUT / "04_INPUT_HASH_AUDIT.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["dataset_id", "relative_path", "expected_sha256",
                    "observed_sha256", "status", "notes"])
        w.writerows(rows)
    return rows


def software_environment():
    import matplotlib, numpy, pandas, scipy
    plat = f"{platform.system()} {platform.release()} {platform.machine()}"
    rows = [
        ("language", "Python", platform.python_version(), plat,
         "independent reconstruction runtime",
         "venv at .venv-audit; isolated from system python"),
        ("array", "numpy", numpy.__version__, plat, "numeric arrays", ""),
        ("stats", "scipy", scipy.__version__, plat,
         "chi-square quantiles for lambda_GC",
         "used instead of hand-rolled quantiles so the audit code is not less "
         "trustworthy than its target"),
        ("dataframe", "pandas", pandas.__version__, plat, "tabular IO", ""),
        ("plotting", "matplotlib", matplotlib.__version__, plat,
         "Figure 1 rendering", "Agg backend; SVG primary, PNG preview"),
    ]
    with open(OUT / "06_SOFTWARE_ENVIRONMENT.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["component", "software", "version", "platform", "purpose", "notes"])
        w.writerows(rows)
    return rows


def artifact_results():
    counts = json.loads((OUT / "03_SOURCE_TABLES/fig1_counts.json").read_text())
    rows = [
        ("FIG1_SOURCE_SHA256", "FIG1", counts["source_sha256"], "L1_RECALCULATED",
         "DS_GWAS_CURRENT", "prespec band A (exact)"),
        ("FIG1_VALID_VARIANTS", "FIG1", str(counts["valid_plotted_variants"]),
         "L1_RECALCULATED", "DS_GWAS_CURRENT",
         "prespec band A (exact); 0 rows dropped by the M01 validity filter"),
        ("FIG1_LAMBDA_GC", "FIG1", repr(counts["lambda_gc_full_precision"]),
         "L1_RECALCULATED", "DS_GWAS_CURRENT",
         "prespec band B (continuous); displays as 1.054"),
        ("FIG1_GW_THRESHOLD", "FIG1", "5e-08", "L1_RECALCULATED", "DS_GWAS_CURRENT",
         "prespec band A (exact); contract constant"),
        ("FIG1_SUGGESTIVE_THRESHOLD", "FIG1", "1e-05", "L1_RECALCULATED",
         "DS_GWAS_CURRENT", "prespec band A (exact); contract constant"),
        ("FIG1_PANEL_COUNT", "FIG1", "2", "L1_RECALCULATED", "DS_GWAS_CURRENT",
         "prespec band A (exact); Manhattan + QQ"),
    ]
    with open(OUT / "01_INDEPENDENT_ARTIFACT_RESULTS.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["claim_id", "artifact_id", "value", "method_status",
                    "source_dataset_ids", "notes"])
        w.writerows(rows)
    return rows


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ha = input_hash_audit()
    software_environment()
    artifact_results()

    from collections import Counter
    tally = Counter(r[4] for r in ha)
    print(f"04_INPUT_HASH_AUDIT.tsv      : {len(ha)} datasets  {dict(tally)}")
    for r in ha:
        if r[4] != "MATCH":
            print(f"  !! {r[0]:32s} {r[4]}  {r[5]}")
    print("06_SOFTWARE_ENVIRONMENT.tsv  : written")
    print("01_INDEPENDENT_ARTIFACT_RESULTS.tsv : 6 FIG1 claims (of 91 total)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
