"""M04 - Figure 3: cross-population directional reconstruction (L2).

M04 designates the required starting points as "the current primary/sensitivity
statistic checkpoints and the two figure-source tables", and describes the
genome-wide recalculation as optional ("For optional recalculation, ...").
This module therefore has two layers, kept strictly separate:

LAYER 1 (required, L2) - reconstruct both panels from DS_FIG3_PANEL_A /
    DS_FIG3_PANEL_B and independently re-derive every statistic those tables
    contain: Clopper-Pearson intervals and one-sided exact binomial tests from
    (n_concordant, K), the one-sided normal tail from Z, the alpha=0.05
    reference Z, the 0.0125 four-threshold benchmark, and cross-table
    consistency against DS_CROSSPOP_PRIMARY / DS_CROSSPOP_SENSITIVITY.
    The returned claim values come from this layer.

LAYER 2 (optional, supporting) - a genome-wide independent rerun from
    DS_CROSSPOP_MERGED_GENOMEWIDE and the EAS reference using this project's
    own LD clumping engine, to test whether the checkpoint values are
    recoverable from the underlying data. Differences are quantified and
    disclosed rather than absorbed.

Statistical contract (M04):
  * Japanese signed Z = beta / SE
  * European direction = sign(beta); magnitude from the two-sided P
  * clump by European P under the primary and four sensitivity settings
  * ties broken by chromosome, position, normalized variant key
  * weighted directional Z: sign +1 concordant / -1 discordant,
    weight = -log10(European P clipped at 1e-300),
    Z = sum(sign*weight)/sqrt(sum(weight^2)), positive standard-normal tail
"""
from __future__ import annotations

import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy import stats

from common import OUT, ds, ds_sha, normalized_variant_key, repr_full, write_tsv
from ld_clump import GenotypeSource, clump, load_pvar

DEST = OUT / "FIG3"

SETTINGS = [
    ("PRIMARY_R010_KB500", 0.10, 500),
    ("SENS_R2_005_KB500", 0.05, 500),
    ("SENS_R2_020_KB500", 0.20, 500),
    ("SENS_R2_010_KB250", 0.10, 250),
    ("SENS_R2_010_KB1000", 0.10, 1000),
]
K_VALUES = [100, 500, 1000, 2000]
FOUR_THRESHOLD_BENCHMARK = 0.0125
P_CLIP = 1e-300
RUN_OPTIONAL_RERUN = True


def clopper_pearson(k: int, n: int, alpha: float = 0.05) -> tuple[float, float]:
    lo = 0.0 if k == 0 else float(stats.beta.ppf(alpha / 2, k, n - k + 1))
    hi = 1.0 if k == n else float(stats.beta.isf(alpha / 2, k + 1, n - k))
    return lo, hi


def build_merged() -> pd.DataFrame:
    m = pd.read_csv(ds("DS_CROSSPOP_MERGED_GENOMEWIDE"), sep="\t")
    pv = load_pvar()
    m["key"] = m["CHR"].astype(str) + ":" + m["BP"].astype(str)
    m = m.merge(pv[["key", "ref_index"]], on="key", how="left")
    m["variant_key"] = [
        normalized_variant_key(c, b, a1, a2)
        for c, b, a1, a2 in zip(m["CHR"], m["BP"], m["A1"], m["A2"])
    ]
    m = m.rename(columns={"CHR": "chrom_int", "BP": "pos"})
    m["japanese_signed_z"] = m["IQ_BETA"] / m["IQ_SE"]
    m["european_sign"] = np.sign(m["European_BETA"])
    m["european_signed_z"] = m["european_sign"] * stats.norm.isf(
        m["European_P"].to_numpy() / 2.0)
    m["concordant"] = np.sign(m["japanese_signed_z"]) == m["european_sign"]
    return m


def directional_z(sub: pd.DataFrame) -> tuple[float, float]:
    w = -np.log10(np.clip(sub["European_P"].to_numpy(dtype=float), P_CLIP, None))
    s = np.where(sub["concordant"].to_numpy(), 1.0, -1.0)
    z = float(np.sum(s * w) / np.sqrt(np.sum(w * w)))
    return z, float(stats.norm.sf(z))


