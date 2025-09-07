"""
LIGO/Virgo用情報修正重力波形テンプレート (GIPF Waveform Implementation)

情報物理学フレームワーク(GIPF)による重力波の位相・振幅修正を実装。
LIGO O4/O5、Virgo O4での検出可能性を評価。

理論的基盤:
- 情報密度による時空修正: h_info = h_GR × [1 + β_info (f/f_info)^(1/3)]
- 位相修正: Φ_info = 1.5 β_info (f/f_info)^(1/3)
- 検出予測: 10%歪み振幅増大 (f > 10⁻³ Hz)

著者: Jun Kawasaki
日付: 2025-01-27
ライセンス: MIT
"""

import numpy as np
import matplotlib.pyplot as plt
from scipy.integrate import quad, cumtrapz
from scipy.interpolate import interp1d
from scipy.optimize import minimize
import h5py
import warnings
warnings.filterwarnings('ignore')

class GIPFWaveformGenerator:
    """
    GIPF情報修正重力波形生成クラス
    
    機能:
    - 標準GR波形の生成
    - 情報修正の適用（位相・振幅）
    - LIGO/Virgoノイズカーブでの検出可能性評価
    - LALSuite/PyCBC互換インターフェース
    """
    
    def __init__(self, detector='LIGO_O4'):
        """
        初期化
        
        Parameters:
        -----------
        detector : str
            'LIGO_O4', 'LIGO_O5', 'Virgo_O4', 'LIGO_Virgo_network'
        """
        self.detector = detector
        
        # GIPF理論パラメータ（最適化済み）
        self.beta_info = 0.01      # 情報結合係数
        self.f_info = 1e-3         # 情報特性周波数 [Hz]
        self.alpha_amplitude = 0.1 # 振幅修正係数
        
        # 物理定数
        self.c = 2.998e8           # 光速 [m/s]
        self.G = 6.674e-11         # 重力定数 [m³/kg/s²]
        self.M_sun = 1.989e30      # 太陽質量 [kg]
        
        # 検出器仕様
        self.detector_specs = self._initialize_detector_specs()
        
        print(f"🌊 GIPF重力波形生成システム初期化完了")
        print(f"検出器: {detector}")
        print(f"情報パラメータ: β_info = {self.beta_info}, f_info = {self.f_info} Hz")
    
    def _initialize_detector_specs(self):
        """検出器仕様の初期化"""
        specs = {
            'LIGO_O4': {
                'arm_length': 4000,      # m
                'f_min': 10,             # Hz
                'f_max': 8000,           # Hz
                'sensitivity_curve': 'design_O4'
            },
            'LIGO_O5': {
                'arm_length': 4000,      # m
                'f_min': 5,              # Hz
                'f_max': 8000,           # Hz
                'sensitivity_curve': 'A+'
            },
            'Virgo_O4': {
                'arm_length': 3000,      # m
                'f_min': 10,             # Hz
                'f_max': 6000,           # Hz
                'sensitivity_curve': 'design_O4'
            }
        }
        return specs[self.detector]
    
    def gipf_phase_correction(self, f):
        """
        GIPF位相修正の計算
        
        Φ_info(f) = 1.5 × β_info × (f/f_info)^(1/3)
        
        Parameters:
        -----------
        f : array_like
            周波数 [Hz]
        
        Returns:
        --------
        phase_correction : array_like
            位相修正 [rad]
        """
        return 1.5 * self.beta_info * np.power(f / self.f_info, 1.0/3.0)
    
    def gipf_amplitude_correction(self, f):
        """
        GIPF振幅修正の計算
        
        A_info(f) = 1 + α_amplitude × β_info × (f/f_info)^(2/3)
        
        Parameters:
        -----------
        f : array_like
            周波数 [Hz]
        
        Returns:
        --------
        amplitude_correction : array_like
            振幅修正係数
        """
        return 1 + self.alpha_amplitude * self.beta_info * np.power(f / self.f_info, 2.0/3.0)
    
    def standard_inspiral_waveform(self, f, M1, M2, distance, inclination=0):
        """
        標準GRインスパイラル波形の生成
        
        Parameters:
        -----------
        f : array_like
            周波数配列 [Hz]
        M1, M2 : float
            バイナリー質量 [M_sun]
        distance : float
            光度距離 [Mpc]
        inclination : float
            傾斜角 [rad]
        
        Returns:
        --------
        h_plus, h_cross : array_like
            + および × 偏波成分
        """
        # 総質量とチャープ質量
        M_total = M1 + M2
        M_chirp = (M1 * M2)**(3/5) / (M1 + M2)**(1/5)
        
        # 対称質量比
        eta = M1 * M2 / (M1 + M2)**2
        
        # 距離の換算 [m]
        d_m = distance * 3.086e22  # Mpc to m
        
        # 周波数進化 (Post-Newtonian近似)
        v = np.power(np.pi * self.G * M_total * self.M_sun * f / self.c**3, 1.0/3.0)
        
        # 位相進化
        Psi = 2 * np.pi * f * self._time_to_merger(f, M_chirp) - np.pi/4
        
        # 振幅
        A = np.sqrt(5/24) / np.pi**(2/3) * (self.G * M_chirp * self.M_sun / self.c**2)**(5/6) / d_m * np.power(self.G * M_total * self.M_sun * np.pi * f / self.c**3, -7/6)
        
        # 偏波成分
        h_plus = A * (1 + np.cos(inclination)**2) * np.cos(Psi)
        h_cross = A * 2 * np.cos(inclination) * np.sin(Psi)
        
        return h_plus, h_cross
    
    def _time_to_merger(self, f, M_chirp):
        """
        合体までの時間計算 (Post-Newtonian)
        """
        return 5 / (256 * np.pi**(8/3)) * (self.G * M_chirp * self.M_sun / self.c**3)**(-5/3) * (2 * np.pi * f)**(-8/3)
    
    def gipf_modified_waveform(self, f, M1, M2, distance, inclination=0):
        """
        GIPF修正波形の生成
        
        Parameters:
        -----------
        f : array_like
            周波数配列 [Hz]
        M1, M2 : float
            バイナリー質量 [M_sun]
        distance : float
            光度距離 [Mpc]
        inclination : float
            傾斜角 [rad]
        
        Returns:
        --------
        h_plus_gipf, h_cross_gipf : array_like
            GIPF修正後の + および × 偏波成分
        """
        # 標準GR波形
        h_plus_gr, h_cross_gr = self.standard_inspiral_waveform(f, M1, M2, distance, inclination)
        
        # GIPF修正の適用
        phase_correction = self.gipf_phase_correction(f)
        amplitude_correction = self.gipf_amplitude_correction(f)
        
        # 複素数表現での修正
        h_complex_gr = h_plus_gr + 1j * h_cross_gr
        h_complex_gipf = h_complex_gr * amplitude_correction * np.exp(1j * phase_correction)
        
        # 実部・虚部の分離
        h_plus_gipf = np.real(h_complex_gipf)
        h_cross_gipf = np.imag(h_complex_gipf)
        
        return h_plus_gipf, h_cross_gipf
    
    def load_detector_noise_psd(self):
        """
        検出器ノイズパワースペクトル密度の読み込み
        
        Returns:
        --------
        f_noise, S_n : array_like
            周波数とノイズPSD
        """
        # 簡略化されたノイズカーブ（実際にはLIGO公式データを使用）
        f_noise = np.logspace(1, 4, 1000)  # 10 Hz - 10 kHz
        
        if 'LIGO' in self.detector:
            # LIGO設計感度（簡略版）
            S_n = 1e-23 * (f_noise / 100)**(-7/3) * np.exp(-(f_noise/1000)**2)
            S_n = np.maximum(S_n, 1e-24)  # 下限設定
        else:
            # Virgo設計感度（簡略版）
            S_n = 2e-23 * (f_noise / 100)**(-7/3) * np.exp(-(f_noise/800)**2)
            S_n = np.maximum(S_n, 2e-24)  # 下限設定
        
        return f_noise, S_n
    
    def calculate_snr(self, h_plus, h_cross, f):
        """
        信号対雑音比(SNR)の計算
        
        Parameters:
        -----------
        h_plus, h_cross : array_like
            重力波偏波成分
        f : array_like
            周波数配列
        
        Returns:
        --------
        snr : float
            信号対雑音比
        """
        # 検出器ノイズPSD
        f_noise, S_n = self.load_detector_noise_psd()
        
        # 周波数範囲の調整
        f_min, f_max = self.detector_specs['f_min'], self.detector_specs['f_max']
        mask = (f >= f_min) & (f <= f_max)
        f_signal = f[mask]
        h_plus_signal = h_plus[mask]
        h_cross_signal = h_cross[mask]
        
        # ノイズPSDの補間
        S_n_interp = interp1d(f_noise, S_n, bounds_error=False, fill_value='extrapolate')
        S_n_signal = S_n_interp(f_signal)
        
        # SNR積分
        integrand = (h_plus_signal**2 + h_cross_signal**2) / S_n_signal
        snr_squared = 4 * np.trapz(integrand, f_signal)
        
        return np.sqrt(snr_squared)
    
    def detection_analysis(self, M1_range, M2_range, distance_range, n_samples=50):
        """
        検出可能性の包括的分析
        
        Parameters:
        -----------
        M1_range, M2_range : tuple
            質量範囲 [M_sun]
        distance_range : tuple
            距離範囲 [Mpc]
        n_samples : int
            サンプル数
        
        Returns:
        --------
        results : dict
            検出分析結果
        """
        print("🔍 GIPF重力波検出可能性分析開始...")
        
        # パラメータサンプリング
        M1_samples = np.linspace(M1_range[0], M1_range[1], n_samples)
        M2_samples = np.linspace(M2_range[0], M2_range[1], n_samples)
        distance_samples = np.linspace(distance_range[0], distance_range[1], n_samples)
        
        # 周波数配列
        f = np.logspace(1, 4, 2000)  # 10 Hz - 10 kHz
        
        results = {
            'parameters': [],
            'snr_gr': [],
            'snr_gipf': [],
            'snr_improvement': [],
            'detectable_gr': [],
            'detectable_gipf': []
        }
        
        detection_threshold = 8.0  # 標準的な検出閾値
        
        for i, M1 in enumerate(M1_samples[::5]):  # サンプル数削減
            for j, M2 in enumerate(M2_samples[::5]):
                for k, distance in enumerate(distance_samples[::5]):
                    if M1 < M2:  # M1 >= M2の慣例
                        continue
                    
                    # 標準GR波形
                    h_plus_gr, h_cross_gr = self.standard_inspiral_waveform(f, M1, M2, distance)
                    snr_gr = self.calculate_snr(h_plus_gr, h_cross_gr, f)
                    
                    # GIPF修正波形
                    h_plus_gipf, h_cross_gipf = self.gipf_modified_waveform(f, M1, M2, distance)
                    snr_gipf = self.calculate_snr(h_plus_gipf, h_cross_gipf, f)
                    
                    # 結果の記録
                    results['parameters'].append((M1, M2, distance))
                    results['snr_gr'].append(snr_gr)
                    results['snr_gipf'].append(snr_gipf)
                    results['snr_improvement'].append(snr_gipf - snr_gr)
                    results['detectable_gr'].append(snr_gr > detection_threshold)
                    results['detectable_gipf'].append(snr_gipf > detection_threshold)
        
        # 統計的サマリー
        snr_improvements = np.array(results['snr_improvement'])
        detectable_gr_count = sum(results['detectable_gr'])
        detectable_gipf_count = sum(results['detectable_gipf'])
        
        print(f"📊 検出分析結果:")
        print(f"   サンプル数: {len(results['parameters'])}")
        print(f"   平均SNR改善: {np.mean(snr_improvements):.2f}")
        print(f"   最大SNR改善: {np.max(snr_improvements):.2f}")
        print(f"   GR検出可能: {detectable_gr_count}/{len(results['parameters'])}")
        print(f"   GIPF検出可能: {detectable_gipf_count}/{len(results['parameters'])}")
        print(f"   検出率改善: {detectable_gipf_count - detectable_gr_count} 追加検出")
        
        return results
    
    def generate_lal_compatible_output(self, M1, M2, distance, output_file='gipf_waveform_template.h5'):
        """
        LALSuite互換形式での波形テンプレート出力
        
        Parameters:
        -----------
        M1, M2 : float
            バイナリー質量 [M_sun]
        distance : float
            光度距離 [Mpc]
        output_file : str
            出力ファイル名
        """
        print(f"💾 LALSuite互換波形テンプレート生成中...")
        
        # 高解像度周波数配列
        f = np.logspace(np.log10(self.detector_specs['f_min']), 
                       np.log10(self.detector_specs['f_max']), 8192)
        
        # GIPF修正波形の生成
        h_plus_gipf, h_cross_gipf = self.gipf_modified_waveform(f, M1, M2, distance)
        
        # HDF5形式で保存（LALSuite標準）
        with h5py.File(output_file, 'w') as hf:
            # メタデータ
            hf.attrs['detector'] = self.detector
            hf.attrs['M1'] = M1
            hf.attrs['M2'] = M2
            hf.attrs['distance'] = distance
            hf.attrs['beta_info'] = self.beta_info
            hf.attrs['f_info'] = self.f_info
            
            # 波形データ
            hf.create_dataset('frequency', data=f)
            hf.create_dataset('h_plus', data=h_plus_gipf)
            hf.create_dataset('h_cross', data=h_cross_gipf)
            
            # 補正データ
            hf.create_dataset('phase_correction', data=self.gipf_phase_correction(f))
            hf.create_dataset('amplitude_correction', data=self.gipf_amplitude_correction(f))
        
        print(f"✅ LALSuite互換テンプレート保存: {output_file}")
        
        return output_file
    
    def visualize_gipf_modifications(self, M1=30, M2=25, distance=100):
        """
        GIPF修正効果の可視化
        
        Parameters:
        -----------
        M1, M2 : float
            バイナリー質量 [M_sun]
        distance : float
            光度距離 [Mpc]
        """
        f = np.logspace(1, 4, 1000)
        
        # 波形計算
        h_plus_gr, h_cross_gr = self.standard_inspiral_waveform(f, M1, M2, distance)
        h_plus_gipf, h_cross_gipf = self.gipf_modified_waveform(f, M1, M2, distance)
        
        # 修正効果の計算
        phase_correction = self.gipf_phase_correction(f)
        amplitude_correction = self.gipf_amplitude_correction(f)
        
        # 可視化
        fig, axes = plt.subplots(2, 2, figsize=(15, 10))
        
        # 波形比較
        axes[0,0].loglog(f, np.abs(h_plus_gr), 'b-', label='GR標準', alpha=0.7)
        axes[0,0].loglog(f, np.abs(h_plus_gipf), 'r-', label='GIPF修正', alpha=0.7)
        axes[0,0].set_xlabel('周波数 [Hz]')
        axes[0,0].set_ylabel('歪み振幅')
        axes[0,0].set_title('重力波歪み振幅比較')
        axes[0,0].legend()
        axes[0,0].grid(True, alpha=0.3)
        
        # 位相修正
        axes[0,1].semilogx(f, phase_correction, 'g-', linewidth=2)
        axes[0,1].set_xlabel('周波数 [Hz]')
        axes[0,1].set_ylabel('位相修正 [rad]')
        axes[0,1].set_title('GIPF位相修正')
        axes[0,1].grid(True, alpha=0.3)
        
        # 振幅修正
        axes[1,0].semilogx(f, amplitude_correction, 'm-', linewidth=2)
        axes[1,0].set_xlabel('周波数 [Hz]')
        axes[1,0].set_ylabel('振幅修正係数')
        axes[1,0].set_title('GIPF振幅修正')
        axes[1,0].grid(True, alpha=0.3)
        
        # 相対的改善
        strain_improvement = np.abs(h_plus_gipf) / np.abs(h_plus_gr)
        axes[1,1].semilogx(f, strain_improvement, 'orange', linewidth=2)
        axes[1,1].axhline(y=1.1, color='red', linestyle='--', alpha=0.7, label='10%改善')
        axes[1,1].set_xlabel('周波数 [Hz]')
        axes[1,1].set_ylabel('歪み改善比')
        axes[1,1].set_title('GIPF歪み改善率')
        axes[1,1].legend()
        axes[1,1].grid(True, alpha=0.3)
        
        plt.tight_layout()
        plt.show()
        
        # 統計情報の表示
        improvement_high_freq = strain_improvement[f > 100]
        print(f"📈 GIPF修正効果サマリー:")
        print(f"   高周波域(f>100Hz)での平均改善: {np.mean(improvement_high_freq):.3f}")
        print(f"   最大歪み改善: {np.max(strain_improvement):.3f}")
        print(f"   最大位相修正: {np.max(phase_correction):.3f} rad")

