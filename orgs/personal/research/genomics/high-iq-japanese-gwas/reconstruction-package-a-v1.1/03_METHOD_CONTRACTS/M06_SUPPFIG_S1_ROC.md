
# M06 — Supplementary Figure S1: ROC visual reassembly

## Scope and level

`L3_VISUAL_REASSEMBLY_FROM_CANONICAL_AGGREGATE_OR_RASTER`. Analytical FPR/TPR
coordinates and per-sample prediction probabilities are not retained. Do not
claim model-level ROC recalculation.

## Reassembly contract

Use `DS_ROC_PANEL_A_RASTER` and `DS_ROC_PANEL_B_RASTER` only as aggregate curve
checkpoints. Rebuild a two-panel layout with live outer axes, panel letters,
titles, model labels, diagonal reference semantics, and AUC annotations taken
from `DS_ROC_AUC_SOURCE`. The canonical interior crop is x=201:1864 and
y=108:1264 in each 1920x1440 checkpoint; implementations may detect the same
axes rectangle algorithmically and must log any differing crop.

Do not trace or invent curve coordinates. Do not insert the canonical combined
Supplementary Figure, which is withheld in Package B.

## Return

Return the reassembled figure, panel/crop metadata, source raster hashes,
annotation table, and a declaration that the result is L3 visual reassembly.
Pixel identity is not required; panel content, axes, AUC labels, legend
semantics, and caption compatibility are required.
