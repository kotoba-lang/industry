import pandas as pd
import numpy as np
import gzip
from pathlib import Path
from scipy.stats import norm

def load_japanese_data(path: Path) -> pd.DataFrame:
    """Load and preprocess the Japanese High-IQ GWAS data from TSV."""
    print(f"Loading Japanese data from {path.name}...")
    df = pd.read_csv(path, sep='\\t')
    df = df.rename(columns={
        'P': 'P_jp', 
        'BETA': 'BETA_jp', 
        'Z': 'Z_jp',
        'A1': 'A1_jp',
        'A2': 'A2_jp'
    })
    print(f"Loaded {len(df)} records.")
    return df

def load_european_data(path: Path) -> pd.DataFrame:
    """Load and preprocess the European Intelligence GWAS data from VCF.GZ."""
    print(f"Loading European data from {path.name}...")
    with gzip.open(path, 'rt') as f:
        lines = [l for l in f if not l.startswith('##')]
    
    df = pd.read_csv(
        pd.io.common.StringIO(''.join(lines)),
        sep='\\t'
    ).rename(columns={'#CHROM': 'CHR', 'ID': 'SNP', 'POS': 'BP', 'REF': 'A2_eu', 'ALT': 'A1_eu'})
    print(f"Loaded {len(df)} records from VCF.")

    # Extract ES and SE from the last two columns (FORMAT and sample data)
    if 'FORMAT' in df.columns and df.columns[-1] != 'FORMAT':
        sample_col_name = df.columns[-1]
        print(f"Extracting ES and SE from FORMAT and '{sample_col_name}' columns...")

        def get_format_field(row, field_name):
            try:
                format_order = row['FORMAT'].split(':')
                sample_values = row[sample_col_name].split(':')
                field_index = format_order.index(field_name)
                return sample_values[field_index]
            except (ValueError, IndexError):
                return None

        df['BETA_eu'] = pd.to_numeric(df.apply(lambda row: get_format_field(row, 'ES'), axis=1), errors='coerce')
        df['SE_eu'] = pd.to_numeric(df.apply(lambda row: get_format_field(row, 'SE'), axis=1), errors='coerce')
        
        valid_mask = (df['SE_eu'] != 0) & df['BETA_eu'].notna() & df['SE_eu'].notna()
        df['Z_eu'] = np.nan
        df.loc[valid_mask, 'Z_eu'] = df.loc[valid_mask, 'BETA_eu'] / df.loc[valid_mask, 'SE_eu']
        print(f"Successfully calculated Z-scores for {df['Z_eu'].notna().sum()} variants.")

        # Calculate P-value from Z-score
        df['P_eu'] = np.nan
        p_mask = df['Z_eu'].notna()
        df.loc[p_mask, 'P_eu'] = 2 * (1 - norm.cdf(np.abs(df.loc[p_mask, 'Z_eu'])))
        print(f"Successfully calculated P-values for {df['P_eu'].notna().sum()} variants.")
    else:
        print("⚠️ INFO column with ES/SE not found.")

    return df

def main():
    """
    Creates a single, reliable Parquet file from the primary GWAS datasets
    to serve as the source-of-truth for all downstream analysis and figures.
    """
    print("🧬 Creating a unified summary file for all analyses...")
    
    # Define file paths
    jp_path = Path('../../manuscript/data/data.tsv')
    eu_path = Path('../../reference_data/Savage2018_Intelligence_GWAS_European_OpenGWAS.vcf.gz')
    output_dir = Path("output")
    output_dir.mkdir(exist_ok=True)
    output_path = output_dir / "unified_gwas_summary.parquet"

    # Load and preprocess both datasets
    jp_df = load_japanese_data(jp_path)
    eu_df = load_european_data(eu_path)

    # Merge the two DataFrames on the 'SNP' identifier
    print("\n--- Merging Datasets ---")
    jp_cols = ['SNP', 'CHR', 'BP', 'A1_jp', 'A2_jp', 'P_jp', 'Z_jp', 'BETA_jp']
    eu_cols = ['SNP', 'P_eu', 'Z_eu', 'BETA_eu']
    
    merged_df = pd.merge(
        jp_df[jp_cols], 
        eu_df[eu_cols].dropna(subset=['Z_eu']), # Only merge SNPs that were successfully processed
        on='SNP'
    )
    print(f"✅ Merged datasets on SNP ID. Found {len(merged_df)} common variants.")

    # Save the unified DataFrame to a Parquet file
    merged_df.to_parquet(output_path, index=False, engine='pyarrow')
    
    print(f"\n🎉 Unified summary file created successfully at: {output_path}")
    print("This file will be the single source of truth for all subsequent analyses.")

if __name__ == "__main__":
    main() 