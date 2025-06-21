#!/usr/bin/env python3
"""
Comprehensive GWAS Analysis Pipeline using DuckDB backend

This script provides a complete analysis pipeline for GWAS data using 
high-performance DuckDB database for data storage and querying.

Updated to use DuckDB-based data loading and analysis
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import sys
import os
from pathlib import Path
import warnings
warnings.filterwarnings('ignore')

# プロジェクトルートを追加
sys.path.append(str(Path(__file__).parent.parent))

# DuckDBベースのモジュールをインポート
try:
    from utils.data_loader import GWASDataLoader
    from utils.duckdb_manager import GWASDuckDBManager
    from utils.statistics import calculate_lambda_gc
except ImportError as e:
    print(f"⚠️ Module import failed: {e}")
    print("Trying relative import...")
    sys.path.append('../utils')
    from data_loader import GWASDataLoader
    from duckdb_manager import GWASDuckDBManager
    from statistics import calculate_lambda_gc

# 図表生成をインポート
try:
    from generate_figures import GWASFigureGenerator
except ImportError:
    print("⚠️ Figure generator not available. Will skip figure generation.")
    GWASFigureGenerator = None

class PaperAnalysis:
    """DuckDB-based comprehensive GWAS analysis pipeline"""
    
    def __init__(self, dataset_path='../../dataset/', primary_trait='PASS_Intelligence_SavageJansen2018'):
        """
        Initialize DuckDB-based analysis pipeline
        
        Args:
            dataset_path: Path to DuckDB dataset
            primary_trait: Primary trait for analysis
        """
        print("🧬 Initializing DuckDB-based GWAS Analysis Pipeline...")
        
        self.dataset_path = Path(dataset_path)
        self.primary_trait = primary_trait
        
        # DuckDBマネージャー初期化
        self.manager = GWASDuckDBManager(self.dataset_path)
        
        # データローダー初期化
        self.loader = GWASDataLoader(self.dataset_path, trait_id=primary_trait)
        
        # データベース状態確認
        self.check_database_status()
        
        # デフォルト設定
        self.output_dir = Path('analysis/scripts/output')
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
        print("✅ Analysis pipeline initialized")
    
    def check_database_status(self):
        """データベースの状態を確認"""
        print("\n📊 Database Status Check:")
        
        try:
            status = self.manager.get_import_status()
            
            print(f"  • Total traits available: {status['total_available']}")
            print(f"  • Imported to database: {status['imported_count']}")
            print(f"  • Missing traits: {status['missing_count']}")
            print(f"  • Completion rate: {status['completion_rate']:.1f}%")
            
            if status['imported_count'] == 0:
                print("❌ No traits imported yet. Database import may still be running.")
                return False
            
            if status['recently_imported']:
                print(f"\n🆕 Recently imported traits:")
                for trait in status['recently_imported'][:5]:
                    print(f"    • {trait}")
            
            return True
            
        except Exception as e:
            print(f"❌ Error checking database status: {e}")
            return False
    
    def load_and_analyze_trait(self, trait_id: str = None):
        """Load and perform basic analysis on a trait"""
        if trait_id is None:
            trait_id = self.primary_trait
        
        print(f"\n🔬 Loading and analyzing trait: {trait_id}")
        
        try:
            # データ読み込み
            success = self.loader.load_trait_data(trait_id)
            if not success:
                print(f"❌ Failed to load trait: {trait_id}")
                return None
            
            # 前処理
            self.loader.preprocess_data()
            
            # 基本統計
            summary = self.loader.get_data_summary()
            
            print(f"\n📋 Trait Analysis Summary:")
            print(f"  • Trait ID: {summary['trait_id']}")
            print(f"  • Total variants: {summary['total_variants']:,}")
            print(f"  • Chromosomes: {len(summary['chromosomes'])} ({min(summary['chromosomes'])}-{max(summary['chromosomes'])})")
            print(f"  • P-value range: {summary['p_value_range'][0]:.2e} - {summary['p_value_range'][1]:.2e}")
            print(f"  • Top variant: {summary['top_variant']}")
            
            # 有意性統計
            sig_counts = summary['significance_counts']
            print(f"\n🎯 Significance Analysis:")
            for level, count in sig_counts.items():
                print(f"  • {level}: {count:,} variants")
            
            return self.loader.df
            
        except Exception as e:
            print(f"❌ Error in trait analysis: {e}")
            return None
    
    def perform_cross_trait_analysis(self, trait1: str, trait2: str):
        """Perform cross-trait analysis"""
        print(f"\n🔄 Cross-trait analysis: {trait1} vs {trait2}")
        
        try:
            # DuckDBを使用した高速クロス解析
            cross_result = self.manager.cross_trait_analysis(trait1, trait2)
            
            if len(cross_result) == 0:
                print("❌ No shared significant SNPs found")
                return None
            
            print(f"📊 Found {len(cross_result)} shared SNPs")
            
            # 相関統計
            correlation = cross_result['z_score_trait1'].corr(cross_result['z_score_trait2'])
            concordance_rate = cross_result['concordant'].mean()
            
            print(f"📈 Cross-trait Results:")
            print(f"  • Z-score correlation: {correlation:.4f}")
            print(f"  • Effect direction concordance: {concordance_rate:.1%}")
            print(f"  • Concordant SNPs: {cross_result['concordant'].sum()}")
            print(f"  • Discordant SNPs: {(~cross_result['concordant']).sum()}")
            
            # 保存
            output_file = self.output_dir / f'cross_trait_{trait1}_vs_{trait2}.csv'
            cross_result.to_csv(output_file, index=False)
            print(f"💾 Cross-trait results saved: {output_file}")
            
            return cross_result
            
        except Exception as e:
            print(f"❌ Cross-trait analysis error: {e}")
            return None
    
    def generate_comprehensive_summary(self):
        """Generate comprehensive analysis summary"""
        print("\n📋 Generating comprehensive analysis summary...")
        
        try:
            # 利用可能な形質取得
            available_traits = self.manager.scan_available_traits()
            
            if not available_traits:
                print("❌ No traits available for summary")
                return None
            
            # 主要形質の統計
            summary_data = []
            
            # 高優先度形質のリスト
            priority_traits = [
                'PASS_Intelligence_SavageJansen2018',
                'PASS_Height1',
                'PASS_BMI1',
                'PASS_Schizophrenia',
                'PASS_MDD_Howard2019',
                'PASS_ADHD_Demontis2018',
                'PASS_Autism_Grove2019',
                'PASS_Coronary_Artery_Disease',
                'PASS_Type_2_Diabetes'
            ]
            
            # 利用可能な優先形質を解析
            analysis_traits = [t for t in priority_traits if t in available_traits][:10]
            
            if not analysis_traits:
                analysis_traits = available_traits[:10]  # 最初の10形質
            
            print(f"📊 Analyzing {len(analysis_traits)} traits...")
            
            for trait in analysis_traits:
                try:
                    query = f"""
                    SELECT 
                        '{trait}' as trait_id,
                        COUNT(*) as total_snps,
                        COUNT(CASE WHEN ABS(z_score) > 3.0 THEN 1 END) as significant_snps_z3,
                        COUNT(CASE WHEN ABS(z_score) > 5.0 THEN 1 END) as significant_snps_z5,
                        MAX(ABS(z_score)) as max_zscore,
                        AVG(n) as avg_sample_size,
                        COUNT(DISTINCT chromosome) as chromosomes_covered,
                        MIN(CASE WHEN ABS(z_score) > 3.0 THEN ABS(z_score) END) as min_sig_zscore
                    FROM gwas_associations 
                    WHERE trait_id = '{trait}'
                    """
                    
                    result = self.manager.query_gwas_data(query)
                    if len(result) > 0:
                        summary_data.append(result.iloc[0])
                        print(f"  ✅ {trait}: {result.iloc[0]['total_snps']:,} SNPs")
                    
                except Exception as e:
                    print(f"  ❌ Error analyzing {trait}: {e}")
            
            # DataFrame作成
            summary_df = pd.DataFrame(summary_data)
            
            if len(summary_df) > 0:
                # 追加統計計算
                summary_df['significance_rate'] = (summary_df['significant_snps_z3'] / summary_df['total_snps'] * 100).round(3)
                summary_df['high_significance_rate'] = (summary_df['significant_snps_z5'] / summary_df['total_snps'] * 100).round(3)
                
                # 保存
                output_file = self.output_dir / 'comprehensive_trait_summary.csv'
                summary_df.to_csv(output_file, index=False)
                
                print(f"\n✅ Comprehensive summary generated: {output_file}")
                print("\n📊 Top Traits by Significance:")
                
                # 有意性でソート
                top_traits = summary_df.nlargest(5, 'significant_snps_z3')
                print(top_traits[['trait_id', 'total_snps', 'significant_snps_z3', 'significance_rate']].to_string(index=False))
                
                return summary_df
            
        except Exception as e:
            print(f"❌ Error generating summary: {e}")
        
        return None
    
    def run_quality_control_analysis(self, trait_id: str = None):
        """Run quality control analysis"""
        if trait_id is None:
            trait_id = self.primary_trait
        
        print(f"\n🔍 Quality Control Analysis: {trait_id}")
        
        try:
            # データ読み込み
            df = self.load_and_analyze_trait(trait_id)
            if df is None:
                return None
            
            # P値分布検査
            p_values = df['P'].dropna()
            p_values = p_values[p_values > 0]
            
            # λGC計算
            lambda_gc = calculate_lambda_gc(p_values)
            
            # 統計サマリー
            print(f"\n🔬 Quality Control Results:")
            print(f"  • Lambda GC: {lambda_gc:.3f}")
            
            if lambda_gc > 1.1:
                print("  ⚠️  Warning: High genomic inflation (λGC > 1.1)")
            elif lambda_gc < 0.9:
                print("  ⚠️  Warning: Low genomic inflation (λGC < 0.9)")
            else:
                print("  ✅ Genomic inflation within acceptable range")
            
            # 欠損値チェック
            missing_stats = {
                'total_variants': len(df),
                'missing_p': df['P'].isna().sum(),
                'missing_z': df['Z'].isna().sum(),
                'missing_chr': df['CHR'].isna().sum(),
                'missing_bp': df['BP'].isna().sum()
            }
            
            print(f"\n📊 Data Completeness:")
            for stat, value in missing_stats.items():
                if 'missing' in stat:
                    pct = (value / missing_stats['total_variants']) * 100
                    print(f"  • {stat}: {value:,} ({pct:.1f}%)")
                else:
                    print(f"  • {stat}: {value:,}")
            
            # 異常値検出
            z_scores = df['Z'].dropna()
            extreme_z = np.abs(z_scores) > 10
            
            print(f"\n🎯 Extreme Values:")
            print(f"  • Z-scores > 10: {extreme_z.sum():,}")
            print(f"  • Max |Z|: {np.abs(z_scores).max():.2f}")
            
            return {
                'lambda_gc': lambda_gc,
                'missing_stats': missing_stats,
                'extreme_values': extreme_z.sum()
            }
            
        except Exception as e:
            print(f"❌ Quality control error: {e}")
            return None
    
    def generate_publication_figures(self):
        """Generate all publication figures"""
        print("\n🎨 Generating publication figures...")
        
        if GWASFigureGenerator is None:
            print("❌ Figure generator not available")
            return None
        
        try:
            # 利用可能な形質から主要なものを選択
            available_traits = self.manager.scan_available_traits()
            
            primary_trait = self.primary_trait
            comparison_trait = 'PASS_Height1'
            
            # 利用可能な形質から選択
            if primary_trait not in available_traits:
                primary_trait = available_traits[0] if available_traits else None
            
            if comparison_trait not in available_traits:
                comparison_trait = available_traits[1] if len(available_traits) > 1 else available_traits[0]
            
            if not primary_trait:
                print("❌ No traits available for figure generation")
                return None
            
            print(f"🎯 Primary trait: {primary_trait}")
            print(f"🎯 Comparison trait: {comparison_trait}")
            
            # 図表生成
            generator = GWASFigureGenerator(
                dataset_path=str(self.dataset_path),
                primary_trait=primary_trait,
                comparison_trait=comparison_trait
            )
            
            results = generator.generate_all_figures(str(self.output_dir))
            
            return results
            
        except Exception as e:
            print(f"❌ Figure generation error: {e}")
            return None
    
    def generate_summary_table(self):
        """Generate summary table for top variants"""
        print("\n📋 Generating summary table for top variants...")
        
        try:
            # メイン形質のトップ変異を取得
            df = self.load_and_analyze_trait(self.primary_trait)
            if df is None:
                print("❌ No data available for summary table")
                return None
            
            # トップ50変異
            top_variants = df.nsmallest(50, 'P')
            
            # サマリーテーブル作成
            summary_table = top_variants[['CHR', 'SNP', 'BP', 'A1', 'A2', 'P', 'Z', 'N']].copy()
            
            # P値フォーマット
            summary_table['P_formatted'] = summary_table['P'].apply(
                lambda x: f"{x:.2e}" if pd.notna(x) else "NA"
            )
            
            # BETAとSEがある場合はOR計算
            if 'BETA' in df.columns and 'SE' in df.columns:
                summary_table['BETA'] = top_variants['BETA']
                summary_table['SE'] = top_variants['SE']
                summary_table['OR'] = np.exp(summary_table['BETA'].fillna(0))
                summary_table['95%_CI_Lower'] = np.exp(summary_table['BETA'] - 1.96 * summary_table['SE'])
                summary_table['95%_CI_Upper'] = np.exp(summary_table['BETA'] + 1.96 * summary_table['SE'])
            
            # 保存
            output_file = self.output_dir / f'Table1_Top_Variants_{self.primary_trait}.csv'
            summary_table.to_csv(output_file, index=False)
            
            # HTML版も保存
            html_file = self.output_dir / f'Table1_Top_Variants_{self.primary_trait}.html'
            summary_table.to_html(html_file, index=False, table_id='top_variants')
            
            print(f"✅ Summary table saved: {output_file}")
            print(f"✅ HTML table saved: {html_file}")
            
            # プレビュー表示
            print("\n🏆 Top 10 variants preview:")
            display_cols = ['CHR', 'SNP', 'P_formatted', 'Z']
            if 'OR' in summary_table.columns:
                display_cols.append('OR')
            
            print(summary_table.head(10)[display_cols].to_string(index=False))
            
            return summary_table
            
        except Exception as e:
            print(f"❌ Error generating summary table: {e}")
            return None
    
    def run_complete_analysis(self):
        """Run complete analysis pipeline"""
        print("🚀 Starting Complete GWAS Analysis Pipeline...")
        print("=" * 60)
        
        results = {}
        
        try:
            # 1. データベース状態確認
            print("\n1️⃣ Database Status Check")
            if not self.check_database_status():
                print("❌ Database not ready. Exiting analysis.")
                return results
            
            # 2. 主要形質の解析
            print("\n2️⃣ Primary Trait Analysis")
            primary_data = self.load_and_analyze_trait(self.primary_trait)
            results['primary_analysis'] = primary_data is not None
            
            # 3. 品質管理解析
            print("\n3️⃣ Quality Control Analysis")
            qc_results = self.run_quality_control_analysis(self.primary_trait)
            results['quality_control'] = qc_results
            
            # 4. 包括的サマリー
            print("\n4️⃣ Comprehensive Summary")
            summary_df = self.generate_comprehensive_summary()
            results['comprehensive_summary'] = summary_df is not None
            
            # 5. クロス形質解析
            print("\n5️⃣ Cross-trait Analysis")
            available_traits = self.manager.scan_available_traits()
            if len(available_traits) >= 2:
                trait2 = 'PASS_Height1' if 'PASS_Height1' in available_traits else available_traits[1]
                cross_results = self.perform_cross_trait_analysis(self.primary_trait, trait2)
                results['cross_trait'] = cross_results is not None
            
            # 6. サマリーテーブル
            print("\n6️⃣ Summary Table Generation")
            summary_table = self.generate_summary_table()
            results['summary_table'] = summary_table is not None
            
            # 7. 図表生成
            print("\n7️⃣ Publication Figures")
            figure_results = self.generate_publication_figures()
            results['figures'] = figure_results is not None
            
            # 8. 最終レポート
            print("\n8️⃣ Final Report")
            self.generate_final_report(results)
            
            print("\n🎉 Complete analysis pipeline finished!")
            print(f"📁 Results saved in: {self.output_dir}")
            
            return results
            
        except Exception as e:
            print(f"❌ Analysis pipeline error: {e}")
            import traceback
            traceback.print_exc()
            return results
    
    def generate_final_report(self, results):
        """Generate final analysis report"""
        print("📝 Generating final analysis report...")
        
        try:
            report_lines = []
            report_lines.append("# GWAS Analysis Report")
            report_lines.append(f"## Generated on: {pd.Timestamp.now().strftime('%Y-%m-%d %H:%M:%S')}")
            report_lines.append(f"## Primary Trait: {self.primary_trait}")
            report_lines.append("")
            
            # データベース情報
            db_info = self.manager.get_database_info()
            report_lines.append("## Database Information")
            report_lines.append(f"- Database size: {db_info['database_size_mb']:.1f} MB")
            report_lines.append(f"- Stored traits: {db_info['traits_stored']}")
            report_lines.append(f"- Total SNPs: {db_info['total_snps']:,}")
            report_lines.append("")
            
            # 解析結果サマリー
            report_lines.append("## Analysis Results Summary")
            for analysis, success in results.items():
                if isinstance(success, bool):
                    status = "✅ Completed" if success else "❌ Failed"
                    report_lines.append(f"- {analysis}: {status}")
                elif success is not None:
                    report_lines.append(f"- {analysis}: ✅ Completed")
                else:
                    report_lines.append(f"- {analysis}: ❌ Failed")
            
            report_lines.append("")
            
            # 品質管理結果
            if results.get('quality_control'):
                qc = results['quality_control']
                report_lines.append("## Quality Control Results")
                report_lines.append(f"- Lambda GC: {qc['lambda_gc']:.3f}")
                report_lines.append(f"- Extreme Z-scores (>10): {qc['extreme_values']:,}")
                report_lines.append("")
            
            # ファイル出力
            report_file = self.output_dir / 'analysis_report.md'
            with open(report_file, 'w', encoding='utf-8') as f:
                f.write('\n'.join(report_lines))
            
            print(f"✅ Final report saved: {report_file}")
            
        except Exception as e:
            print(f"❌ Error generating report: {e}")

def main():
    """Main function to run analysis"""
    print("🧬 GWAS Analysis Pipeline - DuckDB Backend")
    print("=" * 50)
    
    try:
        # デフォルト設定
        dataset_path = '../../dataset/'
        primary_trait = 'PASS_Intelligence_SavageJansen2018'
        
        # 解析パイプライン初期化
        analysis = PaperAnalysis(dataset_path, primary_trait)
        
        # 完全解析実行
        results = analysis.run_complete_analysis()
        
        print("\n🎯 Analysis Summary:")
        success_count = sum(1 for v in results.values() if v is not None and v is not False)
        total_count = len(results)
        print(f"Successfully completed: {success_count}/{total_count} analyses")
        
    except Exception as e:
        print(f"❌ Main execution error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    main() 