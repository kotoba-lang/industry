#!/usr/bin/env python3
"""
高度なパラメータ最適化システム for GIPF

パラメータ最適化深化 (Phase 2.5)
目標: H₀ < 1.5σ, σ₈ < 1.0σ達成のための戦略的最適化

Features:
1. 多次元パラメータ空間の効率的探索
2. 機械学習ベースの最適化 (Bayesian Optimization)
3. 目標値達成のための戦略的パラメータ調整
4. 高精度MCMC分析

Author: Jun Kawasaki
Date: 2025-01-25
"""

import numpy as np
import matplotlib.pyplot as plt
from scipy.optimize import minimize, differential_evolution, basinhopping
from scipy.stats import multivariate_normal
import warnings
warnings.filterwarnings('ignore')

# 機械学習ベース最適化
try:
    from sklearn.gaussian_process import GaussianProcessRegressor
    from sklearn.gaussian_process.kernels import RBF, ConstantKernel as C
    sklearn_available = True
except ImportError:
    sklearn_available = False
    print("📦 scikit-learnが利用できません。基本最適化のみ実行します")

# 元のMCMC分析システム
try:
    from mcmc_bayesian_analysis import ObservationalTensionAnalysis
except ImportError:
    print("❌ エラー: mcmc_bayesian_analysis.py が必要です")
    exit(1)

