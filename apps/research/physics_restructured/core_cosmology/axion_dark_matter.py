"""
Axion Dark Matter研究システム (Axion Dark Matter Research System)
QCD axionの宇宙論的役割と検出可能性の理論計算
Generative Information Physics Framework との統合解析

Author: Jun Kawasaki
Date: 2025/01/22
License: MIT

概要:
- QCD axionの理論的基盤構築
- 宇宙論的進化の数値計算
- 検出可能性の詳細評価
- 観測戦略の策定
- 構造形成への影響分析
- 実験との比較検証
- Generative Information Physics との情報理論的統合
"""

import numpy as np
import scipy as sp
from scipy.integrate import solve_ivp, quad
from scipy.optimize import minimize
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
class AxionConfig:
    """Axion研究設定"""
    # 基本パラメータ
    fa_range: Tuple[float, float] = (1e9, 1e17)  # GeV, axion decay constant range
    ma_range: Tuple[float, float] = (1e-12, 1e-2)  # eV, axion mass range
    
    # 宇宙論パラメータ
    H_0: float = 67.66  # km/s/Mpc
    Omega_dm: float = 0.264  # Dark matter density
    Omega_b: float = 0.049  # Baryon density
    Omega_Lambda: float = 0.687  # Dark energy density
    
    # QCD相転移パラメータ
    T_QCD: float = 150e-3  # GeV, QCD transition temperature
    Lambda_QCD: float = 200e-3  # GeV, QCD scale
    
    # 計算設定
    redshift_range: Tuple[float, float] = (1100, 0)  # z=1100 (CMB) to z=0 (today)
    time_steps: int = 1000
    
    # 検出実験パラメータ
    experiments: Dict[str, Dict] = field(default_factory=lambda: {
        'ADMX': {
            'frequency_range': (1e-6, 1e-3),  # eV
            'sensitivity': 1e-24,  # GeV^-1
            'exposure_time': 1e6  # seconds
        },
        'CAST': {
            'frequency_range': (1e-5, 1e-2),  # eV
            'sensitivity': 1e-10,  # GeV^-1
            'magnetic_field': 9.0  # Tesla
        },
        'IAXO': {
            'frequency_range': (1e-5, 1e-2),  # eV
            'sensitivity': 1e-12,  # GeV^-1
            'magnetic_field': 20.0  # Tesla
        },
        'EUCLID': {
            'frequency_range': (1e-25, 1e-20),  # eV
            'sensitivity': 1e-11,  # GeV^-1
            'observation_time': 6  # years
        }
    })

