
# M01 — Figure 1: Manhattan and QQ reconstruction

## Scope and level

`L1_INDEPENDENT_RECALCULATION_FROM_ANALYSIS_READY_DATA`. Recalculate the
statistics and both panels from `DS_GWAS_CURRENT`; do not use a canonical
plotting implementation or a rendered current figure.

## Required input and coding

- Input: `DS_GWAS_CURRENT`.
- Genome build: GRCh37; autosomes 1–22.
- Variant key: `chromosome:position_grch37:effect_allele:other_allele`, while
  preserving `variant_id` for display and audit.
- Association P is two-sided and beta is coded to `effect_allele`.
- Preserve `qc_status`. Do not silently restore any variant that is absent from
  the current exact snapshot or substitute an older summary.

## Calculation

Validate finite positions and `0 < p_value <= 1`. Build cumulative chromosome
positions in chromosome order with a declared constant inter-chromosome gap.
Plot `-log10(P)` and show method thresholds at `5e-8` and `1e-5`.

For the QQ panel, order all valid P values, use the corresponding uniform-order
expectation, and plot observed against expected `-log10(P)`. Compute
`lambda_GC = median(chi-square_1 inverse-survival(P)) / median(chi-square_1)`.
State the exact order-statistic convention if it differs from `(i-0.5)/m`.

## Return

Return the recomputed numeric claim table, a Manhattan/QQ figure in SVG or PDF
plus a PNG preview, the input SHA-256, row/filter counts, and a deviation log.
Figure style may differ, but chromosome order, thresholds, annotations,
scientific direction, and both panels must be semantically equivalent.
