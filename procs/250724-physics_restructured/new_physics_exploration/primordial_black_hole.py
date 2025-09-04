"""
Primordial Black Hole研究システム (Primordial Black Hole Research System)
原始ブラックホールの構造形成への影響と観測予測

Author: Jun Kawasaki
Date: 2025/01/22
License: MIT

概要:
- 原始ブラックホールの形成機構
- 質量関数と存在量の計算
- 構造形成と重力波への影響
- Hawking輻射と蒸発過程
- 観測制約と検出可能性の評価
- 暗黒物質候補としての評価
"""

import numpy as np
import scipy as sp
from scipy.integrate import solve_ivp, quad
from scipy.optimize import minimize, fsolve
from scipy.special import gamma, factorial
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
class PBHConfig:
    """Primordial Black Hole研究設定"""
    # 基本パラメータ
    mass_range: Tuple[float, float] = (1e-18, 1e6)  # solar masses
    formation_redshift: float = 1e6  # PBH formation redshift
    
    # 形成機構パラメータ
    formation_mechanism: str = 'density_fluctuation'  # density_fluctuation, cosmic_strings, phase_transitions
    critical_density_threshold: float = 0.45  # δc for PBH formation
    
    # 質量関数パラメータ
    mass_function_type: str = 'lognormal'  # lognormal, power_law, delta_function
    characteristic_mass: float = 1e-12  # solar masses
    mass_function_width: float = 0.5  # σ for lognormal
    
    # 宇宙論パラメータ
    H_0: float = 67.66  # km/s/Mpc
    Omega_dm: float = 0.264  # Dark matter density
    Omega_b: float = 0.049  # Baryon density
    Omega_Lambda: float = 0.687  # Dark energy density
    
    # Hawking輻射パラメータ
    include_hawking_radiation: bool = True
    hawking_temperature_factor: float = 1.0
    
    # 観測制約
    observational_constraints: Dict[str, Dict] = field(default_factory=lambda: {
        'microlensing': {
            'mass_range': (1e-7, 1e2),  # solar masses
            'fraction_limit': 0.1,  # f_PBH < 0.1
            'survey': 'EROS/MACHO'
        },
        'CMB_distortions': {
            'mass_range': (1e-17, 1e-13),  # solar masses
            'fraction_limit': 0.01,
            'survey': 'Planck'
        },
        'gravitational_waves': {
            'mass_range': (1e-12, 1e-8),  # solar masses
            'fraction_limit': 0.1,
            'survey': 'LIGO/Virgo'
        },
        'gamma_ray_background': {
            'mass_range': (1e-18, 1e-15),  # solar masses
            'fraction_limit': 0.001,
            'survey': 'Fermi'
        }
    })
    
    # 計算設定
    time_steps: int = 1000
    redshift_range: Tuple[float, float] = (1e6, 0)

