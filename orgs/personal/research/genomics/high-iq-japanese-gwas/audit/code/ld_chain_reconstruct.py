#!/usr/bin/env python3
"""M02/M03/M04/M07 — TABLE1, FIG2, SuppTable S1 and FIG3 reconstruction.

Level: L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT for all four.

A pure transcription of checkpoint numbers verifies nothing. What an L2 audit
can legitimately establish is that every DERIVED quantity in a checkpoint
follows from the primitives sitting next to it. So each derived field is
recomputed here from the raw counts and frequencies and compared against the
value the checkpoint carries:

  Figure 3 panel A   concordance, Clopper-Pearson interval and the one-sided
                     exact binomial P, all from (n_concordant, K)
  Figure 3 panel B   the one-sided P from Z, and the alpha reference Z
  Figure 2           the enrichment category from the EAS/EUR ratio, using the
                     M03 thresholds
  SuppTable S1       the enrichment category, the EAS/EUR fold difference, the
                     odds ratio from beta, and the case/control MAF ratio
  Table 1            the row selection and ordering, re-ranked from
                     DS_GWAS_CURRENT rather than taken from the checkpoint

Cross-artifact consistency is checked too: the LD clump sizes must sum to the
SuppTable S1 row count, the Figure 2 lead set must equal the SuppTable S1 lead
set, and the category counts must agree across both.

Package B was not accessed.
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

ROOT = pathlib.Path(__file__).resolve().parents[2]
PKG = ROOT / "reconstruction-package-a-v1.1"
PAY = PKG / "04_DATA_PAYLOAD"
OUT = ROOT / "audit/return/KAWASAKI_PHASE5C_INDEPENDENT_RETURN"

# M03 category thresholds.
EAS_ENRICHED_ABOVE = 2.0
EUR_ENRICHED_BELOW = 0.5
SERIES = ["Case_MAF", "Control_MAF", "ToMMo_38KJPN_MAF", "gnomAD_EAS_MAF", "gnomAD_EUR_MAF"]

checks = []          # (check_id, expected, observed, ok)
claims = []          # (claim_id, artifact_id, value, source_dataset_ids, notes)


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for c in iter(lambda: fh.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def check(cid, expected, observed, tol=None):
    """Record a verification. tol=None means exact; else relative tolerance."""
    if tol is None:
        ok = expected == observed
    else:
        e, o = float(expected), float(observed)
        ok = abs(e - o) <= tol * max(abs(e), abs(o), 1e-300)
    checks.append((cid, repr(expected), repr(observed), "PASS" if ok else "FAIL"))
    return ok


def claim(cid, art, val, src, note=""):
    claims.append((cid, art, val, "L2_RECONSTRUCTED", src, note))


def clopper_pearson(k, n, conf=0.95):
    a = 1 - conf
    lo = 0.0 if k == 0 else stats.beta.ppf(a / 2, k, n - k + 1)
    hi = 1.0 if k == n else stats.beta.ppf(1 - a / 2, k + 1, n - k)
    return float(lo), float(hi)


def half(x):
    """Half-ULP of a display-rounded decimal string: '0.0162' -> 5e-5."""
    t = str(x)
    d = len(t.split(".")[1]) if "." in t else 0
    return 0.5 * 10 ** (-d)


def parse_freq(v):
    """Return (value, kind). The frequency columns are not purely numeric: the
    package censors small European frequencies as '<0.0001' and the derived fold
    difference as '>100'. M03 defines behaviour for numeric, zero and missing
    values but says nothing about censoring - see FINDING F-02. Censored entries
    are carried as bounds rather than silently coerced to a point value."""
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return None, "missing"
    s = str(v).strip()
    if s in ("", "NA", "nan", "None"):
        return None, "missing"
    if s.startswith("<"):
        return float(s[1:]), "upper_bound"
    if s.startswith(">"):
        return float(s[1:]), "lower_bound"
    try:
        return float(s), "exact"
    except ValueError:
        return None, "missing"


def classify(eas_raw, eur_raw):
    """M03: >2 EAS enriched, <0.5 EUR enriched, inclusive 0.5-2 similar.
    EUR==0 with EAS>0 is EAS enriched; missing stays unclassified.

    Extension for censored EUR (declared in the deviation log, F-02): if EUR is
    reported as '<b' the true ratio exceeds eas/b, so the class is determinable
    whenever that lower bound already clears the threshold. Otherwise the row is
    left unclassified rather than guessed."""
    eas, eas_kind = parse_freq(eas_raw)
    eur, eur_kind = parse_freq(eur_raw)
    if eas is None or eur is None:
        return "unclassified"
    if eur == 0:
        return "East Asian enriched" if eas > 0 else "unclassified"
    if eur_kind == "upper_bound":
        # true EUR < eur, hence true ratio > eas/eur
        return "East Asian enriched" if eas / eur > EAS_ENRICHED_ABOVE else "unclassified"
    if eas_kind != "exact" or eur_kind != "exact":
        return "unclassified"
    r = eas / eur
    if r > EAS_ENRICHED_ABOVE:
        return "East Asian enriched"
    if r < EUR_ENRICHED_BELOW:
        return "European enriched"
    return "similar frequency"


# ============================================================ TABLE 1  (M02)
def table1():
    f = PAY / "02_TABLE1_MAC_AF_SOURCES/table1_source.tsv"
    t1 = pd.read_csv(f, sep="\t")

    # Re-rank independently from DS_GWAS_CURRENT rather than trusting the
    # checkpoint's own order. M02 tie-break: chromosome, position, variant key.
    g = pd.read_csv(PAY / "01_JAPANESE_GWAS_SUMMARY/japanese_gwas_summary_privacy_filtered.tsv.gz",
                    sep="\t")
    g["rs"] = g["variant_id"].str.extract(r"(rs\d+)", expand=False)
    g = g.sort_values(["p_value", "chromosome", "position_grch37", "variant_id"])
    independent_top = g["rs"].head(len(t1)).tolist()
    checkpoint_order = t1["SNP"].tolist()

    check("TABLE1_ORDER_MATCHES_INDEPENDENT_RANKING", checkpoint_order, independent_top)
    check("TABLE1_TOP_VARIANT", independent_top[0], checkpoint_order[0])

    claim("TABLE1_ROW_COUNT", "TABLE1", str(len(t1)), "DS_TABLE1_CHECKPOINT",
          "prespec band A; re-ranked independently from DS_GWAS_CURRENT")
    claim("TABLE1_NORMALIZED_SHA256", "TABLE1", sha256(f), "DS_TABLE1_CHECKPOINT",
          "prespec band A; sha256 of the delivered file as-is (no normalisation applied)")
    claim("TABLE1_TOP_VARIANT_ID", "TABLE1", str(checkpoint_order[0]),
          "DS_GWAS_CURRENT;DS_TABLE1_CHECKPOINT", "prespec band A")
    return t1


# ============================================================ FIGURE 2 (M03)
def figure2():
    f = PAY / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/figure2_source.tsv"
    f2 = pd.read_csv(f, sep="\t")
    ldp = pd.read_csv(PAY / "03_LD_LOCI_AND_POPULATION_FREQUENCIES/ld_locus_definition_primary.tsv",
                      sep="\t")

    # Recompute each row's category from the EAS/EUR ratio.
    recomputed = [classify(r.gnomAD_EAS_MAF, r.gnomAD_EUR_MAF) for r in f2.itertuples()]
    stored = f2["Population_Enrichment"].tolist()
    # M03 writes the labels lower-case ("similar frequency"); the checkpoint
    # capitalises them. Compare on meaning, and record the casing separately.
    check("FIG2_CATEGORY_RECOMPUTED_FROM_RATIO",
          [x.lower() for x in stored], [x.lower() for x in recomputed])
    check("FIG2_CATEGORY_LABEL_CASING_MATCHES_M03", stored, recomputed)

    cats = f2["Population_Enrichment"].value_counts().to_dict()
    check("FIG2_LEAD_SET_EQUALS_LD_PRIMARY",
          sorted(ldp["lead_reference_id"]), sorted(f2["Variant"]))
    check("FIG2_PLOT_ORDER_IS_1_TO_N",
          list(range(1, len(f2) + 1)), sorted(f2["plot_order"].tolist()))

    claim("FIG2_LEAD_ROW_COUNT", "FIG2", str(len(f2)),
          "DS_FIG2_FREQUENCY_CHECKPOINT;DS_LD_PRIMARY",
          "prespec band A; equals the DS_LD_PRIMARY lead set")
    claim("FIG2_CATEGORY_COUNTS", "FIG2", json.dumps(cats, sort_keys=True),
          "DS_FIG2_FREQUENCY_CHECKPOINT",
          "prespec band A; every label independently recomputed from the EAS/EUR ratio")
    claim("FIG2_SOURCE_SHA256", "FIG2", sha256(f), "DS_FIG2_FREQUENCY_CHECKPOINT",
          "prespec band A")
    claim("FIG2_SERIES_COUNT", "FIG2", str(len(SERIES)), "DS_FIG2_FREQUENCY_CHECKPOINT",
          "prespec band A; " + ", ".join(SERIES))

    # ---- render
    # (match key, display label). The match key must be lower-case because the
    # checkpoint and M03 disagree on capitalisation.
    order = [("east asian enriched", "East Asian enriched"),
             ("similar frequency", "Similar frequency"),
             ("european enriched", "European enriched")]
    d = f2.sort_values("plot_order").reset_index(drop=True)
    x = np.arange(len(d))
    colors = ["#2f4b7c", "#6b8fc4", "#1c6f53", "#c9963f", "#b3402f"]
    marks = ["o", "s", "^", "D", "v"]

    fig, ax = plt.subplots(figsize=(13.5, 5.4))
    for s, c, m in zip(SERIES, colors, marks):
        # Censored entries ('<0.0001') are plotted at their bound and flagged,
        # never silently coerced to a point estimate.
        parsed = [parse_freq(v) for v in d[s]]
        yv = [p[0] for p in parsed]
        ax.plot(x, yv, marker=m, ms=5, lw=1.0, color=c, label=s.replace("_", " "))
        for xi, (val, kind) in enumerate(parsed):
            if kind in ("upper_bound", "lower_bound"):
                ax.annotate("<" if kind == "upper_bound" else ">", (xi, val),
                            textcoords="offset points", xytext=(-9, -3),
                            fontsize=9, color=c, fontweight="bold")
    ax.set_yscale("log")
    ax.set_xticks(x)
    ax.set_xticklabels(d["Gene"].fillna(d["Variant"]), rotation=55, ha="right", fontsize=8.5)
    ax.set_ylabel("Effect-allele frequency (log scale)")
    ax.set_xlabel("East Asian LD-defined lead locus")
    # Match on lower-case: the checkpoint capitalises these labels while M03
    # writes them lower-case, and grouping on the M03 spelling silently drops
    # the middle category (and mis-places the remaining ones).
    cat_lower = d["Population_Enrichment"].str.lower()
    ymin, ymax = ax.get_ylim()
    ax.set_ylim(ymin, ymax * 3.2)          # headroom for the group labels
    bound = 0
    for key, label in order:
        n = int((cat_lower == key).sum())
        if not n:
            continue
        if bound:
            ax.axvline(bound - 0.5, color="#98a4b3", lw=0.9, ls=":")
        ax.text(bound + n / 2 - 0.5, ymax * 1.25, f"{label} ({n})",
                ha="center", va="bottom", fontsize=9.5, color="#59687a")
        bound += n
    ax.legend(frameon=False, fontsize=8.5, ncol=5, loc="lower center",
              bbox_to_anchor=(0.5, -0.42))
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)
    fig.tight_layout()
    fig.savefig(OUT / "02_ARTIFACT_FILES/FIG2_population_frequency.svg", bbox_inches="tight")
    fig.savefig(OUT / "02_ARTIFACT_FILES/FIG2_population_frequency_preview.png",
                dpi=140, bbox_inches="tight")
    plt.close(fig)
    return f2, ldp, cats


# ====================================================== SUPP TABLE S1 (M07)
def supptable_s1(f2cats, ldp):
    f = PAY / "08_SUPPLEMENTARY_TABLE_SOURCES/table_s1_integrated_variant_annotation.tsv"
    s1 = pd.read_csv(f, sep="\t")

    leads = s1[s1["primary_LD_locus_role"] == "lead"]
    members = s1[s1["primary_LD_locus_role"] != "lead"]

    # Derived-field recomputation.
    rec_cat = [classify(r.gnomAD_EAS_MAF, r.gnomAD_EUR_MAF) for r in s1.itertuples()]
    stored_cat = s1["Population_Enrichment"].tolist()
    check("SUPPTABLE_S1_CATEGORY_RECOMPUTED",
          [x.lower() for x in stored_cat], [x.lower() for x in rec_cat])

    fold_bad, censored = [], []
    for r in s1.itertuples():
        eas, _ = parse_freq(r.gnomAD_EAS_MAF)
        eur, eur_kind = parse_freq(r.gnomAD_EUR_MAF)
        fold, fold_kind = parse_freq(r.EAS_EUR_Fold_Difference)
        if eas is None or eur is None or fold is None or eur == 0:
            continue
        if eur_kind == "exact" and fold_kind == "exact":
            # Both the MAF columns and the fold column are display-rounded, so
            # each is really an interval. The honest test is whether the two
            # intervals overlap - point equality would fail on every row.
            lo = (eas - half(r.gnomAD_EAS_MAF)) / (eur + half(r.gnomAD_EUR_MAF))
            hi = (eas + half(r.gnomAD_EAS_MAF)) / max(eur - half(r.gnomAD_EUR_MAF), 1e-12)
            fh = half(r.EAS_EUR_Fold_Difference)
            if hi < fold - fh or lo > fold + fh:
                fold_bad.append(r.Variant)
        else:
            # censored: verify the stated bound is consistent, not an equality
            censored.append((r.Variant, str(r.gnomAD_EUR_MAF), str(r.EAS_EUR_Fold_Difference)))
            if fold_kind == "lower_bound" and eur_kind == "upper_bound":
                if not eas / eur > fold:
                    fold_bad.append(r.Variant)
    check("SUPPTABLE_S1_FOLD_DIFFERENCE_RECOMPUTED", [], fold_bad)
    check("SUPPTABLE_S1_CENSORED_ROWS_LOGGED", 1, len(censored))
    if censored:
        print("  censored frequency rows (F-02):", censored)

    or_ok = bool(np.allclose(np.exp(s1["Beta"]), s1["OR"], rtol=1e-3))
    check("SUPPTABLE_S1_OR_EQUALS_EXP_BETA", True, or_ok)

    ratio_ok = bool(np.allclose(s1["Case_MAF"] / s1["Control_MAF"],
                                s1["Case_Control_Ratio"], rtol=5e-3))
    check("SUPPTABLE_S1_CASE_CONTROL_RATIO_RECOMPUTED", True, ratio_ok)

    # Cross-artifact consistency.
    check("LD_CLUMP_SIZES_SUM_TO_S1_ROWS", int(ldp["n_variants_in_clump"].sum()), len(s1))
    check("S1_LEAD_SET_EQUALS_LD_PRIMARY", sorted(ldp["lead_reference_id"]),
          sorted(leads["Variant"]))
    check("S1_LEAD_CATEGORY_COUNTS_EQUAL_FIG2",
          dict(sorted(f2cats.items())),
          dict(sorted(leads["Population_Enrichment"].value_counts().to_dict().items())))

    # Submitted CSV must be the same table.
    sub = pd.read_csv(ROOT / "submission/Supplementary_Table_S1.csv")
    check("S1_PAYLOAD_EQUALS_SUBMITTED_CSV_VARIANTS",
          sorted(sub["Variant"]), sorted(s1["Variant"]))

    # M07 calls DS_TABLE_S1_CURRENT the *current* source. Verify that cell by
    # cell against what was actually submitted, not just on the variant set.
    pay_s = pd.read_csv(f, sep="\t", dtype=str).set_index("Variant").sort_index()
    sub_s = pd.read_csv(ROOT / "submission/Supplementary_Table_S1.csv",
                        dtype=str).set_index("Variant").sort_index()
    cell_diffs = [(v, c) for v in pay_s.index for c in pay_s.columns
                  if c in sub_s.columns and str(pay_s.loc[v, c]) != str(sub_s.loc[v, c])]
    check("S1_PAYLOAD_CELLS_EQUAL_SUBMITTED", [], cell_diffs)
    if cell_diffs:
        print("  DS_TABLE_S1_CURRENT differs from the submitted table (F-03):", cell_diffs)

    cats = leads["Population_Enrichment"].value_counts().to_dict()
    claim("SUPPTABLE_S1_ROW_COUNT", "SUPPTABLE_S1", str(len(s1)), "DS_TABLE_S1_CURRENT",
          f"prespec band A; {len(leads)} leads + {len(members)} correlated non-lead members")
    claim("SUPPTABLE_S1_LEAD_COUNT", "SUPPTABLE_S1", str(len(leads)),
          "DS_TABLE_S1_CURRENT;DS_LD_PRIMARY", "prespec band A")
    claim("SUPPTABLE_S1_CATEGORY_COUNTS", "SUPPTABLE_S1", json.dumps(cats, sort_keys=True),
          "DS_TABLE_S1_CURRENT;DS_FIG2_FREQUENCY_CHECKPOINT",
          "prespec band A; lead rows only, independently recomputed")
    claim("SUPPTABLE_S1_SHA256", "SUPPTABLE_S1", sha256(f), "DS_TABLE_S1_CURRENT",
          "prespec band A")
    return s1


# ============================================================ FIGURE 3 (M04)
def figure3():
    fa = PAY / "04_CROSS_POPULATION_INPUTS/figure3_panel_a_source.tsv"
    fb = PAY / "04_CROSS_POPULATION_INPUTS/figure3_panel_b_source.tsv"
    a = pd.read_csv(fa, sep="\t")
    b = pd.read_csv(fb, sep="\t")

    claim("FIG3_PANEL_A_SOURCE_SHA256", "FIG3", sha256(fa), "DS_FIG3_PANEL_A", "prespec band A")
    claim("FIG3_PANEL_B_SOURCE_SHA256", "FIG3", sha256(fb), "DS_FIG3_PANEL_B", "prespec band A")

    # ---- panel A: every derived field recomputed from (n_concordant, K)
    # Column access is by name throughout: 'one_sided_binomial_P_vs_0.5' and
    # 'alpha_0.05_reference_Z' are not valid Python identifiers, so itertuples
    # would silently rename them to positional attributes.
    for i in range(len(a)):
        row = a.iloc[i]
        K, k = int(row["K"]), int(row["n_concordant"])
        conc = k / K
        p_one = float(stats.binomtest(k, K, 0.5, alternative="greater").pvalue)
        lo, hi = clopper_pearson(k, K)

        check(f"FIG3_K{K}_CONCORDANCE_RECOMPUTED", round(conc, 6),
              round(float(row["concordance"]), 6))
        # DS_FIG3_PANEL_A stores this P at 4 significant digits while storing
        # the Clopper-Pearson bounds at 6 decimals - see FINDING F-04. Compare
        # at the stored precision; the claim carries our full-precision value.
        check(f"FIG3_K{K}_BINOMIAL_P_RECOMPUTED_AT_STORED_PRECISION",
              float(f"{p_one:.4g}"), float(row["one_sided_binomial_P_vs_0.5"]))
        check(f"FIG3_K{K}_CP_CI_LO_RECOMPUTED", round(lo, 6),
              round(float(row["clopper_pearson_ci_lo"]), 6))
        check(f"FIG3_K{K}_CP_CI_HI_RECOMPUTED", round(hi, 6),
              round(float(row["clopper_pearson_ci_hi"]), 6))

        claim(f"FIG3_K{K}_N_CONCORDANT", "FIG3", str(k), "DS_FIG3_PANEL_A", "prespec band A")
        claim(f"FIG3_K{K}_CONCORDANCE", "FIG3", repr(conc), "DS_FIG3_PANEL_A",
              "prespec band B; recomputed as n_concordant/K")
        claim(f"FIG3_K{K}_BINOMIAL_P", "FIG3", repr(p_one), "DS_FIG3_PANEL_A",
              "prespec band C; recomputed one-sided exact binomial vs 0.5")

    # ---- panel B: P recomputed from Z; Z itself is not derivable here
    for i in range(len(b)):
        r = b.iloc[i]
        setting = str(r["setting"])
        z = float(r["directional_stouffer_Z"])
        p = float(stats.norm.sf(z))
        check(f"FIG3_{setting}_P_FROM_Z", round(p, 6), round(float(r["one_sided_P"]), 6))
        check(f"FIG3_{setting}_ALPHA_REF_Z", round(float(stats.norm.isf(0.05)), 6),
              round(float(r["alpha_0.05_reference_Z"]), 6))

        claim(f"FIG3_{setting}_N_LEADS", "FIG3", str(int(r["n_leads"])),
              "DS_FIG3_PANEL_B;DS_EAS_LD_PGEN;DS_EAS_LD_PVAR;DS_EAS_LD_PSAM",
              "prespec band A; genome-wide LD-clumped leads, not the 17 retained loci")
        claim(f"FIG3_{setting}_Z", "FIG3", repr(z), "DS_FIG3_PANEL_B",
              "prespec band B; transcribed - per-lead sign/weight data is not in Package A, "
              "so Z cannot be rebuilt from primitives at L2")
        claim(f"FIG3_{setting}_P", "FIG3", repr(p), "DS_FIG3_PANEL_B",
              "prespec band C; recomputed as the upper standard-normal tail of Z")

    # ---- render
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 4.8))
    x = np.arange(len(a))
    lo = a["concordance"] - a["clopper_pearson_ci_lo"]
    hi = a["clopper_pearson_ci_hi"] - a["concordance"]
    ax1.errorbar(x, a["concordance"], yerr=[lo, hi], fmt="o", ms=6, capsize=4,
                 color="#2f4b7c", ecolor="#8fa8c8", lw=1.2)
    ax1.axhline(0.5, color="#b3402f", lw=1.0, ls="--")
    ax1.set_xticks(x)
    ax1.set_xticklabels([f"K={int(k)}" for k in a["K"]])
    ax1.set_ylabel("Directional concordance")
    ax1.set_title("(a) Top-K directional concordance", loc="left", fontsize=11)
    for i, r in enumerate(a.itertuples()):
        ax1.annotate(f"{int(r.n_concordant)}/{int(r.K)}", (i, r.clopper_pearson_ci_hi),
                     textcoords="offset points", xytext=(0, 7), ha="center", fontsize=8.5,
                     color="#59687a")
    ax1.set_ylim(0.44, 0.70)

    y = np.arange(len(b))
    ax2.barh(y, b["directional_stouffer_Z"], color="#2f4b7c", height=0.55)
    ax2.axvline(float(b["alpha_0.05_reference_Z"].iloc[0]), color="#b3402f", lw=1.0, ls="--")
    ax2.annotate(r"one-sided $\alpha$=0.05", (float(b["alpha_0.05_reference_Z"].iloc[0]), -0.75),
                 fontsize=8.5, color="#b3402f", ha="center")
    ax2.set_yticks(y)
    ax2.set_yticklabels(b["setting"], fontsize=8.5)
    ax2.invert_yaxis()
    ax2.set_xlabel("Weighted directional Z")
    ax2.set_title("(b) LD-specification sensitivity", loc="left", fontsize=11)
    for sp in ("top", "right"):
        ax1.spines[sp].set_visible(False)
        ax2.spines[sp].set_visible(False)
    fig.tight_layout()
    fig.savefig(OUT / "02_ARTIFACT_FILES/FIG3_cross_population.svg", bbox_inches="tight")
    fig.savefig(OUT / "02_ARTIFACT_FILES/FIG3_cross_population_preview.png",
                dpi=140, bbox_inches="tight")
    plt.close(fig)
    return a, b


def main():
    t1 = table1()
    f2, ldp, f2cats = figure2()
    supptable_s1(f2cats, ldp)
    figure3()

    with open(OUT / "03_SOURCE_TABLES/ld_chain_verification.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(["check_id", "expected", "observed", "status"])
        w.writerows(checks)

    hdr = ["claim_id", "artifact_id", "value", "method_status", "source_dataset_ids", "notes"]
    with open(OUT / "03_SOURCE_TABLES/ld_chain_claims.tsv", "w", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        w.writerow(hdr)
        w.writerows(claims)

    failed = [c for c in checks if c[3] == "FAIL"]
    print(f"claims produced : {len(claims)}")
    print(f"verifications   : {len(checks)}  PASS={len(checks)-len(failed)}  FAIL={len(failed)}")
    for c in failed:
        print(f"  FAIL {c[0]}\n       expected {c[1][:150]}\n       observed {c[2][:150]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
