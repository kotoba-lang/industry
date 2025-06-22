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
    """DuckDB-based GWAS data loader for high-performance analysis with LDSC integration"""
    
    def __init__(self, dataset_path='../../dataset/', trait_id=None):
        """
        Initialize DuckDB-based data loader with LDSC integration
        
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
        
        # LDSC統合状況確認
        self.ldsc_available = self._check_ldsc_integration()
        
        print(f"🗄️ DuckDB GWAS Data Loader initialized")
        print(f"📊 Database: {self.manager.db_path}")
        
        db_info = self.manager.get_database_info()
        if db_info['status'] == 'ready':
            print(f"💾 Database size: {db_info['database_size_mb']:.1f}MB")
            print(f"🧬 Stored traits: {db_info['traits_stored']}")
            print(f"📈 Total SNPs: {db_info['total_snps']:,}")
        
        if self.ldsc_available:
            ldsc_info = self.manager.get_ldsc_dataset_info()
            print(f"🧬 LDSC Integration: ✅ Ready")
            print(f"📋 LD Scores: {ldsc_info.get('scores_count', 0):,}")
            print(f"🏷️ Annotations: {ldsc_info.get('annotations_count', 0):,}")
        else:
            print(f"🧬 LDSC Integration: ❌ Not available")
        
    def _check_ldsc_integration(self) -> bool:
        """LDSC統合状況をチェック"""
        try:
            ldsc_info = self.manager.get_ldsc_dataset_info()
            return ldsc_info.get('integration_complete', False)
        except:
            return False
    
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
                   n as N, z_score as Z,
                   chromosome as CHR, position as BP,
                   beta as BETA, se as SE, p_value as P
            FROM gwas_associations 
            WHERE trait_id = '{trait_id}'
            """
            
            self.df = self.manager.query_gwas_data(query)
            
            if len(self.df) == 0:
                print(f"⚠️ No data found for trait: {trait_id}")
                print("Available traits:")
                self._show_available_traits()
                return False
            
            # P値、BETA、SEがDBにない場合のフォールバック計算
            if 'P' not in self.df.columns or self.df['P'].isnull().all():
                from scipy.stats import norm
                self.df['P'] = 2 * (1 - norm.cdf(np.abs(self.df['Z'])))

            if 'BETA' not in self.df.columns or self.df['BETA'].isnull().all():
                if 'SE' in self.df.columns and self.df['SE'].notnull().any():
                     self.df['BETA'] = self.df['Z'] * self.df['SE']
            
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

    def get_ld_scores(self, snp_list: list, dataset_name: str = "baselineLF_v2.2_UKB") -> pd.DataFrame:
        """
        指定SNPのLDスコアを取得
        
        Args:
            snp_list: SNP ID のリスト
            dataset_name: LDSCデータセット名
            
        Returns:
            LDスコア情報のDataFrame
        """
        if not self.ldsc_available:
            print("❌ LDSC integration not available")
            return pd.DataFrame()
        
        try:
            # SNPリストをクエリ用に変換
            snp_list_str = "', '".join(snp_list)
            
            query = f"""
            SELECT snp_id, chromosome, bp, annotation_scores
            FROM ldsc_scores 
            WHERE dataset_name = '{dataset_name}' 
              AND snp_id IN ('{snp_list_str}')
            ORDER BY chromosome, bp
            """
            
            result = self.manager.query_gwas_data(query)
            
            if len(result) > 0:
                print(f"📊 Found LD scores for {len(result)}/{len(snp_list)} SNPs")
                
                # JSON形式のアノテーションスコアを展開
                import json
                expanded_scores = []
                for _, row in result.iterrows():
                    try:
                        scores = json.loads(row['annotation_scores'])
                        scores.update({
                            'snp_id': row['snp_id'],
                            'chromosome': row['chromosome'],
                            'bp': row['bp']
                        })
                        expanded_scores.append(scores)
                    except:
                        continue
                
                if expanded_scores:
                    return pd.DataFrame(expanded_scores)
            
            print(f"⚠️ No LD scores found for provided SNPs")
            return pd.DataFrame()
            
        except Exception as e:
            print(f"❌ Error retrieving LD scores: {e}")
            return pd.DataFrame()

    def get_functional_annotations(self, snp_list: list, dataset_name: str = "baselineLF_v2.2_UKB") -> pd.DataFrame:
        """
        指定SNPの機能的アノテーション情報を取得
        
        Args:
            snp_list: SNP ID のリスト
            dataset_name: LDSCデータセット名
            
        Returns:
            機能的アノテーション情報のDataFrame
        """
        if not self.ldsc_available:
            print("❌ LDSC integration not available")
            return pd.DataFrame()
        
        try:
            snp_list_str = "', '".join(snp_list)
            
            query = f"""
            SELECT snp_id, chromosome, bp, annotation_values
            FROM ldsc_annotations 
            WHERE dataset_name = '{dataset_name}' 
              AND snp_id IN ('{snp_list_str}')
            ORDER BY chromosome, bp
            """
            
            result = self.manager.query_gwas_data(query)
            
            if len(result) > 0:
                print(f"📋 Found annotations for {len(result)}/{len(snp_list)} SNPs")
                
                # JSON形式のアノテーション値を展開
                import json
                expanded_annotations = []
                for _, row in result.iterrows():
                    try:
                        annotations = json.loads(row['annotation_values'])
                        annotations.update({
                            'snp_id': row['snp_id'],
                            'chromosome': row['chromosome'],
                            'bp': row['bp']
                        })
                        expanded_annotations.append(annotations)
                    except:
                        continue
                
                if expanded_annotations:
                    return pd.DataFrame(expanded_annotations)
            
            print(f"⚠️ No annotations found for provided SNPs")
            return pd.DataFrame()
            
        except Exception as e:
            print(f"❌ Error retrieving annotations: {e}")
            return pd.DataFrame()

    def calculate_polygenic_score(self, effect_sizes: dict, 
                                 use_ld_weights: bool = True,
                                 dataset_name: str = "baselineLF_v2.2_UKB") -> dict:
        """
        ポリジェニックスコアをLD重みつきで計算
        
        Args:
            effect_sizes: {snp_id: effect_size} の辞書
            use_ld_weights: LD重みを使用するかどうか
            dataset_name: LDSCデータセット名
            
        Returns:
            ポリジェニックスコア計算結果
        """
        if not self.ldsc_available or not use_ld_weights:
            # 単純な合計スコア
            total_score = sum(effect_sizes.values())
            return {
                'polygenic_score': total_score,
                'snp_count': len(effect_sizes),
                'method': 'simple_sum'
            }
        
        try:
            snp_list = list(effect_sizes.keys())
            snp_list_str = "', '".join(snp_list)
            
            # LD重みを取得
            query = f"""
            SELECT snp_id, weight_score
            FROM ldsc_weights 
            WHERE dataset_name = '{dataset_name}' 
              AND snp_id IN ('{snp_list_str}')
            """
            
            weights = self.manager.query_gwas_data(query)
            
            if len(weights) == 0:
                print("⚠️ No LD weights found, using simple sum")
                total_score = sum(effect_sizes.values())
                return {
                    'polygenic_score': total_score,
                    'snp_count': len(effect_sizes),
                    'method': 'simple_sum'
                }
            
            # LD重みつきスコア計算
            weighted_score = 0.0
            weighted_count = 0
            
            for _, row in weights.iterrows():
                snp_id = row['snp_id']
                if snp_id in effect_sizes:
                    weight = row['weight_score']
                    effect = effect_sizes[snp_id]
                    weighted_score += effect * weight
                    weighted_count += 1
            
            print(f"📊 Calculated polygenic score using {weighted_count}/{len(effect_sizes)} LD-weighted SNPs")
            
            return {
                'polygenic_score': weighted_score,
                'snp_count': len(effect_sizes),
                'ld_weighted_count': weighted_count,
                'method': 'ld_weighted'
            }
            
        except Exception as e:
            print(f"❌ Error calculating polygenic score: {e}")
            # フォールバック
            total_score = sum(effect_sizes.values())
            return {
                'polygenic_score': total_score,
                'snp_count': len(effect_sizes),
                'method': 'simple_sum_fallback'
            }

    def analyze_top_variants_with_annotations(self, n: int = 20):
        """
        トップ変異に機能的アノテーション情報を付加して分析
        
        Args:
            n: 分析するトップ変異数
            
        Returns:
            アノテーション付きトップ変異のDataFrame
        """
        if not self.processed:
            print("❌ Data not preprocessed. Call preprocess_data() first.")
            return None
        
        try:
            # トップ変異を取得
            top_variants = self.get_top_variants(n)
            
            if len(top_variants) == 0:
                print("⚠️ No top variants found")
                return None
            
            snp_list = top_variants['SNP'].tolist()
            
            # 機能的アノテーションを取得
            if self.ldsc_available:
                annotations = self.get_functional_annotations(snp_list)
                
                if len(annotations) > 0:
                    # アノテーション情報をマージ
                    enhanced_variants = top_variants.merge(
                        annotations, 
                        left_on='SNP', 
                        right_on='snp_id', 
                        how='left'
                    )
                    
                    # 主要な機能的アノテーション列を選択
                    functional_cols = [col for col in annotations.columns 
                                     if any(keyword in col.lower() for keyword in 
                                           ['coding', 'promoter', 'enhancer', 'conserved', 'regulatory'])]
                    
                    if functional_cols:
                        print(f"📋 Added {len(functional_cols)} functional annotations")
                        return enhanced_variants
            
            print("⚠️ LDSC annotations not available, returning basic top variants")
            return top_variants
            
        except Exception as e:
            print(f"❌ Error analyzing top variants with annotations: {e}")
            return top_variants

    def estimate_heritability_ldsc(self, trait_id: str = None, 
                                  dataset_name: str = "baselineLF_v2.2_UKB") -> dict:
        """
        LDSC方法による遺伝率推定（簡易版）
        
        Args:
            trait_id: 形質ID
            dataset_name: LDSCデータセット名
            
        Returns:
            遺伝率推定結果
        """
        if trait_id is None:
            trait_id = self.trait_id
            
        if not self.ldsc_available or trait_id is None:
            print("❌ LDSC integration or trait data not available")
            return {}
        
        try:
            print(f"🧬 Estimating heritability for {trait_id} using LDSC approach...")
            
            # 形質のZ-scoreを取得
            query = f"""
            SELECT g.snp_id, g.z_score, g.n, l.weight_score
            FROM gwas_associations g
            JOIN ldsc_weights l ON g.snp_id = l.snp_id
            WHERE g.trait_id = '{trait_id}' 
              AND l.dataset_name = '{dataset_name}'
              AND ABS(g.z_score) < 30  -- 極端な値を除外
            """
            
            data = self.manager.query_gwas_data(query)
            
            if len(data) < 1000:
                print(f"⚠️ Insufficient overlapping SNPs for reliable heritability estimation: {len(data)}")
                return {}
            
            # 簡易LDSC回帰 (Chi-square vs LD Score)
            z_squared = data['z_score'] ** 2
            weights = data['weight_score']
            sample_size = data['n'].median()
            
            # 重み付き線形回帰
            from sklearn.linear_model import LinearRegression
            import numpy as np
            
            # 重みを正規化
            normalized_weights = weights / weights.mean()
            
            # 回帰: z^2 = intercept + slope * LD_score
            X = normalized_weights.values.reshape(-1, 1)
            y = z_squared.values
            
            reg = LinearRegression().fit(X, y)
            intercept = reg.intercept_
            slope = reg.coef_[0]
            
            # 遺伝率推定 (簡易版)
            # h2 = slope * M / N (Mは有効SNP数、Nはサンプルサイズ)
            M_eff = len(data)  # 簡易的に利用可能SNP数
            h2_estimate = slope * M_eff / sample_size if sample_size > 0 else 0
            
            # λGC (genomic inflation factor)
            lambda_gc = np.median(z_squared) / 0.4549
            
            print(f"📊 Heritability estimation completed:")
            print(f"   SNPs used: {len(data):,}")
            print(f"   h² estimate: {h2_estimate:.4f}")
            print(f"   λGC: {lambda_gc:.3f}")
            
            return {
                'trait_id': trait_id,
                'h2_estimate': h2_estimate,
                'lambda_gc': lambda_gc,
                'intercept': intercept,
                'slope': slope,
                'snp_count': len(data),
                'sample_size': sample_size,
                'method': 'simple_ldsc'
            }
            
        except Exception as e:
            print(f"❌ Error estimating heritability: {e}")
            return {}

    def cross_trait_ldsc_analysis(self, trait1: str, trait2: str,
                                 dataset_name: str = "baselineLF_v2.2_UKB") -> dict:
        """
        LD Score回帰による形質間相関分析
        
        Args:
            trait1, trait2: 比較する形質ID
            dataset_name: LDSCデータセット名
            
        Returns:
            形質間相関分析結果
        """
        if not self.ldsc_available:
            print("❌ LDSC integration not available")
            return {}
        
        try:
            print(f"🔬 LDSC cross-trait analysis: {trait1} vs {trait2}")
            
            # 両形質の共通SNPを取得
            query = f"""
            SELECT g1.snp_id, g1.z_score as z1, g2.z_score as z2,
                   g1.n as n1, g2.n as n2, l.weight_score
            FROM gwas_associations g1
            JOIN gwas_associations g2 ON g1.snp_id = g2.snp_id
            JOIN ldsc_weights l ON g1.snp_id = l.snp_id
            WHERE g1.trait_id = '{trait1}' 
              AND g2.trait_id = '{trait2}'
              AND l.dataset_name = '{dataset_name}'
              AND ABS(g1.z_score) < 30 AND ABS(g2.z_score) < 30
            """
            
            data = self.manager.query_gwas_data(query)
            
            if len(data) < 1000:
                print(f"⚠️ Insufficient overlapping SNPs: {len(data)}")
                return {}
            
            # 遺伝相関の簡易推定
            z1z2_product = data['z1'] * data['z2']
            weights = data['weight_score']
            
            # LD Score回帰アプローチ
            from sklearn.linear_model import LinearRegression
            import numpy as np
            
            normalized_weights = weights / weights.mean()
            X = normalized_weights.values.reshape(-1, 1)
            y = z1z2_product.values
            
            reg = LinearRegression().fit(X, y)
            intercept = reg.intercept_
            slope = reg.coef_[0]
            
            # 遺伝相関推定 (簡易版)
            sqrt_n1n2 = np.sqrt(data['n1'].median() * data['n2'].median())
            M_eff = len(data)
            rg_estimate = slope * M_eff / sqrt_n1n2 if sqrt_n1n2 > 0 else 0
            
            # 相関係数
            z_correlation = np.corrcoef(data['z1'], data['z2'])[0, 1]
            
            print(f"📊 Cross-trait analysis completed:")
            print(f"   Shared SNPs: {len(data):,}")
            print(f"   Genetic correlation: {rg_estimate:.4f}")
            print(f"   Z-score correlation: {z_correlation:.4f}")
            
            return {
                'trait1': trait1,
                'trait2': trait2,
                'genetic_correlation': rg_estimate,
                'z_correlation': z_correlation,
                'intercept': intercept,
                'slope': slope,
                'shared_snps': len(data),
                'method': 'simple_ldsc_cross_trait'
            }
            
        except Exception as e:
            print(f"❌ Error in cross-trait LDSC analysis: {e}")
            return {}

    def get_ldsc_summary(self) -> dict:
        """LDSC統合状況のサマリーを取得"""
        if not self.ldsc_available:
            return {'status': 'not_available', 'message': 'LDSC integration not found'}
        
        try:
            ldsc_info = self.manager.get_ldsc_dataset_info()
            
            # アノテーション種類の分析
            query = """
            SELECT COUNT(DISTINCT chromosome) as chromosomes,
                   COUNT(*) as total_entries
            FROM ldsc_scores 
            WHERE dataset_name = 'baselineLF_v2.2_UKB'
            """
            
            stats = self.manager.query_gwas_data(query)
            
            return {
                'status': 'available',
                'dataset_name': ldsc_info.get('dataset_name'),
                'version': ldsc_info.get('version'),
                'total_snps': ldsc_info.get('total_snps'),
                'ld_scores_count': ldsc_info.get('scores_count'),
                'annotations_count': ldsc_info.get('annotations_count'),
                'weights_count': ldsc_info.get('weights_count'),
                'chromosomes_covered': stats.iloc[0]['chromosomes'] if len(stats) > 0 else 0,
                'integration_complete': ldsc_info.get('integration_complete', False)
            }
            
        except Exception as e:
            return {'status': 'error', 'error': str(e)}

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