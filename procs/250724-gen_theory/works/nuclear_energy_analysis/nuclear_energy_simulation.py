#!/usr/bin/env python3
"""
核融合・核分裂エネルギー生成シミュレーション
家庭スケールでの実現困難性の理論分析

Author: 川崎淳一
Created: 2025-01-27
"""

import numpy as np
import matplotlib.pyplot as plt
import matplotlib.patches as patches
from matplotlib.animation import FuncAnimation
import pandas as pd
from scipy import constants

# 日本語フォント設定
plt.rcParams['font.family'] = ['DejaVu Sans', 'Hiragino Sans', 'Yu Gothic', 'Meiryo', 'Takao', 'IPAexGothic', 'IPAPGothic', 'VL PGothic', 'Noto Sans CJK JP']

class NuclearEnergySimulator:
    """核エネルギーシミュレータークラス"""
    
    def __init__(self):
        """定数の初期化"""
        self.c = constants.c  # 光速 [m/s]
        self.eV_to_J = constants.eV  # eV to J 変換
        self.u_to_kg = constants.atomic_mass  # 原子質量単位 to kg
        self.N_A = constants.Avogadro  # アボガドロ数
        self.k_B = constants.Boltzmann  # ボルツマン定数
        
        # 核データ
        self.nuclear_data = {
            'deuteron': {'mass': 2.01410178, 'binding_energy': 2.2246},  # amu, MeV
            'tritium': {'mass': 3.0160493, 'binding_energy': 8.4818},
            'helium4': {'mass': 4.0026032, 'binding_energy': 28.2957},
            'neutron': {'mass': 1.00866492, 'binding_energy': 0},
            'uranium235': {'mass': 235.0439299, 'binding_energy': 1783.871},
            'krypton92': {'mass': 91.926156, 'binding_energy': 783.13},
            'barium141': {'mass': 140.914411, 'binding_energy': 1172.84}
        }
        
        # 家庭用エネルギー需要データ
        self.household_energy = {
            'daily_consumption': 30,  # kWh/day
            'monthly_consumption': 900,  # kWh/month
            'annual_consumption': 10800  # kWh/year
        }

    def calculate_fusion_energy(self, reaction_type='D-T'):
        """
        核融合反応のエネルギー計算
        
        Args:
            reaction_type (str): 反応タイプ ('D-T', 'D-D')
        
        Returns:
            dict: エネルギー計算結果
        """
        if reaction_type == 'D-T':
            # D + T → α + n + 17.59 MeV
            reactants_mass = (self.nuclear_data['deuteron']['mass'] + 
                            self.nuclear_data['tritium']['mass'])
            products_mass = (self.nuclear_data['helium4']['mass'] + 
                           self.nuclear_data['neutron']['mass'])
            
        elif reaction_type == 'D-D':
            # D + D → T + p + 4.03 MeV (branch 1)
            # D + D → ³He + n + 3.27 MeV (branch 2)
            reactants_mass = 2 * self.nuclear_data['deuteron']['mass']
            products_mass = (self.nuclear_data['tritium']['mass'] + 
                           1.007276)  # プロトン質量
        
        # 質量欠損とエネルギー解放
        mass_defect = reactants_mass - products_mass  # amu
        energy_released = mass_defect * 931.494  # MeV (1 amu = 931.494 MeV)
        
        # 1グラムあたりのエネルギー
        energy_per_gram = energy_released * self.N_A / reactants_mass  # MeV/g
        energy_per_gram_J = energy_per_gram * 1e6 * self.eV_to_J  # J/g
        
        return {
            'reaction': reaction_type,
            'mass_defect_amu': mass_defect,
            'energy_released_MeV': energy_released,
            'energy_per_gram_MJ': energy_per_gram_J / 1e6,
            'energy_per_gram_kWh': energy_per_gram_J / 3.6e6
        }

    def calculate_fission_energy(self):
        """
        核分裂反応のエネルギー計算 (U-235)
        
        Returns:
            dict: エネルギー計算結果
        """
        # U-235 + n → Kr-92 + Ba-141 + 3n + ~200 MeV
        reactants_mass = (self.nuclear_data['uranium235']['mass'] + 
                         self.nuclear_data['neutron']['mass'])
        products_mass = (self.nuclear_data['krypton92']['mass'] + 
                        self.nuclear_data['barium141']['mass'] + 
                        3 * self.nuclear_data['neutron']['mass'])
        
        # 質量欠損とエネルギー解放
        mass_defect = reactants_mass - products_mass  # amu
        energy_released = mass_defect * 931.494  # MeV
        
        # 1グラムあたりのエネルギー
        energy_per_gram = energy_released * self.N_A / reactants_mass  # MeV/g
        energy_per_gram_J = energy_per_gram * 1e6 * self.eV_to_J  # J/g
        
        return {
            'reaction': 'U-235 fission',
            'mass_defect_amu': mass_defect,
            'energy_released_MeV': energy_released,
            'energy_per_gram_MJ': energy_per_gram_J / 1e6,
            'energy_per_gram_kWh': energy_per_gram_J / 3.6e6
        }

    def calculate_household_implications(self, energy_density):
        """
        家庭スケールでのエネルギー需要と核エネルギーの比較
        
        Args:
            energy_density (float): エネルギー密度 [kWh/g]
        
        Returns:
            dict: 家庭用エネルギー換算結果
        """
        daily_fuel_mass = self.household_energy['daily_consumption'] / energy_density  # g
        monthly_fuel_mass = self.household_energy['monthly_consumption'] / energy_density  # g
        annual_fuel_mass = self.household_energy['annual_consumption'] / energy_density  # g
        
        return {
            'daily_fuel_needed_g': daily_fuel_mass,
            'monthly_fuel_needed_g': monthly_fuel_mass,
            'annual_fuel_needed_g': annual_fuel_mass,
            'daily_fuel_needed_mg': daily_fuel_mass * 1000,
            'comparison_with_coal': energy_density / 8.0  # 石炭の約8kWh/kgと比較
        }

    def calculate_confinement_requirements(self):
        """
        核融合の閉じ込め要件計算 (ローソン条件)
        
        Returns:
            dict: 閉じ込め要件
        """
        # D-T融合の臨界条件
        lawson_criterion = 1.5e20  # n*τ [m^-3*s] @ T=10keV
        ignition_temperature = 10  # keV ≈ 100 million K
        ignition_temperature_K = ignition_temperature * 1e3 * self.eV_to_J / self.k_B
        
        # 必要な圧力 (理想気体近似)
        density_needed = 1e20  # m^-3
        pressure_needed = density_needed * self.k_B * ignition_temperature_K  # Pa
        pressure_atm = pressure_needed / 101325  # atm
        
        # 磁場要件 (トカマク型)
        magnetic_field = 5.0  # Tesla
        magnetic_pressure = magnetic_field**2 / (2 * constants.mu_0)  # Pa
        
        return {
            'ignition_temperature_K': ignition_temperature_K,
            'ignition_temperature_million_K': ignition_temperature_K / 1e6,
            'lawson_criterion': lawson_criterion,
            'plasma_density_m3': density_needed,
            'plasma_pressure_Pa': pressure_needed,
            'plasma_pressure_atm': pressure_atm,
            'magnetic_field_T': magnetic_field,
            'magnetic_pressure_Pa': magnetic_pressure
        }

    def create_energy_comparison_plot(self):
        """エネルギー密度比較プロット"""
        # エネルギー計算
        fusion_dt = self.calculate_fusion_energy('D-T')
        fusion_dd = self.calculate_fusion_energy('D-D')
        fission = self.calculate_fission_energy()
        
        # 比較データ
        energy_sources = {
            'D-T核融合': fusion_dt['energy_per_gram_kWh'],
            'D-D核融合': fusion_dd['energy_per_gram_kWh'],
            'U-235核分裂': fission['energy_per_gram_kWh'],
            '石炭': 8.0,
            '石油': 12.0,
            '天然ガス': 15.0,
            'リチウム電池': 0.15,
            'ガソリン': 12.2
        }
        
        fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 8))
        
        # 線形スケール
        sources = list(energy_sources.keys())
        values = list(energy_sources.values())
        colors = ['red', 'orange', 'purple', 'brown', 'black', 'blue', 'green', 'gray']
        
        bars1 = ax1.bar(sources, values, color=colors, alpha=0.7)
        ax1.set_ylabel('エネルギー密度 [kWh/g]', fontsize=12)
        ax1.set_title('エネルギー源別エネルギー密度比較', fontsize=14, fontweight='bold')
        ax1.tick_params(axis='x', rotation=45)
        
        # 対数スケール
        ax2.bar(sources, values, color=colors, alpha=0.7)
        ax2.set_yscale('log')
        ax2.set_ylabel('エネルギー密度 [kWh/g] (対数)', fontsize=12)
        ax2.set_title('エネルギー源別エネルギー密度比較 (対数スケール)', fontsize=14, fontweight='bold')
        ax2.tick_params(axis='x', rotation=45)
        
        # 値をバーの上に表示
        for i, (bar, value) in enumerate(zip(bars1, values)):
            if value > 1000:
                ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(values)*0.01,
                        f'{value:.0f}', ha='center', va='bottom', fontweight='bold')
            else:
                ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(values)*0.01,
                        f'{value:.1f}', ha='center', va='bottom', fontweight='bold')
        
        plt.tight_layout()
        plt.savefig('nuclear_energy_density_comparison.png', dpi=300, bbox_inches='tight')
        plt.show()
        
        return energy_sources

    def create_household_energy_plot(self):
        """家庭用エネルギー需要と核燃料必要量の可視化"""
        fusion_dt = self.calculate_fusion_energy('D-T')
        fission = self.calculate_fission_energy()
        
        household_fusion = self.calculate_household_implications(fusion_dt['energy_per_gram_kWh'])
        household_fission = self.calculate_household_implications(fission['energy_per_gram_kWh'])
        
        # 比較用（石炭）
        coal_energy_density = 8.0 / 1000  # kWh/g
        household_coal = self.calculate_household_implications(coal_energy_density)
        
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(16, 12))
        
        # 1. 日次燃料必要量
        fuels = ['D-T核融合', 'U-235核分裂', '石炭']
        daily_amounts = [
            household_fusion['daily_fuel_needed_mg'],
            household_fission['daily_fuel_needed_mg'],
            household_coal['daily_fuel_needed_g']
        ]
        
        bars1 = ax1.bar(fuels, daily_amounts, color=['red', 'purple', 'brown'], alpha=0.7)
        ax1.set_ylabel('必要燃料量 [mg/日]', fontsize=12)
        ax1.set_title('家庭の日次エネルギー需要を満たす燃料量', fontsize=14, fontweight='bold')
        ax1.set_yscale('log')
        
        for bar, amount in zip(bars1, daily_amounts):
            if amount < 1:
                ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() * 1.1,
                        f'{amount:.2f}mg', ha='center', va='bottom', fontweight='bold')
            else:
                ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() * 1.1,
                        f'{amount:.0f}mg', ha='center', va='bottom', fontweight='bold')
        
        # 2. 年間燃料必要量
        annual_amounts = [
            household_fusion['annual_fuel_needed_g'],
            household_fission['annual_fuel_needed_g'],
            household_coal['annual_fuel_needed_g'] / 1000  # kg換算
        ]
        
        ax2.bar(fuels, annual_amounts, color=['red', 'purple', 'brown'], alpha=0.7)
        ax2.set_ylabel('必要燃料量 [g/年] (石炭はkg)', fontsize=12)
        ax2.set_title('家庭の年間エネルギー需要を満たす燃料量', fontsize=14, fontweight='bold')
        ax2.set_yscale('log')
        
        # 3. 核融合の困難性要因
        requirements = self.calculate_confinement_requirements()
        
        categories = ['温度\n(百万K)', '密度\n(×10²⁰/m³)', '圧力\n(大気圧倍)', '磁場\n(テスラ)']
        values = [
            requirements['ignition_temperature_million_K'],
            requirements['plasma_density_m3'] / 1e20,
            requirements['plasma_pressure_atm'],
            requirements['magnetic_field_T']
        ]
        
        bars3 = ax3.bar(categories, values, color=['orange', 'blue', 'green', 'purple'], alpha=0.7)
        ax3.set_ylabel('要求値', fontsize=12)
        ax3.set_title('核融合実現に必要な極限条件', fontsize=14, fontweight='bold')
        ax3.set_yscale('log')
        
        for bar, value in zip(bars3, values):
            ax3.text(bar.get_x() + bar.get_width()/2, bar.get_height() * 1.1,
                    f'{value:.1f}', ha='center', va='bottom', fontweight='bold')
        
        # 4. エネルギー効率比較
        efficiency_data = {
            '核融合発電所': 0.4,  # 想定効率
            '核分裂発電所': 0.33,
            '石炭火力': 0.38,
            '家庭用太陽光': 0.20,
            '風力発電': 0.35
        }
        
        ax4.bar(efficiency_data.keys(), efficiency_data.values(), 
               color=['red', 'purple', 'brown', 'yellow', 'cyan'], alpha=0.7)
        ax4.set_ylabel('エネルギー変換効率', fontsize=12)
        ax4.set_title('発電方式別エネルギー変換効率', fontsize=14, fontweight='bold')
        ax4.set_ylim(0, 0.5)
        
        for i, (source, eff) in enumerate(efficiency_data.items()):
            ax4.text(i, eff + 0.01, f'{eff:.0%}', ha='center', va='bottom', fontweight='bold')
        
        plt.tight_layout()
        plt.savefig('household_nuclear_energy_analysis.png', dpi=300, bbox_inches='tight')
        plt.show()
        
        return {
            'fusion_household': household_fusion,
            'fission_household': household_fission,
            'coal_household': household_coal,
            'requirements': requirements
        }

    def create_nuclear_physics_schematic(self):
        """核物理プロセスの概念図"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(16, 12))
        
        # 1. 核融合反応図
        ax1.set_xlim(0, 10)
        ax1.set_ylim(0, 6)
        
        # 重水素とトリチウム
        d_circle = patches.Circle((2, 3), 0.5, color='blue', alpha=0.7, label='重水素(D)')
        t_circle = patches.Circle((4, 3), 0.6, color='red', alpha=0.7, label='トリチウム(T)')
        ax1.add_patch(d_circle)
        ax1.add_patch(t_circle)
        
        # 矢印
        ax1.arrow(5, 3, 1, 0, head_width=0.2, head_length=0.3, fc='black', ec='black')
        
        # 生成物
        he_circle = patches.Circle((7.5, 3.5), 0.7, color='green', alpha=0.7, label='ヘリウム4(α)')
        n_circle = patches.Circle((7.5, 2.5), 0.3, color='gray', alpha=0.7, label='中性子')
        ax1.add_patch(he_circle)
        ax1.add_patch(n_circle)
        
        ax1.text(2, 2, 'D', ha='center', va='center', fontsize=14, fontweight='bold')
        ax1.text(4, 2, 'T', ha='center', va='center', fontsize=14, fontweight='bold')
        ax1.text(7.5, 4.5, 'α', ha='center', va='center', fontsize=14, fontweight='bold')
        ax1.text(7.5, 1.8, 'n', ha='center', va='center', fontsize=14, fontweight='bold')
        ax1.text(5, 4, '+ 17.59 MeV', ha='center', va='center', fontsize=12, fontweight='bold', color='red')
        
        ax1.set_title('核融合反応 (D-T)', fontsize=14, fontweight='bold')
        ax1.set_xticks([])
        ax1.set_yticks([])
        
        # 2. 核分裂反応図
        ax2.set_xlim(0, 10)
        ax2.set_ylim(0, 6)
        
        # ウラン235
        u_circle = patches.Circle((2, 3), 1.0, color='yellow', alpha=0.7)
        ax2.add_patch(u_circle)
        ax2.text(2, 3, 'U-235', ha='center', va='center', fontsize=12, fontweight='bold')
        
        # 中性子
        n_small = patches.Circle((0.5, 3), 0.2, color='gray', alpha=0.7)
        ax2.add_patch(n_small)
        ax2.arrow(1, 3, 0.5, 0, head_width=0.1, head_length=0.2, fc='black', ec='black')
        
        # 分裂後
        ax2.arrow(3.5, 3, 1, 0, head_width=0.2, head_length=0.3, fc='black', ec='black')
        
        # 分裂生成物
        kr_circle = patches.Circle((6.5, 4), 0.6, color='orange', alpha=0.7)
        ba_circle = patches.Circle((7.5, 2), 0.7, color='purple', alpha=0.7)
        ax2.add_patch(kr_circle)
        ax2.add_patch(ba_circle)
        
        # 中性子放出
        for i, (x, y) in enumerate([(8.5, 3), (8.8, 3.5), (8.2, 2.5)]):
            n_out = patches.Circle((x, y), 0.15, color='gray', alpha=0.7)
            ax2.add_patch(n_out)
        
        ax2.text(6.5, 4, 'Kr-92', ha='center', va='center', fontsize=10, fontweight='bold')
        ax2.text(7.5, 2, 'Ba-141', ha='center', va='center', fontsize=10, fontweight='bold')
        ax2.text(5, 4.5, '+ ~200 MeV', ha='center', va='center', fontsize=12, fontweight='bold', color='red')
        
        ax2.set_title('核分裂反応 (U-235)', fontsize=14, fontweight='bold')
        ax2.set_xticks([])
        ax2.set_yticks([])
        
        # 3. 結合エネルギー曲線
        mass_numbers = np.arange(1, 250)
        binding_energy_per_nucleon = np.zeros_like(mass_numbers, dtype=float)
        
        # 簡略化した結合エネルギー式（実験値に基づく近似）
        for i, A in enumerate(mass_numbers):
            if A == 1:
                binding_energy_per_nucleon[i] = 0
            elif A <= 4:
                binding_energy_per_nucleon[i] = A * 2  # 軽核
            elif A <= 60:
                # 鉄-56付近でピーク
                binding_energy_per_nucleon[i] = 8.8 - (A - 56)**2 / 1000
            else:
                # 重い核では減少
                binding_energy_per_nucleon[i] = 8.8 - (A - 56) / 30
        
        ax3.plot(mass_numbers, binding_energy_per_nucleon, 'b-', linewidth=2)
        ax3.axvline(x=4, color='red', linestyle='--', alpha=0.7, label='D-T融合領域')
        ax3.axvline(x=235, color='purple', linestyle='--', alpha=0.7, label='U-235分裂領域')
        ax3.axvline(x=56, color='green', linestyle='-', alpha=0.7, label='鉄-56 (最安定)')
        
        ax3.set_xlabel('質量数 A', fontsize=12)
        ax3.set_ylabel('核子あたり結合エネルギー [MeV]', fontsize=12)
        ax3.set_title('原子核の結合エネルギー曲線', fontsize=14, fontweight='bold')
        ax3.grid(True, alpha=0.3)
        ax3.legend()
        
        # 4. 家庭実現困難性の要因
        factors = ['超高温\n(1億度)', '極高密度\n(大気の10²⁰倍)', '強磁場\n(5テスラ)', '真空容器\n(10⁻¹⁰torr)', '放射線\n遮蔽']
        difficulty_scores = [10, 9, 8, 7, 8]  # 困難度スコア
        colors_diff = ['red', 'orange', 'yellow', 'green', 'blue']
        
        bars4 = ax4.bar(factors, difficulty_scores, color=colors_diff, alpha=0.7)
        ax4.set_ylabel('実現困難度スコア', fontsize=12)
        ax4.set_title('家庭用核融合炉の実現困難要因', fontsize=14, fontweight='bold')
        ax4.set_ylim(0, 10)
        
        for bar, score in zip(bars4, difficulty_scores):
            ax4.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.1,
                    f'{score}', ha='center', va='bottom', fontsize=12, fontweight='bold')
        
        plt.tight_layout()
        plt.savefig('nuclear_physics_schematic.png', dpi=300, bbox_inches='tight')
        plt.show()

    def generate_comprehensive_report(self):
        """包括的な分析レポートを生成"""
        print("="*80)
        print("核融合・核分裂エネルギー生成メカニズムと家庭実現困難性分析")
        print("="*80)
        
        # エネルギー計算
        fusion_dt = self.calculate_fusion_energy('D-T')
        fusion_dd = self.calculate_fusion_energy('D-D')
        fission = self.calculate_fission_energy()
        
        print("\n【1. エネルギー生成メカニズム】")
        print(f"D-T核融合: {fusion_dt['energy_released_MeV']:.2f} MeV/反応")
        print(f"  → エネルギー密度: {fusion_dt['energy_per_gram_kWh']:.0f} kWh/g")
        print(f"U-235核分裂: {fission['energy_released_MeV']:.1f} MeV/反応")
        print(f"  → エネルギー密度: {fission['energy_per_gram_kWh']:.0f} kWh/g")
        
        # 家庭用エネルギー換算
        household_fusion = self.calculate_household_implications(fusion_dt['energy_per_gram_kWh'])
        household_fission = self.calculate_household_implications(fission['energy_per_gram_kWh'])
        
        print(f"\n【2. 家庭用エネルギー需要との比較】")
        print(f"日次消費量: {self.household_energy['daily_consumption']} kWh")
        print(f"核融合燃料必要量: {household_fusion['daily_fuel_needed_mg']:.3f} mg/日")
        print(f"核分裂燃料必要量: {household_fission['daily_fuel_needed_mg']:.3f} mg/日")
        print(f"石炭との比較 (核融合): {household_fusion['comparison_with_coal']:.0f}倍効率的")
        
        # 実現困難性
        requirements = self.calculate_confinement_requirements()
        
        print(f"\n【3. 核融合実現の技術的障壁】")
        print(f"点火温度: {requirements['ignition_temperature_million_K']:.0f} 百万K")
        print(f"プラズマ密度: {requirements['plasma_density_m3']:.0e} /m³")
        print(f"プラズマ圧力: {requirements['plasma_pressure_atm']:.0e} 気圧")
        print(f"磁場強度: {requirements['magnetic_field_T']:.1f} テスラ")
        
        print(f"\n【4. 家庭実現が困難な理由】")
        print("• 超高温: 太陽中心温度(1500万K)の約7倍が必要")
        print("• 極限密度: 大気密度の10²⁰倍の密度が必要") 
        print("• 強磁場: MRIの100倍の磁場で粒子を閉じ込め")
        print("• 超高真空: 宇宙空間以上の真空度が必要")
        print("• 放射線遮蔽: 中性子による強い放射線")
        print("• 膨大な初期投資: 数千億円規模の設備投資")
        
        print(f"\n【5. 結論】")
        print("核融合・核分裂は理論的には小量で膨大エネルギーを発生可能だが、")
        print("実現には極限的な物理条件が必要で、家庭規模での実現は現実的ではない。")
        print("エネルギー密度の高さが、逆に制御の困難さを意味している。")

def main():
    """メイン実行関数"""
    simulator = NuclearEnergySimulator()
    
    # 分析の実行
    print("核エネルギーシミュレーション開始...")
    
    # レポート生成
    simulator.generate_comprehensive_report()
    
    # 可視化
    print("\n可視化を生成中...")
    simulator.create_energy_comparison_plot()
    simulator.create_household_energy_plot()
    simulator.create_nuclear_physics_schematic()
    
    print("\n分析完了! 生成されたファイル:")
    print("- nuclear_energy_density_comparison.png")
    print("- household_nuclear_energy_analysis.png") 
    print("- nuclear_physics_schematic.png")

if __name__ == "__main__":
    main() 