"""M06 - Supplementary Figure S1: ROC visual reassembly (L3).

This is L3 VISUAL REASSEMBLY, not model-level ROC recalculation. Analytical
FPR/TPR coordinates and per-sample prediction probabilities are not retained in
Package A, so no ROC is recomputed and no curve coordinate is traced, fitted or
invented. The two canonical panel rasters are used only as aggregate curve
checkpoints; everything around them - outer axes, panel letters, titles, model
labels, diagonal reference semantics and AUC annotations - is rebuilt live from
DS_ROC_AUC_SOURCE.

The canonical interior crop is x=201:1864, y=108:1264 in each 1920x1440
checkpoint. The axes rectangle is ALSO detected algorithmically and any
difference from the canonical crop is logged rather than silently accepted.
"""
from __future__ import annotations

import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from PIL import Image

from common import OUT, ds, ds_sha, repr_full, write_tsv

DEST = OUT / "SUPPFIG_S1"

# Canonical interior crop declared by M06 (1-based inclusive, as written).
CANONICAL_CROP = {"x_start": 201, "x_end": 1864, "y_start": 108, "y_end": 1264}
EXPECTED_SIZE = (1920, 1440)

# Panel -> model, read off the panel titles carried in the rasters themselves
# and confirmed against the AUC source.
PANEL_MODEL = {"A": "PGS-only", "B": "Full model (PGS + covariates)"}


def detect_axes_rect(img: Image.Image) -> dict:
    """Locate the plotted axes rectangle by finding the darkest full-length
    rows and columns of the frame. Independent of the declared crop."""
    g = np.asarray(img.convert("L"), dtype=np.float32)
    dark = g < 128
    col_frac = dark.mean(axis=0)
    row_frac = dark.mean(axis=1)
    # Frame lines span most of the interior height/width.
    cand_cols = np.where(col_frac > 0.5)[0]
    cand_rows = np.where(row_frac > 0.5)[0]
    if cand_cols.size < 2 or cand_rows.size < 2:
        return {"detected": False}
    return {
        "detected": True,
        "x_start": int(cand_cols.min()) + 1,
        "x_end": int(cand_cols.max()) + 1,
        "y_start": int(cand_rows.min()) + 1,
        "y_end": int(cand_rows.max()) + 1,
    }


def interior(img: Image.Image, crop: dict) -> np.ndarray:
    """Crop the interior. M06 states 1-based inclusive bounds."""
    return np.asarray(img.convert("RGB"))[
        crop["y_start"] - 1: crop["y_end"], crop["x_start"] - 1: crop["x_end"]]


# The chance diagonal drawn in each checkpoint runs from data (0,0) to (1,1),
# so its pixel extent calibrates the raster onto ROC coordinates. This is a
# registration measurement, not a reading of the ROC curve itself.
DIAGONAL_RGB = np.array([255, 127, 14], dtype=np.int16)


def calibrate_extent(crop_img: np.ndarray, tol: int = 60) -> dict:
    """Return the imshow `extent` placing the cropped axes rectangle so that
    the chance diagonal spans exactly data (0,0)-(1,1).

    Without this the axes rectangle is drawn as if it were the data rectangle,
    which silently shifts every curve inwards by the renderer's axis margin.
    """
    d = np.abs(crop_img.astype(np.int16) - DIAGONAL_RGB).sum(axis=2)
    mask = d < tol
    if mask.sum() < 100:
        return {"calibrated": False}
    rows, cols = np.where(mask)
    c0, c1 = int(cols.min()), int(cols.max())
    r0, r1 = int(rows.min()), int(rows.max())
    h, w = mask.shape
    if c1 == c0 or r1 == r0:
        return {"calibrated": False}
    left = (0.0 - c0) / (c1 - c0)
    right = (w - c0) / (c1 - c0)
    # Image row 0 is the top, which is data y = 1.
    top = (r1 - 0.0) / (r1 - r0)
    bottom = (r1 - h) / (r1 - r0)
    return {
        "calibrated": True,
        "extent": (left, right, bottom, top),
        "diagonal_pixel_bbox": {"col_min": c0, "col_max": c1,
                                "row_min": r0, "row_max": r1},
        "diagonal_pixel_count": int(mask.sum()),
        "implied_axis_margin_fraction": float((0.0 - left) / (right - left)),
    }


