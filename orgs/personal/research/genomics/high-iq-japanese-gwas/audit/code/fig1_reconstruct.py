#!/usr/bin/env python3
"""M01 — Figure 1 (Manhattan + QQ) independent reconstruction.

Level: L1_INDEPENDENT_RECALCULATION_FROM_ANALYSIS_READY_DATA.

Implemented from 03_METHOD_CONTRACTS/M01_FIGURE1_MANHATTAN_QQ.md only. No
canonical plotting implementation, no rendered current figure, and no Package B
content was consulted. Statistical primitives come from scipy rather than being
hand-rolled: re-deriving a chi-square quantile here would make the audit code
less trustworthy than the thing it audits.

Emits full precision throughout; display rounding is applied only at the figure
layer, per the contract.
"""

import hashlib
import json
import pathlib
import sys

import numpy as np
import pandas as pd
from scipy import stats

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

# ---------------------------------------------------------------- declarations
# Contract constants (claim-matrix region A: exact match required).
GW_THRESHOLD = 5e-8
SUGGESTIVE_THRESHOLD = 1e-5
PANEL_COUNT = 2

# Free implementation choices the contract requires us to DECLARE, not to match.
INTER_CHROM_GAP_BP = 20_000_000      # constant gap between chromosomes, Manhattan x-axis
QQ_ORDER_CONVENTION = "(i-0.5)/m"    # contract's stated default; no deviation

