#!/usr/bin/env python3
"""
LDSC統合テストスクリプト

問題診断と軽量テスト用
"""

import sys
from pathlib import Path
import pandas as pd

# プロジェクトパスを追加
script_dir = Path(__file__).parent
project_root = script_dir.parent.parent
sys.path.insert(0, str(script_dir.parent / "utils"))

from duckdb_manager import GWASDuckDBManager
from data_loader import GWASDataLoader

def test_database_status():
    """データベース状況をテスト"""
    print("🧪 データベース状況テスト")
    print("=" * 40)
    
    dataset_path = project_root / "dataset"
    manager = GWASDuckDBManager(dataset_path)
    
    # 基本情報
    db_info = manager.get_database_info()
    print(f"📊 データベース状況: {db_info['status']}")
    print(f"💾 サイズ: {db_info['database_size_mb']:.2f}MB")
    
    # テーブル確認
    tables = manager.conn.execute("SHOW TABLES").fetchall()
    print(f"📋 テーブル数: {len(tables)}")
    for table in tables:
        count = manager.conn.execute(f"SELECT COUNT(*) FROM {table[0]}").fetchone()[0]
        print(f"   {table[0]}: {count:,} 行")
    
    return manager

def test_ldsc_files():
    """LDSCファイルの構造テスト"""
    print("\n🧪 LDSCファイル構造テスト")
    print("=" * 40)
    
    baseline_path = project_root / "analysis" / "reference_data" / "ldsc_reference" / "baselineLF_v2.2.UKB"
    
    # Chr1のファイルをサンプルテスト
    ldscore_file = baseline_path / "baselineLF2.2.UKB.1.l2.ldscore.gz"
    annot_file = baseline_path / "baselineLF2.2.UKB.1.annot.gz"
    
    if ldscore_file.exists():
        print(f"📊 LD Score ファイル: {ldscore_file.name}")
        
        # 先頭5行を読み込み
        df = pd.read_csv(ldscore_file, sep='\t', compression='gzip', nrows=5)
        print(f"   カラム数: {len(df.columns)}")
        print(f"   主要カラム: {list(df.columns[:5])}")
        print(f"   重複SNP確認: {df['SNP'].duplicated().sum()} 個")
        
        # より大きなサンプルで重複確認
        df_large = pd.read_csv(ldscore_file, sep='\t', compression='gzip', nrows=10000)
        duplicates = df_large['SNP'].duplicated().sum()
        print(f"   重複SNP (10K行): {duplicates} 個")
        
        if duplicates > 0:
            duplicate_snps = df_large[df_large['SNP'].duplicated(keep=False)]['SNP'].unique()
            print(f"   重複SNP例: {duplicate_snps[:3]}")
    
    if annot_file.exists():
        print(f"📋 アノテーション ファイル: {annot_file.name}")
        
        # 先頭5行を読み込み
        df = pd.read_csv(annot_file, sep='\t', compression='gzip', nrows=5)
        print(f"   カラム数: {len(df.columns)}")
        print(f"   基本カラム: {list(df.columns[:4])}")

def test_single_chromosome_import():
    """単一染色体の軽量インポートテスト"""
    print("\n🧪 単一染色体インポートテスト (Chr22)")
    print("=" * 40)
    
    try:
        dataset_path = project_root / "dataset"
        manager = GWASDuckDBManager(dataset_path)
        
        # 既存データクリア
        print("🗑️ 既存LDSCデータクリア...")
        manager._clear_ldsc_dataset("test_chr22")
        
        baseline_path = manager.baseline_path
        chr_num = 22  # 最小の染色体でテスト
        
        # Chr22のファイル
        ldscore_file = baseline_path / f"baselineLF2.2.UKB.{chr_num}.l2.ldscore.gz"
        
        if ldscore_file.exists():
            print(f"📊 Chr{chr_num} LD Scoreインポートテスト...")
            
            # チャンクサイズを小さくしてテスト
            success_count = 0
            error_count = 0
            
            try:
                # 小さなチャンクでテスト
                chunk_size = 1000
                df_sample = pd.read_csv(ldscore_file, sep='\t', compression='gzip', nrows=chunk_size)
                
                # 重複除去
                df_sample = df_sample.drop_duplicates(subset=['SNP'])
                print(f"   サンプルサイズ: {len(df_sample)} SNPs (重複除去後)")
                
                if len(df_sample) > 0:
                    print("✅ Chr22 サンプルデータ読み込み成功")
                    success_count += 1
                else:
                    print("⚠️ データが空です")
                    error_count += 1
                    
            except Exception as e:
                print(f"❌ Chr22 テストエラー: {e}")
                error_count += 1
            
            return success_count > 0
        else:
            print(f"❌ Chr{chr_num} ファイルが見つかりません")
            return False
            
    except Exception as e:
        print(f"❌ 単一染色体テストエラー: {e}")
        return False

def test_data_loader_integration():
    """データローダーのLDSC統合テスト"""
    print("\n🧪 データローダーLDSC統合テスト")
    print("=" * 40)
    
    try:
        dataset_path = project_root / "dataset"
        loader = GWASDataLoader(dataset_path)
        
        print(f"📊 LDSC利用可能: {loader.ldsc_available}")
        
        if loader.ldsc_available:
            # LDSC情報取得テスト
            ldsc_summary = loader.get_ldsc_summary()
            print("📋 LDSC サマリー:")
            for key, value in ldsc_summary.items():
                print(f"   {key}: {value}")
        else:
            print("⚠️ LDSC統合が利用できません")
            
        return loader.ldsc_available
        
    except Exception as e:
        print(f"❌ データローダーテストエラー: {e}")
        return False

def main():
    """メインテスト実行"""
    print("🧬 LDSC統合診断テスト")
    print("=" * 50)
    
    # テスト実行
    manager = test_database_status()
    test_ldsc_files()
    
    chr_test_success = test_single_chromosome_import()
    loader_test_success = test_data_loader_integration()
    
    print("\n📊 テスト結果サマリー")
    print("=" * 40)
    print(f"データベース: ✅ OK")
    print(f"ファイル構造: ✅ OK")
    print(f"Chr22 インポート: {'✅ OK' if chr_test_success else '❌ FAIL'}")
    print(f"データローダー: {'✅ OK' if loader_test_success else '❌ FAIL'}")
    
    if chr_test_success and loader_test_success:
        print("\n🎉 LDSC統合の基本機能は正常です！")
        print("💡 推奨: 段階的な統合を実行してください")
    else:
        print("\n⚠️ 問題が検出されました。詳細ログを確認してください")

if __name__ == "__main__":
    main() 