class PBHFormation:
    """Primordial Black Hole形成機構"""
    
    def __init__(self, config: PBHConfig):
        self.config = config
        
        # 基本定数
        self.G = 6.674e-11  # m³/kg/s²
        self.c = 2.998e8  # m/s
        self.hbar = 1.055e-34  # J·s
        self.k_B = 1.381e-23  # J/K
        self.M_sun = 1.989e30  # kg
        self.M_planck = 2.176e-8  # kg
        
        # 宇宙論定数
        self.H_0 = config.H_0 * 1e3 / (3.086e22)  # s^-1
        
        logger.info("PBH formation mechanism initialized")
    
    def calculate_horizon_mass(self, t: float) -> float:
        """地平線質量の計算"""
        # M_H(t) = (4π/3) * ρ(t) * (c*t)³
        
        # 放射優勢期の密度
        rho_rad = 3 * self.H_0**2 / (8 * np.pi * self.G) * (t / (2 * self.H_0))**(-2)
        
        # 地平線質量
        M_H = (4 * np.pi / 3) * rho_rad * (self.c * t)**3
        
        return M_H / self.M_sun  # 太陽質量単位
    
    def calculate_formation_probability(self, delta: float) -> float:
        """PBH形成確率の計算"""
        # Press-Schechter formalism
        
        if self.config.formation_mechanism == 'density_fluctuation':
            # 密度ゆらぎからの形成
            delta_c = self.config.critical_density_threshold
            
            if delta > delta_c:
                # 形成確率
                beta = np.exp(-(delta_c - delta)**2 / (2 * 0.1**2))
            else:
                beta = 0.0
        
        elif self.config.formation_mechanism == 'cosmic_strings':
            # 宇宙ひもからの形成
            beta = 1e-5  # 典型的な値
        
        elif self.config.formation_mechanism == 'phase_transitions':
            # 相転移からの形成
            beta = 1e-3  # 典型的な値
        
        else:
            beta = 0.0
        
        return beta
    
    def calculate_mass_function(self, mass: float) -> float:
        """質量関数の計算"""
        M_c = self.config.characteristic_mass
        sigma = self.config.mass_function_width
        
        if self.config.mass_function_type == 'lognormal':
            # 対数正規分布
            dN_dM = (1 / (mass * sigma * np.sqrt(2 * np.pi))) * \
                    np.exp(-0.5 * ((np.log(mass) - np.log(M_c)) / sigma)**2)
        
        elif self.config.mass_function_type == 'power_law':
            # べき乗則
            alpha = 2.0  # べき指数
            dN_dM = (alpha - 1) / M_c * (mass / M_c)**(-alpha)
        
        elif self.config.mass_function_type == 'delta_function':
            # デルタ関数
            if abs(mass - M_c) < 0.1 * M_c:
                dN_dM = 1.0 / (0.1 * M_c)
            else:
                dN_dM = 0.0
        
        else:
            dN_dM = 0.0
        
        return dN_dM
    
    def calculate_initial_abundance(self, mass: float) -> float:
        """初期存在量の計算"""
        # 形成確率
        beta = self.calculate_formation_probability(0.5)  # 典型的な密度ゆらぎ
        
        # 質量関数
        dN_dM = self.calculate_mass_function(mass)
        
        # 初期存在量
        f_PBH_initial = beta * dN_dM
        
        return f_PBH_initial
    
    def calculate_accretion_rate(self, mass: float, z: float) -> float:
        """降着率の計算"""
        # Bondi-Hoyle降着
        
        # 周囲の物質密度
        rho_matter = self.calculate_matter_density(z)
        
        # 相対速度
        v_rel = 1e5  # m/s, 典型的な値
        
        # Bondi半径
        r_Bondi = 2 * self.G * mass * self.M_sun / v_rel**2
        
        # 降着率
        dM_dt = 4 * np.pi * rho_matter * r_Bondi**2 * v_rel
        
        return dM_dt / self.M_sun  # 太陽質量/秒
    
    def calculate_matter_density(self, z: float) -> float:
        """物質密度の計算"""
        # 現在の物質密度
        rho_m0 = self.config.Omega_dm * 3 * self.H_0**2 / (8 * np.pi * self.G)
        
        # 赤方偏移進化
        rho_m = rho_m0 * (1 + z)**3
        
        return rho_m