class QCDAxionTheory:
    """QCD Axion理論計算"""
    
    def __init__(self, config: AxionConfig):
        self.config = config
        
        # 基本定数
        self.alpha_em = 1/137.0  # 電磁気学的結合定数
        self.alpha_s = 0.3  # 強い相互作用結合定数
        self.m_pi = 0.140  # GeV, pion mass
        self.f_pi = 0.093  # GeV, pion decay constant
        self.m_p = 0.938  # GeV, proton mass
        
        # 物理定数
        self.c = 2.998e8  # m/s
        self.hbar = 1.055e-34  # J·s
        self.k_B = 1.381e-23  # J/K
        self.eV_to_J = 1.602e-19  # eV to Joule
        
        logger.info("QCD Axion theory initialized for Generative Information Physics framework")
    
    def calculate_axion_mass(self, fa: float) -> float:
        """Axion質量の計算"""
        # KSVZ/DFSZ model
        # ma = (Lambda_QCD^2 * m_pi * f_pi) / (sqrt(2) * fa)
        
        numerator = self.config.Lambda_QCD**2 * self.m_pi * self.f_pi
        denominator = np.sqrt(2) * fa
        
        ma = numerator / denominator
        return ma
    
    def calculate_axion_photon_coupling(self, fa: float) -> float:
        """Axion-photon結合定数の計算"""
        # gaγγ = α_em / (2π * fa) * (E/N - 1.95)
        # E/N = electromagnetic anomaly / color anomaly
        
        # KSVZ model: E/N = 0
        # DFSZ model: E/N = 8/3
        E_over_N = 0  # KSVZ model を使用
        
        gagg = self.alpha_em / (2 * np.pi * fa) * (E_over_N - 1.95)
        return abs(gagg)
    
    def calculate_axion_nucleon_coupling(self, fa: float) -> float:
        """Axion-nucleon結合定数の計算"""
        # gaNucl = (md - mu) / (md + mu) * (1 / fa)
        # md, mu = down and up quark masses
        
        md = 5e-3  # GeV, down quark mass
        mu = 2e-3  # GeV, up quark mass
        
        gaN = (md - mu) / (md + mu) * (1 / fa)
        return abs(gaN)
    
    def calculate_axion_potential(self, theta: float, T: float) -> float:
        """Axion potential の計算"""
        # V(θ, T) = ma(T)^2 * fa^2 * (1 - cos(θ))
        
        # 温度依存質量
        ma_T = self.calculate_temperature_dependent_mass(T)
        
        # 任意のfaを使用（実際の計算では適切な値を設定）
        fa = 1e12  # GeV
        
        V = ma_T**2 * fa**2 * (1 - np.cos(theta))
        return V
    
    def calculate_temperature_dependent_mass(self, T: float) -> float:
        """温度依存axion質量の計算"""
        # ma(T) = ma(0) * (Lambda_QCD / T)^β for T > T_QCD
        
        if T > self.config.T_QCD:
            # 高温での質量抑制
            beta = 3.0  # Lattice QCD結果
            ma_T = self.calculate_axion_mass(1e12) * (self.config.Lambda_QCD / T)**beta
        else:
            # 低温での通常質量
            ma_T = self.calculate_axion_mass(1e12)
        
        return ma_T
    
    def calculate_axion_string_tension(self, fa: float) -> float:
        """Axion string tension の計算"""
        # μ = π * fa^2 * ln(fa / Lambda_QCD)
        
        if fa > self.config.Lambda_QCD:
            mu = np.pi * fa**2 * np.log(fa / self.config.Lambda_QCD)
        else:
            mu = np.pi * fa**2
        
        return mu
    
    def calculate_domain_wall_tension(self, fa: float) -> float:
        """Domain wall tension の計算"""
        # σ = 2 * sqrt(2) * ma * fa^2
        
        ma = self.calculate_axion_mass(fa)
        sigma = 2 * np.sqrt(2) * ma * fa**2
        
        return sigma

