"""M02 - Table 1: top-variant reconstruction (L2).

Independent part: the row *selection and ordering* is recomputed from
DS_GWAS_CURRENT by ranking eligible rows on full-precision P ascending with
deterministic (chromosome, position, normalized variant key) tie-breaking.

Checkpoint part: exact case AC/AF and control AF are joined from
DS_TABLE1_CHECKPOINT. Counts are never inferred from rounded frequencies, and
no end-to-end provider GWAS rerun is claimed.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

import re

from common import (
    GENOME_WIDE_THRESHOLD,
    OUT,
    SUGGESTIVE_THRESHOLD,
    ds,
    ds_sha,
    normalized_variant_key,
    repr_full,
    write_tsv,
)
from m01_fig1 import load_gwas

DEST = OUT / "TABLE1"

_RS = re.compile(r"^rs\d+$")
_PREFIXED_RS = re.compile(r"^[A-Za-z0-9]+-(rs\d+)$")


def display_rsid(variant_id: str) -> str:
    """Map an array-probe variant_id to its plain rsID for display.

    DS_GWAS_CURRENT carries Illumina probe identifiers (e.g. 'GSA-rs146572333')
    while DS_TABLE1_CHECKPOINT displays the bare rsID. Only a single leading
    'TOKEN-' is stripped, and only when the remainder is itself a plain rsID;
    anything else is returned unchanged. Logged as a method deviation.
    """
    s = str(variant_id)
    if _RS.match(s):
        return s
    m = _PREFIXED_RS.match(s)
    return m.group(1) if m else s


def sig3(x: float) -> str:
    """Three significant digits, as required for displayed P."""
    return f"{float(x):.3g}" if pd.notna(x) else ""


def dec3(x) -> str:
    return f"{float(x):.3f}" if pd.notna(x) and x != "" else ""


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    audit: dict[str, object] = {}

    chk = pd.read_csv(ds("DS_TABLE1_CHECKPOINT"), sep="\t", dtype=str)
    n_rows = len(chk)
    audit["checkpoint_sha256"] = ds_sha("DS_TABLE1_CHECKPOINT")
    audit["checkpoint_row_count"] = n_rows

    # --- independent ranking from the current GWAS snapshot ---------------
    g = load_gwas()
    g["chrom_int"] = pd.to_numeric(
        g["chromosome"].astype(str).str.replace(r"^chr", "", regex=True), errors="coerce")
    g["position_grch37"] = pd.to_numeric(g["position_grch37"], errors="coerce")
    g["p_value"] = pd.to_numeric(g["p_value"], errors="coerce")

    # "Use the current artifact status/filter field" -> qc_status eligibility.
    audit["qc_status_values"] = sorted(set(g["qc_status"].astype(str)))
    eligible = (
        g["qc_status"].astype(str).str.startswith("PASS")
        & g["chrom_int"].between(1, 22)
        & np.isfinite(g["position_grch37"])
        & np.isfinite(g["p_value"])
        & (g["p_value"] > 0)
        & (g["p_value"] <= 1)
    )
    e = g.loc[eligible].copy()
    e["variant_key"] = [
        normalized_variant_key(c, b, ea, oa)
        for c, b, ea, oa in zip(e["chrom_int"].astype(int),
                                e["position_grch37"].astype(np.int64),
                                e["effect_allele"], e["other_allele"])
    ]
    audit["eligible_rows"] = int(len(e))
    audit["excluded_by_status_or_validity"] = int(len(g) - len(e))

    ranked = e.sort_values(
        ["p_value", "chrom_int", "position_grch37", "variant_key"],
        kind="mergesort",
    ).reset_index(drop=True)
    top = ranked.head(n_rows).copy()
    top.insert(0, "rank", np.arange(1, len(top) + 1))
    top["chrpos"] = [f"{int(c)}:{int(b)}" for c, b in
                     zip(top["chrom_int"], top["position_grch37"])]
    top["display_rsid"] = [display_rsid(x) for x in top["variant_id"]]

    audit["independent_top_variant_id"] = str(top.loc[0, "display_rsid"])
    audit["independent_top_variant_id_raw"] = str(top.loc[0, "variant_id"])
    audit["independent_top_p"] = float(top.loc[0, "p_value"])
    # Tie audit: does any P value repeat inside the selected set or at its edge?
    pmax_sel = float(top["p_value"].max())
    audit["n_rows_tied_at_selection_boundary"] = int((e["p_value"] == pmax_sel).sum())
    audit["selection_boundary_p"] = pmax_sel

    # --- agreement of independent selection vs the checkpoint -------------
    # Join on the contract's own biological key (GRCh37 chr:position), not on
    # the display identifier, because DS_GWAS_CURRENT uses array probe IDs.
    chk_snp = list(chk["SNP"])
    chk_pos = list(chk["Chr:position"])
    ind_snp = list(top["display_rsid"].astype(str))
    ind_pos = list(top["chrpos"].astype(str))
    audit["checkpoint_order_snps"] = chk_snp
    audit["independent_order_snps"] = ind_snp
    audit["row_selection_set_match"] = sorted(chk_pos) == sorted(ind_pos)
    audit["row_order_match"] = chk_pos == ind_pos
    audit["rsid_order_match"] = chk_snp == ind_snp

    chk_idx = chk.set_index("Chr:position")
    rs_ok, allele_ok, p_rel = [], [], []
    for _, r in top.iterrows():
        key = r["chrpos"]
        if key not in chk_idx.index:
            rs_ok.append(False)
            allele_ok.append(False)
            p_rel.append(float("nan"))
            continue
        c = chk_idx.loc[key]
        rs_ok.append(str(r["display_rsid"]) == str(c["SNP"]))
        allele_ok.append(str(r["effect_allele"]).upper() == str(c["Effect allele"]).upper()
                         and str(r["other_allele"]).upper() == str(c["Other allele"]).upper())
        p_rel.append(abs(float(r["p_value"]) - float(c["P value"])) / float(c["P value"]))
    audit["rsid_match_all"] = bool(np.all(rs_ok))
    audit["allele_orientation_match_all"] = bool(np.all(allele_ok))
    audit["max_relative_p_diff_vs_checkpoint_display"] = float(np.nanmax(p_rel))

    # beta / SE agreement (checkpoint carries 3-decimal display values).
    b_abs, se_abs = [], []
    for _, r in top.iterrows():
        c = chk_idx.loc[r["chrpos"]]
        b_abs.append(abs(round(float(r["beta"]), 3) - float(c["β"])))
        se_abs.append(abs(round(float(r["standard_error"]), 3) - float(c["SE"])))
    audit["max_abs_beta_diff_after_3dp_rounding"] = float(np.max(b_abs))
    audit["max_abs_se_diff_after_3dp_rounding"] = float(np.max(se_abs))

    # --- significance label recomputed from the declared thresholds -------
    def significance(p: float) -> str:
        if p < GENOME_WIDE_THRESHOLD:
            return "Genome-wide"
        if p < SUGGESTIVE_THRESHOLD:
            return "Suggestive"
        return "Not significant"

    top["significance_recomputed"] = [significance(x) for x in top["p_value"]]
    sig_match = [a == b for a, b in zip(top["significance_recomputed"],
                                        chk_idx.loc[ind_pos, "Significance"])]
    audit["significance_label_match_all"] = bool(np.all(sig_match))

    # --- assembled Table 1 -------------------------------------------------
    # Row order preserved from the independent ranking (identical to checkpoint
    # order when row_order_match is true). Case AC/AF and Control AF are taken
    # verbatim from the checkpoint, never derived.
    rows = []
    for _, r in top.iterrows():
        c = chk_idx.loc[r["chrpos"]]
        rows.append([
            int(r["rank"]), str(r["display_rsid"]), r["chrpos"],
            str(r["effect_allele"]).upper(), str(r["other_allele"]).upper(),
            sig3(r["p_value"]), dec3(r["beta"]), dec3(r["standard_error"]),
            c["Case AF"], c["Control AF"], c["Case AC"],
            r["significance_recomputed"],
            # full-precision audit columns
            repr_full(float(r["p_value"])), repr_full(float(r["beta"])),
            repr_full(float(r["standard_error"])), r["variant_key"],
            str(r["variant_id"]),
        ])
    header = ["rank", "SNP", "Chr:position", "Effect allele", "Other allele",
              "P value", "beta", "SE", "Case AF", "Control AF", "Case AC",
              "Significance", "p_value_full_precision", "beta_full_precision",
              "se_full_precision", "normalized_variant_key", "source_variant_id"]
    write_tsv(DEST / "TABLE1_reconstructed.tsv", header, rows)

    write_tsv(DEST / "TABLE1_selection_audit.tsv",
              ["rank", "independent_chrpos", "checkpoint_chrpos", "order_match",
               "independent_rsid", "checkpoint_rsid", "rsid_match", "allele_match",
               "significance_match"],
              [[i + 1, ind_pos[i], chk_pos[i], ind_pos[i] == chk_pos[i],
                ind_snp[i], chk_snp[i], rs_ok[i], allele_ok[i], sig_match[i]]
               for i in range(n_rows)])

    (DEST / "TABLE1_audit.json").write_text(json.dumps(audit, indent=2, default=str),
                                            encoding="utf-8")

    claims = {
        "TABLE1_ROW_COUNT": n_rows,
        "TABLE1_NORMALIZED_SHA256": audit["checkpoint_sha256"],
        "TABLE1_TOP_VARIANT_ID": audit["independent_top_variant_id"],
    }
    (DEST / "TABLE1_claims.json").write_text(
        json.dumps({k: repr_full(v) for k, v in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit}


if __name__ == "__main__":
    r = main()
    for k, v in r["claims"].items():
        print(f"{k}\t{repr_full(v)}")
    print("\n-- selection agreement --")
    for k in ("checkpoint_row_count", "eligible_rows", "row_selection_set_match",
              "row_order_match", "rsid_order_match", "rsid_match_all", "allele_orientation_match_all",
              "significance_label_match_all", "max_relative_p_diff_vs_checkpoint_display",
              "max_abs_beta_diff_after_3dp_rounding", "max_abs_se_diff_after_3dp_rounding",
              "n_rows_tied_at_selection_boundary"):
        print(f"{k}\t{r['audit'][k]}")