class PBHEvolution:
    """PBH時間進化"""
    
    def __init__(self, config: PBHConfig):
        self.config = config
        self.formation = PBHFormation(config)
        
        # 時間配列
        self.redshifts = np.logspace(np.log10(config.redshift_range[0]), 
                                    np.log10(config.redshift_range[1]), 
                                    config.time_steps)
        
        logger.info("PBH evolution initialized")
    
    def calculate_hawking_temperature(self, mass: float) -> float:
        """Hawking温度の計算"""
        # T_H = hbar * c³ / (8π * k_B * G * M)
        
        T_H = (self.formation.hbar * self.formation.c**3) / \
              (8 * np.pi * self.formation.k_B * self.formation.G * mass * self.formation.M_sun)
        
        return T_H * self.config.hawking_temperature_factor
    
    def calculate_hawking_luminosity(self, mass: float) -> float:
        """Hawking光度の計算"""
        # L_H = A * hbar * c⁶ / (15360π * G² * M²)
        
        A = 3.8e-16  # 粒子種数に依存する係数
        
        L_H = A * self.formation.hbar * self.formation.c**6 / \
              (15360 * np.pi * self.formation.G**2 * (mass * self.formation.M_sun)**2)
        
        return L_H
    
    def calculate_evaporation_time(self, mass: float) -> float:
        """蒸発時間の計算"""
        # t_evap = 5120π * G² * M³ / (A * hbar * c⁴)
        
        A = 3.8e-16
        
        t_evap = (5120 * np.pi * self.formation.G**2 * (mass * self.formation.M_sun)**3) / \
                 (A * self.formation.hbar * self.formation.c**4)
        
        return t_evap
    
    def solve_mass_evolution(self, mass_initial: float) -> Tuple[np.ndarray, np.ndarray]:
        """質量進化の求解"""
        def mass_evolution_rhs(t, y):
            # y[0] = mass
            mass = y[0]
            
            if mass <= 0:
                return [0]
            
            # 赤方偏移の計算
            z = self.time_to_redshift(t)
            
            # 降着による質量増加
            dM_dt_accretion = self.formation.calculate_accretion_rate(mass, z)
            
            # Hawking輻射による質量減少
            if self.config.include_hawking_radiation:
                dM_dt_hawking = -self.calculate_hawking_luminosity(mass) / self.formation.c**2
                dM_dt_hawking /= self.formation.M_sun  # 太陽質量/秒
            else:
                dM_dt_hawking = 0.0
            
            # 総質量変化率
            dM_dt = dM_dt_accretion + dM_dt_hawking
            
            return [dM_dt]
        
        # 初期条件
        y0 = [mass_initial]
        
        # 時間範囲
        t_span = (0, 13.8e9 * 365.25 * 24 * 3600)  # 0 to 13.8 Gyr in seconds
        t_eval = np.linspace(t_span[0], t_span[1], self.config.time_steps)
        
        # 求解
        sol = solve_ivp(
            fun=mass_evolution_rhs,
            t_span=t_span,
            y0=y0,
            t_eval=t_eval,
            method='RK45',
            rtol=1e-8
        )
        
        return sol.t, sol.y[0]
    
    def time_to_redshift(self, t: float) -> float:
        """時間から赤方偏移への変換"""
        # 簡略化された関係式
        t_0 = 13.8e9 * 365.25 * 24 * 3600  # 現在の宇宙年齢（秒）
        
        if t < t_0:
            z = (t_0 / t)**(2/3) - 1
        else:
            z = 0
        
        return max(z, 0)
    
    def calculate_present_abundance(self, mass_initial: float) -> float:
        """現在の存在量の計算"""
        # 質量進化の求解
        t, mass_evolution = self.solve_mass_evolution(mass_initial)
        
        # 現在の質量
        mass_present = mass_evolution[-1]
        
        # 蒸発していない場合
        if mass_present > 0:
            # 初期存在量
            f_initial = self.formation.calculate_initial_abundance(mass_initial)
            
            # 現在の存在量（希釈効果を考慮）
            f_present = f_initial * (mass_present / mass_initial)
            
            return f_present
        else:
            return 0.0
    
    def calculate_merger_rate(self, mass1: float, mass2: float) -> float:
        """PBH合体率の計算"""
        # 二体緩和による合体率
        
        # 数密度
        n_PBH = self.calculate_number_density(mass1)
        
        # 重力波による軌道減衰時間
        t_GW = self.calculate_gw_inspiral_time(mass1, mass2)
        
        # 合体率
        R_merger = n_PBH**2 * self.formation.c**3 / t_GW
        
        return R_merger
    
    def calculate_number_density(self, mass: float) -> float:
        """数密度の計算"""
        # 存在量から数密度を計算
        f_PBH = self.calculate_present_abundance(mass)
        
        # 暗黒物質密度
        rho_dm = self.config.Omega_dm * 3 * self.formation.H_0**2 / (8 * np.pi * self.formation.G)
        
        # 数密度
        n_PBH = f_PBH * rho_dm / (mass * self.formation.M_sun)
        
        return n_PBH
    
    def calculate_gw_inspiral_time(self, mass1: float, mass2: float) -> float:
        """重力波軌道減衰時間の計算"""
        # Peters formula
        
        # 換算質量
        mu = mass1 * mass2 / (mass1 + mass2)
        M_total = mass1 + mass2
        
        # 初期軌道半径（適当な値）
        a_initial = 1e6 * 2 * self.formation.G * M_total * self.formation.M_sun / self.formation.c**2
        
        # 軌道減衰時間
        t_inspiral = (5 * self.formation.c**5 * a_initial**4) / \
                     (256 * self.formation.G**3 * (M_total * self.formation.M_sun)**2 * mu * self.formation.M_sun)
        
        return t_inspiral

