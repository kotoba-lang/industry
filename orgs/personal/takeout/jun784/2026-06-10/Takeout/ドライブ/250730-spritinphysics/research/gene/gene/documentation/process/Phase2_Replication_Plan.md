# Phase 2: Replication Study Protocol

**Version:** 1.0  
**Date:** 2024-06-21

## 1. Introduction & Rationale

This document outlines the protocol for the Phase 2 Replication Study of the "Population-Specific Genetic Architecture of Intelligence Revealed by World-First Japanese High-IQ GWAS" project.

The initial discovery phase (Phase 1), conducted on an exploratory cohort of 91 high-IQ cases, identified 28 candidate single nucleotide polymorphisms (SNPs) potentially associated with high intelligence in the Japanese population. However, due to the critical limitations of the discovery study—most notably the extremely small sample size and severe sex bias—these findings are considered highly preliminary and require rigorous validation before any conclusions can be drawn.

The primary objective of this Phase 2 study is to test the validity of these 28 candidate associations in a new, larger, and independent cohort of Japanese individuals with high intelligence. Successful replication is the **minimum requirement** to establish the credibility of any initial findings and is a critical step towards building a robust understanding of the genetic architecture of intelligence in this population.

## 2. Study Objectives

**Primary Objective:**
*   To determine if the association between the 28 candidate SNPs (identified in Phase 1) and high intelligence (IQ ≥ 140) can be replicated in an independent Japanese cohort.

**Secondary Objectives:**
*   To obtain a more precise estimate of the effect sizes (odds ratios) for the replicated SNPs.
*   To contribute data towards a future, larger meta-analysis (Phase 3).

## 3. Candidate SNPs for Replication

The following 28 SNPs, which showed suggestive association (P < 1×10⁻⁵) in the discovery phase, will be tested for replication. The effect allele (A1) and the direction of effect (BETA) from the discovery phase are listed for one-sided testing.

| CHR | SNP                 | BP        | A1 | A2 | Discovery_P | Discovery_BETA |
|:----|:--------------------|:----------|:---|:---|:------------|:---------------|
| 19  | GSA-rs146572333     | 52703635  | A  | C  | 1.17E-08    | 1.66589        |
| 16  | GSA-rs139129152     | 77880365  | T  | C  | 8.86E-08    | 1.52259        |
| 7   | GSA-rs78137899      | 232179    | T  | C  | 1.55E-07    | 1.43142        |
| 15  | rs4396508           | 26457523  | C  | T  | 2.34E-07    | 0.762448       |
| 10  | rs17135001          | 3327931   | A  | C  | 2.44E-07    | 1.14489        |
| 3   | rs7653468           | 61046955  | C  | T  | 3.15E-07    | 1.56621        |
| 15  | rs60409151          | 26455100  | T  | C  | 6.45E-07    | 0.748176       |
| 2   | rs28478647          | 110382715 | A  | G  | 8.09E-07    | 1.35989        |
| 4   | rs17675581          | 5080187   | G  | A  | 8.57E-07    | 1.16188        |
| 4   | GSA-rs28729447      | 5064533   | C  | A  | 9.13E-07    | 1.25294        |
| 15  | rs8026670           | 26469100  | G  | A  | 1.35E-06    | 0.742969       |
| 10  | rs79938208          | 117737634 | A  | G  | 1.67E-06    | 1.14965        |
| 20  | rs139501598         | 38805699  | C  | T  | 1.81E-06    | 1.19036        |
| 4   | GSA-rs10032565      | 5082734   | T  | G  | 2.48E-06    | 1.13969        |
| 15  | GSA-rs2217865       | 26446728  | T  | C  | 2.49E-06    | 0.71306        |
| 15  | GSA-rs79729092      | 26445261  | G  | A  | 2.57E-06    | 0.711841       |
| 11  | rs8177374           | 126162843 | T  | C  | 3.02E-06    | 1.27943        |
| 22  | GSA-rs5996564       | 23843439  | T  | G  | 3.30E-06    | 0.931466       |
| 11  | GSA-rs601580        | 126191074 | T  | C  | 3.77E-06    | 1.20293        |
| 14  | seq-rs376128944     | 55433073  | A  | C  | 4.25E-06    | 1.0417         |
| 10  | rs56939961          | 3772777   | G  | A  | 5.42E-06    | 1.35951        |
| 1   | rs749902            | 117660754 | T  | C  | 5.92E-06    | 1.51219        |
| 17  | rs8074490           | 14529338  | A  | G  | 7.14E-06    | 0.662377       |
| 4   | rs6847646           | 5074816   | G  | T  | 7.16E-06    | 1.14478        |
| 8   | rs78080264          | 4932342   | C  | T  | 7.77E-06    | 0.960288       |
| 14  | GSA-rs75790544      | 55445126  | A  | C  | 8.87E-06    | 1.02242        |
| 22  | GSA-rs114733590     | 23842657  | G  | T  | 9.26E-06    | 0.999596       |

## 4. Study Design & Cohort Requirements

### 4.1. Study Design
*   A case-control genetic association study.

### 4.2. Replication Cohort (Cases)
*   **Inclusion Criteria:**
    *   Unrelated individuals of confirmed Japanese ancestry.
    *   Verified high cognitive ability, defined as a standardized IQ score ≥ 140.
    *   Informed consent for genetic analysis.
*   **Exclusion Criteria:**
    *   Individuals included in the Phase 1 discovery cohort.
*   **Target Sample Size:**
    *   **Minimum:** 300 individuals.
    *   **Ideal:** 500 individuals or more.
*   **Sex Representation:** A balanced sex ratio (e.g., 40-60% for each sex) is required to mitigate the bias observed in Phase 1.

### 4.3. Control Cohort
*   The existing control cohort of ~40,000 individuals from the Gene Quest and Euglena MyHealth services will be used for comparison, ensuring consistency with the discovery phase.

## 5. Statistical Analysis Plan

### 5.1. Genotyping and Quality Control
*   Candidate SNPs will be genotyped in the replication cohort using a reliable platform (e.g., custom array or imputation from a genome-wide array).
*   Standard sample and variant QC will be applied.

### 5.2. Association Testing
*   For each of the 28 SNPs, a logistic regression analysis will be performed to test for association with high-IQ case-control status.
*   The model will be adjusted for the same covariates as Phase 1: Sex and the first 10 principal components of genetic ancestry.

### 5.3. Hypothesis Testing
*   **Hypothesis:** The effect allele (A1) identified in the discovery phase is associated with increased odds of being in the high-IQ group.
*   **Test:** A **one-sided test** will be used for each SNP, consistent with the direction of effect (BETA) observed in the discovery phase.
*   **Significance Threshold:** To account for multiple testing of 28 hypotheses, a **Bonferroni-corrected significance threshold** will be applied.
    *   `p < 0.05 / 28 ≈ 0.00179`

## 6. Criteria for Successful Replication

A candidate SNP will be considered successfully replicated if it meets **both** of the following criteria:

1.  **Statistical Significance:** The one-sided P-value from the replication cohort analysis is less than the Bonferroni-corrected threshold of **0.00179**.
2.  **Consistent Direction of Effect:** The direction of the effect (i.e., the sign of the beta coefficient) is the same as that observed in the discovery study.

## 7. Next Steps
*   Upon successful replication of one or more SNPs, we will proceed to Phase 3: Expansion & Meta-Analysis, where the discovery and replication cohorts will be combined to refine effect size estimates and potentially discover novel loci.
*   If no SNPs replicate, the initial findings will be considered false positives, and the need for a much larger, new discovery cohort will be emphasized. 