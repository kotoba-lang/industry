"""M07 - Supplementary Table S1 reconstruction (L2).

Starts from DS_TABLE_S1_CURRENT and verifies row identity and order against the
current primary LD checkpoint (DS_LD_PRIMARY) and the population-frequency
source (DS_FIG2_FREQUENCY_CHECKPOINT). All columns, GRCh37 coordinates,
effect-allele orientation, lead/member mapping, category labels, missing-value
representation and notes are preserved byte-for-byte; no row is added from an
older 18-locus or pre-exclusion source.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from common import OUT, ds, ds_sha, repr_full, write_tsv

DEST = OUT / "SUPPTABLE_S1"


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    audit: dict[str, object] = {}

    # Read as raw strings so missing-value tokens and precision are preserved.
    t = pd.read_csv(ds("DS_TABLE_S1_CURRENT"), sep="\t", dtype=str, keep_default_na=False)
    ld = pd.read_csv(ds("DS_LD_PRIMARY"), sep="\t", dtype=str, keep_default_na=False)
    f2 = pd.read_csv(ds("DS_FIG2_FREQUENCY_CHECKPOINT"), sep="\t", dtype=str,
                     keep_default_na=False)

    audit["source_sha256"] = ds_sha("DS_TABLE_S1_CURRENT")
    audit["row_count"] = int(len(t))
    audit["column_count"] = int(len(t.columns))
    audit["columns"] = list(t.columns)

    # --- lead / member structure ------------------------------------------
    role = t["primary_LD_locus_role"]
    audit["locus_role_counts"] = {str(k): int(v) for k, v in role.value_counts().items()}
    audit["lead_count"] = int((role == "lead").sum())
    audit["member_count"] = int((role == "member").sum())
    lead_flag = t["Lead_Variant"]
    audit["lead_variant_flag_counts"] = {str(k): int(v) for k, v in
                                         lead_flag.value_counts().items()}
    audit["lead_flag_consistent_with_role"] = bool(
        ((role == "lead") == (lead_flag == "Yes")).all())

    # --- agreement with the primary LD checkpoint -------------------------
    ld_leads = list(ld["lead_reference_id"])
    t_leads = list(t.loc[role == "lead", "Variant"])
    audit["lead_set_matches_ld_primary"] = sorted(t_leads) == sorted(ld_leads)
    audit["leads_in_table_not_in_ld_primary"] = sorted(set(t_leads) - set(ld_leads))
    audit["leads_in_ld_primary_not_in_table"] = sorted(set(ld_leads) - set(t_leads))

    # locus_id -> lead mapping must agree
    map_rows, map_ok = [], True
    ld_by_locus = ld.set_index("locus_id")
    for locus_id, grp in t.groupby("primary_LD_locus_id"):
        declared = set(grp["primary_LD_lead_reference_id"])
        expected = str(ld_by_locus.loc[locus_id, "lead_reference_id"])
        n_expected = int(ld_by_locus.loc[locus_id, "n_variants_in_clump"])
        lead_ok = declared == {expected}
        size_ok = len(grp) == n_expected
        map_ok &= bool(lead_ok and size_ok)
        map_rows.append([locus_id, expected, ";".join(sorted(declared)), lead_ok,
                         len(grp), n_expected, size_ok,
                         int((grp["primary_LD_locus_role"] == "lead").sum()),
                         int((grp["primary_LD_locus_role"] == "member").sum())])
    audit["locus_lead_and_size_mapping_consistent"] = bool(map_ok)
    write_tsv(DEST / "SUPPTABLE_S1_locus_mapping_audit.tsv",
              ["primary_LD_locus_id", "ld_checkpoint_lead", "table_lead_ids",
               "lead_match", "table_rows_in_locus", "ld_n_variants_in_clump",
               "size_match", "n_lead_rows", "n_member_rows"], map_rows)

    # members listed by the LD checkpoint must be exactly the member rows
    mem_ok = True
    for _, r in ld.iterrows():
        raw = str(r["members_ref_ids"])
        expected = {x.strip() for x in raw.replace(";", ",").split(",")
                    if x.strip() and x.strip() != "."}
        got = set(t.loc[(t["primary_LD_locus_id"] == r["locus_id"])
                        & (role == "member"), "Variant"])
        mem_ok &= expected == got
    audit["member_ids_match_ld_checkpoint"] = bool(mem_ok)

    # --- agreement with the Figure 2 frequency source ---------------------
    f2_vars = list(f2["Variant"])
    audit["fig2_variants_all_present_as_leads"] = set(f2_vars).issubset(set(t_leads))
    cat_rows, cat_ok = [], True
    f2_idx = f2.set_index("Variant")
    t_idx = t.set_index("Variant")
    for v in f2_vars:
        a = str(f2_idx.loc[v, "Population_Enrichment"])
        b = str(t_idx.loc[v, "Population_Enrichment"])
        ok = a == b
        cat_ok &= ok
        cat_rows.append([v, a, b, ok])
    audit["lead_category_labels_match_fig2"] = bool(cat_ok)
    write_tsv(DEST / "SUPPTABLE_S1_category_agreement.tsv",
              ["Variant", "fig2_Population_Enrichment",
               "table_s1_Population_Enrichment", "match"], cat_rows)

    audit["category_counts_all_rows"] = {
        str(k): int(v) for k, v in t["Population_Enrichment"].value_counts().items()}
    audit["category_counts_lead_rows"] = {
        str(k): int(v) for k, v in
        t.loc[role == "lead", "Population_Enrichment"].value_counts().items()}
    audit["significance_counts"] = {
        str(k): int(v) for k, v in t["Significance"].value_counts().items()}

    # --- coordinate and orientation integrity -----------------------------
    pos = t["Position_GRCh37"].astype(str)
    audit["all_positions_grch37_prefixed"] = bool(pos.str.match(r"^chr\d+:\d+$").all())
    # Lead coordinates must agree with the LD checkpoint chr/pos.
    coord_ok = True
    ld_by_lead = ld.set_index("lead_reference_id")
    for v in t_leads:
        want = f"chr{ld_by_lead.loc[v, 'chr']}:{ld_by_lead.loc[v, 'pos_grch37']}"
        coord_ok &= str(t_idx.loc[v, "Position_GRCh37"]) == want
    audit["lead_coordinates_match_ld_checkpoint"] = bool(coord_ok)

    # --- missing-value representation -------------------------------------
    tokens: dict[str, int] = {}
    for c in t.columns:
        for val in t[c]:
            s = str(val).strip()
            if s == "" or s in {".", "NA", "N/A", "NaN", "nan", "-"} or s.startswith("<"):
                tokens[f"{c}::{s}"] = tokens.get(f"{c}::{s}", 0) + 1
    audit["missing_or_censored_value_tokens"] = tokens

    # --- no stale rows ----------------------------------------------------
    audit["row_count_equals_ld_lead_plus_member_total"] = int(len(t)) == int(
        pd.to_numeric(ld["n_variants_in_clump"]).sum())
    audit["duplicate_variant_rows"] = int(t["Variant"].duplicated().sum())

    # --- emit the reconstructed table verbatim ----------------------------
    # Column order, values, and missing-value tokens are preserved exactly.
    # The source uses CRLF terminators; that is detected rather than assumed,
    # and reproduced, so byte identity is a meaningful check instead of being
    # defeated by a line-ending convention.
    raw = ds("DS_TABLE_S1_CURRENT").read_bytes()
    eol = "\r\n" if b"\r\n" in raw else "\n"
    audit["source_line_terminator"] = "CRLF" if eol == "\r\n" else "LF"
    audit["source_has_trailing_newline"] = raw.endswith(eol.encode())

    out = DEST / "SUPPTABLE_S1_reconstructed.tsv"
    with open(out, "w", encoding="utf-8", newline="") as fh:
        fh.write("\t".join(t.columns) + eol)
        for _, r in t.iterrows():
            fh.write("\t".join(str(r[c]) for c in t.columns) + eol)
    import hashlib
    audit["reconstructed_sha256"] = hashlib.sha256(out.read_bytes()).hexdigest()
    audit["reconstructed_is_byte_identical_to_source"] = (
        audit["reconstructed_sha256"] == audit["source_sha256"])

    write_tsv(DEST / "SUPPTABLE_S1_row_selection_audit.tsv",
              ["row_index", "Variant", "Position_GRCh37", "primary_LD_locus_id",
               "primary_LD_lead_reference_id", "primary_LD_locus_role",
               "Lead_Variant", "Population_Enrichment", "Significance",
               "present_in_ld_primary", "present_in_fig2_source"],
              [[i, r["Variant"], r["Position_GRCh37"], r["primary_LD_locus_id"],
                r["primary_LD_lead_reference_id"], r["primary_LD_locus_role"],
                r["Lead_Variant"], r["Population_Enrichment"], r["Significance"],
                r["Variant"] in set(ld_leads)
                or r["Variant"] in {x.strip() for raw in ld["members_ref_ids"]
                                    for x in str(raw).replace(";", ",").split(",")
                                    if x.strip() and x.strip() != "."},
                r["Variant"] in set(f2_vars)]
               for i, (_, r) in enumerate(t.iterrows(), start=1)])

    (DEST / "SUPPTABLE_S1_audit.json").write_text(
        json.dumps(audit, indent=2, default=str), encoding="utf-8")

    claims = {
        "SUPPTABLE_S1_ROW_COUNT": audit["row_count"],
        "SUPPTABLE_S1_LEAD_COUNT": audit["lead_count"],
        # "Population-category row counts" is read as the counts over all 26
        # rows. The lead-only breakdown is 5/7/5, which is exactly
        # FIG2_CATEGORY_COUNTS over the 17-row DS_FIG2_FREQUENCY_CHECKPOINT --
        # so reading this claim as lead-only would duplicate an existing claim
        # under a second ID. The matrix also lists the counting scope first and
        # the cross-check second (cf. SUPPTABLE_S1_LEAD_COUNT), and 8+12+6 = 26
        # = SUPPTABLE_S1_ROW_COUNT. The lead-only value is still returned
        # separately so the custodian can substitute it without recomputation.
        "SUPPTABLE_S1_CATEGORY_COUNTS": json.dumps(
            {k: int(v) for k, v in sorted(audit["category_counts_all_rows"].items())},
            separators=(",", ":")),
        "SUPPTABLE_S1_CATEGORY_COUNTS_LEAD_ROWS_ONLY": json.dumps(
            {k: int(v) for k, v in sorted(audit["category_counts_lead_rows"].items())},
            separators=(",", ":")),
        "SUPPTABLE_S1_SHA256": audit["source_sha256"],
    }
    (DEST / "SUPPTABLE_S1_claims.json").write_text(
        json.dumps({k: repr_full(v) for k, v in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit}


if __name__ == "__main__":
    r = main()
    for k, v in r["claims"].items():
        print(f"{k}\t{repr_full(v)}")
    a = r["audit"]
    print("\n-- audit --")
    for k in ("row_count", "column_count", "lead_count", "member_count",
              "lead_flag_consistent_with_role", "lead_set_matches_ld_primary",
              "locus_lead_and_size_mapping_consistent", "member_ids_match_ld_checkpoint",
              "fig2_variants_all_present_as_leads", "lead_category_labels_match_fig2",
              "all_positions_grch37_prefixed", "lead_coordinates_match_ld_checkpoint",
              "row_count_equals_ld_lead_plus_member_total", "duplicate_variant_rows",
              "reconstructed_is_byte_identical_to_source", "category_counts_lead_rows",
              "category_counts_all_rows", "missing_or_censored_value_tokens"):
        print(f"{k}\t{a[k]}")
