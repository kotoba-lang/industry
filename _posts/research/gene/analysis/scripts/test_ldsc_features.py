#!/usr/bin/env python3
"""
LDSC統合機能テストスクリプト

実際のLDSC機能動作確認
"""

import sys
from pathlib import Path

# プロジェクトパスを追加
script_dir = Path(__file__).parent
project_root = script_dir.parent.parent
sys.path.insert(0, str(script_dir.parent / "utils"))

from data_loader import GWASDataLoader

def test_ldsc_features():
    """LDSC統合機能の実動作テスト"""
    print("🧬 LDSC統合機能テスト")
    print("=" * 50)
    
    # データローダー初期化
    loader = GWASDataLoader()
    
    # 1. LDSC統合状況確認
    print("\n📊 1. LDSC統合状況")
    print("-" * 30)
    summary = loader.get_ldsc_summary()
    for key, value in summary.items():
        print(f"   {key}: {value}")
    
    if not loader.ldsc_available:
        print("❌ LDSC統合が利用できません")
        return
    
    # 2. サンプルSNPでLD Score検索テスト
    print("\n📊 2. LD Score検索テスト")
    print("-" * 30)
    
    # Chr20-22のサンプルSNPを検索
    test_snps = ['rs6010620', 'rs6014724', 'rs775268684', 'rs2821826', 'rs8122066']
    
    try:
        ld_scores = loader.get_ld_scores(test_snps)
        if len(ld_scores) > 0:
            print(f"✅ LD Scores取得成功: {len(ld_scores)}個")
            print(f"   SNPs: {list(ld_scores['snp_id'])}")
            print(f"   アノテーション数: {len([col for col in ld_scores.columns if col not in ['snp_id', 'chromosome', 'bp']])}")
        else:
            print("⚠️ LD Scoresが見つかりませんでした")
    except Exception as e:
        print(f"❌ LD Score検索エラー: {e}")
    
    # 3. 機能的アノテーション検索テスト
    print("\n📋 3. 機能的アノテーション検索テスト")
    print("-" * 30)
    
    try:
        annotations = loader.get_functional_annotations(test_snps)
        if len(annotations) > 0:
            print(f"✅ アノテーション取得成功: {len(annotations)}個")
            print(f"   SNPs: {list(annotations['snp_id'])}")
            
            # 主要アノテーションカラムを表示
            functional_cols = [col for col in annotations.columns 
                             if any(keyword in col.lower() for keyword in 
                                   ['coding', 'promoter', 'enhancer', 'conserved'])]
            print(f"   機能的アノテーション例: {functional_cols[:5]}")
        else:
            print("⚠️ アノテーションが見つかりませんでした")
    except Exception as e:
        print(f"❌ アノテーション検索エラー: {e}")
    
    # 4. ポリジェニックスコア計算テスト
    print("\n🧮 4. ポリジェニックスコア計算テスト")
    print("-" * 30)
    
    # サンプル効果サイズ
    sample_effects = {
        'rs6010620': 0.1,
        'rs6014724': -0.05,
        'rs775268684': 0.08
    }
    
    try:
        # LD重みなし
        simple_score = loader.calculate_polygenic_score(sample_effects, use_ld_weights=False)
        print(f"✅ 単純合計スコア: {simple_score}")
        
        # LD重みつき（重みデータがないため単純合計にフォールバック予想）
        ld_weighted_score = loader.calculate_polygenic_score(sample_effects, use_ld_weights=True)
        print(f"✅ LD重みつきスコア: {ld_weighted_score}")
        
    except Exception as e:
        print(f"❌ ポリジェニックスコア計算エラー: {e}")
    
    # 5. 統合状況最終確認
    print("\n📋 5. 最終統合状況")
    print("-" * 30)
    
    # データベース統計
    manager = loader.manager
    db_info = manager.get_database_info()
    ldsc_info = manager.get_ldsc_dataset_info()
    
    print(f"💾 データベースサイズ: {db_info['database_size_mb']:.1f}MB")
    print(f"🧬 LDSC SNP数: {ldsc_info['total_snps']:,}")
    print(f"📊 LD Scores: {ldsc_info['scores_count']:,}")
    print(f"📋 Annotations: {ldsc_info['annotations_count']:,}")
    print(f"🏷️ 対象染色体: {ldsc_info.get('n_chromosomes', 0)}")
    print(f"✅ 統合完了: {ldsc_info['integration_complete']}")
    
    print("\n🎉 LDSC統合機能テスト完了!")

if __name__ == "__main__":
    test_ldsc_features() 