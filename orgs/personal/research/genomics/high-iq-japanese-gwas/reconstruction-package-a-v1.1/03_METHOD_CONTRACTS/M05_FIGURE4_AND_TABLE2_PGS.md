
# M05 — Figure 4 and Table 2: PGS checkpoint reconstruction

## Scope and level

L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT. Accept the designated
provider workbook, the full available PRSice2 threshold-scan output, the
current canonical case-only PGS, and the current aggregate control/model
sources as the Phase 5C starting point. A PRSice2 rerun from 41,528 individual
control genotypes is explicitly outside scope and must not be claimed.

Only the Package A dataset IDs DS_NOGAWA_WORKBOOK, DS_PRSICE_THRESHOLD_SCAN,
DS_CASE_PGS_CANONICAL, DS_FIG4_AGGREGATE_SOURCE, DS_FIG4_DENSITY,
DS_FIG4_SUMMARY, DS_FIG4_PANEL_B, and DS_TABLE2_CHECKPOINT are accepted for the
current Figure 4/Table 2 chain. If an alternate case-score series or alternate
performance checkpoint is introduced, stop and request source adjudication.

## Fixed and optimized rows

The prespecified fixed P thresholds are 5e-8, 5e-5, 1e-4, 1e-3, 1e-2, and
5e-2. The optimized row is a secondary analysis and must remain visually and
textually distinct from the fixed primary grid. Preserve full-precision
threshold, SNP-count, incremental Nagelkerke R2, covariate-adjusted P, OR/CI,
AUC, and LRT fields; apply display rounding only at the final table layer.

For Panel A, standardize each case score as
z=(case_score-control_mean)/control_sample_SD. The control curve is a normal
approximation with mean zero and SD one. For an independent density rebuild,
use Gaussian KDE bandwidth case_sample_SD * n^(-1/5), a 600-point grid from
min(-4, case_min-0.5) to max(4, case_max+0.5), and 18 equal-width histogram
bins. Aggregate grid/bin outputs may be returned; individual scores must remain
inside the encrypted return package.

## Return

Return Figure 4, Table 2A/2B, aggregate density/summary data, the full threshold
scan or a hash-locked copy, model field definitions, and deviations. State L2
and the provider-level limitation prominently.
