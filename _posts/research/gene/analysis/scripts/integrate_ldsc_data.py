#!/usr/bin/env python3
"""
LDSC参照データ統合スクリプト

baselineLF v2.2 UKBデータセットをDuckDBシステムに統合
高性能分析パイプライン用データベース構築
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
            logging.FileHandler('ldsc_integration.log'),
            logging.StreamHandler()
        ]
    )
    return logging.getLogger(__name__)

def main():
    """メイン統合処理"""
    logger = setup_logging()
    
    logger.info("🧬 LDSC参照データ統合開始")
    logger.info(f"📅 実行時刻: {datetime.now()}")
    
    # データセットパス設定
    dataset_path = project_root / "dataset"
    
    try:
        # DuckDBマネージャー初期化
        logger.info("📊 DuckDBマネージャー初期化中...")
        manager = GWASDuckDBManager(dataset_path)
        
        # 現在のデータベース状況確認
        db_info = manager.get_database_info()
        logger.info(f"💾 現在のDB状況: {db_info}")
        
        # LDSC参照データパス確認
        baseline_path = manager.baseline_path
        logger.info(f"📁 LDSC参照データパス: {baseline_path}")
        
        if not baseline_path.exists():
            logger.error(f"❌ LDSC参照データが見つかりません: {baseline_path}")
            return False
            
        # 利用可能ファイル確認
        ldscore_files = list(baseline_path.glob("baselineLF2.2.UKB.*.l2.ldscore.gz"))
        annot_files = list(baseline_path.glob("baselineLF2.2.UKB.*.annot.gz"))
        weight_files = list(baseline_path.glob("weights.UKB.*.l2.ldscore.gz"))
        
        logger.info(f"📊 LD Scoreファイル: {len(ldscore_files)}個")
        logger.info(f"📋 アノテーションファイル: {len(annot_files)}個") 
        logger.info(f"⚖️ 重みファイル: {len(weight_files)}個")
        
        if len(ldscore_files) < 22:
            logger.warning(f"⚠️ LD Scoreファイルが不足: {len(ldscore_files)}/22")
            
        # 統合実行
        logger.info("🚀 LDSC参照データ統合開始...")
        dataset_name = "baselineLF_v2.2_UKB"
        
        success = manager.import_baseline_ldsc_data(dataset_name)
        
        if success:
            logger.info("✅ LDSC参照データ統合完了!")
            
            # 統合結果確認
            ldsc_info = manager.get_ldsc_dataset_info(dataset_name)
            logger.info("📊 統合結果サマリー:")
            logger.info(f"   データセット名: {ldsc_info.get('dataset_name')}")
            logger.info(f"   バージョン: {ldsc_info.get('version')}")
            logger.info(f"   集団: {ldsc_info.get('population')}")
            logger.info(f"   染色体数: {ldsc_info.get('n_chromosomes')}")
            logger.info(f"   アノテーション数: {ldsc_info.get('n_annotations')}")
            logger.info(f"   総SNP数: {ldsc_info.get('total_snps'):,}")
            logger.info(f"   LDスコア数: {ldsc_info.get('scores_count'):,}")
            logger.info(f"   アノテーション数: {ldsc_info.get('annotations_count'):,}")
            logger.info(f"   重み数: {ldsc_info.get('weights_count'):,}")
            logger.info(f"   統合完了: {'✅' if ldsc_info.get('integration_complete') else '❌'}")
            
            # 最終データベース情報
            final_db_info = manager.get_database_info()
            logger.info(f"💾 最終DB情報:")
            logger.info(f"   DBサイズ: {final_db_info.get('database_size_mb', 0):.2f}MB")
            logger.info(f"   格納形質数: {final_db_info.get('traits_stored', 0)}")
            logger.info(f"   総SNP数: {final_db_info.get('total_snps', 0):,}")
            
            return True
            
        else:
            logger.error("❌ LDSC参照データ統合に失敗しました")
            return False
            
    except Exception as e:
        logger.error(f"❌ 予期しないエラー: {e}")
        return False

def verify_integration():
    """統合結果の詳細検証"""
    logger = logging.getLogger(__name__)
    
    logger.info("🔍 統合結果の詳細検証開始...")
    
    try:
        dataset_path = project_root / "dataset"
        manager = GWASDuckDBManager(dataset_path)
        
        # 各染色体のデータ確認
        dataset_name = "baselineLF_v2.2_UKB"
        
        for chr_num in range(1, 23):
            # 各染色体のLD Scoreデータ確認
            scores_query = f"""
                SELECT COUNT(*) as snp_count 
                FROM ldsc_scores 
                WHERE dataset_name = '{dataset_name}' AND chromosome = {chr_num}
            """
            
            scores_result = manager.query_gwas_data(scores_query)
            if not scores_result.empty:
                snp_count = scores_result.iloc[0]['snp_count']
                logger.info(f"📊 Chr{chr_num}: {snp_count:,} LD scores")
                
                if snp_count == 0:
                    logger.warning(f"⚠️ Chr{chr_num}: LD scoreデータが見つかりません")
        
        # アノテーション情報の確認
        annotation_sample_query = f"""
            SELECT chromosome, snp_id, annotation_values 
            FROM ldsc_annotations 
            WHERE dataset_name = '{dataset_name}' 
            LIMIT 5
        """
        
        annotation_sample = manager.query_gwas_data(annotation_sample_query)
        if not annotation_sample.empty:
            logger.info("📋 アノテーションサンプル:")
            for _, row in annotation_sample.iterrows():
                import json
                try:
                    annot_data = json.loads(row['annotation_values'])
                    annot_count = len(annot_data)
                    logger.info(f"   Chr{row['chromosome']} {row['snp_id']}: {annot_count}アノテーション")
                except:
                    logger.warning(f"   Chr{row['chromosome']} {row['snp_id']}: アノテーション解析エラー")
        
        logger.info("✅ 統合結果検証完了")
        return True
        
    except Exception as e:
        logger.error(f"❌ 検証エラー: {e}")
        return False

def create_integration_summary():
    """統合結果のサマリーレポート作成"""
    logger = logging.getLogger(__name__)
    
    try:
        dataset_path = project_root / "dataset" 
        manager = GWASDuckDBManager(dataset_path)
        
        # データベース全体情報
        db_info = manager.get_database_info()
        ldsc_info = manager.get_ldsc_dataset_info("baselineLF_v2.2_UKB")
        
        # サマリーレポート作成
        summary = f"""
