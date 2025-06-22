#!/usr/bin/env python3
"""
LDSC統合クリーンアップ＆段階実行スクリプト

安全なデータベースリセットと段階的統合
"""

import sys
import os
from pathlib import Path
import logging
from datetime import datetime

# プロジェクトパスを追加
script_dir = Path(__file__).parent
project_root = script_dir.parent.parent
sys.path.insert(0, str(script_dir.parent / "utils"))

from duckdb_manager import GWASDuckDBManager

def setup_logging():
    """ログ設定"""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=[
            logging.FileHandler('clean_ldsc_integration.log'),
            logging.StreamHandler()
        ]
    )
    return logging.getLogger(__name__)

def clean_database(manager):
    """データベースクリーンアップ"""
    logger = logging.getLogger(__name__)
    
    logger.info("🧹 データベースクリーンアップ開始...")
    
    try:
        # LDSCテーブルをクリア
        ldsc_tables = ['ldsc_scores', 'ldsc_annotations', 'ldsc_m_files', 'ldsc_weights', 'ldsc_metadata']
        
        for table in ldsc_tables:
            manager.conn.execute(f"DELETE FROM {table}")
            logger.info(f"✅ {table} テーブルクリア完了")
        
        # データベース最適化 (DuckDB対応)
        try:
            manager.conn.execute("VACUUM")
            logger.info("✅ VACUUM実行完了")
        except Exception as e:
            logger.warning(f"⚠️ VACUUM実行できませんでした: {e}")
        
        # サイズ確認
        db_info = manager.get_database_info()
        logger.info(f"💾 クリーンアップ後サイズ: {db_info['database_size_mb']:.2f}MB")
        
        return True
        
    except Exception as e:
        logger.error(f"❌ クリーンアップエラー: {e}")
        return False

def import_single_chromosome_safe(manager, chr_num, dataset_name="baselineLF_v2.2_UKB"):
    """安全な単一染色体インポート"""
    logger = logging.getLogger(__name__)
    
    try:
        logger.info(f"🧬 Chr{chr_num} インポート開始...")
        
        baseline_path = manager.baseline_path
        
        # ファイルパス
        ldscore_file = baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.ldscore.gz"
        annot_file = baseline_path / f"baselineLF2.2.UKB.{chr_num}.annot.gz"
        m_file = baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.M"
        m_5_50_file = baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.M_5_50"
        weight_file = baseline_path / f"weights.UKB.{chr_num}.l2.ldscore.gz"
        
        results = {
            'chromosome': chr_num,
            'ld_scores': 0,
            'annotations': 0,
            'weights': 0,
            'success': False
        }
        
        # LD Scores (最重要)
        if ldscore_file.exists():
            try:
                ld_count = manager._import_ldscore_file(dataset_name, chr_num, ldscore_file)
                results['ld_scores'] = ld_count
                logger.info(f"✅ Chr{chr_num} LD Scores: {ld_count:,}")
            except Exception as e:
                logger.error(f"❌ Chr{chr_num} LD Scores エラー: {e}")
                return results
        
        # Annotations
        if annot_file.exists():
            try:
                annot_count = manager._import_annotation_file(dataset_name, chr_num, annot_file)
                results['annotations'] = annot_count
                logger.info(f"✅ Chr{chr_num} Annotations: {annot_count:,}")
            except Exception as e:
                logger.warning(f"⚠️ Chr{chr_num} Annotations エラー: {e}")
        
        # M files
        if m_file.exists() and m_5_50_file.exists():
            try:
                manager._import_m_files(dataset_name, chr_num, m_file, m_5_50_file)
                logger.info(f"✅ Chr{chr_num} M files")
            except Exception as e:
                logger.warning(f"⚠️ Chr{chr_num} M files エラー: {e}")
        
        # Weights
        if weight_file.exists():
            try:
                weight_count = manager._import_weight_file(dataset_name, chr_num, weight_file)
                results['weights'] = weight_count
                logger.info(f"✅ Chr{chr_num} Weights: {weight_count:,}")
            except Exception as e:
                logger.warning(f"⚠️ Chr{chr_num} Weights エラー: {e}")
        
        results['success'] = results['ld_scores'] > 0
        return results
        
    except Exception as e:
        logger.error(f"❌ Chr{chr_num} 全体エラー: {e}")
        return {'chromosome': chr_num, 'success': False, 'error': str(e)}