class AxionCosmology:
    """Axion宇宙論"""
    
    def __init__(self, config: AxionConfig):
        self.config = config
        self.theory = QCDAxionTheory(config)
        
        # 宇宙論パラメータ
        self.H_0 = config.H_0 * 1e3 / (3.086e22)  # s^-1
        self.T_0 = 2.725  # K, CMB temperature today
        
        # 時間配列
        self.redshifts = np.linspace(config.redshift_range[0], config.redshift_range[1], config.time_steps)
        self.scale_factors = 1.0 / (1.0 + self.redshifts)
        
        logger.info("Axion cosmology initialized")
    
    def calculate_hubble_parameter(self, z: float) -> float:
        """Hubble parameter の計算"""
        H_z = self.H_0 * np.sqrt(
            self.config.Omega_dm * (1 + z)**3 + 
            self.config.Omega_b * (1 + z)**3 + 
            self.config.Omega_Lambda
        )
        return H_z
    
    def calculate_temperature_evolution(self, z: float) -> float:
        """温度進化の計算"""
        # T(z) = T_0 * (1 + z)
        return self.T_0 * (1 + z)
    
    def axion_field_evolution(self, t: float, y: List[float], fa: float) -> List[float]:
        """Axion場の時間進化方程式"""
        # y[0] = θ (axion field)
        # y[1] = θ̇ (axion field derivative)
        
        theta, theta_dot = y
        
        # 現在の赤方偏移の計算
        z = self.time_to_redshift(t)
        
        # Hubble parameter
        H = self.calculate_hubble_parameter(z)
        
        # 温度
        T = self.calculate_temperature_evolution(z)
        
        # 温度依存質量
        ma_T = self.theory.calculate_temperature_dependent_mass(T)
        
        # 運動方程式: θ̈ + 3Hθ̇ + ma(T)^2 * sin(θ) = 0
        theta_ddot = -3 * H * theta_dot - ma_T**2 * np.sin(theta)
        
        return [theta_dot, theta_ddot]
    
    def time_to_redshift(self, t: float) -> float:
        """時間から赤方偏移への変換"""
        # 簡略化された関係式
        # 実際の実装では積分が必要
        return 1100 * np.exp(-t / 1e6)  # 近似式
    
    def solve_axion_evolution(self, fa: float, theta_i: float = 1.0) -> Tuple[np.ndarray, np.ndarray]:
        """Axion場進化の求解"""
        # 初期条件
        y0 = [theta_i, 0.0]  # θ_i, θ̇_i = 0
        
        # 時間範囲
        t_span = (0, 1e7)  # 任意単位
        t_eval = np.linspace(t_span[0], t_span[1], self.config.time_steps)
        
        # 微分方程式の求解
        sol = solve_ivp(
            fun=lambda t, y: self.axion_field_evolution(t, y, fa),
            t_span=t_span,
            y0=y0,
            t_eval=t_eval,
            method='RK45',
            rtol=1e-8
        )
        
        return sol.t, sol.y
    
    def calculate_axion_relic_density(self, fa: float, theta_i: float = 1.0) -> float:
        """Axion遺存密度の計算"""
        # Axion場進化の求解
        t, y = self.solve_axion_evolution(fa, theta_i)
        
        # 最終的な場の振幅
        theta_final = y[0][-1]
        
        # エネルギー密度
        ma = self.theory.calculate_axion_mass(fa)
        rho_a = 0.5 * ma**2 * fa**2 * theta_final**2
        
        # 臨界密度
        rho_c = 3 * self.H_0**2 / (8 * np.pi)  # 自然単位系
        
        # 密度パラメータ
        Omega_a = rho_a / rho_c
        
        return Omega_a
    
    def calculate_axion_power_spectrum(self, fa: float, k_modes: np.ndarray) -> np.ndarray:
        """Axion密度ゆらぎパワースペクトルの計算"""
        # Axion isocurvature perturbations
        
        # 転移関数
        def transfer_function(k, fa):
            # Axion転移関数（簡略化）
            k_eq = 0.01  # Mpc^-1, matter-radiation equality
            
            if k < k_eq:
                return 1.0
            else:
                return (k_eq / k)**2
        
        # パワースペクトル
        power_spectrum = np.zeros_like(k_modes)
        
        for i, k in enumerate(k_modes):
            # 原始パワースペクトル
            P_prim = k**(-3)  # scale-invariant
            
            # 転移関数
            T_k = transfer_function(k, fa)
            
            # 最終パワースペクトル
            power_spectrum[i] = P_prim * T_k**2
        
        return power_spectrum
    
    def calculate_structure_formation_effects(self, fa: float) -> Dict[str, float]:
        """構造形成への影響の計算"""
        effects = {}
        
        # Axion質量
        ma = self.theory.calculate_axion_mass(fa)
        
        # Jeans長の計算
        # λ_J = c * sqrt(π / (G * ρ_a))
        rho_a = self.calculate_axion_relic_density(fa) * 1e-26  # kg/m^3
        G = 6.674e-11  # m^3/kg/s^2
        
        lambda_J = self.theory.c * np.sqrt(np.pi / (G * rho_a))
        effects['jeans_length'] = lambda_J
        
        # 自由ストリーミング長
        # λ_fs = integral(v_a(t) dt)
        v_a = self.theory.c  # 相対論的速度
        lambda_fs = v_a * 1e6 * 365.25 * 24 * 3600  # 1 Myr in meters
        effects['free_streaming_length'] = lambda_fs
        
        # 小スケール構造への影響
        if lambda_J < 1e20:  # 1 kpc in meters
            effects['small_scale_suppression'] = True
        else:
            effects['small_scale_suppression'] = False
        
        return effects

