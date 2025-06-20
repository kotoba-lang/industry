import pandas as pd
import numpy as np
import gzip
import os
import csv

def vcf_to_dataframe_manual(vcf_path):
    """
    Parses a VCF file containing GWAS summary statistics manually and returns a pandas DataFrame.

    Args:
        vcf_path (str): Path to the VCF file (can be gzipped).

    Returns:
        pd.DataFrame: A DataFrame with GWAS summary statistics.
    """
    records = []
    open_func = gzip.open if vcf_path.endswith('.gz') else open

    with open_func(vcf_path, 'rt') as f:
        for line in f:
            if line.startswith('##'):
                continue
            if line.startswith('#CHROM'):
                header = line.strip().split('\t')
                sample_id = header[-1]
                continue
            
            fields = line.strip().split('\t')
            if len(fields) < 10:
                continue

            chrom = fields[0]
            pos = int(fields[1])
            snp_id = fields[2]
            ref = fields[3]
            alt = fields[4]
            
            format_keys = fields[8].split(':')
            format_values = fields[9].split(':')
            
            data = dict(zip(format_keys, format_values))
            
            es = data.get('ES')
            se = data.get('SE')
            lp = data.get('LP')

            if es is not None and se is not None and lp is not None:
                try:
                    p_value = 10**(-float(lp))
                    records.append({
                        'CHR': chrom,
                        'BP': pos,
                        'SNP': snp_id,
                        'A1': ref,
                        'A2': alt,
                        'BETA': float(es),
                        'SE': float(se),
                        'P': p_value
                    })
                except (ValueError, TypeError):
                    # Skip if conversion to float fails
                    continue

    df = pd.DataFrame(records)
    return df

def main():
    """
    Main function to process the VCF and save it as a CSV file.
    """
    # Relative path from the script's location
    vcf_file = '../../manuscript/data/Savage2018_Intelligence_GWAS_European_OpenGWAS.vcf.gz'
    output_csv = '../../manuscript/data/gwas_summary_stats.csv'
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    abs_vcf_file = os.path.join(script_dir, vcf_file)
    abs_output_csv = os.path.join(script_dir, output_csv)
    
    print(f"Reading VCF file from: {abs_vcf_file}")
    
    try:
        gwas_df = vcf_to_dataframe_manual(abs_vcf_file)
        
        print(f"Successfully parsed {len(gwas_df)} variants.")
        
        gwas_df.to_csv(abs_output_csv, index=False)
        print(f"GWAS summary statistics saved to: {abs_output_csv}")
        
        print("\nPreview of the first 5 rows:")
        print(gwas_df.head())

    except FileNotFoundError:
        print(f"Error: VCF file not found at {abs_vcf_file}")
    except Exception as e:
        print(f"An error occurred during VCF processing: {e}")

if __name__ == '__main__':
    main() 