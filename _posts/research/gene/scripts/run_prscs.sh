#!/bin/bash
#
# This script is a template for running PRS-CS, a Bayesian polygenic prediction method.
# It requires PRS-CS to be installed and configured in the environment.
#
# Usage:
# ./run_prscs.sh <gwas_sumstats> <ld_ref_dir> <output_prefix>

set -e

# --- Configuration ---
PRSCS_PATH="path/to/PRS-CS/PRScs.py" # Path to the PRScs.py script
GWAS_SUMSTATS=$1 # GWAS summary statistics file (needs columns: SNP, A1, A2, BETA, P)
LD_REF_DIR=$2    # Directory containing LD reference data in MAT format (e.g., from 1000 Genomes)
OUT_PREFIX=$3    # Prefix for the output files

# --- PRS-CS Parameters ---
N_GWA=41619 # Sample size of the GWAS
N_THREADS=4   # Number of threads to use

echo "--- Running PRS-CS ---"
echo "GWAS Summary Stats: ${GWAS_SUMSTATS}"
echo "LD Reference: ${LD_REF_DIR}"
echo "Output Prefix: ${OUT_PREFIX}"

python ${PRSCS_PATH} \\
    --ref_dir=${LD_REF_DIR} \\
    --bim_prefix=${LD_REF_DIR}/ldblk_1kg_eur \\ # Assumes EUR reference, adjust if needed
    --sst_file=${GWAS_SUMSTATS} \\
    --n_gwas=${N_GWA} \\
    --out_dir=${OUT_PREFIX} \\
    --chrom=all \\
    --n_threads=${N_THREADS}

echo "--- PRS-CS execution finished ---"
echo "Posterior effect sizes are saved in ${OUT_PREFIX}" 