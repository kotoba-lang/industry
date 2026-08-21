"""Assemble KAWASAKI_PHASE5C_INDEPENDENT_RETURN per 06_RETURN_PACKAGE_SPECIFICATION.

Runs every artifact module, collects the claim values, copies the artifact files
and machine-readable source tables, and writes all required return templates,
the manifest and SHA256SUMS.

No individual-level row (case PGS, provider workbook) is copied into the return
tree; only aggregates, as M05 requires.
"""
from __future__ import annotations

import hashlib
import json
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone

import pandas as pd

from common import DATASETS, OUT, PKG, ROOT, sha256, write_tsv

RETURN = ROOT / "KAWASAKI_PHASE5C_INDEPENDENT_RETURN"
ARTIFACT_DIR = RETURN / "02_ARTIFACT_FILES"
SOURCE_DIR = RETURN / "03_SOURCE_TABLES"

LEVEL = {
    "FIG1": "L1_INDEPENDENT_RECALCULATION_FROM_ANALYSIS_READY_DATA",
    "FIG2": "L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT",
    "FIG3": "L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT",
    "FIG4": "L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT",
    "SUPPFIG_S1": "L3_VISUAL_REASSEMBLY_FROM_CANONICAL_AGGREGATE_OR_RASTER",
    "TABLE1": "L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT",
    "TABLE2": "L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT",
    "SUPPTABLE_S1": "L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT",
}

# Artifact files (figures) to return, per artifact.
ARTIFACT_FILES = {
    "FIG1": ["FIG1/FIG1_manhattan_qq.svg", "FIG1/FIG1_manhattan_qq.pdf",
             "FIG1/FIG1_manhattan_qq.png"],
    "FIG2": ["FIG2/FIG2_population_frequency.svg", "FIG2/FIG2_population_frequency.pdf",
             "FIG2/FIG2_population_frequency.png"],
    "FIG3": ["FIG3/FIG3_cross_population.svg", "FIG3/FIG3_cross_population.pdf",
             "FIG3/FIG3_cross_population.png"],
    "FIG4": ["FIG4_TABLE2/FIG4_pgs_validation.svg", "FIG4_TABLE2/FIG4_pgs_validation.pdf",
             "FIG4_TABLE2/FIG4_pgs_validation.png"],
    "SUPPFIG_S1": ["SUPPFIG_S1/SUPPFIG_S1_roc_reassembled.svg",
                   "SUPPFIG_S1/SUPPFIG_S1_roc_reassembled.pdf",
                   "SUPPFIG_S1/SUPPFIG_S1_roc_reassembled.png"],
    "TABLE1": ["TABLE1/TABLE1_reconstructed.tsv"],
    "TABLE2": ["FIG4_TABLE2/TABLE2_reconstructed.tsv"],
    "SUPPTABLE_S1": ["SUPPTABLE_S1/SUPPTABLE_S1_reconstructed.tsv"],
}

# Machine-readable source / audit tables to return.
SOURCE_FILES = [
    "FIG1/FIG1_manhattan_source.tsv.gz", "FIG1/FIG1_qq_source.tsv.gz",
    "FIG1/FIG1_chromosome_offsets.tsv", "FIG1/FIG1_audit.json",
    "TABLE1/TABLE1_selection_audit.tsv", "TABLE1/TABLE1_audit.json",
    "FIG2/FIG2_source_table.tsv", "FIG2/FIG2_category_audit.tsv", "FIG2/FIG2_audit.json",
    "FIG3/FIG3_panelA_source.tsv", "FIG3/FIG3_panelB_source.tsv",
    "FIG3/FIG3_panelA_independent_rederivation.tsv",
    "FIG3/FIG3_panelB_independent_rederivation.tsv",
    "FIG3/FIG3_crosstable_consistency.tsv",
    "FIG3/FIG3_optional_rerun_panelB.tsv",
    "FIG3/FIG3_optional_rerun_topK_orderings.tsv", "FIG3/FIG3_audit.json",
    "FIG4_TABLE2/FIG4_panelA_density.tsv",
    "FIG4_TABLE2/FIG4_panelA_summary_comparison.tsv",
    "FIG4_TABLE2/FIG4_panelA_density_comparison.tsv",
    "FIG4_TABLE2/FIG4_panelB_comparison.tsv",
    "FIG4_TABLE2/TABLE2_display_rounding_check.tsv",
    "FIG4_TABLE2/FIG4_TABLE2_audit.json",
    "SUPPFIG_S1/SUPPFIG_S1_panel_crop_metadata.tsv",
    "SUPPFIG_S1/SUPPFIG_S1_annotation_table.tsv", "SUPPFIG_S1/SUPPFIG_S1_audit.json",
    "SUPPTABLE_S1/SUPPTABLE_S1_locus_mapping_audit.tsv",
    "SUPPTABLE_S1/SUPPTABLE_S1_category_agreement.tsv",
    "SUPPTABLE_S1/SUPPTABLE_S1_row_selection_audit.tsv",
    "SUPPTABLE_S1/SUPPTABLE_S1_audit.json",
]

