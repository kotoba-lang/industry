#!/usr/bin/env python3
"""
修正されたGWASデータから正常な分布を持つ図を再生成
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path
from scipy import stats

# カラー設定
try:
    from config.colors import COLORS
    from config.styles import setup_publication_style, save_figure
except ImportError:
    def setup_publication_style():
        plt.style.use('seaborn-v0_8')
    
    def save_figure(fig, filename, output_dir):
        plt.savefig(f"{output_dir}/{filename}.png", dpi=300, bbox_inches='tight')
        plt.savefig(f"{output_dir}/{filename}.pdf", bbox_inches='tight')
    
    COLORS = {
        'japanese': '#2E86AB',
        'european': '#A23B72',
        'convergent': '#F18F01',
        'significant': '#C73E1D'
    }

class CorrectedGWASFigures:
    """修正されたGWASデータで図を再生成"""
    
    def __init__(self, output_dir: Path):
        self.output_dir = output_dir
        self.output_dir.mkdir(exist_ok=True)
        setup_publication_style()
        self.colors = COLORS if 'COLORS' in globals() else {
            'japanese': '#2E86AB',
            'european': '#A23B72',
            'convergent': '#F18F01',
            'significant': '#C73E1D'
        }
    
    def load_corrected_data(self):
        """修正されたデータを読み込み"""
        print("📊 修正されたデータの読み込み")
        print("=" * 40)
        
        jp_file = Path("./output/quality_control/Japanese_HighIQ_GWAS_2024_corrected.tsv")
        eu_file = Path("./output/quality_control/EastAsian_EducationalAttainment_GWAS_corrected.tsv")
        
        try:
            jp_df = pd.read_csv(jp_file, sep='\t')
            print(f"✅ 修正済み日本人データ読み込み: {len(jp_df):,}行")
        except Exception as e:
            print(f"❌ 日本人データ読み込み失敗: {e}")
            return None, None
        
        try:
            eu_df = pd.read_csv(eu_file, sep='\t')
            print(f"✅ 修正済み欧州人データ読み込み: {len(eu_df):,}行")
        except Exception as e:
            print(f"❌ 欧州人データ読み込み失敗: {e}")
            return jp_df, None
        
        return jp_df, eu_df
    
    def generate_corrected_manhattan_qq(self, df: pd.DataFrame, title: str):
        """修正されたManhattan & QQプロット"""
        print(f"🏔️ {title} Manhattan & QQ プロット生成")
        
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 10))
        fig.suptitle(f'{title} - Corrected Data Analysis', fontsize=16, fontweight='bold')
        
        # データの準備
        df_plot = df.copy()
        df_plot = df_plot.dropna(subset=['CHR', 'POS', 'P'])
        df_plot = df_plot[df_plot['P'] > 0]
        
        # 累積位置の計算
        df_plot = df_plot.sort_values(['CHR', 'POS'])
        df_plot['pos_cum'] = 0
        
        cum_pos = 0
        for chrom in sorted(df_plot['CHR'].unique()):
            chr_data = df_plot[df_plot['CHR'] == chrom]
            df_plot.loc[df_plot['CHR'] == chrom, 'pos_cum'] = chr_data['POS'] + cum_pos
            cum_pos += chr_data['POS'].max()
        
        df_plot['neglog10p'] = -np.log10(df_plot['P'])
        
        # Manhattan plot
        chromosomes = sorted(df_plot['CHR'].unique())
        
        for i, chrom in enumerate(chromosomes):
            chr_data = df_plot[df_plot['CHR'] == chrom]
            if len(chr_data) == 0:
                continue
                
            color = self.colors['chr_even'] if chrom % 2 == 0 else self.colors['chr_odd']
            color = color if 'chr_even' in self.colors else (self.colors['japanese'] if chrom % 2 == 0 else self.colors['european'])
            
            ax1.scatter(chr_data['pos_cum'], chr_data['neglog10p'], 
                       c=color, alpha=0.6, s=20, edgecolors='none')
        
        # 有意性ライン
        ax1.axhline(y=-np.log10(5e-8), color='red', linestyle='--', linewidth=2,
                   label='Genome-wide significance (P = 5×10⁻⁸)')
        ax1.axhline(y=-np.log10(1e-6), color='orange', linestyle='--', linewidth=2,
                   label='Suggestive significance (P = 10⁻⁶)')
        
        ax1.set_xlabel('Chromosome')
        ax1.set_ylabel('-log₁₀(P)')
        ax1.set_title('Manhattan Plot (Corrected Data)')
        ax1.legend()
        ax1.grid(True, alpha=0.3)
        
        # 染色体ラベル
        chr_centers = []
        for chrom in chromosomes:
            chr_data = df_plot[df_plot['CHR'] == chrom]
            if len(chr_data) > 0:
                chr_centers.append(chr_data['pos_cum'].median())
        
        if chr_centers:
            ax1.set_xticks(chr_centers)
            ax1.set_xticklabels([str(int(c)) for c in chromosomes])
        
        # QQ plot
        p_values = df_plot['P'].dropna()
        p_values = p_values[p_values > 0]
        
        if len(p_values) > 0:
            n = len(p_values)
            expected = -np.log10(np.arange(1, n + 1) / (n + 1))
            observed = -np.log10(np.sort(p_values))
            
            ax2.scatter(expected, observed, alpha=0.6, s=20, color=self.colors['japanese'])
            
            # 対角線
            max_val = max(expected.max(), observed.max())
            ax2.plot([0, max_val], [0, max_val], 'r--', linewidth=2, label='y = x')
            
            # λGC計算
            chisq = stats.chi2.ppf(1 - p_values, 1)
            lambda_gc = np.median(chisq) / stats.chi2.ppf(0.5, 1)
            
            ax2.set_xlabel('Expected -log₁₀(P)')
            ax2.set_ylabel('Observed -log₁₀(P)')
            ax2.set_title(f'QQ Plot (λGC = {lambda_gc:.3f}) - Corrected Data')
            ax2.legend()
            ax2.grid(True, alpha=0.3)
        
        plt.tight_layout()
        
        # 保存
        filename = f"{title}_Corrected_Manhattan_QQ"
        save_figure(fig, filename, str(self.output_dir))
        
        return fig
    
    def generate_distribution_comparison(self, jp_df: pd.DataFrame, eu_df: pd.DataFrame):
        """修正前後の分布比較"""
        print("📊 修正後の分布分析")
        
        fig, axes = plt.subplots(2, 3, figsize=(18, 12))
        fig.suptitle('Corrected Data Distribution Analysis', fontsize=16, fontweight='bold')
        
        # 日本人データの分析
        jp_z = jp_df['Z'].dropna()
        jp_p = jp_df['P'].dropna()
        jp_p = jp_p[jp_p > 0]
        
        # Z-score分布（日本人）
        axes[0, 0].hist(jp_z, bins=30, alpha=0.7, color=self.colors['japanese'], density=True)
        
        # 理論正規分布を重ねて表示
        x = np.linspace(jp_z.min(), jp_z.max(), 100)
        y = stats.norm.pdf(x, 0, 1)
        axes[0, 0].plot(x, y, 'r--', linewidth=2, label='Standard Normal')
        
        axes[0, 0].set_xlabel('Z-score')
        axes[0, 0].set_ylabel('Density')
        axes[0, 0].set_title('Japanese Z-score Distribution (Corrected)')
        axes[0, 0].legend()
        axes[0, 0].grid(True, alpha=0.3)
        
        # P-value分布（日本人）
        axes[0, 1].hist(jp_p, bins=30, alpha=0.7, color=self.colors['japanese'])
        axes[0, 1].set_xlabel('P-value')
        axes[0, 1].set_ylabel('Frequency')
        axes[0, 1].set_title('Japanese P-value Distribution (Corrected)')
        axes[0, 1].grid(True, alpha=0.3)
        
        # QQ plot（日本人）
        if len(jp_p) > 0:
            n = len(jp_p)
            expected = -np.log10(np.arange(1, n + 1) / (n + 1))
            observed = -np.log10(np.sort(jp_p))
            
            axes[0, 2].scatter(expected, observed, alpha=0.6, s=20, color=self.colors['japanese'])
            max_val = max(expected.max(), observed.max())
            axes[0, 2].plot([0, max_val], [0, max_val], 'r--', linewidth=2)
            axes[0, 2].set_xlabel('Expected -log₁₀(P)')
            axes[0, 2].set_ylabel('Observed -log₁₀(P)')
            axes[0, 2].set_title('Japanese QQ Plot (Corrected)')
            axes[0, 2].grid(True, alpha=0.3)
        
        # 欧州人データの分析（利用可能な場合）
        if eu_df is not None:
            eu_z = eu_df['Z'].dropna()
            eu_p = eu_df['P'].dropna()
            eu_p = eu_p[eu_p > 0]
            
            # Z-score分布（欧州人）
            axes[1, 0].hist(eu_z, bins=30, alpha=0.7, color=self.colors['european'], density=True)
            
            # 理論正規分布
            x = np.linspace(eu_z.min(), eu_z.max(), 100)
            y = stats.norm.pdf(x, 0, 1)
            axes[1, 0].plot(x, y, 'r--', linewidth=2, label='Standard Normal')
            
            axes[1, 0].set_xlabel('Z-score')
            axes[1, 0].set_ylabel('Density')
            axes[1, 0].set_title('European Z-score Distribution (Corrected)')
            axes[1, 0].legend()
            axes[1, 0].grid(True, alpha=0.3)
            
            # P-value分布（欧州人）
            axes[1, 1].hist(eu_p, bins=30, alpha=0.7, color=self.colors['european'])
            axes[1, 1].set_xlabel('P-value')
            axes[1, 1].set_ylabel('Frequency')
            axes[1, 1].set_title('European P-value Distribution (Corrected)')
            axes[1, 1].grid(True, alpha=0.3)
            
            # QQ plot（欧州人）
            if len(eu_p) > 0:
                n = len(eu_p)
                expected = -np.log10(np.arange(1, n + 1) / (n + 1))
                observed = -np.log10(np.sort(eu_p))
                
                axes[1, 2].scatter(expected, observed, alpha=0.6, s=20, color=self.colors['european'])
                max_val = max(expected.max(), observed.max())
                axes[1, 2].plot([0, max_val], [0, max_val], 'r--', linewidth=2)
                axes[1, 2].set_xlabel('Expected -log₁₀(P)')
                axes[1, 2].set_ylabel('Observed -log₁₀(P)')
                axes[1, 2].set_title('European QQ Plot (Corrected)')
                axes[1, 2].grid(True, alpha=0.3)
        else:
            # 欧州人データがない場合はプレースホルダー
            for i in range(3):
                axes[1, i].text(0.5, 0.5, 'European Data\nNot Available', 
                               transform=axes[1, i].transAxes, ha='center', va='center')
                axes[1, i].set_title(f'European Analysis {i+1}')
        
        plt.tight_layout()
        
        # 保存
        filename = "Corrected_Distribution_Analysis"
        save_figure(fig, filename, str(self.output_dir))
        
        return fig
    
    def generate_cross_population_analysis(self, jp_df: pd.DataFrame, eu_df: pd.DataFrame):
        """修正後の集団間比較分析"""
        print("🔄 修正後の集団間比較分析")
        
        if eu_df is None:
            print("⚠️ 欧州人データが利用できません")
            return None
        
        # 共通のSNPでマージ（位置ベース）
        # ここでは簡単のため、Z-scoreの相関を比較
        
        fig, axes = plt.subplots(1, 2, figsize=(15, 6))
        fig.suptitle('Cross-Population Analysis (Corrected Data)', fontsize=16, fontweight='bold')
        
        # 染色体レベルでの相関分析
        jp_chr_stats = jp_df.groupby('CHR').agg({
            'Z': 'mean',
            'P': lambda x: (x < 1e-5).mean(),
            'BETA': 'mean'
        }).reset_index()
        
        eu_chr_stats = eu_df.groupby('CHR').agg({
            'Z': 'mean',
            'P': lambda x: (x < 1e-5).mean(),
            'BETA': 'mean'
        }).reset_index()
        
        # 共通の染色体で結合
        merged_chr = pd.merge(jp_chr_stats, eu_chr_stats, on='CHR', suffixes=('_jp', '_eu'))
        
        if len(merged_chr) > 0:
            # Z-scoreの相関
            correlation = merged_chr['Z_jp'].corr(merged_chr['Z_eu'])
            
            axes[0].scatter(merged_chr['Z_jp'], merged_chr['Z_eu'], 
                           alpha=0.7, s=100, color=self.colors['convergent'])
            axes[0].set_xlabel('Japanese Mean Z-score by Chromosome')
            axes[0].set_ylabel('European Mean Z-score by Chromosome')
            axes[0].set_title(f'Chromosome-level Z-score Correlation (r = {correlation:.3f})')
            axes[0].grid(True, alpha=0.3)
            
            # 有意SNP割合の比較
            axes[1].scatter(merged_chr['P_jp'], merged_chr['P_eu'], 
                           alpha=0.7, s=100, color=self.colors['significant'])
            axes[1].set_xlabel('Japanese Significant SNPs Proportion')
            axes[1].set_ylabel('European Significant SNPs Proportion')
            axes[1].set_title('Significant SNPs Proportion by Chromosome')
            axes[1].grid(True, alpha=0.3)
        else:
            axes[0].text(0.5, 0.5, 'No Common\nChromosomes', 
                        transform=axes[0].transAxes, ha='center', va='center')
            axes[1].text(0.5, 0.5, 'No Common\nChromosomes', 
                        transform=axes[1].transAxes, ha='center', va='center')
        
        plt.tight_layout()
        
        # 保存
        filename = "Corrected_Cross_Population_Analysis"
        save_figure(fig, filename, str(self.output_dir))
        
        return fig
    
    def run_complete_analysis(self):
        """完全な修正後分析を実行"""
        print("🚀 修正されたデータの完全分析開始")
        print("=" * 60)
        
        # データ読み込み
        jp_df, eu_df = self.load_corrected_data()
        
        if jp_df is None:
            print("❌ データ読み込み失敗")
            return
        
        # 図の生成
        results = {}
        
        # 1. 日本人データのManhattan & QQ plot
        results['jp_manhattan'] = self.generate_corrected_manhattan_qq(jp_df, "Japanese")
        
        # 2. 欧州人データのManhattan & QQ plot（利用可能な場合）
        if eu_df is not None:
            results['eu_manhattan'] = self.generate_corrected_manhattan_qq(eu_df, "European")
        
        # 3. 分布比較分析
        results['distribution'] = self.generate_distribution_comparison(jp_df, eu_df)
        
        # 4. 集団間比較分析
        if eu_df is not None:
            results['cross_pop'] = self.generate_cross_population_analysis(jp_df, eu_df)
        
        print("\n✅ 修正後分析完了！")
        print(f"📁 出力ディレクトリ: {self.output_dir}")
        
        return results

def main():
    """メイン実行関数"""
    output_dir = Path("./output/corrected_figures")
    
    corrected_analysis = CorrectedGWASFigures(output_dir)
    results = corrected_analysis.run_complete_analysis()
    
    print("\n🎉 全ての修正後図の生成完了！")

if __name__ == "__main__":
    main() 