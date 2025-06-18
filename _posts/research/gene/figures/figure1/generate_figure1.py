#!/usr/bin/env python3
"""
Figure 1: Manhattan Plot and QQ Plot for Japanese Intelligence GWAS

This script generates the primary GWAS visualization showing:
- Manhattan plot of -log10(P-values) across chromosomes
- QQ plot comparing observed vs expected P-values
- Genomic inflation factor (lambda)

Author: AI Research Assistant
Date: 2024
"""

import sys
import os
sys.path.append(os.path.join(os.path.dirname(__file__), '../..'))

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from config.colors import COLORS, get_chromosome_color, get_significance_color
from config.styles import setup_publication_style, get_figure_size, add_significance_line, save_figure
from utils.data_loader import GWASDataLoader
from utils.statistics import calculate_lambda_gc, calculate_qq_expected

class Figure1Generator:
    """Generate Figure 1: Manhattan and QQ plots"""
    
    def __init__(self, data_file='../../gwas-data.csv'):
        """Initialize with GWAS data"""
        self.data_file = data_file
        self.data_loader = GWASDataLoader(data_file)
        self.df = None
        
        # Setup publication style
        setup_publication_style()
        
    def load_data(self):
        """Load and preprocess GWAS data"""
        self.df = self.data_loader.load_and_process()
        return self.df is not None
    
    def generate_manhattan_plot(self, ax):
        """Generate Manhattan plot"""
        if self.df is None:
            print("❌ No data loaded. Call load_data() first.")
            return None
        
        print("📊 Generating Manhattan plot...")
        
        # Get unique chromosomes
        chromosomes = sorted([c for c in self.df['CHR'].unique() if pd.notna(c)])
        
        # Plot points by chromosome
        for chrom in chromosomes:
            chr_data = self.df[self.df['CHR'] == chrom]
            if len(chr_data) == 0:
                continue
                
            color = get_chromosome_color(chrom)
            
            ax.scatter(chr_data['pos_cum'], chr_data['neglog10p'], 
                      c=color, alpha=0.6, s=15, edgecolors='none',
                      label=f'Chr {chrom}' if chrom <= 2 else "")
        
        # Add significance lines
        add_significance_line(ax, 'genome_wide')
        add_significance_line(ax, 'suggestive')
        
        # Customize axes
        ax.set_xlabel('Chromosome', fontweight='bold')
        ax.set_ylabel('-log₁₀(P-value)', fontweight='bold')
        ax.set_title('A. Manhattan Plot: Japanese High-IQ GWAS', 
                    fontweight='bold', fontsize=14, pad=20)
        
        # Set chromosome labels
        if hasattr(self.data_loader, 'chr_info'):
            chr_centers = []
            chr_labels = []
            for chrom in chromosomes:
                if chrom in self.data_loader.chr_info['centers']:
                    chr_centers.append(self.data_loader.chr_info['centers'][chrom])
                    chr_labels.append(str(chrom))
            
            if chr_centers:
                ax.set_xticks(chr_centers)
                ax.set_xticklabels(chr_labels)
        
        # Add legend for significance lines only
        handles, labels = ax.get_legend_handles_labels()
        # Filter to only significance lines
        sig_handles = []
        sig_labels = []
        for h, l in zip(handles, labels):
            if 'significance' in l.lower():
                sig_handles.append(h)
                sig_labels.append(l)
        
        if sig_handles:
            ax.legend(sig_handles, sig_labels, loc='upper right', fontsize=10)
        
        # Grid styling
        ax.grid(True, alpha=0.3)
        ax.set_ylim(bottom=0)
        
        # Highlight top variants
        top_variants = self.df.nsmallest(5, 'P')
        if len(top_variants) > 0:
            ax.scatter(top_variants['pos_cum'], top_variants['neglog10p'],
                      c=COLORS['significant'], s=60, edgecolors='black', 
                      linewidth=1, alpha=0.9, zorder=10)
        
        return ax
    
    def generate_qq_plot(self, ax):
        """Generate QQ plot"""
        if self.df is None:
            print("❌ No data loaded. Call load_data() first.")
            return None
        
        print("📊 Generating QQ plot...")
        
        # Get observed P-values (remove NaN and zeros)
        observed_p = self.df['P'].dropna()
        observed_p = observed_p[observed_p > 0].sort_values()
        
        if len(observed_p) == 0:
            print("⚠️  No valid P-values for QQ plot")
            return ax
        
        # Calculate expected P-values
        expected_p = calculate_qq_expected(len(observed_p))
        
        # Convert to -log10 scale
        observed_log = -np.log10(observed_p)
        expected_log = -np.log10(expected_p)
        
        # Plot QQ plot
        ax.scatter(expected_log, observed_log, 
                  color=COLORS['japanese'], alpha=0.6, s=20, 
                  edgecolors='none', label='Observed')
        
        # Add diagonal line (expected under null)
        max_val = max(expected_log.max(), observed_log.max())
        ax.plot([0, max_val], [0, max_val], 
               color='red', linestyle='--', alpha=0.8, linewidth=2,
               label='Expected (null hypothesis)')
        
        # Calculate and display lambda (genomic inflation factor)
        lambda_gc = calculate_lambda_gc(observed_p.values)
        
        # Add lambda annotation
        ax.text(0.05, 0.95, f'λ = {lambda_gc:.3f}', 
               transform=ax.transAxes, fontsize=12, fontweight='bold',
               bbox=dict(boxstyle='round,pad=0.5', facecolor='white', 
                        edgecolor='black', alpha=0.9))
        
        # Customize axes
        ax.set_xlabel('Expected -log₁₀(P-value)', fontweight='bold')
        ax.set_ylabel('Observed -log₁₀(P-value)', fontweight='bold')
        ax.set_title('B. QQ Plot: P-value Distribution', 
                    fontweight='bold', fontsize=14, pad=20)
        
        # Equal aspect ratio and limits
        ax.set_xlim(0, max_val * 1.02)
        ax.set_ylim(0, max_val * 1.02)
        ax.set_aspect('equal', adjustable='box')
        
        # Legend and grid
        ax.legend(loc='upper left', fontsize=10)
        ax.grid(True, alpha=0.3)
        
        return ax
    
    def generate_figure1(self, output_dir='../../output'):
        """Generate complete Figure 1"""
        if not self.load_data():
            print("❌ Failed to load data")
            return None
        
        print("\n" + "="*50)
        print("GENERATING FIGURE 1: MANHATTAN & QQ PLOTS")
        print("="*50)
        
        # Create figure with two subplots
        fig_size = get_figure_size('dual_vertical')
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=fig_size)
        
        # Generate Manhattan plot
        self.generate_manhattan_plot(ax1)
        
        # Generate QQ plot
        self.generate_qq_plot(ax2)
        
        # Adjust layout
        plt.tight_layout(pad=3.0)
        
        # Save figure
        png_path, pdf_path = save_figure(fig, 'Figure1_Manhattan_QQ', output_dir)
        
        # Display summary
        self.print_summary()
        
        return fig, (png_path, pdf_path)
    
    def print_summary(self):
        """Print analysis summary"""
        if self.df is None:
            return
        
        summary = self.data_loader.get_data_summary()
        
        print(f"\n📋 ANALYSIS SUMMARY:")
        print(f"   • Total variants: {summary['total_variants']:,}")
        print(f"   • Chromosomes: {len(summary['chromosomes'])}")
        print(f"   • P-value range: {summary['p_value_range'][0]:.2e} - {summary['p_value_range'][1]:.2e}")
        print(f"   • Genome-wide significant: {summary['significance_counts'].get('genome_wide', 0)}")
        print(f"   • Suggestive significant: {summary['significance_counts'].get('suggestive', 0)}")
        print(f"   • Top variant: {summary['top_variant']}")
        
        # Calculate lambda
        lambda_gc = calculate_lambda_gc(self.df['P'].values)
        print(f"   • Genomic inflation (λ): {lambda_gc:.3f}")

def main():
    """Main function to generate Figure 1"""
    generator = Figure1Generator()
    
    try:
        fig, paths = generator.generate_figure1()
        
        if fig is not None:
            print(f"\n✅ Figure 1 generated successfully!")
            print(f"   📁 Files saved: {paths[0]} and {paths[1]}")
            return True
        else:
            print(f"\n❌ Failed to generate Figure 1")
            return False
            
    except Exception as e:
        print(f"\n❌ Error generating Figure 1: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1) 