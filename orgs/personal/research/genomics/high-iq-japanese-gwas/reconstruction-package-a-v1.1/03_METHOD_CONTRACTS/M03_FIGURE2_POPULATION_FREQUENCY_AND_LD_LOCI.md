
# M03 — Figure 2: population frequency and East Asian LD loci

## Scope and level

`L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT`. The verified starting
point is `DS_FIG2_FREQUENCY_CHECKPOINT` with `DS_LD_PRIMARY`. The exact
operational compact EAS reference and sensitivity checkpoints are included to
support deeper discrepancy localization, but reproducing the submitted figure
does not require reacquiring the full 1000 Genomes source archive.

## LD and frequency rules

The primary locus definition is unphased East Asian LD clumping with `r2=0.10`,
a 500 kb window, and Japanese association ranking. Sensitivities use
`r2=0.05/500 kb`, `r2=0.20/500 kb`, `r2=0.10/250 kb`, and
`r2=0.10/1000 kb`. Treat a clump as the lead plus assigned member variants;
do not reinterpret PLINK summary-bin counts as member counts.

Harmonize every reference frequency to the Japanese effect allele. Classify
the EAS/EUR frequency ratio as East Asian enriched when above 2, European
enriched when below 0.5, and similar frequency on the inclusive interval
0.5–2. If EUR is zero and EAS positive, classify East Asian enriched; missing
values remain unclassified and must be logged.

Plot the five declared frequency series in checkpoint `plot_order`, preserving
category grouping and the full precision in the returned source table.

## Return

Return a reconstructed Figure 2, exact data table, locus/category audit,
allele-harmonization audit, and deviations. Label this L2 even if an optional
independent LD rerun is also supplied.
