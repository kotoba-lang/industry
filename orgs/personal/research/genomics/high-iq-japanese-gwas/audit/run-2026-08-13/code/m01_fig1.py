"""M01 - Figure 1: Manhattan + QQ, independent recalculation (L1).

Implemented from the M01 contract text only.

Contract points implemented here:
  * input DS_GWAS_CURRENT, GRCh37, autosomes 1-22
  * variant key chromosome:position_grch37:effect_allele:other_allele, with
    variant_id preserved for display/audit
  * qc_status preserved; no variant is restored from any other summary
  * validity filter: finite position and 0 < p_value <= 1
  * cumulative chromosome x with a declared constant inter-chromosome gap
  * -log10(P) with method thresholds at 5e-8 and 1e-5
  * QQ from the uniform order-statistic expectation (i-0.5)/m
  * lambda_GC = median(chi2_1.isf(P)) / median(chi2_1)
"""
from __future__ import annotations

import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy import stats

from common import (
    AUTOSOMES,
    GENOME_WIDE_THRESHOLD,
    INTER_CHROM_GAP_BP,
    OUT,
    SUGGESTIVE_THRESHOLD,
    ds,
    ds_sha,
    normalized_variant_key,
    repr_full,
    write_tsv,
)

DEST = OUT / "FIG1"


