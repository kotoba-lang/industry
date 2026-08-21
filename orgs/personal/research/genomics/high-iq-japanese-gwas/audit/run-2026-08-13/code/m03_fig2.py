"""M03 - Figure 2: population frequency and East Asian LD loci (L2).

Independent part: the EAS/EUR population-enrichment category of every row is
recomputed from the harmonized frequencies using the M03 thresholds, and the
lead/member structure is audited against DS_LD_PRIMARY.

Checkpoint part: the frequency values themselves come from
DS_FIG2_FREQUENCY_CHECKPOINT; no external portal is re-queried.

Category rule (M03 / METRIC_DEFINITIONS population_frequency_ratio):
    ratio = EAS effect-allele frequency / EUR effect-allele frequency
    ratio > 2            -> East Asian enriched
    ratio < 0.5          -> European enriched
    0.5 <= ratio <= 2    -> Similar frequency        (inclusive interval)
    EUR == 0 and EAS > 0 -> East Asian enriched
    missing              -> unclassified, and logged
"""
from __future__ import annotations

import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from common import OUT, ds, ds_sha, repr_full, write_tsv

DEST = OUT / "FIG2"

# The five declared frequency series, in the checkpoint's own column order.
SERIES = ["Case_MAF", "Control_MAF", "ToMMo_38KJPN_MAF", "gnomAD_EAS_MAF", "gnomAD_EUR_MAF"]
SERIES_LABEL = {
    "Case_MAF": "Case (high-IQ)",
    "Control_MAF": "Control",
    "ToMMo_38KJPN_MAF": "ToMMo 38KJPN",
    "gnomAD_EAS_MAF": "gnomAD EAS",
    "gnomAD_EUR_MAF": "gnomAD EUR",
}
EAS_ENRICHED = "East Asian enriched"
EUR_ENRICHED = "European enriched"
SIMILAR = "Similar frequency"
UNCLASSIFIED = "Unclassified"


def parse_freq(raw) -> tuple[float, bool]:
    """Parse a frequency cell. Returns (value, censored).

    '<0.0001' is a censored below-detection report, not a missing value: it is
    carried at its stated bound and flagged so the classification audit can
    show the decision does not depend on the bound.
    """
    s = str(raw).strip()
    if s == "" or s.lower() in {"na", "nan", "none", "."}:
        return float("nan"), False
    if s.startswith("<"):
        return float(s[1:]), True
    if s.startswith(">"):
        return float(s[1:]), True
    try:
        return float(s), False
    except ValueError:
        return float("nan"), False


