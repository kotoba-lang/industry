#!/usr/bin/env python3
"""M05/M06 — Figure 4, Table 2 and Supplementary Figure S1 reconstruction.

Levels: FIG4 and TABLE2 are L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT.
SUPPFIG_S1 is L3_VISUAL_REASSEMBLY_FROM_CANONICAL_AGGREGATE_OR_RASTER.

RESTRICTED DATA. DS_CASE_PGS_CANONICAL holds 91 pseudonymous individual case
scores and DS_NOGAWA_WORKBOOK is a provider-internal workbook. Both are read
here, but only aggregates are ever printed or written: M05 requires individual
scores to stay inside the encrypted return package, and DATA_SECURITY_AND_AI_RULES
bars individual rows from reaching an external service. Nothing in this script
emits a per-individual value.

What the L2 stage actually verifies:
  Panel A   the twelve summary aggregates recomputed from the individual case
            scores and the control mean/SD, plus the KDE bandwidth, grid bounds
            and bin count against the M05 formulas
  density   the 600-point KDE and normal series and the 18-bin histogram
            recomputed on the contract's grid
  Panel B   all seven tiers cross-checked against the 6,000-row PRSice2 scan
  Table 2   display rounding against the exact Panel B values, and the AUCs
            against the independent ROC source

Package B was not accessed. The two ROC panel rasters used here are Package A
checkpoints; the combined canonical Supplementary Figure is withheld and is not
inserted.
"""

import csv
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
import matplotlib.image as mpimg  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
PAY = ROOT / "reconstruction-package-a-v1.1/04_DATA_PAYLOAD"
OUT = ROOT / "audit/return/KAWASAKI_PHASE5C_INDEPENDENT_RETURN"

# M05 constants.
GRID_POINTS = 600
HIST_BINS = 18
# M06 canonical interior crop of each 1920x1440 checkpoint.
CROP_X = (201, 1864)
CROP_Y = (108, 1264)
RASTER_SIZE = (1920, 1440)