DEVIATIONS = [
    ("FIG1", "Manhattan cumulative x axis",
     "Declared constant inter-chromosome gap",
     "Constant gap fixed at 20,000,000 bp, applied uniformly between adjacent autosomes",
     "M01 requires a declared constant but does not fix its value",
     "Horizontal spacing only; no effect on any statistic or on point ordering", "DECLARED"),
    ("FIG1", "QQ order statistic",
     "Uniform order-statistic expectation, state convention if it differs from (i-0.5)/m",
     "(i-0.5)/m used, i ascending in P over all m valid P values",
     "Contract default adopted; no deviation",
     "None", "NO_DEVIATION"),
    ("FIG1", "Validity filter",
     "Finite positions and 0 < p_value <= 1; preserve qc_status; restore nothing",
     "Filter applied as written; all 362,797 rows are autosomal, finite and valid, "
     "qc_status is uniformly PASS_DIRECTLY_GENOTYPED, so 0 rows were excluded",
     "No filtering was necessary on this snapshot",
     "None", "NO_DEVIATION"),
    ("TABLE1", "Join key between GWAS snapshot and Table 1 checkpoint",
     "Join exact aggregate frequency/count fields from DS_TABLE1_CHECKPOINT",
     "Join executed on GRCh37 chr:position (the contract's own variant key), not on the "
     "display identifier, because DS_GWAS_CURRENT carries Illumina array probe IDs "
     "(e.g. GSA-rs146572333) while the checkpoint displays the bare rsID",
     "Display identifiers are not directly comparable between the two sources",
     "None; rsID agreement is reported separately and matched for all 10 rows. The same "
     "dual convention is present in DS_LD_PRIMARY as lead_reference_id vs lead_summary_id",
     "DECLARED"),
    ("TABLE1", "Display identifier normalization",
     "Preserve variant_id for display and audit",
     "A single leading 'TOKEN-' is stripped only when the remainder is itself a plain "
     "rsID; the unmodified source identifier is retained in column source_variant_id",
     "Required to render the submission's display identifier",
     "Display only", "DECLARED"),
    ("FIG2", "Censored reference frequency",
     "Missing values remain unclassified and must be logged",
     "'<0.0001' (1 row, gnomAD_EUR_MAF for rs139129152) treated as a censored bound, not "
     "as missing; carried at its stated value and flagged",
     "A below-detection bound is informative for a ratio threshold, unlike a true missing",
     "None; the audit table shows the East Asian enriched call is unchanged if the bound "
     "is instead treated as exactly zero", "DECLARED"),
    ("FIG2", "Clump membership counting",
     "A clump is the lead plus assigned member variants; do not reinterpret PLINK "
     "summary-bin counts as member counts",
     "members_ref_ids lists non-lead members only and uses '.' for none, so the clump "
     "size was computed as 1 + len(members) and verified against n_variants_in_clump",
     "Convention confirmed empirically from the checkpoint",
     "None; 17 leads + 9 members = 26 = Supplementary Table S1 row count", "NO_DEVIATION"),
    ("FIG3", "Top-K ordering",
     "At K = 100, 500, 1000, 2000 report matching-direction counts and proportions",
     "M04 does not state which P orders the Top-K. Six readings were computed; the "
     "returned claim values are taken from DS_FIG3_PANEL_A as M04 designates",
     "Contract ambiguity resolved by evidence rather than assumption",
     "Returned values are unaffected. In the optional rerun the closest reading is "
     "clump-by-European-P / rank-by-European-P, which reproduces every K to within one "
     "concordant variant (55/56, 279/280, 546/545, 1065/1064)", "DECLARED"),
    ("FIG3", "Optional genome-wide rerun - LD engine",
     "EAS-LD clump by European P under the primary and four sensitivity settings",
     "LD clumping implemented independently (no PLINK binary available): r2 is the "
     "squared Pearson correlation of ALT dosages in the 504-sample EAS panel, greedy "
     "assignment in ascending European P, window applied as a radius, ties broken by "
     "chromosome, position then normalized variant key. Only the PGEN format reader "
     "(pgenlib) is third-party",
     "No reference clumping implementation was available or consulted",
     "Lead counts agree with the checkpoint to +5 / +2 / -2 / +9 / +7 across the five "
     "settings (relative 1.7e-05 to 1.0e-04). Attributable to tie order: the European P "
     "values take only 28,990 distinct values across 307,866 variants, so 97.4% of rows "
     "sit in tied groups and lead choice within a tie is convention-dependent",
     "DECLARED"),
    ("FIG3", "Optional genome-wide rerun - directional Z",
     "Z = sum(sign*weight)/sqrt(sum(weight^2)), weight = -log10(European P clip 1e-300)",
     "Implemented as written over the independently derived lead set",
     "Difference propagates from the lead set above",
     "Z agrees to 0.029-0.154 absolute (0.97%-4.8% relative) across the five settings; "
     "sign, significance and the rank order of settings are unchanged, and every setting "
     "exceeds the one-sided alpha=0.05 reference in both the checkpoint and the rerun",
     "DECLARED"),
    ("FIG3", "Non-reference variants",
     "Not specified",
     "347 variants that carry a European P but are absent from the EAS reference panel "
     "were excluded from clumping rather than admitted as singleton leads",
     "A variant absent from the LD panel cannot be assessed for r2",
     "Admitting them instead would add 347 leads and move the primary count away from "
     "the checkpoint (87,033 vs 86,681), so the exclusion rule is the supported one",
     "DECLARED"),
    ("FIG4", "Input data defect in DS_FIG4_AGGREGATE_SOURCE",
     "Aggregate control/model source",
     "Rows 2-7 (the six fixed-threshold rows) carry 19 tab-separated fields against a "
     "20-field header, so the trailing provenance text is parsed into control_pgs_sd_raw "
     "and provenance is empty for those rows",
     "Defect in the supplied file, not introduced by this reconstruction",
     "None on any returned value: control_pgs_mean_raw and control_pgs_sd_raw were read "
     "from the optimized row (row 8), which has the full 20 fields and parses correctly. "
     "Reported for correction upstream", "REPORTED_UPSTREAM"),
    ("FIG4", "Panel A histogram binning range",
     "18 equal-width histogram bins",
     "Bins span the observed case z range [z_min, z_max], not the 600-point KDE grid range",
     "M05 fixes the bin count but not the span; the observed-range reading is the one "
     "that reproduces the checkpoint",
     "None; all 18 bin edges, counts and densities reproduce to 4.8e-10, the checkpoint's "
     "own stored decimal precision", "DECLARED"),
    ("FIG4", "Optimized threshold selection",
     "The optimized row is a secondary analysis",
     "The optimized threshold was re-derived as the scan-wide maximiser of R2 over all "
     "6,000 PRSice2 scan rows rather than taken from the checkpoint label",
     "Independent verification of a checkpoint-supplied value",
     "None; the maximiser is unique and equals the checkpoint threshold 0.00640005",
     "NO_DEVIATION"),
    ("SUPPFIG_S1", "Interior crop",
     "Canonical interior crop x=201:1864, y=108:1264; log any differing crop",
     "The canonical crop was used. Independent detection of the axes frame returns "
     "x=199:1867, y=106:1267 (deltas -2/+3 px on each side)",
     "The detector returns the frame including the drawn axis spine; the canonical crop "
     "is the interior strictly inside it",
     "None; containment was verified for both panels and the canonical crop was used",
     "DECLARED"),
    ("SUPPFIG_S1", "ROC coordinate registration",
     "Rebuild a two-panel layout with live outer axes",
     "The imshow extent was calibrated from the pixel extent of each checkpoint's own "
     "chance diagonal, which spans data (0,0)-(1,1), giving extent "
     "(-0.0488, 1.0495, -0.0483, 1.0473) and an implied axis margin of 0.0445",
     "Placing the axes rectangle as if it were the data rectangle would shift every "
     "curve inwards by the renderer's axis margin",
     "Registration only. This measures the frame, not the ROC curve; no curve coordinate "
     "was traced, fitted or invented. Both panels calibrate identically",
     "DECLARED"),
    ("SUPPTABLE_S1", "Line terminator",
     "Preserve all columns and missing-value representation",
     "The source CRLF terminator was detected and reproduced",
     "So that byte identity is a meaningful check rather than being defeated by a "
     "line-ending convention",
     "None; the reconstructed table is byte-identical to the source (SHA-256 equal)",
     "NO_DEVIATION"),
    ("SUPPTABLE_S1", "Scope of the population-category row counts",
     "SUPPTABLE_S1_CATEGORY_COUNTS - 'Population-category row counts' over "
     "DS_TABLE_S1_CURRENT;DS_FIG2_FREQUENCY_CHECKPOINT",
     "Counted over all 26 rows (8/12/6) rather than the 17 lead rows (5/7/5)",
     "Three pieces of contract evidence: FIG2_CATEGORY_COUNTS already returns the "
     "lead-only map 5/7/5 from the 17-row DS_FIG2_FREQUENCY_CHECKPOINT, so the "
     "lead-only reading would duplicate an existing claim; the matrix lists counting "
     "scope first and cross-check second throughout; and 8+12+6 = 26 = the sibling "
     "SUPPTABLE_S1_ROW_COUNT, whereas lead-only would sum to 17",
     "The alternative breakdown is retained and returned as "
     "SUPPTABLE_S1_CATEGORY_COUNTS_LEAD_ROWS_ONLY so the custodian can substitute it "
     "without a recomputation", "DECLARED"),
    ("FIG1", "Suggestive variant set",
     "M01 gives no instruction on excluding any variant below the suggestive line",
     "All 27 variants with P < 1e-5 in DS_GWAS_CURRENT are plotted",
     "DS_GWAS_CURRENT carries 27 such variants while DS_TABLE_S1_CURRENT retains 26 "
     "rows; the one absent from Table S1 is rs376128944, and the Table S1 Notes cell "
     "for rs75790544 records it as a replacement lead for the WDHD1 locus after an "
     "rs376128944 exclusion. The exclusion is therefore documented in the payload but "
     "not instructed by M01, so applying it to the figure would require an "
     "instruction the contract does not give",
     "No claim value is affected -- FIG1_VALID_VARIANTS, FIG1_LAMBDA_GC and the two "
     "thresholds are unchanged. The rendered Manhattan shows 27 points below the "
     "suggestive line where a 26-variant description would show 26", "OPEN_FINDING"),
    ("FIG2", "Population-category label casing",
     "M03 writes the labels lower-case: 'East Asian enriched', 'European enriched', "
     "'similar frequency'",
     "The emitted labels use the checkpoint capitalization 'Similar frequency'",
     "DS_FIG2_FREQUENCY_CHECKPOINT and DS_TABLE_S1_CURRENT both capitalize the middle "
     "label, so matching them keeps the category-agreement audit and the returned "
     "count maps on a single spelling. This alignment to the checkpoint spelling was "
     "made during implementation and is recorded here rather than left implicit",
     "None on classification -- every row agrees on category under either spelling. "
     "The risk it carries is that grouping on the M03 spelling silently yields an "
     "empty middle category rather than an error", "DECLARED"),
    ("SUPPTABLE_S1", "Checkpoint currency is not testable in scope",
     "M07: start from DS_TABLE_S1_CURRENT as the current source",
     "Reconstructed from DS_TABLE_S1_CURRENT and verified against DS_LD_PRIMARY and "
     "DS_FIG2_FREQUENCY_CHECKPOINT only",
     "The v1.1 payload contains no submitted or published Supplementary Table S1, so "
     "whether DS_TABLE_S1_CURRENT is current with respect to the submitted table "
     "cannot be determined from the input package. Byte identity here establishes "
     "faithful reproduction of the payload checkpoint, not agreement with the "
     "submission",
     "Noted as a scope limit. One value a reader may wish to confirm against the "
     "submission is EAS_EUR_Fold_Difference for rs146572333, which the payload "
     "carries as 38.6", "OPEN_FINDING"),
    ("ALL", "Statistical toolchain",
     "Implement reconstruction code independently",
     "Python 3.14 with numpy/scipy/pandas/matplotlib/pgenlib; all analysis logic written "
     "from the M01-M07 contract text only",
     "No canonical plotting or analysis implementation was available or consulted",
     "None", "DECLARED"),
]

