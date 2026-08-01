
# M02 — Table 1: top-variant reconstruction

## Scope and level

`L2_RECONSTRUCTION_FROM_PROVIDER_OUTPUT_CHECKPOINT`. `DS_GWAS_CURRENT` supports
independent ranking, while `DS_TABLE1_CHECKPOINT` is the designated aggregate
checkpoint for exact case allele count/frequency and control frequency. Raw
control genotypes are outside Phase 5C scope.

## Operation

Use the current artifact status/filter field, rank eligible rows by full-
precision association P ascending, and apply deterministic chromosome,
position, then normalized variant-key tie breaking. Join exact aggregate
frequency/count fields from `DS_TABLE1_CHECKPOINT`; do not infer counts from
rounded frequencies. Preserve the current row order and column semantics.

Display P values with three significant digits. Display beta, standard error,
and frequencies with three decimal places unless an exact source field is
explicitly required in the returned machine-readable table. Keep effect-allele
orientation consistent in every frequency column.

## Return

Return machine-readable Table 1, the selected-variant audit, input hashes,
rounding declaration, and deviations. Do not claim an end-to-end provider GWAS
rerun.
