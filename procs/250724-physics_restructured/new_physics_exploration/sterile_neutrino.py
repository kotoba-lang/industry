"""
Sterile Neutrino研究システム (Sterile Neutrino Research System)
右巻きニュートリノの現象論と宇宙論への影響

Author: Jun Kawasaki
Date: 2025/01/22
License: MIT

概要:
- 右巻きニュートリノの理論的基盤
- 宇宙論的進化と核合成への影響
- 振動現象と質量機構の解析
- 観測制約と検出可能性の評価
- 構造形成への影響分析
- 実験データとの比較検証
"""

import numpy as np
import scipy as sp
from scipy.integrate import solve_ivp, quad
from scipy.optimize import minimize, fsolve
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
import h5py
import logging
from typing import Dict, List, Tuple, Optional, Union, Any
from dataclasses import dataclass, field
import time
import warnings
warnings.filterwarnings('ignore')

# 基本設定
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@dataclass
class SterileNeutrinoConfig:
    """Sterile Neutrino研究設定"""
    # 基本パラメータ
    mass_range: Tuple[float, float] = (1e-3, 1e3)  # eV, sterile neutrino mass range
    mixing_angle_range: Tuple[float, float] = (1e-12, 1e-1)  # mixing angle range
    num_sterile_species: int = 1  # number of sterile neutrino species
    
    # 標準ニュートリノパラメータ
    active_masses: List[float] = field(default_factory=lambda: [0.0, 0.009, 0.05])  # eV
    mixing_angles: List[float] = field(default_factory=lambda: [0.59, 0.84, 0.15])  # θ12, θ23, θ13
    
    # 宇宙論パラメータ
    H_0: float = 67.66  # km/s/Mpc
    Omega_dm: float = 0.264  # Dark matter density
    Omega_b: float = 0.049  # Baryon density
    Omega_r: float = 5.38e-5  # Radiation density
    N_eff_std: float = 3.046  # Effective number of neutrino species
    
    # 核合成パラメータ
    T_BBN: float = 1.0  # MeV, Big Bang nucleosynthesis temperature
    eta_b: float = 6.1e-10  # Baryon-to-photon ratio
    
    # 計算設定
    temperature_range: Tuple[float, float] = (100, 1e-4)  # MeV, T_max to T_min
    time_steps: int = 1000
    
    # 観測制約
    constraints: Dict[str, Dict] = field(default_factory=lambda: {
        'reactor_anomaly': {
            'delta_m2': 1.8,  # eV^2
            'sin2_2theta': 0.1,
            'confidence': 0.95
        },
        'gallium_anomaly': {
            'delta_m2': 1.0,  # eV^2
            'sin2_2theta': 0.05,
            'confidence': 0.99
        },
        'LSND': {
            'delta_m2': 1.2,  # eV^2
            'sin2_2theta': 0.003,
            'confidence': 0.95
        },
        'MiniBooNE': {
            'delta_m2': 0.5,  # eV^2
            'sin2_2theta': 0.002,
            'confidence': 0.90
        }
    })

