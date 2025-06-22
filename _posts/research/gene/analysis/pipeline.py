# %% [markdown]
# # GWAS Analysis Pipeline for Population-Specific Genetic Architecture
# 
# This Jupyter-compatible Python script provides the complete, refactored analysis pipeline for the study *"Population-Specific Genetic Architecture of Intelligence Revealed by World-First Japanese High-IQ GWAS"*.
# 
# **Workflow:**
# 1.  **Data Ingestion & Unification**: Load raw data (Japanese TSV & European VCF), preprocess, and merge into a single, reliable Parquet file.
# 2.  **Analysis with DuckDB**: Demonstrate how to use DuckDB for high-performance queries directly on the unified Parquet file.
# 3.  **Figure & Table Generation**: Load the unified data to generate all figures (1-5) and summary tables presented in the manuscript.

# %%
import pandas as pd
import numpy as np
import gzip
from pathlib import Path
from scipy.stats import norm
import duckdb
import warnings
from IPython.display import display, Image

# Suppress pandas warnings
warnings.filterwarnings('ignore', category=pd.errors.ParserWarning)

# Ensure the figure generation script is importable
from generate_figures import GWASFigureGenerator

print("Libraries imported successfully.")

# %% [markdown]
# ## 1. Data Ingestion & Unification
# 
# This section consolidates the data processing. It loads the raw data files, performs the necessary calculations (like Z-scores for the European data), merges them, and saves the result as a single, efficient Parquet file. This Parquet file becomes the **single source of truth** for all subsequent steps.

# %%
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

def create_unified_dataset(base_dir: Path):
    """Main function to orchestrate data loading and unification."""
    jp_path = base_dir / 'manuscript/data/data.tsv'
    eu_path = base_dir / 'reference_data/Savage2018_Intelligence_GWAS_European_OpenGWAS.vcf.gz'
    output_dir = base_dir / "analysis/output"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "unified_gwas_summary.parquet"

    jp_df = load_japanese_data(jp_path)
    eu_df = load_european_data(eu_path)

    print("\\n--- Merging Datasets ---")
    jp_cols = ['SNP', 'CHR', 'BP', 'A1_jp', 'A2_jp', 'P_jp', 'Z_jp', 'BETA_jp']
    eu_cols = ['SNP', 'P_eu', 'Z_eu', 'BETA_eu']
    merged_df = pd.merge(jp_df[jp_cols], eu_df[eu_cols].dropna(subset=['Z_eu']), on='SNP')
    print(f"✅ Merged datasets on SNP ID. Found {len(merged_df)} common variants.")

    merged_df.to_parquet(output_path, index=False, engine='pyarrow')
    print(f"\\n🎉 Unified summary file created successfully at: {output_path}")
    return output_path

# Execute the data unification process
# Assuming the script is run from the project root
unified_file_path = create_unified_dataset(Path('..'))

# %% [markdown]
# ## 2. Analysis with DuckDB
# 
# With the unified Parquet file, we can now perform highly efficient analyses using DuckDB. DuckDB can query Parquet files directly without needing to load the entire dataset into memory.

# %%
con = duckdb.connect(database=':memory:', read_only=False)

print("Top 5 variants with the lowest P-value in the Japanese dataset:")
result_jp = con.execute(f"""
    SELECT SNP, CHR, BP, P_jp, Z_jp, P_eu, Z_eu
    FROM '{unified_file_path}'
    ORDER BY P_jp ASC
    LIMIT 5;
""").df()
display(result_jp)

print("\\nTop 5 variants with the lowest P-value in the European dataset:")
result_eu = con.execute(f"""
    SELECT SNP, CHR, BP, P_jp, Z_jp, P_eu, Z_eu
    FROM '{unified_file_path}'
    ORDER BY P_eu ASC
    LIMIT 5;
""").df()
display(result_eu)

# %% [markdown]
# ## 3. Figure & Table Generation
# 
# Now we load the unified Parquet file into pandas and use the refactored `GWASFigureGenerator` to create all the figures for the manuscript. This ensures all figures are generated from the exact same, reliable data source.

# %%
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

# Instantiate the figure generator and create all figures
output_dir = unified_file_path.parent
fig_generator = GWASFigureGenerator(
    primary_df=jp_df,
    comparison_df=eu_df,
    primary_trait_name='Japanese High-IQ',
    comparison_trait_name='European Intelligence'
)
results = fig_generator.generate_all_figures(output_dir)

from IPython.display import Image, display
for fig_name, fig_path in results.items():
    if fig_path and Path(fig_path).exists():
        print(f"\\n--- {fig_name.capitalize()} ---")
        display(Image(filename=fig_path))

# %% [markdown]
# ## 4. Summary Table Generation

# %%
print("📋 Generating Top Variants Summary Table...")
top_variants = jp_df.sort_values('P').head(20)
top_variants_path = output_dir / "Table1_Top_Variants_Japanese.csv"
top_variants.to_csv(top_variants_path, index=False)
print(f"✅ Top variants table saved to {top_variants_path}")
print("Displaying top 10 variants:")
display(top_variants.head(10))

# %% [markdown]
# ## 5. Conclusion
# 
# The analysis is complete. All data processing, analysis, and figure generation steps have been consolidated into this single notebook-style script, running from a unified, reliable Parquet file. This ensures maximum reproducibility and transparency. 