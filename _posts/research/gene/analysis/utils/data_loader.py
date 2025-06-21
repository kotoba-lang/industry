"""
DuckDB-based GWAS data loading utilities for high-performance analysis
"""

import pandas as pd
import numpy as np
import warnings
from pathlib import Path

warnings.filterwarnings('ignore')

# 絶対インポートに変更
try:
    from duckdb_manager import GWASDuckDBManager
except ImportError:
    try:
        from .duckdb_manager import GWASDuckDBManager
    except ImportError:
        import sys
        import os
        sys.path.append(os.path.dirname(os.path.abspath(__file__)))
        from duckdb_manager import GWASDuckDBManager

class GWASDataLoader:
    """DuckDB-based GWAS data loader for high-performance analysis"""
    
    def __init__(self, dataset_path='../../dataset/', trait_id=None):
        """
        Initialize DuckDB-based data loader
        
        Args:
            dataset_path: Path to dataset directory containing DuckDB
            trait_id: Default trait ID to load (optional)
        """
        self.dataset_path = Path(dataset_path)
        self.trait_id = trait_id
        self.manager = GWASDuckDBManager(self.dataset_path)
        self.df = None
        self.processed = False
        
        # 利用可能な形質を確認
        self.available_traits = self.manager.scan_available_traits()
        
        print(f"🗄️ DuckDB GWAS Data Loader initialized")
        print(f"📊 Database: {self.manager.db_path}")
        
        db_info = self.manager.get_database_info()
        if db_info['status'] == 'ready':
            print(f"💾 Database size: {db_info['database_size_mb']:.1f}MB")
            print(f"🧬 Stored traits: {db_info['traits_stored']}")
            print(f"📈 Total SNPs: {db_info['total_snps']:,}")
        
    def load_trait_data(self, trait_id: str = None):
        """
        Load GWAS data for specific trait from DuckDB
        
        Args:
            trait_id: Trait identifier (e.g., 'PASS_Intelligence_SavageJansen2018')
        
        Returns:
            bool: Success status
        """
        if trait_id is None:
            trait_id = self.trait_id
            
        if trait_id is None:
            print("❌ No trait specified. Available traits:")
            self._show_available_traits()
            return False
            
        try:
            print(f"📥 Loading trait data: {trait_id}")
            
            # DuckDBから高速読み込み
            query = f"""
            SELECT snp_id as SNP, a1 as A1, a2 as A2, 
                   n as N, chisq as CHISQ, z_score as Z,
                   chromosome as CHR, position as BP
            FROM gwas_associations 
            WHERE trait_id = '{trait_id}'
            ORDER BY ABS(z_score) DESC
            """
            
            self.df = self.manager.query_gwas_data(query)
            
            if len(self.df) == 0:
                print(f"⚠️ No data found for trait: {trait_id}")
                print("Available traits:")
                self._show_available_traits()
                return False
            
            # P値を計算 (Z-scoreから)
            from scipy.stats import norm
            self.df['P'] = 2 * (1 - norm.cdf(np.abs(self.df['Z'])))
            
            # BETAとSEを推定 (必要に応じて)
            if 'BETA' not in self.df.columns and 'SE' not in self.df.columns:
                # Z-score から概算BETA/SE を推定
                # 仮定: SE ≈ 1/sqrt(N) * adjustment_factor
                se_estimate = 1.0 / np.sqrt(self.df['N'].fillna(self.df['N'].median()))
                self.df['SE'] = se_estimate
                self.df['BETA'] = self.df['Z'] * se_estimate
            
            self.trait_id = trait_id
            print(f"✅ Loaded {len(self.df)} variants for {trait_id}")
            
            return True
            
        except Exception as e:
            print(f"❌ Error loading trait data: {e}")
            return False
    
    def load_data(self, data_file=None):
        """
        Legacy method - loads default trait or shows available options
        Maintains compatibility with existing code
        """
        if data_file and data_file != 'gwas-data.csv':
            print(f"⚠️ File-based loading no longer supported: {data_file}")
            print("💡 Use load_trait_data(trait_id) instead")
        
        # デフォルト形質の選択 (Intelligence がよく使われる)
        default_traits = [
            'PASS_Intelligence_SavageJansen2018',
            'PASS_Height1',
            'PASS_BMI1'
        ]
        
        for trait in default_traits:
            if trait in self.available_traits:
                return self.load_trait_data(trait)
        
        # デフォルトがない場合は最初の利用可能な形質
        if self.available_traits:
            return self.load_trait_data(self.available_traits[0])
        
        print("❌ No traits available in database")
        return False
    
    def _show_available_traits(self, limit=10):
        """利用可能な形質リストを表示"""
        if not self.available_traits:
            print("No traits found in database")
            return
            
        print(f"\n📋 Available traits (showing first {limit}):")
        for i, trait in enumerate(self.available_traits[:limit]):
            print(f"  {i+1:2d}. {trait}")
        
        if len(self.available_traits) > limit:
            print(f"     ... and {len(self.available_traits) - limit} more")
        
        print(f"\n💡 Use: loader.load_trait_data('trait_name')")
    
    def preprocess_data(self):
        """Preprocess GWAS data for analysis"""
        if self.df is None:
            print("❌ No data loaded. Call load_trait_data() first.")
            return False
        
        try:
            original_count = len(self.df)
            print(f"🔧 Preprocessing {original_count:,} variants...")
            
            # データ型の確認・変換
            numeric_cols = ['CHR', 'BP', 'P', 'Z', 'N']
            for col in numeric_cols:
                if col in self.df.columns:
                    self.df[col] = pd.to_numeric(self.df[col], errors='coerce')
            
            # 欠損値の状況を確認
            missing_before = self.df.isna().sum()
            print(f"📊 Missing values: CHR={missing_before.get('CHR', 0)}, BP={missing_before.get('BP', 0)}, Z={missing_before.get('Z', 0)}")
            
            # 必須カラムの欠損値を除去（より寛容に）
            essential_cols = ['Z']  # Z-scoreが最も重要
            for col in essential_cols:
                if col in self.df.columns:
                    before_count = len(self.df)
                    self.df = self.df.dropna(subset=[col])
                    after_count = len(self.df)
                    if before_count != after_count:
                        print(f"⚠️ Removed {before_count - after_count:,} variants with missing {col}")
            
            # P値が0または負の場合を除去
            if 'P' in self.df.columns:
                before_count = len(self.df)
                self.df = self.df[self.df['P'] > 0]
                after_count = len(self.df)
                if before_count != after_count:
                    print(f"⚠️ Removed {before_count - after_count:,} variants with P <= 0")
            
            # -log10(P) 計算
            if 'P' in self.df.columns:
                self.df['neglog10p'] = -np.log10(self.df['P'].replace(0, 1e-300))
            else:
                # P値がない場合はZ-scoreから計算
                from scipy.stats import norm
                self.df['P'] = 2 * (1 - norm.cdf(np.abs(self.df['Z'])))
                self.df['neglog10p'] = -np.log10(self.df['P'].replace(0, 1e-300))
            
            # 染色体・位置情報がある場合のみManhattan plot用の処理
            if 'CHR' in self.df.columns and 'BP' in self.df.columns:
                try:
                    self._calculate_cumulative_positions()
                except Exception as e:
                    print(f"⚠️ Cumulative position calculation failed: {e}")
            
            # オッズ比計算 (BETAがある場合)
            if 'BETA' in self.df.columns:
                self.df['OR'] = np.exp(self.df['BETA'])
                if 'SE' in self.df.columns:
                    self.df['OR_lower'] = np.exp(self.df['BETA'] - 1.96 * self.df['SE'])
                    self.df['OR_upper'] = np.exp(self.df['BETA'] + 1.96 * self.df['SE'])
            
            # 有意性カテゴリ
            self.df['significance'] = self.df['P'].apply(self._categorize_significance)
            
            final_count = len(self.df)
            print(f"✅ Data preprocessing complete: {original_count:,} → {final_count:,} variants ({final_count/original_count*100:.1f}% retained)")
            
            if final_count == 0:
                print("❌ No variants remain after preprocessing!")
                return False
            
            self.processed = True
            return True
            
        except Exception as e:
            print(f"❌ Error in preprocessing: {e}")
            import traceback
            traceback.print_exc()
            return False
    
    def _calculate_cumulative_positions(self):
        """Calculate cumulative positions for Manhattan plot"""
        if 'CHR' not in self.df.columns or 'BP' not in self.df.columns:
            print("⚠️ CHR or BP columns missing. Skipping cumulative position calculation.")
            return
        
        # 有効な染色体・位置情報があるかチェック
        valid_chr = self.df['CHR'].notna() & (self.df['CHR'] > 0)
        valid_bp = self.df['BP'].notna() & (self.df['BP'] > 0)
        valid_pos = valid_chr & valid_bp
        
        if valid_pos.sum() == 0:
            print("⚠️ No valid chromosome/position data. Skipping cumulative position calculation.")
            self.df['pos_cum'] = 0  # デフォルト値
            return
        
        try:
            # 有効な位置情報のあるデータのみで計算
            valid_df = self.df[valid_pos].copy()
            
            # 染色体を整数に変換
            valid_df['CHR'] = valid_df['CHR'].astype(int)
            valid_df = valid_df[(valid_df['CHR'] >= 1) & (valid_df['CHR'] <= 22)]
            
            if len(valid_df) == 0:
                print("⚠️ No valid autosomal chromosomes found.")
                self.df['pos_cum'] = 0
                return
            
            # 累積位置計算
            valid_df = valid_df.sort_values(['CHR', 'BP'])
            chr_lengths = valid_df.groupby('CHR')['BP'].max()
            chr_starts = chr_lengths.cumsum() - chr_lengths
            
            # 全データに対して累積位置を設定
            self.df['pos_cum'] = 0  # デフォルト値
            
            for chr_id in chr_starts.index:
                chr_mask = (self.df['CHR'] == chr_id) & valid_pos
                self.df.loc[chr_mask, 'pos_cum'] = (
                    chr_starts[chr_id] + self.df.loc[chr_mask, 'BP']
                )
            
            # 染色体情報を保存
            self.chr_info = {
                'lengths': chr_lengths,
                'starts': chr_starts,
                'centers': chr_starts + chr_lengths / 2
            }
            
            print(f"✅ Cumulative positions calculated for {valid_pos.sum():,} variants")
            
        except Exception as e:
            print(f"⚠️ Cumulative position calculation failed: {e}")
            self.df['pos_cum'] = 0  # 失敗時のデフォルト値
    
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
        """Get top variants by P-value using DuckDB query"""
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        if p_threshold:
            filtered = self.df[self.df['P'] < p_threshold]
        else:
            filtered = self.df
        
        return filtered.nsmallest(n, 'P')
    
    def get_significant_variants(self, significance_level='genome_wide'):
        """Get variants by significance level using optimized query"""
        if significance_level == 'genome_wide':
            threshold = 5e-8
        elif significance_level == 'suggestive':
            threshold = 1e-6
        elif significance_level == 'nominal':
            threshold = 0.05
        else:
            return self.df[self.df['significance'] == significance_level]
        
        return self.df[self.df['P'] < threshold]
    
    def cross_trait_analysis(self, trait1: str, trait2: str):
        """
        Perform cross-trait analysis using DuckDB
        
        Args:
            trait1, trait2: Trait IDs to compare
            
        Returns:
            DataFrame with cross-trait results
        """
        print(f"🔬 Cross-trait analysis: {trait1} vs {trait2}")
        
        try:
            result = self.manager.cross_trait_analysis(trait1, trait2)
            return result
        except Exception as e:
            print(f"❌ Cross-trait analysis failed: {e}")
            return pd.DataFrame()
    
    def get_trait_summary(self, trait_id: str = None):
        """Get summary statistics for a trait using DuckDB"""
        if trait_id is None:
            trait_id = self.trait_id
            
        if trait_id is None:
            print("❌ No trait specified")
            return None
        
        try:
            query = f"""
            SELECT 
                COUNT(*) as total_snps,
                COUNT(CASE WHEN ABS(z_score) > 3.0 THEN 1 END) as significant_snps,
                MAX(ABS(z_score)) as max_zscore,
                AVG(n) as avg_sample_size
            FROM gwas_associations 
            WHERE trait_id = '{trait_id}'
            """
            
            summary = self.manager.query_gwas_data(query)
            
            if len(summary) > 0:
                return summary.iloc[0].to_dict()
            
        except Exception as e:
            print(f"❌ Error getting trait summary: {e}")
        
        return None
    
    def get_data_summary(self):
        """Get summary statistics of loaded data"""
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        try:
            summary = {
                'trait_id': self.trait_id,
                'total_variants': len(self.df),
                'chromosomes': sorted(self.df['CHR'].dropna().unique()) if 'CHR' in self.df.columns else [],
                'p_value_range': (self.df['P'].min(), self.df['P'].max()),
                'significance_counts': self.df['significance'].value_counts().to_dict(),
                'top_variant': self.df.loc[self.df['P'].idxmin()]['SNP'] if 'SNP' in self.df.columns else 'Unknown',
                'database_source': str(self.manager.db_path)
            }
            
            return summary
            
        except Exception as e:
            print(f"❌ Error generating summary: {e}")
            return None
    
    def load_and_process(self, trait_id: str = None):
        """Convenience method to load and process data in one step"""
        success = self.load_trait_data(trait_id) if trait_id else self.load_data()
        if success:
            self.preprocess_data()
        return self.df
    
    def list_available_traits(self):
        """List all available traits in the database"""
        try:
            status = self.manager.get_import_status()
            
            print(f"\n📊 GWAS Database Status:")
            print(f"  • Total traits available: {status['total_available']}")
            print(f"  • Imported to database: {status['imported_count']}")
            print(f"  • Completion rate: {status['completion_rate']:.1f}%")
            
            if status['recently_imported']:
                print(f"\n🆕 Recently imported traits:")
                for trait in status['recently_imported']:
                    print(f"  • {trait}")
            
            return self.available_traits
            
        except Exception as e:
            print(f"❌ Error listing traits: {e}")
            return []

# Legacy compatibility functions
def load_gwas_data(data_file=None, trait_id=None):
    """Legacy function for backward compatibility"""
    loader = GWASDataLoader(trait_id=trait_id)
    
    if trait_id:
        loader.load_trait_data(trait_id)
    else:
        loader.load_data(data_file)
    
    loader.preprocess_data()
    return loader.df

# Example usage
if __name__ == "__main__":
    # DuckDBベースの使用例
    loader = GWASDataLoader()
    
    # 利用可能な形質表示
    loader.list_available_traits()
    
    # 特定形質の読み込み
    if loader.load_trait_data('PASS_Intelligence_SavageJansen2018'):
        loader.preprocess_data()
        
        # データサマリー
        summary = loader.get_data_summary()
        print(f"\n📋 Data Summary:")
        for key, value in summary.items():
            print(f"  • {key}: {value}")
        
        # トップ変異
        top_variants = loader.get_top_variants(5)
        print(f"\n🏆 Top 5 variants:")
        print(top_variants[['SNP', 'P', 'Z', 'CHR', 'BP']])
    
    print("\n🚀 DuckDB-based GWAS analysis ready!") 