class SterileNeutrinoTheory:
    """Sterile Neutrino理論計算"""
    
    def __init__(self, config: SterileNeutrinoConfig):
        self.config = config
        
        # 基本定数
        self.G_F = 1.166e-5  # GeV^-2, Fermi constant
        self.alpha_em = 1/137.0  # electromagnetic coupling
        self.sin2_theta_w = 0.23  # weak mixing angle
        
        # 物理定数
        self.c = 2.998e8  # m/s
        self.hbar = 1.055e-34  # J·s
        self.k_B = 1.381e-23  # J/K
        self.eV_to_J = 1.602e-19  # eV to Joule
        
        # ニュートリノ質量行列
        self.setup_mass_matrix()
        
        logger.info("Sterile neutrino theory initialized")
    
    def setup_mass_matrix(self):
        """質量行列の設定"""
        # 4x4 質量行列 (3 active + 1 sterile)
        n_total = 3 + self.config.num_sterile_species
        self.mass_matrix = np.zeros((n_total, n_total))
        
        # 標準ニュートリノ質量
        for i in range(3):
            self.mass_matrix[i, i] = self.config.active_masses[i]
        
        # sterile neutrino質量 (仮の値)
        for i in range(self.config.num_sterile_species):
            self.mass_matrix[3 + i, 3 + i] = 1.0  # eV
    
    def calculate_mixing_matrix(self, theta_sterile: float, mass_sterile: float) -> np.ndarray:
        """混合行列の計算"""
        # 4x4 混合行列
        U = np.eye(4)
        
        # 標準PMNS行列要素
        c12 = np.cos(self.config.mixing_angles[0])
        s12 = np.sin(self.config.mixing_angles[0])
        c23 = np.cos(self.config.mixing_angles[1])
        s23 = np.sin(self.config.mixing_angles[1])
        c13 = np.cos(self.config.mixing_angles[2])
        s13 = np.sin(self.config.mixing_angles[2])
        
        # 3x3 PMNS行列
        U[:3, :3] = np.array([
            [c12*c13, s12*c13, s13],
            [-s12*c23 - c12*s23*s13, c12*c23 - s12*s23*s13, s23*c13],
            [s12*s23 - c12*c23*s13, -c12*s23 - s12*c23*s13, c23*c13]
        ])
        
        # sterile-active混合
        cos_theta = np.cos(theta_sterile)
        sin_theta = np.sin(theta_sterile)
        
        # 簡略化: e-sterile混合のみ
        U[0, 0] *= cos_theta
        U[0, 3] = sin_theta
        U[3, 0] = -sin_theta
        U[3, 3] = cos_theta
        
        return U
    
    def calculate_effective_potential(self, T: float, n_e: float) -> np.ndarray:
        """有効ポテンシャルの計算"""
        # 4x4 有効ポテンシャル行列
        V_eff = np.zeros((4, 4))
        
        # 標準ニュートリノのMSW効果
        V_cc = np.sqrt(2) * self.G_F * n_e * 1e-9  # GeV単位
        V_nc = -0.5 * V_cc  # 中性カレント
        
        # CC interaction (電子ニュートリノのみ)
        V_eff[0, 0] = V_cc
        
        # NC interaction (全ニュートリノ)
        for i in range(3):
            V_eff[i, i] += V_nc
        
        # sterile neutrinoはMSW効果を受けない
        # V_eff[3, 3] = 0
        
        return V_eff
    
    def calculate_oscillation_probability(self, E: float, L: float, 
                                        theta_sterile: float, mass_sterile: float) -> float:
        """振動確率の計算"""
        # 2-flavor近似での活性-sterile振動
        
        # 質量二乗差
        delta_m2 = mass_sterile**2 - self.config.active_masses[0]**2  # eV^2
        
        # 振動長
        L_osc = 4 * np.pi * E / delta_m2  # km (E in MeV, delta_m2 in eV^2)
        
        # 振動確率
        P = (np.sin(2 * theta_sterile))**2 * (np.sin(1.27 * delta_m2 * L / E))**2
        
        return P
    
    def calculate_sterile_production_rate(self, T: float, theta_sterile: float, 
                                        mass_sterile: float) -> float:
        """Sterile neutrino生成率の計算"""
        # 熱平衡からの偏差を考慮した生成率
        
        # 相互作用率
        Gamma = (self.G_F**2 * T**5) / (192 * np.pi**3) * (np.sin(theta_sterile))**2
        
        # Hubble parameter
        H = self.calculate_hubble_parameter(T)
        
        # 生成効率
        if Gamma > H:
            efficiency = 1.0  # 熱平衡
        else:
            efficiency = Gamma / H  # 非平衡生成
        
        return efficiency
    
    def calculate_hubble_parameter(self, T: float) -> float:
        """Hubble parameterの計算"""
        # 放射優勢期のHubble parameter
        # H = sqrt(8πG/3 * ρ_rad)
        
        # 放射エネルギー密度
        rho_rad = (np.pi**2 / 30) * (T**4)  # 自然単位系
        
        # 重力結合定数
        G = 6.708e-39  # GeV^-2
        
        H = np.sqrt(8 * np.pi * G / 3 * rho_rad)
        
        return H
    
    def calculate_decay_rate(self, mass_sterile: float, theta_sterile: float) -> float:
        """Sterile neutrino崩壊率の計算"""
        # 3-body decay: νs → νe + e+ + e-
        
        if mass_sterile < 2 * 0.511e-3:  # 2me in GeV
            return 0.0  # 運動学的に禁止
        
        # 崩壊率
        Gamma_decay = (self.G_F**2 * mass_sterile**5) / (192 * np.pi**3) * (np.sin(theta_sterile))**2
        
        return Gamma_decay
    
    def calculate_see_saw_mass(self, yukawa_coupling: float, right_handed_mass: float) -> float:
        """See-saw機構による質量の計算"""
        # Type I see-saw: m_nu = y^2 * v^2 / M_R
        
        v = 246  # GeV, Higgs VEV
        m_nu = (yukawa_coupling**2 * v**2) / right_handed_mass
        
        return m_nu