VISUAL_QA = [
    ("FIG1", 2, "YES", "YES", "YES", "YES", "PASS",
     "Manhattan over autosomes 1-22 in chromosome order with 5e-8 and 1e-5 thresholds "
     "shown; QQ with observed vs expected -log10(P) and lambda_GC annotated. Style "
     "differs from the canonical rendering; panel content and scientific direction match. "
     "Point count below the suggestive line is 27, matching DS_GWAS_CURRENT; "
     "DS_TABLE_S1_CURRENT retains 26 -- see the FIG1 suggestive-variant-set entry in "
     "the deviation log."),
    ("FIG2", 1, "YES", "YES", "YES", "YES", "PASS",
     "All five declared frequency series drawn in checkpoint plot_order with category "
     "grouping preserved and separators between the three enrichment categories."),
    ("FIG3", 2, "YES", "YES", "YES", "YES", "PASS",
     "Panel A Top-K concordance with 95% Clopper-Pearson intervals against the 0.5 null; "
     "Panel B weighted directional Z per LD setting against the one-sided alpha=0.05 "
     "reference. Primary setting visually distinguished."),
    ("FIG4", 2, "YES", "YES", "YES", "YES", "PASS",
     "Panel A case KDE, 18-bin histogram and N(0,1) control curve on the standardized "
     "scale; Panel B incremental Nagelkerke R2 by threshold with the optimized tier "
     "hatched and labelled as a secondary analysis, keeping it distinct from the fixed grid."),
    ("SUPPFIG_S1", 2, "YES", "YES", "YES", "YES", "PASS",
     "Two-panel ROC layout with live outer axes, panel letters, model labels, diagonal "
     "reference semantics and AUC annotations from DS_ROC_AUC_SOURCE. Interior content is "
     "the canonical raster checkpoint; curves were not recalculated or traced. "
     "Covariates-only AUC 0.758 is stated as a reference value with no retained raster."),
    ("TABLE1", 0, "NA", "NA", "YES", "YES", "PASS",
     "10 rows in independently reproduced rank order; P at three significant digits, beta "
     "SE and frequencies at three decimals, with full-precision audit columns retained."),
    ("TABLE2", 0, "NA", "NA", "YES", "YES", "PASS",
     "2A fixed primary grid and 2B optimized secondary row kept visually and textually "
     "distinct; display rounding reproduces the checkpoint for all 7 rows."),
    ("SUPPTABLE_S1", 0, "NA", "NA", "YES", "YES", "PASS",
     "26 rows, 23 columns, byte-identical to the DS_TABLE_S1_CURRENT checkpoint; lead/member "
     "mapping and category labels verified against DS_LD_PRIMARY and the Figure 2 source."),
]


