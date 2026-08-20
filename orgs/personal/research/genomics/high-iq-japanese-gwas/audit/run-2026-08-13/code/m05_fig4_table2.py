"""M05 - Figure 4 and Table 2: PGS checkpoint reconstruction (L2).

Independent part
  * Panel A is rebuilt from the individual case PGS series and the aggregate
    control mean/SD: z = (case_score - control_mean) / control_sample_SD,
    then all twelve Panel A summary metrics, a Gaussian KDE on the declared
    600-point grid, an 18-bin histogram, and the N(0,1) control curve.
  * Panel B tiers are re-selected from the full PRSice2 threshold scan. The
    six fixed thresholds are matched on the prespecified grid and the
    "optimized" tier is re-derived as the scan-wide maximiser of R2 rather
    than being taken on trust from the checkpoint label.

Checkpoint part
  * OR/CI, the three AUCs and the LRT P come from the provider checkpoint.
    No PRSice2 rerun from the 41,528 individual control genotypes is performed
    or claimed.

Privacy: individual case scores are read locally and never printed, plotted
per-point, or written out. Only aggregates leave this module.
"""
from __future__ import annotations

import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from common import OUT, ds, ds_sha, repr_full, write_tsv

DEST = OUT / "FIG4_TABLE2"

# M05: the prespecified fixed P thresholds of the primary grid.
FIXED_TARGETS = [5e-8, 5e-5, 1e-4, 1e-3, 1e-2, 5e-2]
KDE_GRID_POINTS = 600
HIST_BINS = 18