class SterileNeutrinoCosmology:
    """Sterile Neutrino宇宙論"""
    
    def __init__(self, config: SterileNeutrinoConfig):
        self.config = config
        self.theory = SterileNeutrinoTheory(config)
        
        # 宇宙論パラメータ
        self.H_0 = config.H_0 * 1e3 / (3.086e22)  # s^-1
        self.T_0 = 2.725  # K, CMB temperature today
        
        # 温度配列
        self.temperatures = np.logspace(np.log10(config.temperature_range[0]), 
                                       np.log10(config.temperature_range[1]), 
                                       config.time_steps)
        
        logger.info("Sterile neutrino cosmology initialized")
    
    def calculate_effective_dof(self, T: float, mass_sterile: float) -> float:
        """有効自由度の計算"""
        # 標準模型の寄与
        g_star_SM = 106.75  # at T >> m_W
        
        # sterile neutrinoの寄与
        if T > mass_sterile * 1e-3:  # MeV単位
            g_sterile = 7/8 * 2 * self.config.num_sterile_species  # 2 = 2 helicity states
        else:
            g_sterile = 0.0
        
        g_star_total = g_star_SM + g_sterile
        
        return g_star_total
    
    def calculate_BBN_constraints(self, mass_sterile: float, theta_sterile: float) -> Dict[str, float]:
        """Big Bang核合成制約の計算"""
        constraints = {}
        
        # 有効ニュートリノ種数への寄与
        Delta_N_eff = self.calculate_delta_N_eff(mass_sterile, theta_sterile)
        constraints['Delta_N_eff'] = Delta_N_eff
        
        # ヘリウム存在比への影響
        Y_p_standard = 0.247  # 標準値
        Delta_Y_p = 0.013 * Delta_N_eff  # 近似式
        constraints['Y_p'] = Y_p_standard + Delta_Y_p
        
        # 重水素存在比への影響
        D_H_standard = 2.5e-5  # 標準値
        Delta_D_H = -0.1 * Delta_N_eff * D_H_standard  # 近似式
        constraints['D_H'] = D_H_standard + Delta_D_H
        
        return constraints
    
    def calculate_delta_N_eff(self, mass_sterile: float, theta_sterile: float) -> float:
        """有効ニュートリノ種数の変化"""
        # 熱平衡からの偏差を考慮
        
        # 生成効率
        production_efficiency = self.theory.calculate_sterile_production_rate(
            self.config.T_BBN, theta_sterile, mass_sterile
        )
        
        # 有効種数への寄与
        Delta_N_eff = production_efficiency * self.config.num_sterile_species
        
        return Delta_N_eff
    
    def solve_boltzmann_equation(self, mass_sterile: float, theta_sterile: float) -> Tuple[np.ndarray, np.ndarray]:
        """Boltzmann方程式の求解"""
        def boltzmann_rhs(T, y):
            # y[0] = sterile neutrino number density
            n_sterile = y[0]
            
            # 生成項
            production_rate = self.theory.calculate_sterile_production_rate(T, theta_sterile, mass_sterile)
            
            # 希釈項（宇宙膨張）
            H = self.theory.calculate_hubble_parameter(T)
            dilution_rate = 3 * H
            
            # 崩壊項
            decay_rate = self.theory.calculate_decay_rate(mass_sterile, theta_sterile)
            
            # Boltzmann方程式
            dn_dt = production_rate - dilution_rate * n_sterile - decay_rate * n_sterile
            
            return [dn_dt]
        
        # 初期条件
        y0 = [0.0]  # 初期密度ゼロ
        
        # 温度範囲
        T_span = (self.config.temperature_range[0], self.config.temperature_range[1])
        
        # 求解
        sol = solve_ivp(
            fun=boltzmann_rhs,
            t_span=T_span,
            y0=y0,
            t_eval=self.temperatures,
            method='RK45',
            rtol=1e-8
        )
        
        return sol.t, sol.y[0]
    
    def calculate_sterile_relic_density(self, mass_sterile: float, theta_sterile: float) -> float:
        """Sterile neutrino遺存密度の計算"""
        # Boltzmann方程式の求解
        T, n_sterile = self.solve_boltzmann_equation(mass_sterile, theta_sterile)
        
        # 現在の密度
        n_sterile_today = n_sterile[-1]
        
        # エネルギー密度
        rho_sterile = n_sterile_today * mass_sterile * 1e-9  # GeV/cm^3
        
        # 臨界密度
        rho_c = 1.9e-29  # g/cm^3
        
        # 密度パラメータ
        Omega_sterile = rho_sterile / rho_c
        
        return Omega_sterile
    
    def calculate_free_streaming_length(self, mass_sterile: float, T_decouple: float) -> float:
        """自由ストリーミング長の計算"""
        # 脱結合時の平均運動量
        p_avg = 3.15 * T_decouple  # 1e-3 GeV単位
        
        # 非相対論的遷移
        if mass_sterile > p_avg:
            # 非相対論的
            v_avg = p_avg / mass_sterile
        else:
            # 相対論的
            v_avg = 1.0  # 光速単位
        
        # 自由ストリーミング長
        t_0 = 13.8e9 * 365.25 * 24 * 3600  # 現在の宇宙年齢（秒）
        lambda_fs = v_avg * self.theory.c * t_0
        
        return lambda_fs
    
    def calculate_structure_formation_suppression(self, mass_sterile: float, 
                                                theta_sterile: float) -> Dict[str, float]:
        """構造形成抑制の計算"""
        suppression = {}
        
        # 自由ストリーミング長
        lambda_fs = self.calculate_free_streaming_length(mass_sterile, self.config.T_BBN)
        suppression['free_streaming_length'] = lambda_fs
        
        # 抑制スケール
        k_fs = 2 * np.pi / lambda_fs  # Mpc^-1
        suppression['suppression_scale'] = k_fs
        
        # 小スケール構造への影響
        if lambda_fs > 1e20:  # 1 kpc
            suppression['small_scale_impact'] = 'significant'
        else:
            suppression['small_scale_impact'] = 'negligible'
        
        return suppression

