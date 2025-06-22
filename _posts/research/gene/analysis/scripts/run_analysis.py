#!/usr/bin/env python3
"""
Refactored Main GWAS Analysis Pipeline

This script serves as the single entry point for the entire analysis.
It orchestrates the following steps:
1.  Loads raw Japanese (TSV) and European (VCF) data.
2.  Processes and calculates necessary statistics (Z-scores, P-values).
3.  Merges the datasets into a single, unified Parquet file, which becomes the
    single source of truth for all downstream tasks.
4.  Loads the unified data from the Parquet file.
5.  Generates all manuscript figures (1-5) and summary tables.

This centralized approach ensures consistency and reproducibility.
"""
import pandas as pd
import numpy as np
import gzip
from pathlib import Path
from scipy.stats import norm
import warnings
import sys

# Suppress pandas warnings and set up path
warnings.filterwarnings('ignore', category=pd.errors.ParserWarning)
sys.path.append(str(Path(__file__).parent))
from generate_figures import GWASFigureGenerator

def load_japanese_data(path: Path) -> pd.DataFrame:
    """Load and preprocess the Japanese High-IQ GWAS data from TSV."""
    print(f"Loading Japanese data from {path.name}...")
    df = pd.read_csv(path, sep='\\t')
    df = df.rename(columns={'P': 'P_jp', 'BETA': 'BETA_jp', 'Z': 'Z_jp', 'A1': 'A1_jp', 'A2': 'A2_jp'})
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
        
        df['P_eu'] = np.nan
        p_mask = df['Z_eu'].notna()
        df.loc[p_mask, 'P_eu'] = 2 * (1 - norm.cdf(np.abs(df.loc[p_mask, 'Z_eu'])))
        print(f"Successfully calculated P-values for {df['P_eu'].notna().sum()} variants.")
    return df

def create_unified_dataset(jp_path: Path, eu_path: Path, output_path: Path):
    """Orchestrates data loading, processing, and saving to a unified Parquet file."""
    print("🧬 Step 1: Creating a unified summary file...")
    jp_df = load_japanese_data(jp_path)
    eu_df = load_european_data(eu_path)

    print("\\n--- Merging Datasets ---")
    jp_cols = ['SNP', 'CHR', 'BP', 'A1_jp', 'A2_jp', 'P_jp', 'Z_jp', 'BETA_jp']
    eu_cols = ['SNP', 'P_eu', 'Z_eu', 'BETA_eu']
    merged_df = pd.merge(jp_df[jp_cols], eu_df[eu_cols].dropna(subset=['Z_eu']), on='SNP')
    print(f"✅ Merged datasets on SNP ID. Found {len(merged_df)} common variants.")

    merged_df.to_parquet(output_path, index=False, engine='pyarrow')
    print(f"\\n🎉 Unified summary file created successfully at: {output_path}")

def run_downstream_analysis(unified_file_path: Path, output_dir: Path):
    """Runs all figure and table generation from the unified Parquet file."""
    print("\\n🚀 Step 2: Running Downstream Analysis and Figure Generation...")
    
    print(f"📊 Loading unified data from {unified_file_path}")
    df = pd.read_parquet(unified_file_path)

    jp_df = df[['SNP', 'CHR', 'BP', 'P_jp', 'Z_jp', 'A1_jp', 'A2_jp']].copy()
    jp_df.rename(columns={'P_jp': 'P', 'Z_jp': 'Z', 'A1_jp': 'A1', 'A2_jp': 'A2'}, inplace=True)
    eu_df = df[['SNP', 'P_eu', 'Z_eu']].copy()
    eu_df.rename(columns={'P_eu': 'P', 'Z_eu': 'Z'}, inplace=True)
    
    print("🔧 Preprocessing data for plotting...")
    jp_df['neglog10p'] = -np.log10(jp_df['P'].replace(0, 1e-300))
    jp_df.dropna(subset=['CHR'], inplace=True)
    jp_df['CHR'] = jp_df['CHR'].astype(int)
    jp_df = jp_df.sort_values(['CHR', 'BP'])
    chr_lengths = jp_df.groupby('CHR')['BP'].max()
    chr_starts = chr_lengths.cumsum() - chr_lengths
    jp_df['pos_cum'] = 0
    for chr_id in chr_starts.index:
        mask = jp_df['CHR'] == chr_id
        jp_df.loc[mask, 'pos_cum'] = chr_starts[chr_id] + jp_df.loc[mask, 'BP']
    print("✅ Data ready for figure generation.")

    print("\\n🎨 Generating Publication Figures...")
    fig_generator = GWASFigureGenerator(
        primary_df=jp_df,
        comparison_df=eu_df,
        primary_trait_name='Japanese High-IQ',
        comparison_trait_name='European Intelligence'
    )
    fig_generator.generate_all_figures(output_dir)

    print("\\n📋 Generating Summary Table...")
    top_variants = jp_df.sort_values('P').head(20)
    top_variants_path = output_dir / "Table1_Top_Variants_Japanese.csv"
    top_variants.to_csv(top_variants_path, index=False)
    print(f"✅ Top variants table saved to {top_variants_path}")

def main():
    """Main function to run the entire refactored pipeline."""
    # Define paths relative to the script's location
    script_dir = Path(__file__).parent
    output_dir = script_dir / "output"
    output_dir.mkdir(exist_ok=True)
    
    jp_path = script_dir / '../../manuscript/data/data.tsv'
    eu_path = script_dir / '../../reference_data/Savage2018_Intelligence_GWAS_European_OpenGWAS.vcf.gz'
    unified_file_path = output_dir / "unified_gwas_summary.parquet"

    # Step 1: Create the unified dataset
    create_unified_dataset(jp_path, eu_path, unified_file_path)
    
    # Step 2: Run all downstream analyses from the unified file
    run_downstream_analysis(unified_file_path, output_dir)
    
    print("\\n🎉🎉🎉 Complete analysis pipeline finished successfully! 🎉🎉🎉")
    print(f"📁 All results saved in: {output_dir}")

if __name__ == "__main__":
    main() 