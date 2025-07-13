#!/usr/bin/env python3
"""
インタラクティブGEN実証実験ダッシュボード
Interactive GEN Proof-of-Concept Experiment Dashboard

リアルタイムパラメータ調整と可視化
"""

import numpy as np
import matplotlib.pyplot as plt
from matplotlib.widgets import Slider, Button, CheckButtons
from matplotlib.animation import FuncAnimation
import pandas as pd
from scipy import constants
from typing import Dict, List, Tuple, Any
from dataclasses import dataclass
import time
import threading
import queue

# 前回の設定をインポート
from minimal_scale_experiment import MinimalExperimentConfig, MinimalGENExperiment

class InteractiveExperimentDashboard:
    """インタラクティブ実験ダッシュボード"""
    
    def __init__(self):
        self.config = MinimalExperimentConfig()
        self.experiment = MinimalGENExperiment(self.config)
        
        # リアルタイムデータ
        self.time_data = []
        self.input_power_data = []
        self.output_power_data = []
        self.efficiency_data = []
        self.magnetic_field_data = []
        self.temperature_data = []
        
        # パラメータ制御
        self.params = {
            'led_power': 1.0,
            'laser_power': 0.005,
            'magnetic_field': 0.01,
            'chamber_temperature': 300,
            'information_rate': 1e6,
            'chamber_volume': 1e-4,
            'detection_sensitivity': 1e-12
        }
        
        # 実験状態
        self.is_running = False
        self.start_time = 0
        self.data_queue = queue.Queue()
        
        # フィギュア設定
        self.setup_dashboard()
    
    def setup_dashboard(self):
        """ダッシュボードの設定"""
        
        # メインフィギュア
        self.fig = plt.figure(figsize=(20, 12))
        self.fig.suptitle('リアルタイムGEN実証実験ダッシュボード', fontsize=16, fontweight='bold')
        
        # グリッドレイアウト
        gs = self.fig.add_gridspec(4, 5, hspace=0.4, wspace=0.3)
        
        # 1. リアルタイムパワー監視
        self.ax_power = self.fig.add_subplot(gs[0, :3])
        self.line_input, = self.ax_power.plot([], [], 'b-', label='入力パワー (W)', linewidth=2)
        self.line_output, = self.ax_power.plot([], [], 'r-', label='出力パワー (×10¹⁵ W)', linewidth=2)
        self.ax_power.set_xlabel('経過時間 (s)')
        self.ax_power.set_ylabel('パワー')
        self.ax_power.set_title('リアルタイムパワー監視')
        self.ax_power.legend()
        self.ax_power.grid(True, alpha=0.3)
        self.ax_power.set_xlim(0, 60)
        self.ax_power.set_ylim(0, 15)
        
        # 2. 効率監視
        self.ax_efficiency = self.fig.add_subplot(gs[1, :3])
        self.line_efficiency, = self.ax_efficiency.plot([], [], 'purple', linewidth=2)
        self.ax_efficiency.set_xlabel('経過時間 (s)')
        self.ax_efficiency.set_ylabel('効率 (log scale)')
        self.ax_efficiency.set_title('エネルギー変換効率')
        self.ax_efficiency.set_yscale('log')
        self.ax_efficiency.grid(True, alpha=0.3)
        self.ax_efficiency.set_xlim(0, 60)
        self.ax_efficiency.set_ylim(1e-30, 1e-25)
        
        # 3. 環境パラメータ
        self.ax_env = self.fig.add_subplot(gs[2, :3])
        self.line_magnetic, = self.ax_env.plot([], [], 'orange', label='磁場 (mT)', linewidth=2)
        self.ax_env_temp = self.ax_env.twinx()
        self.line_temp, = self.ax_env_temp.plot([], [], 'red', label='温度 (K)', linewidth=2)
        self.ax_env.set_xlabel('経過時間 (s)')
        self.ax_env.set_ylabel('磁場 (mT)', color='orange')
        self.ax_env_temp.set_ylabel('温度 (K)', color='red')
        self.ax_env.set_title('環境パラメータ監視')
        self.ax_env.grid(True, alpha=0.3)
        self.ax_env.set_xlim(0, 60)
        self.ax_env.set_ylim(9, 11)
        self.ax_env_temp.set_ylim(295, 310)
        
        # 4. 制御パネル（右側）
        self.setup_control_panel()
        
        # 5. 実験状態表示
        self.ax_status = self.fig.add_subplot(gs[3, :])
        self.ax_status.axis('off')
        self.status_text = self.ax_status.text(0.1, 0.5, '実験待機中...', 
                                              fontsize=14, fontweight='bold',
                                              bbox=dict(boxstyle='round', facecolor='lightgray'))
    
    def setup_control_panel(self):
        """制御パネルの設定"""
        
        # スライダー位置計算
        slider_height = 0.03
        slider_width = 0.15
        start_x = 0.65
        start_y = 0.85
        
        # LED電力制御
        ax_led = plt.axes([start_x, start_y, slider_width, slider_height])
        self.slider_led = Slider(ax_led, 'LED Power (W)', 0.1, 5.0, 
                                valinit=self.params['led_power'], valfmt='%.2f')
        
        # レーザー電力制御
        ax_laser = plt.axes([start_x, start_y - 0.08, slider_width, slider_height])
        self.slider_laser = Slider(ax_laser, 'Laser Power (mW)', 1, 20, 
                                  valinit=self.params['laser_power']*1000, valfmt='%.1f')
        
        # 磁場制御
        ax_magnetic = plt.axes([start_x, start_y - 0.16, slider_width, slider_height])
        self.slider_magnetic = Slider(ax_magnetic, 'Magnetic Field (mT)', 5, 50, 
                                     valinit=self.params['magnetic_field']*1000, valfmt='%.1f')
        
        # 温度制御
        ax_temp = plt.axes([start_x, start_y - 0.24, slider_width, slider_height])
        self.slider_temp = Slider(ax_temp, 'Temperature (K)', 280, 350, 
                                 valinit=self.params['chamber_temperature'], valfmt='%.0f')
        
        # 情報処理レート制御
        ax_info = plt.axes([start_x, start_y - 0.32, slider_width, slider_height])
        self.slider_info = Slider(ax_info, 'Info Rate (MHz)', 0.1, 10, 
                                 valinit=self.params['information_rate']/1e6, valfmt='%.1f')
        
        # 体積制御
        ax_volume = plt.axes([start_x, start_y - 0.40, slider_width, slider_height])
        self.slider_volume = Slider(ax_volume, 'Volume (ml)', 50, 500, 
                                   valinit=self.params['chamber_volume']*1e6, valfmt='%.0f')
        
        # 検出感度制御
        ax_sensitivity = plt.axes([start_x, start_y - 0.48, slider_width, slider_height])
        self.slider_sensitivity = Slider(ax_sensitivity, 'Sensitivity (log)', -15, -9, 
                                        valinit=np.log10(self.params['detection_sensitivity']), 
                                        valfmt='%.0f')
        
        # 制御ボタン
        ax_start = plt.axes([start_x, start_y - 0.60, 0.07, 0.04])
        self.btn_start = Button(ax_start, 'Start')
        
        ax_stop = plt.axes([start_x + 0.08, start_y - 0.60, 0.07, 0.04])
        self.btn_stop = Button(ax_stop, 'Stop')
        
        ax_reset = plt.axes([start_x, start_y - 0.67, 0.07, 0.04])
        self.btn_reset = Button(ax_reset, 'Reset')
        
        ax_save = plt.axes([start_x + 0.08, start_y - 0.67, 0.07, 0.04])
        self.btn_save = Button(ax_save, 'Save')
        
        # イベントハンドラ
        self.slider_led.on_changed(self.update_led_power)
        self.slider_laser.on_changed(self.update_laser_power)
        self.slider_magnetic.on_changed(self.update_magnetic_field)
        self.slider_temp.on_changed(self.update_temperature)
        self.slider_info.on_changed(self.update_info_rate)
        self.slider_volume.on_changed(self.update_volume)
        self.slider_sensitivity.on_changed(self.update_sensitivity)
        
        self.btn_start.on_clicked(self.start_experiment)
        self.btn_stop.on_clicked(self.stop_experiment)
        self.btn_reset.on_clicked(self.reset_experiment)
        self.btn_save.on_clicked(self.save_data)
        
        # パラメータ表示
        info_y = start_y - 0.75
        self.ax_info = self.fig.add_axes([start_x, info_y, slider_width, 0.15])
        self.ax_info.axis('off')
        self.update_parameter_display()
    
    def update_led_power(self, val):
        """LED電力更新"""
        self.params['led_power'] = val
        self.update_parameter_display()
    
    def update_laser_power(self, val):
        """レーザー電力更新"""
        self.params['laser_power'] = val / 1000  # mW to W
        self.update_parameter_display()
    
    def update_magnetic_field(self, val):
        """磁場更新"""
        self.params['magnetic_field'] = val / 1000  # mT to T
        self.update_parameter_display()
    
    def update_temperature(self, val):
        """温度更新"""
        self.params['chamber_temperature'] = val
        self.update_parameter_display()
    
    def update_info_rate(self, val):
        """情報処理レート更新"""
        self.params['information_rate'] = val * 1e6  # MHz to Hz
        self.update_parameter_display()
    
    def update_volume(self, val):
        """体積更新"""
        self.params['chamber_volume'] = val / 1e6  # ml to m³
        self.update_parameter_display()
    
    def update_sensitivity(self, val):
        """検出感度更新"""
        self.params['detection_sensitivity'] = 10**val
        self.update_parameter_display()
    
    def update_parameter_display(self):
        """パラメータ表示更新"""
        
        # 現在のパラメータでエネルギー生成を計算
        generation = self.experiment.estimate_minimal_energy_generation(
            self.params['led_power'] + self.params['laser_power'] + 2.0,
            self.params['information_rate']
        )
        
        param_text = f"""
現在のパラメータ:
LED: {self.params['led_power']:.2f} W
Laser: {self.params['laser_power']*1000:.1f} mW
磁場: {self.params['magnetic_field']*1000:.1f} mT
温度: {self.params['chamber_temperature']:.0f} K
情報: {self.params['information_rate']/1e6:.1f} MHz
体積: {self.params['chamber_volume']*1e6:.0f} ml

予測結果:
効率: {generation['efficiency']:.2e}
出力: {generation['base_generation']:.2e} W
S/N: {generation['signal_to_noise']:.2e}
検出: {'Yes' if generation['detectable'] else 'No'}
        """
        
        self.ax_info.clear()
        self.ax_info.axis('off')
        self.ax_info.text(0.05, 0.95, param_text, transform=self.ax_info.transAxes,
                         fontsize=9, fontfamily='monospace',
                         verticalalignment='top',
                         bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.8))
    
    def start_experiment(self, event):
        """実験開始"""
        if not self.is_running:
            self.is_running = True
            self.start_time = time.time()
            self.experiment_thread = threading.Thread(target=self.run_experiment_simulation)
            self.experiment_thread.daemon = True
            self.experiment_thread.start()
            
            # アニメーション開始
            self.animation = FuncAnimation(self.fig, self.update_plots, interval=50, blit=False)
            
            self.update_status("実験実行中...")
    
    def stop_experiment(self, event):
        """実験停止"""
        self.is_running = False
        if hasattr(self, 'animation'):
            self.animation.event_source.stop()
        self.update_status("実験停止")
    
    def reset_experiment(self, event):
        """実験リセット"""
        self.stop_experiment(None)
        
        # データクリア
        self.time_data.clear()
        self.input_power_data.clear()
        self.output_power_data.clear()
        self.efficiency_data.clear()
        self.magnetic_field_data.clear()
        self.temperature_data.clear()
        
        # プロットクリア
        self.line_input.set_data([], [])
        self.line_output.set_data([], [])
        self.line_efficiency.set_data([], [])
        self.line_magnetic.set_data([], [])
        self.line_temp.set_data([], [])
        
        self.fig.canvas.draw()
        self.update_status("実験リセット完了")
    
    def save_data(self, event):
        """データ保存"""
        if len(self.time_data) > 0:
            df = pd.DataFrame({
                'time': self.time_data,
                'input_power': self.input_power_data,
                'output_power': self.output_power_data,
                'efficiency': self.efficiency_data,
                'magnetic_field': self.magnetic_field_data,
                'temperature': self.temperature_data
            })
            
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            filename = f"gen_experiment_data_{timestamp}.csv"
            df.to_csv(filename, index=False)
            self.update_status(f"データ保存: {filename}")
        else:
            self.update_status("保存するデータがありません")
    
    def update_status(self, message):
        """ステータス更新"""
        self.status_text.set_text(f"ステータス: {message} | 時刻: {time.strftime('%H:%M:%S')}")
        self.fig.canvas.draw_idle()
    
    def run_experiment_simulation(self):
        """実験シミュレーション（バックグラウンド）"""
        
        while self.is_running:
            current_time = time.time() - self.start_time
            
            # 現在のパラメータで計算
            total_input = (self.params['led_power'] + 
                          self.params['laser_power'] + 
                          2.0)  # 制御系2W
            
            # 動的変調（実際の実験での変動をシミュレート）
            led_modulation = 1 + 0.05 * np.sin(2 * np.pi * 0.5 * current_time)
            magnetic_modulation = 1 + 0.02 * np.sin(2 * np.pi * 0.1 * current_time)
            temp_variation = self.params['chamber_temperature'] + 2 * np.random.normal()
            
            # エネルギー生成計算
            modulated_input = total_input * led_modulation
            generation = self.experiment.estimate_minimal_energy_generation(
                modulated_input, 
                self.params['information_rate']
            )
            
            # ノイズ追加
            noise_level = self.params['detection_sensitivity'] * 0.1
            measured_output = generation['base_generation'] + np.random.normal(0, noise_level)
            
            # データをキューに追加
            data_point = {
                'time': current_time,
                'input_power': modulated_input,
                'output_power': measured_output,
                'efficiency': generation['efficiency'],
                'magnetic_field': self.params['magnetic_field'] * magnetic_modulation,
                'temperature': temp_variation
            }
            
            self.data_queue.put(data_point)
            
            # 制御間隔
            time.sleep(0.05)  # 20Hz更新
    
    def update_plots(self, frame):
        """プロット更新"""
        
        # キューからデータを取得
        while not self.data_queue.empty():
            try:
                data_point = self.data_queue.get_nowait()
                
                self.time_data.append(data_point['time'])
                self.input_power_data.append(data_point['input_power'])
                self.output_power_data.append(data_point['output_power'])
                self.efficiency_data.append(data_point['efficiency'])
                self.magnetic_field_data.append(data_point['magnetic_field'])
                self.temperature_data.append(data_point['temperature'])
                
                # データ量制限（最新1000点）
                if len(self.time_data) > 1000:
                    self.time_data.pop(0)
                    self.input_power_data.pop(0)
                    self.output_power_data.pop(0)
                    self.efficiency_data.pop(0)
                    self.magnetic_field_data.pop(0)
                    self.temperature_data.pop(0)
                
            except queue.Empty:
                break
        
        if len(self.time_data) > 0:
            # プロット更新
            self.line_input.set_data(self.time_data, self.input_power_data)
            self.line_output.set_data(self.time_data, 
                                     [y * 1e15 for y in self.output_power_data])
            self.line_efficiency.set_data(self.time_data, self.efficiency_data)
            self.line_magnetic.set_data(self.time_data, 
                                       [y * 1000 for y in self.magnetic_field_data])
            self.line_temp.set_data(self.time_data, self.temperature_data)
            
            # 軸範囲自動調整
            if len(self.time_data) > 10:
                max_time = max(self.time_data)
                
                # 時間軸
                self.ax_power.set_xlim(max(0, max_time - 60), max_time + 5)
                self.ax_efficiency.set_xlim(max(0, max_time - 60), max_time + 5)
                self.ax_env.set_xlim(max(0, max_time - 60), max_time + 5)
                
                # パワー軸
                max_input = max(self.input_power_data[-100:]) if len(self.input_power_data) > 100 else max(self.input_power_data)
                self.ax_power.set_ylim(0, max_input * 1.2)
                
                # 効率軸
                if len(self.efficiency_data) > 10:
                    valid_eff = [e for e in self.efficiency_data[-100:] if e > 0]
                    if valid_eff:
                        min_eff = min(valid_eff)
                        max_eff = max(valid_eff)
                        self.ax_efficiency.set_ylim(min_eff * 0.5, max_eff * 2)
        
        return (self.line_input, self.line_output, self.line_efficiency, 
                self.line_magnetic, self.line_temp)

def main():
    """メイン関数"""
    print("🎛️ インタラクティブGEN実証実験ダッシュボード")
    print("=" * 60)
    print("使用方法:")
    print("1. 右側のスライダーでパラメータを調整")
    print("2. 'Start'ボタンで実験開始")
    print("3. リアルタイムで結果を監視")
    print("4. 'Save'ボタンでデータ保存")
    print("5. ウィンドウを閉じて終了")
    print()
    
    # ダッシュボード起動
    dashboard = InteractiveExperimentDashboard()
    
    # イベントループ開始
    plt.show()
    
    print("ダッシュボード終了")

if __name__ == "__main__":
    main() 