#!/usr/bin/env python3
"""
Figure 2: Cross-Population Comparison of Intelligence-Associated Variants

This script generates comparative analysis between Japanese and European populations:
- P-value correlation between populations
- Effect size correlation
- Heterogeneity analysis (I² statistics)
- Population-specific variant classification

Author: AI Research Assistant
Date: 2024

❗ WARNING: THIS SCRIPT USES SYNTHETIC DATA FOR DEMONSTRATION PURPOSES.
The results generated, particularly the heterogeneity analysis (I²), are based on
simulated data designed to show a specific outcome, not real analysis.
"""

import sys
import os
sys.path.append(os.path.join(os.path.dirname(__file__), '../../../..'))

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from scipy import stats
from analysis.config.colors import COLORS
from analysis.config.styles import setup_publication_style, get_figure_size, save_figure
from analysis.utils.data_loader import GWASDataLoader, create_comparison_data
from analysis.utils.statistics import calculate_heterogeneity, calculate_effect_correlation

class Figure2Generator:
    """Generate Figure 2: Cross-population comparison"""
    
    def __init__(self, data_file='../../gwas-data.csv'):
        """Initialize with GWAS data"""
        self.data_file = data_file
        self.data_loader = GWASDataLoader(data_file)
        self.df = None
        self.comparison_df = None
        
        # Setup publication style
        setup_publication_style()
        
    def load_data(self):
        """Load and preprocess GWAS data"""
        self.df = self.data_loader.load_and_process()
        
        if self.df is not None:
            # Create comparison data with European populations
            self.comparison_df = create_comparison_data(self.df)
            return True
        
        return False
    
    def generate_pvalue_correlation(self, ax):
        """Generate P-value correlation plot (Panel A)"""
        print("📊 Generating P-value correlation plot...")
        
        if self.comparison_df is None:
            print("❌ No comparison data available")
            return ax
        
        # Calculate -log10 P-values
        jp_log = -np.log10(self.comparison_df['P'].replace(0, 1e-100))
        eu_log = -np.log10(self.comparison_df['P_European'].replace(0, 1e-100))
        
        # Remove infinite and NaN values
        valid_idx = np.isfinite(jp_log) & np.isfinite(eu_log)
        jp_clean = jp_log[valid_idx]
        eu_clean = eu_log[valid_idx]
        
        # Scatter plot
        ax.scatter(eu_clean, jp_clean, 
                  alpha=0.6, s=30, color=COLORS['japanese'], 
                  edgecolors='none', rasterized=True)
        
        # Add diagonal line (perfect correlation)
        max_val = max(eu_clean.max(), jp_clean.max()) if len(eu_clean) > 0 else 10
        ax.plot([0, max_val], [0, max_val], 
               'r--', alpha=0.8, linewidth=2, 
               label='Perfect correlation')
        
        # Calculate and display correlation
        if len(jp_clean) > 3:
            correlation, p_value = stats.pearsonr(eu_clean, jp_clean)
            
            # Add correlation text
            ax.text(0.05, 0.95, f'r = {correlation:.3f}\nP = {p_value:.2e}', 
                   transform=ax.transAxes, fontsize=11, fontweight='bold',
                   bbox=dict(boxstyle='round,pad=0.5', facecolor='white', 
                            edgecolor='black', alpha=0.9),
                   verticalalignment='top')
        
        # Customize axes
        ax.set_xlabel('European -log₁₀(P-value)', fontweight='bold')
        ax.set_ylabel('Japanese -log₁₀(P-value)', fontweight='bold')
        ax.set_title('A. P-value Correlation', fontweight='bold', fontsize=14)
        
        # Set equal limits
        ax.set_xlim(0, max_val * 1.02)
        ax.set_ylim(0, max_val * 1.02)
        
        ax.legend(loc='lower right', fontsize=10)
        ax.grid(True, alpha=0.3)
        
        return ax
    
    def generate_effect_size_correlation(self, ax):
        """Generate effect size correlation plot (Panel B)"""
        print("📊 Generating effect size correlation plot...")
        
        if self.comparison_df is None:
            print("❌ No comparison data available")
            return ax
        
        # Get effect sizes
        jp_beta = self.comparison_df['BETA'].fillna(0)
        eu_beta = self.comparison_df['BETA_European'].fillna(0)
        
        # Remove extreme outliers
        jp_beta = np.clip(jp_beta, -3, 3)
        eu_beta = np.clip(eu_beta, -3, 3)
        
        # Scatter plot
        ax.scatter(eu_beta, jp_beta, 
                  alpha=0.6, s=30, color=COLORS['european'], 
                  edgecolors='none', rasterized=True)
        
        # Add diagonal line
        max_abs = max(abs(jp_beta).max(), abs(eu_beta).max())
        ax.plot([-max_abs, max_abs], [-max_abs, max_abs], 
               'r--', alpha=0.8, linewidth=2,
               label='Perfect correlation')
        
        # Calculate correlation
        correlation_result = calculate_effect_correlation(jp_beta, eu_beta)
        
        if not np.isnan(correlation_result['correlation']):
            # Add correlation text
            ax.text(0.05, 0.95, 
                   f'r = {correlation_result["correlation"]:.3f}\n'
                   f'P = {correlation_result["p_value"]:.2e}\n'
                   f'n = {correlation_result["n_variants"]}',
                   transform=ax.transAxes, fontsize=11, fontweight='bold',
                   bbox=dict(boxstyle='round,pad=0.5', facecolor='white', 
                            edgecolor='black', alpha=0.9),
                   verticalalignment='top')
        
        # Customize axes
        ax.set_xlabel('European Effect Size (β)', fontweight='bold')
        ax.set_ylabel('Japanese Effect Size (β)', fontweight='bold')
        ax.set_title('B. Effect Size Correlation', fontweight='bold', fontsize=14)
        
        # Set equal limits
        ax.set_xlim(-max_abs * 1.1, max_abs * 1.1)
        ax.set_ylim(-max_abs * 1.1, max_abs * 1.1)
        
        ax.legend(loc='lower right', fontsize=10)
        ax.grid(True, alpha=0.3)
        
        return ax
    
    def generate_heterogeneity_plot(self, ax):
        """Generate heterogeneity distribution plot (Panel C)"""
        print("📊 Generating heterogeneity analysis plot...")
        
        if self.comparison_df is None:
            print("❌ No comparison data available")
            return ax
        
        # Calculate I² heterogeneity for each variant
        i2_values = []
        
        for _, row in self.comparison_df.iterrows():
            if pd.notna(row['BETA']) and pd.notna(row['BETA_European']):
                # Use default SE if not available
                se_jp = row.get('SE', 0.2)
                se_eu = row.get('SE_European', 0.2)
                
                het_result = calculate_heterogeneity(
                    row['BETA'], se_jp,
                    row['BETA_European'], se_eu
                )
                i2_values.append(het_result['I2'])
            else:
                i2_values.append(0)
        
        # Create histogram
        i2_values = np.array(i2_values)
        i2_values = i2_values[~np.isnan(i2_values)]
        
        if len(i2_values) > 0:
            ax.hist(i2_values, bins=25, alpha=0.7, color=COLORS['convergent'], 
                   edgecolor='black', linewidth=0.5, density=True)
            
            # Add I² = 50% threshold line
            ax.axvline(x=50, color='red', linestyle='--', linewidth=2,
                      label='I² = 50% threshold')
            
            # Calculate percentage with substantial heterogeneity
            substantial_het = np.mean(i2_values > 50) * 100
            
            # Add text annotation
            ax.text(0.65, 0.95, 
                   f'Substantial heterogeneity\n(I² > 50%): {substantial_het:.1f}%',
                   transform=ax.transAxes, fontsize=11, fontweight='bold',
                   bbox=dict(boxstyle='round,pad=0.5', facecolor='white', 
                            edgecolor='black', alpha=0.9),
                   verticalalignment='top')
        
        # Customize axes
        ax.set_xlabel('I² Heterogeneity (%)', fontweight='bold')
        ax.set_ylabel('Density', fontweight='bold')
        ax.set_title('C. Heterogeneity Distribution', fontweight='bold', fontsize=14)
        ax.set_xlim(0, 100)
        
        ax.legend(loc='upper right', fontsize=10)
        ax.grid(True, alpha=0.3)
        
        return ax
    
    def generate_variant_classification(self, ax):
        """Generate population-specific variant classification (Panel D)"""
        print("📊 Generating variant classification plot...")
        
        if self.comparison_df is None:
            print("❌ No comparison data available")
            return ax
        
        # Define significance thresholds
        jp_significant = self.comparison_df['P'] < 1e-4
        eu_significant = self.comparison_df['P_European'] < 1e-4
        
        # Classify variants
        categories = []
        for jp_sig, eu_sig in zip(jp_significant, eu_significant):
            if jp_sig and not eu_sig:
                categories.append('Japanese-specific')
            elif eu_sig and not jp_sig:
                categories.append('European-specific')
            elif jp_sig and eu_sig:
                categories.append('Shared')
            else:
                categories.append('Non-significant')
        
        # Count categories
        category_counts = pd.Series(categories).value_counts()
        
        # Colors for pie chart
        colors_pie = {
            'Japanese-specific': COLORS['japanese'],
            'European-specific': COLORS['european'],
            'Shared': COLORS['convergent'],
            'Non-significant': '#CCCCCC'
        }
        
        # Create pie chart
        wedges, texts, autotexts = ax.pie(
            category_counts.values, 
            labels=category_counts.index,
            autopct='%1.1f%%',
            startangle=90,
            colors=[colors_pie.get(cat, '#CCCCCC') for cat in category_counts.index],
            explode=[0.05 if 'specific' in cat else 0 for cat in category_counts.index]
        )
        
        # Customize text
        for autotext in autotexts:
            autotext.set_color('white')
            autotext.set_fontweight('bold')
            autotext.set_fontsize(10)
        
        for text in texts:
            text.set_fontweight('bold')
            text.set_fontsize(10)
        
        ax.set_title('D. Variant Classification', fontweight='bold', fontsize=14)
        
        return ax
    
    def generate_figure2(self, output_dir='../../output'):
        """Generate complete Figure 2"""
        if not self.load_data():
            print("❌ Failed to load data")
            return None
        
        print("\n" + "="*50)
        print("GENERATING FIGURE 2: CROSS-POPULATION COMPARISON")
        print("="*50)
        
        # Create figure with 2x2 subplots
        fig_size = get_figure_size('quad_plot')
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=fig_size)
        
        # Generate all panels
        self.generate_pvalue_correlation(ax1)
        self.generate_effect_size_correlation(ax2)
        self.generate_heterogeneity_plot(ax3)
        self.generate_variant_classification(ax4)
        
        # Adjust layout
        plt.tight_layout(pad=3.0)
        
        # Save figure
        png_path, pdf_path = save_figure(fig, 'Figure2_Cross_Population', output_dir)
        
        # Display summary
        self.print_summary()
        
        return fig, (png_path, pdf_path)
    
    def print_summary(self):
        """Print analysis summary"""
        if self.comparison_df is None:
            return
        
        print("\n" + "="*50)
        print("❗ WARNING: Results are based on SYNTHETIC data.")
        print("="*50)

        print(f"\n📋 CROSS-POPULATION ANALYSIS SUMMARY:")
        
        # P-value correlation
        jp_log = -np.log10(self.comparison_df['P'].replace(0, 1e-100))
        eu_log = -np.log10(self.comparison_df['P_European'].replace(0, 1e-100))
        valid_idx = np.isfinite(jp_log) & np.isfinite(eu_log)
        
        if np.sum(valid_idx) > 3:
            p_correlation, _ = stats.pearsonr(jp_log[valid_idx], eu_log[valid_idx])
            print(f"   • P-value correlation: r = {p_correlation:.3f}")
        
        # Effect size correlation
        effect_corr = calculate_effect_correlation(
            self.comparison_df['BETA'].fillna(0),
            self.comparison_df['BETA_European'].fillna(0)
        )
        if not np.isnan(effect_corr['correlation']):
            print(f"   • Effect size correlation: r = {effect_corr['correlation']:.3f}")
        
        # Variant classification
        jp_sig = self.comparison_df['P'] < 1e-4
        eu_sig = self.comparison_df['P_European'] < 1e-4
        
        n_jp_specific = np.sum(jp_sig & ~eu_sig)
        n_eu_specific = np.sum(eu_sig & ~jp_sig)
        n_shared = np.sum(jp_sig & eu_sig)
        
        print(f"   • Japanese-specific variants: {n_jp_specific}")
        print(f"   • European-specific variants: {n_eu_specific}")
        print(f"   • Shared variants: {n_shared}")
        
        # Transferability percentage
        total_sig = np.sum(jp_sig | eu_sig)
        if total_sig > 0:
            transferability = (n_shared / total_sig) * 100
            print(f"   • Cross-population transferability: {transferability:.1f}%")

def main():
    """Main function to generate Figure 2"""
    generator = Figure2Generator()
    
    try:
        fig, paths = generator.generate_figure2()
        
        if fig is not None:
            print(f"\n✅ Figure 2 generated successfully!")
            print(f"   📁 Files saved: {paths[0]} and {paths[1]}")
            return True
        else:
            print(f"\n❌ Failed to generate Figure 2")
            return False
            
    except Exception as e:
        print(f"\n❌ Error generating Figure 2: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1) 