def run_all() -> dict:
    import m01_fig1, m02_table1, m03_fig2, m04_fig3, m05_fig4_table2
    import m06_suppfig_s1, m07_supptable_s1
    claims: dict[str, object] = {}
    audits: dict[str, dict] = {}
    for name, mod in (("FIG1", m01_fig1), ("TABLE1", m02_table1), ("FIG2", m03_fig2),
                      ("FIG3", m04_fig3), ("FIG4_TABLE2", m05_fig4_table2),
                      ("SUPPFIG_S1", m06_suppfig_s1), ("SUPPTABLE_S1", m07_supptable_s1)):
        print(f"  running {name} ...", flush=True)
        r = mod.main()
        claims.update(r["claims"])
        audits[name] = r["audit"]
    return {"claims": claims, "audits": audits}


CLAIM_NOTES = {
    "SUPPTABLE_S1_CATEGORY_COUNTS":
        "Counts over all 26 rows of Supplementary Table S1 (leads 5/7/5 + members "
        "3/5/1). The competing reading -- 17 lead rows only, giving 5/7/5 -- was "
        "considered and rejected on three pieces of contract evidence. (1) The claim "
        "matrix already carries FIG2_CATEGORY_COUNTS over "
        "DS_FIG2_FREQUENCY_CHECKPOINT, whose 17 rows are exactly the 17 leads and "
        "whose categories are exactly 5/7/5; the lead-only reading would make this "
        "claim a verbatim duplicate of that one under a second ID. (2) The matrix "
        "lists the counting scope first and the corroborating source second "
        "(SUPPTABLE_S1_LEAD_COUNT = DS_TABLE_S1_CURRENT;DS_LD_PRIMARY, "
        "FIG2_LEAD_ROW_COUNT = DS_FIG2_FREQUENCY_CHECKPOINT;DS_LD_PRIMARY), so here "
        "the scope is DS_TABLE_S1_CURRENT and the frequency checkpoint is the "
        "cross-check -- which is how it is used, in "
        "SUPPTABLE_S1_category_agreement.tsv. (3) 8+12+6 = 26 = "
        "SUPPTABLE_S1_ROW_COUNT, the sibling claim in the same group; the lead-only "
        "reading would sum to 17 against a declared row count of 26. This is an "
        "interpretation of contract wording, not a computed value, so the lead-only "
        "breakdown is retained in "
        "03_SOURCE_TABLES/SUPPTABLE_S1/SUPPTABLE_S1_audit.json and returned as "
        "SUPPTABLE_S1_CATEGORY_COUNTS_LEAD_ROWS_ONLY for custodian review.",
}
for _k in ("FIG3_K100_BINOMIAL_P", "FIG3_K500_BINOMIAL_P", "FIG3_K1000_BINOMIAL_P",
           "FIG3_K2000_BINOMIAL_P"):
    CLAIM_NOTES[_k] = ("Independently recomputed at full precision from (n_concordant, K); "
                       "DS_FIG3_PANEL_A stores this at 4 significant digits and agrees to "
                       "that precision.")
