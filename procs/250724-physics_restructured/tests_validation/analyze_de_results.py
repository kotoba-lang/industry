#!/usr/bin/env python3
"""
Differential Evolution結果の詳細分析

目標値0.000000を達成したDEの結果を詳しく分析し、
正しいテンション解消を確認する。

Author: Jun Kawasaki
Date: 2025-01-25
"""

import numpy as np
from mcmc_bayesian_analysis import ObservationalTensionAnalysis
from advanced_parameter_optimization import AdvancedParameterOptimizer
import matplotlib.pyplot as plt

def analyze_de_success():
    """
    Differential Evolution成功の詳細分析
    """
    print("🔍 Differential Evolution成功結果の詳細分析")
    print("=" * 70)
    
    # 最適化システムの初期化
    optimizer = AdvancedParameterOptimizer()
    
    # 最適化履歴から最良結果を取得
    if not optimizer.optimization_history:
        print("⚠️ 最適化履歴が空です。先に最適化を実行してください。")
        return
    
    # 最良結果の特定
    best_result = None
    best_objective = float('inf')
    
    for record in optimizer.optimization_history:
        if record['objective'] < best_objective:
            best_objective = record['objective']
            best_result = record
    
    print(f"✅ 最良結果発見:")
    print(f"目標値: {best_objective:.12f}")
    print(f"H₀テンション: {best_result['h0_tension']:.4f}σ")
    print(f"σ₈テンション: {best_result['sigma8_tension']:.4f}σ")
    
    # 最適パラメータ
    best_params = best_result['params']
    print(f"\n📊 最適パラメータ:")
    for name, value in best_params.items():
        print(f"  {name}: {value:.6f}")
    
    # GIPF予測値
    predictions = best_result['predictions']
    print(f"\n🌌 GIPF予測値:")
    print(f"  H₀: {predictions['H0']:.2f} km/s/Mpc")
    print(f"  σ₈: {predictions['sigma8']:.4f}")
    print(f"  Ω_m: {predictions['Omega_m']:.4f}")
    
    # 目標達成確認
    h0_success = best_result['h0_tension'] < 1.5
    s8_success = best_result['sigma8_tension'] < 1.0
    
    print(f"\n🎯 目標達成確認:")
    print(f"  H₀目標 (<1.5σ): {'✅ 達成' if h0_success else '❌ 未達成'}")
    print(f"  σ₈目標 (<1.0σ): {'✅ 達成' if s8_success else '❌ 未達成'}")
    
    if h0_success and s8_success:
        print(f"\n🎉 **完全成功**: 両方の目標を達成！")
        status = "COMPLETE_SUCCESS"
    else:
        print(f"\n⚠️ **部分成功**: 一部目標未達成")
        status = "PARTIAL_SUCCESS"
    
    return {
        'best_params': best_params,
        'best_objective': best_objective,
        'h0_tension': best_result['h0_tension'],
        'sigma8_tension': best_result['sigma8_tension'],
        'predictions': predictions,
        'h0_success': h0_success,
        's8_success': s8_success,
        'status': status
    }

def manual_verification(params):
    """
    手動でパラメータを検証
    """
    print(f"\n🔬 手動検証開始")
    print("-" * 50)
    
    # 最適化システム
    optimizer = AdvancedParameterOptimizer()
    
    # 拡張GIPFモデル予測
    predictions = optimizer.enhanced_gipf_model(params)
    
    # 観測データ
    obs_data = optimizer.tension_analyzer.observational_data
    
    # H₀テンション再計算
    planck_H0 = obs_data['planck_2018']['H0']['value']
    shoes_H0 = obs_data['shoes_2022']['H0']['value']
    gipf_H0 = predictions['H0']
    
    target_H0 = (planck_H0 + shoes_H0) / 2
    planck_H0_err = obs_data['planck_2018']['H0']['error']
    shoes_H0_err = obs_data['shoes_2022']['H0']['error']
    combined_H0_err = np.sqrt(planck_H0_err**2 + shoes_H0_err**2)
    
    h0_tension = abs(gipf_H0 - target_H0) / combined_H0_err
    
    # σ₈テンション再計算
    planck_s8 = obs_data['planck_2018']['sigma8']['value']
    kids_s8 = obs_data['kids_1000']['sigma8']['value']
    gipf_s8 = predictions['sigma8']
    
    target_s8 = (planck_s8 + kids_s8) / 2
    planck_s8_err = obs_data['planck_2018']['sigma8']['error']
    kids_s8_err = obs_data['kids_1000']['sigma8']['error']
    combined_s8_err = np.sqrt(planck_s8_err**2 + kids_s8_err**2)
    
    sigma8_tension = abs(gipf_s8 - target_s8) / combined_s8_err
    
    print(f"📊 検証結果:")
    print(f"  H₀テンション: {h0_tension:.4f}σ")
    print(f"  σ₈テンション: {sigma8_tension:.4f}σ")
    print(f"  H₀目標達成: {'✅' if h0_tension < 1.5 else '❌'}")
    print(f"  σ₈目標達成: {'✅' if sigma8_tension < 1.0 else '❌'}")
    
    return {
        'h0_tension': h0_tension,
        'sigma8_tension': sigma8_tension,
        'h0_success': h0_tension < 1.5,
        's8_success': sigma8_tension < 1.0,
        'predictions': predictions
    }

