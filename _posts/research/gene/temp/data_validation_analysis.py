#!/usr/bin/env python3
"""
データ検証分析：日本人特異的変異100%の結果検証

疑問点：
1. 100%の集団特異性は現実的ではない
2. データ品質や分析手法に問題がある可能性
3. より詳細な検証が必要
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path

def validate_population_specificity():
    """集団特異性分析の詳細検証"""
    
    # データ読み込み
    data_path = Path("manuscript/data/data.tsv")
    df = pd.read_csv(data_path, sep='\t')
    
    print("="*70)
    print("データ検証分析：日本人特異的変異100%の結果検証")
    print("="*70)
    
    # 1. 基本的なデータ品質チェック
    print("\n1. 基本的なデータ品質")
    print(f"総変異数: {len(df):,}")
    print(f"欧州人Z-scoreデータがある変異数: {df['Z'].notna().sum():,}")
    print(f"欧州人P値データがある変異数: {df['P_euro'].notna().sum():,}")
    print(f"両方のデータがある変異数: {(df['Z'].notna() & df['P_euro'].notna()).sum():,}")
    
    # 2. 日本人GWASでの有意性分布
    print("\n2. 日本人GWASでの有意性分布")
    significance_levels = [5e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 0.01, 0.05]
    
    for threshold in significance_levels:
        count = (df['P'] < threshold).sum()
        print(f"P < {threshold:g}: {count:,} 変異")
    
    # 3. 欧州人データの品質チェック
    print("\n3. 欧州人データの品質")
    euro_data = df[df['Z'].notna() & df['P_euro'].notna()].copy()
    print(f"比較可能な変異数: {len(euro_data):,}")
    
    if len(euro_data) > 0:
        print(f"欧州人Z-scoreの範囲: {euro_data['Z'].min():.3f} ～ {euro_data['Z'].max():.3f}")
        print(f"欧州人P値の範囲: {euro_data['P_euro'].min():.2e} ～ {euro_data['P_euro'].max():.2e}")
        
        # Z-scoreの分布
        fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5))
        
        ax1.hist(euro_data['Z'], bins=50, alpha=0.7)
        ax1.set_xlabel('European Z-score')
        ax1.set_ylabel('Frequency')
        ax1.set_title('Distribution of European Z-scores')
        
        ax2.hist(-np.log10(euro_data['P_euro']), bins=50, alpha=0.7)
        ax2.set_xlabel('-log10(European P-value)')
        ax2.set_ylabel('Frequency')
        ax2.set_title('Distribution of European P-values')
        
        plt.tight_layout()
        plt.savefig('temp/european_data_distribution.png', dpi=300)
        print("欧州人データの分布を temp/european_data_distribution.png に保存")
    
    # 4. 詳細な集団特異性分析
    print("\n4. 詳細な集団特異性分析")
    
    # 異なる閾値で分析
    analysis_results = []
    
    for jp_threshold in [1e-5, 1e-4, 1e-3]:
        for eu_threshold in [0.05, 0.01, 1e-3]:
            # 日本人で有意な変異
            jp_sig = df['P'] < jp_threshold
            jp_sig_variants = df[jp_sig]
            
            # この中で欧州人データがある変異
            comparable = jp_sig_variants[jp_sig_variants['P_euro'].notna()]
            
            if len(comparable) > 0:
                # 欧州人でも有意な変異
                eu_also_sig = comparable['P_euro'] < eu_threshold
                shared_count = eu_also_sig.sum()
                specificity_rate = ((len(comparable) - shared_count) / len(comparable)) * 100
                
                result = {
                    'JP_threshold': jp_threshold,
                    'EU_threshold': eu_threshold,
                    'JP_significant': len(jp_sig_variants),
                    'Comparable': len(comparable),
                    'EU_also_significant': shared_count,
                    'Specificity_rate': specificity_rate
                }
                analysis_results.append(result)
                
                print(f"日本人P<{jp_threshold:g}, 欧州人P<{eu_threshold:g}:")
                print(f"  日本人有意変異: {len(jp_sig_variants):,}")
                print(f"  比較可能変異: {len(comparable):,}")
                print(f"  欧州人でも有意: {shared_count:,}")
                print(f"  日本人特異性: {specificity_rate:.1f}%")
                print()
    
    # 5. 効果方向の一致性チェック
    print("5. 効果方向の一致性チェック")
    if len(euro_data) > 0:
        # 日本人のBETAと欧州人のZ-scoreの相関
        correlation = euro_data['BETA'].corr(euro_data['Z'])
        print(f"効果量相関 (BETA vs Z): {correlation:.4f}")
        
        # 効果方向の一致率
        jp_beta_sign = np.sign(euro_data['BETA'])
        eu_z_sign = np.sign(euro_data['Z'])
        concordant = (jp_beta_sign == eu_z_sign).sum()
        concordance_rate = (concordant / len(euro_data)) * 100
        print(f"効果方向一致率: {concordance_rate:.1f}%")
        
        # 有意な変異での一致率
        jp_sig_in_comparable = euro_data['P'] < 1e-5
        if jp_sig_in_comparable.sum() > 0:
            jp_sig_data = euro_data[jp_sig_in_comparable]
            jp_sig_beta_sign = np.sign(jp_sig_data['BETA'])
            jp_sig_eu_z_sign = np.sign(jp_sig_data['Z'])
            jp_sig_concordant = (jp_sig_beta_sign == jp_sig_eu_z_sign).sum()
            jp_sig_concordance_rate = (jp_sig_concordant / len(jp_sig_data)) * 100
            print(f"日本人有意変異での効果方向一致率: {jp_sig_concordance_rate:.1f}%")
    
    # 6. トップ変異の詳細確認
    print("\n6. トップ変異の詳細確認")
    top_variants = df.nsmallest(10, 'P')
    for i, (_, variant) in enumerate(top_variants.iterrows(), 1):
        print(f"{i:2d}. {variant['SNP']}")
        print(f"    日本人: P={variant['P']:.2e}, BETA={variant['BETA']:.3f}")
        if pd.notna(variant['Z']) and pd.notna(variant['P_euro']):
            print(f"    欧州人: P={variant['P_euro']:.2e}, Z={variant['Z']:.3f}")
        else:
            print(f"    欧州人: データなし")
        print()
    
    # 7. 結論と推奨事項
    print("7. 結論と推奨事項")
    print("-" * 50)
    
    total_comparable = (df['Z'].notna() & df['P_euro'].notna()).sum()
    if total_comparable < 50:
        print("⚠️  警告: 比較可能な変異数が非常に少ない")
        print("   これが100%特異性の主要因である可能性")
    
    jp_sig_1e5 = (df['P'] < 1e-5).sum()
    comparable_in_sig = df[(df['P'] < 1e-5) & df['P_euro'].notna()]
    
    if len(comparable_in_sig) == 0:
        print("⚠️  重大な問題: 日本人で有意な変異に欧州人データが全くない")
        print("   これは明らかにデータマッチングの問題")
    
    print("\n推奨事項:")
    print("1. 欧州人GWASデータとのSNP IDマッチングを再確認")
    print("2. より包括的な欧州人intelligence GWASデータを使用")
    print("3. 統計的閾値を調整して感度分析を実施")
    print("4. 論文では「データの制限により」として慎重に記述")

if __name__ == "__main__":
    validate_population_specificity() 