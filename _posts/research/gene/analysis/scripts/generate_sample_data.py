#!/usr/bin/env python3
"""
Sample Data Generator for Japanese Intelligence GWAS

This script generates synthetic GWAS data for testing purposes
when the full external datasets are not available.
"""

import os
import sys
import pandas as pd
import numpy as np
import gzip
from pathlib import Path

# Add utils to path
sys.path.append(os.path.join(os.path.dirname(__file__), '..', 'utils'))

def generate_sample_gwas_data(trait_name, n_snps=10000, n_samples=50000):
    """
    Generate synthetic GWAS summary statistics
    
    Args:
        trait_name: Name of the trait (e.g., 'Intelligence')
        n_snps: Number of SNPs to generate
        n_samples: Sample size
    
    Returns:
        DataFrame with GWAS summary statistics
    """
    np.random.seed(42)  # For reproducibility
    
    # Generate SNP IDs
    snp_ids = [f"rs{i+1000000}" for i in range(n_snps)]
    
    # Generate chromosomes (1-22)
    chromosomes = np.random.choice(range(1, 23), n_snps)
    
    # Generate positions
    positions = np.random.randint(1000000, 200000000, n_snps)
    
    # Generate alleles
    alleles = ['A', 'T', 'C', 'G']
    a1 = np.random.choice(alleles, n_snps)
    a2 = np.random.choice(alleles, n_snps)
    
    # Ensure a1 != a2
    for i in range(n_snps):
        while a1[i] == a2[i]:
            a2[i] = np.random.choice(alleles)
    
    # Generate effect sizes (mostly small effects, few large)
    betas = np.random.normal(0, 0.02, n_snps)
    
    # Add some larger effects for interesting variants
    large_effect_indices = np.random.choice(n_snps, 50, replace=False)
    betas[large_effect_indices] = np.random.normal(0, 0.1, 50)
    
    # Generate standard errors
    ses = np.random.uniform(0.01, 0.05, n_snps)
    
    # Calculate Z-scores and P-values
    z_scores = betas / ses
    p_values = 2 * (1 - scipy.stats.norm.cdf(np.abs(z_scores)))
    
    # For demonstration, make one variant genome-wide significant
    if trait_name == 'Intelligence':
        p_values[0] = 1e-9
        betas[0] = 0.15
        z_scores[0] = betas[0] / ses[0]
    
    # Create DataFrame
    gwas_data = pd.DataFrame({
        'SNP': snp_ids,
        'CHR': chromosomes,
        'BP': positions,
        'A1': a1,
        'A2': a2,
        'N': n_samples,
        'BETA': betas,
        'SE': ses,
        'Z': z_scores,
        'P': p_values
    })
    
    return gwas_data

def create_sample_datasets():
    """Create sample datasets for testing"""
    
    print("🧪 Generating sample GWAS datasets...")
    
    # Create directories
    os.makedirs('dataset/sumstats', exist_ok=True)
    os.makedirs('analysis/scripts/output', exist_ok=True)
    
    # Define traits to generate
    traits = {
        'PASS_Intelligence_SavageJansen2018': {'n_snps': 15000, 'n_samples': 269867},
        'PASS_Height1': {'n_snps': 12000, 'n_samples': 700000},
        'PASS_BMI1': {'n_snps': 10000, 'n_samples': 681275},
        'PASS_Schizophrenia': {'n_snps': 8000, 'n_samples': 105318}
    }
    
    for trait_name, params in traits.items():
        print(f"📊 Generating {trait_name}...")
        
        # Generate data
        gwas_data = generate_sample_gwas_data(
            trait_name.split('_')[1] if '_' in trait_name else trait_name,
            params['n_snps'], 
            params['n_samples']
        )
        
        # Save as compressed file
        output_file = f"dataset/sumstats/{trait_name}.sumstats.gz"
        
        with gzip.open(output_file, 'wt') as f:
            gwas_data.to_csv(f, sep='\t', index=False)
        
        print(f"✅ Created {output_file} ({len(gwas_data):,} variants)")
    
    # Create metadata file
    metadata = {
        'sample_data': True,
        'generated_at': pd.Timestamp.now().isoformat(),
        'description': 'Synthetic GWAS data for testing purposes',
        'traits': list(traits.keys()),
        'note': 'This is not real GWAS data - for testing only'
    }
    
    import json
    with open('dataset/gwas_data_metadata.json', 'w') as f:
        json.dump(metadata, f, indent=2)
    
    print("📝 Created metadata file")
    
    # Try to build database
    try:
        from duckdb_manager import GWASDuckDBManager
        print("🗄️  Building sample DuckDB database...")
        
        manager = GWASDuckDBManager('dataset/')
        manager.build_database_from_sumstats()
        
        print("✅ Sample database created successfully!")
        
        # Show database info
        info = manager.get_database_info()
        print(f"📊 Database contains {info['total_traits']} traits")
        
    except ImportError:
        print("⚠️  Could not import DuckDB manager - database not created")
        print("💡 Run this after installing dependencies")
    
    except Exception as e:
        print(f"⚠️  Database creation failed: {e}")
    
    print("\n✅ Sample data generation complete!")
    print("🚀 You can now run analysis with sample data:")
    print("   python3 analysis/scripts/run_analysis.py --sample-mode")

if __name__ == "__main__":
    # Import scipy here to handle missing dependency gracefully
    try:
        import scipy.stats
    except ImportError:
        print("⚠️  SciPy not found - installing...")
        os.system("pip install scipy")
        import scipy.stats
    
    create_sample_datasets() 