ROOT = pathlib.Path(__file__).resolve().parents[2]
PKG = ROOT / "reconstruction-package-a-v1.1"
SRC = PKG / "04_DATA_PAYLOAD/01_JAPANESE_GWAS_SUMMARY/japanese_gwas_summary_privacy_filtered.tsv.gz"
OUT = ROOT / "audit/return/KAWASAKI_PHASE5C_INDEPENDENT_RETURN"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    (OUT / "02_ARTIFACT_FILES").mkdir(parents=True, exist_ok=True)
    (OUT / "03_SOURCE_TABLES").mkdir(parents=True, exist_ok=True)

    src_sha = sha256(SRC)
    df = pd.read_csv(SRC, sep="\t")
    n_rows_raw = len(df)

    # ------------------------------------------------------------- validation
    # Contract: "Validate finite positions and 0 < p_value <= 1."
    finite_pos = np.isfinite(df["position_grch37"])
    valid_p = (df["p_value"] > 0) & (df["p_value"] <= 1) & np.isfinite(df["p_value"])
    autosomal = df["chromosome"].between(1, 22)

    keep = finite_pos & valid_p & autosomal
    dropped = {
        "non_finite_position": int((~finite_pos).sum()),
        "p_out_of_range_or_nonfinite": int((~valid_p).sum()),
        "non_autosomal": int((~autosomal).sum()),
    }
    d = df.loc[keep].copy()
    n_valid = len(d)

    # qc_status is preserved, never used to silently drop rows (contract).
    qc_counts = d["qc_status"].value_counts().to_dict()

    # ------------------------------------------------------------------ lambda
    # lambda_GC = median(chi2_1 inverse-survival(P)) / median(chi2_1)
    chisq = stats.chi2.isf(d["p_value"].to_numpy(), df=1)
    lambda_gc = float(np.median(chisq) / stats.chi2.ppf(0.5, df=1))

    # ------------------------------------------------------- variant key check
    d["variant_key"] = (
        d["chromosome"].astype(str) + ":" + d["position_grch37"].astype(str) + ":"
        + d["effect_allele"].astype(str) + ":" + d["other_allele"].astype(str)
    )
    n_unique_keys = int(d["variant_key"].nunique())

    # ---------------------------------------------------------- threshold sets
    gw_hits = d.loc[d["p_value"] < GW_THRESHOLD].sort_values("p_value")
    sugg_hits = d.loc[d["p_value"] < SUGGESTIVE_THRESHOLD].sort_values("p_value")

    # -------------------------------------------------------- cumulative x-axis
    d = d.sort_values(["chromosome", "position_grch37"]).reset_index(drop=True)
    offset, offsets, ticks = 0, {}, []
    for c in range(1, 23):
        sub = d.loc[d["chromosome"] == c, "position_grch37"]
        if sub.empty:
            continue
        offsets[c] = offset - sub.min()
        ticks.append((c, offset + (sub.max() - sub.min()) / 2))
        offset += (sub.max() - sub.min()) + INTER_CHROM_GAP_BP
    d["x"] = d["position_grch37"] + d["chromosome"].map(offsets)
    d["neglog10p"] = -np.log10(d["p_value"])

    # ------------------------------------------------------------------ QQ panel
    m = n_valid
    obs = np.sort(d["p_value"].to_numpy())
    exp = (np.arange(1, m + 1) - 0.5) / m          # QQ_ORDER_CONVENTION
    obs_nlp, exp_nlp = -np.log10(obs), -np.log10(exp)

    # --------------------------------------------------------------- rendering
    fig, (axm, axq) = plt.subplots(
        1, 2, figsize=(15, 5.2), gridspec_kw={"width_ratios": [2.45, 1]}
    )

    shade = ["#2f4b7c", "#8fa8c8"]
    for c in range(1, 23):
        s = d.loc[d["chromosome"] == c]
        axm.scatter(s["x"], s["neglog10p"], s=3.2, c=shade[c % 2], linewidths=0, rasterized=True)
    axm.axhline(-np.log10(GW_THRESHOLD), color="#b3402f", lw=1.1, zorder=5)
    axm.axhline(-np.log10(SUGGESTIVE_THRESHOLD), color="#c9963f", lw=1.1, ls="--", zorder=5)
    if not gw_hits.empty:
        top = gw_hits.iloc[0]
        tx = top["position_grch37"] + offsets[top["chromosome"]]
        ty = -np.log10(top["p_value"])
        axm.scatter([tx], [ty], s=42, facecolor="none", edgecolor="#b3402f", lw=1.5, zorder=6)
        axm.annotate(top["variant_id"], (tx, ty), textcoords="offset points",
                     xytext=(9, 3), fontsize=9, color="#b3402f")
    axm.set_xticks([t[1] for t in ticks])
    axm.set_xticklabels([str(t[0]) for t in ticks], fontsize=8)
    axm.set_xlabel("Chromosome")
    axm.set_ylabel(r"$-\log_{10}(P)$")
    axm.set_title("(a) Manhattan", loc="left", fontsize=11)
    axm.set_xlim(d["x"].min() - INTER_CHROM_GAP_BP, d["x"].max() + INTER_CHROM_GAP_BP)
    axm.set_ylim(0, max(9.0, d["neglog10p"].max() * 1.12))
    for sp in ("top", "right"):
        axm.spines[sp].set_visible(False)

    axq.scatter(exp_nlp, obs_nlp, s=3.2, c="#2f4b7c", linewidths=0, rasterized=True)
    lim = max(exp_nlp.max(), obs_nlp.max()) * 1.03
    axq.plot([0, lim], [0, lim], color="#b3402f", lw=1.0)
    axq.set_xlabel(r"Expected $-\log_{10}(P)$")
    axq.set_ylabel(r"Observed $-\log_{10}(P)$")
    axq.set_title("(b) QQ", loc="left", fontsize=11)
    axq.set_xlim(0, lim)
    axq.set_ylim(0, lim)
    axq.annotate(rf"$\lambda_{{GC}}$ = {lambda_gc:.3f}", (0.05, 0.92),
                 xycoords="axes fraction", fontsize=10)
    for sp in ("top", "right"):
        axq.spines[sp].set_visible(False)

    fig.tight_layout()
    svg = OUT / "02_ARTIFACT_FILES/FIG1_manhattan_qq.svg"
    png = OUT / "02_ARTIFACT_FILES/FIG1_manhattan_qq_preview.png"
    fig.savefig(svg, format="svg", bbox_inches="tight")
    fig.savefig(png, format="png", dpi=140, bbox_inches="tight")
    plt.close(fig)

    # ------------------------------------------------------------- claim table
    claims = [
        ("FIG1_SOURCE_SHA256", src_sha, "exact"),
        ("FIG1_VALID_VARIANTS", str(n_valid), "exact"),
        ("FIG1_LAMBDA_GC", repr(lambda_gc), "continuous"),
        ("FIG1_GW_THRESHOLD", repr(GW_THRESHOLD), "exact"),
        ("FIG1_SUGGESTIVE_THRESHOLD", repr(SUGGESTIVE_THRESHOLD), "exact"),
        ("FIG1_PANEL_COUNT", str(PANEL_COUNT), "exact"),
    ]
    with open(OUT / "03_SOURCE_TABLES/fig1_claim_table.tsv", "w") as fh:
        fh.write("claim_id\tartifact_id\tvalue\tmethod_status\tsource_dataset_ids\tnotes\n")
        for cid, val, band in claims:
            fh.write(f"{cid}\tFIG1\t{val}\tL1_RECALCULATED\tDS_GWAS_CURRENT\t"
                     f"prespec band={band}\n")

    counts = {
        "input_rows_including_header_excluded": n_rows_raw,
        "valid_plotted_variants": n_valid,
        "dropped": dropped,
        "unique_variant_keys": n_unique_keys,
        "duplicate_keys": n_valid - n_unique_keys,
        "qc_status_counts": qc_counts,
        "n_below_gw_threshold": int(len(gw_hits)),
        "n_below_suggestive_threshold": int(len(sugg_hits)),
        "lambda_gc_full_precision": lambda_gc,
        "inter_chrom_gap_bp": INTER_CHROM_GAP_BP,
        "qq_order_convention": QQ_ORDER_CONVENTION,
        "source_sha256": src_sha,
        "min_p_value": float(d["p_value"].min()),
        "gw_hit_variants": gw_hits["variant_id"].tolist(),
        "suggestive_hit_variants_top10": sugg_hits["variant_id"].head(10).tolist(),
    }
    with open(OUT / "03_SOURCE_TABLES/fig1_counts.json", "w") as fh:
        json.dump(counts, fh, indent=2)

    print(json.dumps(counts, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
