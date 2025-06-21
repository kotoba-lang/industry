#!/usr/bin/env python3
"""
DuckDB ベース GWAS データベース管理システム

GitHub LFS対応の軽量・高速分析特化データベース
"""

import pandas as pd
import os
import gzip
from pathlib import Path
from typing import Optional, List, Dict, Union
import logging
import json
from datetime import datetime

# DuckDBは Optional dependency として扱う
try:
    import duckdb
    DUCKDB_AVAILABLE = True
except ImportError:
    DUCKDB_AVAILABLE = False
    duckdb = None

class GWASDuckDBManager:
    """
    DuckDB ベース GWAS データ管理システム
    
    特徴:
    - GitHub LFS対応の軽量DBファイル
    - 分析特化の高速クエリ
    - SQL互換でpandas連携
    - 自動圧縮・最適化
    """
    
    def __init__(self, dataset_path: Path, db_path: Optional[Path] = None):
        """
        初期化
        
        Args:
            dataset_path: datasetディレクトリのパス
            db_path: DuckDBファイルパス (None の場合は自動生成)
        """
        self.dataset_path = Path(dataset_path)
        self.sumstats_path = self.dataset_path / "sumstats"
        
        # DuckDBファイルパス設定
        if db_path is None:
            self.db_path = self.dataset_path / "gwas_data.duckdb"
        else:
            self.db_path = Path(db_path)
            
        self.metadata_path = self.db_path.parent / f"{self.db_path.stem}_metadata.json"
        
        # 高頻度アクセス形質リスト
        self.high_priority_traits = [
            'PASS_Intelligence_SavageJansen2018',
            'PASS_Height1', 'PASS_BMI1', 'PASS_Schizophrenia',
            'PASS_Type_2_Diabetes', 'PASS_Coronary_Artery_Disease',
            'PASS_Neuroticism', 'PASS_HDL', 'PASS_LDL'
        ]
        
        self._init_logging()
        if DUCKDB_AVAILABLE:
            self._init_database()
        else:
            self.logger.error("❌ DuckDB not available. Install with: pip install duckdb")
    
    def _init_logging(self):
        """ロギング設定"""
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        self.logger = logging.getLogger(__name__)
    
    def _init_database(self):
        """DuckDBデータベース初期化"""
        try:
            # データベース接続 (ファイルベース)
            self.conn = duckdb.connect(str(self.db_path))
            
            # パフォーマンス最適化設定
            self.conn.execute("PRAGMA threads=4")
            self.conn.execute("PRAGMA memory_limit='4GB'")
            self.conn.execute("PRAGMA temp_directory='/tmp'")
            
            self._create_schema()
            self.logger.info(f"✅ DuckDB initialized: {self.db_path}")
            
        except Exception as e:
            self.logger.error(f"❌ DuckDB initialization failed: {e}")
            self.conn = None
    
    def _create_schema(self):
        """データベーススキーマ作成"""
        if not self.conn:
            return
            
        # メタデータテーブル
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS gwas_metadata (
                trait_id VARCHAR PRIMARY KEY,
                file_path VARCHAR,
                original_size_mb DOUBLE,
                compressed_size_mb DOUBLE,
                snp_count INTEGER,
                publication VARCHAR,
                population VARCHAR,
                imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_high_priority BOOLEAN DEFAULT FALSE
            )
        """)
        
        # メイン GWAS データテーブル (パーティション対応)
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS gwas_associations (
                trait_id VARCHAR,
                snp_id VARCHAR,
                chromosome INTEGER,
                position BIGINT,
                a1 VARCHAR,
                a2 VARCHAR,
                n INTEGER,
                chisq DOUBLE,
                z_score DOUBLE,
                p_value DOUBLE,
                beta DOUBLE,
                se DOUBLE
            )
        """)
        
        # 高速検索用インデックス
        self.conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_gwas_trait_snp 
            ON gwas_associations (trait_id, snp_id)
        """)
        
        self.conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_gwas_chromosome_position 
            ON gwas_associations (chromosome, position)
        """)
        
        self.conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_gwas_zscore 
            ON gwas_associations (trait_id, z_score DESC)
        """)
        
        self.logger.info("📋 DuckDB schema created/verified")
    
    def scan_available_traits(self) -> List[str]:
        """利用可能な形質リストを取得"""
        trait_files = list(self.sumstats_path.glob("*.sumstats.gz"))
        traits = [f.stem.replace(".sumstats", "") for f in trait_files]
        
        self.logger.info(f"📊 Found {len(traits)} available traits")
        return sorted(traits)
    
    def get_database_info(self) -> Dict:
        """データベース情報を取得"""
        if not self.conn:
            return {"status": "unavailable", "reason": "DuckDB not initialized"}
            
        try:
            # データベースファイルサイズ
            db_size_mb = 0
            if self.db_path.exists():
                db_size_mb = self.db_path.stat().st_size / (1024 * 1024)
            
            # 格納済み形質数
            trait_count = self.conn.execute(
                "SELECT COUNT(*) FROM gwas_metadata"
            ).fetchone()[0]
            
            # 総SNP数
            snp_count = self.conn.execute(
                "SELECT COUNT(*) FROM gwas_associations"
            ).fetchone()[0]
            
            return {
                "status": "ready",
                "database_file": str(self.db_path),
                "database_size_mb": round(db_size_mb, 2),
                "traits_stored": trait_count,
                "total_snps": snp_count,
                "github_lfs_ready": db_size_mb > 100,  # 100MB超でLFS推奨
                "last_updated": datetime.now().isoformat()
            }
            
        except Exception as e:
            return {"status": "error", "error": str(e)}
    
    def import_trait_data(self, trait_id: str, force_reimport: bool = False) -> bool:
        """
        形質データをDuckDBにインポート
        
        Args:
            trait_id: 形質ID
            force_reimport: 既存データを強制的に再インポート
            
        Returns:
            インポート成功フラグ
        """
        if not self.conn:
            self.logger.error("❌ DuckDB not available")
            return False
            
        # 既存チェック
        if not force_reimport:
            existing = self.conn.execute(
                "SELECT COUNT(*) FROM gwas_metadata WHERE trait_id = ?",
                [trait_id]
            ).fetchone()[0]
            
            if existing > 0:
                self.logger.info(f"✅ {trait_id} already imported (use force_reimport=True to override)")
                return True
        
        # ファイル読み込み
        file_path = self.sumstats_path / f"{trait_id}.sumstats.gz"
        if not file_path.exists():
            self.logger.error(f"❌ File not found: {file_path}")
            return False
        
        try:
            self.logger.info(f"📥 Importing {trait_id}...")
            
            # ファイルサイズ情報
            compressed_size_mb = file_path.stat().st_size / (1024 * 1024)
            
            # データ読み込み
            df = pd.read_csv(file_path, sep='\t', compression='gzip')
            
            # カラム名標準化
            column_mapping = {
                'SNP': 'snp_id',
                'A1': 'a1', 
                'A2': 'a2',
                'N': 'n',
                'CHISQ': 'chisq',
                'Z': 'z_score'
            }
            df = df.rename(columns=column_mapping)
            
            # 必要なカラムを追加
            df['trait_id'] = trait_id
            
            # DuckDBに高速インサート
            if force_reimport:
                self.conn.execute(
                    "DELETE FROM gwas_associations WHERE trait_id = ?",
                    [trait_id]
                )
                self.conn.execute(
                    "DELETE FROM gwas_metadata WHERE trait_id = ?",
                    [trait_id]
                )
            
            # データ挿入
            self.conn.register('temp_gwas_data', df)
            self.conn.execute("""
                INSERT INTO gwas_associations 
                (trait_id, snp_id, a1, a2, n, chisq, z_score)
                SELECT trait_id, snp_id, a1, a2, n, chisq, z_score 
                FROM temp_gwas_data
            """)
            
            # メタデータ更新
            is_high_priority = trait_id in self.high_priority_traits
            self.conn.execute("""
                INSERT INTO gwas_metadata 
                (trait_id, file_path, compressed_size_mb, snp_count, is_high_priority)
                VALUES (?, ?, ?, ?, ?)
            """, [trait_id, str(file_path), compressed_size_mb, len(df), is_high_priority])
            
            self.logger.info(f"✅ {trait_id} imported: {len(df)} SNPs")
            return True
            
        except Exception as e:
            self.logger.error(f"❌ Import failed for {trait_id}: {e}")
            return False
    
    def bulk_import_high_priority_traits(self):
        """高優先度形質を一括インポート"""
        if not self.conn:
            self.logger.error("❌ DuckDB not available")
            return
            
        available_traits = self.scan_available_traits()
        high_priority_available = [
            trait for trait in self.high_priority_traits 
            if trait in available_traits
        ]
        
        self.logger.info(f"📦 Bulk importing {len(high_priority_available)} high-priority traits...")
        
        success_count = 0
        for trait in high_priority_available:
            if self.import_trait_data(trait):
                success_count += 1
                
        self.logger.info(f"✅ Bulk import completed: {success_count}/{len(high_priority_available)} traits")
        
        # データベース最適化
        self._optimize_database()
    
    def query_gwas_data(self, query: str) -> pd.DataFrame:
        """
        SQL クエリ実行
        
        Args:
            query: SQL クエリ文字列
            
        Returns:
            クエリ結果のDataFrame
        """
        if not self.conn:
            self.logger.error("❌ DuckDB not available")
            return pd.DataFrame()
            
        try:
            return self.conn.execute(query).df()
        except Exception as e:
            self.logger.error(f"❌ Query failed: {e}")
            return pd.DataFrame()
    
    def cross_trait_analysis(self, trait1: str, trait2: str, 
                           significance_threshold: float = 1e-5) -> pd.DataFrame:
        """
        高速クロス形質解析
        
        Args:
            trait1, trait2: 比較する形質ID
            significance_threshold: 有意性閾値
            
        Returns:
            共通SNPの比較結果
        """
        query = f"""
        SELECT 
            g1.snp_id,
            g1.z_score as z_score_trait1,
            g2.z_score as z_score_trait2,
            g1.n as n_trait1,
            g2.n as n_trait2,
            CASE 
                WHEN g1.z_score * g2.z_score > 0 THEN true 
                ELSE false 
            END as concordant,
            ABS(g1.z_score) as abs_z1,
            ABS(g2.z_score) as abs_z2
        FROM gwas_associations g1
        JOIN gwas_associations g2 ON g1.snp_id = g2.snp_id
        WHERE g1.trait_id = '{trait1}' 
          AND g2.trait_id = '{trait2}'
          AND (ABS(g1.z_score) > 3.0 OR ABS(g2.z_score) > 3.0)
        ORDER BY GREATEST(ABS(g1.z_score), ABS(g2.z_score)) DESC
        """
        
        self.logger.info(f"🔬 Cross-trait analysis: {trait1} vs {trait2}")
        result = self.query_gwas_data(query)
        
        if len(result) > 0:
            concordance_rate = result['concordant'].mean() * 100
            self.logger.info(f"📊 Found {len(result)} significant shared SNPs (concordance: {concordance_rate:.1f}%)")
        
        return result
    
    def get_top_snps(self, trait_id: str, top_n: int = 100) -> pd.DataFrame:
        """
        形質の最も有意なSNPを取得
        
        Args:
            trait_id: 形質ID
            top_n: 取得するSNP数
            
        Returns:
            トップSNPのDataFrame
        """
        query = f"""
        SELECT snp_id, z_score, chisq, n
        FROM gwas_associations 
        WHERE trait_id = '{trait_id}'
        ORDER BY ABS(z_score) DESC 
        LIMIT {top_n}
        """
        
        return self.query_gwas_data(query)
    
    def _optimize_database(self):
        """データベース最適化"""
        if not self.conn:
            return
            
        try:
            self.logger.info("🔧 Optimizing database...")
            
            # 統計情報更新
            self.conn.execute("ANALYZE")
            
            # VACUUMでファイルサイズ最適化
            self.conn.execute("VACUUM")
            
            self.logger.info("✅ Database optimization completed")
            
        except Exception as e:
            self.logger.warning(f"⚠️ Database optimization failed: {e}")
    
    def export_for_github(self) -> Dict:
        """
        GitHub push 用データベース準備
        
        Returns:
            エクスポート情報
        """
        if not self.conn:
            return {"status": "error", "reason": "DuckDB not available"}
        
        try:
            # データベース最適化
            self._optimize_database()
            
            # ファイル情報取得
            db_info = self.get_database_info()
            
            # Git LFS設定チェック
            gitattributes_path = Path(".gitattributes")
            lfs_configured = False
            
            if gitattributes_path.exists():
                content = gitattributes_path.read_text()
                lfs_configured = "*.duckdb" in content
            
            # LFS設定が必要な場合は追加
            if not lfs_configured and db_info.get("database_size_mb", 0) > 50:
                with open(gitattributes_path, "a") as f:
                    f.write("*.duckdb filter=lfs diff=lfs merge=lfs -text\n")
                    f.write("*.duckdb.* filter=lfs diff=lfs merge=lfs -text\n")
                self.logger.info("📝 Added *.duckdb to .gitattributes for LFS")
            
            # メタデータファイル生成
            metadata = {
                "database_info": db_info,
                "export_timestamp": datetime.now().isoformat(),
                "git_lfs_required": db_info.get("database_size_mb", 0) > 100,
                "usage_instructions": {
                    "python": "from analysis.utils.duckdb_manager import GWASDuckDBManager; manager = GWASDuckDBManager('dataset/')",
                    "query_example": "manager.query_gwas_data('SELECT * FROM gwas_associations WHERE trait_id = \"PASS_Intelligence_SavageJansen2018\" LIMIT 10')"
                }
            }
            
            with open(self.metadata_path, 'w') as f:
                json.dump(metadata, f, indent=2)
            
            return {
                "status": "ready_for_github",
                "database_file": str(self.db_path),
                "metadata_file": str(self.metadata_path),
                "size_mb": db_info.get("database_size_mb", 0),
                "lfs_required": db_info.get("database_size_mb", 0) > 100,
                "git_add_command": f"git add {self.db_path} {self.metadata_path}"
            }
            
        except Exception as e:
            return {"status": "error", "error": str(e)}

# 便利関数
def create_github_ready_gwas_db(dataset_path: str, 
                               include_high_priority_only: bool = True) -> Dict:
    """
    GitHub対応 GWAS データベースを作成
    
    Args:
        dataset_path: datasetディレクトリパス
        include_high_priority_only: 高優先度形質のみインポート
        
    Returns:
        作成結果情報
    """
    manager = GWASDuckDBManager(dataset_path)
    
    if not DUCKDB_AVAILABLE:
        return {"status": "error", "reason": "DuckDB not installed"}
    
    if include_high_priority_only:
        manager.bulk_import_high_priority_traits()
    else:
        # 全形質インポート (時間がかかる)
        traits = manager.scan_available_traits()
        for trait in traits:
            manager.import_trait_data(trait)
    
    return manager.export_for_github()

# 使用例とテスト
if __name__ == "__main__":
    if not DUCKDB_AVAILABLE:
        print("❌ DuckDB not available. Install with: pip install duckdb")
        exit(1)
    
    # データベース作成テスト
    manager = GWASDuckDBManager("../../dataset")
    
    print("📋 DuckDB GWAS Manager Summary:")
    db_info = manager.get_database_info()
    for key, value in db_info.items():
        print(f"  • {key}: {value}")
    
    # サンプル形質インポート
    available_traits = manager.scan_available_traits()
    if available_traits:
        sample_trait = available_traits[0]
        print(f"\n🧪 Testing import: {sample_trait}")
        
        success = manager.import_trait_data(sample_trait)
        if success:
            # サンプルクエリ
            top_snps = manager.get_top_snps(sample_trait, 5)
            print(f"Top 5 SNPs for {sample_trait}:")
            print(top_snps)
        
        # GitHub準備
        export_info = manager.export_for_github()
        print(f"\n🚀 GitHub Export Status: {export_info['status']}")
    else:
        print("⚠️ No trait files found in dataset/sumstats/") 