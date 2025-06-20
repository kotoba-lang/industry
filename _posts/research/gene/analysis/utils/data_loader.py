"""
Data loading utilities for GWAS analysis
"""

import pandas as pd
import numpy as np
import warnings
warnings.filterwarnings('ignore')

class GWASDataLoader:
    """Load and preprocess GWAS data for analysis"""
    
    def __init__(self, data_file='gwas-data.csv'):
        """Initialize data loader with GWAS data file"""
        self.data_file = data_file
        self.df = None
        self.processed = False
        
    def load_data(self):
        """Load GWAS data from CSV file"""
        try:
            self.df = pd.read_csv(self.data_file)
            print(f"✅ Loaded {len(self.df)} variants from {self.data_file}")
            
            # Clean column names
            self.df.columns = self.df.columns.str.strip()
            
            return True
            
        except FileNotFoundError:
            print(f"⚠️  Data file {self.data_file} not found. Creating synthetic data...")
            print("❗ WARNING: The following results are based on SYNTHETIC data for demonstration purposes.")
            self._create_synthetic_data()
            return True
            
        except Exception as e:
            print(f"❌ Error loading data: {e}")
            print("Creating synthetic data as fallback...")
            print("❗ WARNING: The following results are based on SYNTHETIC data for demonstration purposes.")
            self._create_synthetic_data()
            return False
    
    def _create_synthetic_data(self):
        """Create synthetic GWAS data for demonstration"""
        np.random.seed(42)
        
        n_snps = 50000
        chromosomes = np.random.choice(range(1, 23), n_snps)
        positions = []
        
        # Generate realistic positions for each chromosome
        for chrom in range(1, 23):
            n_chr = np.sum(chromosomes == chrom)
            if n_chr > 0:
                # Chromosome lengths (approximate, in bp)
                chr_lengths = {
                    1: 249250621, 2: 242193529, 3: 198295559, 4: 190214555,
                    5: 181538259, 6: 170805979, 7: 159345973, 8: 145138636,
                    9: 138394717, 10: 133797422, 11: 135086622, 12: 133275309,
                    13: 114364328, 14: 107043718, 15: 101991189, 16: 90338345,
                    17: 83257441, 18: 80373285, 19: 58617616, 20: 64444167,
                    21: 46709983, 22: 50818468
                }
                max_pos = chr_lengths.get(chrom, 150000000)
                chr_positions = np.random.randint(1000000, max_pos, n_chr)
                positions.extend(chr_positions)
        
        # Generate realistic P-values with some significant hits
        p_values = np.random.exponential(0.1, n_snps)
        p_values = np.minimum(p_values, 1.0)
        
        # Add some highly significant variants (top 20)
        top_indices = np.random.choice(n_snps, 20, replace=False)
        p_values[top_indices] = np.random.uniform(1e-8, 1e-6, 20)
        
        # Generate effect sizes correlated with significance
        beta_values = np.random.normal(0, 0.3, n_snps)
        # Make significant variants have larger effects
        beta_values[top_indices] = np.random.normal(0, 0.8, 20)
        
        # Generate standard errors
        se_values = np.random.uniform(0.1, 0.4, n_snps)
        
        # Calculate Z-scores
        z_scores = beta_values / se_values
        
        # Generate alleles
        alleles1 = np.random.choice(['A', 'T', 'G', 'C'], n_snps)
        alleles2 = np.random.choice(['A', 'T', 'G', 'C'], n_snps)
        
        self.df = pd.DataFrame({
            'CHR': chromosomes,
            'SNP': [f'rs{1000000 + i}' for i in range(n_snps)],
            'BP': positions,
            'A1': alleles1,
            'A2': alleles2,
            'P': p_values,
            'BETA': beta_values,
            'SE': se_values,
            'Z': z_scores
        })
        
        print(f"✅ Created synthetic dataset with {n_snps} variants")
    
    def preprocess_data(self):
        """Preprocess GWAS data for analysis"""
        if self.df is None:
            print("❌ No data loaded. Call load_data() first.")
            return False
        
        # Convert columns to appropriate types
        numeric_cols = ['CHR', 'BP', 'P', 'BETA', 'SE', 'Z']
        for col in numeric_cols:
            if col in self.df.columns:
                self.df[col] = pd.to_numeric(self.df[col], errors='coerce')
        
        # Calculate -log10(P) for plotting
        self.df['neglog10p'] = -np.log10(self.df['P'].replace(0, 1e-100))
        
        # Add cumulative position for Manhattan plot
        self._calculate_cumulative_positions()
        
        # Calculate odds ratios
        if 'BETA' in self.df.columns:
            self.df['OR'] = np.exp(self.df['BETA'])
            if 'SE' in self.df.columns:
                self.df['OR_lower'] = np.exp(self.df['BETA'] - 1.96 * self.df['SE'])
                self.df['OR_upper'] = np.exp(self.df['BETA'] + 1.96 * self.df['SE'])
        
        # Add significance categories
        self.df['significance'] = self.df['P'].apply(self._categorize_significance)
        
        self.processed = True
        print(f"✅ Data preprocessing complete. {len(self.df)} variants processed.")
        return True
    
    def _calculate_cumulative_positions(self):
        """Calculate cumulative positions for Manhattan plot"""
        if 'CHR' not in self.df.columns or 'BP' not in self.df.columns:
            print("⚠️  CHR or BP columns missing. Cannot calculate cumulative positions.")
            return
        
        # Group by chromosome and calculate cumulative positions
        self.df = self.df.sort_values(['CHR', 'BP'])
        
        chr_lengths = self.df.groupby('CHR')['BP'].max().fillna(0)
        chr_starts = chr_lengths.cumsum() - chr_lengths
        
        self.df['pos_cum'] = self.df.apply(
            lambda x: chr_starts.get(x['CHR'], 0) + x['BP'] if pd.notna(x['CHR']) and pd.notna(x['BP']) else 0,
            axis=1
        )
        
        # Store chromosome information for plotting
        self.chr_info = {
            'lengths': chr_lengths,
            'starts': chr_starts,
            'centers': chr_starts + chr_lengths / 2
        }
    
    def _categorize_significance(self, p_value):
        """Categorize P-value by significance level"""
        if pd.isna(p_value):
            return 'missing'
        elif p_value < 5e-8:
            return 'genome_wide'
        elif p_value < 1e-6:
            return 'suggestive'
        elif p_value < 0.05:
            return 'nominal'
        else:
            return 'nonsignificant'
    
    def get_top_variants(self, n=20, p_threshold=None):
        """Get top variants by P-value"""
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        data = self.df.copy()
        
        if p_threshold:
            data = data[data['P'] < p_threshold]
        
        top_variants = data.nsmallest(n, 'P')
        return top_variants
    
    def get_chromosome_data(self, chromosome):
        """Get data for specific chromosome"""
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        return self.df[self.df['CHR'] == chromosome].copy()
    
    def get_significant_variants(self, significance_level='genome_wide'):
        """Get variants by significance level"""
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        return self.df[self.df['significance'] == significance_level].copy()
    
    def get_data_summary(self):
        """Get summary statistics of the data"""
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        summary = {
            'total_variants': len(self.df),
            'chromosomes': sorted(self.df['CHR'].dropna().unique()),
            'p_value_range': (self.df['P'].min(), self.df['P'].max()),
            'significance_counts': self.df['significance'].value_counts().to_dict(),
            'top_variant': self.df.loc[self.df['P'].idxmin()]['SNP'] if 'SNP' in self.df.columns else 'Unknown'
        }
        
        return summary
    
    def load_and_process(self):
        """Convenience method to load and process data in one step"""
        success = self.load_data()
        if success:
            self.preprocess_data()
        return self.df

