#!/usr/bin/env python3
"""
修正された集団比較分析：科学的に適切な結果表現

問題点：
- 100%特異性はデータの制限によるアーティファクト
- より慎重で科学的に正確な表現が必要
- データの制限を明示した分析が必要
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from pathlib import Path

def corrected_population_analysis():
    """修正された集団比較分析"""
    
    # データ読み込み
    data_path = Path("manuscript/data/data.tsv")
    df = pd.read_csv(data_path, sep='\t')
    
    print("="*70)
    print("修正された集団比較分析：科学的に適切な結果表現")
    print("="*70)
    
    # 1. データの制限を明示した基本統計
    print("\n1. データカバレッジと制限")
    total_variants = len(df)
    has_euro_data = (df['Z'].notna() & df['P_euro'].notna()).sum()
    coverage_rate = (has_euro_data / total_variants) * 100
    
    print(f"総変異数: {total_variants:,}")
    print(f"欧州人データカバレッジ: {has_euro_data:,} ({coverage_rate:.1f}%)")
    print(f"データ欠損: {total_variants - has_euro_data:,} ({100-coverage_rate:.1f}%)")
    
    # 2. 有意変異の分析（データ制限を考慮）
    print("\n2. 有意変異の分析（データ制限を考慮）")
    
    # 異なる閾値での分析
    thresholds = [1e-5, 1e-4, 1e-3, 0.01]
    results = []
    
    for threshold in thresholds:
        jp_sig = df[df['P'] < threshold]
        jp_sig_with_euro = jp_sig[jp_sig['P_euro'].notna()]
        
        if len(jp_sig) > 0:
            coverage_in_sig = (len(jp_sig_with_euro) / len(jp_sig)) * 100
        else:
            coverage_in_sig = 0
        
        print(f"P < {threshold:g}:")
        print(f"  日本人有意変異: {len(jp_sig):,}")
        print(f"  欧州人データあり: {len(jp_sig_with_euro):,} ({coverage_in_sig:.1f}%)")
        
        # 欧州人でも有意な変異（緩い閾値で）
        if len(jp_sig_with_euro) > 0:
            euro_nominal_sig = (jp_sig_with_euro['P_euro'] < 0.05).sum()
            euro_strict_sig = (jp_sig_with_euro['P_euro'] < 0.01).sum()
            
            print(f"  欧州人でも有意(P<0.05): {euro_nominal_sig:,}")
            print(f"  欧州人でも有意(P<0.01): {euro_strict_sig:,}")
            
            # 限定的な特異性率（データがある範囲で）
            if len(jp_sig_with_euro) > 0:
                limited_specificity = ((len(jp_sig_with_euro) - euro_nominal_sig) / len(jp_sig_with_euro)) * 100
                print(f"  限定的特異性率: {limited_specificity:.1f}% (データがある範囲内)")
        
        print()
    
    # 3. 効果量と効果方向の分析
    print("3. 効果量と効果方向の分析")
    euro_available = df[df['Z'].notna() & df['P_euro'].notna()].copy()
    
    if len(euro_available) > 0:
        # 相関分析
        beta_z_corr = euro_available['BETA'].corr(euro_available['Z'])
        print(f"効果量相関 (BETA vs Z-score): {beta_z_corr:.4f}")
        
        # 効果方向の一致性
        jp_sign = np.sign(euro_available['BETA'])
        eu_sign = np.sign(euro_available['Z'])
        concordant = (jp_sign == eu_sign).sum()
        concordance_rate = (concordant / len(euro_available)) * 100
        
        print(f"効果方向一致率: {concordance_rate:.1f}%")
        
        # 統計的有意性
        from scipy.stats import binomtest
        p_value = binomtest(concordant, len(euro_available), 0.5).pvalue
        print(f"一致率の統計的有意性: P = {p_value:.4f}")
        
        if concordance_rate > 55 and p_value < 0.05:
            print("→ 効果方向に有意な一致性あり（弱いながらも共通の遺伝的基盤を示唆）")
        else:
            print("→ 効果方向はランダムと区別がつかない")
    
    # 4. 適切な科学的結論
    print("\n4. 科学的に適切な結論")
    print("-" * 50)
    
    jp_sig_1e5 = (df['P'] < 1e-5).sum()
    jp_sig_with_data = df[(df['P'] < 1e-5) & df['P_euro'].notna()]
    
    print("【データの制限】")
    print(f"- 欧州人データのカバレッジが{coverage_rate:.1f}%に制限")
    print(f"- 最も有意な変異ほどデータ欠損が多い傾向")
    print("- GSA-プレフィックス変異は特に欧州人データが不完全")
    
    print("\n【限定的な知見】")
    if len(jp_sig_with_data) > 0:
        euro_sig_in_jp_sig = (jp_sig_with_data['P_euro'] < 0.05).sum()
        limited_specificity = ((len(jp_sig_with_data) - euro_sig_in_jp_sig) / len(jp_sig_with_data)) * 100
        
        print(f"- データが利用可能な範囲内で{limited_specificity:.0f}%の変異が集団特異的")
        print(f"- ただし、これは限定的なデータセットに基づく")
        print(f"- より包括的な比較には追加の欧州人データが必要")
    else:
        print("- 日本人で最も有意な変異についてはデータ不足で結論困難")
    
    print("\n【推奨される論文表現】")
    print("「利用可能なデータの範囲内で、日本人で同定された知能関連変異の多くは")
    print("欧州人集団では統計的に有意な関連を示さなかった。しかし、この結果は")
    print("欧州人GWASデータのカバレッジの制限によるものであり、より包括的な")
    print("集団間比較には追加の研究が必要である。」")

def generate_corrected_figures():
    """修正された図の生成"""
    
    data_path = Path("manuscript/data/data.tsv")
    df = pd.read_csv(data_path, sep='\t')
    
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(16, 12))
    fig.suptitle('Corrected Population Comparison Analysis\n(with Data Limitations Acknowledged)', 
                fontsize=14, fontweight='bold')
    
    # 1. データカバレッジの可視化
    labels = ['European data available', 'European data missing']
    sizes = [(df['P_euro'].notna()).sum(), (df['P_euro'].isna()).sum()]
    colors = ['lightblue', 'lightcoral']
    
    ax1.pie(sizes, labels=labels, autopct='%1.1f%%', colors=colors, startangle=90)
    ax1.set_title('A. European GWAS Data Coverage')
    
    # 2. 効果量相関（データがある範囲で）
    euro_data = df[df['Z'].notna() & df['P_euro'].notna()]
    if len(euro_data) > 0:
        correlation = euro_data['BETA'].corr(euro_data['Z'])
        ax2.scatter(euro_data['BETA'], euro_data['Z'], alpha=0.6, s=30)
        ax2.set_xlabel('Japanese Effect Size (β)')
        ax2.set_ylabel('European Z-score')
        ax2.set_title(f'B. Effect Size Correlation\n(r = {correlation:.3f}, n = {len(euro_data)})')
        ax2.grid(True, alpha=0.3)
    
    # 3. P値分布比較
    jp_log_p = -np.log10(df['P'].clip(lower=1e-20))
    ax3.hist(jp_log_p, bins=30, alpha=0.7, label='Japanese', color='red')
    
    if len(euro_data) > 0:
        eu_log_p = -np.log10(euro_data['P_euro'].clip(lower=1e-20))
        ax3.hist(eu_log_p, bins=30, alpha=0.7, label='European (limited)', color='blue')
    
    ax3.set_xlabel('-log₁₀(P-value)')
    ax3.set_ylabel('Frequency')
    ax3.set_title('C. P-value Distributions')
    ax3.legend()
    ax3.grid(True, alpha=0.3)
    
    # 4. 制限を考慮した特異性分析
    thresholds = [1e-5, 1e-4, 1e-3, 0.01, 0.05]
    specificity_rates = []
    sample_sizes = []
    
    for threshold in thresholds:
        jp_sig = df[df['P'] < threshold]
        jp_sig_with_euro = jp_sig[jp_sig['P_euro'].notna()]
        
        if len(jp_sig_with_euro) > 0:
            euro_sig = (jp_sig_with_euro['P_euro'] < 0.05).sum()
            specificity = ((len(jp_sig_with_euro) - euro_sig) / len(jp_sig_with_euro)) * 100
            specificity_rates.append(specificity)
            sample_sizes.append(len(jp_sig_with_euro))
        else:
            specificity_rates.append(np.nan)
            sample_sizes.append(0)
    
    # サンプルサイズに応じた色分け
    colors = ['red' if n < 5 else 'orange' if n < 10 else 'green' for n in sample_sizes]
    
    ax4.bar(range(len(thresholds)), specificity_rates, color=colors, alpha=0.7)
    ax4.set_xlabel('Japanese P-value Threshold')
    ax4.set_ylabel('Apparent Specificity Rate (%)')
    ax4.set_title('D. Limited Specificity Analysis\n(Red: n<5, Orange: n<10, Green: n≥10)')
    ax4.set_xticks(range(len(thresholds)))
    ax4.set_xticklabels([f'{t:g}' for t in thresholds])
    ax4.grid(True, alpha=0.3)
    
    # サンプルサイズを表示
    for i, (rate, n) in enumerate(zip(specificity_rates, sample_sizes)):
        if not np.isnan(rate):
            ax4.text(i, rate + 5, f'n={n}', ha='center', fontsize=8)
    
    plt.tight_layout()
    plt.savefig('temp/corrected_population_analysis.png', dpi=300, bbox_inches='tight')
    print("修正された分析図を temp/corrected_population_analysis.png に保存")

if __name__ == "__main__":
    corrected_population_analysis()
    generate_corrected_figures() 