def load_case_scores() -> np.ndarray:
    """Read the canonical case-only PGS series. Values never leave this process."""
    df = pd.read_csv(ds("DS_CASE_PGS_CANONICAL"), sep="\t")
    col = [c for c in df.columns if c.strip().upper() in {"PRS", "PGS", "SCORE"}]
    if not col:
        col = [df.columns[-1]]
    return df[col[0]].to_numpy(dtype=float)


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    audit: dict[str, object] = {}

    # ------------------------------------------------------------------
    # Panel A
    # ------------------------------------------------------------------
    agg = pd.read_csv(ds("DS_FIG4_AGGREGATE_SOURCE"), sep="\t", dtype=str)
    opt_rows = agg[agg["panel"].astype(str).str.startswith("Optimized")]
    opt = opt_rows.iloc[0]
    control_mean = float(opt["control_pgs_mean_raw"])
    control_sd = float(opt["control_pgs_sd_raw"])
    audit["control_mean_raw"] = control_mean
    audit["control_sd_raw"] = control_sd
    audit["control_n"] = int(opt["control_n"])

    scores = load_case_scores()
    n = int(scores.size)
    z = (scores - control_mean) / control_sd

    z_mean = float(np.mean(z))
    z_sd1 = float(np.std(z, ddof=1))
    z_min = float(np.min(z))
    z_max = float(np.max(z))
    z_lt_0 = int(np.sum(z < 0))
    kde_bw = float(z_sd1 * n ** (-1.0 / 5.0))
    x_lower = float(min(-4.0, z_min - 0.5))
    x_upper = float(max(4.0, z_max + 0.5))

    recomputed = {
        "n": n, "z_mean": z_mean, "z_sd_ddof1": z_sd1, "z_min": z_min,
        "z_max": z_max, "z_lt_0": z_lt_0, "kde_bandwidth": kde_bw,
        "x_lower": x_lower, "x_upper": x_upper, "histogram_bins": HIST_BINS,
        "control_mean": control_mean, "control_sd": control_sd,
    }

    summ = pd.read_csv(ds("DS_FIG4_SUMMARY"), sep="\t")
    chk_summary = {str(r["metric"]): float(r["value"]) for _, r in summ.iterrows()}
    audit["panel_a_summary_sha256"] = ds_sha("DS_FIG4_SUMMARY")

    summary_rows, summary_ok = [], True
    for k, v in recomputed.items():
        c = chk_summary.get(k)
        if c is None:
            summary_rows.append([k, repr_full(v), "", "", "ABSENT_FROM_CHECKPOINT"])
            continue
        absdiff = abs(float(v) - c)
        rel = absdiff / abs(c) if c != 0 else absdiff
        ok = absdiff <= 1e-12 or rel <= 1e-12
        summary_ok &= bool(ok)
        summary_rows.append([k, repr_full(v), repr_full(c), repr_full(absdiff),
                             "MATCH" if ok else "DIFFERS"])
    write_tsv(DEST / "FIG4_panelA_summary_comparison.tsv",
              ["metric", "independent_value", "checkpoint_value",
               "absolute_difference", "status"], summary_rows)
    audit["panel_a_summary_all_match"] = bool(summary_ok)

    # --- densities on the declared grid -----------------------------------
    x = np.linspace(x_lower, x_upper, KDE_GRID_POINTS)
    # Gaussian KDE, bandwidth = case_sample_SD * n^(-1/5).
    u = (x[:, None] - z[None, :]) / kde_bw
    case_kde = np.exp(-0.5 * u * u).sum(axis=1) / (n * kde_bw * np.sqrt(2.0 * np.pi))
    # Control curve: normal approximation, mean 0 SD 1.
    control_normal = np.exp(-0.5 * x * x) / np.sqrt(2.0 * np.pi)
    # 18 equal-width bins over the observed case range.
    counts, edges = np.histogram(z, bins=HIST_BINS)
    width = edges[1] - edges[0]
    hist_density = counts / (n * width)

    dens_chk = pd.read_csv(ds("DS_FIG4_DENSITY"), sep="\t")
    audit["density_sha256"] = ds_sha("DS_FIG4_DENSITY")
    audit["density_rows"] = int(len(dens_chk))

    def compare(series: str, xs, ds_, extra=None) -> dict:
        sub = dens_chk[dens_chk["series"] == series].sort_values("order")
        res = {"series": series, "n_independent": len(xs), "n_checkpoint": len(sub)}
        if len(sub) != len(xs):
            res["status"] = "ROW_COUNT_DIFFERS"
            return res
        res["max_abs_x_diff"] = float(np.max(np.abs(sub["x"].to_numpy() - xs)))
        res["max_abs_density_diff"] = float(np.max(np.abs(sub["density"].to_numpy() - ds_)))
        if extra is not None:
            for k, v in extra.items():
                res[k] = float(np.max(np.abs(sub[k].to_numpy() - v)))
        return res

    cmp_kde = compare("case_kde", x, case_kde)
    cmp_ctl = compare("control_normal", x, control_normal)
    centers = (edges[:-1] + edges[1:]) / 2.0
    cmp_hist = compare("case_histogram", centers, hist_density,
                       {"x_left": edges[:-1], "x_right": edges[1:],
                        "count": counts.astype(float)})
    audit["density_comparison"] = [cmp_kde, cmp_ctl, cmp_hist]

    write_tsv(DEST / "FIG4_panelA_density_comparison.tsv",
              ["series", "n_independent", "n_checkpoint", "max_abs_x_diff",
               "max_abs_density_diff", "max_abs_x_left_diff",
               "max_abs_x_right_diff", "max_abs_count_diff"],
              [[c.get("series"), c.get("n_independent"), c.get("n_checkpoint"),
                repr_full(c.get("max_abs_x_diff")), repr_full(c.get("max_abs_density_diff")),
                repr_full(c.get("x_left")), repr_full(c.get("x_right")),
                repr_full(c.get("count"))] for c in (cmp_kde, cmp_ctl, cmp_hist)])

    # Aggregate density output (no individual scores).
    dens_rows = []
    for i, (xv, dv) in enumerate(zip(x, case_kde), start=1):
        dens_rows.append(["case_kde", i, repr_full(xv), "", "", repr_full(dv), "", n])
    for i, (xv, dv) in enumerate(zip(x, control_normal), start=1):
        dens_rows.append(["control_normal", i, repr_full(xv), "", "", repr_full(dv), "",
                          audit["control_n"]])
    for i in range(HIST_BINS):
        dens_rows.append(["case_histogram", i + 1, repr_full(centers[i]),
                          repr_full(edges[i]), repr_full(edges[i + 1]),
                          repr_full(hist_density[i]), int(counts[i]), n])
    write_tsv(DEST / "FIG4_panelA_density.tsv",
              ["series", "order", "x", "x_left", "x_right", "density", "count", "n"],
              dens_rows)

    # ------------------------------------------------------------------
    # Panel B - independent re-selection from the full threshold scan
    # ------------------------------------------------------------------
    scan = pd.read_csv(ds("DS_PRSICE_THRESHOLD_SCAN"))
    audit["scan_sha256"] = ds_sha("DS_PRSICE_THRESHOLD_SCAN")
    audit["scan_rows"] = int(len(scan))

    opt_idx = int(scan["R2"].idxmax())
    opt_scan = scan.loc[opt_idx]
    audit["optimized_threshold_rederived"] = float(opt_scan["Threshold"])
    audit["optimized_r2_rederived"] = float(opt_scan["R2"])
    audit["optimized_is_unique_max"] = int((scan["R2"] == scan["R2"].max()).sum()) == 1

    selected = []
    for t in FIXED_TARGETS:
        i = int((scan["Threshold"] - t).abs().idxmin())
        r = scan.loc[i]
        selected.append(("fixed", t, r))
    selected.append(("optimized", float(opt_scan["Threshold"]), opt_scan))
    # Display order follows ascending threshold, with the optimized row
    # interleaved by value but flagged as a distinct secondary analysis.
    selected.sort(key=lambda s: float(s[2]["Threshold"]))

    pb_chk = pd.read_csv(ds("DS_FIG4_PANEL_B"), sep="\t")
    audit["panel_b_sha256"] = ds_sha("DS_FIG4_PANEL_B")

    pb_rows, pb_ok = [], True
    tiers = []
    for order, (tier, target, r) in enumerate(selected, start=1):
        c = pb_chk[pb_chk["display_order"] == order].iloc[0]
        n_ok = int(r["Num_SNP"]) == int(c["n_snps"])
        r2_ok = abs(float(r["R2"]) - float(c["incremental_nagelkerke_r2_exact"])) <= 1e-12
        p_ok = abs(float(r["P"]) - float(c["covariate_adjusted_p_exact"])) <= 1e-12
        t_ok = abs(float(r["Threshold"]) - float(c["threshold_exact"])) <= 1e-12
        tier_ok = str(c["analysis_tier"]) == tier
        pb_ok &= bool(n_ok and r2_ok and p_ok and t_ok and tier_ok)
        tiers.append({"order": order, "tier": tier,
                      "threshold": float(r["Threshold"]),
                      "threshold_display": str(c["threshold_display"]),
                      "n_snps": int(r["Num_SNP"]), "r2": float(r["R2"]),
                      "p": float(r["P"]), "coefficient": float(r["Coefficient"]),
                      "se": float(r["SE"])})
        pb_rows.append([order, tier, str(c["analysis_tier"]), tier_ok,
                        repr_full(float(r["Threshold"])), repr_full(float(c["threshold_exact"])), t_ok,
                        int(r["Num_SNP"]), int(c["n_snps"]), n_ok,
                        repr_full(float(r["R2"])), repr_full(float(c["incremental_nagelkerke_r2_exact"])), r2_ok,
                        repr_full(float(r["P"])), repr_full(float(c["covariate_adjusted_p_exact"])), p_ok])
    write_tsv(DEST / "FIG4_panelB_comparison.tsv",
              ["display_order", "tier_independent", "tier_checkpoint", "tier_match",
               "threshold_independent", "threshold_checkpoint", "threshold_match",
               "n_snps_independent", "n_snps_checkpoint", "n_snps_match",
               "r2_independent", "r2_checkpoint", "r2_match",
               "p_independent", "p_checkpoint", "p_match"], pb_rows)
    audit["panel_b_all_match"] = bool(pb_ok)

    # ------------------------------------------------------------------
    # Figure 4
    # ------------------------------------------------------------------
    fig = plt.figure(figsize=(13.5, 5.4))
    gs = fig.add_gridspec(1, 2, width_ratios=[1.15, 1.0], wspace=0.24)

    ax = fig.add_subplot(gs[0, 0])
    ax.bar(centers, hist_density, width=width * 0.96, color="#9dc3e6",
           edgecolor="#5b8db8", linewidth=0.5, label=f"Case histogram ({HIST_BINS} bins)")
    ax.plot(x, case_kde, color="#c0392b", lw=1.8, label=f"Case KDE (n={n})")
    ax.plot(x, control_normal, color="#2c3e50", lw=1.5, ls="--",
            label=f"Control N(0,1) (n={audit['control_n']:,})")
    ax.axvline(0.0, color="#888888", lw=0.8)
    ax.axvline(z_mean, color="#c0392b", lw=0.9, ls=":")
    ax.text(z_mean, ax.get_ylim()[1] * 0.96, f"  case mean z = {z_mean:.3f}",
            color="#c0392b", fontsize=8, va="top")
    ax.set_xlim(x_lower, x_upper)
    ax.set_xlabel("PGS z-score (standardized to the control distribution)")
    ax.set_ylabel("Density")
    ax.set_title("A  PGS distribution", loc="left", fontweight="bold")
    ax.legend(fontsize=8, frameon=False, loc="upper left")
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)

    ax2 = fig.add_subplot(gs[0, 1])
    xs = np.arange(len(tiers))
    is_opt = [t["tier"] == "optimized" for t in tiers]
    bars = ax2.bar(xs, [t["r2"] * 100 for t in tiers],
                   color=["#e08a1e" if o else "#2c6fad" for o in is_opt],
                   edgecolor=["#a5610a" if o else "#1d4f7c" for o in is_opt],
                   hatch=["//" if o else "" for o in is_opt], linewidth=0.8)
    for xi, t, b in zip(xs, tiers, bars):
        ax2.text(xi, b.get_height() + 0.02, f"{t['n_snps']:,}\nP={t['p']:.2e}",
                 ha="center", va="bottom", fontsize=7)
    ax2.set_xticks(xs)
    ax2.set_xticklabels([t["threshold_display"] for t in tiers], fontsize=8, rotation=30,
                        ha="right")
    ax2.set_xlabel("P-value threshold  (hatched = optimized, secondary analysis)")
    ax2.set_ylabel("Incremental Nagelkerke $R^2$ (%)")
    ax2.set_title("B  Variance explained by threshold", loc="left", fontweight="bold")
    ax2.set_ylim(0, max(t["r2"] for t in tiers) * 100 * 1.28)
    for s in ("top", "right"):
        ax2.spines[s].set_visible(False)

    fig.savefig(DEST / "FIG4_pgs_validation.svg", bbox_inches="tight")
    fig.savefig(DEST / "FIG4_pgs_validation.pdf", bbox_inches="tight")
    fig.savefig(DEST / "FIG4_pgs_validation.png", dpi=200, bbox_inches="tight")
    plt.close(fig)

    # ------------------------------------------------------------------
    # Table 2 (2A fixed primary grid / 2B optimized secondary)
    # ------------------------------------------------------------------
    t2 = pd.read_csv(ds("DS_TABLE2_CHECKPOINT"), sep="\t", dtype=str)
    audit["table2_sha256"] = ds_sha("DS_TABLE2_CHECKPOINT")
    audit["table2_row_count"] = int(len(t2))
    t2opt = t2[t2["Panel"].astype(str).str.startswith("Optimized")].iloc[0]
    model = {
        "or_per_sd": str(t2opt["OR per SD"]),
        "ci_95": str(t2opt["95% CI"]),
        "pgs_only_auc": float(t2opt["PGS-only AUC"]),
        "covariates_only_auc": float(t2opt["Covariates-only AUC"]),
        "full_auc": float(t2opt["Full AUC"]),
        "lrt_p": float(t2opt["LRT P"]),
    }
    audit["table2_model_fields"] = model

    rows2 = []
    for t in tiers:
        is_o = t["tier"] == "optimized"
        panel = "2B Optimized threshold (secondary)" if is_o else "2A Fixed threshold (primary)"
        rows2.append([
            panel, t["threshold_display"], t["n_snps"],
            f"{t['r2']:.4f}", f"{t['p']:.2e}",
            model["or_per_sd"] if is_o else "", model["ci_95"] if is_o else "",
            f"{model['pgs_only_auc']:.3f}" if is_o else "",
            f"{model['covariates_only_auc']:.3f}" if is_o else "",
            f"{model['full_auc']:.3f}" if is_o else "",
            f"{model['lrt_p']:.2e}" if is_o else "",
            repr_full(t["threshold"]), repr_full(t["r2"]), repr_full(t["p"]),
        ])
    write_tsv(DEST / "TABLE2_reconstructed.tsv",
              ["Panel", "Threshold", "N SNPs", "Incremental Nagelkerke R2",
               "Covariate-adjusted P", "OR per SD", "95% CI", "PGS-only AUC",
               "Covariates-only AUC", "Full AUC", "LRT P",
               "threshold_full_precision", "r2_full_precision", "p_full_precision"],
              rows2)

    # Display-rounding check against the checkpoint's own rendered table.
    t2_fixed = t2[~t2["Panel"].astype(str).str.startswith("Optimized")]
    round_rows, round_ok = [], True
    for _, c in t2.iterrows():
        thr = str(c["Threshold"])
        # Match numerically, not on the display string: the checkpoint renders
        # the same threshold as '5e-2' where Panel B renders it '0.05', and the
        # PRSice grid value is offset from the nominal target (5.005e-05 vs 5e-5).
        thr_val = float(thr)
        mine = sorted(tiers, key=lambda t: abs(t["threshold"] - thr_val) / thr_val)
        if not mine or abs(mine[0]["threshold"] - thr_val) / thr_val > 1e-2:
            round_rows.append([thr, "", "", "NO_INDEPENDENT_MATCH"])
            round_ok = False
            continue
        t = mine[0]
        r2_disp_ok = f"{t['r2']:.4f}" == str(c["Incremental Nagelkerke R²"])
        p_disp_ok = f"{t['p']:.2e}" == str(c["Covariate-adjusted P"])
        n_ok = str(t["n_snps"]) == str(c["N SNPs"])
        round_ok &= bool(r2_disp_ok and p_disp_ok and n_ok)
        round_rows.append([thr, f"{t['r2']:.4f}|{c['Incremental Nagelkerke R²']}",
                           f"{t['p']:.2e}|{c['Covariate-adjusted P']}",
                           "MATCH" if (r2_disp_ok and p_disp_ok and n_ok) else "DIFFERS"])
    write_tsv(DEST / "TABLE2_display_rounding_check.tsv",
              ["threshold", "r2_independent|checkpoint", "p_independent|checkpoint", "status"],
              round_rows)
    audit["table2_display_rounding_all_match"] = bool(round_ok)
    audit["table2_fixed_rows"] = int(len(t2_fixed))

    (DEST / "FIG4_TABLE2_audit.json").write_text(
        json.dumps(audit, indent=2, default=str), encoding="utf-8")

    claims: dict[str, object] = {
        "FIG4_DENSITY_SOURCE_SHA256": audit["density_sha256"],
        "FIG4_PANEL_A_N": n,
        "FIG4_PANEL_A_Z_MEAN": z_mean,
        "FIG4_PANEL_A_Z_SD_DDOF1": z_sd1,
        "FIG4_PANEL_A_Z_MIN": z_min,
        "FIG4_PANEL_A_Z_MAX": z_max,
        "FIG4_PANEL_A_Z_LT_0": z_lt_0,
        "FIG4_PANEL_A_KDE_BANDWIDTH": kde_bw,
        "FIG4_PANEL_A_X_LOWER": x_lower,
        "FIG4_PANEL_A_X_UPPER": x_upper,
        "FIG4_PANEL_A_HISTOGRAM_BINS": HIST_BINS,
        "FIG4_PANEL_A_CONTROL_MEAN": control_mean,
        "FIG4_PANEL_A_CONTROL_SD": control_sd,
    }
    for t in tiers:
        claims[f"FIG4_TIER_{t['order']}_N_SNPS"] = t["n_snps"]
        claims[f"FIG4_TIER_{t['order']}_R2"] = t["r2"]
        claims[f"FIG4_TIER_{t['order']}_P"] = t["p"]
    claims.update({
        "TABLE2_ROW_COUNT": audit["table2_row_count"],
        "TABLE2_NORMALIZED_SHA256": audit["table2_sha256"],
        "TABLE2_PGS_ONLY_AUC": model["pgs_only_auc"],
        "TABLE2_COVARIATES_ONLY_AUC": model["covariates_only_auc"],
        "TABLE2_FULL_AUC": model["full_auc"],
        "TABLE2_LRT_P": model["lrt_p"],
    })
    (DEST / "FIG4_TABLE2_claims.json").write_text(
        json.dumps({k: repr_full(v) for k, v in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit, "tiers": tiers}


if __name__ == "__main__":
    r = main()
    for k, v in r["claims"].items():
        print(f"{k}\t{repr_full(v)}")
    a = r["audit"]
    print("\n-- verification --")
    for k in ("panel_a_summary_all_match", "panel_b_all_match",
              "table2_display_rounding_all_match", "optimized_threshold_rederived",
              "optimized_is_unique_max", "scan_rows", "density_rows"):
        print(f"{k}\t{a[k]}")
    print("density comparison:")
    for c in a["density_comparison"]:
        print("   ", c)