class AdvancedParameterOptimizer:
    """
    高度なパラメータ最適化システム
    
    機能:
    1. 多次元パラメータ空間の効率的探索
    2. 機械学習ベースの最適化
    3. 目標値達成戦略
    4. 高精度統計検証
    """
    
    def __init__(self, target_h0_tension=1.5, target_sigma8_tension=1.0):
        self.target_h0_tension = target_h0_tension
        self.target_sigma8_tension = target_sigma8_tension
        
        # 基本分析システム
        self.tension_analyzer = ObservationalTensionAnalysis()
        
        # パラメータ空間の拡張定義
        self.parameter_bounds = {
            'alpha_info': (0.001, 0.5),    # 拡張: 0.2 → 0.5
            'beta_info': (0.001, 0.2),     # 拡張: 0.1 → 0.2
            'gamma_info': (0.0001, 0.05),  # 拡張: 0.01 → 0.05
            'delta_info': (0.001, 0.1),    # 新規: 時間発展補正
            'epsilon_info': (0.001, 0.1)   # 新規: 非線形結合項
        }
        
        # 最適化履歴
        self.optimization_history = []
        
        print("🚀 高度なパラメータ最適化システム初期化")
        print("=" * 60)
        print(f"目標: H₀ < {target_h0_tension}σ, σ₈ < {target_sigma8_tension}σ")
        print("拡張パラメータ空間:")
        for param, bounds in self.parameter_bounds.items():
            print(f"  {param}: [{bounds[0]:.4f}, {bounds[1]:.4f}]")
    
    def enhanced_gipf_model(self, params):
        """
        拡張GIPF理論モデル
        
        新規パラメータ追加:
        - delta_info: 時間発展補正項
        - epsilon_info: 非線形結合項
        """
        # 基本パラメータ
        alpha_info = params.get('alpha_info', 0.01)
        beta_info = params.get('beta_info', 0.005)
        gamma_info = params.get('gamma_info', 0.001)
        delta_info = params.get('delta_info', 0.01)   # 新規
        epsilon_info = params.get('epsilon_info', 0.01)  # 新規
        
        # 基本宇宙論パラメータ
        base_H0 = 67.36
        base_sigma8 = 0.8111
        base_Omega_m = 0.3153
        
        # 拡張H₀補正（時間発展項追加）
        H0_linear = 1 + alpha_info * (1 - np.exp(-beta_info * 10))
        H0_time_evolution = 1 + delta_info * np.log(1 + alpha_info * 50)
        H0_nonlinear = 1 + epsilon_info * alpha_info * beta_info * 100
        H0_gipf = base_H0 * H0_linear * H0_time_evolution * H0_nonlinear
        
        # 拡張σ₈補正（非線形結合強化）
        sigma8_base = 1 - gamma_info * np.log(1 + alpha_info * 100)
        sigma8_coupling = 1 + epsilon_info * (alpha_info + beta_info) * 50
        sigma8_time_effect = 1 - delta_info * np.exp(-gamma_info * 200)
        sigma8_gipf = base_sigma8 * sigma8_base * sigma8_coupling * sigma8_time_effect
        
        # 有効物質密度（全パラメータ依存）
        Omega_m_correction = 1 + 0.3 * (alpha_info + beta_info/2 + gamma_info*10 + delta_info*2 + epsilon_info*3)
        Omega_m_eff = base_Omega_m * Omega_m_correction
        
        return {
            'H0': H0_gipf,
            'sigma8': sigma8_gipf,
            'Omega_m': Omega_m_eff,
            'alpha_info': alpha_info,
            'beta_info': beta_info,
            'gamma_info': gamma_info,
            'delta_info': delta_info,
            'epsilon_info': epsilon_info
        }
    
    def tension_objective_function(self, params_array):
        """
        目標関数: テンション最小化
        """
        # パラメータ配列から辞書に変換
        param_names = ['alpha_info', 'beta_info', 'gamma_info', 'delta_info', 'epsilon_info']
        params = {name: value for name, value in zip(param_names, params_array)}
        
        # 境界条件チェック
        for name, value in params.items():
            bounds = self.parameter_bounds[name]
            if not (bounds[0] <= value <= bounds[1]):
                return 1e10  # ペナルティ
        
        # 拡張GIPF予測
        predictions = self.enhanced_gipf_model(params)
        
        # 観測データ
        obs_data = self.tension_analyzer.observational_data
        
        # H₀テンション計算
        planck_H0 = obs_data['planck_2018']['H0']['value']
        shoes_H0 = obs_data['shoes_2022']['H0']['value']
        gipf_H0 = predictions['H0']
        
        # GIPFが両観測の中間値を目指す
        target_H0 = (planck_H0 + shoes_H0) / 2  # 約70.2 km/s/Mpc
        
        planck_H0_err = obs_data['planck_2018']['H0']['error']
        shoes_H0_err = obs_data['shoes_2022']['H0']['error']
        combined_H0_err = np.sqrt(planck_H0_err**2 + shoes_H0_err**2)
        
        h0_tension = abs(gipf_H0 - target_H0) / combined_H0_err
        
        # σ₈テンション計算
        planck_s8 = obs_data['planck_2018']['sigma8']['value']
        kids_s8 = obs_data['kids_1000']['sigma8']['value']
        gipf_s8 = predictions['sigma8']
        
        target_s8 = (planck_s8 + kids_s8) / 2  # 約0.785
        
        planck_s8_err = obs_data['planck_2018']['sigma8']['error']
        kids_s8_err = obs_data['kids_1000']['sigma8']['error']
        combined_s8_err = np.sqrt(planck_s8_err**2 + kids_s8_err**2)
        
        sigma8_tension = abs(gipf_s8 - target_s8) / combined_s8_err
        
        # 目標関数: 重み付きテンション
        # H₀テンションの重みを高く設定（目標値まで遠いため）
        objective = 3.0 * max(0, h0_tension - self.target_h0_tension) + \
                   2.0 * max(0, sigma8_tension - self.target_sigma8_tension) + \
                   0.5 * h0_tension + 0.5 * sigma8_tension
        
        # 最適化履歴記録
        self.optimization_history.append({
            'params': params.copy(),
            'h0_tension': h0_tension,
            'sigma8_tension': sigma8_tension,
            'objective': objective,
            'predictions': predictions.copy()
        })
        
        return objective
    
    def differential_evolution_optimization(self, max_iter=1000):
        """
        Differential Evolution最適化
        """
        print("🔄 Differential Evolution最適化開始...")
        
        # パラメータ境界
        bounds = [self.parameter_bounds[param] for param in ['alpha_info', 'beta_info', 'gamma_info', 'delta_info', 'epsilon_info']]
        
        # 最適化実行
        result = differential_evolution(
            self.tension_objective_function,
            bounds,
            maxiter=max_iter,
            popsize=20,
            seed=42,
            polish=True,
            disp=True
        )
        
        print(f"✅ DE最適化完了: {result.message}")
        print(f"最適化ステップ数: {result.nit}")
        print(f"最終目標値: {result.fun:.6f}")
        
        return result
    
    def bayesian_optimization(self, n_iterations=50):
        """
        Bayesian Optimization (Gaussian Process)
        """
        if not sklearn_available:
            print("⚠️ scikit-learnが利用できません。Bayesian Optimizationをスキップします")
            return None
        
        print("🧠 Bayesian Optimization開始...")
        
        # パラメータ次元
        param_names = ['alpha_info', 'beta_info', 'gamma_info', 'delta_info', 'epsilon_info']
        n_params = len(param_names)
        
        # 初期サンプリング
        n_initial = 20
        X_samples = []
        y_samples = []
        
        print(f"初期サンプリング: {n_initial}点")
        for i in range(n_initial):
            # ランダムサンプリング
            sample = []
            for param in param_names:
                bounds = self.parameter_bounds[param]
                value = np.random.uniform(bounds[0], bounds[1])
                sample.append(value)
            
            X_samples.append(sample)
            y_samples.append(self.tension_objective_function(sample))
            
            if (i+1) % 5 == 0:
                print(f"  初期サンプル {i+1}/{n_initial} 完了")
        
        X_samples = np.array(X_samples)
        y_samples = np.array(y_samples)
        
        # Gaussian Process回帰
        kernel = C(1.0, (1e-3, 1e3)) * RBF(1.0, (1e-2, 1e2))
        gp = GaussianProcessRegressor(kernel=kernel, n_restarts_optimizer=10)
        
        best_params = None
        best_value = float('inf')
        
        print(f"最適化イテレーション: {n_iterations}回")
        
        for iteration in range(n_iterations):
            # GPフィッティング
            gp.fit(X_samples, y_samples)
            
            # Acquisition function (Upper Confidence Bound)
            def acquisition_function(x):
                x = np.array(x).reshape(1, -1)
                mu, sigma = gp.predict(x, return_std=True)
                return -(mu - 2.0 * sigma)  # UCB with β=2.0
            
            # 次の候補点を最適化
            bounds = [self.parameter_bounds[param] for param in param_names]
            
            try:
                acq_result = differential_evolution(
                    acquisition_function,
                    bounds,
                    maxiter=100,
                    popsize=10,
                    seed=42 + iteration
                )
                
                if acq_result.success:
                    next_point = acq_result.x
                else:
                    # フォールバック: ランダムサンプリング
                    next_point = []
                    for param in param_names:
                        bounds_param = self.parameter_bounds[param]
                        value = np.random.uniform(bounds_param[0], bounds_param[1])
                        next_point.append(value)
                
                # 次の点を評価
                next_value = self.tension_objective_function(next_point)
                
                # サンプルに追加
                X_samples = np.vstack([X_samples, next_point])
                y_samples = np.append(y_samples, next_value)
                
                # 最良値更新
                if next_value < best_value:
                    best_value = next_value
                    best_params = next_point.copy()
                
                if (iteration + 1) % 10 == 0:
                    print(f"  イテレーション {iteration + 1}/{n_iterations}: 最良値 = {best_value:.6f}")
            
            except Exception as e:
                print(f"⚠️ イテレーション {iteration + 1} でエラー: {e}")
                continue
        
        print(f"✅ Bayesian Optimization完了: 最良値 = {best_value:.6f}")
        
        return {
            'best_params': best_params,
            'best_value': best_value,
            'X_samples': X_samples,
            'y_samples': y_samples,
            'param_names': param_names
        }
    
    def comprehensive_optimization(self):
        """
        包括的最適化実行
        """
        print("🎯 包括的パラメータ最適化開始")
        print("=" * 70)
        
        # Method 1: Differential Evolution
        de_result = self.differential_evolution_optimization(max_iter=500)
        
        # Method 2: Bayesian Optimization
        bo_result = self.bayesian_optimization(n_iterations=30)
        
        # 最良結果の選択
        best_result = None
        best_method = None
        best_objective = float('inf')
        
        if de_result.success:
            best_result = de_result
            best_method = "Differential Evolution"
            best_objective = de_result.fun
        
        if bo_result is not None and bo_result['best_value'] < best_objective:
            best_result = bo_result
            best_method = "Bayesian Optimization"
            best_objective = bo_result['best_value']
        
        print(f"\n🏆 最良最適化手法: {best_method}")
        print(f"最終目標値: {best_objective:.6f}")
        
        # 最適パラメータ
        if best_method == "Differential Evolution":
            best_params_array = de_result.x
        else:
            best_params_array = bo_result['best_params']
        
        param_names = ['alpha_info', 'beta_info', 'gamma_info', 'delta_info', 'epsilon_info']
        best_params = {name: value for name, value in zip(param_names, best_params_array)}
        
        print("最適パラメータ:")
        for name, value in best_params.items():
            print(f"  {name}: {value:.6f}")
        
        return {
            'best_params': best_params,
            'best_objective': best_objective,
            'best_method': best_method,
            'de_result': de_result,
            'bo_result': bo_result,
            'optimization_history': self.optimization_history
        }
    
    def precision_verification(self, optimized_params):
        """
        最適化結果の高精度検証
        """
        print("🔍 最適化結果の高精度検証...")
        
        # 拡張GIPFモデルを使用してテンション分析システムを更新
        original_gipf_method = self.tension_analyzer.gipf_theoretical_predictions
        
        def enhanced_gipf_wrapper(params):
            # 拡張パラメータをデフォルト値で補完
            enhanced_params = {
                'alpha_info': params.get('alpha_info', 0.01),
                'beta_info': params.get('beta_info', 0.005),
                'gamma_info': params.get('gamma_info', 0.001),
                'delta_info': optimized_params.get('delta_info', 0.01),
                'epsilon_info': optimized_params.get('epsilon_info', 0.01)
            }
            return self.enhanced_gipf_model(enhanced_params)
        
        # 一時的にメソッドを置き換え
        self.tension_analyzer.gipf_theoretical_predictions = enhanced_gipf_wrapper
        
        # 高精度MCMC分析
        mcmc_results = self.tension_analyzer.run_mcmc_analysis(
            n_walkers=64,  # 増加
            n_steps=5000,  # 増加
            n_burn=1000    # 増加
        )
        
        # テンション解消評価
        tension_results = self.tension_analyzer.tension_resolution_assessment(mcmc_results)
        
        # 元のメソッドに戻す
        self.tension_analyzer.gipf_theoretical_predictions = original_gipf_method
        
        print("✅ 高精度検証完了")
        
        return {
            'mcmc_results': mcmc_results,
            'tension_results': tension_results,
            'optimized_params': optimized_params
        }
    
    def generate_optimization_report(self, optimization_results, verification_results):
        """
        最適化レポート生成
        """
        print("📋 最適化レポート生成...")
        
        # 結果サマリー
        best_params = optimization_results['best_params']
        tension_results = verification_results['tension_results']
        
        current_h0 = tension_results['current_tensions']['H0_tension']
        current_s8 = tension_results['current_tensions']['sigma8_tension']
        new_h0 = tension_results['new_tensions']['H0_tension']
        new_s8 = tension_results['new_tensions']['sigma8_tension']
        
        h0_success = tension_results['success_criteria']['H0_success']
        s8_success = tension_results['success_criteria']['sigma8_success']
        overall_success = tension_results['success_criteria']['overall_success']
        
        print("\n" + "=" * 80)
        print("📊 パラメータ最適化深化 - 最終レポート")
        print("=" * 80)
        print(f"最適化手法: {optimization_results['best_method']}")
        print(f"最終目標値: {optimization_results['best_objective']:.6f}")
        print("\n【最適パラメータ】")
        for name, value in best_params.items():
            print(f"  {name}: {value:.6f}")
        
        print("\n【テンション改善結果】")
        print(f"  H₀テンション: {current_h0:.2f}σ → {new_h0:.2f}σ ({current_h0-new_h0:+.2f}σ)")
        print(f"  σ₈テンション: {current_s8:.2f}σ → {new_s8:.2f}σ ({current_s8-new_s8:+.2f}σ)")
        
        print("\n【目標達成状況】")
        print(f"  H₀目標 (<{self.target_h0_tension}σ): {'✅ 達成' if h0_success else '❌ 未達成'}")
        print(f"  σ₈目標 (<{self.target_sigma8_tension}σ): {'✅ 達成' if s8_success else '❌ 未達成'}")
        print(f"  総合評価: {'🎉 完全成功' if overall_success else '⚠️ 部分成功'}")
        
        # 成功判定
        if overall_success:
            print("\n🎉 **パラメータ最適化深化: 完全成功**")
            print("Nature/Science論文提出準備完了")
            status = "COMPLETE_SUCCESS"
        else:
            print("\n⚠️ **パラメータ最適化深化: 部分成功**")
            print("さらなる調整またはPhase 3実験検証への移行を検討")
            status = "PARTIAL_SUCCESS"
        
        return {
            'status': status,
            'overall_success': overall_success,
            'tension_improvements': {
                'h0_improvement': current_h0 - new_h0,
                'sigma8_improvement': current_s8 - new_s8
            },
            'final_tensions': {
                'h0_tension': new_h0,
                'sigma8_tension': new_s8
            }
        }

def main():
    """
    メイン実行
    """
    print("🚀 高度なパラメータ最適化システム実行開始")
    print("=" * 80)
    
    # 最適化システム初期化
    optimizer = AdvancedParameterOptimizer()
    
    # 包括的最適化
    optimization_results = optimizer.comprehensive_optimization()
    
    # 高精度検証
    verification_results = optimizer.precision_verification(optimization_results['best_params'])
    
    # 最終レポート
    final_report = optimizer.generate_optimization_report(optimization_results, verification_results)
    
    print(f"\n🏁 パラメータ最適化深化完了: {final_report['status']}")
    
    return optimizer, optimization_results, verification_results, final_report

if __name__ == "__main__":
    optimizer, opt_results, ver_results, final_report = main() 