def main() -> dict:
    DEST.mkdir(parents=True, exist_ok=True)
    audit: dict[str, object] = {"reconstruction_level": "L3_VISUAL_REASSEMBLY"}

    auc_src = pd.read_csv(ds("DS_ROC_AUC_SOURCE"), sep="\t")
    audit["auc_source_sha256"] = ds_sha("DS_ROC_AUC_SOURCE")
    audit["auc_models"] = list(auc_src["model"])
    aucs = {str(r["model"]): float(r["auc"]) for _, r in auc_src.iterrows()}
    thresholds = {str(r["model"]): str(r["threshold_setting"]) for _, r in auc_src.iterrows()}
    provenance = {str(r["model"]): str(r["provenance"]) for _, r in auc_src.iterrows()}
    audit["auc_values"] = aucs
    audit["auc_provenance"] = provenance

    panels, crop_rows = {}, []
    for letter, key in (("A", "DS_ROC_PANEL_A_RASTER"), ("B", "DS_ROC_PANEL_B_RASTER")):
        img = Image.open(ds(key))
        sha = ds_sha(key)
        size_ok = img.size == EXPECTED_SIZE
        det = detect_axes_rect(img)
        same = det.get("detected") and all(
            det[k] == CANONICAL_CROP[k] for k in CANONICAL_CROP)
        deltas = ({k: det[k] - CANONICAL_CROP[k] for k in CANONICAL_CROP}
                  if det.get("detected") else {})
        # The detector returns the frame rectangle *including* the drawn axis
        # spine; the canonical crop is the interior strictly inside it. The
        # expected relationship is therefore containment, not equality.
        contains = det.get("detected") and (
            det["x_start"] < CANONICAL_CROP["x_start"]
            and det["x_end"] > CANONICAL_CROP["x_end"]
            and det["y_start"] < CANONICAL_CROP["y_start"]
            and det["y_end"] > CANONICAL_CROP["y_end"])
        panels[letter] = {
            "dataset_id": key, "sha256": sha, "size": list(img.size),
            "size_matches_expected": bool(size_ok),
            "canonical_crop": CANONICAL_CROP, "detected_crop": det,
            "detected_matches_canonical": bool(same),
            "detected_frame_contains_canonical_interior": bool(contains),
            "crop_deltas_px": deltas,
            "crop_used": "canonical",
            "image": interior(img, CANONICAL_CROP),
        }
        panels[letter]["calibration"] = calibrate_extent(panels[letter]["image"])
        crop_rows.append([
            letter, key, sha, f"{img.size[0]}x{img.size[1]}", size_ok,
            f"x={CANONICAL_CROP['x_start']}:{CANONICAL_CROP['x_end']}, "
            f"y={CANONICAL_CROP['y_start']}:{CANONICAL_CROP['y_end']}",
            (f"x={det['x_start']}:{det['x_end']}, y={det['y_start']}:{det['y_end']}"
             if det.get("detected") else "NOT_DETECTED"),
            same, bool(contains), json.dumps(deltas, separators=(",", ":")), "canonical",
            panels[letter]["calibration"].get("calibrated"),
            json.dumps([round(float(v), 6) for v in
                        panels[letter]["calibration"].get("extent", ())],
                       separators=(",", ":")),
            repr_full(panels[letter]["calibration"].get("implied_axis_margin_fraction")),
        ])

    audit["panels"] = {k: {kk: vv for kk, vv in v.items() if kk != "image"}
                       for k, v in panels.items()}
    audit["all_panels_calibrated"] = all(
        p["calibration"].get("calibrated") for p in panels.values())
    audit["all_panels_size_ok"] = all(p["size_matches_expected"] for p in panels.values())
    audit["all_detected_crops_match_canonical"] = all(
        p["detected_matches_canonical"] for p in panels.values())
    audit["all_detected_frames_contain_canonical_interior"] = all(
        p["detected_frame_contains_canonical_interior"] for p in panels.values())

    write_tsv(DEST / "SUPPFIG_S1_panel_crop_metadata.tsv",
              ["panel", "dataset_id", "source_sha256", "raster_size",
               "size_matches_expected", "canonical_crop", "detected_crop",
               "detected_matches_canonical", "detected_frame_contains_canonical",
               "crop_delta_px", "crop_used", "roc_registration_calibrated",
               "imshow_extent", "implied_axis_margin_fraction"],
              crop_rows)

    write_tsv(DEST / "SUPPFIG_S1_annotation_table.tsv",
              ["panel", "model", "auc", "threshold_setting", "provenance",
               "curve_source", "recalculated"],
              [[letter, PANEL_MODEL[letter], repr_full(aucs[PANEL_MODEL[letter]]),
                thresholds[PANEL_MODEL[letter]], provenance[PANEL_MODEL[letter]],
                panels[letter]["dataset_id"], "NO_L3_VISUAL_REASSEMBLY"]
               for letter in ("A", "B")]
              + [["(reference, no panel raster)", "Covariates-only (sex + PC1-10)",
                  repr_full(aucs["Covariates-only (sex + PC1-10)"]),
                  thresholds["Covariates-only (sex + PC1-10)"],
                  provenance["Covariates-only (sex + PC1-10)"],
                  "NONE_RETAINED", "NO_L3_VISUAL_REASSEMBLY"]])

    # ---------------- reassembled figure ----------------
    fig, axes = plt.subplots(1, 2, figsize=(12.4, 6.1))
    for ax, letter in zip(axes, ("A", "B")):
        p = panels[letter]
        model = PANEL_MODEL[letter]
        # The interior raster is placed on live outer axes in ROC coordinates.
        cal = p["calibration"]
        ext = cal["extent"] if cal.get("calibrated") else (0.0, 1.0, 0.0, 1.0)
        ax.imshow(p["image"], extent=ext, aspect="auto",
                  interpolation="antialiased", zorder=1)
        ax.set_xlim(ext[0], ext[1])
        ax.set_ylim(ext[2], ext[3])
        ax.set_xticks(np.arange(0, 1.01, 0.2))
        ax.set_yticks(np.arange(0, 1.01, 0.2))
        ax.set_xlabel("False positive rate")
        ax.set_ylabel("True positive rate")
        ax.set_title(f"{letter}  {model}", loc="left", fontweight="bold")
        ax.text(0.97, 0.06, f"AUC = {aucs[model]:.3f}", transform=ax.transAxes,
                ha="right", va="bottom", fontsize=11,
                bbox=dict(boxstyle="round,pad=0.35", facecolor="white",
                          edgecolor="#666666", alpha=0.92), zorder=3)
        ax.text(0.97, 0.005, f"threshold: {thresholds[model]}",
                transform=ax.transAxes, ha="right", va="bottom", fontsize=7.5,
                color="#555555", zorder=3)
        # Diagonal reference semantics are restated live (not traced).
        ax.plot([0, 1], [0, 1], color="#c0392b", ls=":", lw=1.1, zorder=2,
                label="chance reference (AUC = 0.5)")
        ax.legend(loc="upper left", fontsize=8, frameon=False)
        ax.set_aspect("equal", adjustable="box")

    fig.suptitle("Supplementary Figure S1  ROC curves "
                 "(L3 visual reassembly from canonical panel rasters; "
                 "curves not recalculated)", fontsize=10.5, y=0.995)
    fig.text(0.5, 0.005,
             "Interior panel content is the canonical aggregate raster checkpoint, "
             f"cropped x={CANONICAL_CROP['x_start']}:{CANONICAL_CROP['x_end']}, "
             f"y={CANONICAL_CROP['y_start']}:{CANONICAL_CROP['y_end']}. "
             f"Covariates-only (sex + PC1-10) AUC = "
             f"{aucs['Covariates-only (sex + PC1-10)']:.3f} has no retained panel raster.",
             ha="center", fontsize=7.5, color="#444444")
    fig.tight_layout(rect=(0, 0.03, 1, 0.96))
    fig.savefig(DEST / "SUPPFIG_S1_roc_reassembled.svg", bbox_inches="tight")
    fig.savefig(DEST / "SUPPFIG_S1_roc_reassembled.pdf", bbox_inches="tight")
    fig.savefig(DEST / "SUPPFIG_S1_roc_reassembled.png", dpi=200, bbox_inches="tight")
    plt.close(fig)

    (DEST / "SUPPFIG_S1_audit.json").write_text(
        json.dumps(audit, indent=2, default=str), encoding="utf-8")

    claims = {
        "SUPPFIG_S1_PGS_ONLY_AUC": aucs["PGS-only"],
        "SUPPFIG_S1_COVARIATES_ONLY_SEX_PC1_10_AUC": aucs["Covariates-only (sex + PC1-10)"],
        "SUPPFIG_S1_FULL_MODEL_PGS_COVARIATES_AUC": aucs["Full model (PGS + covariates)"],
        "SUPPFIG_S1_PANEL_A_RASTER_SHA256": panels["A"]["sha256"],
        "SUPPFIG_S1_PANEL_B_RASTER_SHA256": panels["B"]["sha256"],
    }
    (DEST / "SUPPFIG_S1_claims.json").write_text(
        json.dumps({k: repr_full(v) for k, v in claims.items()}, indent=2), encoding="utf-8")
    return {"claims": claims, "audit": audit}


if __name__ == "__main__":
    r = main()
    for k, v in r["claims"].items():
        print(f"{k}\t{repr_full(v)}")
    a = r["audit"]
    print("\n-- registration --")
    for letter, p in a["panels"].items():
        c = p["calibration"]
        print(f"panel {letter}: calibrated={c.get('calibrated')} "
              f"extent={tuple(round(float(v),6) for v in c.get('extent',()))} "
              f"margin={c.get('implied_axis_margin_fraction')}")
    print("\n-- crop audit --")
    for letter, p in a["panels"].items():
        print(f"panel {letter}: size={p['size']} ok={p['size_matches_expected']} "
              f"detected={p['detected_crop']} matches_canonical={p['detected_matches_canonical']} "
              f"deltas={p['crop_deltas_px']}")
