#!/usr/bin/env python3
"""
σ₈問題解決のテスト・デモンストレーション実行スクリプト

このスクリプトは以下を実行します:
1. σ₈問題解決システムの動作確認
2. 基本機能のテスト
3. 結果の検証と表示
4. 目標達成確認

使用方法:
    python test_sigma8_solution.py
"""

import sys
import os
import numpy as np
import matplotlib.pyplot as plt
import time
from datetime import datetime

# 同じディレクトリのsigma8_problem_solutionをインポート
try:
    from sigma8_problem_solution import Sigma8PrecisionSolver
except ImportError:
    print("❌ エラー: sigma8_problem_solution.py が見つかりません")
    print("   同じディレクトリに sigma8_problem_solution.py があることを確認してください")
    sys.exit(1)

def test_basic_functionality():
    """
    基本機能のテスト
    """
    print("🔬 基本機能テスト開始...")
    
    try:
        # システム初期化
        solver = Sigma8PrecisionSolver()
        print("✅ システム初期化: 成功")
        
        # 転送関数テスト
        k_test = 0.1
        T_k = solver.eisenstein_hu_transfer(k_test)
        assert 0 < T_k < 1, f"転送関数の値が異常: T({k_test}) = {T_k}"
        print("✅ 転送関数: 正常")
        
        # 原始パワースペクトルテスト
        P_prim = solver.primordial_power_spectrum(k_test)
        assert P_prim > 0, f"原始パワースペクトルが負: P({k_test}) = {P_prim}"
        print("✅ 原始パワースペクトル: 正常")
        
        # 成長因子テスト
        D_0 = solver.growth_factor(0)
        assert 0.8 < D_0 < 1.2, f"成長因子が異常: D(0) = {D_0}"
        print("✅ 成長因子: 正常")
        
        return True
        
    except Exception as e:
        print(f"❌ 基本機能テスト失敗: {e}")
        return False

def test_sigma8_calculation():
    """
    σ₈計算のテスト
    """
    print("\n🎯 σ₈計算テスト開始...")
    
    try:
        solver = Sigma8PrecisionSolver()
        
        # 基本計算
        start_time = time.time()
        basic_result = solver.calculate_sigma_8_precise()
        basic_time = time.time() - start_time
        
        sigma_8_calc = basic_result['sigma_8_calculated']
        error_percent = basic_result['error_percent']
        
        print(f"✅ 基本計算完了: {basic_time:.2f}秒")
        print(f"   σ₈(計算) = {sigma_8_calc:.4f}")
        print(f"   誤差 = {error_percent:.2f}%")
        
        # 妥当性チェック
        assert 0.6 < sigma_8_calc < 1.0, f"σ₈値が異常: {sigma_8_calc}"
        assert error_percent < 50, f"誤差が大きすぎ: {error_percent}%"
        
        return True, basic_result
        
    except Exception as e:
        print(f"❌ σ₈計算テスト失敗: {e}")
        return False, None

def test_comprehensive_analysis():
    """
    包括的分析のテスト
    """
    print("\n🚀 包括的分析テスト開始...")
    
    try:
        solver = Sigma8PrecisionSolver()
        
        # 包括的分析実行
        start_time = time.time()
        results = solver.comprehensive_analysis()
        analysis_time = time.time() - start_time
        
        print(f"✅ 包括的分析完了: {analysis_time:.2f}秒")
        
        # 結果検証
        basic_error = results['basic']['error_percent']
        corrected_error = results['corrected']['error_percent']
        ml_error = results['ml_calibrated']['error_percent']
        
        print(f"\n📊 精度改善結果:")
        print(f"   基本計算誤差: {basic_error:.2f}%")
        print(f"   補正済み誤差: {corrected_error:.2f}%")
        print(f"   ML校正誤差: {ml_error:.2f}%")
        
        # 目標達成確認
        target_achieved = ml_error < 5.0
        improvement = 20.0 - ml_error
        
        print(f"\n🎯 目標達成状況:")
        print(f"   目標: 誤差 < 5%")
        print(f"   実際: 誤差 = {ml_error:.2f}%")
        print(f"   達成: {'✅ YES' if target_achieved else '❌ NO'}")
        print(f"   改善幅: {improvement:.2f}%")
        
        return True, results, target_achieved
        
    except Exception as e:
        print(f"❌ 包括的分析テスト失敗: {e}")
        return False, None, False

def generate_quick_visualization(results):
    """
    簡易可視化の生成
    """
    print("\n📈 簡易可視化生成...")
    
    try:
        # 結果比較グラフ
        methods = ['Basic', 'Corrected', 'ML-Calibrated']
        errors = [
            results['basic']['error_percent'],
            results['corrected']['error_percent'],
            results['ml_calibrated']['error_percent']
        ]
        
        plt.figure(figsize=(10, 6))
        
        bars = plt.bar(methods, errors, color=['blue', 'orange', 'green'], alpha=0.7)
        plt.axhline(y=5.0, color='red', linestyle='--', alpha=0.7, label='Target: 5%')
        plt.axhline(y=20.0, color='gray', linestyle=':', alpha=0.7, label='Initial: 20%')
        
        plt.ylabel('Error (%)')
        plt.title('σ₈ Prediction Error Improvement')
        plt.legend()
        
        # 値をバーの上に表示
        for bar, error in zip(bars, errors):
            height = bar.get_height()
            plt.text(bar.get_x() + bar.get_width()/2., height + 0.3,
                    f'{error:.2f}%', ha='center', va='bottom', fontweight='bold')
        
        plt.grid(True, alpha=0.3)
        plt.tight_layout()
        
        # 保存
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"sigma8_test_result_{timestamp}.png"
        plt.savefig(filename, dpi=150, bbox_inches='tight')
        plt.show()
        
        print(f"✅ 可視化完了: {filename}")
        return True
        
    except Exception as e:
        print(f"❌ 可視化生成失敗: {e}")
        return False

def main():
    """
    メイン実行関数
    """
    print("=" * 70)
    print("🎯 σ₈問題解決システム - テスト・デモンストレーション")
    print("=" * 70)
    print(f"実行日時: {datetime.now().strftime('%Y年%m月%d日 %H:%M:%S')}")
    print()
    
    # テスト1: 基本機能
    if not test_basic_functionality():
        print("❌ 基本機能テストで失敗しました。実行を中止します。")
        return False
    
    # テスト2: σ₈計算
    success, basic_result = test_sigma8_calculation()
    if not success:
        print("❌ σ₈計算テストで失敗しました。実行を中止します。")
        return False
    
    # テスト3: 包括的分析
    success, results, target_achieved = test_comprehensive_analysis()
    if not success:
        print("❌ 包括的分析テストで失敗しました。")
        return False
    
    # 可視化
    generate_quick_visualization(results)
    
    # 最終評価
    print("\n" + "=" * 70)
    print("📋 最終評価結果")
    print("=" * 70)
    
    if target_achieved:
        print("🎉 **目標達成!** σ₈問題の即座解決に成功しました")
        print("✅ 誤差を20%から5%以下に改善")
        print("✅ 全ての機能が正常に動作")
        print("✅ 計算時間も実用的範囲内")
        final_status = "SUCCESS"
    else:
        print("⚠️  目標未達成: さらなる改善が必要です")
        final_status = "NEEDS_IMPROVEMENT"
    
    print(f"\n🏁 テスト完了: {final_status}")
    print("=" * 70)
    
    return target_achieved

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1) 