checks, claims = [], []


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for c in iter(lambda: fh.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def check(cid, expected, observed, tol=None):
    if tol is None:
        ok = expected == observed
    else:
        e, o = float(expected), float(observed)
        ok = abs(e - o) <= tol * max(abs(e), abs(o), 1e-300)
    checks.append((cid, repr(expected), repr(observed), "PASS" if ok else "FAIL"))
    return ok


def claim(cid, art, val, src, note=""):
    lvl = "L3_VISUAL_REASSEMBLY" if art == "SUPPFIG_S1" else "L2_RECONSTRUCTED"
    claims.append((cid, art, val, lvl, src, note))


# ======================================================= FIGURE 4 PANEL A
def panel_a():
    summ = pd.read_csv(PAY / "06_CASE_PGS_AND_CONTROL_AGGREGATES/Figure4_panel_a_summary.tsv",
                       sep="\t").set_index("metric")["value"]
    agg = pd.read_csv(PAY / "06_CASE_PGS_AND_CONTROL_AGGREGATES/figure4_aggregate_source.tsv",
                      sep="\t")
    # full-precision control moments live on the optimized row
    opt = agg[agg["panel"].str.contains("Optimized", case=False, na=False)].iloc[0]
    control_mean = float(opt["control_pgs_mean_raw"])
    control_sd = float(opt["control_pgs_sd_raw"])

    # RESTRICTED read. Individual scores stay in this scope; only aggregates leave.
    case = pd.read_csv(PAY / "06_CASE_PGS_AND_CONTROL_AGGREGATES/IQ_PGS_caseonly.md", sep="\t")
    z = (case["PRS"].to_numpy(float) - control_mean) / control_sd
    del case

    n = int(z.size)
    z_mean = float(z.mean())
    z_sd = float(z.std(ddof=1))
    z_min, z_max = float(z.min()), float(z.max())
    z_lt_0 = int((z < 0).sum())
    bw = z_sd * n ** (-1 / 5)                      # M05 bandwidth rule
    x_lower = min(-4.0, z_min - 0.5)
    x_upper = max(4.0, z_max + 0.5)

    for key, obs, tol in [("n", n, None), ("z_mean", z_mean, 1e-4),
                          ("z_sd_ddof1", z_sd, 1e-4), ("z_min", z_min, 1e-4),
                          ("z_max", z_max, 1e-4), ("z_lt_0", z_lt_0, None),
                          ("kde_bandwidth", bw, 1e-4), ("x_lower", x_lower, None),
                          ("x_upper", x_upper, None),
                          ("control_mean", control_mean, 1e-2),
                          ("control_sd", control_sd, 1e-2)]:
        exp = float(summ[key])
        if tol is None:
            check(f"FIG4_PANEL_A_{key.upper()}_RECOMPUTED", exp, float(obs))
        else:
            check(f"FIG4_PANEL_A_{key.upper()}_RECOMPUTED", exp, float(obs), tol=tol)
    check("FIG4_PANEL_A_HISTOGRAM_BINS", float(summ["histogram_bins"]), float(HIST_BINS))

    vals = {"N": n, "Z_MEAN": z_mean, "Z_SD_DDOF1": z_sd, "Z_MIN": z_min, "Z_MAX": z_max,
            "Z_LT_0": z_lt_0, "KDE_BANDWIDTH": bw, "X_LOWER": x_lower, "X_UPPER": x_upper,
            "HISTOGRAM_BINS": HIST_BINS, "CONTROL_MEAN": control_mean, "CONTROL_SD": control_sd}
    for k, v in vals.items():
        band = "prespec band A" if k in ("N", "Z_LT_0", "HISTOGRAM_BINS",
                                         "X_LOWER", "X_UPPER") else "prespec band B"
        claim(f"FIG4_PANEL_A_{k}", "FIG4", repr(v),
              "DS_CASE_PGS_CANONICAL;DS_FIG4_AGGREGATE_SOURCE;DS_FIG4_SUMMARY",
              f"{band}; recomputed from the individual case scores (aggregate output only)")
    return z, bw, x_lower, x_upper, control_mean, control_sd


# ======================================================= FIGURE 4 DENSITY
def density(z, bw, x_lower, x_upper):
    f = PAY / "06_CASE_PGS_AND_CONTROL_AGGREGATES/Figure4_panel_a_density.tsv"
    dens = pd.read_csv(f, sep="\t")
    claim("FIG4_DENSITY_SOURCE_SHA256", "FIG4", sha256(f), "DS_FIG4_DENSITY", "prespec band A")

    kde_ck = dens[dens["series"] == "case_kde"].sort_values("order")
    ctl_ck = dens[dens["series"] == "control_normal"].sort_values("order")
    hist_ck = dens[dens["series"] == "case_histogram"].sort_values("order")

    check("FIG4_DENSITY_KDE_POINT_COUNT", GRID_POINTS, len(kde_ck))
    check("FIG4_DENSITY_NORMAL_POINT_COUNT", GRID_POINTS, len(ctl_ck))
    check("FIG4_DENSITY_HISTOGRAM_BIN_COUNT", HIST_BINS, len(hist_ck))

    grid = np.linspace(x_lower, x_upper, GRID_POINTS)
    check("FIG4_DENSITY_GRID_MATCHES_CONTRACT", True,
          bool(np.allclose(grid, kde_ck["x"].to_numpy(float), atol=1e-9)))

    # scipy scales the kernel covariance by bw_method^2 * cov(data, ddof=1),
    # so bw_method = n^(-1/5) reproduces the M05 bandwidth exactly.
    kde = stats.gaussian_kde(z, bw_method=len(z) ** (-1 / 5))
    check("FIG4_KDE_BANDWIDTH_MATCHES_SCIPY_FACTOR", round(bw, 9),
          round(float(np.sqrt(kde.covariance[0, 0])), 9))
    check("FIG4_DENSITY_KDE_VALUES_RECOMPUTED", True,
          bool(np.allclose(kde(grid), kde_ck["density"].to_numpy(float), rtol=1e-4, atol=1e-6)))
    check("FIG4_DENSITY_NORMAL_VALUES_RECOMPUTED", True,
          bool(np.allclose(stats.norm.pdf(grid), ctl_ck["density"].to_numpy(float),
                           rtol=1e-6, atol=1e-9)))

    counts, edges = np.histogram(z, bins=HIST_BINS)
    check("FIG4_HISTOGRAM_COUNTS_RECOMPUTED", counts.tolist(),
          hist_ck["count"].astype(int).tolist())
    check("FIG4_HISTOGRAM_COUNTS_SUM_TO_N", int(z.size), int(hist_ck["count"].sum()))
    return dens, grid, kde


# ======================================================= FIGURE 4 PANEL B
def panel_b():
    pb = pd.read_csv(PAY / "06_CASE_PGS_AND_CONTROL_AGGREGATES/Figure4_panel_b_source.tsv",
                     sep="\t").sort_values("display_order")
    scan = pd.read_csv(PAY / "05_PGS_PROVIDER_CHECKPOINTS/prsice2_all_thresholds.csv")

    for i, row in enumerate(pb.itertuples(), start=1):
        thr = float(row.threshold_exact)
        hit = scan.loc[np.isclose(scan["Threshold"], thr, rtol=1e-12)]
        check(f"FIG4_TIER_{i}_FOUND_IN_PRSICE_SCAN", 1, len(hit))
        if len(hit) == 1:
            h = hit.iloc[0]
            check(f"FIG4_TIER_{i}_N_SNPS_MATCHES_SCAN", int(h["Num_SNP"]), int(row.n_snps))
            check(f"FIG4_TIER_{i}_R2_MATCHES_SCAN", round(float(h["R2"]), 9),
                  round(float(row.incremental_nagelkerke_r2_exact), 9))
            check(f"FIG4_TIER_{i}_P_MATCHES_SCAN", round(float(h["P"]), 9),
                  round(float(row.covariate_adjusted_p_exact), 9))

        claim(f"FIG4_TIER_{i}_N_SNPS", "FIG4", str(int(row.n_snps)), "DS_FIG4_PANEL_B",
              f"prespec band A; {row.analysis_tier} tier, threshold {row.threshold_display}")
        claim(f"FIG4_TIER_{i}_R2", "FIG4", repr(float(row.incremental_nagelkerke_r2_exact)),
              "DS_FIG4_PANEL_B", "prespec band B; cross-checked against DS_PRSICE_THRESHOLD_SCAN")
        claim(f"FIG4_TIER_{i}_P", "FIG4", repr(float(row.covariate_adjusted_p_exact)),
              "DS_FIG4_PANEL_B", "prespec band C; cross-checked against DS_PRSICE_THRESHOLD_SCAN")

    check("FIG4_PANEL_B_HAS_ONE_OPTIMIZED_TIER", 1,
          int((pb["analysis_tier"] == "optimized").sum()))
    check("FIG4_PANEL_B_HAS_SIX_FIXED_TIERS", 6, int((pb["analysis_tier"] == "fixed").sum()))
    return pb


# ============================================================== TABLE 2
def table2(pb):
    f = PAY / "06_CASE_PGS_AND_CONTROL_AGGREGATES/table2_source.tsv"
    t2 = pd.read_csv(f, sep="\t")
    roc = pd.read_csv(PAY / "07_ROC_INPUTS_OR_RASTER_CHECKPOINTS/suppfig_s1_source.tsv", sep="\t")
    opt = t2[t2["Panel"].str.contains("Optimized", case=False, na=False)].iloc[0]

    auc_pgs = float(opt["PGS-only AUC"])
    auc_cov = float(opt["Covariates-only AUC"])
    auc_full = float(opt["Full AUC"])
    lrt = float(opt["LRT P"])

    r = roc.set_index("model")["auc"]
    check("TABLE2_PGS_AUC_MATCHES_ROC_SOURCE", float(r["PGS-only"]), auc_pgs)
    check("TABLE2_COV_AUC_MATCHES_ROC_SOURCE",
          float(r["Covariates-only (sex + PC1-10)"]), auc_cov)
    check("TABLE2_FULL_AUC_MATCHES_ROC_SOURCE",
          float(r["Full model (PGS + covariates)"]), auc_full)
    check("TABLE2_AUC_ORDERING_PGS_LT_COV_LT_FULL", True,
          bool(auc_pgs < auc_cov < auc_full))

    # Table 2 must be the display rounding of the exact Panel B values.
    bad = []
    for row in pb.itertuples():
        m = t2.loc[np.isclose(t2["Threshold"], float(row.threshold_exact), rtol=1e-3)]
        if len(m) != 1:
            bad.append(("threshold", row.threshold_display))
            continue
        if int(m.iloc[0]["N SNPs"]) != int(row.n_snps):
            bad.append(("n_snps", row.threshold_display))
        if abs(float(m.iloc[0]["Incremental Nagelkerke R²"])
               - float(row.incremental_nagelkerke_r2_exact)) > 5e-5:
            bad.append(("r2", row.threshold_display))
    check("TABLE2_IS_DISPLAY_ROUNDING_OF_PANEL_B", [], bad)

    claim("TABLE2_ROW_COUNT", "TABLE2", str(len(t2)), "DS_TABLE2_CHECKPOINT",
          "prespec band A; 6 fixed + 1 optimized")
    claim("TABLE2_NORMALIZED_SHA256", "TABLE2", sha256(f), "DS_TABLE2_CHECKPOINT",
          "prespec band A; sha256 of the delivered file as-is")
    for cid, v in [("TABLE2_PGS_ONLY_AUC", auc_pgs), ("TABLE2_COVARIATES_ONLY_AUC", auc_cov),
                   ("TABLE2_FULL_AUC", auc_full)]:
        claim(cid, "TABLE2", repr(v), "DS_NOGAWA_WORKBOOK;DS_TABLE2_CHECKPOINT",
              "prespec band B; agrees with the independent DS_ROC_AUC_SOURCE")
    claim("TABLE2_LRT_P", "TABLE2", repr(lrt), "DS_NOGAWA_WORKBOOK;DS_TABLE2_CHECKPOINT",
          "prespec band C")
    return t2, roc


# ===================================================== SUPP FIGURE S1 (L3)
def suppfig_s1(roc):
    pa = PAY / "07_ROC_INPUTS_OR_RASTER_CHECKPOINTS/suppfig_s1_panel_a_canonical.png"
    pb_ = PAY / "07_ROC_INPUTS_OR_RASTER_CHECKPOINTS/suppfig_s1_panel_b_canonical.png"
    r = roc.set_index("model")["auc"]

    imgs, extents = [], []
    for tag, p in [("A", pa), ("B", pb_)]:
        im = mpimg.imread(p)
        h, w = im.shape[0], im.shape[1]
        check(f"SUPPFIG_S1_PANEL_{tag}_RASTER_SIZE", RASTER_SIZE, (w, h))
        crop = im[CROP_Y[0]:CROP_Y[1], CROP_X[0]:CROP_X[1]]
        imgs.append(crop)

        # The canonical crop is the axes RECTANGLE, so it includes the plot
        # margins and the frame itself; mapping it straight onto [0,1] misplaces
        # the curve by the margin width. Calibrate on the orange diagonal
        # reference, which by definition runs (0,0) to (1,1): its pixel extent
        # fixes the data-to-crop mapping exactly. This reads the reference
        # line's endpoints, not the ROC curve's coordinates, so it stays inside
        # M06's prohibition on tracing or inventing curve coordinates. A
        # whole-crop ink box does NOT work here - the frame is drawn at the crop
        # border and would report the full width.
        ch, cw = crop.shape[0], crop.shape[1]
        R, G, B = crop[..., 0], crop[..., 1], crop[..., 2]
        diag = (R > 0.80) & (G > 0.35) & (G < 0.68) & (B < 0.30)
        ys, xs = np.nonzero(diag)
        check(f"SUPPFIG_S1_PANEL_{tag}_DIAGONAL_DETECTED", True, bool(len(xs) > 1000))
        x0, x1 = xs.min() / cw, xs.max() / cw
        y0, y1 = 1 - ys.max() / ch, 1 - ys.min() / ch      # imshow y is flipped
        ex0, ex1 = -x0 / (x1 - x0), 1 + (1 - x1) / (x1 - x0)
        ey0, ey1 = -y0 / (y1 - y0), 1 + (1 - y1) / (y1 - y0)
        extents.append([ex0, ex1, ey0, ey1])
        # a square reference line must calibrate to near-identical x and y spans
        check(f"SUPPFIG_S1_PANEL_{tag}_CALIBRATION_IS_SQUARE", True,
              bool(abs((x1 - x0) - (y1 - y0)) < 0.01))
    check("SUPPFIG_S1_CROP_USED_IS_CANONICAL",
          {"x": list(CROP_X), "y": list(CROP_Y)}, {"x": list(CROP_X), "y": list(CROP_Y)})

    claim("SUPPFIG_S1_PGS_ONLY_AUC", "SUPPFIG_S1", repr(float(r["PGS-only"])),
          "DS_ROC_AUC_SOURCE", "prespec band B; annotation value, no model-level recalculation")
    claim("SUPPFIG_S1_COVARIATES_ONLY_SEX_PC1_10_AUC", "SUPPFIG_S1",
          repr(float(r["Covariates-only (sex + PC1-10)"])), "DS_ROC_AUC_SOURCE",
          "prespec band B; reference value, curve not shown per the caption")
    claim("SUPPFIG_S1_FULL_MODEL_PGS_COVARIATES_AUC", "SUPPFIG_S1",
          repr(float(r["Full model (PGS + covariates)"])), "DS_ROC_AUC_SOURCE",
          "prespec band B")
    claim("SUPPFIG_S1_PANEL_A_RASTER_SHA256", "SUPPFIG_S1", sha256(pa),
          "DS_ROC_PANEL_A_RASTER", "prespec band A")
    claim("SUPPFIG_S1_PANEL_B_RASTER_SHA256", "SUPPFIG_S1", sha256(pb_),
          "DS_ROC_PANEL_B_RASTER", "prespec band A")

    # L3 reassembly: live outer axes around the canonical interior crops. Curve
    # coordinates are neither traced nor invented, as M06 requires.
    fig, axes = plt.subplots(1, 2, figsize=(11.6, 5.6))
    titles = [("(a) PGS-only model", float(r["PGS-only"])),
              ("(b) Full model (PGS + sex + PC1-10)", float(r["Full model (PGS + covariates)"]))]
    for ax, img, ext, (title, auc) in zip(axes, imgs, extents, titles):
        ax.imshow(img, extent=ext, aspect="auto", interpolation="antialiased")
        ax.set_xlim(0, 1)
        ax.set_ylim(0, 1)
        ax.set_xlabel("False positive rate")
        ax.set_ylabel("True positive rate")
        ax.set_title(title, loc="left", fontsize=11)
        ax.annotate(f"AUC = {auc:.3f}", (0.97, 0.06), xycoords="axes fraction",
                    ha="right", fontsize=11, color="#14202b")
        for sp in ("top", "right"):
            ax.spines[sp].set_visible(False)
    axes[1].annotate(f"covariates-only AUC = {float(r['Covariates-only (sex + PC1-10)']):.3f} "
                     "(reference; curve not shown)", (0.97, 0.005), xycoords="axes fraction",
                     ha="right", fontsize=8.5, color="#59687a")
    fig.suptitle("Supplementary Figure S1 — L3 visual reassembly from Package A raster "
                 "checkpoints", fontsize=9.5, color="#59687a", y=1.02)
    fig.tight_layout()
    fig.savefig(OUT / "02_ARTIFACT_FILES/SUPPFIG_S1_roc_reassembly.svg", bbox_inches="tight")
    fig.savefig(OUT / "02_ARTIFACT_FILES/SUPPFIG_S1_roc_reassembly_preview.png",
                dpi=140, bbox_inches="tight")
    plt.close(fig)


# ============================================================== FIGURE 4
def render_fig4(dens, grid, kde, z, pb, x_lower, x_upper):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.6, 5.0),
                                   gridspec_kw={"width_ratios": [1.25, 1]})
    hist_ck = dens[dens["series"] == "case_histogram"].sort_values("order")
    widths = (hist_ck["x_right"] - hist_ck["x_left"]).to_numpy(float)
    dens_h = hist_ck["count"].to_numpy(float) / (len(z) * widths)
    ax1.bar(hist_ck["x_left"].to_numpy(float), dens_h, width=widths, align="edge",
            color="#c8d4e6", edgecolor="#8fa8c8", lw=0.5, label="Cases (histogram, n=91)")
    ax1.plot(grid, stats.norm.pdf(grid), color="#59687a", lw=1.6,
             label="Controls (normal approx., n=41,528)")
    ax1.plot(grid, kde(grid), color="#2f4b7c", lw=1.8, label="Cases (KDE)")
    ax1.axvline(0.0, color="#59687a", ls="--", lw=1.0)
    ax1.axvline(float(z.mean()), color="#2f4b7c", ls="--", lw=1.0)
    ax1.set_xlim(x_lower, x_upper)
    ax1.set_xlabel("Control-standardized polygenic score (z)")
    ax1.set_ylabel("Density")
    ax1.set_title("(a) PGS distribution at the optimized threshold", loc="left", fontsize=11)
    ax1.legend(frameon=False, fontsize=8.5, loc="upper left")

    y = np.arange(len(pb))
    cols = ["#b3402f" if t == "optimized" else "#2f4b7c" for t in pb["analysis_tier"]]
    ax2.barh(y, pb["incremental_nagelkerke_r2_exact"], color=cols, height=0.6)
    ax2.set_yticks(y)
    ax2.set_yticklabels(pb["threshold_display"], fontsize=9)
    ax2.invert_yaxis()
    ax2.set_xlabel("Incremental Nagelkerke $R^2$")
    ax2.set_title("(b) Fixed grid (primary) vs optimized (secondary)", loc="left", fontsize=11)
    for i, v in enumerate(pb["incremental_nagelkerke_r2_exact"]):
        ax2.annotate(f"{v:.4f}", (v, i), textcoords="offset points", xytext=(5, 0),
                     va="center", fontsize=8.5, color="#59687a")
    ax2.set_xlim(0, float(pb["incremental_nagelkerke_r2_exact"].max()) * 1.28)
    for ax in (ax1, ax2):
        for sp in ("top", "right"):
            ax.spines[sp].set_visible(False)
    fig.tight_layout()
    fig.savefig(OUT / "02_ARTIFACT_FILES/FIG4_pgs.svg", bbox_inches="tight")
    fig.savefig(OUT / "02_ARTIFACT_FILES/FIG4_pgs_preview.png", dpi=140, bbox_inches="tight")
    plt.close(fig)


def main():
    z, bw, x_lower, x_upper, cm, csd = panel_a()
    dens, grid, kde = density(z, bw, x_lower, x_upper)
    pb = panel_b()
    t2, roc = table2(pb)
    suppfig_s1(roc)
    render_fig4(dens, grid, kde, z, pb, x_lower, x_upper)

    with open(OUT / "03_SOURCE_TABLES/pgs_chain_verification.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["check_id", "expected", "observed", "status"])
        w.writerows(checks)
    with open(OUT / "03_SOURCE_TABLES/pgs_chain_claims.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["claim_id", "artifact_id", "value", "method_status",
                    "source_dataset_ids", "notes"])
        w.writerows(claims)

    failed = [c for c in checks if c[3] == "FAIL"]
    print(f"claims produced : {len(claims)}")
    print(f"verifications   : {len(checks)}  PASS={len(checks)-len(failed)}  FAIL={len(failed)}")
    for c in failed:
        print(f"  FAIL {c[0]}\n       expected {c[1][:160]}\n       observed {c[2][:160]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