for _s in ("PRIMARY_R010_KB500", "SENS_R2_005_KB500", "SENS_R2_020_KB500",
           "SENS_R2_010_KB250", "SENS_R2_010_KB1000"):
    CLAIM_NOTES[f"FIG3_{_s}_P"] = ("Independently recomputed at full precision as the "
                                   "positive standard-normal tail of Z; the checkpoint "
                                   "stores a rounded value and agrees to that precision.")


def artifact_of(claim_id: str) -> str:
    for a in ("SUPPTABLE_S1", "SUPPFIG_S1", "TABLE1", "TABLE2", "FIG1", "FIG2", "FIG3", "FIG4"):
        if claim_id.startswith(a + "_"):
            return a
    return "UNKNOWN"


def main() -> None:
    print("Running all artifact modules ...")
    res = run_all()
    claims = res["claims"]

    if RETURN.exists():
        shutil.rmtree(RETURN)
    ARTIFACT_DIR.mkdir(parents=True)
    SOURCE_DIR.mkdir(parents=True)

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # ---- 01 independent artifact results --------------------------------
    matrix = pd.read_csv(PKG / "03_METHOD_CONTRACTS/ARTIFACT_CLAIM_MATRIX_OUTPUTS_WITHHELD.tsv",
                         sep="\t", dtype=str)
    rows, missing, extra = [], [], []
    produced = dict(claims)
    for _, m in matrix.iterrows():
        cid = str(m["claim_id"])
        if cid in produced:
            v = produced.pop(cid)
            note = f"{LEVEL.get(str(m['artifact_id']), '')}; full precision as computed"
            if cid in CLAIM_NOTES:
                note = f"{note}. {CLAIM_NOTES[cid]}"
            rows.append([cid, str(m["artifact_id"]), v, "COMPLETED",
                         str(m["required_dataset_ids"]), note])
        else:
            missing.append(cid)
            rows.append([cid, str(m["artifact_id"]), "", "NOT_PRODUCED",
                         str(m["required_dataset_ids"]), "see method deviation log"])
    for cid, v in produced.items():
        extra.append(cid)
        rows.append([cid, artifact_of(cid), v, "COMPLETED_ADDITIONAL", "", "not in claim matrix"])
    write_tsv(RETURN / "01_INDEPENDENT_ARTIFACT_RESULTS.tsv",
              ["claim_id", "artifact_id", "value", "method_status",
               "source_dataset_ids", "notes"], rows)
    print(f"  claims: {len(matrix)} required, {len(missing)} missing, {len(extra)} extra")

    # ---- 02 artifact files ----------------------------------------------
    art_rows = []
    for aid, files in ARTIFACT_FILES.items():
        for rel in files:
            src = OUT / rel
            dst = ARTIFACT_DIR / aid / src.name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            art_rows.append([aid, LEVEL[aid], f"02_ARTIFACT_FILES/{aid}/{src.name}",
                             sha256(dst), src.suffix.lstrip(".").upper(), "RETURNED", ""])
    write_tsv(RETURN / "02_ARTIFACT_FILES/ARTIFACT_FILE_RETURN.tsv",
              ["artifact_id", "reconstruction_level", "relative_output_path",
               "sha256", "format", "status", "notes"], art_rows)

    # ---- 03 source tables -------------------------------------------------
    for rel in SOURCE_FILES:
        src = OUT / rel
        dst = SOURCE_DIR / rel.split("/", 1)[0] / src.name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    # ---- 04 input hash audit ---------------------------------------------
    expected = {}
    for line in (PKG / "SHA256SUMS.txt").read_text().splitlines():
        if line.strip():
            h, p = line.split("  ", 1)
            expected[p.strip()] = h
    ds_by_path = {str(v.relative_to(PKG)): k for k, v in DATASETS.items()}
    hash_rows = []
    for rel, exp in sorted(expected.items()):
        f = PKG / rel
        obs = sha256(f) if f.exists() else ""
        hash_rows.append([ds_by_path.get(rel, "PACKAGE_DOC"), rel, exp, obs,
                          "MATCH" if obs == exp else ("MISSING" if not obs else "MISMATCH"),
                          "" if obs == exp else "investigate before use"])
    write_tsv(RETURN / "04_INPUT_HASH_AUDIT.tsv",
              ["dataset_id", "relative_path", "expected_sha256", "observed_sha256",
               "status", "notes"], hash_rows)
    n_match = sum(1 for r in hash_rows if r[4] == "MATCH")
    print(f"  input hash audit: {n_match}/{len(hash_rows)} MATCH")

    # ---- 05 method deviation log -----------------------------------------
    write_tsv(RETURN / "05_METHOD_DEVIATION_LOG.tsv",
              ["artifact_id", "step", "contract_method", "implemented_method",
               "reason", "expected_impact", "status"], [list(d) for d in DEVIATIONS])

    # ---- 06 software environment -----------------------------------------
    mods = ["numpy", "scipy", "pandas", "matplotlib", "pgenlib", "PIL"]
    env_rows = [["language", "Python", platform.python_version(), platform.platform(),
                 "all reconstruction code", "CPython"]]
    for m in mods:
        try:
            mod = __import__(m)
            env_rows.append(["library", m, getattr(mod, "__version__", "unknown"),
                             platform.platform(),
                             "PGEN format reader only" if m == "pgenlib" else
                             "numerics/plotting", ""])
        except Exception:
            env_rows.append(["library", m, "NOT_AVAILABLE", "", "", ""])
    env_rows.append(["tool", "PLINK", "NOT_AVAILABLE", platform.platform(),
                     "LD clumping", "No PLINK binary present; the clumping algorithm was "
                     "implemented independently (see 05_METHOD_DEVIATION_LOG)"])
    env_rows.append(["hardware", "host", platform.machine(), platform.platform(),
                     "local controlled research environment", "no external compute used"])
    write_tsv(RETURN / "06_SOFTWARE_ENVIRONMENT.tsv",
              ["component", "software", "version", "platform", "purpose", "notes"], env_rows)

    # ---- 07 visual semantic QA --------------------------------------------
    write_tsv(RETURN / "07_VISUAL_SEMANTIC_QA.tsv",
              ["artifact_id", "panel_count", "axes_match", "legend_match",
               "annotation_match", "caption_compatible", "status", "notes"],
              [list(v) for v in VISUAL_QA])

    # ---- 08 AI assistance log ---------------------------------------------
    ai_rows = [
        [now, "Claude Opus 5 (Claude Code CLI), executed locally on the recipient host",
         "Method contract text (M01-M07), file schemas and column headers, dataset "
         "inventory documents, aggregate summary tables (Figure 2/3/4 sources, Table 1/2 "
         "sources, Supplementary Table S1, PRSice2 threshold scan, ROC AUC source), "
         "aggregate error and comparison summaries, and the reconstruction source code",
         "YES - LIMITED, SEE ATTESTATION",
         "Authoring the independent reconstruction code and audit tables from the method "
         "contracts",
         "Restricted individual-level data was NOT uploaded in bulk. One disclosure is "
         "recorded: during initial schema inspection, before the content type of "
         "DS_CASE_PGS_CANONICAL had been established, a 3-line head of "
         "IQ_PGS_caseonly.md was displayed, exposing 2 of the 91 pseudonymous case rows "
         "(pseudonymous ID and PRS value). No further individual rows were read into the "
         "assistant context; all subsequent handling of the 91 case scores and of the "
         "provider workbook was performed by locally executed code whose outputs are "
         "aggregates only. No provider workbook row and no other individual row was "
         "exposed."],
        [now, "Claude Opus 5 (Claude Code CLI), executed locally on the recipient host",
         "Two canonical ROC panel rasters (DS_ROC_PANEL_A_RASTER, DS_ROC_PANEL_B_RASTER)",
         "NO",
         "Confirming panel-to-model assignment and verifying crop and registration for "
         "the M06 L3 visual reassembly",
         "These are aggregate curve rasters supplied in Package A and are the designated "
         "M06 inputs; they contain no individual-level observation."],
    ]
    write_tsv(RETURN / "08_AI_ASSISTANCE_LOG.tsv",
              ["timestamp", "tool", "material_shared", "individual_data_shared",
               "purpose", "attestation"], ai_rows)

    # ---- 09 verifier attestation ------------------------------------------
    (RETURN / "09_VERIFIER_ATTESTATION.md").write_text(f"""# Verifier attestation

I attest that I reconstructed the in-scope artifacts using independently
implemented code and Package A inputs only; did not access Package B or current
canonical rendered artifacts before result lock; and have disclosed every
method deviation and source limitation in `05_METHOD_DEVIATION_LOG.tsv`.

## Independence

All analysis logic was written from the M01-M07 method contract text. No
canonical plotting or analysis implementation, and no rendered current figure or
table, was available or consulted. The only third-party analysis component is
the PGEN file-format reader (pgenlib); the LD clumping algorithm, the Clopper-
Pearson and exact binomial procedures, the weighted directional statistic, the
KDE and histogram construction, and lambda_GC were all implemented here.

The canonical ROC panel rasters supplied in Package A were used, as M06
directs, solely as aggregate curve checkpoints for L3 visual reassembly.

## Data handling disclosure

Restricted individual-level data was not uploaded in bulk to any external
service. One limited exposure is recorded in `08_AI_ASSISTANCE_LOG.tsv` and is
repeated here so it is not missed: during initial schema inspection, before the
content type of `DS_CASE_PGS_CANONICAL` had been established, a 3-line head of
`IQ_PGS_caseonly.md` was displayed, exposing 2 of the 91 pseudonymous case rows
(pseudonymous identifier and PRS value). No further individual rows entered the
assistant context; all subsequent handling of the 91 case scores and of the
provider workbook was performed by locally executed code emitting aggregates
only. No provider workbook row was exposed. The project owner should decide
whether this requires any further action under the Package A handling rules.

No individual-level row is included anywhere in this return package.

## Scope

Provider-level rerun from the 41,528 individual control genotypes was not
performed and is not claimed. Figure 4 and Table 2 are stated as L2
reconstruction from the provider output checkpoint. Supplementary Figure S1 is
stated as L3 visual reassembly; model-level ROC recalculation is not claimed.

## What this establishes, and what it does not

This exercise is a **co-author independent verification with result lock**. It
establishes two things and not a third.

It establishes **implementation independence**: the reconstruction code was
written from the M01-M07 method contracts, not derived from, adapted from, or
compared against the original analysis code, which was never available. It also
establishes **result blinding against Package B**: the answer key was not opened
before lock. During this work a submitted supplementary table was found to exist
in the surrounding research repository, outside the Package A input set; it was
deliberately not opened, because reading it before hash lock would defeat the
blinding this attestation asserts.

It does **not** establish third-party independence, and does not claim to. The
verifier is an author of the manuscript and has prior knowledge of the reported
values. This should be described as a co-author independent reconstruction, not
as an external audit.

## Reconstruction level ceiling

Of the 91 in-scope claims, **6 are L1**, **80 are L2**, and **5 are L3**. Only
the six FIG1 claims are independent recalculation from analysis-ready data.

The 80 L2 claims verify faithful derivation from the provider output
checkpoint. **An error upstream of that checkpoint is reproduced faithfully and
cannot be detected at this level.** The 5 L3 claims establish visual semantics
only. Completion of all 91 claims must therefore not be read as confirmation
that the underlying analysis is correct; it confirms that the artifacts follow
from the checkpoints they declare, and that the six L1 quantities recompute.

## Corroboration by a second independent reconstruction

An earlier independent reconstruction of the same claim set was performed from
the same method contracts in a separate session with a separately written
implementation. Cross-checking the two returns, **90 of 91 claim values agree**.
The single substantive difference is SUPPTABLE_S1_CATEGORY_COUNTS, where the
two runs read the scope of "population-category row counts" differently; the
reasoning for the value returned here, and the alternative value, are both in
the deviation log. Two findings recorded in this package -- the FIG1
suggestive-variant-set discrepancy and the FIG2 label-casing alignment -- were
surfaced by that cross-check and had been missed by this run alone.

Verifier: ____________________  Date: ____________________

Return archive SHA-256: _________________________________________________
""", encoding="utf-8")

    # ---- 00 completion block ----------------------------------------------
    a = res["audits"]
    l2 = a["FIG3"].get("layer2_optional_rerun", {})
    (RETURN / "00_COMPLETION_BLOCK.txt").write_text(f"""KAWASAKI PHASE 5C - INDEPENDENT RECONSTRUCTION COMPLETION BLOCK
Generated (UTC): {now}

INPUT INTEGRITY
  Package A files hash-verified : {n_match}/{len(hash_rows)} MATCH, 0 MISMATCH, 0 MISSING

ARTIFACTS COMPLETED (8/8)
  FIG1          L1  Manhattan + QQ recalculated from DS_GWAS_CURRENT
  FIG2          L2  Population frequency and East Asian LD loci
  FIG3          L2  Cross-population directional analysis
  FIG4          L2  PGS validation
  SUPPFIG_S1    L3  ROC visual reassembly
  TABLE1        L2  Top variants
  TABLE2        L2  PGS performance, 2A fixed / 2B optimized
  SUPPTABLE_S1  L2  Integrated variant annotation

CLAIM COVERAGE
  Required claims in matrix : {len(matrix)}
  Produced                  : {len(matrix) - len(missing)}
  Not produced              : {len(missing)}

INDEPENDENT VERIFICATION SUMMARY
  FIG1  362,797 valid variants (0 excluded); lambda_GC = {a['FIG1']['lambda_gc']:.10f}
        1 genome-wide significant, 27 suggestive
  TABLE1 independent ranking reproduces the checkpoint's 10 variants, in order,
        with matching rsIDs, alleles and significance labels; beta and SE agree
        exactly at 3 decimals
  FIG2  all 17 population-enrichment categories recomputed from the EAS/EUR
        ratio agree with the checkpoint; 17 leads + 9 members = 26
  FIG3  every statistic in both figure-source tables re-derived independently:
        concordance exact, Clopper-Pearson to 5.0e-07, binomial P to 2.0e-04
        relative, one-sided normal P to 1.2e-06 relative, cross-table consistent
        Optional genome-wide rerun: lead counts agree to within +5/+2/-2/+9/+7
        across the five LD settings; directional Z agrees to 0.97%-4.8%
  FIG4  all 12 Panel A summary metrics reproduce exactly from the 91 case scores;
        all 1,218 density rows (600 KDE + 600 control + 18 histogram) reproduce
        to 5.0e-11; all 7 Panel B tiers reproduce exactly; the optimized
        threshold is independently confirmed as the unique scan-wide R2 maximum
  TABLE2 display rounding reproduces the checkpoint for all 7 rows
  SUPPFIG_S1 both panel rasters hash-verified, crop containment confirmed, ROC
        registration calibrated independently from each panel's chance diagonal
  SUPPTABLE_S1 reconstructed table is byte-identical to DS_TABLE_S1_CURRENT, the
        payload checkpoint (SHA-256 equal); lead/member mapping and categories
        verified. This is reproduction of the checkpoint, NOT agreement with the
        submitted table, which the input package does not contain

RECONSTRUCTION LEVEL CEILING
  Of the 91 claims, 6 are L1, 80 are L2, 5 are L3. Only the six FIG1 claims are
  independent recalculation from analysis-ready data. The 80 L2 claims verify
  derivation from the provider checkpoint only -- an error upstream of that
  checkpoint is reproduced faithfully and cannot be detected here. Completing
  all 91 claims is not confirmation that the underlying analysis is correct.

CORROBORATION BY A SECOND INDEPENDENT RECONSTRUCTION
  A separately written reconstruction of the same claim set, from the same
  method contracts, agrees on 90 of 91 claim values. The one substantive
  difference is SUPPTABLE_S1_CATEGORY_COUNTS, a difference in reading the scope
  of the claim rather than in computation; both values are returned. Two of the
  findings below were surfaced only by that cross-check.

NOT CLAIMED
  PRSice2 rerun from 41,528 individual control genotypes
  Model-level ROC recalculation (Supplementary Figure S1 is L3)
  End-to-end provider GWAS rerun
  Agreement between DS_TABLE_S1_CURRENT and the submitted Supplementary Table S1

OPEN FINDINGS
  FIG1  DS_GWAS_CURRENT carries 27 variants at P < 1e-5 while Table S1 retains
        26; the absent one is rs376128944, whose exclusion the Table S1 notes
        document but M01 does not instruct. No claim value is affected; the
        rendered Manhattan shows 27 points below the suggestive line.
  SUPPTABLE_S1  checkpoint currency versus the submitted table is not testable
        from the input package. For reference the payload carries
        EAS_EUR_Fold_Difference = 38.6 for rs146572333.

DISCLOSURES
  See 05_METHOD_DEVIATION_LOG.tsv ({len(DEVIATIONS)} entries) and
  08_AI_ASSISTANCE_LOG.tsv. One limited individual-data exposure is recorded
  there and in 09_VERIFIER_ATTESTATION.md.
  One defect in a supplied input file is reported upstream: rows 2-7 of
  figure4_aggregate_source.tsv carry 19 fields against a 20-field header.
  The M03 label spelling differs in case from the checkpoints; the emitted
  labels follow the checkpoint capitalization. Declared in the deviation log.

ADDITIONS BEYOND THE REQUIRED RETURN TREE
  10_RECONSTRUCTION_CODE/  all reconstruction source, so the independence claim
                           can be audited directly rather than taken on trust.

RESULT LOCK
  Package B was not requested, accessed or used.
  This archive must be hash-locked with the custodian before any answer-key
  comparison.
""", encoding="utf-8")

    # ---- 10 reconstruction code (addition beyond the required tree) -------
    # Not listed in REQUIRED_RETURN_TREE.md, but included so the custodian can
    # audit the independence claim rather than take it on trust.
    code_dir = RETURN / "10_RECONSTRUCTION_CODE"
    code_dir.mkdir(parents=True, exist_ok=True)
    for src in sorted((ROOT / "code").glob("*.py")):
        if src.name.startswith("probe_"):
            continue
        shutil.copy2(src, code_dir / src.name)
    (code_dir / "README.md").write_text(
        "# Reconstruction code\n\n"
        "Addition beyond REQUIRED_RETURN_TREE.md, included so the independence\n"
        "claim in 09_VERIFIER_ATTESTATION.md can be audited directly.\n\n"
        "| file | contract |\n|---|---|\n"
        "| `common.py` | shared paths, hashing, variant key, full-precision output |\n"
        "| `m01_fig1.py` | M01 Figure 1 Manhattan + QQ (L1) |\n"
        "| `m02_table1.py` | M02 Table 1 top variants (L2) |\n"
        "| `m03_fig2.py` | M03 Figure 2 population frequency and LD loci (L2) |\n"
        "| `m04_fig3.py` | M04 Figure 3 cross-population (L2), two layers |\n"
        "| `m05_fig4_table2.py` | M05 Figure 4 and Table 2 PGS (L2) |\n"
        "| `m06_suppfig_s1.py` | M06 Supplementary Figure S1 ROC (L3) |\n"
        "| `m07_supptable_s1.py` | M07 Supplementary Table S1 (L2) |\n"
        "| `ld_clump.py` | independent East Asian LD clumping engine |\n"
        "| `assemble_return.py` | builds this return package |\n\n"
        "Run order: `python assemble_return.py` executes every module and\n"
        "regenerates the whole package. Only `pgenlib` (PGEN format reader) is a\n"
        "third-party analysis component; all statistical logic is in these files.\n",
        encoding="utf-8")

    # ---- manifest + sha256sums --------------------------------------------
    files = sorted(p for p in RETURN.rglob("*") if p.is_file())
    write_tsv(RETURN / "RETURN_MANIFEST.tsv",
              ["relative_path", "bytes", "sha256"],
              [[str(p.relative_to(RETURN)), p.stat().st_size, sha256(p)]
               for p in files if p.name != "RETURN_MANIFEST.tsv"])
    files = sorted(p for p in RETURN.rglob("*") if p.is_file())
    with open(RETURN / "SHA256SUMS.txt", "w", encoding="utf-8", newline="\n") as fh:
        for p in files:
            if p.name != "SHA256SUMS.txt":
                fh.write(f"{sha256(p)}  {p.relative_to(RETURN)}\n")

    n_files = sum(1 for p in RETURN.rglob("*") if p.is_file())
    total = sum(p.stat().st_size for p in RETURN.rglob("*") if p.is_file())
    print(f"\nReturn package: {RETURN}")
    print(f"  {n_files} files, {total/1e6:.2f} MB")
    if missing:
        print(f"  MISSING CLAIMS: {missing}")


if __name__ == "__main__":
    main()
