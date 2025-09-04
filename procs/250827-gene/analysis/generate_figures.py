#!/usr/bin/env python3
"""
Publication-quality figure generation for GWAS analysis using DuckDB backend

Refactored to focus solely on figure generation from pre-processed DataFrames.
Data loading and preprocessing are handled by the main analysis script.
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import os
from pathlib import Path
from scipy.stats import chi2_contingency, chi2

def calculate_lambda_gc(p_values: pd.Series) -> float:
    """Calculates the genomic inflation factor (lambda GC)."""
    if p_values.empty:
        return np.nan
    chisq = chi2.ppf(1 - p_values, 1)
    return np.median(chisq) / chi2.ppf(0.5, 1)

# スタイル設定
plt.style.use('seaborn-v0_8')
sns.set_palette("husl")

class GWASFigureGenerator:
    """Generate publication-quality figures for GWAS paper from pre-processed DataFrames."""
    
    def __init__(self, primary_df: pd.DataFrame, comparison_df: pd.DataFrame, 
                 primary_trait_name: str, comparison_trait_name: str):
        """
        Initialize with pre-processed DataFrames.
        
        Args:
            primary_df: DataFrame for the primary trait.
            comparison_df: DataFrame for the comparison trait.
            primary_trait_name: Name of the primary trait.
            comparison_trait_name: Name of the comparison trait.
        """
        print("🎨 Initializing Refactored GWAS Figure Generator...")
        
        self.df_primary = primary_df
        self.df_comparison = comparison_df
        self.primary_trait = primary_trait_name
        self.comparison_trait = comparison_trait_name
        
        self.setup_colors()
        
        print("✅ Figure generator initialization complete")
    
    def setup_colors(self):
        """Setup color schemes for plots"""
        self.colors = {
            'chr_even': '#2E86AB',
            'chr_odd': '#A23B72', 
            'significant': '#F18F01',
            'suggestive': '#C73E1D',
            'primary': '#2E86AB',
            'comparison': '#A23B72',
            'convergent': '#F18F01'
        }
    
    def generate_manhattan_qq_plot(self, output_dir='analysis/scripts/output'):
        """Generate combined Manhattan and QQ plot (Figure 1)"""
        print("🏔️ Generating Manhattan and QQ plots...")
        
        if self.df_primary is None or len(self.df_primary) == 0:
            print("❌ No primary data available for Manhattan plot")
            return
        
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 10))
        
        # Manhattan plot requires 'pos_cum' and 'neglog10p' columns from pre-processing
        if 'pos_cum' not in self.df_primary.columns or 'neglog10p' not in self.df_primary.columns:
            print("❌ 'pos_cum' or 'neglog10p' columns are missing. Cannot generate Manhattan plot.")
            return

        df_plot = self.df_primary.copy()
        
        # Manhattan plot
        chromosomes = sorted(df_plot['CHR'].unique())
        
        for chrom in chromosomes:
            chr_data = df_plot[df_plot['CHR'] == chrom]
            if len(chr_data) == 0:
                continue
                
            color = self.colors['chr_even'] if chrom % 2 == 0 else self.colors['chr_odd']
            
            ax1.scatter(chr_data['pos_cum'], chr_data['neglog10p'], 
                       c=color, alpha=0.6, s=20, edgecolors='none')
        
        # 有意性ライン
        ax1.axhline(y=-np.log10(5e-8), color='red', linestyle='--', 
                   label='Genome-wide significance (P = 5×10⁻⁸)')
        ax1.axhline(y=-np.log10(1e-6), color='orange', linestyle='--', 
                   label='Suggestive significance (P = 10⁻⁶)')
        
        # 軸設定
        ax1.set_xlabel('Chromosome')
        ax1.set_ylabel('-log₁₀(P)')
        ax1.set_title(f'Manhattan Plot - {self.primary_trait}')
        ax1.legend()
        
        # 染色体ラベル
        chr_centers = []
        for chrom in chromosomes:
            chr_data = df_plot[df_plot['CHR'] == chrom]
            if len(chr_data) > 0:
                chr_centers.append(chr_data['pos_cum'].median())
        
        if chr_centers:
            ax1.set_xticks(chr_centers)
            ax1.set_xticklabels([int(c) for c in chromosomes if c in df_plot['CHR'].unique()])
        
        # QQ plot
        self.generate_qq_plot(ax2, df_plot)
        
        plt.tight_layout()
        
        # 保存
        os.makedirs(output_dir, exist_ok=True)
        output_file = Path(output_dir) / 'Figure1_Manhattan_QQ.png'
        plt.savefig(output_file, dpi=300, bbox_inches='tight')
        
        # PDF版も保存
        pdf_file = Path(output_dir) / 'Figure1_Manhattan_QQ.pdf'
        plt.savefig(pdf_file, bbox_inches='tight')
        
        print(f"✅ Manhattan/QQ plot saved: {output_file}")
        plt.close(fig) # メモリ解放
        
        return output_file
    
    def generate_qq_plot(self, ax, df):
        """Generate QQ plot for P-values"""
        try:
            p_values = df['P'].dropna()
            p_values = p_values[p_values > 0]
            
            if len(p_values) == 0:
                ax.text(0.5, 0.5, 'No valid P-values', transform=ax.transAxes, 
                       ha='center', va='center')
                return
            
            # 期待値と観測値
            n = len(p_values)
            expected = -np.log10(np.arange(1, n + 1) / (n + 1))
            observed = -np.log10(np.sort(p_values))
            
            # プロット
            ax.scatter(expected, observed, alpha=0.6, s=10)
            
            # 対角線
            max_val = max(expected.max(), observed.max())
            ax.plot([0, max_val], [0, max_val], 'r--', alpha=0.8)
            
            # λGC計算
            lambda_gc = calculate_lambda_gc(p_values)
            
            ax.set_xlabel('Expected -log₁₀(P)')
            ax.set_ylabel('Observed -log₁₀(P)')
            ax.set_title(f'QQ Plot (λGC = {lambda_gc:.3f})')
            
        except Exception as e:
            print(f"⚠️ QQ plot error: {e}")
            ax.text(0.5, 0.5, f'QQ plot error: {e}', transform=ax.transAxes, 
                   ha='center', va='center')
    
    def generate_cross_population_plot(self, output_dir='analysis/scripts/output'):
        """Generate cross-trait comparison plot (Figure 2) from pre-merged DataFrame."""
        print("🔄 Generating cross-trait comparison plot...")
        
        if self.df_primary is None or len(self.df_primary) == 0 or \
           self.df_comparison is None or len(self.df_comparison) == 0:
            print("❌ Insufficient data for cross-trait analysis")
            return

        try:
            # 共通のSNPでマージ
            merged_df = pd.merge(self.df_primary, self.df_comparison, on='SNP', suffixes=('_p', '_c'))
            
            if len(merged_df) == 0:
                print("❌ No shared SNPs found for cross-trait analysis")
                return

            print(f"📊 Found {len(merged_df)} shared SNPs for comparison")

            merged_df['concordant'] = (np.sign(merged_df['Z_p']) == np.sign(merged_df['Z_c']))
            
            # プロット作成
            fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))
            
            # 効果量相関プロット
            ax1.scatter(merged_df['Z_p'], merged_df['Z_c'], 
                       alpha=0.6, s=30, 
                       c=merged_df['concordant'].map({True: self.colors['convergent'], 
                                                      False: self.colors['chr_odd']}))
            
            # 相関計算
            correlation = merged_df['Z_p'].corr(merged_df['Z_c'])
            
            ax1.set_xlabel(f'{self.primary_trait} Z-score')
            ax1.set_ylabel(f'{self.comparison_trait} Z-score')
            ax1.set_title(f'Cross-Trait Effect Correlation (r = {correlation:.3f})')
            ax1.grid(True, alpha=0.3)
            
            # 一致性率の棒グラフ
            concordance_rate = merged_df['concordant'].mean()
            categories = ['Concordant', 'Discordant']
            values = [concordance_rate, 1 - concordance_rate]
            colors = [self.colors['convergent'], self.colors['chr_odd']]
            
            ax2.bar(categories, values, color=colors, alpha=0.8)
            ax2.set_ylabel('Proportion')
            ax2.set_title(f'Effect Direction Concordance ({concordance_rate:.1%})')
            ax2.set_ylim(0, 1)
            
            # 値をバーに表示
            for i, v in enumerate(values):
                ax2.text(i, v + 0.02, f'{v:.1%}', ha='center', va='bottom')
            
            plt.tight_layout()
            
            # 保存
            os.makedirs(output_dir, exist_ok=True)
            output_file = Path(output_dir) / 'Figure2_Cross_Population.png'
            plt.savefig(output_file, dpi=300, bbox_inches='tight')
            
            pdf_file = Path(output_dir) / 'Figure2_Cross_Population.pdf'
            plt.savefig(pdf_file, bbox_inches='tight')
            
            print(f"✅ Cross-trait plot saved: {output_file}")
            plt.close(fig) # メモリ解放
            
            return output_file
            
        except Exception as e:
            print(f"❌ Cross-trait analysis error: {e}")
            return None

    def generate_effect_size_plot(self, output_dir='analysis/scripts/output'):
        """Generate effect size distribution plot (Figure 3)"""
        print("📊 Generating effect size distribution plot (Figure 3)...")
        
        if self.df_primary is None or len(self.df_primary) == 0:
            print("❌ No primary data available for effect size plot")
            return None
        
        try:
            fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 6))
            
            # Z-score分布
            z_scores = self.df_primary['Z'].dropna()
            sns.histplot(z_scores, bins=50, ax=ax1, color=self.colors['primary'], kde=True)
            ax1.set_xlabel('Z-score')
            ax1.set_ylabel('Frequency')
            ax1.set_title(f'Z-score Distribution ({self.primary_trait})')
            ax1.grid(True, alpha=0.3)
            
            # P値分布
            p_values = self.df_primary['P'].dropna()
            sns.histplot(p_values, bins=50, ax=ax2, color=self.colors['comparison'])
            ax2.set_xlabel('P-value')
            ax2.set_ylabel('Frequency')
            ax2.set_title(f'P-value Distribution ({self.primary_trait})')
            ax2.grid(True, alpha=0.3)
            
            plt.tight_layout()
            output_file = Path(output_dir) / 'Figure3_Effect_Sizes.png'
            plt.savefig(output_file, dpi=300)
            plt.close(fig)
            print(f"✅ Effect size plot saved: {output_file}")
            return output_file
        except Exception as e:
            print(f"❌ Effect size plot error: {e}")
            return None

    def generate_chromosome_enrichment_plot(self, output_dir='analysis/scripts/output'):
        """Generate chromosome enrichment analysis plot (Figure 4)"""
        print("🧬 Generating chromosome enrichment plot (Figure 4)...")

        if self.df_primary is None or len(self.df_primary) == 0:
            print("❌ No primary data available for enrichment plot")
            return None
            
        try:
            df = self.df_primary.copy()
            significant_threshold = 1e-5
            df['is_significant'] = df['P'] < significant_threshold

            # 染色体ごとの観測された有意SNP数
            observed_counts = df[df['is_significant']].groupby('CHR').size()
            
            # 全体のSNP数と有意SNP数
            total_snps = len(df)
            total_significant_snps = df['is_significant'].sum()
            
            # 染色体ごとの期待される有意SNP数
            snps_per_chrom = df.groupby('CHR').size()
            expected_counts = (total_significant_snps / total_snps) * snps_per_chrom
            
            enrichment_ratio = (observed_counts / expected_counts).fillna(0)
            
            fig, ax = plt.subplots(figsize=(14, 7))
            enrichment_ratio.plot(kind='bar', ax=ax, color=self.colors['primary'], alpha=0.8)
            ax.axhline(1, ls='--', color='grey', label='Expected Ratio (1.0)')
            
            ax.set_title('Chromosome Enrichment for Significant SNPs (P < 1e-5)')
            ax.set_xlabel('Chromosome')
            ax.set_ylabel('Observed / Expected Ratio')
            ax.legend()
            plt.tight_layout()

            output_file = Path(output_dir) / 'Figure4_Chromosome_Enrichment.png'
            plt.savefig(output_file, dpi=300)
            plt.close(fig)
            print(f"✅ Chromosome enrichment plot saved: {output_file}")
            return output_file
        except Exception as e:
            print(f"❌ Chromosome enrichment plot error: {e}")
            return None

    def generate_replication_plot(self, output_dir='analysis/scripts/output'):
        """Generate replication analysis plot (Figure 5)"""
        print("🔄 Generating replication plot (Figure 5)...")

        if self.df_primary is None or len(self.df_primary) == 0 or \
           self.df_comparison is None or len(self.df_comparison) == 0:
            print("❌ Insufficient data for replication analysis")
            return None

        try:
            # 欧州人データで最も有意なSNPを50個選択
            top_eu_snps = self.df_comparison.nsmallest(50, 'P')
            
            # それらのSNPを日本人データとマージ
            replication_df = pd.merge(top_eu_snps, self.df_primary, on='SNP', suffixes=('_eu', '_jp'))

            if len(replication_df) == 0:
                print("⚠️ No overlapping top SNPs found for replication plot.")
                return None

            replication_df = replication_df.sort_values('P_eu')
            
            fig, ax = plt.subplots(figsize=(14, 7))
            
            # Zスコアを比較する棒グラフ
            index = np.arange(len(replication_df))
            bar_width = 0.35
            
            ax.bar(index - bar_width/2, replication_df['Z_eu'], bar_width, 
                   label=f'{self.comparison_trait} Z-score', color=self.colors['comparison'])
            ax.bar(index + bar_width/2, replication_df['Z_jp'], bar_width, 
                   label=f'{self.primary_trait} Z-score', color=self.colors['primary'])
            
            ax.set_xlabel('Top 50 SNPs from European GWAS')
            ax.set_ylabel('Z-score')
            ax.set_title('Replication of Top European Hits in Japanese Population')
            ax.set_xticks(index)
            ax.set_xticklabels(replication_df['SNP'], rotation=90, size='small')
            ax.legend()
            ax.grid(True, axis='y', alpha=0.3)
            
            plt.tight_layout()
            output_file = Path(output_dir) / 'Figure5_Replication_Analysis.png'
            plt.savefig(output_file, dpi=300)
            plt.close(fig)
            print(f"✅ Replication plot saved: {output_file}")
            return output_file
        except Exception as e:
            print(f"❌ Replication plot error: {e}")
            return None

    def generate_all_figures(self, output_dir='analysis/scripts/output'):
        """Generate all figures for the analysis"""
        print("🎨 Generating all publication figures...")
        
        os.makedirs(output_dir, exist_ok=True)
        results = {}
        
        try:
            # Figure 1: Manhattan & QQ plots
            results['figure1'] = self.generate_manhattan_qq_plot(output_dir)
            
            # Figure 2: Cross-trait comparison  
            results['figure2'] = self.generate_cross_population_plot(output_dir)
            
            # Figure 3: Effect size distributions
            results['figure3'] = self.generate_effect_size_plot(output_dir)

            # Figure 4: Chromosome Enrichment
            results['figure4'] = self.generate_chromosome_enrichment_plot(output_dir)
            
            # Figure 5: Replication Analysis
            results['figure5'] = self.generate_replication_plot(output_dir)
            
            # 他の図生成関数も必要に応じて呼び出す
            
            print("\n✅ All figures generated successfully!")
            print(f"📁 Output directory: {output_dir}")
            
            for fig_name, fig_path in results.items():
                if fig_path:
                    print(f"  • {fig_name}: {fig_path}")
            
            return results
            
        except Exception as e:
            print(f"❌ Error generating figures: {e}")
            return results 