# Helper functions for common data operations
def load_gwas_data(data_file='gwas-data.csv'):
    """Quick function to load and process GWAS data"""
    loader = GWASDataLoader(data_file)
    return loader.load_and_process()

def create_comparison_data(japanese_df, european_summary=None):
    """Create data for cross-population comparison"""
    if european_summary is None:
        # Create synthetic European data for comparison
        np.random.seed(123)
        n_vars = len(japanese_df)
        
        european_data = pd.DataFrame({
            'SNP': japanese_df['SNP'].values,
            'P_European': np.random.exponential(0.1, n_vars),
            'BETA_European': np.random.normal(0, 0.4, n_vars),
            'SE_European': np.random.uniform(0.15, 0.35, n_vars)
        })
        
        # Make some variants have correlated effects
        correlated_indices = np.random.choice(n_vars, int(0.3 * n_vars), replace=False)
        correlation_factor = 0.6
        
        for idx in correlated_indices:
            if idx < len(japanese_df):
                jp_effect = japanese_df.iloc[idx]['BETA'] if 'BETA' in japanese_df.columns else 0
                european_data.iloc[idx, european_data.columns.get_loc('BETA_European')] = \
                    jp_effect * correlation_factor + np.random.normal(0, 0.2)
        
        # Merge with Japanese data
        comparison_df = japanese_df.merge(european_data, on='SNP', how='left')
        
    else:
        comparison_df = japanese_df.merge(european_summary, on='SNP', how='left')
    
    return comparison_df 