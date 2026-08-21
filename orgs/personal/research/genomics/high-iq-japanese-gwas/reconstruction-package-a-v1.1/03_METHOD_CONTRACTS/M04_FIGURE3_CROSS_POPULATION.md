
# M04 — Figure 3: cross-population directional reconstruction

## Scope and level

`L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT`. The package includes the
exact current merged Japanese-European genome-wide checkpoint and operational
EAS reference for optional deeper reruns. The required submitted-artifact
starting points are the current primary/sensitivity statistic checkpoints and
the two figure-source tables.

## Statistical contract

For optional recalculation, harmonize GRCh37 position and effect alleles.
Japanese signed Z is beta/SE; European direction is the sign of beta with
magnitude derived from the two-sided P value. EAS-LD clump by European P under
the primary and four sensitivity settings defined in M03. Break exact P ties
by chromosome, position, then normalized variant key.

At K = 100, 500, 1000, and 2000, report matching-direction counts and
proportions, two-sided 95% Clopper–Pearson intervals, and a one-sided exact
binomial test against 0.5. The four-threshold benchmark is 0.0125.

The weighted directional statistic uses `sign=+1` for concordant and `-1` for
discordant, `weight=-log10(European P clipped at 1e-300)`, and
`Z=sum(sign*weight)/sqrt(sum(weight^2))`; evaluate the positive standard-normal
tail. This is not an effect-size meta-analysis.

## Return

Return both panels, their machine-readable data, setting/lead counts, all
Top-K fields, directional Z/P fields, and a method-deviation log.