# ----------------------------------------------------------------------
# Layer 1 - required checkpoint reconstruction with independent re-derivation
# ----------------------------------------------------------------------
def layer1() -> dict:
    pa = pd.read_csv(ds("DS_FIG3_PANEL_A"), sep="\t")
    pb = pd.read_csv(ds("DS_FIG3_PANEL_B"), sep="\t")
    cp = pd.read_csv(ds("DS_CROSSPOP_PRIMARY"), sep="\t")
    cs = pd.read_csv(ds("DS_CROSSPOP_SENSITIVITY"), sep="\t")
    allc = pd.concat([cp, cs], ignore_index=True)

    panel_a, a_rows = [], []
    for _, r in pa.iterrows():
        K, nc = int(r["K"]), int(r["n_concordant"])
        prop = nc / K
        lo, hi = clopper_pearson(nc, K)
        p_one = float(stats.binomtest(nc, K, 0.5, alternative="greater").pvalue)
        panel_a.append({
            "K": K, "n_concordant": nc, "concordance": float(r["concordance"]),
            "concordance_recomputed": prop,
            "ci_lo": float(r["clopper_pearson_ci_lo"]), "ci_lo_recomputed": lo,
            "ci_hi": float(r["clopper_pearson_ci_hi"]), "ci_hi_recomputed": hi,
            "binomial_p": float(r["one_sided_binomial_P_vs_0.5"]),
            "binomial_p_recomputed": p_one,
            "passes_benchmark": p_one < FOUR_THRESHOLD_BENCHMARK,
        })
        a_rows.append([
            K, nc, repr_full(float(r["concordance"])), repr_full(prop),
            repr_full(abs(prop - float(r["concordance"]))),
            repr_full(float(r["clopper_pearson_ci_lo"])), repr_full(lo),
            repr_full(abs(lo - float(r["clopper_pearson_ci_lo"]))),
            repr_full(float(r["clopper_pearson_ci_hi"])), repr_full(hi),
            repr_full(abs(hi - float(r["clopper_pearson_ci_hi"]))),
            repr_full(float(r["one_sided_binomial_P_vs_0.5"])), repr_full(p_one),
            repr_full(abs(p_one - float(r["one_sided_binomial_P_vs_0.5"]))
                      / float(r["one_sided_binomial_P_vs_0.5"])),
            p_one < FOUR_THRESHOLD_BENCHMARK,
        ])
    write_tsv(DEST / "FIG3_panelA_independent_rederivation.tsv",
              ["K", "n_concordant", "concordance_checkpoint", "concordance_recomputed",
               "concordance_abs_diff", "cp_ci_lo_checkpoint", "cp_ci_lo_recomputed",
               "cp_ci_lo_abs_diff", "cp_ci_hi_checkpoint", "cp_ci_hi_recomputed",
               "cp_ci_hi_abs_diff", "binomial_P_checkpoint", "binomial_P_recomputed",
               "binomial_P_relative_diff", "passes_0.0125_benchmark"], a_rows)

    panel_b, b_rows = [], []
    ref_z = float(stats.norm.isf(0.05))
    for _, r in pb.iterrows():
        z = float(r["directional_stouffer_Z"])
        p_rec = float(stats.norm.sf(z))
        panel_b.append({
            "setting": str(r["setting"]), "n_leads": int(r["n_leads"]),
            "z": z, "one_sided_p": float(r["one_sided_P"]),
            "one_sided_p_recomputed": p_rec,
            "alpha_005_reference_z": float(r["alpha_0.05_reference_Z"]),
            "exceeds_alpha_005": z > float(r["alpha_0.05_reference_Z"]),
        })
        b_rows.append([
            str(r["setting"]), int(r["n_leads"]), repr_full(z),
            repr_full(float(r["one_sided_P"])), repr_full(p_rec),
            repr_full(abs(p_rec - float(r["one_sided_P"])) / float(r["one_sided_P"])),
            repr_full(float(r["alpha_0.05_reference_Z"])), repr_full(ref_z),
            repr_full(abs(ref_z - float(r["alpha_0.05_reference_Z"]))),
            z > float(r["alpha_0.05_reference_Z"]),
        ])
    write_tsv(DEST / "FIG3_panelB_independent_rederivation.tsv",
              ["setting", "n_leads", "directional_Z", "one_sided_P_checkpoint",
               "one_sided_P_recomputed", "one_sided_P_relative_diff",
               "alpha_0.05_reference_Z_checkpoint", "alpha_0.05_reference_Z_recomputed",
               "alpha_reference_Z_abs_diff", "exceeds_alpha_0.05"], b_rows)

    # Cross-table consistency: the figure sources must agree with the
    # crosspop statistic tables to their respective stored precision.
    cross_rows, cross_ok = [], True
    for r in panel_b:
        s = r["setting"]
        z_full = float(allc[(allc.setting == s)
                            & (allc.metric == "directional_stouffer_Z")]["value"].iloc[0])
        p_full = float(allc[(allc.setting == s)
                            & (allc.metric == "directional_stouffer_P_one_sided")]["value"].iloc[0])
        n_full = int(allc[allc.setting == s]["n_leads"].iloc[0])
        z_ok = abs(z_full - r["z"]) <= 5e-7
        p_ok = abs(p_full - r["one_sided_p"]) / p_full <= 5e-6
        n_ok = n_full == r["n_leads"]
        cross_ok &= bool(z_ok and p_ok and n_ok)
        cross_rows.append([s, repr_full(z_full), repr_full(r["z"]), z_ok,
                           repr_full(p_full), repr_full(r["one_sided_p"]), p_ok,
                           n_full, r["n_leads"], n_ok])
    for r in panel_a:
        K = r["K"]
        row = cp[cp.metric == f"concordance_K{K}"].iloc[0]
        # Concordance rows leave p_two_sided empty and carry the Clopper-Pearson
        # bounds in boot95_lo / boot95_hi, K in n, and n_concordant in notes.
        v_ok = abs(float(row["value"]) - r["concordance"]) <= 5e-7
        lo_ok = abs(float(row["boot95_lo"]) - r["ci_lo"]) <= 5e-6
        hi_ok = abs(float(row["boot95_hi"]) - r["ci_hi"]) <= 5e-6
        k_ok = int(row["n"]) == K
        nc_ok = f"n_concordant={r['n_concordant']}" in str(row["notes"])
        cross_ok &= bool(v_ok and lo_ok and hi_ok and k_ok and nc_ok)
        cross_rows.append([f"concordance_K{K}", repr_full(float(row["value"])),
                           repr_full(r["concordance"]), v_ok,
                           repr_full(float(row["boot95_lo"])), repr_full(r["ci_lo"]),
                           lo_ok and hi_ok, int(row["n"]), K, k_ok and nc_ok])
    write_tsv(DEST / "FIG3_crosstable_consistency.tsv",
              ["item", "crosspop_value", "figure_source_value", "value_match",
               "crosspop_secondary", "figure_source_secondary", "secondary_match",
               "crosspop_count", "figure_source_count", "count_match"], cross_rows)

    return {"panel_a": panel_a, "panel_b": panel_b,
            "cross_table_consistent": bool(cross_ok)}


