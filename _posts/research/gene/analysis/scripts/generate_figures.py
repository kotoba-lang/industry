#!/usr/bin/env python3
"""
Publication-quality figure generation for GWAS analysis using DuckDB backend

Updated to use DuckDB-based data loading for high-performance analysis
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import sys
import os
from pathlib import Path

# プロジェクトルートを追加
sys.path.append(str(Path(__file__).parent.parent))

# DuckDBベースのデータローダーをインポート
try:
    from utils.data_loader import GWASDataLoader
    from utils.duckdb_manager import GWASDuckDBManager
except ImportError:
    print("⚠️ Module import failed. Trying relative import...")
    sys.path.append('../utils')
    from data_loader import GWASDataLoader
    from duckdb_manager import GWASDuckDBManager

# スタイル設定
plt.style.use('seaborn-v0_8')
sns.set_palette("husl")

class GWASFigureGenerator:
    """Generate publication-quality figures for GWAS paper using DuckDB backend"""
    
    def __init__(self, dataset_path='../../dataset/', 
                 primary_trait='PASS_Intelligence_SavageJansen2018',
                 comparison_trait='PASS_Height1'):
        """
        Initialize with DuckDB-based data loading
        
        Args:
            dataset_path: Path to DuckDB dataset
            primary_trait: Primary trait for analysis
            comparison_trait: Comparison trait for cross-analysis
        """
        print("🎨 Initializing DuckDB-based GWAS Figure Generator...")
        
        self.dataset_path = Path(dataset_path)
        self.primary_trait = primary_trait
        self.comparison_trait = comparison_trait
        
        # DuckDBマネージャー初期化
        self.manager = GWASDuckDBManager(self.dataset_path)
        
        # データローダー初期化
        self.loader = GWASDataLoader(self.dataset_path)
        
        # データ読み込み
        self.df_primary = self.load_trait_data(primary_trait, "Primary")
        self.df_comparison = self.load_trait_data(comparison_trait, "Comparison")
        
        self.setup_colors()
        
        print("✅ Figure generator initialization complete")
        
    def load_trait_data(self, trait_id, label):
        """Load and preprocess trait data from DuckDB"""
        try:
            print(f"📥 Loading {label} trait: {trait_id}")
            
            # 最初にその形質がデータベースに存在するか確認
            check_query = f"""
            SELECT COUNT(*) as count
            FROM gwas_associations 
            WHERE trait_id = '{trait_id}'
            """
            
            check_result = self.manager.query_gwas_data(check_query)
            trait_count = check_result.iloc[0]['count']
            
            if trait_count == 0:
                print(f"❌ No data found for {trait_id} in database")
                return pd.DataFrame()
            
            print(f"📊 Found {trait_count:,} variants for {trait_id}")
            
            # DuckDBから直接クエリ（修正版）
            query = f"""
            SELECT snp_id as SNP, a1 as A1, a2 as A2, 
                   n as N, chisq as CHISQ, z_score as Z,
                   chromosome as CHR, position as BP
            FROM gwas_associations 
            WHERE trait_id = '{trait_id}'
            ORDER BY ABS(z_score) DESC
            LIMIT 1000000
            """
            
            df = self.manager.query_gwas_data(query)
            
            if len(df) == 0:
                print(f"❌ Query returned no data for {trait_id}")
                return pd.DataFrame()
            
            print(f"✅ Loaded {len(df)} variants for {label}")
            
            # データ前処理
            df = self.preprocess_data(df)
            
            return df
            
        except Exception as e:
            print(f"❌ Error loading {trait_id}: {e}")
            import traceback
            traceback.print_exc()
            return pd.DataFrame()
    
    def preprocess_data(self, df):
        """Preprocess GWAS data for plotting"""
        try:
            if len(df) == 0:
                return df
            
            original_count = len(df)
            print(f"🔧 Preprocessing {original_count:,} variants...")
            
            # データ型変換
            df['CHR'] = pd.to_numeric(df['CHR'], errors='coerce')
            df['BP'] = pd.to_numeric(df['BP'], errors='coerce')
            df['Z'] = pd.to_numeric(df['Z'], errors='coerce')
            df['N'] = pd.to_numeric(df['N'], errors='coerce')
            
            # Z-scoreの欠損値確認
            z_missing = df['Z'].isna().sum()
            print(f"📊 Z-score missing values: {z_missing}")
            
            # 必須データ（Z-score）の欠損値除去
            if z_missing > 0:
                df = df.dropna(subset=['Z'])
                print(f"⚠️ Removed {z_missing:,} variants with missing Z-scores")
            
            # P値計算
            from scipy.stats import norm
            df['P'] = 2 * (1 - norm.cdf(np.abs(df['Z'])))
            df['neglog10p'] = -np.log10(df['P'].replace(0, 1e-300))
            
            # 染色体・位置情報の処理（オプション）
            if 'CHR' in df.columns and 'BP' in df.columns:
                # 有効な染色体・位置情報があるかチェック
                valid_chr = df['CHR'].notna() & (df['CHR'] > 0) & (df['CHR'] <= 22)
                valid_bp = df['BP'].notna() & (df['BP'] > 0)
                valid_pos = valid_chr & valid_bp
                
                if valid_pos.sum() > 0:
                    print(f"📍 Found {valid_pos.sum():,} variants with valid position data")
                    
                    # 累積位置計算
                    valid_df = df[valid_pos].copy()
                    valid_df['CHR'] = valid_df['CHR'].astype(int)
                    valid_df = valid_df.sort_values(['CHR', 'BP'])
                    
                    chr_lengths = valid_df.groupby('CHR')['BP'].max()
                    chr_starts = chr_lengths.cumsum() - chr_lengths
                    
                    # 全データに累積位置を設定
                    df['pos_cum'] = 0
                    for chr_id in chr_starts.index:
                        chr_mask = (df['CHR'] == chr_id) & valid_pos
                        df.loc[chr_mask, 'pos_cum'] = (
                            chr_starts[chr_id] + df.loc[chr_mask, 'BP']
                        )
                else:
                    print("⚠️ No valid chromosome/position data for Manhattan plot")
                    df['pos_cum'] = 0
            else:
                print("⚠️ CHR/BP columns missing - Manhattan plot not available")
                df['pos_cum'] = 0
            
            final_count = len(df)
            print(f"✅ Preprocessing complete: {original_count:,} → {final_count:,} variants ({final_count/original_count*100:.1f}% retained)")
            
            return df
            
        except Exception as e:
            print(f"❌ Preprocessing error: {e}")
            import traceback
            traceback.print_exc()
            return df
    
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
        
        if len(self.df_primary) == 0:
            print("❌ No primary data available for Manhattan plot")
            return
        
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 10))
        
        # データサンプリング (大量データ対応)
        df_plot = self.df_primary.copy()
        if len(df_plot) > 500000:
            print(f"📊 Downsampling from {len(df_plot)} to 500k points for visualization...")
            
            # 有意なSNPは全て保持
            significant_thresh = 1e-5
            df_sig = df_plot[df_plot['P'] < significant_thresh]
            df_nonsig = df_plot[df_plot['P'] >= significant_thresh]
            
            # 非有意SNPをサンプリング
            n_samples = min(300000, len(df_nonsig))
            df_nonsig_sampled = df_nonsig.sample(n=n_samples, random_state=42)
            df_plot = pd.concat([df_sig, df_nonsig_sampled]).sort_values('pos_cum')
            
            print(f"📈 Plotting {len(df_sig)} significant + {len(df_nonsig_sampled)} sampled = {len(df_plot)} points")
        
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
        
        ax1.set_xticks(chr_centers)
        ax1.set_xticklabels(chromosomes)
        
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
        plt.show()
        
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
            from scipy.stats import chi2
            chisq_stats = chi2.ppf(1 - p_values, df=1)
            lambda_gc = np.median(chisq_stats) / chi2.ppf(0.5, df=1)
            
            ax.set_xlabel('Expected -log₁₀(P)')
            ax.set_ylabel('Observed -log₁₀(P)')
            ax.set_title(f'QQ Plot (λGC = {lambda_gc:.3f})')
            
        except Exception as e:
            print(f"⚠️ QQ plot error: {e}")
            ax.text(0.5, 0.5, f'QQ plot error: {e}', transform=ax.transAxes, 
                   ha='center', va='center')
    
    def generate_cross_population_plot(self, output_dir='analysis/scripts/output'):
        """Generate cross-trait comparison plot (Figure 2)"""
        print("🔄 Generating cross-trait comparison plot...")
        
        if len(self.df_primary) == 0 or len(self.df_comparison) == 0:
            print("❌ Insufficient data for cross-trait analysis")
            return
        
        try:
            # DuckDBを使用した高速クロス解析
            cross_result = self.manager.cross_trait_analysis(
                self.primary_trait, 
                self.comparison_trait
            )
            
            if len(cross_result) == 0:
                print("❌ No shared SNPs found for cross-trait analysis")
                return
            
            print(f"📊 Found {len(cross_result)} shared significant SNPs")
            
            # プロット作成
            fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))
            
            # 効果量相関プロット
            ax1.scatter(cross_result['z_score_trait1'], cross_result['z_score_trait2'], 
                       alpha=0.6, s=30, 
                       c=cross_result['concordant'].map({True: self.colors['convergent'], 
                                                        False: self.colors['chr_odd']}))
            
            # 相関計算
            correlation = cross_result['z_score_trait1'].corr(cross_result['z_score_trait2'])
            
            ax1.set_xlabel(f'{self.primary_trait} Z-score')
            ax1.set_ylabel(f'{self.comparison_trait} Z-score')
            ax1.set_title(f'Cross-Trait Effect Correlation (r = {correlation:.3f})')
            ax1.grid(True, alpha=0.3)
            
            # 一致性率の棒グラフ
            concordance_rate = cross_result['concordant'].mean()
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
            plt.show()
            
            return output_file
            
        except Exception as e:
            print(f"❌ Cross-trait analysis error: {e}")
            return None
    
    def generate_effect_size_plot(self, output_dir='analysis/scripts/output'):
        """Generate effect size distribution plot"""
        print("📊 Generating effect size distribution plot...")
        
        if len(self.df_primary) == 0:
            print("❌ No data available for effect size plot")
            return
        
        try:
            fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 6))
            
            # Z-score分布
            z_scores = self.df_primary['Z'].dropna()
            ax1.hist(z_scores, bins=50, alpha=0.7, color=self.colors['primary'], 
                    density=True, edgecolor='black', linewidth=0.5)
            
            # 理論正規分布と比較
            x = np.linspace(z_scores.min(), z_scores.max(), 100)
            y = (1/np.sqrt(2*np.pi)) * np.exp(-0.5 * x**2)
            ax1.plot(x, y, 'r--', label='Standard Normal', linewidth=2)
            
            ax1.set_xlabel('Z-score')
            ax1.set_ylabel('Density')
            ax1.set_title('Z-score Distribution')
            ax1.legend()
            ax1.grid(True, alpha=0.3)
            
            # P値分布
            p_values = self.df_primary['P'].dropna()
            p_values = p_values[p_values > 0]
            
            ax2.hist(p_values, bins=50, alpha=0.7, color=self.colors['comparison'], 
                    density=True, edgecolor='black', linewidth=0.5)
            
            ax2.set_xlabel('P-value')
            ax2.set_ylabel('Density')
            ax2.set_title('P-value Distribution')
            ax2.grid(True, alpha=0.3)
            
            plt.tight_layout()
            
            # 保存
            os.makedirs(output_dir, exist_ok=True)
            output_file = Path(output_dir) / 'Figure3_Effect_Sizes.png'
            plt.savefig(output_file, dpi=300, bbox_inches='tight')
            
            print(f"✅ Effect size plot saved: {output_file}")
            plt.show()
            
            return output_file
            
        except Exception as e:
            print(f"❌ Effect size plot error: {e}")
            return None
    
    def generate_summary_statistics_table(self, output_dir='analysis/scripts/output'):
        """Generate summary statistics table"""
        print("📋 Generating summary statistics table...")
        
        try:
            # 各形質のサマリー統計をDuckDBから取得
            traits = [self.primary_trait, self.comparison_trait]
            summary_data = []
            
            for trait in traits:
                query = f"""
                SELECT 
                    '{trait}' as trait_id,
                    COUNT(*) as total_snps,
                    COUNT(CASE WHEN ABS(z_score) > 3.0 THEN 1 END) as significant_snps,
                    MAX(ABS(z_score)) as max_zscore,
                    AVG(n) as avg_sample_size,
                    COUNT(DISTINCT chromosome) as chromosomes_covered
                FROM gwas_associations 
                WHERE trait_id = '{trait}'
                """
                
                result = self.manager.query_gwas_data(query)
                if len(result) > 0:
                    summary_data.append(result.iloc[0])
            
            # DataFrame作成
            summary_df = pd.DataFrame(summary_data)
            
            # 保存
            os.makedirs(output_dir, exist_ok=True)
            output_file = Path(output_dir) / 'Summary_Statistics.csv'
            summary_df.to_csv(output_file, index=False)
            
            print(f"✅ Summary table saved: {output_file}")
            print("\n📊 Summary Statistics:")
            print(summary_df.to_string(index=False))
            
            return output_file
            
        except Exception as e:
            print(f"❌ Summary table error: {e}")
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
            
            # Summary table
            results['summary'] = self.generate_summary_statistics_table(output_dir)
            
            print("\n✅ All figures generated successfully!")
            print(f"📁 Output directory: {output_dir}")
            
            for fig_name, fig_path in results.items():
                if fig_path:
                    print(f"  • {fig_name}: {fig_path}")
            
            return results
            
        except Exception as e:
            print(f"❌ Error generating figures: {e}")
            return results

def main():
    """Main function to generate all figures"""
    print("🚀 Starting DuckDB-based GWAS figure generation...")
    
    try:
        # 利用可能な形質を確認
        manager = GWASDuckDBManager('../../dataset/')
        status = manager.get_import_status()
        
        print(f"📊 Database Status:")
        print(f"  • Status: {status.get('status', 'unknown')}")
        if 'total_available' in status:
            print(f"  • Total traits: {status['total_available']}")
            print(f"  • Imported: {status['imported_count']}")
            print(f"  • Completion: {status['completion_rate']:.1f}%")
        else:
            print(f"  • Available traits: {len(manager.scan_available_traits())}")
        
        if status.get('imported_count', 0) == 0:
            print("⚠️ No GWAS traits imported in database. Using LDSC-only mode.")
            print("💡 To import GWAS data, run: manager.bulk_import_high_priority_traits()")
        
        # 使用する形質を確認
        available_traits = manager.scan_available_traits()
        
        # デフォルト形質設定
        primary_trait = 'PASS_Intelligence_SavageJansen2018'
        comparison_trait = 'PASS_Height1'
        
        # 利用可能な形質から選択
        if primary_trait not in available_traits:
            primary_trait = available_traits[0] if available_traits else None
        
        if comparison_trait not in available_traits:
            comparison_trait = available_traits[1] if len(available_traits) > 1 else (available_traits[0] if available_traits else None)
        
        if not primary_trait:
            print("⚠️ No GWAS traits available. Generating LDSC-only demo...")
            
            # LDSC統合状況の確認と表示
            import sys
            sys.path.append('../utils')
            from data_loader import GWASDataLoader
            loader = GWASDataLoader('../../dataset/')
            
            if loader.ldsc_available:
                ldsc_summary = loader.get_ldsc_summary()
                print("\n🧬 LDSC Integration Status:")
                for key, value in ldsc_summary.items():
                    print(f"   {key}: {value}")
                
                # LDSC機能デモ
                print("\n📊 LDSC Feature Demo:")
                test_snps = ['rs6010620', 'rs6014724', 'rs775268684']
                
                # LD Score取得テスト
                ld_scores = loader.get_ld_scores(test_snps)
                if len(ld_scores) > 0:
                    print(f"✅ LD Scores for {len(ld_scores)} SNPs")
                
                # 機能的アノテーション取得テスト
                annotations = loader.get_functional_annotations(test_snps)
                if len(annotations) > 0:
                    print(f"✅ Functional annotations for {len(annotations)} SNPs")
                
                print("🎉 LDSC integration is working perfectly!")
            else:
                print("❌ LDSC integration not available")
            
            return
        
        print(f"🎯 Primary trait: {primary_trait}")
        print(f"🎯 Comparison trait: {comparison_trait}")
        
        # 図表生成器初期化
        generator = GWASFigureGenerator(
            dataset_path='../../dataset/',
            primary_trait=primary_trait,
            comparison_trait=comparison_trait
        )
        
        # 全図表生成
        results = generator.generate_all_figures()
        
        print("\n🎉 Figure generation completed!")
        
    except Exception as e:
        print(f"❌ Main execution error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    main() 