def load_gwas() -> pd.DataFrame:
    df = pd.read_csv(
        ds("DS_GWAS_CURRENT"),
        sep="\t",
        dtype={"chromosome": str, "variant_id": str, "effect_allele": str,
               "other_allele": str, "qc_status": str},
    )
    return df


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    df = load_gwas()
    n_total = len(df)

    audit: dict[str, object] = {}
    audit["input_sha256"] = ds_sha("DS_GWAS_CURRENT")
    audit["rows_in_input"] = n_total
    audit["columns"] = list(df.columns)
    audit["qc_status_counts"] = {str(k): int(v) for k, v in
                                 df["qc_status"].value_counts(dropna=False).items()}

    # --- coding -----------------------------------------------------------
    # chromosome to integer; autosomes 1-22 only (GRCh37 per contract).
    chrom_raw = df["chromosome"].astype(str).str.replace(r"^chr", "", regex=True)
    chrom_num = pd.to_numeric(chrom_raw, errors="coerce")
    df["chrom_int"] = chrom_num
    audit["chromosome_labels_present"] = sorted(set(chrom_raw))

    pos = pd.to_numeric(df["position_grch37"], errors="coerce")
    p = pd.to_numeric(df["p_value"], errors="coerce")

    is_autosome = df["chrom_int"].isin(AUTOSOMES)
    pos_finite = np.isfinite(pos)
    p_valid = np.isfinite(p) & (p > 0) & (p <= 1)

    audit["excluded_non_autosome"] = int((~is_autosome).sum())
    audit["excluded_nonfinite_position"] = int((is_autosome & ~pos_finite).sum())
    audit["excluded_invalid_p"] = int((is_autosome & pos_finite & ~p_valid).sum())
    audit["p_equal_zero"] = int((p == 0).sum())
    audit["p_gt_one"] = int((p > 1).sum())
    audit["p_missing"] = int(p.isna().sum())

    valid = is_autosome & pos_finite & p_valid
    v = df.loc[valid].copy()
    v["position_grch37"] = pos[valid].astype(np.int64)
    v["p_value"] = p[valid].astype(float)
    v["chrom_int"] = v["chrom_int"].astype(int)
    v["variant_key"] = [
        normalized_variant_key(c, b, ea, oa)
        for c, b, ea, oa in zip(v["chrom_int"], v["position_grch37"],
                                v["effect_allele"], v["other_allele"])
    ]
    n_valid = len(v)
    audit["valid_plotted_variants"] = n_valid

    # --- lambda_GC --------------------------------------------------------
    # chi-square(1) inverse survival of the two-sided association P.
    chisq = stats.chi2.isf(v["p_value"].to_numpy(), df=1)
    lambda_gc = float(np.median(chisq) / stats.chi2.ppf(0.5, 1))
    audit["median_chisq_observed"] = float(np.median(chisq))
    audit["median_chisq_null"] = float(stats.chi2.ppf(0.5, 1))
    audit["lambda_gc"] = lambda_gc

    # --- cumulative x for the Manhattan panel -----------------------------
    v = v.sort_values(["chrom_int", "position_grch37"], kind="mergesort").reset_index(drop=True)
    offsets: dict[int, float] = {}
    centers: dict[int, float] = {}
    cum = 0.0
    xs = np.empty(len(v), dtype=float)
    for c in AUTOSOMES:
        m = v["chrom_int"].to_numpy() == c
        if not m.any():
            continue
        cpos = v.loc[m, "position_grch37"].to_numpy(dtype=float)
        offsets[c] = cum
        xs[m] = cum + cpos
        centers[c] = cum + (cpos.min() + cpos.max()) / 2.0
        cum += float(cpos.max()) + INTER_CHROM_GAP_BP
    v["x_cumulative"] = xs
    v["neglog10_p"] = -np.log10(v["p_value"].to_numpy())
    audit["inter_chromosome_gap_bp"] = INTER_CHROM_GAP_BP
    audit["chromosomes_plotted"] = sorted(offsets)
    audit["n_chromosomes_plotted"] = len(offsets)

    audit["n_genome_wide_significant"] = int((v["p_value"] < GENOME_WIDE_THRESHOLD).sum())
    audit["n_suggestive"] = int((v["p_value"] < SUGGESTIVE_THRESHOLD).sum())
    audit["min_p"] = float(v["p_value"].min())
    audit["max_neglog10_p"] = float(v["neglog10_p"].max())

    # --- QQ ---------------------------------------------------------------
    # Uniform order-statistic expectation (i-0.5)/m, i ascending in P.
    obs_p = np.sort(v["p_value"].to_numpy())
    m = obs_p.size
    exp_p = (np.arange(1, m + 1) - 0.5) / m
    obs_nl = -np.log10(obs_p)
    exp_nl = -np.log10(exp_p)
    audit["qq_order_statistic_convention"] = "(i-0.5)/m"
    audit["qq_n_points"] = int(m)

    # --- figure -----------------------------------------------------------
    fig = plt.figure(figsize=(14, 5.2))
    gs = fig.add_gridspec(1, 2, width_ratios=[2.6, 1.0], wspace=0.18)

    ax = fig.add_subplot(gs[0, 0])
    colors = ["#3b6ea5", "#8a9bb0"]
    for c in sorted(offsets):
        m_c = v["chrom_int"].to_numpy() == c
        ax.scatter(v.loc[m_c, "x_cumulative"], v.loc[m_c, "neglog10_p"],
                   s=3.0, c=colors[c % 2], linewidths=0, rasterized=True)
    ax.axhline(-np.log10(GENOME_WIDE_THRESHOLD), color="#c0392b", lw=1.0, ls="--",
               label=f"genome-wide {GENOME_WIDE_THRESHOLD:g}")
    ax.axhline(-np.log10(SUGGESTIVE_THRESHOLD), color="#e08a1e", lw=1.0, ls=":",
               label=f"suggestive {SUGGESTIVE_THRESHOLD:g}")
    ax.set_xticks([centers[c] for c in sorted(centers)])
    ax.set_xticklabels([str(c) for c in sorted(centers)], fontsize=7)
    ax.set_xlabel("Chromosome (GRCh37)")
    ax.set_ylabel(r"$-\log_{10}(P)$")
    ax.set_title("A  Manhattan", loc="left", fontweight="bold")
    ax.set_xlim(-INTER_CHROM_GAP_BP, cum)
    ax.set_ylim(0, max(8.0, float(v["neglog10_p"].max()) * 1.08))
    ax.legend(loc="upper right", fontsize=7, frameon=False)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)

    ax2 = fig.add_subplot(gs[0, 1])
    ax2.scatter(exp_nl, obs_nl, s=3.0, c="#3b6ea5", linewidths=0, rasterized=True)
    lim = max(exp_nl.max(), obs_nl.max()) * 1.05
    ax2.plot([0, lim], [0, lim], color="#888888", lw=0.9, ls="-")
    ax2.set_xlim(0, lim)
    ax2.set_ylim(0, lim)
    ax2.set_xlabel(r"Expected $-\log_{10}(P)$")
    ax2.set_ylabel(r"Observed $-\log_{10}(P)$")
    ax2.set_title("B  QQ", loc="left", fontweight="bold")
    ax2.text(0.04, 0.94, rf"$\lambda_{{GC}}$ = {lambda_gc:.3f}",
             transform=ax2.transAxes, va="top", fontsize=9)
    for s in ("top", "right"):
        ax2.spines[s].set_visible(False)

    fig.savefig(DEST / "FIG1_manhattan_qq.svg", bbox_inches="tight")
    fig.savefig(DEST / "FIG1_manhattan_qq.pdf", bbox_inches="tight")
    fig.savefig(DEST / "FIG1_manhattan_qq.png", dpi=200, bbox_inches="tight")
    plt.close(fig)

    # --- machine-readable source tables -----------------------------------
    src = v[["variant_id", "variant_key", "chrom_int", "position_grch37",
             "effect_allele", "other_allele", "beta", "standard_error",
             "p_value", "neglog10_p", "x_cumulative", "qc_status"]].rename(
        columns={"chrom_int": "chromosome"})
    src.to_csv(DEST / "FIG1_manhattan_source.tsv.gz", sep="\t", index=False)

    qq = pd.DataFrame({
        "rank_i": np.arange(1, m + 1),
        "expected_p": exp_p,
        "observed_p": obs_p,
        "expected_neglog10_p": exp_nl,
        "observed_neglog10_p": obs_nl,
    })
    qq.to_csv(DEST / "FIG1_qq_source.tsv.gz", sep="\t", index=False)

    write_tsv(DEST / "FIG1_chromosome_offsets.tsv",
              ["chromosome", "cumulative_offset_bp", "tick_center_bp", "n_variants"],
              [[c, repr_full(offsets[c]), repr_full(centers[c]),
                int((v["chrom_int"] == c).sum())] for c in sorted(offsets)])

    (DEST / "FIG1_audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")

    claims = {
        "FIG1_SOURCE_SHA256": audit["input_sha256"],
        "FIG1_VALID_VARIANTS": n_valid,
        "FIG1_LAMBDA_GC": lambda_gc,
        "FIG1_GW_THRESHOLD": GENOME_WIDE_THRESHOLD,
        "FIG1_SUGGESTIVE_THRESHOLD": SUGGESTIVE_THRESHOLD,
        "FIG1_PANEL_COUNT": 2,
    }
    (DEST / "FIG1_claims.json").write_text(
        json.dumps({k: repr_full(x) for k, x in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit}


if __name__ == "__main__":
    r = main()
    for k, x in r["claims"].items():
        print(f"{k}\t{repr_full(x)}")
    a = r["audit"]
    print("\n-- filter audit --")
    for k in ("rows_in_input", "excluded_non_autosome", "excluded_nonfinite_position",
              "excluded_invalid_p", "valid_plotted_variants", "n_genome_wide_significant",
              "n_suggestive", "min_p", "qc_status_counts", "chromosome_labels_present"):
        print(f"{k}\t{a[k]}")