class SterileNeutrinoObservations:
    """Sterile Neutrino観測"""
    
    def __init__(self, config: SterileNeutrinoConfig):
        self.config = config
        self.theory = SterileNeutrinoTheory(config)
        
        logger.info("Sterile neutrino observations initialized")
    
    def calculate_reactor_anomaly_fit(self, mass_sterile: float, theta_sterile: float) -> float:
        """原子炉異常への適合度計算"""
        # 観測データ
        obs_data = self.config.constraints['reactor_anomaly']
        
        # 理論予測
        delta_m2_theory = mass_sterile**2 - self.config.active_masses[0]**2
        sin2_2theta_theory = np.sin(2 * theta_sterile)**2
        
        # χ²計算
        chi2 = ((delta_m2_theory - obs_data['delta_m2'])**2 / obs_data['delta_m2']**2 + 
                (sin2_2theta_theory - obs_data['sin2_2theta'])**2 / obs_data['sin2_2theta']**2)
        
        return chi2
    
    def calculate_gallium_anomaly_fit(self, mass_sterile: float, theta_sterile: float) -> float:
        """ガリウム異常への適合度計算"""
        obs_data = self.config.constraints['gallium_anomaly']
        
        # 理論予測
        delta_m2_theory = mass_sterile**2 - self.config.active_masses[0]**2
        sin2_2theta_theory = np.sin(2 * theta_sterile)**2
        
        # χ²計算
        chi2 = ((delta_m2_theory - obs_data['delta_m2'])**2 / obs_data['delta_m2']**2 + 
                (sin2_2theta_theory - obs_data['sin2_2theta'])**2 / obs_data['sin2_2theta']**2)
        
        return chi2
    
    def calculate_cosmological_constraints(self, mass_sterile: float, theta_sterile: float) -> Dict[str, float]:
        """宇宙論的制約の計算"""
        constraints = {}
        
        # 遺存密度制約
        Omega_sterile = self.calculate_sterile_relic_density(mass_sterile, theta_sterile)
        constraints['relic_density'] = Omega_sterile
        
        # 暗黒物質の一部として許される上限
        Omega_dm_max = self.config.Omega_dm
        constraints['dm_fraction'] = Omega_sterile / Omega_dm_max
        
        # 有効ニュートリノ種数制約
        Delta_N_eff = self.calculate_delta_N_eff(mass_sterile, theta_sterile)
        constraints['Delta_N_eff'] = Delta_N_eff
        
        # Planck制約: Delta_N_eff < 0.3
        constraints['planck_constraint'] = Delta_N_eff < 0.3
        
        return constraints
    
    def calculate_delta_N_eff(self, mass_sterile: float, theta_sterile: float) -> float:
        """有効ニュートリノ種数の変化（観測用）"""
        # 熱平衡からの偏差
        if mass_sterile < 1.0:  # eV
            # 相対論的寄与
            Delta_N_eff = (np.sin(theta_sterile))**2 * self.config.num_sterile_species
        else:
            # 非相対論的寄与
            Delta_N_eff = 0.0
        
        return Delta_N_eff
    
    def calculate_sterile_relic_density(self, mass_sterile: float, theta_sterile: float) -> float:
        """Sterile neutrino遺存密度（観測用）"""
        # Dodelson-Widrow機構
        if mass_sterile < 100:  # keV
            # 暖かい暗黒物質として寄与
            Omega_sterile = 0.27 * (mass_sterile / 3.0)**1.8 * (np.sin(theta_sterile) / 1e-9)**2
        else:
            # 冷たい暗黒物質として寄与
            Omega_sterile = 0.1 * (mass_sterile / 1000)**0.5 * (np.sin(theta_sterile) / 1e-6)**2
        
        return min(Omega_sterile, self.config.Omega_dm)
    
    def calculate_x_ray_constraints(self, mass_sterile: float, theta_sterile: float) -> Dict[str, float]:
        """X線観測制約の計算"""
        constraints = {}
        
        # 3.5 keV lineからの制約
        if 3.4 < mass_sterile < 3.6:  # keV
            # 観測された強度
            observed_intensity = 4.4e-6  # photons/cm^2/s/sr
            
            # 理論予測
            predicted_intensity = (np.sin(theta_sterile))**2 * 1e-5
            
            constraints['3.5keV_line'] = predicted_intensity / observed_intensity
        
        # 将来のX線観測制約
        constraints['future_xray_sensitivity'] = 1e-11  # sin^2(theta)
        
        return constraints
    
    def optimize_parameter_fit(self) -> Dict[str, float]:
        """パラメータフィットの最適化"""
        def objective(params):
            mass_sterile, theta_sterile = params
            
            # 制約チェック
            if not (self.config.mass_range[0] <= mass_sterile <= self.config.mass_range[1]):
                return 1e10
            if not (self.config.mixing_angle_range[0] <= theta_sterile <= self.config.mixing_angle_range[1]):
                return 1e10
            
            # 各異常への適合度
            chi2_reactor = self.calculate_reactor_anomaly_fit(mass_sterile, theta_sterile)
            chi2_gallium = self.calculate_gallium_anomaly_fit(mass_sterile, theta_sterile)
            
            # 宇宙論的制約
            cosmo_constraints = self.calculate_cosmological_constraints(mass_sterile, theta_sterile)
            
            # ペナルティ項
            penalty = 0.0
            if not cosmo_constraints['planck_constraint']:
                penalty += 1e6
            if cosmo_constraints['dm_fraction'] > 1.0:
                penalty += 1e6
            
            total_chi2 = chi2_reactor + chi2_gallium + penalty
            
            return total_chi2
        
        # 最適化
        result = minimize(objective, x0=[1.0, 1e-6], method='Nelder-Mead')
        
        return {
            'optimal_mass': result.x[0],
            'optimal_theta': result.x[1],
            'chi2_min': result.fun
        }