def main():
    """
    GIPF重力波形実装のデモンストレーション
    """
    print("🚀 GIPF重力波形実装デモ開始")
    print("=" * 60)
    
    # LIGO O4検出器でのGIPF波形生成
    gipf_generator = GIPFWaveformGenerator(detector='LIGO_O4')
    
    # 典型的なバイナリーブラックホール
    M1, M2 = 30, 25  # solar masses
    distance = 100    # Mpc
    
    print(f"\n📏 分析パラメータ:")
    print(f"   バイナリー質量: {M1} + {M2} M☉")
    print(f"   光度距離: {distance} Mpc")
    
    # 可視化
    print(f"\n🎨 GIPF修正効果可視化...")
    gipf_generator.visualize_gipf_modifications(M1, M2, distance)
    
    # 検出可能性分析
    print(f"\n🔍 検出可能性分析...")
    detection_results = gipf_generator.detection_analysis(
        M1_range=(10, 50), 
        M2_range=(10, 50), 
        distance_range=(50, 200),
        n_samples=30
    )
    
    # LALSuite互換テンプレート生成
    print(f"\n💾 LALSuite互換テンプレート生成...")
    template_file = gipf_generator.generate_lal_compatible_output(M1, M2, distance)
    
    print(f"\n✅ GIPF重力波形実装デモ完了")
    print(f"生成されたテンプレート: {template_file}")
    
    return gipf_generator, detection_results

if __name__ == "__main__":
    generator, results = main() 