class PBHObservations:
    """PBH観測制約"""
    
    def __init__(self, config: PBHConfig):
        self.config = config
        self.formation = PBHFormation(config)
        self.evolution = PBHEvolution(config)
        
        logger.info("PBH observations initialized")
    
    def calculate_microlensing_constraints(self, mass: float) -> float:
        """マイクロレンズ制約の計算"""
        constraint = self.config.observational_constraints['microlensing']
        
        # 質量範囲の確認
        if not (constraint['mass_range'][0] <= mass <= constraint['mass_range'][1]):
            return 1.0  # 制約外
        
        # Einstein半径
        D_L = 8e3  # pc, LMC distance
        D_S = 50e3  # pc, source distance
        
        theta_E = np.sqrt(4 * self.formation.G * mass * self.formation.M_sun * (D_S - D_L) / 
                         (self.formation.c**2 * D_L * D_S))
        
        # 光学的厚さ
        tau = np.pi * theta_E**2 * self.calculate_number_density(mass) * D_L
        
        # 観測されたイベント数との比較
        N_observed = 10  # 典型的な観測数
        N_predicted = tau * 1e6  # 監視星数
        
        if N_predicted > N_observed:
            return N_observed / N_predicted
        else:
            return 1.0
    
    def calculate_cmb_constraints(self, mass: float) -> float:
        """CMB制約の計算"""
        constraint = self.config.observational_constraints['CMB_distortions']
        
        # 質量範囲の確認
        if not (constraint['mass_range'][0] <= mass <= constraint['mass_range'][1]):
            return 1.0
        
        # 蒸発によるエネルギー注入
        if self.config.include_hawking_radiation:
            # 蒸発時間
            t_evap = self.evolution.calculate_evaporation_time(mass)
            
            # 宇宙年齢との比較
            t_universe = 13.8e9 * 365.25 * 24 * 3600  # seconds
            
            if t_evap < t_universe:
                # 蒸発によるエネルギー注入
                energy_injection = mass * self.formation.M_sun * self.formation.c**2
                
                # CMB歪みへの影響
                # 簡略化された計算
                distortion_parameter = energy_injection / (1e50)  # 典型的な値
                
                # 観測制約
                if distortion_parameter > 1e-5:
                    return 1e-5 / distortion_parameter
        
        return 1.0
    
    def calculate_gw_constraints(self, mass1: float, mass2: float) -> float:
        """重力波制約の計算"""
        constraint = self.config.observational_constraints['gravitational_waves']
        
        # 質量範囲の確認
        if not (constraint['mass_range'][0] <= mass1 <= constraint['mass_range'][1]):
            return 1.0
        
        # 合体率の計算
        R_merger = self.evolution.calculate_merger_rate(mass1, mass2)
        
        # 観測される合体率
        R_observed = 10  # Gpc^-3 yr^-1, LIGO/Virgo observations
        
        if R_merger > R_observed:
            return R_observed / R_merger
        else:
            return 1.0
    
    def calculate_gamma_ray_constraints(self, mass: float) -> float:
        """ガンマ線制約の計算"""
        constraint = self.config.observational_constraints['gamma_ray_background']
        
        # 質量範囲の確認
        if not (constraint['mass_range'][0] <= mass <= constraint['mass_range'][1]):
            return 1.0
        
        # Hawking輻射による gamma ray 放出
        if self.config.include_hawking_radiation:
            # 蒸発時間
            t_evap = self.evolution.calculate_evaporation_time(mass)
            t_universe = 13.8e9 * 365.25 * 24 * 3600
            
            if t_evap < t_universe:
                # gamma ray flux
                luminosity = self.evolution.calculate_hawking_luminosity(mass)
                
                # 拡散ガンマ線背景との比較
                background_flux = 1e-6  # MeV cm^-2 s^-1 sr^-1
                
                # 存在量制約
                f_max = background_flux / luminosity
                
                return min(f_max, 1.0)
        
        return 1.0
    
    def calculate_combined_constraints(self, mass: float) -> float:
        """統合制約の計算"""
        # 各制約の計算
        f_microlensing = self.calculate_microlensing_constraints(mass)
        f_cmb = self.calculate_cmb_constraints(mass)
        f_gamma = self.calculate_gamma_ray_constraints(mass)
        
        # 最も厳しい制約
        f_max = min(f_microlensing, f_cmb, f_gamma)
        
        return f_max
    
    def calculate_detection_prospects(self, mass: float) -> Dict[str, float]:
        """検出可能性の計算"""
        prospects = {}
        
        # 重力波検出
        prospects['gravitational_waves'] = self.calculate_gw_detection_probability(mass)
        
        # ガンマ線検出
        prospects['gamma_rays'] = self.calculate_gamma_detection_probability(mass)
        
        # マイクロレンズ検出
        prospects['microlensing'] = self.calculate_microlensing_detection_probability(mass)
        
        # X線検出
        prospects['x_rays'] = self.calculate_xray_detection_probability(mass)
        
        return prospects
    
    def calculate_gw_detection_probability(self, mass: float) -> float:
        """重力波検出確率の計算"""
        # 合体率
        R_merger = self.evolution.calculate_merger_rate(mass, mass)
        
        # 検出可能距離
        D_horizon = 1e3  # Mpc, LIGO/Virgo horizon
        
        # 検出レート
        R_detection = R_merger * (4 * np.pi / 3) * D_horizon**3
        
        # 検出確率
        P_detection = min(R_detection / 100, 1.0)  # 年間100イベント程度
        
        return P_detection
    
    def calculate_gamma_detection_probability(self, mass: float) -> float:
        """ガンマ線検出確率の計算"""
        if not self.config.include_hawking_radiation:
            return 0.0
        
        # 蒸発時間
        t_evap = self.evolution.calculate_evaporation_time(mass)
        t_universe = 13.8e9 * 365.25 * 24 * 3600
        
        if t_evap > t_universe:
            return 0.0
        
        # ガンマ線光度
        L_gamma = self.evolution.calculate_hawking_luminosity(mass)
        
        # 検出閾値
        threshold = 1e-12  # erg s^-1 cm^-2
        
        # 検出可能距離
        D_max = np.sqrt(L_gamma / (4 * np.pi * threshold))
        
        # 検出確率
        P_detection = min(D_max / 1e20, 1.0)  # 適当な正規化
        
        return P_detection
    
    def calculate_microlensing_detection_probability(self, mass: float) -> float:
        """マイクロレンズ検出確率の計算"""
        # 光学的厚さ
        tau = 1e-6  # 典型的な値
        
        # 検出効率
        efficiency = 0.1  # 10%
        
        # 検出確率
        P_detection = tau * efficiency
        
        return min(P_detection, 1.0)
    
    def calculate_xray_detection_probability(self, mass: float) -> float:
        """X線検出確率の計算"""
        # 降着による X線 放出
        accretion_rate = self.formation.calculate_accretion_rate(mass, 0)
        
        # X線光度
        L_x = accretion_rate * self.formation.M_sun * self.formation.c**2 * 0.1  # 10%効率
        
        # 検出閾値
        threshold = 1e-14  # erg s^-1 cm^-2
        
        # 検出可能距離
        D_max = np.sqrt(L_x / (4 * np.pi * threshold))
        
        # 検出確率
        P_detection = min(D_max / 1e18, 1.0)  # 適当な正規化
        
        return P_detection

