#!/usr/bin/env python3
"""
MCMC/Bayesian Analysis for Observational Tension Resolution

Phase 2実装: σ₈/H₀テンション解消の統計的検証
- MCMC parameter sampling 
- Bayesian evidence calculation
- Observational constraint integration
- Statistical tension metrics

Author: Jun Kawasaki
Date: 2025-01-25
"""

import numpy as np
import matplotlib.pyplot as plt
from scipy.stats import multivariate_normal, chi2
from scipy.optimize import minimize
import emcee
import warnings
warnings.filterwarnings('ignore')

try:
    from sigma8_problem_solution import Sigma8PrecisionSolver
except ImportError:
    print("❌ エラー: sigma8_problem_solution.py が必要です")
    exit(1)

class ObservationalTensionAnalysis:
    """
    観測テンション解消の統計的検証システム
    
    主要機能:
    1. σ₈/H₀テンション定量化
    2. MCMC parameter sampling
    3. Bayesian model comparison
    4. 統計的信頼度評価
    """
    
    def __init__(self):
        # 観測データ (Planck 2018, SH0ES 2022, KiDS-1000)
        self.observational_data = {
            'planck_2018': {
                'H0': {'value': 67.36, 'error': 0.54},  # km/s/Mpc
                'sigma8': {'value': 0.8111, 'error': 0.0060},
                'Omega_m': {'value': 0.3153, 'error': 0.0073},
                'source': 'Planck Collaboration (2020)'
            },
            'shoes_2022': {
                'H0': {'value': 73.04, 'error': 1.04},  # km/s/Mpc  
                'source': 'Riess et al. (2022)'
            },
            'kids_1000': {
                'sigma8': {'value': 0.759, 'error': 0.021},
                'source': 'Heymans et al. (2021)'
            }
        }
        
        # テンション指標
        self.current_tensions = {
            'H0_tension': self._calculate_h0_tension(),
            'sigma8_tension': self._calculate_sigma8_tension()
        }
        
        # σ₈解決システム
        self.sigma8_solver = Sigma8PrecisionSolver()
        
        print("🔬 観測テンション解消統計検証システム初期化")
        print("=" * 60)
        print(f"現在のH₀テンション: {self.current_tensions['H0_tension']:.2f}σ")
        print(f"現在のσ₈テンション: {self.current_tensions['sigma8_tension']:.2f}σ")
        print("目標: H₀ < 1.5σ, σ₈ < 1.0σ")
        
    def _calculate_h0_tension(self):
        """H₀テンションの計算"""
        planck_H0 = self.observational_data['planck_2018']['H0']['value']
        planck_err = self.observational_data['planck_2018']['H0']['error']
        shoes_H0 = self.observational_data['shoes_2022']['H0']['value']
        shoes_err = self.observational_data['shoes_2022']['H0']['error']
        
        delta_H0 = abs(planck_H0 - shoes_H0)
        combined_error = np.sqrt(planck_err**2 + shoes_err**2)
        
        return delta_H0 / combined_error
    
    def _calculate_sigma8_tension(self):
        """σ₈テンションの計算"""
        planck_s8 = self.observational_data['planck_2018']['sigma8']['value']
        planck_err = self.observational_data['planck_2018']['sigma8']['error']
        kids_s8 = self.observational_data['kids_1000']['sigma8']['value']
        kids_err = self.observational_data['kids_1000']['sigma8']['error']
        
        delta_s8 = abs(planck_s8 - kids_s8)
        combined_error = np.sqrt(planck_err**2 + kids_err**2)
        
        return delta_s8 / combined_error
    
    def gipf_theoretical_predictions(self, params):
        """
        Generative Information Physics Framework (GIPF) 理論予測
        
        Parameters:
        -----------
        params : dict
            alpha_info, beta_info, gamma_info: 情報物理学パラメータ
        """
        # 基本宇宙論パラメータ
        base_H0 = 67.36  # Planck基準
        base_sigma8 = 0.8111
        base_Omega_m = 0.3153
        
        # 情報補正項
        alpha_info = params.get('alpha_info', 0.01)
        beta_info = params.get('beta_info', 0.005)  
        gamma_info = params.get('gamma_info', 0.001)
        
        # H₀補正 (情報処理による宇宙膨張修正)
        H0_correction = 1 + alpha_info * (1 - np.exp(-beta_info * 10))
        H0_gipf = base_H0 * H0_correction
        
        # σ₈補正 (情報密度による構造形成強化)
        sigma8_enhancement = 1 - gamma_info * np.log(1 + alpha_info * 100)
        sigma8_gipf = base_sigma8 * sigma8_enhancement
        
        # Ω_m補正 (有効物質密度)
        Omega_m_eff = base_Omega_m * (1 + 0.5 * alpha_info)
        
        return {
            'H0': H0_gipf,
            'sigma8': sigma8_gipf, 
            'Omega_m': Omega_m_eff,
            'alpha_info': alpha_info,
            'beta_info': beta_info,
            'gamma_info': gamma_info
        }
    
    def likelihood_function(self, params):
        """
        観測データに対する尤度関数
        """
        predictions = self.gipf_theoretical_predictions(params)
        
        # 各観測データの chi-squared 寄与
        chi_squared_total = 0
        
        # Planck constraints
        planck = self.observational_data['planck_2018']
        chi_squared_total += ((predictions['H0'] - planck['H0']['value']) / planck['H0']['error'])**2
        chi_squared_total += ((predictions['sigma8'] - planck['sigma8']['value']) / planck['sigma8']['error'])**2
        chi_squared_total += ((predictions['Omega_m'] - planck['Omega_m']['value']) / planck['Omega_m']['error'])**2
        
        # SH0ES constraint  
        shoes = self.observational_data['shoes_2022']
        chi_squared_total += ((predictions['H0'] - shoes['H0']['value']) / shoes['H0']['error'])**2
        
        # KiDS constraint
        kids = self.observational_data['kids_1000']
        chi_squared_total += ((predictions['sigma8'] - kids['sigma8']['value']) / kids['sigma8']['error'])**2
        
        # Prior constraints (0 < alpha_info < 0.2, etc.)
        if not (0 <= params.get('alpha_info', 0) <= 0.2):
            return -np.inf
        if not (0 <= params.get('beta_info', 0) <= 0.1):
            return -np.inf
        if not (0 <= params.get('gamma_info', 0) <= 0.01):
            return -np.inf
        
        # Log likelihood
        log_likelihood = -0.5 * chi_squared_total
        
        return log_likelihood
    
    def run_mcmc_analysis(self, n_walkers=32, n_steps=5000, n_burn=1000):
        """
        MCMC parameter sampling
        """
        print("🔄 MCMC解析開始...")
        print(f"Walkers: {n_walkers}, Steps: {n_steps}, Burn-in: {n_burn}")
        
        # 初期パラメータ設定
        n_params = 3  # alpha_info, beta_info, gamma_info
        param_names = ['alpha_info', 'beta_info', 'gamma_info']
        
        # 初期位置 (パラメータ空間の中央付近)
        initial_guess = [0.05, 0.02, 0.003]
        initial_spread = [0.01, 0.005, 0.001]
        
        # Walker初期位置
        pos = []
        for i in range(n_walkers):
            walker_pos = []
            for j in range(n_params):
                walker_pos.append(np.random.normal(initial_guess[j], initial_spread[j]))
            pos.append(walker_pos)
        
        pos = np.array(pos)
        
        # MCMC likelihood wrapper
        def mcmc_log_prob(theta):
            params_dict = {name: value for name, value in zip(param_names, theta)}
            return self.likelihood_function(params_dict)
        
        # emcee sampler
        sampler = emcee.EnsembleSampler(n_walkers, n_params, mcmc_log_prob)
        
        # Burn-in
        print("  Burn-in phase...")
        state = sampler.run_mcmc(pos, n_burn, progress=True)
        sampler.reset()
        
        # Production run
        print("  Production phase...")
        sampler.run_mcmc(state, n_steps, progress=True)
        
        # Chain analysis
        samples = sampler.get_chain(flat=True)
        log_probs = sampler.get_log_prob(flat=True)
        
        # Best-fit parameters
        best_idx = np.argmax(log_probs)
        best_params = {name: samples[best_idx, i] for i, name in enumerate(param_names)}
        
        # Parameter statistics
        param_stats = {}
        for i, name in enumerate(param_names):
            param_stats[name] = {
                'mean': np.mean(samples[:, i]),
                'std': np.std(samples[:, i]),
                'median': np.median(samples[:, i]),
                'percentile_16': np.percentile(samples[:, i], 16),
                'percentile_84': np.percentile(samples[:, i], 84)
            }
        
        print("✅ MCMC解析完了")
        
        return {
            'samples': samples,
            'log_probs': log_probs,
            'best_params': best_params,
            'param_stats': param_stats,
            'param_names': param_names,
            'sampler': sampler
        }
    
    def calculate_bayesian_evidence(self, mcmc_results):
        """
        Bayesian evidenceの計算 (Thermodynamic Integration近似)
        """
        print("🧮 Bayesian Evidence計算...")
        
        samples = mcmc_results['samples']
        log_probs = mcmc_results['log_probs']
        
        # Harmonic mean approximation (簡易版)
        # 注意: より厳密な計算にはnested samplingが望ましい
        
        # Remove outliers
        valid_idx = np.isfinite(log_probs)
        valid_log_probs = log_probs[valid_idx]
        
        if len(valid_log_probs) == 0:
            return {'log_evidence': -np.inf, 'method': 'failed'}
        
        # Harmonic mean
        max_log_prob = np.max(valid_log_probs)
        shifted_probs = np.exp(valid_log_probs - max_log_prob)
        
        # Avoid division by zero
        nonzero_idx = shifted_probs > 1e-100
        if np.sum(nonzero_idx) == 0:
            return {'log_evidence': -np.inf, 'method': 'failed'}
        
        harmonic_mean = len(shifted_probs[nonzero_idx]) / np.sum(1.0 / shifted_probs[nonzero_idx])
        log_evidence = np.log(harmonic_mean) + max_log_prob
        
        print(f"✅ Log Evidence (GIPF): {log_evidence:.2f}")
        
        return {
            'log_evidence': log_evidence,
            'method': 'harmonic_mean',
            'n_samples': len(valid_log_probs)
        }
    
    def tension_resolution_assessment(self, mcmc_results):
        """
        テンション解消度の評価
        """
        print("📊 テンション解消度評価...")
        
        best_params = mcmc_results['best_params']
        gipf_predictions = self.gipf_theoretical_predictions(best_params)
        
        # 改善後のテンション計算
        # H₀ tension (Planck vs SH0ES)
        planck_H0 = self.observational_data['planck_2018']['H0']['value']
        shoes_H0 = self.observational_data['shoes_2022']['H0']['value']
        gipf_H0 = gipf_predictions['H0']
        
        # GIPFが両方の観測を統合できるかチェック
        planck_H0_err = self.observational_data['planck_2018']['H0']['error']
        shoes_H0_err = self.observational_data['shoes_2022']['H0']['error']
        
        tension_planck_gipf = abs(gipf_H0 - planck_H0) / planck_H0_err
        tension_shoes_gipf = abs(gipf_H0 - shoes_H0) / shoes_H0_err
        new_H0_tension = max(tension_planck_gipf, tension_shoes_gipf)
        
        # σ₈ tension (Planck vs KiDS)
        planck_s8 = self.observational_data['planck_2018']['sigma8']['value']
        kids_s8 = self.observational_data['kids_1000']['sigma8']['value']
        gipf_s8 = gipf_predictions['sigma8']
        
        planck_s8_err = self.observational_data['planck_2018']['sigma8']['error']
        kids_s8_err = self.observational_data['kids_1000']['sigma8']['error']
        
        tension_planck_s8 = abs(gipf_s8 - planck_s8) / planck_s8_err
        tension_kids_s8 = abs(gipf_s8 - kids_s8) / kids_s8_err
        new_sigma8_tension = max(tension_planck_s8, tension_kids_s8)
        
        # 改善度の計算
        H0_improvement = self.current_tensions['H0_tension'] - new_H0_tension
        sigma8_improvement = self.current_tensions['sigma8_tension'] - new_sigma8_tension
        
        # 成功判定
        H0_success = new_H0_tension < 1.5  # 目標: <1.5σ
        sigma8_success = new_sigma8_tension < 1.0  # 目標: <1.0σ
        
        results = {
            'current_tensions': self.current_tensions.copy(),
            'new_tensions': {
                'H0_tension': new_H0_tension,
                'sigma8_tension': new_sigma8_tension
            },
            'improvements': {
                'H0_improvement': H0_improvement,
                'sigma8_improvement': sigma8_improvement
            },
            'success_criteria': {
                'H0_success': H0_success,
                'sigma8_success': sigma8_success,
                'overall_success': H0_success and sigma8_success
            },
            'gipf_predictions': gipf_predictions
        }
        
        print(f"📈 テンション解消結果:")
        print(f"  H₀テンション: {self.current_tensions['H0_tension']:.2f}σ → {new_H0_tension:.2f}σ")
        print(f"  σ₈テンション: {self.current_tensions['sigma8_tension']:.2f}σ → {new_sigma8_tension:.2f}σ")
        print(f"  H₀目標達成: {'✅' if H0_success else '❌'} (<1.5σ)")
        print(f"  σ₈目標達成: {'✅' if sigma8_success else '❌'} (<1.0σ)")
        
        return results
    
    def generate_corner_plot(self, mcmc_results, output_file="mcmc_corner_plot.png"):
        """
        MCMC結果のコーナープロット生成
        """
        samples = mcmc_results['samples']
        param_names = mcmc_results['param_names']
        
        n_params = len(param_names)
        fig, axes = plt.subplots(n_params, n_params, figsize=(12, 12))
        
        for i in range(n_params):
            for j in range(n_params):
                ax = axes[i, j]
                
                if i == j:
                    # Diagonal: 1D histogram
                    ax.hist(samples[:, i], bins=50, alpha=0.7, density=True)
                    ax.set_xlabel(param_names[i])
                    ax.set_ylabel('Density')
                elif i > j:
                    # Lower triangle: 2D scatter plot
                    ax.scatter(samples[:, j], samples[:, i], alpha=0.3, s=1)
                    ax.set_xlabel(param_names[j])
                    ax.set_ylabel(param_names[i])
                else:
                    # Upper triangle: hide
                    ax.set_visible(False)
        
        plt.tight_layout()
        plt.savefig(output_file, dpi=150, bbox_inches='tight')
        plt.show()
        
        print(f"✅ コーナープロット保存: {output_file}")
        
        return fig
    
    def comprehensive_analysis(self):
        """
        包括的統計解析の実行
        """
        print("🎯 Phase 2: 観測テンション解消統計検証")
        print("=" * 70)
        
        # Step 1: MCMC analysis
        mcmc_results = self.run_mcmc_analysis(n_walkers=32, n_steps=3000, n_burn=500)
        
        # Step 2: Bayesian evidence
        evidence_results = self.calculate_bayesian_evidence(mcmc_results)
        
        # Step 3: Tension resolution assessment
        tension_results = self.tension_resolution_assessment(mcmc_results)
        
        # Step 4: Visualization
        corner_fig = self.generate_corner_plot(mcmc_results)
        
        # Final assessment
        print("\n" + "=" * 70)
        print("📋 Phase 2 最終評価")
        print("=" * 70)
        
        overall_success = tension_results['success_criteria']['overall_success']
        log_evidence = evidence_results['log_evidence']
        
        if overall_success:
            print("🎉 **成功**: 観測テンション解消を統計的に実証")
            print(f"✅ H₀テンション解消: {tension_results['success_criteria']['H0_success']}")
            print(f"✅ σ₈テンション解消: {tension_results['success_criteria']['sigma8_success']}")
            print(f"📊 Bayesian Evidence: {log_evidence:.2f}")
            status = "SUCCESS"
        else:
            print("⚠️ 部分的成功: さらなる調整が必要")
            status = "PARTIAL_SUCCESS"
        
        return {
            'mcmc_results': mcmc_results,
            'evidence_results': evidence_results,
            'tension_results': tension_results,
            'corner_plot': corner_fig,
            'overall_success': overall_success,
            'status': status
        }

def main():
    """
    Phase 2メイン実行
    """
    analyzer = ObservationalTensionAnalysis()
    results = analyzer.comprehensive_analysis()
    
    return analyzer, results

if __name__ == "__main__":
    analyzer, results = main()
    print(f"\n🏁 Phase 2完了: {results['status']}") 