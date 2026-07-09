![Figure 2: High-Performance GWAS Analysis Pipeline Architecture.](./Figure2_Pipeline_Architecture.png)
*Figure 2: The diagram illustrates the architecture of the high-performance analysis pipeline. Raw GWAS summary statistics are ingested, converted to the efficient Parquet format, and loaded into a DuckDB instance. This enables rapid, interactive SQL queries and analysis using Python/R, leading to accelerated generation of results, figures, and reports.*

2.5. Cross-Population Comparison
However, such large effects are unusual for polygenic traits and require rigorous replication.

![Figure 1: Manhattan and QQ plots for the Japanese high-IQ GWAS.](./Figure1_Manhattan_QQ.png)
*Figure 1: The Manhattan plot (top) displays the -log10(P-values) for all tested variants. The red line indicates the threshold for genome-wide significance (P = 5×10⁻⁸), and the blue line indicates the threshold for suggestive significance (P = 1×10⁻⁵). The QQ plot (bottom) shows some deviation from the null, but should be interpreted with caution given the small sample size.*

Table 1 lists the top associated variants from the GWAS.
The apparent complete specificity is more likely a reflection of **low statistical power and data limitations** rather than true biological differences.

![Figure 3: Cross-population comparison of genetic effects.](./Figure2_Cross_Population.png)
*Figure 3: Scatter plot comparing the effect sizes (Z-scores) of intelligence-associated variants between the Japanese high-IQ GWAS (Y-axis) and a large-scale European GWAS (X-axis). Each point represents a variant. The apparent lack of correlation is suggestive but inconclusive due to the low power of the Japanese GWAS.*

3.3. Implications for Polygenic Score Transferability (Hypothetical)
However, any robust assessment of transferability requires a well-powered GWAS and comprehensive cross-population datasets.

![Figure 4: Replication analysis of top Japanese variants.](./Figure5_Replication_Analysis.png)
*Figure 4: This plot shows the replication status of the top variants identified in the Japanese GWAS within a European population cohort. The results highlight the apparent limited transferability of findings, but these results are tentative and require validation.*

3.4. Biological Implications (Highly Speculative)
**Real Japanese High-IQ GWAS Results:**
- [Figure 1: Japanese High-IQ Manhattan & QQ Plots (PNG)](./Figure1_Manhattan_QQ.png) | [PDF](../analysis/output/Figure1_Manhattan_QQ.pdf)
- [Figure 2: High-Performance GWAS Analysis Pipeline Architecture (PNG)](./Figure2_Pipeline_Architecture.png)
- [Figure 3: Cross-Population Genetic Architecture (PNG)](./Figure2_Cross_Population.png) | [PDF](../analysis/output/Figure2_Cross_Population.pdf)
- [Figure 4: Effect Size Distribution (PNG)](./Figure3_Effect_Sizes.png)
- [Figure 5: Replication Analysis (PNG)](./Figure5_Replication_Analysis.png)
- [Table 1: Top Japanese Intelligence-Associated Variants (CSV)](../analysis/output/Table1_Top_Variants_Japanese.csv)