def progressive_integration(manager, start_chr=22, max_chromosomes=5):
    """段階的統合（小さい染色体から）"""
    logger = logging.getLogger(__name__)
    
    logger.info(f"🚀 段階的LDSC統合開始 (Chr{start_chr}から{max_chromosomes}染色体)")
    
    dataset_name = "baselineLF_v2.2_UKB"
    success_count = 0
    total_snps = 0
    
    # 小さい染色体から開始 (Chr22, 21, 20, ...)
    chromosomes = list(range(start_chr, max(start_chr - max_chromosomes, 0), -1))
    
    for chr_num in chromosomes:
        logger.info(f"📊 処理中: Chr{chr_num} ({success_count + 1}/{len(chromosomes)})")
        
        result = import_single_chromosome_safe(manager, chr_num, dataset_name)
        
        if result['success']:
            success_count += 1
            total_snps += result.get('ld_scores', 0)
            logger.info(f"✅ Chr{chr_num} 完了 - 累計: {total_snps:,} SNPs")
        else:
            logger.error(f"❌ Chr{chr_num} 失敗")
            if 'error' in result:
                logger.error(f"   エラー詳細: {result['error']}")
        
        # 2染色体ごとに最適化
        if (success_count % 2) == 0 and success_count > 0:
            logger.info("🔧 中間最適化...")
            try:
                # DuckDBでは単純にVACUUMまたは何もしない
                pass  # 中間最適化をスキップ
            except Exception as e:
                logger.warning(f"⚠️ 中間最適化スキップ: {e}")
    
    # メタデータ登録
    if success_count > 0:
        try:
            # 既存メタデータクリア
            manager.conn.execute("DELETE FROM ldsc_metadata WHERE dataset_name = ?", [dataset_name])
            
            # 新しいメタデータ
            manager.conn.execute("""
                INSERT INTO ldsc_metadata 
                (dataset_name, version, population, n_chromosomes, n_annotations, total_snps, data_path)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, [dataset_name, "v2.2", "UKB", success_count, 0, total_snps, str(manager.baseline_path)])
            
            logger.info(f"📋 メタデータ登録完了")
            
        except Exception as e:
            logger.warning(f"⚠️ メタデータ登録エラー: {e}")
    
    logger.info(f"🎉 段階的統合完了: {success_count}/{len(chromosomes)} 染色体成功")
    logger.info(f"📊 総SNP数: {total_snps:,}")
    
    return success_count > 0

def verify_integration(manager):
    """統合結果検証"""
    logger = logging.getLogger(__name__)
    
    logger.info("🔍 統合結果検証...")
    
    try:
        # LDSCデータセット情報
        ldsc_info = manager.get_ldsc_dataset_info()
        
        if ldsc_info['status'] == 'ready':
            logger.info("✅ LDSC統合検証成功")
            logger.info(f"   データセット: {ldsc_info['dataset_name']}")
            logger.info(f"   総SNP数: {ldsc_info['total_snps']:,}")
            logger.info(f"   LD Scores: {ldsc_info['scores_count']:,}")
            logger.info(f"   Annotations: {ldsc_info['annotations_count']:,}")
            logger.info(f"   統合完了: {ldsc_info['integration_complete']}")
            return True
        else:
            logger.warning(f"⚠️ LDSC統合不完全: {ldsc_info['status']}")
            return False
            
    except Exception as e:
        logger.error(f"❌ 検証エラー: {e}")
        return False

def main():
    """メイン実行"""
    logger = setup_logging()
    
    logger.info("🧬 LDSC統合クリーンアップ＆再実行開始")
    logger.info(f"📅 実行時刻: {datetime.now()}")
    
    try:
        # DuckDBマネージャー初期化
        dataset_path = project_root / "dataset"
        manager = GWASDuckDBManager(dataset_path)
        
        # 現在の状況確認
        db_info = manager.get_database_info()
        logger.info(f"💾 開始時DBサイズ: {db_info['database_size_mb']:.2f}MB")
        
        # 1. データベースクリーンアップ
        if not clean_database(manager):
            logger.error("❌ クリーンアップ失敗")
            return False
        
        # 2. 段階的統合実行
        if not progressive_integration(manager, start_chr=22, max_chromosomes=3):
            logger.error("❌ 段階的統合失敗") 
            return False
        
        # 3. 結果検証
        if verify_integration(manager):
            logger.info("🎉 LDSC統合成功!")
            
            # 最終データベース情報
            final_db_info = manager.get_database_info()
            logger.info(f"💾 最終DBサイズ: {final_db_info['database_size_mb']:.2f}MB")
            
            return True
        else:
            logger.error("❌ 統合検証失敗")
            return False
            
    except Exception as e:
        logger.error(f"❌ 予期しないエラー: {e}")
        return False

if __name__ == "__main__":
    print("🧬 LDSC統合クリーンアップ＆段階実行")
    print("=" * 50)
    
    success = main()
    
    if success:
        print("\n✅ LDSC統合が正常に完了しました!")
        print("\n次のステップ:")
        print("1. python test_ldsc_integration.py (検証)")
        print("2. python -c \"from data_loader import GWASDataLoader; loader = GWASDataLoader(); print(loader.get_ldsc_summary())\"")
    else:
        print("\n❌ LDSC統合に失敗しました")
        print("詳細ログ: clean_ldsc_integration.log")
    
    print("=" * 50) 