class SterileNeutrinoResearchSystem:
    """Sterile Neutrino研究システム統合"""
    
    def __init__(self, config: SterileNeutrinoConfig):
        self.config = config
        
        # 各コンポーネントの初期化
        self.theory = SterileNeutrinoTheory(config)
        self.cosmology = SterileNeutrinoCosmology(config)
        self.observations = SterileNeutrinoObservations(config)
        
        # 結果保存
        self.results = {}
        
        logger.info("Sterile neutrino research system initialized")
    
    def run_comprehensive_analysis(self, mass_values: np.ndarray, theta_values: np.ndarray) -> Dict[str, Any]:
        """包括的解析の実行"""
        logger.info("Starting comprehensive sterile neutrino analysis...")
        
        results = {
            'theoretical_predictions': {},
            'cosmological_constraints': {},
            'observational_fits': {},
            'parameter_optimization': {}
        }
        
        # 理論的予測
        logger.info("Calculating theoretical predictions...")
        results['theoretical_predictions'] = self.calculate_theoretical_predictions(mass_values, theta_values)
        
        # 宇宙論的制約
        logger.info("Analyzing cosmological constraints...")
        results['cosmological_constraints'] = self.analyze_cosmological_constraints(mass_values, theta_values)
        
        # 観測適合度
        logger.info("Evaluating observational fits...")
        results['observational_fits'] = self.evaluate_observational_fits(mass_values, theta_values)
        
        # パラメータ最適化
        logger.info("Optimizing parameters...")
        results['parameter_optimization'] = self.optimize_parameters()
        
        self.results = results
        logger.info("Comprehensive analysis completed")
        
        return results
    
    def calculate_theoretical_predictions(self, mass_values: np.ndarray, theta_values: np.ndarray) -> Dict[str, Any]:
        """理論的予測の計算"""
        predictions = {
            'mixing_matrices': [],
            'oscillation_probabilities': [],
            'production_rates': [],
            'decay_rates': []
        }
        
        for mass in mass_values:
            for theta in theta_values:
                # 混合行列
                U = self.theory.calculate_mixing_matrix(theta, mass)
                predictions['mixing_matrices'].append(U)
                
                # 振動確率
                P_osc = self.theory.calculate_oscillation_probability(1.0, 1.0, theta, mass)
                predictions['oscillation_probabilities'].append(P_osc)
                
                # 生成率
                prod_rate = self.theory.calculate_sterile_production_rate(self.config.T_BBN, theta, mass)
                predictions['production_rates'].append(prod_rate)
                
                # 崩壊率
                decay_rate = self.theory.calculate_decay_rate(mass, theta)
                predictions['decay_rates'].append(decay_rate)
        
        return predictions
    
    def analyze_cosmological_constraints(self, mass_values: np.ndarray, theta_values: np.ndarray) -> Dict[str, Any]:
        """宇宙論的制約の解析"""
        constraints = {
            'BBN_constraints': [],
            'relic_densities': [],
            'structure_formation': [],
            'N_eff_contributions': []
        }
        
        # 代表的な値での詳細計算
        for mass in mass_values[::10]:  # サンプリング
            for theta in theta_values[::10]:
                # BBN制約
                bbn_const = self.cosmology.calculate_BBN_constraints(mass, theta)
                constraints['BBN_constraints'].append(bbn_const)
                
                # 遺存密度
                relic_density = self.cosmology.calculate_sterile_relic_density(mass, theta)
                constraints['relic_densities'].append(relic_density)
                
                # 構造形成抑制
                structure_suppression = self.cosmology.calculate_structure_formation_suppression(mass, theta)
                constraints['structure_formation'].append(structure_suppression)
                
                # N_eff寄与
                Delta_N_eff = self.cosmology.calculate_delta_N_eff(mass, theta)
                constraints['N_eff_contributions'].append(Delta_N_eff)
        
        return constraints
    
    def evaluate_observational_fits(self, mass_values: np.ndarray, theta_values: np.ndarray) -> Dict[str, Any]:
        """観測適合度の評価"""
        fits = {
            'reactor_chi2': [],
            'gallium_chi2': [],
            'cosmological_constraints': [],
            'xray_constraints': []
        }
        
        for mass in mass_values:
            for theta in theta_values:
                # 原子炉異常適合度
                reactor_chi2 = self.observations.calculate_reactor_anomaly_fit(mass, theta)
                fits['reactor_chi2'].append(reactor_chi2)
                
                # ガリウム異常適合度
                gallium_chi2 = self.observations.calculate_gallium_anomaly_fit(mass, theta)
                fits['gallium_chi2'].append(gallium_chi2)
                
                # 宇宙論的制約
                cosmo_const = self.observations.calculate_cosmological_constraints(mass, theta)
                fits['cosmological_constraints'].append(cosmo_const)
                
                # X線制約
                xray_const = self.observations.calculate_x_ray_constraints(mass, theta)
                fits['xray_constraints'].append(xray_const)
        
        return fits
    
    def optimize_parameters(self) -> Dict[str, Any]:
        """パラメータの最適化"""
        # 最適パラメータの探索
        optimization_result = self.observations.optimize_parameter_fit()
        
        # 最適値での詳細解析
        optimal_mass = optimization_result['optimal_mass']
        optimal_theta = optimization_result['optimal_theta']
        
        detailed_analysis = {
            'optimal_parameters': optimization_result,
            'bbn_predictions': self.cosmology.calculate_BBN_constraints(optimal_mass, optimal_theta),
            'relic_density': self.cosmology.calculate_sterile_relic_density(optimal_mass, optimal_theta),
            'structure_formation': self.cosmology.calculate_structure_formation_suppression(optimal_mass, optimal_theta)
        }
        
        return detailed_analysis
    
    def save_results(self, filename: str = 'sterile_neutrino_results.h5'):
        """結果の保存"""
        with h5py.File(filename, 'w') as f:
            # 設定の保存
            config_group = f.create_group('config')
            config_group.attrs['mass_range'] = self.config.mass_range
            config_group.attrs['mixing_angle_range'] = self.config.mixing_angle_range
            
            # 結果の保存
            for key, value in self.results.items():
                if isinstance(value, dict):
                    group = f.create_group(key)
                    for subkey, subvalue in value.items():
                        if isinstance(subvalue, (list, np.ndarray)):
                            if len(subvalue) > 0 and isinstance(subvalue[0], (int, float)):
                                group.create_dataset(subkey, data=subvalue)
                        else:
                            group.attrs[subkey] = subvalue
        
        logger.info(f"Results saved to {filename}")
    
    def generate_summary_report(self) -> str:
        """要約レポートの生成"""
        if not self.results:
            return "No results available"
        
        report = []
        report.append("=== Sterile Neutrino Research Summary ===\n")
        
        # パラメータ最適化結果
        optimization = self.results.get('parameter_optimization', {})
        if optimization and 'optimal_parameters' in optimization:
            optimal = optimization['optimal_parameters']
            report.append("Optimal Parameters:")
            report.append(f"  Mass: {optimal['optimal_mass']:.3f} eV")
            report.append(f"  Mixing angle: {optimal['optimal_theta']:.2e}")
            report.append(f"  χ² minimum: {optimal['chi2_min']:.2f}")
            report.append("")
        
        # 宇宙論的制約
        cosmological = self.results.get('cosmological_constraints', {})
        if cosmological:
            report.append("Cosmological Constraints:")
            if 'relic_densities' in cosmological:
                relic_densities = cosmological['relic_densities']
                if relic_densities:
                    avg_relic = np.mean(relic_densities)
                    report.append(f"  Average relic density: {avg_relic:.3f}")
            report.append("")
        
        # 観測適合度
        observations = self.results.get('observational_fits', {})
        if observations:
            report.append("Observational Fits:")
            if 'reactor_chi2' in observations:
                reactor_chi2 = observations['reactor_chi2']
                if reactor_chi2:
                    min_chi2 = min(reactor_chi2)
                    report.append(f"  Best reactor anomaly fit: χ² = {min_chi2:.2f}")
            report.append("")
        
        return "\n".join(report)