class PBHResearchSystem:
    """PBH研究システム統合"""
    
    def __init__(self, config: PBHConfig):
        self.config = config
        
        # 各コンポーネントの初期化
        self.formation = PBHFormation(config)
        self.evolution = PBHEvolution(config)
        self.observations = PBHObservations(config)
        
        # 結果保存
        self.results = {}
        
        logger.info("PBH research system initialized")
    
    def run_comprehensive_analysis(self, mass_values: np.ndarray) -> Dict[str, Any]:
        """包括的解析の実行"""
        logger.info("Starting comprehensive PBH analysis...")
        
        results = {
            'formation_mechanisms': {},
            'evolution_history': {},
            'observational_constraints': {},
            'detection_prospects': {},
            'dark_matter_contribution': {}
        }
        
        # 形成機構の解析
        logger.info("Analyzing formation mechanisms...")
        results['formation_mechanisms'] = self.analyze_formation_mechanisms(mass_values)
        
        # 進化史の解析
        logger.info("Analyzing evolution history...")
        results['evolution_history'] = self.analyze_evolution_history(mass_values)
        
        # 観測制約の評価
        logger.info("Evaluating observational constraints...")
        results['observational_constraints'] = self.evaluate_observational_constraints(mass_values)
        
        # 検出可能性の評価
        logger.info("Evaluating detection prospects...")
        results['detection_prospects'] = self.evaluate_detection_prospects(mass_values)
        
        # 暗黒物質寄与の評価
        logger.info("Evaluating dark matter contribution...")
        results['dark_matter_contribution'] = self.evaluate_dark_matter_contribution(mass_values)
        
        self.results = results
        logger.info("Comprehensive analysis completed")
        
        return results
    
    def analyze_formation_mechanisms(self, mass_values: np.ndarray) -> Dict[str, Any]:
        """形成機構の解析"""
        formation_analysis = {
            'mass_functions': [],
            'formation_probabilities': [],
            'horizon_masses': [],
            'initial_abundances': []
        }
        
        for mass in mass_values:
            # 質量関数
            dN_dM = self.formation.calculate_mass_function(mass)
            formation_analysis['mass_functions'].append(dN_dM)
            
            # 形成確率
            beta = self.formation.calculate_formation_probability(0.5)
            formation_analysis['formation_probabilities'].append(beta)
            
            # 初期存在量
            f_initial = self.formation.calculate_initial_abundance(mass)
            formation_analysis['initial_abundances'].append(f_initial)
        
        # 地平線質量の時間進化
        times = np.logspace(1, 17, 100)  # seconds
        horizon_masses = [self.formation.calculate_horizon_mass(t) for t in times]
        formation_analysis['horizon_masses'] = horizon_masses
        formation_analysis['times'] = times
        
        return formation_analysis
    
    def analyze_evolution_history(self, mass_values: np.ndarray) -> Dict[str, Any]:
        """進化史の解析"""
        evolution_analysis = {
            'mass_evolution': {},
            'evaporation_times': [],
            'present_abundances': [],
            'merger_rates': []
        }
        
        # 代表的な質量での詳細進化
        representative_masses = mass_values[::10]  # サンプリング
        
        for mass in representative_masses:
            # 質量進化
            t, mass_evolution = self.evolution.solve_mass_evolution(mass)
            evolution_analysis['mass_evolution'][f'mass_{mass:.2e}'] = {
                'time': t,
                'mass': mass_evolution
            }
            
            # 蒸発時間
            t_evap = self.evolution.calculate_evaporation_time(mass)
            evolution_analysis['evaporation_times'].append(t_evap)
            
            # 現在の存在量
            f_present = self.evolution.calculate_present_abundance(mass)
            evolution_analysis['present_abundances'].append(f_present)
            
            # 合体率
            R_merger = self.evolution.calculate_merger_rate(mass, mass)
            evolution_analysis['merger_rates'].append(R_merger)
        
        return evolution_analysis
    
    def evaluate_observational_constraints(self, mass_values: np.ndarray) -> Dict[str, Any]:
        """観測制約の評価"""
        constraints_analysis = {
            'microlensing_limits': [],
            'cmb_limits': [],
            'gamma_ray_limits': [],
            'combined_limits': []
        }
        
        for mass in mass_values:
            # 各制約の計算
            f_microlensing = self.observations.calculate_microlensing_constraints(mass)
            f_cmb = self.observations.calculate_cmb_constraints(mass)
            f_gamma = self.observations.calculate_gamma_ray_constraints(mass)
            f_combined = self.observations.calculate_combined_constraints(mass)
            
            constraints_analysis['microlensing_limits'].append(f_microlensing)
            constraints_analysis['cmb_limits'].append(f_cmb)
            constraints_analysis['gamma_ray_limits'].append(f_gamma)
            constraints_analysis['combined_limits'].append(f_combined)
        
        return constraints_analysis
    
    def evaluate_detection_prospects(self, mass_values: np.ndarray) -> Dict[str, Any]:
        """検出可能性の評価"""
        detection_analysis = {
            'gravitational_wave_prospects': [],
            'gamma_ray_prospects': [],
            'microlensing_prospects': [],
            'xray_prospects': []
        }
        
        for mass in mass_values:
            # 検出可能性の計算
            prospects = self.observations.calculate_detection_prospects(mass)
            
            detection_analysis['gravitational_wave_prospects'].append(prospects['gravitational_waves'])
            detection_analysis['gamma_ray_prospects'].append(prospects['gamma_rays'])
            detection_analysis['microlensing_prospects'].append(prospects['microlensing'])
            detection_analysis['xray_prospects'].append(prospects['x_rays'])
        
        return detection_analysis
    
    def evaluate_dark_matter_contribution(self, mass_values: np.ndarray) -> Dict[str, Any]:
        """暗黒物質寄与の評価"""
        dm_analysis = {
            'dm_fractions': [],
            'allowed_parameter_space': [],
            'total_contribution': 0.0
        }
        
        total_contribution = 0.0
        
        for mass in mass_values:
            # 現在の存在量
            f_present = self.evolution.calculate_present_abundance(mass)
            
            # 観測制約
            f_max = self.observations.calculate_combined_constraints(mass)
            
            # 許容される存在量
            f_allowed = min(f_present, f_max)
            
            dm_analysis['dm_fractions'].append(f_allowed)
            dm_analysis['allowed_parameter_space'].append(f_allowed > 0.001)
            
            total_contribution += f_allowed
        
        dm_analysis['total_contribution'] = total_contribution
        
        return dm_analysis
    
    def save_results(self, filename: str = 'pbh_analysis_results.h5'):
        """結果の保存"""
        with h5py.File(filename, 'w') as f:
            # 設定の保存
            config_group = f.create_group('config')
            config_group.attrs['mass_range'] = self.config.mass_range
            config_group.attrs['characteristic_mass'] = self.config.characteristic_mass
            
            # 結果の保存
            for key, value in self.results.items():
                if isinstance(value, dict):
                    group = f.create_group(key)
                    for subkey, subvalue in value.items():
                        if isinstance(subvalue, (list, np.ndarray)):
                            if len(subvalue) > 0:
                                if isinstance(subvalue[0], (int, float)):
                                    group.create_dataset(subkey, data=subvalue)
                                elif isinstance(subvalue[0], dict):
                                    # 辞書のリストの場合
                                    subgroup = group.create_group(subkey)
                                    for i, item in enumerate(subvalue):
                                        if isinstance(item, dict):
                                            item_group = subgroup.create_group(f'item_{i}')
                                            for k, v in item.items():
                                                if isinstance(v, (int, float, np.ndarray)):
                                                    item_group.create_dataset(k, data=v)
                        else:
                            group.attrs[subkey] = subvalue
        
        logger.info(f"Results saved to {filename}")
    
    def generate_summary_report(self) -> str:
        """要約レポートの生成"""
        if not self.results:
            return "No results available"
        
        report = []
        report.append("=== Primordial Black Hole Research Summary ===\n")
        
        # 形成機構
        formation = self.results.get('formation_mechanisms', {})
        if formation:
            report.append("Formation Mechanisms:")
            report.append(f"  Formation mechanism: {self.config.formation_mechanism}")
            report.append(f"  Characteristic mass: {self.config.characteristic_mass:.2e} M☉")
            report.append(f"  Mass function: {self.config.mass_function_type}")
            report.append("")
        
        # 進化史
        evolution = self.results.get('evolution_history', {})
        if evolution:
            report.append("Evolution History:")
            if 'present_abundances' in evolution:
                abundances = evolution['present_abundances']
                if abundances:
                    max_abundance = max(abundances)
                    report.append(f"  Maximum present abundance: {max_abundance:.2e}")
            report.append("")
        
        # 観測制約
        constraints = self.results.get('observational_constraints', {})
        if constraints:
            report.append("Observational Constraints:")
            if 'combined_limits' in constraints:
                limits = constraints['combined_limits']
                if limits:
                    strongest_limit = min(limits)
                    report.append(f"  Strongest constraint: f_PBH < {strongest_limit:.2e}")
            report.append("")
        
        # 暗黒物質寄与
        dm_contribution = self.results.get('dark_matter_contribution', {})
        if dm_contribution:
            report.append("Dark Matter Contribution:")
            total = dm_contribution.get('total_contribution', 0)
            report.append(f"  Total allowed contribution: {total:.3f}")
            report.append("")
        
        return "\n".join(report)