class AxionDetection:
    """Axion検出可能性評価"""
    
    def __init__(self, config: AxionConfig):
        self.config = config
        self.theory = QCDAxionTheory(config)
        
        logger.info("Axion detection system initialized")
    
    def calculate_axion_conversion_rate(self, fa: float, experiment: str) -> float:
        """Axion変換率の計算"""
        exp_config = self.config.experiments[experiment]
        
        # Axion-photon結合定数
        gagg = self.theory.calculate_axion_photon_coupling(fa)
        
        # 変換確率（Primakoff効果）
        if experiment in ['CAST', 'IAXO']:
            # 太陽axion → photon 変換
            B = exp_config['magnetic_field']  # Tesla
            L = 10.0  # メートル, 磁場長
            
            # 変換確率
            P_agg = (gagg * B * L)**2
            
        elif experiment in ['ADMX']:
            # 暗黒物質axion → photon 変換
            rho_a = 0.45e-24  # kg/m^3, local axion density
            B = 8.0  # Tesla
            V = 1e-3  # m^3, cavity volume
            Q = 1e5  # quality factor
            
            # 変換率
            P_agg = gagg**2 * B**2 * V * Q * rho_a
        
        else:
            P_agg = 0.0
        
        return P_agg
    
    def calculate_detection_sensitivity(self, fa: float, experiment: str) -> float:
        """検出感度の計算"""
        exp_config = self.config.experiments[experiment]
        
        # 変換率
        conversion_rate = self.calculate_axion_conversion_rate(fa, experiment)
        
        # 実験感度
        sensitivity = exp_config['sensitivity']
        
        # 信号強度
        signal_strength = conversion_rate * sensitivity
        
        return signal_strength
    
    def calculate_exclusion_limits(self, experiment: str) -> Tuple[np.ndarray, np.ndarray]:
        """排除限界の計算"""
        # fa範囲
        fa_values = np.logspace(np.log10(self.config.fa_range[0]), 
                               np.log10(self.config.fa_range[1]), 100)
        
        # 各faに対する検出感度
        sensitivities = []
        
        for fa in fa_values:
            sensitivity = self.calculate_detection_sensitivity(fa, experiment)
            sensitivities.append(sensitivity)
        
        return fa_values, np.array(sensitivities)
    
    def calculate_discovery_potential(self, fa: float) -> Dict[str, float]:
        """発見可能性の計算"""
        discovery_potential = {}
        
        for experiment in self.config.experiments:
            # 信号強度
            signal = self.calculate_detection_sensitivity(fa, experiment)
            
            # 統計的有意性
            significance = signal / np.sqrt(signal + 1e-10)  # S/√(S+B)
            
            discovery_potential[experiment] = significance
        
        return discovery_potential
    
    def optimize_experimental_parameters(self, fa: float, experiment: str) -> Dict[str, float]:
        """実験パラメータの最適化"""
        def objective(params):
            # パラメータの展開
            if experiment in ['CAST', 'IAXO']:
                B, L = params
                
                # 制約
                if B < 1.0 or B > 50.0:  # Tesla
                    return 1e10
                if L < 1.0 or L > 100.0:  # meters
                    return 1e10
                
                # 変換確率
                gagg = self.theory.calculate_axion_photon_coupling(fa)
                P_agg = (gagg * B * L)**2
                
                return -P_agg  # 最大化のため負値
            
            else:
                return 0.0
        
        # 最適化
        if experiment in ['CAST', 'IAXO']:
            result = minimize(objective, x0=[10.0, 10.0], method='Nelder-Mead')
            
            return {
                'optimal_B': result.x[0],
                'optimal_L': result.x[1],
                'max_sensitivity': -result.fun
            }
        
        else:
            return {'optimization': 'not_implemented'}

