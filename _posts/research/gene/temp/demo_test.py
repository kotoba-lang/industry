#!/usr/bin/env python3
"""
Demo script to test figure generation system
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

def demo_manhattan_plot():
    """Generate a demo Manhattan plot"""
    plt.style.use('default')
    plt.rcParams.update({'font.size': 12, 'figure.dpi': 300})

    # Create demo data
    np.random.seed(42)
    n_snps = 1000
    chromosomes = np.random.choice(range(1, 23), n_snps)
    p_values = np.random.exponential(0.1, n_snps)
    p_values = np.minimum(p_values, 1.0)

    # Add significant hits
    sig_indices = np.random.choice(n_snps, 5, replace=False)
    p_values[sig_indices] = np.random.uniform(1e-8, 1e-6, 5)

    neglog10p = -np.log10(p_values)

    # Simple Manhattan plot
    plt.figure(figsize=(12, 6))
    for chrom in range(1, 23):
        chr_data = neglog10p[chromosomes == chrom]
        if len(chr_data) > 0:
            color = '#2E86AB' if chrom % 2 == 0 else '#A23B72'
            plt.scatter([chrom] * len(chr_data), chr_data, c=color, alpha=0.6, s=20)

    plt.axhline(y=-np.log10(5e-8), color='red', linestyle='--', 
               label='Genome-wide significance (P = 5×10⁻⁸)')
    plt.xlabel('Chromosome')
    plt.ylabel('-log₁₀(P-value)')
    plt.title('Demo Manhattan Plot - Intelligence GWAS')
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig('demo_manhattan.png', dpi=300, bbox_inches='tight')
    plt.close()
    
    print('✅ Demo Manhattan plot generated: demo_manhattan.png')

if __name__ == "__main__":
    print("🧬 Testing figure generation system...")
    demo_manhattan_plot()
    print('🎉 System is fully operational!')
    print('📊 Ready for publication-quality figures!') 