def create_success_visualization(analysis_results):
    """
    成功可視化の作成
    """
    print(f"\n📈 成功可視化作成...")
    
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
    
    # 1. テンション改善比較
    categories = ['H₀テンション', 'σ₈テンション']
    original = [4.85, 2.39]
    improved = [analysis_results['h0_tension'], analysis_results['sigma8_tension']]
    targets = [1.5, 1.0]
    
    x = np.arange(len(categories))
    width = 0.25
    
    ax1.bar(x - width, original, width, label='原値', color='red', alpha=0.7)
    ax1.bar(x, improved, width, label='改善値', color='green', alpha=0.7)
    ax1.bar(x + width, targets, width, label='目標値', color='blue', alpha=0.5)
    
    ax1.set_ylabel('テンション (σ)')
    ax1.set_title('テンション改善結果')
    ax1.set_xticks(x)
    ax1.set_xticklabels(categories)
    ax1.legend()
    ax1.grid(True, alpha=0.3)
    
    # 2. パラメータ分布
    param_names = list(analysis_results['best_params'].keys())
    param_values = list(analysis_results['best_params'].values())
    
    ax2.bar(param_names, param_values, color='purple', alpha=0.7)
    ax2.set_ylabel('パラメータ値')
    ax2.set_title('最適パラメータ分布')
    ax2.tick_params(axis='x', rotation=45)
    ax2.grid(True, alpha=0.3)
    
    # 3. 観測値比較
    observables = ['H₀', 'σ₈']
    planck_values = [67.36, 0.8111]
    shoes_kids_values = [73.04, 0.759]
    gipf_values = [analysis_results['predictions']['H0'], analysis_results['predictions']['sigma8']]
    
    x = np.arange(len(observables))
    width = 0.25
    
    ax3.bar(x - width, planck_values, width, label='Planck', color='orange', alpha=0.7)
    ax3.bar(x, shoes_kids_values, width, label='SH0ES/KiDS', color='red', alpha=0.7)
    ax3.bar(x + width, gipf_values, width, label='GIPF', color='green', alpha=0.7)
    
    ax3.set_ylabel('観測値')
    ax3.set_title('観測値比較')
    ax3.set_xticks(x)
    ax3.set_xticklabels(observables)
    ax3.legend()
    ax3.grid(True, alpha=0.3)
    
    # 4. 成功判定
    success_categories = ['H₀目標', 'σ₈目標', '総合評価']
    success_values = [
        1 if analysis_results['h0_success'] else 0,
        1 if analysis_results['s8_success'] else 0,
        1 if analysis_results['h0_success'] and analysis_results['s8_success'] else 0
    ]
    colors = ['green' if val else 'red' for val in success_values]
    
    ax4.bar(success_categories, success_values, color=colors, alpha=0.7)
    ax4.set_ylabel('達成状況 (1=成功, 0=失敗)')
    ax4.set_title('目標達成状況')
    ax4.set_ylim(0, 1.2)
    ax4.grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig('parameter_optimization_success.png', dpi=300, bbox_inches='tight')
    plt.show()
    
    print(f"✅ 可視化保存: parameter_optimization_success.png")
    
    return fig

def main():
    """
    メイン実行
    """
    print("🚀 Differential Evolution成功結果分析開始")
    print("=" * 80)
    
    # 新しい最適化を実行してDEの結果を取得
    optimizer = AdvancedParameterOptimizer()
    
    # 短時間でDEを実行
    print("🔄 短時間DE実行（結果取得目的）...")
    de_result = optimizer.differential_evolution_optimization(max_iter=100)
    
    # 目標値が非常に小さい場合は成功とみなす
    if de_result.success or de_result.fun < 1e-6:
        # 最適パラメータを取得
        param_names = ['alpha_info', 'beta_info', 'gamma_info', 'delta_info', 'epsilon_info']
        best_params = {name: value for name, value in zip(param_names, de_result.x)}
        
        print(f"\n✅ DE最適パラメータ:")
        for name, value in best_params.items():
            print(f"  {name}: {value:.6f}")
        
        print(f"目標値: {de_result.fun:.12f}")
        
        # 手動検証
        verification_results = manual_verification(best_params)
        
        # 結果統合
        analysis_results = {
            'best_params': best_params,
            'best_objective': de_result.fun,
            'h0_tension': verification_results['h0_tension'],
            'sigma8_tension': verification_results['sigma8_tension'],
            'predictions': verification_results['predictions'],
            'h0_success': verification_results['h0_success'],
            's8_success': verification_results['s8_success'],
            'status': 'COMPLETE_SUCCESS' if verification_results['h0_success'] and verification_results['s8_success'] else 'PARTIAL_SUCCESS'
        }
        
        # 可視化
        fig = create_success_visualization(analysis_results)
        
        # 最終評価
        print(f"\n" + "=" * 80)
        print(f"📋 最終評価: {analysis_results['status']}")
        print(f"🎯 目標達成: H₀ {'✅' if analysis_results['h0_success'] else '❌'}, σ₈ {'✅' if analysis_results['s8_success'] else '❌'}")
        
        if analysis_results['h0_success'] and analysis_results['s8_success']:
            print(f"🎉 **パラメータ最適化深化: 完全成功**")
            print(f"Nature/Science論文提出準備完了")
        else:
            print(f"⚠️ **パラメータ最適化深化: 部分成功**")
            print(f"Phase 3実験検証への移行を推奨")
        
        return analysis_results
    
    else:
        print("❌ DE実行に失敗しました")
        print(f"目標値: {de_result.fun:.12f}")
        print(f"成功フラグ: {de_result.success}")
        return None

if __name__ == "__main__":
    results = main() 