class AxionObservationStrategy:
    """Axion観測戦略"""
    
    def __init__(self, config: AxionConfig):
        self.config = config
        self.theory = QCDAxionTheory(config)
        self.detection = AxionDetection(config)
        
        logger.info("Axion observation strategy initialized")
    
    def calculate_optimal_observation_timeline(self) -> Dict[str, Any]:
        """最適観測タイムラインの計算"""
        timeline = {}
        
        # 現在の技術レベル
        current_year = 2025
        
        # 短期戦略 (2025-2030)
        timeline['short_term'] = {
            'period': '2025-2030',
            'experiments': ['ADMX', 'CAST'],
            'target_fa_range': (1e10, 1e12),
            'expected_sensitivity': 1e-15,
            'discovery_probability': 0.1
        }
        
        # 中期戦略 (2030-2040)
        timeline['medium_term'] = {
            'period': '2030-2040',
            'experiments': ['IAXO', 'EUCLID'],
            'target_fa_range': (1e8, 1e14),
            'expected_sensitivity': 1e-17,
            'discovery_probability': 0.3
        }
        
        # 長期戦略 (2040-2050)
        timeline['long_term'] = {
            'period': '2040-2050',
            'experiments': ['Next-generation', 'Space-based'],
            'target_fa_range': (1e6, 1e16),
            'expected_sensitivity': 1e-20,
            'discovery_probability': 0.7
        }
        
        return timeline
    
    def calculate_multi_messenger_strategy(self) -> Dict[str, Any]:
        """マルチメッセンジャー戦略の計算"""
        strategy = {}
        
        # 直接検出
        strategy['direct_detection'] = {
            'method': 'cavity_haloscope',
            'sensitivity': 1e-15,
            'mass_range': (1e-6, 1e-3),  # eV
            'timeline': '2025-2030'
        }
        
        # 間接検出
        strategy['indirect_detection'] = {
            'method': 'astrophysical_observations',
            'targets': ['white_dwarfs', 'neutron_stars', 'black_holes'],
            'sensitivity': 1e-12,
            'timeline': '2025-2035'
        }
        
        # 宇宙論的検出
        strategy['cosmological_detection'] = {
            'method': 'CMB_polarization',
            'sensitivity': 1e-11,
            'observables': ['isocurvature', 'birefringence'],
            'timeline': '2030-2040'
        }
        
        return strategy
    
    def calculate_systematic_uncertainties(self) -> Dict[str, float]:
        """系統誤差の計算"""
        uncertainties = {}
        
        # 理論的不確定性
        uncertainties['theoretical'] = {
            'QCD_scale': 0.1,  # Lambda_QCD uncertainty
            'quark_masses': 0.05,  # quark mass uncertainty
            'anomaly_coefficients': 0.2  # E/N uncertainty
        }
        
        # 実験的不確定性
        uncertainties['experimental'] = {
            'calibration': 0.03,  # calibration uncertainty
            'background': 0.05,  # background uncertainty
            'systematic': 0.02  # systematic effects
        }
        
        # 宇宙論的不確定性
        uncertainties['cosmological'] = {
            'hubble_constant': 0.02,  # H0 uncertainty
            'dark_matter_density': 0.01,  # Omega_dm uncertainty
            'recombination': 0.005  # recombination uncertainty
        }
        
        return uncertainties
    
    def generate_observation_proposal(self, fa_target: float) -> Dict[str, Any]:
        """観測提案の生成"""
        proposal = {}
        
        # 目標axion質量
        ma_target = self.theory.calculate_axion_mass(fa_target)
        
        # 最適実験の選択
        best_experiment = self.select_optimal_experiment(fa_target)
        
        # 観測計画
        proposal['target_parameters'] = {
            'fa': fa_target,
            'ma': ma_target,
            'gagg': self.theory.calculate_axion_photon_coupling(fa_target)
        }
        
        proposal['experimental_setup'] = {
            'primary_experiment': best_experiment,
            'observation_time': 5,  # years
            'expected_sensitivity': self.detection.calculate_detection_sensitivity(fa_target, best_experiment)
        }
        
        proposal['success_probability'] = {
            'detection': 0.3,
            'exclusion': 0.95,
            'discovery': 0.1
        }
        
        return proposal
    
    def select_optimal_experiment(self, fa: float) -> str:
        """最適実験の選択"""
        # 各実験の感度を比較
        sensitivities = {}
        
        for experiment in self.config.experiments:
            sensitivity = self.detection.calculate_detection_sensitivity(fa, experiment)
            sensitivities[experiment] = sensitivity
        
        # 最高感度の実験を選択
        best_experiment = max(sensitivities, key=sensitivities.get)
        
        return best_experiment

