
# M07 — current Supplementary Table reconstruction

## Scope and level

Only current Supplementary Table S1 is in scope. It is
`L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT`. Historical supplementary
tables and removed figures are not part of the Phase 5C claim set.

## Operation

Start from `DS_TABLE_S1_CURRENT` and verify row identity/order against the
current primary LD checkpoint and population-frequency source. Preserve all
columns, GRCh37 coordinates, effect-allele orientation, locus lead/member
mapping, category labels, missing-value representation, and notes. Do not add
rows from an older 18-locus or pre-exclusion source.

## Return

Return a machine-readable TSV with the same column order, row-selection audit,
LD lead/member counts, category counts, source hashes, and deviations.