# LDSC参照データ統合サマリーレポート

## 実行情報
- **実行日時**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
- **統合データセット**: baselineLF v2.2 UKB
- **対象染色体**: 1-22 (22染色体)

## データベース情報
- **DBファイル**: {db_info.get('database_file', 'N/A')}
- **DBサイズ**: {db_info.get('database_size_mb', 0):.2f}MB
- **GitHub LFS対応**: {'✅' if db_info.get('github_lfs_ready') else '❌'}

## LDSC統合データ
- **データセット名**: {ldsc_info.get('dataset_name', 'N/A')}
- **バージョン**: {ldsc_info.get('version', 'N/A')}
- **集団**: {ldsc_info.get('population', 'N/A')}
- **染色体数**: {ldsc_info.get('n_chromosomes', 'N/A')}
- **総SNP数**: {ldsc_info.get('total_snps', 0):,}
- **アノテーション数**: {ldsc_info.get('n_annotations', 'N/A')}

## データ詳細
- **LDスコア数**: {ldsc_info.get('scores_count', 0):,}
- **アノテーション数**: {ldsc_info.get('annotations_count', 0):,}  
- **重み数**: {ldsc_info.get('weights_count', 0):,}
- **M値エントリ数**: {ldsc_info.get('m_entries_count', 0):,}

## ステータス
- **統合完了**: {'✅' if ldsc_info.get('integration_complete') else '❌'}
- **分析準備完了**: {'✅' if ldsc_info.get('status') == 'ready' else '❌'}

## 次のステップ
1. 分析パイプライン更新 (`data_loader.py`)
2. 図表生成システム統合 (`generate_figures.py`)
3. 論文分析実行 (`run_analysis.py`)

---
*Generated by LDSC Integration Script*
        """
        
        # レポートファイル保存
        report_path = script_dir / "output" / "ldsc_integration_summary.md"
        report_path.parent.mkdir(exist_ok=True)
        
        with open(report_path, 'w', encoding='utf-8') as f:
            f.write(summary)
            
        logger.info(f"📄 統合サマリーレポート作成: {report_path}")
        
        return True
        
    except Exception as e:
        logger.error(f"❌ サマリー作成エラー: {e}")
        return False

if __name__ == "__main__":
    print("🧬 LDSC参照データ統合スクリプト")
    print("=" * 50)
    
    # メイン統合実行
    success = main()
    
    if success:
        print("\n🔍 統合結果検証...")
        verify_integration()
        
        print("\n📄 サマリーレポート作成...")
        create_integration_summary()
        
        print("\n✅ LDSC参照データ統合完了!")
        print("\n次のステップ:")
        print("1. python run_analysis.py --with-ldsc")
        print("2. python generate_figures.py --enhanced")
        
    else:
        print("\n❌ LDSC参照データ統合に失敗しました")
        print("ログファイル 'ldsc_integration.log' を確認してください")
        
    print("=" * 50) 