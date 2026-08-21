"""Shared helpers for the Phase 5C independent reconstruction.

Written from the M01-M07 method contracts only. No canonical plotting or
analysis implementation from the project was consulted.

Data-handling rule (01_SCOPE_AND_AUTHORIZATION/DATA_SECURITY_AND_AI_RULES.md):
individual-level rows (case PGS, workbook rows) are read by this code on the
local machine only. Nothing here prints or exports individual rows; only
aggregates leave these modules.
"""
from __future__ import annotations

import hashlib
import pathlib

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = ROOT / "KAWASAKI_CODE_INDEPENDENT_FIGURE_TABLE_RECONSTRUCTION_INPUTS_v1.1"
PAYLOAD = PKG / "04_DATA_PAYLOAD"
OUT = ROOT / "out"

# Dataset ID -> package-relative path (from ARTIFACT_TO_DATASET_MAP.tsv).
DATASETS = {
    "DS_GWAS_CURRENT": PAYLOAD / "01_JAPANESE_GWAS_SUMMARY/japanese_gwas_summary_privacy_filtered.tsv.gz",
    "DS_TABLE1_CHECKPOINT": PAYLOAD / "02_TABLE1_MAC_AF_SOURCES/table1_source.tsv",
    "DS_FIG2_FREQUENCY_CHECKPOINT": PAYLOAD / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/figure2_source.tsv",
    "DS_LD_PRIMARY": PAYLOAD / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/ld_locus_definition_primary.tsv",
    "DS_LD_SENSITIVITY": PAYLOAD / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/ld_locus_sensitivity.tsv",
    "DS_EAS_LD_PGEN": PAYLOAD / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/EAS_REFERENCE/eas_phase3_grch37_v2.pgen",
    "DS_EAS_LD_PVAR": PAYLOAD / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/EAS_REFERENCE/eas_phase3_grch37_v2.pvar",
    "DS_EAS_LD_PSAM": PAYLOAD / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/EAS_REFERENCE/eas_phase3_grch37_v2.psam",
    "DS_CROSSPOP_MERGED_GENOMEWIDE": PAYLOAD / "04_CROSS_POPULATION_INPUTS/IQ_EuropeanGWAS_merged_all.tsv",
    "DS_CROSSPOP_PRIMARY": PAYLOAD / "04_CROSS_POPULATION_INPUTS/crosspop_primary.tsv",
    "DS_CROSSPOP_SENSITIVITY": PAYLOAD / "04_CROSS_POPULATION_INPUTS/crosspop_sensitivity.tsv",
    "DS_FIG3_PANEL_A": PAYLOAD / "04_CROSS_POPULATION_INPUTS/figure3_panel_a_source.tsv",
    "DS_FIG3_PANEL_B": PAYLOAD / "04_CROSS_POPULATION_INPUTS/figure3_panel_b_source.tsv",
    "DS_NOGAWA_WORKBOOK": PAYLOAD / "05_PGS_PROVIDER_CHECKPOINTS/Nogawa_analysis_results_20260526.xlsx",
    "DS_PRSICE_THRESHOLD_SCAN": PAYLOAD / "05_PGS_PROVIDER_CHECKPOINTS/prsice2_all_thresholds.csv",
    "DS_CASE_PGS_CANONICAL": PAYLOAD / "06_CASE_PGS_AND_CONTROL_AGGREGATES/IQ_PGS_caseonly.md",
    "DS_FIG4_AGGREGATE_SOURCE": PAYLOAD / "06_CASE_PGS_AND_CONTROL_AGGREGATES/figure4_aggregate_source.tsv",
    "DS_FIG4_DENSITY": PAYLOAD / "06_CASE_PGS_AND_CONTROL_AGGREGATES/Figure4_panel_a_density.tsv",
    "DS_FIG4_SUMMARY": PAYLOAD / "06_CASE_PGS_AND_CONTROL_AGGREGATES/Figure4_panel_a_summary.tsv",
    "DS_FIG4_PANEL_B": PAYLOAD / "06_CASE_PGS_AND_CONTROL_AGGREGATES/Figure4_panel_b_source.tsv",
    "DS_TABLE2_CHECKPOINT": PAYLOAD / "06_CASE_PGS_AND_CONTROL_AGGREGATES/table2_source.tsv",
    "DS_ROC_PANEL_A_RASTER": PAYLOAD / "07_ROC_INPUTS_OR_RASTER_CHECKPOINTS/suppfig_s1_panel_a_canonical.png",
    "DS_ROC_PANEL_B_RASTER": PAYLOAD / "07_ROC_INPUTS_OR_RASTER_CHECKPOINTS/suppfig_s1_panel_b_canonical.png",
    "DS_ROC_AUC_SOURCE": PAYLOAD / "07_ROC_INPUTS_OR_RASTER_CHECKPOINTS/suppfig_s1_source.tsv",
    "DS_TABLE_S1_CURRENT": PAYLOAD / "08_SUPPLEMENTARY_TABLE_SOURCES/table_s1_integrated_variant_annotation.tsv",
}

# M01: method thresholds are declared constants, not fitted values.
GENOME_WIDE_THRESHOLD = 5e-8
SUGGESTIVE_THRESHOLD = 1e-5
# M01 requires a *declared* constant inter-chromosome gap for cumulative x.
INTER_CHROM_GAP_BP = 20_000_000
AUTOSOMES = list(range(1, 23))


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ds(name: str) -> pathlib.Path:
    return DATASETS[name]


def ds_sha(name: str) -> str:
    return sha256(DATASETS[name])


def normalized_variant_key(chrom, pos, effect_allele, other_allele) -> str:
    """M01 variant key: chromosome:position_grch37:effect_allele:other_allele.

    Used as the terminal deterministic tie-break in M02/M04.
    """
    return f"{chrom}:{pos}:{str(effect_allele).upper()}:{str(other_allele).upper()}"


def repr_full(x) -> str:
    """Full-precision text for the returned claim table (repr round-trips float64)."""
    if x is None:
        return ""
    if isinstance(x, (float, np.floating)):
        return repr(float(x))
    if isinstance(x, (int, np.integer)):
        return str(int(x))
    return str(x)


def write_tsv(path: pathlib.Path, header: list[str], rows: list[list]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\t".join(header) + "\n")
        for r in rows:
            fh.write("\t".join("" if v is None else str(v) for v in r) + "\n")