def main():
    """メイン実行関数"""
    # 設定
    config = PBHConfig()
    
    # 研究システムの初期化
    pbh_system = PBHResearchSystem(config)
    
    # 質量範囲
    mass_values = np.logspace(np.log10(config.mass_range[0]), np.log10(config.mass_range[1]), 50)
    
    # 包括的解析の実行
    results = pbh_system.run_comprehensive_analysis(mass_values)
    
    # 結果の保存
    pbh_system.save_results()
    
    # レポートの生成
    report = pbh_system.generate_summary_report()
    print(report)
    
    # 結果の可視化
    visualize_pbh_results(results, mass_values)

def visualize_pbh_results(results: Dict[str, Any], mass_values: np.ndarray):
    """結果の可視化"""
    fig, axes = plt.subplots(2, 2, figsize=(15, 12))
    
    # 質量関数
    if 'formation_mechanisms' in results:
        formation = results['formation_mechanisms']
        if 'mass_functions' in formation:
            ax = axes[0, 0]
            ax.loglog(mass_values, formation['mass_functions'], 'b-', linewidth=2, label='Mass Function')
            ax.set_xlabel('Mass [M☉]')
            ax.set_ylabel('dN/dM')
            ax.set_title('PBH Mass Function')
            ax.grid(True, alpha=0.3)
            ax.legend()
    
    # 観測制約
    if 'observational_constraints' in results:
        constraints = results['observational_constraints']
        ax = axes[0, 1]
        
        if 'microlensing_limits' in constraints:
            ax.loglog(mass_values, constraints['microlensing_limits'], 'r-', linewidth=2, label='Microlensing')
        if 'cmb_limits' in constraints:
            ax.loglog(mass_values, constraints['cmb_limits'], 'g-', linewidth=2, label='CMB')
        if 'gamma_ray_limits' in constraints:
            ax.loglog(mass_values, constraints['gamma_ray_limits'], 'purple', linewidth=2, label='Gamma-ray')
        if 'combined_limits' in constraints:
            ax.loglog(mass_values, constraints['combined_limits'], 'k--', linewidth=3, label='Combined')
        
        ax.set_xlabel('Mass [M☉]')
        ax.set_ylabel('Maximum f_PBH')
        ax.set_title('Observational Constraints')
        ax.legend()
        ax.grid(True, alpha=0.3)
    
    # 検出可能性
    if 'detection_prospects' in results:
        detection = results['detection_prospects']
        ax = axes[1, 0]
        
        if 'gravitational_wave_prospects' in detection:
            ax.semilogx(mass_values, detection['gravitational_wave_prospects'], 'b-', linewidth=2, label='Gravitational Waves')
        if 'gamma_ray_prospects' in detection:
            ax.semilogx(mass_values, detection['gamma_ray_prospects'], 'r-', linewidth=2, label='Gamma Rays')
        if 'microlensing_prospects' in detection:
            ax.semilogx(mass_values, detection['microlensing_prospects'], 'g-', linewidth=2, label='Microlensing')
        
        ax.set_xlabel('Mass [M☉]')
        ax.set_ylabel('Detection Probability')
        ax.set_title('Detection Prospects')
        ax.legend()
        ax.grid(True, alpha=0.3)
    
    # 暗黒物質寄与
    if 'dark_matter_contribution' in results:
        dm = results['dark_matter_contribution']
        if 'dm_fractions' in dm:
            ax = axes[1, 1]
            ax.loglog(mass_values, dm['dm_fractions'], 'orange', linewidth=2, label='DM Fraction')
            ax.set_xlabel('Mass [M☉]')
            ax.set_ylabel('f_PBH')
            ax.set_title('Dark Matter Contribution')
            ax.legend()
            ax.grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig('pbh_analysis_results.png', dpi=300, bbox_inches='tight')
    plt.show()

if __name__ == "__main__":
    main() 