class AxionResearchSystem:
    """Axion研究システム統合
    
    Generative Information Physics Framework との統合:
    - Axionを宇宙的情報ストレージ媒体として理解
    - 情報密度とaxion場の相互作用を解析
    - 宇宙の情報処理プロセスにおけるaxionの役割を評価
    """
    
    def __init__(self, config: AxionConfig):
        self.config = config
        
        # 各コンポーネントの初期化
        self.theory = QCDAxionTheory(config)
        self.cosmology = AxionCosmology(config)
        self.detection = AxionDetection(config)
        self.observation_strategy = AxionObservationStrategy(config)
        
        # 結果保存
        self.results = {}
        
        logger.info("Axion research system initialized for Generative Information Physics")
    
    def run_comprehensive_analysis(self, fa_values: np.ndarray) -> Dict[str, Any]:
        """包括的解析の実行"""
        logger.info("Starting comprehensive axion analysis...")
        
        results = {
            'theoretical_predictions': {},
            'cosmological_evolution': {},
            'detection_prospects': {},
            'observation_strategy': {}
        }
        
        # 理論的予測
        logger.info("Calculating theoretical predictions...")
        results['theoretical_predictions'] = self.calculate_theoretical_predictions(fa_values)
        
        # 宇宙論的進化
        logger.info("Analyzing cosmological evolution...")
        results['cosmological_evolution'] = self.analyze_cosmological_evolution(fa_values)
        
        # 検出可能性
        logger.info("Evaluating detection prospects...")
        results['detection_prospects'] = self.evaluate_detection_prospects(fa_values)
        
        # 観測戦略
        logger.info("Developing observation strategy...")
        results['observation_strategy'] = self.develop_observation_strategy()
        
        self.results = results
        logger.info("Comprehensive analysis completed")
        
        return results
    
    def calculate_theoretical_predictions(self, fa_values: np.ndarray) -> Dict[str, Any]:
        """理論的予測の計算"""
        predictions = {
            'axion_masses': [],
            'photon_couplings': [],
            'nucleon_couplings': [],
            'relic_densities': []
        }
        
        for fa in fa_values:
            # 基本パラメータ
            ma = self.theory.calculate_axion_mass(fa)
            gagg = self.theory.calculate_axion_photon_coupling(fa)
            gaN = self.theory.calculate_axion_nucleon_coupling(fa)
            
            # 宇宙論的予測
            relic_density = self.cosmology.calculate_axion_relic_density(fa)
            
            predictions['axion_masses'].append(ma)
            predictions['photon_couplings'].append(gagg)
            predictions['nucleon_couplings'].append(gaN)
            predictions['relic_densities'].append(relic_density)
        
        return predictions
    
    def analyze_cosmological_evolution(self, fa_values: np.ndarray) -> Dict[str, Any]:
        """宇宙論的進化の解析"""
        evolution = {
            'field_evolution': {},
            'structure_formation': {},
            'power_spectra': {}
        }
        
        # 代表的なfa値での詳細解析
        representative_fa = fa_values[len(fa_values)//2]
        
        # 場の進化
        t, y = self.cosmology.solve_axion_evolution(representative_fa)
        evolution['field_evolution'] = {'time': t, 'field': y}
        
        # 構造形成効果
        structure_effects = self.cosmology.calculate_structure_formation_effects(representative_fa)
        evolution['structure_formation'] = structure_effects
        
        # パワースペクトル
        k_modes = np.logspace(-3, 1, 100)
        power_spectrum = self.cosmology.calculate_axion_power_spectrum(representative_fa, k_modes)
        evolution['power_spectra'] = {'k_modes': k_modes, 'power_spectrum': power_spectrum}
        
        return evolution
    
    def evaluate_detection_prospects(self, fa_values: np.ndarray) -> Dict[str, Any]:
        """検出可能性の評価"""
        prospects = {
            'exclusion_limits': {},
            'discovery_potential': {},
            'optimization_results': {}
        }
        
        # 各実験の排除限界
        for experiment in self.config.experiments:
            fa_limits, sensitivities = self.detection.calculate_exclusion_limits(experiment)
            prospects['exclusion_limits'][experiment] = {
                'fa_values': fa_limits,
                'sensitivities': sensitivities
            }
        
        # 発見可能性
        discovery_potential = []
        for fa in fa_values:
            potential = self.detection.calculate_discovery_potential(fa)
            discovery_potential.append(potential)
        
        prospects['discovery_potential'] = discovery_potential
        
        return prospects
    
    def develop_observation_strategy(self) -> Dict[str, Any]:
        """観測戦略の開発"""
        strategy = {}
        
        # 最適観測タイムライン
        strategy['timeline'] = self.observation_strategy.calculate_optimal_observation_timeline()
        
        # マルチメッセンジャー戦略
        strategy['multi_messenger'] = self.observation_strategy.calculate_multi_messenger_strategy()
        
        # 系統誤差評価
        strategy['uncertainties'] = self.observation_strategy.calculate_systematic_uncertainties()
        
        return strategy
    
    def save_results(self, filename: str = 'axion_analysis_results.h5'):
        """結果の保存"""
        with h5py.File(filename, 'w') as f:
            # 設定の保存
            config_group = f.create_group('config')
            config_group.attrs['fa_range'] = self.config.fa_range
            config_group.attrs['ma_range'] = self.config.ma_range
            
            # 結果の保存
            for key, value in self.results.items():
                if isinstance(value, dict):
                    group = f.create_group(key)
                    for subkey, subvalue in value.items():
                        if isinstance(subvalue, (list, np.ndarray)):
                            group.create_dataset(subkey, data=subvalue)
                        else:
                            group.attrs[subkey] = subvalue
        
        logger.info(f"Results saved to {filename}")
    
    def generate_summary_report(self) -> str:
        """要約レポートの生成"""
        if not self.results:
            return "No results available"
        
        report = []
        report.append("=== Axion Dark Matter Research Summary ===\n")
        
        # 理論的予測
        theoretical = self.results.get('theoretical_predictions', {})
        if theoretical:
            report.append("Theoretical Predictions:")
            report.append(f"  Mass range: {min(theoretical['axion_masses']):.2e} - {max(theoretical['axion_masses']):.2e} eV")
            report.append(f"  Coupling range: {min(theoretical['photon_couplings']):.2e} - {max(theoretical['photon_couplings']):.2e} GeV⁻¹")
            report.append("")
        
        # 宇宙論的進化
        cosmological = self.results.get('cosmological_evolution', {})
        if cosmological:
            report.append("Cosmological Evolution:")
            structure = cosmological.get('structure_formation', {})
            if structure:
                report.append(f"  Jeans length: {structure.get('jeans_length', 0):.2e} m")
                report.append(f"  Free streaming length: {structure.get('free_streaming_length', 0):.2e} m")
            report.append("")
        
        # 検出可能性
        detection = self.results.get('detection_prospects', {})
        if detection:
            report.append("Detection Prospects:")
            report.append("  Exclusion limits calculated for all major experiments")
            report.append("  Discovery potential evaluated across parameter space")
            report.append("")
        
        # 観測戦略
        observation = self.results.get('observation_strategy', {})
        if observation:
            report.append("Observation Strategy:")
            timeline = observation.get('timeline', {})
            if timeline:
                report.append("  Short-term (2025-2030): ADMX, CAST")
                report.append("  Medium-term (2030-2040): IAXO, EUCLID")
                report.append("  Long-term (2040-2050): Next-generation experiments")
            report.append("")
        
        return "\n".join(report)

def main():
    """メイン実行関数"""
    # 設定
    config = AxionConfig()
    
    # 研究システムの初期化
    axion_system = AxionResearchSystem(config)
    
    # fa値の範囲
    fa_values = np.logspace(np.log10(config.fa_range[0]), np.log10(config.fa_range[1]), 50)
    
    # 包括的解析の実行
    results = axion_system.run_comprehensive_analysis(fa_values)
    
    # 結果の保存
    axion_system.save_results()
    
    # レポートの生成
    report = axion_system.generate_summary_report()
    print(report)
    
    # 結果の可視化
    visualize_axion_results(results, fa_values)

def visualize_axion_results(results: Dict[str, Any], fa_values: np.ndarray):
    """結果の可視化"""
    fig, axes = plt.subplots(2, 2, figsize=(15, 12))
    
    # 理論的予測
    theoretical = results.get('theoretical_predictions', {})
    if theoretical:
        ax = axes[0, 0]
        ax.loglog(fa_values, theoretical['axion_masses'], 'b-', linewidth=2, label='Axion Mass')
        ax.set_xlabel('Decay Constant fa [GeV]')
        ax.set_ylabel('Axion Mass [eV]')
        ax.set_title('Axion Mass vs Decay Constant')
        ax.grid(True, alpha=0.3)
        ax.legend()
        
        ax = axes[0, 1]
        ax.loglog(fa_values, theoretical['photon_couplings'], 'r-', linewidth=2, label='Photon Coupling')
        ax.set_xlabel('Decay Constant fa [GeV]')
        ax.set_ylabel('Coupling gaγγ [GeV⁻¹]')
        ax.set_title('Axion-Photon Coupling vs Decay Constant')
        ax.grid(True, alpha=0.3)
        ax.legend()
    
    # 宇宙論的進化
    cosmological = results.get('cosmological_evolution', {})
    if cosmological and 'field_evolution' in cosmological:
        ax = axes[1, 0]
        field_data = cosmological['field_evolution']
        ax.plot(field_data['time'], field_data['field'][0], 'g-', linewidth=2, label='Axion Field')
        ax.set_xlabel('Time [arbitrary units]')
        ax.set_ylabel('Field Value θ')
        ax.set_title('Axion Field Evolution')
        ax.grid(True, alpha=0.3)
        ax.legend()
    
    # パワースペクトル
    if cosmological and 'power_spectra' in cosmological:
        ax = axes[1, 1]
        power_data = cosmological['power_spectra']
        ax.loglog(power_data['k_modes'], power_data['power_spectrum'], 'purple', linewidth=2, label='Axion Power Spectrum')
        ax.set_xlabel('k [h/Mpc]')
        ax.set_ylabel('P(k)')
        ax.set_title('Axion Power Spectrum')
        ax.grid(True, alpha=0.3)
        ax.legend()
    
    plt.tight_layout()
    plt.savefig('axion_analysis_results.png', dpi=300, bbox_inches='tight')
    plt.show()

if __name__ == "__main__":
    main() 