def classify(eas: float, eur: float) -> tuple[str, float]:
    if not np.isfinite(eas) or not np.isfinite(eur):
        return UNCLASSIFIED, float("nan")
    if eur == 0.0:
        return (EAS_ENRICHED, float("inf")) if eas > 0 else (UNCLASSIFIED, float("nan"))
    ratio = eas / eur
    if ratio > 2.0:
        return EAS_ENRICHED, ratio
    if ratio < 0.5:
        return EUR_ENRICHED, ratio
    return SIMILAR, ratio


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    audit: dict[str, object] = {}

    f2 = pd.read_csv(ds("DS_FIG2_FREQUENCY_CHECKPOINT"), sep="\t", dtype=str)
    ld = pd.read_csv(ds("DS_LD_PRIMARY"), sep="\t", dtype=str)
    audit["fig2_source_sha256"] = ds_sha("DS_FIG2_FREQUENCY_CHECKPOINT")
    audit["ld_primary_sha256"] = ds_sha("DS_LD_PRIMARY")
    audit["fig2_row_count"] = len(f2)
    audit["series_count"] = len(SERIES)
    audit["series"] = SERIES

    # --- plot_order integrity --------------------------------------------
    order = pd.to_numeric(f2["plot_order"], errors="coerce")
    audit["plot_order_is_contiguous_1_to_n"] = bool(
        list(order) == list(range(1, len(f2) + 1)))

    # --- independent category recomputation -------------------------------
    rows, cat_recomputed, censored_flags = [], [], []
    for _, r in f2.iterrows():
        eas, eas_c = parse_freq(r["gnomAD_EAS_MAF"])
        eur, eur_c = parse_freq(r["gnomAD_EUR_MAF"])
        cat, ratio = classify(eas, eur)
        # Robustness of a censored EUR bound: would the call change at EUR=0?
        alt_cat = cat
        if eur_c:
            alt_cat, _ = classify(eas, 0.0)
        cat_recomputed.append(cat)
        censored_flags.append(eas_c or eur_c)
        rows.append([
            r["plot_order"], r["Variant"], r["Gene"],
            r["Population_Enrichment"], cat,
            "MATCH" if cat == r["Population_Enrichment"] else "MISMATCH",
            repr_full(ratio), repr_full(eas), repr_full(eur),
            "TRUE" if (eas_c or eur_c) else "FALSE",
            alt_cat,
            "TRUE" if alt_cat == cat else "FALSE",
        ])

    f2["category_recomputed"] = cat_recomputed
    match = [a == b for a, b in zip(cat_recomputed, f2["Population_Enrichment"])]
    audit["category_recomputation_match_all"] = bool(np.all(match))
    audit["category_mismatch_rows"] = [
        str(f2.loc[i, "Variant"]) for i, ok in enumerate(match) if not ok]
    audit["n_censored_frequency_rows"] = int(sum(censored_flags))
    audit["n_unclassified"] = int(sum(c == UNCLASSIFIED for c in cat_recomputed))

    counts_chk = f2["Population_Enrichment"].value_counts().to_dict()
    counts_rec = pd.Series(cat_recomputed).value_counts().to_dict()
    audit["category_counts_checkpoint"] = {str(k): int(v) for k, v in counts_chk.items()}
    audit["category_counts_recomputed"] = {str(k): int(v) for k, v in counts_rec.items()}

    write_tsv(DEST / "FIG2_category_audit.tsv",
              ["plot_order", "Variant", "Gene", "category_checkpoint",
               "category_recomputed", "agreement", "eas_eur_ratio",
               "gnomAD_EAS_MAF", "gnomAD_EUR_MAF", "frequency_censored",
               "category_if_censored_bound_treated_as_zero", "call_robust_to_bound"],
              rows)

    # --- LD lead/member audit --------------------------------------------
    audit["ld_primary_lead_rows"] = int(len(ld))
    audit["ld_resolution_status_counts"] = {
        str(k): int(v) for k, v in ld["resolution_status"].value_counts().items()}
    n_in_clump = pd.to_numeric(ld["n_variants_in_clump"], errors="coerce")
    audit["ld_total_variants_in_clumps"] = int(n_in_clump.sum())
    audit["ld_leads_with_members"] = int((n_in_clump > 1).sum())
    # M03: "treat a clump as the lead plus assigned member variants; do not
    # reinterpret PLINK summary-bin counts as member counts."
    # In this checkpoint members_ref_ids lists NON-lead members only and uses
    # '.' for none, so n_variants_in_clump == 1 + len(members).
    member_counts = []
    for _, r in ld.iterrows():
        mem = str(r["members_ref_ids"]) if pd.notna(r["members_ref_ids"]) else ""
        ids = [x.strip() for x in mem.replace(";", ",").split(",")
               if x.strip() and x.strip() != "."]
        member_counts.append(len(ids))
    audit["ld_member_id_list_total"] = int(sum(member_counts))
    audit["ld_lead_plus_members_equals_n_variants_in_clump"] = bool(
        [1 + m for m in member_counts] == list(n_in_clump.astype(int)))
    audit["ld_n_leads"] = int(len(ld))
    audit["ld_n_members"] = int(sum(member_counts))
    audit["ld_leads_plus_members_total"] = int(len(ld) + sum(member_counts))

    # Every Figure 2 variant must be a current primary LD lead.
    f2_vars = set(f2["Variant"])
    ld_leads = set(ld["lead_reference_id"])
    audit["fig2_variants_all_are_primary_ld_leads"] = f2_vars.issubset(ld_leads)
    audit["fig2_variants_not_in_ld_primary"] = sorted(f2_vars - ld_leads)
    audit["ld_leads_not_in_fig2"] = sorted(ld_leads - f2_vars)

    # --- figure ------------------------------------------------------------
    f2p = f2.copy()
    f2p["_order"] = order
    f2p = f2p.sort_values("_order")
    vals = np.array([[parse_freq(v)[0] for v in f2p[s]] for s in SERIES])

    n = len(f2p)
    x = np.arange(n)
    width = 0.16
    palette = ["#c0392b", "#2c6fad", "#4d9f6f", "#e08a1e", "#7c5aa6"]

    fig, ax = plt.subplots(figsize=(15, 6.2))
    for i, s in enumerate(SERIES):
        ax.bar(x + (i - 2) * width, vals[i], width,
               label=SERIES_LABEL[s], color=palette[i], edgecolor="none")

    # Category grouping is preserved: draw separators at category boundaries.
    cats = list(f2p["Population_Enrichment"])
    bounds = [i for i in range(1, n) if cats[i] != cats[i - 1]]
    for b in bounds:
        ax.axvline(b - 0.5, color="#999999", lw=0.9, ls="--")
    start = 0
    for b in bounds + [n]:
        ax.text((start + b - 1) / 2, ax.get_ylim()[1] * 0.97, cats[start],
                ha="center", va="top", fontsize=9, style="italic", color="#444444")
        start = b

    ax.set_xticks(x)
    ax.set_xticklabels([f"{v}\n{g}" for v, g in zip(f2p["Variant"], f2p["Gene"])],
                       rotation=60, ha="right", fontsize=7.5)
    ax.set_ylabel("Minor / effect-allele frequency")
    ax.set_xlabel("Lead variant (primary East Asian LD locus)")
    ax.set_title("Figure 2  Population frequency across cohorts and reference panels",
                 loc="left", fontweight="bold")
    ax.legend(ncol=5, fontsize=8, frameon=False, loc="upper center",
              bbox_to_anchor=(0.5, -0.32))
    ax.set_xlim(-0.6, n - 0.4)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    fig.savefig(DEST / "FIG2_population_frequency.svg", bbox_inches="tight")
    fig.savefig(DEST / "FIG2_population_frequency.pdf", bbox_inches="tight")
    fig.savefig(DEST / "FIG2_population_frequency.png", dpi=200, bbox_inches="tight")
    plt.close(fig)

    # --- exact returned source table (full precision preserved) -----------
    out_rows = []
    for _, r in f2p.iterrows():
        out_rows.append([r["plot_order"], r["Variant"], r["Gene"],
                         r["Population_Enrichment"], r["category_recomputed"],
                         r["P_value"]] + [r[s] for s in SERIES])
    write_tsv(DEST / "FIG2_source_table.tsv",
              ["plot_order", "Variant", "Gene", "Population_Enrichment",
               "Population_Enrichment_recomputed", "P_value"] + SERIES,
              out_rows)

    (DEST / "FIG2_audit.json").write_text(json.dumps(audit, indent=2, default=str),
                                          encoding="utf-8")

    claims = {
        "FIG2_LEAD_ROW_COUNT": int(len(ld)),
        "FIG2_CATEGORY_COUNTS": json.dumps(
            {k: int(v) for k, v in sorted(counts_rec.items())}, separators=(",", ":")),
        "FIG2_SOURCE_SHA256": audit["fig2_source_sha256"],
        "FIG2_SERIES_COUNT": len(SERIES),
    }
    (DEST / "FIG2_claims.json").write_text(
        json.dumps({k: repr_full(v) for k, v in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit}


if __name__ == "__main__":
    r = main()
    for k, v in r["claims"].items():
        print(f"{k}\t{repr_full(v)}")
    print("\n-- audit --")
    for k in ("fig2_row_count", "ld_primary_lead_rows", "plot_order_is_contiguous_1_to_n",
              "category_recomputation_match_all", "category_mismatch_rows",
              "category_counts_checkpoint", "n_censored_frequency_rows",
              "n_unclassified", "fig2_variants_all_are_primary_ld_leads",
              "ld_leads_not_in_fig2", "ld_total_variants_in_clumps",
              "ld_lead_plus_members_equals_n_variants_in_clump", "ld_n_leads", "ld_n_members", "ld_leads_plus_members_total"):
        print(f"{k}\t{r['audit'][k]}")