# ----------------------------------------------------------------------
# Layer 2 - optional independent genome-wide rerun
# ----------------------------------------------------------------------
def layer2(panel_a: list, panel_b: list) -> dict:
    m = build_merged()
    gs = GenotypeSource()
    usable = (m["European_P"].notna() & np.isfinite(m["European_P"])
              & m["ref_index"].notna())
    sub = m.loc[usable].copy()
    sub["ref_index"] = sub["ref_index"].astype(np.int64)

    vc = sub["European_P"].value_counts()
    out: dict[str, object] = {
        "merged_rows": int(len(m)),
        "rows_with_european_p": int((m["European_P"].notna()
                                     & np.isfinite(m["European_P"])).sum()),
        "rows_in_eas_reference": int(m["ref_index"].notna().sum()),
        "rows_usable_for_clumping": int(len(sub)),
        "rows_with_european_p_absent_from_reference": int(
            (m["European_P"].notna() & np.isfinite(m["European_P"])
             & m["ref_index"].isna()).sum()),
        "distinct_european_p_values": int(len(vc)),
        "rows_in_tied_european_p_groups": int(vc[vc > 1].sum()),
        "largest_european_p_tie_group": int(vc.max()),
    }

    chk_b = {r["setting"]: r for r in panel_b}
    rows, leads_primary = [], None
    for name, r2, kb in SETTINGS:
        res = clump(sub, r2_threshold=r2, window_kb=kb,
                    p_col="European_P", genotypes=gs)
        keys = {ld_["variant_key"] for ld_ in res["leads"]}
        leads_df = sub[sub["variant_key"].isin(keys)]
        if name == "PRIMARY_R010_KB500":
            leads_primary = leads_df
        z, p_one = directional_z(leads_df)
        c = chk_b[name]
        rows.append([
            name, r2, kb, res["n_leads"], c["n_leads"],
            res["n_leads"] - c["n_leads"],
            repr_full(abs(res["n_leads"] - c["n_leads"]) / c["n_leads"]),
            repr_full(z), repr_full(c["z"]), repr_full(abs(z - c["z"])),
            repr_full(abs(z - c["z"]) / abs(c["z"])),
            repr_full(p_one), repr_full(c["one_sided_p"]),
            z > c["alpha_005_reference_z"], c["exceeds_alpha_005"],
        ])
    write_tsv(DEST / "FIG3_optional_rerun_panelB.tsv",
              ["setting", "r2_threshold", "window_kb", "n_leads_independent",
               "n_leads_checkpoint", "n_leads_difference", "n_leads_relative_difference",
               "Z_independent", "Z_checkpoint", "Z_abs_difference", "Z_relative_difference",
               "one_sided_P_independent", "one_sided_P_checkpoint",
               "independent_exceeds_alpha_0.05", "checkpoint_exceeds_alpha_0.05"], rows)
    out["panel_b_rerun"] = rows

    # Top-K under every ordering reading M04 leaves open.
    chk_a = {r["K"]: r for r in panel_a}
    variants = {
        "clumpEUR_rankJP": (leads_primary, "IQ_P"),
        "clumpEUR_rankEUR": (leads_primary, "European_P"),
    }
    res_jp = clump(sub, r2_threshold=0.10, window_kb=500, p_col="IQ_P", genotypes=gs)
    keys_jp = {ld_["variant_key"] for ld_ in res_jp["leads"]}
    leads_jp = sub[sub["variant_key"].isin(keys_jp)]
    variants["clumpJP_rankJP"] = (leads_jp, "IQ_P")
    variants["clumpJP_rankEUR"] = (leads_jp, "European_P")
    variants["noclump_rankJP"] = (sub, "IQ_P")
    variants["noclump_rankEUR"] = (sub, "European_P")
    out["n_leads_clumped_by_japanese_p"] = int(res_jp["n_leads"])

    trows, best, best_dev, best_cnt = [], None, None, None
    for label, (df, col) in variants.items():
        ordered = df.sort_values([col, "chrom_int", "pos", "variant_key"],
                                 kind="mergesort")
        devs, cells = [], []
        for K in K_VALUES:
            nc = int(ordered.head(K)["concordant"].sum())
            c = chk_a[K]
            devs.append(abs(nc / K - c["concordance"]))
            cells.append((K, nc, c["n_concordant"]))
        md = float(np.max(devs))
        mc = int(max(abs(nc - cn) for _, nc, cn in cells))
        trows.append([label] + [f"{nc} vs {cn}" for _, nc, cn in cells]
                     + [repr_full(md), mc, all(nc == cn for _, nc, cn in cells)])
        if best_dev is None or md < best_dev:
            best_dev, best, best_cnt = md, label, mc
    write_tsv(DEST / "FIG3_optional_rerun_topK_orderings.tsv",
              ["ordering"] + [f"K={k}_independent_vs_checkpoint" for k in K_VALUES]
              + ["max_abs_concordance_deviation", "max_abs_count_difference",
                 "exact_match_all_K"], trows)
    out["topk_orderings"] = trows
    out["topk_closest_ordering"] = best
    out["topk_closest_max_abs_concordance_deviation"] = best_dev
    out["topk_closest_max_abs_count_difference"] = best_cnt
    out["topk_any_ordering_reproduces_checkpoint"] = any(r[-1] for r in trows)
    return out


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    audit: dict[str, object] = {
        "panel_a_sha256": ds_sha("DS_FIG3_PANEL_A"),
        "panel_b_sha256": ds_sha("DS_FIG3_PANEL_B"),
        "merged_sha256": ds_sha("DS_CROSSPOP_MERGED_GENOMEWIDE"),
    }
    l1 = layer1()
    audit["layer1"] = l1
    panel_a, panel_b = l1["panel_a"], l1["panel_b"]

    audit["panel_a_concordance_rederivation_max_abs_diff"] = max(
        abs(r["concordance_recomputed"] - r["concordance"]) for r in panel_a)
    audit["panel_a_ci_rederivation_max_abs_diff"] = max(
        max(abs(r["ci_lo_recomputed"] - r["ci_lo"]),
            abs(r["ci_hi_recomputed"] - r["ci_hi"])) for r in panel_a)
    audit["panel_a_binomial_rederivation_max_rel_diff"] = max(
        abs(r["binomial_p_recomputed"] - r["binomial_p"]) / r["binomial_p"]
        for r in panel_a)
    audit["panel_b_p_rederivation_max_rel_diff"] = max(
        abs(r["one_sided_p_recomputed"] - r["one_sided_p"]) / r["one_sided_p"]
        for r in panel_b)
    audit["all_settings_exceed_alpha_005"] = all(r["exceeds_alpha_005"] for r in panel_b)
    audit["n_K_passing_benchmark"] = sum(r["passes_benchmark"] for r in panel_a)

    if RUN_OPTIONAL_RERUN:
        audit["layer2_optional_rerun"] = layer2(panel_a, panel_b)

    # ---------------- figure ----------------
    fig = plt.figure(figsize=(13.5, 5.2))
    gs_ = fig.add_gridspec(1, 2, width_ratios=[1.0, 1.05], wspace=0.26)

    ax = fig.add_subplot(gs_[0, 0])
    xs = np.arange(len(panel_a))
    props = [r["concordance"] for r in panel_a]
    los = [r["concordance"] - r["ci_lo"] for r in panel_a]
    his = [r["ci_hi"] - r["concordance"] for r in panel_a]
    ax.errorbar(xs, props, yerr=[los, his], fmt="o", color="#2c6fad",
                capsize=5, lw=1.4, markersize=7)
    ax.axhline(0.5, color="#c0392b", ls="--", lw=1.0, label="null 0.5")
    for xi, r in zip(xs, panel_a):
        mark = "*" if r["passes_benchmark"] else "n.s."
        ax.text(xi, r["ci_hi"] + 0.006,
                f"{r['n_concordant']}/{r['K']}\nP={r['binomial_p']:.2e} {mark}",
                ha="center", va="bottom", fontsize=7)
    ax.set_xticks(xs)
    ax.set_xticklabels([f"K={r['K']}" for r in panel_a])
    ax.set_ylabel("Directional concordance")
    ax.set_xlabel("Top-K independent lead variants (95% Clopper-Pearson CI)")
    ax.set_title("A  Top-K directional concordance", loc="left", fontweight="bold")
    ax.set_ylim(0.44, max(r["ci_hi"] for r in panel_a) + 0.075)
    ax.legend(fontsize=8, frameon=False, loc="lower right")
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)

    ax2 = fig.add_subplot(gs_[0, 1])
    ys = np.arange(len(panel_b))[::-1]
    zs = [r["z"] for r in panel_b]
    cols = ["#e08a1e" if r["setting"].startswith("PRIMARY") else "#2c6fad"
            for r in panel_b]
    ax2.barh(ys, zs, color=cols, edgecolor="none", height=0.62)
    ax2.axvline(panel_b[0]["alpha_005_reference_z"], color="#c0392b", ls="--", lw=1.0,
                label=f"one-sided $\\alpha$=0.05 (Z={panel_b[0]['alpha_005_reference_z']:.4f})")
    for y, r in zip(ys, panel_b):
        ax2.text(r["z"] + 0.06, y,
                 f"Z={r['z']:.3f}  P={r['one_sided_p']:.2e}  ({r['n_leads']:,} leads)",
                 va="center", fontsize=7.5)
    ax2.set_yticks(ys)
    ax2.set_yticklabels([r["setting"] for r in panel_b], fontsize=8)
    ax2.set_xlabel("Weighted directional Z  (orange = primary setting)")
    ax2.set_title("B  Weighted directional statistic by LD setting",
                  loc="left", fontweight="bold")
    ax2.set_xlim(0, max(zs) * 1.62)
    ax2.legend(fontsize=8, frameon=False, loc="lower right")
    for s in ("top", "right"):
        ax2.spines[s].set_visible(False)

    fig.savefig(DEST / "FIG3_cross_population.svg", bbox_inches="tight")
    fig.savefig(DEST / "FIG3_cross_population.pdf", bbox_inches="tight")
    fig.savefig(DEST / "FIG3_cross_population.png", dpi=200, bbox_inches="tight")
    plt.close(fig)

    write_tsv(DEST / "FIG3_panelA_source.tsv",
              ["K", "n_concordant", "concordance", "clopper_pearson_ci_lo",
               "clopper_pearson_ci_hi", "one_sided_binomial_P_vs_0.5",
               "passes_0.0125_benchmark"],
              [[r["K"], r["n_concordant"], repr_full(r["concordance"]),
                repr_full(r["ci_lo"]), repr_full(r["ci_hi"]),
                repr_full(r["binomial_p"]), r["passes_benchmark"]] for r in panel_a])
    write_tsv(DEST / "FIG3_panelB_source.tsv",
              ["setting", "n_leads", "directional_Z", "one_sided_P",
               "alpha_0.05_reference_Z", "exceeds_alpha_0.05"],
              [[r["setting"], r["n_leads"], repr_full(r["z"]),
                repr_full(r["one_sided_p"]), repr_full(r["alpha_005_reference_z"]),
                r["exceeds_alpha_005"]] for r in panel_b])

    (DEST / "FIG3_audit.json").write_text(json.dumps(audit, indent=2, default=str),
                                          encoding="utf-8")

    claims: dict[str, object] = {
        "FIG3_PANEL_A_SOURCE_SHA256": audit["panel_a_sha256"],
        "FIG3_PANEL_B_SOURCE_SHA256": audit["panel_b_sha256"],
    }
    # The claim matrix asks for full precision. The checkpoints store these
    # tail probabilities at 4-7 significant digits, so the independently
    # recomputed values are returned rather than the stored rounded ones; both
    # agree to the checkpoints' own stored precision (see the re-derivation
    # tables). Counts and concordances are exact in the checkpoints and are
    # returned as stored.
    for r in panel_a:
        claims[f"FIG3_K{r['K']}_N_CONCORDANT"] = r["n_concordant"]
        claims[f"FIG3_K{r['K']}_CONCORDANCE"] = r["concordance"]
        claims[f"FIG3_K{r['K']}_BINOMIAL_P"] = r["binomial_p_recomputed"]
    for r in panel_b:
        claims[f"FIG3_{r['setting']}_N_LEADS"] = r["n_leads"]
        claims[f"FIG3_{r['setting']}_Z"] = r["z"]
        claims[f"FIG3_{r['setting']}_P"] = r["one_sided_p_recomputed"]
    (DEST / "FIG3_claims.json").write_text(
        json.dumps({k: repr_full(v) for k, v in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit}


if __name__ == "__main__":
    r = main()
    a = r["audit"]
    print("== LAYER 1 (required): independent re-derivation of checkpoint statistics ==")
    print(f"  concordance max abs diff      : {a['panel_a_concordance_rederivation_max_abs_diff']:.3e}")
    print(f"  Clopper-Pearson max abs diff  : {a['panel_a_ci_rederivation_max_abs_diff']:.3e}")
    print(f"  binomial P max rel diff       : {a['panel_a_binomial_rederivation_max_rel_diff']:.3e}")
    print(f"  panel B one-sided P max rel   : {a['panel_b_p_rederivation_max_rel_diff']:.3e}")
    print(f"  cross-table consistent        : {a['layer1']['cross_table_consistent']}")
    print(f"  K passing 0.0125 benchmark    : {a['n_K_passing_benchmark']}/4")
    print(f"  all settings exceed a=0.05    : {a['all_settings_exceed_alpha_005']}")
    if "layer2_optional_rerun" in a:
        l2 = a["layer2_optional_rerun"]
        print("\n== LAYER 2 (optional): genome-wide independent rerun ==")
        print(f"  usable variants {l2['rows_usable_for_clumping']}, distinct European P "
              f"{l2['distinct_european_p_values']}, rows in tied groups "
              f"{l2['rows_in_tied_european_p_groups']}")
        for row in l2["panel_b_rerun"]:
            print(f"  {row[0]:20s} leads {row[3]:>7d} vs {row[4]:>7d} ({row[5]:+d}, rel {float(row[6]):.2e})"
                  f"  Z {float(row[7]):.5f} vs {float(row[8]):.5f} (rel {float(row[10]):.2e})")
        print(f"  Top-K closest ordering: {l2['topk_closest_ordering']} "
              f"(max abs concordance deviation {l2['topk_closest_max_abs_concordance_deviation']:.4f})")
        print(f"  any ordering reproduces checkpoint exactly: "
              f"{l2['topk_any_ordering_reproduces_checkpoint']}")