def main():
    """メイン実行関数"""
    # 設定
    config = SterileNeutrinoConfig()
    
    # 研究システムの初期化
    sterile_system = SterileNeutrinoResearchSystem(config)
    
    # パラメータ範囲
    mass_values = np.logspace(np.log10(config.mass_range[0]), np.log10(config.mass_range[1]), 20)
    theta_values = np.logspace(np.log10(config.mixing_angle_range[0]), np.log10(config.mixing_angle_range[1]), 20)
    
    # 包括的解析の実行
    results = sterile_system.run_comprehensive_analysis(mass_values, theta_values)
    
    # 結果の保存
    sterile_system.save_results()
    
    # レポートの生成
    report = sterile_system.generate_summary_report()
    print(report)
    
    # 結果の可視化
    visualize_sterile_results(results, mass_values, theta_values)

def visualize_sterile_results(results: Dict[str, Any], mass_values: np.ndarray, theta_values: np.ndarray):
    """結果の可視化"""
    fig, axes = plt.subplots(2, 2, figsize=(15, 12))
    
    # パラメータ空間でのχ²分布
    if 'observational_fits' in results:
        fits = results['observational_fits']
        if 'reactor_chi2' in fits:
            ax = axes[0, 0]
            
            # 2D グリッドでのχ²プロット
            if len(fits['reactor_chi2']) == len(mass_values) * len(theta_values):
                chi2_grid = np.array(fits['reactor_chi2']).reshape(len(mass_values), len(theta_values))
                
                im = ax.imshow(chi2_grid, extent=[np.log10(theta_values[0]), np.log10(theta_values[-1]),
                                                 np.log10(mass_values[0]), np.log10(mass_values[-1])],
                              aspect='auto', origin='lower', cmap='viridis')
                
                ax.set_xlabel('log₁₀(sin²θ)')
                ax.set_ylabel('log₁₀(Mass [eV])')
                ax.set_title('Reactor Anomaly χ² Distribution')
                plt.colorbar(im, ax=ax)
    
    # 遺存密度
    if 'cosmological_constraints' in results:
        cosmo = results['cosmological_constraints']
        if 'relic_densities' in cosmo:
            ax = axes[0, 1]
            relic_densities = cosmo['relic_densities']
            
            if relic_densities:
                ax.hist(relic_densities, bins=30, alpha=0.7, edgecolor='black')
                ax.set_xlabel('Relic Density Ωₛ')
                ax.set_ylabel('Frequency')
                ax.set_title('Sterile Neutrino Relic Density Distribution')
                ax.grid(True, alpha=0.3)
    
    # N_eff寄与
    if 'cosmological_constraints' in results:
        cosmo = results['cosmological_constraints']
        if 'N_eff_contributions' in cosmo:
            ax = axes[1, 0]
            N_eff_contributions = cosmo['N_eff_contributions']
            
            if N_eff_contributions:
                ax.hist(N_eff_contributions, bins=30, alpha=0.7, edgecolor='black')
                ax.set_xlabel('ΔNₑff')
                ax.set_ylabel('Frequency')
                ax.set_title('Effective Neutrino Species Contribution')
                ax.grid(True, alpha=0.3)
    
    # 最適化結果
    if 'parameter_optimization' in results:
        opt = results['parameter_optimization']
        if 'optimal_parameters' in opt:
            ax = axes[1, 1]
            
            optimal = opt['optimal_parameters']
            ax.scatter(optimal['optimal_mass'], optimal['optimal_theta'], 
                      c='red', s=100, marker='*', label='Optimal Point')
            
            ax.set_xlabel('Mass [eV]')
            ax.set_ylabel('Mixing Angle sin²θ')
            ax.set_title('Parameter Optimization Result')
            ax.set_xscale('log')
            ax.set_yscale('log')
            ax.legend()
            ax.grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig('sterile_neutrino_results.png', dpi=300, bbox_inches='tight')
    plt.show()ゔぇゔぇ

if __name__ == "__main__":
    main() 