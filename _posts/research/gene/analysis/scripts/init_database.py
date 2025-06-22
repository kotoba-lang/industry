#!/usr/bin/env python3
"""
DuckDBデータベース初期化スクリプト

基本的なデータベース構造とテーブルを作成
LDSC統合用のセットアップ
"""

import sys
from pathlib import Path
import logging

# プロジェクトパスを追加
script_dir = Path(__file__).parent
project_root = script_dir.parent.parent
sys.path.insert(0, str(script_dir.parent / "utils"))

from duckdb_manager import GWASDuckDBManager

def init_database():
    """データベース初期化"""
    print("🗄️ DuckDBデータベース初期化開始...")
    
    # データセットパス設定
    dataset_path = project_root / "dataset"
    dataset_path.mkdir(exist_ok=True)
    
    try:
        # DuckDBマネージャー初期化（自動でDBファイル作成）
        print(f"📁 Dataset path: {dataset_path}")
        
        # duckdbをインストール確認
        try:
            import duckdb
            print("✅ DuckDB利用可能")
        except ImportError:
            print("❌ DuckDBがインストールされていません")
            print("インストール: pip install duckdb")
            return False
        
        manager = GWASDuckDBManager(dataset_path)
        
        if manager.conn is None:
            print("❌ DuckDB接続に失敗しました")
            return False
        
        # データベース情報確認
        db_info = manager.get_database_info()
        print("📊 データベース初期化完了:")
        print(f"   DBファイル: {db_info.get('database_file')}")
        print(f"   DBサイズ: {db_info.get('database_size_mb', 0):.2f}MB")
        print(f"   ステータス: {db_info.get('status')}")
        
        # テーブル確認
        tables = manager.conn.execute("SHOW TABLES").fetchall()
        print(f"📋 作成されたテーブル: {len(tables)}個")
        for table in tables:
            print(f"   - {table[0]}")
        
        return True
        
    except Exception as e:
        print(f"❌ データベース初期化エラー: {e}")
        return False

if __name__ == "__main__":
    success = init_database()
    
    if success:
        print("\n✅ データベース初期化完了!")
        print("次のステップ: python integrate_ldsc_data.py")
    else:
        print("\n❌ データベース初期化に失敗しました") 