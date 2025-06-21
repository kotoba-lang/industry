import pandas as pd
import gseapy as gp
from gseapy.plot import barplot, dotplot
import matplotlib.pyplot as plt
import os

def run_enrichment_analysis(gwas_file, output_dir):
    """
    Runs over-representation analysis on a list of significant genes from a GWAS file.
    
    Args:
        gwas_file (str): Path to the GWAS summary statistics CSV file.
        output_dir (str): Directory to save the results.
    """
    print(f"Loading GWAS data from {gwas_file}...")
    gwas_df = pd.read_csv(gwas_file)
    
    # --- Step 1: Get a list of significant genes ---
    # We will use SNP IDs as proxies for genes if gene mapping is not readily available.
    # This is a simplification; a full analysis would map SNPs to genes.
    
    significance_threshold = 1e-5
    significant_snps = gwas_df[gwas_df['P'] < significance_threshold]
    
    if significant_snps.empty:
        print("No significant SNPs found at the threshold. Aborting analysis.")
        return
        
    # Use the 'SNP' column which contains rsIDs.
    # enrichr can sometimes resolve these, or we treat them as a list of loci.
    gene_list = significant_snps['SNP'].dropna().unique().tolist()
    print(f"Found {len(gene_list)} unique significant SNPs (p < {significance_threshold}) to test.")
    
    # --- Step 2: Run enrichment analysis using gseapy ---
    # We will query the GO Biological Process, KEGG, and Reactome databases.
    gene_sets = ['GO_Biological_Process_2021', 'KEGG_2021_Human', 'Reactome_2022']
    
    print("Running enrichment analysis with gseapy on Enrichr...")
    try:
        enr = gp.enrichr(
            gene_list=gene_list,
            gene_sets=gene_sets,
            organism='human',
            outdir=os.path.join(output_dir, 'enrichr_results'),
            cutoff=0.05 # P-value cutoff
        )
        
        print("Enrichment analysis complete.")
        
        # --- Step 3: Visualize the results ---
        if enr.results is not None and not enr.results.empty:
            print("Plotting results...")
            
            # Create a dotplot for the top 10 results from each database
            ax = dotplot(
                enr.results,
                column="Adjusted P-value",
                x='Gene_set',
                size=10,
                top_term=10,
                figsize=(10, 15),
                title="Enrichment Analysis of Top GWAS Loci",
                xticklabels_rot=45,
                show_ring=True
            )
            
            plot_path = os.path.join(output_dir, "Supplementary_Figure_Pathway_Enrichment_GSEApy.png")
            plt.savefig(plot_path, dpi=300, bbox_inches='tight')
            print(f"Saved enrichment plot to {plot_path}")
            
            # Save full results to a CSV
            results_path = os.path.join(output_dir, "enrichment_analysis_results.csv")
            enr.results.to_csv(results_path, index=False)
            print(f"Saved full enrichment results to {results_path}")
            
        else:
            print("No enrichment results to plot.")
            
    except Exception as e:
        print(f"An error occurred during enrichment analysis: {e}")
        print("This may be due to network issues or if Enrichr cannot map the SNP IDs.")

# Remove the main block to use this script as a module
# def main():
#     """Main function"""
#     # Assume the script is run from the project root
#     gwas_data_file = 'manuscript/data/gwas_summary_stats.csv'
#     output_directory = 'analysis/output'
    
#     if not os.path.exists(output_directory):
#         os.makedirs(output_directory)
        
#     run_enrichment_analysis(gwas_data_file, output_directory)

# if __name__ == '__main__':
#     main() 