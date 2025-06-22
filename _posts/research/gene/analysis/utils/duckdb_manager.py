#!/usr/bin/env python3
"""
DuckDB ベース GWAS データベース管理システム

GitHub LFS対応の軽量・高速分析特化データベース
LDSC参照データ統合対応版
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
    DuckDB ベース GWAS データ管理システム (LDSC統合版)
    
    特徴:
    - GitHub LFS対応の軽量DBファイル
    - 分析特化の高速クエリ
    - SQL互換でpandas連携
    - 自動圧縮・最適化
    - LDSC参照データ統合対応
    """
    
    def __init__(self, dataset_path: Path, db_path: Optional[Path] = None):
        """
        初期化
        
        Args:
            dataset_path: datasetディレクトリのパス
            db_path: DuckDBファイルパス (None の場合は自動生成)
        """
        self.dataset_path = Path(dataset_path)
        self.sumstats_path = self.dataset_path.parent / "reference_data"
        
        # LDSC参照データパス
        self.ldsc_reference_path = self.dataset_path.parent / "analysis" / "reference_data" / "ldsc_reference"
        self.baseline_path = self.ldsc_reference_path / "baselineLF_v2.2.UKB"
        
        # DuckDBファイルパス設定
        if db_path is None:
            self.db_path = self.dataset_path / "gwas_data.duckdb"
        else:
            self.db_path = Path(db_path)
            
        self.metadata_path = self.db_path.parent / f"{self.db_path.stem}_metadata.json"
        
        # 高頻度アクセス形質リスト
        self.high_priority_traits = [
            'Japanese_HighIQ_GWAS_2024',
            'EastAsian_EducationalAttainment_GWAS_Chen2024',
            'Savage2018_Intelligence_GWAS_European_OpenGWAS',
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
        """データベーススキーマ作成（LDSC対応版）"""
        if not self.conn:
            return
            
        # 既存のGWASテーブル作成
        self._create_gwas_schema()
        
        # LDSC参照データテーブル作成
        self._create_ldsc_schema()
        
        self.logger.info("📋 DuckDB schema created/verified (LDSC integrated)")
    
    def _create_gwas_schema(self):
        """GWAS関連テーブル作成"""
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

    def _create_ldsc_schema(self):
        """LDSC参照データ用テーブル作成"""
        
        # LDSC メタデータテーブル
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS ldsc_metadata (
                dataset_name VARCHAR PRIMARY KEY,
                version VARCHAR,
                population VARCHAR,
                n_chromosomes INTEGER,
                n_annotations INTEGER,
                total_snps BIGINT,
                imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                data_path VARCHAR
            )
        """)
        
        # LD Score データテーブル
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS ldsc_scores (
                dataset_name VARCHAR,
                chromosome INTEGER,
                snp_id VARCHAR,
                bp BIGINT,
                cm DOUBLE,
                maf DOUBLE,
                annotation_scores TEXT,  -- JSON形式でアノテーションスコア格納
                PRIMARY KEY (dataset_name, chromosome, snp_id)
            )
        """)
        
        # アノテーションデータテーブル
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS ldsc_annotations (
                dataset_name VARCHAR,
                chromosome INTEGER,
                snp_id VARCHAR,
                bp BIGINT,
                cm DOUBLE,
                annotation_values TEXT,  -- JSON形式でアノテーション値格納
                PRIMARY KEY (dataset_name, chromosome, snp_id)
            )
        """)
        
        # M ファイル（SNP数）データテーブル
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS ldsc_m_files (
                dataset_name VARCHAR,
                chromosome INTEGER,
                annotation_name VARCHAR,
                m_5_50 DOUBLE,
                m_all DOUBLE,
                PRIMARY KEY (dataset_name, chromosome, annotation_name)
            )
        """)
        
        # 重みファイルデータテーブル
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS ldsc_weights (
                dataset_name VARCHAR,
                chromosome INTEGER,
                snp_id VARCHAR,
                bp BIGINT,
                weight_score DOUBLE,
                PRIMARY KEY (dataset_name, chromosome, snp_id)
            )
        """)
        
        # LDSC用インデックス
        self.conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_ldsc_scores_chr_snp 
            ON ldsc_scores (chromosome, snp_id)
        """)
        
        self.conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_ldsc_annotations_chr_snp 
            ON ldsc_annotations (chromosome, snp_id)
        """)
        
        self.conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_ldsc_weights_chr_snp 
            ON ldsc_weights (chromosome, snp_id)
        """)

    def _import_ldscore_file(self, dataset_name: str, chr_num: int, file_path: Path) -> int:
        """LD Scoreファイルをインポート（最適化版）"""
        try:
            self.logger.info(f"📊 Reading LD Score file: {file_path.name}")
            
            # ファイル読み込み（チャンク処理で メモリ効率化）
            chunk_size = 10000  # 1万行ずつ処理
            total_rows = 0
            processed_snps = set()  # 重複チェック用
            
            # ファイルのチャンク読み込み
            for chunk_idx, df_chunk in enumerate(pd.read_csv(file_path, sep='\t', compression='gzip', chunksize=chunk_size)):
                
                # 基本カラム確認
                required_cols = ['CHR', 'SNP', 'BP']
                if not all(col in df_chunk.columns for col in required_cols):
                    self.logger.warning(f"⚠️ Missing required columns in {file_path}")
                    return 0
                
                # 重複SNP除去
                df_chunk = df_chunk.drop_duplicates(subset=['SNP'])
                df_chunk = df_chunk[~df_chunk['SNP'].isin(processed_snps)]
                processed_snps.update(df_chunk['SNP'])
                
                if len(df_chunk) == 0:
                    continue
                
                # オプショナルカラム確認
                cm_col = 'CM' if 'CM' in df_chunk.columns else None
                maf_col = 'MAF' if 'MAF' in df_chunk.columns else None
                
                # アノテーションスコアカラムを特定
                score_cols = [col for col in df_chunk.columns if col not in ['CHR', 'SNP', 'BP', 'CM', 'MAF']]
                
                # アノテーションスコアをJSON形式で格納
                annotation_scores = []
                for _, row in df_chunk.iterrows():
                    scores = {col: float(row[col]) if pd.notna(row[col]) else 0.0 for col in score_cols}
                    annotation_scores.append(json.dumps(scores))
                
                # データフレーム準備
                import_df = pd.DataFrame({
                    'dataset_name': dataset_name,
                    'chromosome': chr_num,
                    'snp_id': df_chunk['SNP'],
                    'bp': df_chunk['BP'],
                    'cm': df_chunk[cm_col] if cm_col else None,
                    'maf': df_chunk[maf_col] if maf_col else None,
                    'annotation_scores': annotation_scores
                })
                
                # DuckDBに挿入（ON CONFLICT IGNORE for duplicates）
                self.conn.register('temp_ldsc_scores', import_df)
                self.conn.execute("""
                    INSERT OR IGNORE INTO ldsc_scores 
                    (dataset_name, chromosome, snp_id, bp, cm, maf, annotation_scores)
                    SELECT dataset_name, chromosome, snp_id, bp, cm, maf, annotation_scores
                    FROM temp_ldsc_scores
                """)
                
                total_rows += len(df_chunk)
                
                # 進捗表示
                if chunk_idx % 10 == 0:
                    self.logger.info(f"📊 Chr{chr_num} LD scores: {total_rows:,} processed...")
            
            annotation_count = len(score_cols) if 'score_cols' in locals() else 0
            self.logger.info(f"✅ Chr{chr_num} LD scores completed: {total_rows:,} SNPs ({annotation_count} annotations)")
            return total_rows
            
        except Exception as e:
            self.logger.error(f"❌ Failed to import LD scores for chr{chr_num}: {e}")
            return 0

    def _import_annotation_file(self, dataset_name: str, chr_num: int, file_path: Path) -> int:
        """アノテーションファイルをインポート（最適化版）"""
        try:
            self.logger.info(f"📋 Reading annotation file: {file_path.name}")
            
            # ファイル読み込み（チャンク処理）
            chunk_size = 10000
            total_rows = 0
            processed_snps = set()  # 重複チェック用
            
            # ファイルのチャンク読み込み
            for chunk_idx, df_chunk in enumerate(pd.read_csv(file_path, sep='\t', compression='gzip', chunksize=chunk_size)):
                
                # 基本カラム
                base_cols = ['CHR', 'SNP', 'BP']
                optional_cols = ['CM']
                
                if not all(col in df_chunk.columns for col in base_cols):
                    self.logger.warning(f"⚠️ Missing base columns in {file_path}")
                    return 0
                
                # 重複SNP除去
                df_chunk = df_chunk.drop_duplicates(subset=['SNP'])
                df_chunk = df_chunk[~df_chunk['SNP'].isin(processed_snps)]
                processed_snps.update(df_chunk['SNP'])
                
                if len(df_chunk) == 0:
                    continue
                
                # アノテーションカラムを特定
                all_base_cols = base_cols + [col for col in optional_cols if col in df_chunk.columns]
                annot_cols = [col for col in df_chunk.columns if col not in all_base_cols]
                
                # アノテーション値をJSON形式で格納（高速化）
                annotation_values = []
                for _, row in df_chunk.iterrows():
                    values = {}
                    for col in annot_cols:
                        try:
                            val = row[col]
                            if pd.notna(val):
                                # 高速数値変換
                                values[col] = float(val) if '.' in str(val) else int(float(val))
                            else:
                                values[col] = 0
                        except (ValueError, TypeError):
                            values[col] = 0
                            
                    annotation_values.append(json.dumps(values))
                
                # データフレーム準備
                import_df = pd.DataFrame({
                    'dataset_name': dataset_name,
                    'chromosome': chr_num,
                    'snp_id': df_chunk['SNP'],
                    'bp': df_chunk['BP'],
                    'cm': df_chunk['CM'] if 'CM' in df_chunk.columns else None,
                    'annotation_values': annotation_values
                })
                
                # DuckDBに挿入（ON CONFLICT IGNORE for duplicates）
                self.conn.register('temp_ldsc_annotations', import_df)
                self.conn.execute("""
                    INSERT OR IGNORE INTO ldsc_annotations 
                    (dataset_name, chromosome, snp_id, bp, cm, annotation_values)
                    SELECT dataset_name, chromosome, snp_id, bp, cm, annotation_values
                    FROM temp_ldsc_annotations
                """)
                
                total_rows += len(df_chunk)
                
                # 進捗表示
                if chunk_idx % 10 == 0:
                    self.logger.info(f"📋 Chr{chr_num} annotations: {total_rows:,} processed...")
            
            annotation_count = len(annot_cols) if 'annot_cols' in locals() else 0
            self.logger.info(f"✅ Chr{chr_num} annotations completed: {total_rows:,} SNPs ({annotation_count} types)")
            return total_rows
            
        except Exception as e:
            self.logger.error(f"❌ Failed to import annotations for chr{chr_num}: {e}")
            return 0

    def import_baseline_ldsc_data(self, dataset_name: str = "baselineLF_v2.2_UKB") -> bool:
        """
        baselineLF v2.2 UKBデータセットをDuckDBに統合（最適化版）
        
        Args:
            dataset_name: データセット識別名
            
        Returns:
            インポート成功フラグ
        """
        if not self.conn:
            self.logger.error("❌ DuckDB not available")
            return False
            
        if not self.baseline_path.exists():
            self.logger.error(f"❌ Baseline path not found: {self.baseline_path}")
            return False
            
        self.logger.info(f"🧬 Starting LDSC baseline data import: {dataset_name}")
        self.logger.info(f"⚙️ Using optimized batch processing for large files")
        
        try:
            # 既存データクリア
            self._clear_ldsc_dataset(dataset_name)
            
            # 各染色体のデータをインポート
            chromosomes = range(1, 23)  # 1-22
            total_snps = 0
            annotation_count = 0
            
            for chr_num in chromosomes:
                self.logger.info(f"🧬 Processing chromosome {chr_num}/22...")
                
                # LD Scoreファイル
                ldscore_file = self.baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.ldscore.gz"
                # アノテーションファイル  
                annot_file = self.baseline_path / f"baselineLF2.2.UKB.{chr_num}.annot.gz"
                # Mファイル
                m_file = self.baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.M"
                m_5_50_file = self.baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.M_5_50"
                # 重みファイル
                weight_file = self.baseline_path / f"weights.UKB.{chr_num}.l2.ldscore.gz"
                
                # LD Score データインポート（優先）
                if ldscore_file.exists():
                    chr_snps = self._import_ldscore_file(dataset_name, chr_num, ldscore_file)
                    total_snps += chr_snps
                    
                # アノテーションデータインポート（優先）
                if annot_file.exists():
                    self._import_annotation_file(dataset_name, chr_num, annot_file)
                    
                # Mファイルインポート（軽量）
                if m_file.exists() and m_5_50_file.exists():
                    annotation_count = self._import_m_files(dataset_name, chr_num, m_file, m_5_50_file)
                    
                # 重みファイルインポート（軽量）
                if weight_file.exists():
                    self._import_weight_file(dataset_name, chr_num, weight_file)
                
                # 中間最適化（3染色体ごと）
                if chr_num % 3 == 0:
                    self.logger.info(f"🔧 Intermediate optimization after chr{chr_num}...")
                    self.conn.execute("PRAGMA optimize")
            
            # メタデータ登録
            self.conn.execute("""
                INSERT INTO ldsc_metadata 
                (dataset_name, version, population, n_chromosomes, n_annotations, total_snps, data_path)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, [dataset_name, "v2.2", "UKB", 22, annotation_count, total_snps, str(self.baseline_path)])
            
            self.logger.info(f"✅ LDSC baseline data import completed!")
            self.logger.info(f"📊 Dataset: {dataset_name}")
            self.logger.info(f"🧬 Total SNPs: {total_snps:,}")
            self.logger.info(f"📋 Annotations: {annotation_count}")
            
            # データベース最適化
            self.logger.info("🔧 Final database optimization...")
            self._optimize_database()
            
            return True
            
        except Exception as e:
            self.logger.error(f"❌ LDSC baseline import failed: {e}")
            return False

    def _clear_ldsc_dataset(self, dataset_name: str):
        """指定データセットのLDSCデータを削除"""
        tables = ['ldsc_scores', 'ldsc_annotations', 'ldsc_m_files', 'ldsc_weights', 'ldsc_metadata']
        
        for table in tables:
            self.conn.execute(f"DELETE FROM {table} WHERE dataset_name = ?", [dataset_name])
        
        self.logger.info(f"🗑️ Cleared existing data for dataset: {dataset_name}")

    def _import_m_files(self, dataset_name: str, chr_num: int, m_file: Path, m_5_50_file: Path) -> int:
        """Mファイル（SNP数情報）をインポート"""
        try:
            # M (全体) ファイル読み込み
            with open(m_file, 'r') as f:
                m_all_data = f.read().strip().split('\n')
            
            # M_5_50 ファイル読み込み  
            with open(m_5_50_file, 'r') as f:
                m_5_50_data = f.read().strip().split('\n')
            
            if len(m_all_data) != len(m_5_50_data):
                self.logger.warning(f"⚠️ M file length mismatch for chr{chr_num}")
                return 0
            
            # アノテーション名を推定（実際のファイルから取得する場合は別途実装）
            annotation_count = len(m_all_data)
            
            # データ挿入
            for i, (m_all_val, m_5_50_val) in enumerate(zip(m_all_data, m_5_50_data)):
                annotation_name = f"annotation_{i+1}"  # 仮のアノテーション名
                
                self.conn.execute("""
                    INSERT INTO ldsc_m_files 
                    (dataset_name, chromosome, annotation_name, m_5_50, m_all)
                    VALUES (?, ?, ?, ?, ?)
                """, [dataset_name, chr_num, annotation_name, float(m_5_50_val), float(m_all_val)])
            
            self.logger.info(f"📊 Imported {annotation_count} M values for chr{chr_num}")
            return annotation_count
            
        except Exception as e:
            self.logger.error(f"❌ Failed to import M files for chr{chr_num}: {e}")
            return 0

    def _import_weight_file(self, dataset_name: str, chr_num: int, file_path: Path) -> int:
        """重みファイルをインポート"""
        try:
            # ファイル読み込み
            df = pd.read_csv(file_path, sep='\t', compression='gzip')
            
            # 必要カラム確認
            if 'SNP' not in df.columns or 'BP' not in df.columns:
                self.logger.warning(f"⚠️ Missing required columns in weight file {file_path}")
                return 0
            
            # 重みスコアカラムを特定（通常は最後のカラム）
            weight_col = df.columns[-1]
            
            # データフレーム準備
            import_df = pd.DataFrame({
                'dataset_name': dataset_name,
                'chromosome': chr_num,
                'snp_id': df['SNP'],
                'bp': df['BP'],
                'weight_score': df[weight_col]
            })
            
            # DuckDBに挿入
            self.conn.register('temp_ldsc_weights', import_df)
            self.conn.execute("""
                INSERT INTO ldsc_weights 
                (dataset_name, chromosome, snp_id, bp, weight_score)
                SELECT dataset_name, chromosome, snp_id, bp, weight_score
                FROM temp_ldsc_weights
            """)
            
            snp_count = len(df)
            self.logger.info(f"⚖️ Imported {snp_count:,} weights for chr{chr_num}")
            return snp_count
            
        except Exception as e:
            self.logger.error(f"❌ Failed to import weights for chr{chr_num}: {e}")
            return 0

    def get_ldsc_dataset_info(self, dataset_name: str = "baselineLF_v2.2_UKB") -> Dict:
        """LDSC データセット情報を取得"""
        if not self.conn:
            return {"status": "unavailable"}
            
        try:
            # メタデータ取得
            metadata = self.conn.execute("""
                SELECT * FROM ldsc_metadata WHERE dataset_name = ?
            """, [dataset_name]).fetchone()
            
            if not metadata:
                return {"status": "not_found", "dataset_name": dataset_name}
            
            # 各テーブルのSNP数確認
            scores_count = self.conn.execute("""
                SELECT COUNT(*) FROM ldsc_scores WHERE dataset_name = ?
            """, [dataset_name]).fetchone()[0]
            
            annotations_count = self.conn.execute("""
                SELECT COUNT(*) FROM ldsc_annotations WHERE dataset_name = ?
            """, [dataset_name]).fetchone()[0]
            
            weights_count = self.conn.execute("""
                SELECT COUNT(*) FROM ldsc_weights WHERE dataset_name = ?
            """, [dataset_name]).fetchone()[0]
            
            m_entries_count = self.conn.execute("""
                SELECT COUNT(*) FROM ldsc_m_files WHERE dataset_name = ?
            """, [dataset_name]).fetchone()[0]
            
            return {
                "status": "ready",
                "dataset_name": metadata[0],
                "version": metadata[1],
                "population": metadata[2],
                "n_chromosomes": metadata[3],
                "n_annotations": metadata[4],
                "total_snps": metadata[5],
                "imported_at": metadata[6],
                "data_path": metadata[7],
                "scores_count": scores_count,
                "annotations_count": annotations_count,
                "weights_count": weights_count,
                "m_entries_count": m_entries_count,
                "integration_complete": scores_count > 0 and annotations_count > 0
            }
            
        except Exception as e:
            return {"status": "error", "error": str(e)}

    def scan_available_traits(self) -> List[str]:
        """利用可能な形質リストを取得"""
        # .tsv ファイルをスキャン対象に追加
        trait_files = list(self.sumstats_path.glob("*.tsv")) + list(self.sumstats_path.glob("*.sumstats.gz"))
        traits = [f.stem.replace(".sumstats", "").replace(".vcf", "") for f in trait_files]
        
        self.logger.info(f"📊 Found {len(traits)} available traits in {self.sumstats_path}")
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
        
        # ファイル検索ロジックを柔軟化
        file_path_gz = self.sumstats_path / f"{trait_id}.sumstats.gz"
        file_path_tsv = self.sumstats_path / f"{trait_id}.tsv"
        
        if file_path_gz.exists():
            file_path = file_path_gz
            compression = 'gzip'
        elif file_path_tsv.exists():
            file_path = file_path_tsv
            compression = None
        else:
            self.logger.error(f"❌ File not found for trait {trait_id} in {self.sumstats_path}")
            return False
        
        try:
            self.logger.info(f"📥 Importing {trait_id} from {file_path.name}...")
            
            # ファイルサイズ情報
            compressed_size_mb = file_path.stat().st_size / (1024 * 1024)
            
            # データ読み込み
            df = pd.read_csv(file_path, sep='\\t', compression=compression, engine='python', on_bad_lines='warn')
            
            # カラム名標準化（柔軟なマッピング）
            column_mapping = {
                # Standard
                'SNP': 'snp_id', 'A1': 'a1', 'A2': 'a2', 'N': 'n',
                'CHISQ': 'chisq', 'Z': 'z_score', 'P': 'p_value',
                'CHR': 'chromosome', 'BP': 'position', 'BETA': 'beta', 'SE': 'se',
                # Variations
                'variant_id': 'snp_id', 'effect_allele': 'a1', 'other_allele': 'a2',
                'p_value': 'p_value', 'beta': 'beta', 'standard_error': 'se',
                'chromosome': 'chromosome', 'base_pair_location': 'position',
                'ID': 'snp_id', 'P-value': 'p_value', 'Effect': 'beta'
            }
            
            # マッピングを適用
            df = df.rename(columns=lambda c: column_mapping.get(c, c))

            # 不足している必須カラムを計算で補完
            if 'z_score' not in df.columns and 'beta' in df.columns and 'se' in df.columns:
                 # Z-scoreを計算
                df['beta'] = pd.to_numeric(df['beta'], errors='coerce')
                df['se'] = pd.to_numeric(df['se'], errors='coerce')
                df['z_score'] = df['beta'] / df['se']
                self.logger.info("📈 Calculated z_score from beta and se")
            
            # 必要なカラムを追加
            df['trait_id'] = trait_id
            
            # DuckDBのスキーマに存在するカラムのみを選択
            schema_cols = ['trait_id', 'snp_id', 'chromosome', 'position', 'a1', 'a2', 'n', 'chisq', 'z_score', 'p_value', 'beta', 'se']
            final_cols = [col for col in schema_cols if col in df.columns]
            
            if 'snp_id' not in final_cols:
                self.logger.error(f"❌ Critical column 'snp_id' not found after mapping for {trait_id}")
                return False
                
            insert_df = df[final_cols]

            # DuckDBに高速インサート
            if force_reimport:
                self.conn.execute("DELETE FROM gwas_associations WHERE trait_id = ?", [trait_id])
                self.conn.execute("DELETE FROM gwas_metadata WHERE trait_id = ?", [trait_id])
            
            # データ挿入
            self.conn.register('temp_gwas_data', insert_df)
            
            insert_cols_str = ', '.join(final_cols)
            select_cols_str = ', '.join([f'"{c}"' for c in final_cols]) # カラム名を引用符で囲む
            
            self.conn.execute(f"""
                INSERT INTO gwas_associations ({insert_cols_str})
                SELECT {select_cols_str}
                FROM temp_gwas_data
            """)
            
            # メタデータ更新
            is_high_priority = trait_id in self.high_priority_traits
            self.conn.execute("""
                INSERT OR REPLACE INTO gwas_metadata 
                (trait_id, file_path, compressed_size_mb, snp_count, is_high_priority)
                VALUES (?, ?, ?, ?, ?)
            """, [trait_id, str(file_path), compressed_size_mb, len(df), is_high_priority])
            
            self.logger.info(f"✅ {trait_id} imported: {len(df)} SNPs")
            return True
            
        except Exception as e:
            self.logger.error(f"❌ Import failed for {trait_id}: {e}")
            import traceback
            traceback.print_exc()
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
    
    def bulk_import_all_traits(self, batch_size: int = 10):
        """
        全形質を安全にバッチインポート
        
        Args:
            batch_size: バッチサイズ (メモリ制限対応)
        """
        if not self.conn:
            self.logger.error("❌ DuckDB not available")
            return
            
        available_traits = self.scan_available_traits()
        
        # 既存形質確認
        existing_traits = self.conn.execute('SELECT trait_id FROM gwas_metadata').fetchall()
        existing_trait_ids = [t[0] for t in existing_traits]
        
        # 未インポート形質のリスト
        remaining_traits = [t for t in available_traits if t not in existing_trait_ids]
        
        if not remaining_traits:
            self.logger.info("✅ All traits already imported")
            return
            
        self.logger.info(f"📦 Bulk importing {len(remaining_traits)} remaining traits in batches of {batch_size}...")
        
        success_count = 0
        error_count = 0
        
        # バッチ処理
        for i in range(0, len(remaining_traits), batch_size):
            batch = remaining_traits[i:i+batch_size]
            batch_num = (i // batch_size) + 1
            total_batches = (len(remaining_traits) + batch_size - 1) // batch_size
            
            self.logger.info(f"🔄 Processing batch {batch_num}/{total_batches} ({len(batch)} traits)")
            
            for j, trait in enumerate(batch):
                try:
                    self.logger.info(f"📥 [{batch_num}/{total_batches}] [{j+1}/{len(batch)}] Importing {trait}...")
                    
                    if self.import_trait_data(trait):
                        success_count += 1
                        self.logger.info(f"✅ {trait} imported successfully ({success_count}/{len(remaining_traits)})")
                    else:
                        error_count += 1
                        self.logger.error(f"❌ Failed to import {trait}")
                        
                except Exception as e:
                    error_count += 1
                    self.logger.error(f"❌ Error importing {trait}: {e}")
            
            # バッチ完了後に最適化
            if batch_num % 5 == 0:  # 5バッチごとに最適化
                self.logger.info("🔧 Optimizing database...")
                self._optimize_database()
                
                # 現在のサイズ確認
                db_info = self.get_database_info()
                self.logger.info(f"📊 Current DB size: {db_info['database_size_mb']:.1f}MB, Total SNPs: {db_info['total_snps']:,}")
        
        # 最終最適化
        self.logger.info("🔧 Final database optimization...")
        self._optimize_database()
        
        # 結果サマリー
        final_info = self.get_database_info()
        self.logger.info(f"✅ Bulk import completed!")
        self.logger.info(f"📊 Successfully imported: {success_count} traits")
        self.logger.info(f"❌ Failed imports: {error_count} traits")
        self.logger.info(f"💾 Final database size: {final_info['database_size_mb']:.1f}MB")
        self.logger.info(f"🧬 Total SNPs in database: {final_info['total_snps']:,}")

    def get_import_status(self) -> Dict:
        """インポート状況の詳細レポート"""
        if not self.conn:
            return {"status": "unavailable"}
            
        try:
            available_traits = self.scan_available_traits()
            
            # データベース内の形質情報
            db_traits = self.conn.execute("""
                SELECT trait_id, snp_count, compressed_size_mb, is_high_priority
                FROM gwas_metadata
                ORDER BY imported_at DESC
            """).fetchall()
            
            db_trait_ids = [t[0] for t in db_traits]
            missing_traits = [t for t in available_traits if t not in db_trait_ids]
            
            # 統計情報
            total_snps = sum(t[1] for t in db_traits if t[1])
            total_size_mb = sum(t[2] for t in db_traits if t[2])
            high_priority_count = sum(1 for t in db_traits if t[3])
            
            return {
                "status": "ready",
                "total_available": len(available_traits),
                "imported_count": len(db_trait_ids),
                "missing_count": len(missing_traits),
                "high_priority_imported": high_priority_count,
                "total_snps": total_snps,
                "database_size_mb": total_size_mb,
                "completion_rate": len(db_trait_ids) / len(available_traits) * 100,
                "missing_traits": missing_traits[:10] if missing_traits else [],  # 最初の10個
                "recently_imported": [t[0] for t in db_traits[:5]]  # 最近の5個
            }
            
        except Exception as e:
            return {"status": "error", "error": str(e)}
    
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