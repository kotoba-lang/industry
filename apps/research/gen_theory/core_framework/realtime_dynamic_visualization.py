#!/usr/bin/env python3
"""
リアルタイム動的生成可視化システム - Real-time Dynamic Generation Visualization
==========================================================================

動的生成プロセスのリアルタイム可視化システム：
- 動的生成粒子の3D軌道追跡
- 創発場のリアルタイムヒートマップ
- 現実場の時間変化アニメーション
- システム統計の動的更新表示
- インタラクティブな制御機能

Author: Jun Kawasaki
Date: 2025-01-27
Version: 1.0 - Real-time Visualization

特徴:
- matplotlib.animation による滑らかなアニメーション
- 多次元データの同時可視化
- パフォーマンス最適化済み
- 録画・保存機能付き
"""

import numpy as np
import matplotlib
matplotlib.use('TkAgg')  # インタラクティブ表示用
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D
import matplotlib.animation as animation
from matplotlib.widgets import Button, Slider
import time
from typing import Dict, List, Tuple, Any
import logging
from simple_dynamic_simulation import SimpleDynamicSystem, DynamicGenerationParticle

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class RealtimeVisualizationSystem:
    """リアルタイム可視化システム"""
    
    def __init__(self, system: SimpleDynamicSystem):
        self.system = system
        self.animation_running = False
        self.current_step = 0
        self.max_steps = 500
        self.dt = 0.02  # リアルタイム表示用の時間刻み
        
        # 履歴データ
        self.time_history = []
        self.generation_rate_history = []
        self.complexity_history = []
        self.order_parameter_history = []
        self.emergence_max_history = []
        self.reality_volume_history = []
        
        # 粒子軌道履歴
        self.particle_trajectories = {p.id: [] for p in self.system.particles}
        
        # アニメーション設定
        self.update_interval = 50  # ミリ秒
        self.trail_length = 50     # 軌道の長さ
        
        logger.info("Real-time Visualization System initialized")
    
    def setup_visualization(self):
        """可視化セットアップ"""
        
        # メインウィンドウの設定
        self.fig = plt.figure(figsize=(20, 12))
        self.fig.suptitle('リアルタイム動的生成シミュレーション', fontsize=16, fontweight='bold')
        
        # グリッドレイアウト
        gs = self.fig.add_gridspec(3, 4, hspace=0.3, wspace=0.3)
        
        # 1. メイン3D可視化 (大きなパネル)
        self.ax_main = self.fig.add_subplot(gs[:2, :2], projection='3d')
        self.ax_main.set_title('動的生成粒子と創発場')
        self.ax_main.set_xlabel('X')
        self.ax_main.set_ylabel('Y')
        self.ax_main.set_zlabel('Z')
        
        # 3D表示範囲の設定
        grid_size = self.system.grid_size
        self.ax_main.set_xlim(0, grid_size)
        self.ax_main.set_ylim(0, grid_size)
        self.ax_main.set_zlim(0, grid_size)
        
        # 2. 創発場ヒートマップ
        self.ax_emergence = self.fig.add_subplot(gs[0, 2])
        self.ax_emergence.set_title('創発場 (Z=中央層)')
        self.ax_emergence.set_xlabel('X')
        self.ax_emergence.set_ylabel('Y')
        
        # 3. 現実場ヒートマップ
        self.ax_reality = self.fig.add_subplot(gs[0, 3])
        self.ax_reality.set_title('現実場 (Z=中央層)')
        self.ax_reality.set_xlabel('X')
        self.ax_reality.set_ylabel('Y')
        
        # 4. 生成率時系列
        self.ax_generation = self.fig.add_subplot(gs[1, 2])
        self.ax_generation.set_title('平均生成率')
        self.ax_generation.set_xlabel('時間')
        self.ax_generation.set_ylabel('生成率')
        self.ax_generation.grid(True, alpha=0.3)
        
        # 5. 複雑度時系列
        self.ax_complexity = self.fig.add_subplot(gs[1, 3])
        self.ax_complexity.set_title('平均複雑度')
        self.ax_complexity.set_xlabel('時間')
        self.ax_complexity.set_ylabel('複雑度')
        self.ax_complexity.grid(True, alpha=0.3)
        
        # 6. システム統計パネル
        self.ax_stats = self.fig.add_subplot(gs[2, :2])
        self.ax_stats.set_title('システム統計')
        self.ax_stats.axis('off')
        
        # 7. 秩序パラメータ
        self.ax_order = self.fig.add_subplot(gs[2, 2])
        self.ax_order.set_title('秩序パラメータ')
        self.ax_order.set_xlabel('時間')
        self.ax_order.set_ylabel('秩序度')
        self.ax_order.grid(True, alpha=0.3)
        
        # 8. 現実体積
        self.ax_volume = self.fig.add_subplot(gs[2, 3])
        self.ax_volume.set_title('現実体積')
        self.ax_volume.set_xlabel('時間')
        self.ax_volume.set_ylabel('体積')
        self.ax_volume.grid(True, alpha=0.3)
        
        # 初期プロット要素
        self.particle_scatter = None
        self.emergence_heatmap = None
        self.reality_heatmap = None
        self.generation_line, = self.ax_generation.plot([], [], 'b-', linewidth=2)
        self.complexity_line, = self.ax_complexity.plot([], [], 'g-', linewidth=2)
        self.order_line, = self.ax_order.plot([], [], 'r-', linewidth=2)
        self.volume_line, = self.ax_volume.plot([], [], 'm-', linewidth=2)
        
        # 統計テキスト
        self.stats_text = self.ax_stats.text(0.05, 0.95, '', transform=self.ax_stats.transAxes,
                                           fontsize=10, verticalalignment='top',
                                           bbox=dict(boxstyle='round', facecolor='lightblue', alpha=0.8))
        
        # 制御ボタンの追加
        self.setup_controls()
        
        logger.info("Visualization setup completed")
    
    def setup_controls(self):
        """制御ボタンの設定"""
        
        # ボタン用の軸
        ax_button_play = plt.axes([0.02, 0.02, 0.08, 0.04])
        ax_button_pause = plt.axes([0.12, 0.02, 0.08, 0.04])
        ax_button_reset = plt.axes([0.22, 0.02, 0.08, 0.04])
        ax_button_save = plt.axes([0.32, 0.02, 0.08, 0.04])
        
        # ボタンの作成
        self.button_play = Button(ax_button_play, '再生')
        self.button_pause = Button(ax_button_pause, '一時停止')
        self.button_reset = Button(ax_button_reset, 'リセット')
        self.button_save = Button(ax_button_save, '保存')
        
        # ボタンイベントの設定
        self.button_play.on_clicked(self.play_animation)
        self.button_pause.on_clicked(self.pause_animation)
        self.button_reset.on_clicked(self.reset_animation)
        self.button_save.on_clicked(self.save_animation)
        
        # 速度調整スライダー
        ax_speed = plt.axes([0.45, 0.02, 0.2, 0.03])
        self.speed_slider = Slider(ax_speed, '速度', 0.1, 3.0, valinit=1.0)
        self.speed_slider.on_changed(self.update_speed)
    
    def play_animation(self, event):
        """アニメーション再生"""
        self.animation_running = True
        logger.info("Animation started")
    
    def pause_animation(self, event):
        """アニメーション一時停止"""
        self.animation_running = False
        logger.info("Animation paused")
    
    def reset_animation(self, event):
        """アニメーションリセット"""
        self.animation_running = False
        self.current_step = 0
        
        # システムの再初期化
        self.system = SimpleDynamicSystem(
            grid_size=self.system.grid_size,
            num_particles=len(self.system.particles)
        )
        
        # 履歴のクリア
        self.time_history.clear()
        self.generation_rate_history.clear()
        self.complexity_history.clear()
        self.order_parameter_history.clear()
        self.emergence_max_history.clear()
        self.reality_volume_history.clear()
        
        # 軌道履歴のクリア
        self.particle_trajectories = {p.id: [] for p in self.system.particles}
        
        logger.info("Animation reset")
    
    def update_speed(self, val):
        """速度更新"""
        speed = self.speed_slider.val
        self.dt = 0.02 * speed
        logger.info(f"Animation speed updated: {speed:.1f}x")
    
    def save_animation(self, event):
        """アニメーション保存"""
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        filename = f"realtime_dynamic_generation_{timestamp}.mp4"
        
        # MP4ライターの設定
        Writer = animation.writers['pillow']  # または 'ffmpeg'
        writer = Writer(fps=20, metadata=dict(artist='Dynamic Generation System'), bitrate=1800)
        
        # アニメーションの保存
        if hasattr(self, 'anim'):
            self.anim.save(filename, writer=writer)
            logger.info(f"Animation saved: {filename}")
        else:
            logger.warning("No animation to save")
    
    def update_frame(self, frame):
        """フレーム更新関数"""
        
        if not self.animation_running:
            return []
        
        if self.current_step >= self.max_steps:
            self.animation_running = False
            return []
        
        # システムの時間発展
        self.system.evolve_system(self.dt)
        self.current_step += 1
        
        # データの収集
        self.collect_frame_data()
        
        # 可視化の更新
        artists = []
        
        # 1. 3D粒子表示の更新
        artists.extend(self.update_3d_particles())
        
        # 2. ヒートマップの更新
        artists.extend(self.update_heatmaps())
        
        # 3. 時系列グラフの更新
        artists.extend(self.update_timeseries())
        
        # 4. 統計情報の更新
        artists.extend(self.update_statistics())
        
        return artists
    
    def collect_frame_data(self):
        """フレームデータの収集"""
        
        # 時間履歴
        self.time_history.append(self.system.current_time)
        
        # 粒子統計
        generation_rates = [p.generation_rate for p in self.system.particles]
        complexities = [p.complexity for p in self.system.particles]
        
        self.generation_rate_history.append(np.mean(generation_rates))
        self.complexity_history.append(np.mean(complexities))
        
        # システム統計
        self.order_parameter_history.append(self.system.system_order_parameter)
        self.emergence_max_history.append(np.max(self.system.emergence_field))
        self.reality_volume_history.append(np.sum(self.system.reality_field > 0.1))
        
        # 粒子軌道
        for particle in self.system.particles:
            trajectory = self.particle_trajectories[particle.id]
            trajectory.append(particle.position.copy())
            
            # 軌道長制限
            if len(trajectory) > self.trail_length:
                trajectory.pop(0)
    
    def update_3d_particles(self):
        """3D粒子表示の更新"""
        
        # 現在の粒子位置
        positions = np.array([p.position for p in self.system.particles])
        generation_rates = np.array([p.generation_rate for p in self.system.particles])
        complexities = np.array([p.complexity for p in self.system.particles])
        
        # 粒子の色（複雑度）とサイズ（生成率）
        colors = complexities
        sizes = generation_rates * 20 + 10
        
        # 散布図の更新
        if self.particle_scatter is not None:
            self.particle_scatter.remove()
        
        self.particle_scatter = self.ax_main.scatter(
            positions[:, 0], positions[:, 1], positions[:, 2],
            c=colors, s=sizes, cmap='plasma', alpha=0.8
        )
        
        # 軌道の描画（数個の粒子のみ）
        for i, particle in enumerate(self.system.particles[:5]):  # 最初の5個の粒子
            trajectory = self.particle_trajectories[particle.id]
            if len(trajectory) > 1:
                trajectory_array = np.array(trajectory)
                self.ax_main.plot(trajectory_array[:, 0], trajectory_array[:, 1], trajectory_array[:, 2],
                                 alpha=0.5, linewidth=1, color=plt.cm.plasma(complexities[i]/np.max(complexities)))
        
        return [self.particle_scatter]
    
    def update_heatmaps(self):
        """ヒートマップの更新"""
        
        artists = []
        
        # 中央層のスライス
        z_center = self.system.grid_size // 2
        
        # 創発場ヒートマップ
        emergence_slice = self.system.emergence_field[:, :, z_center]
        
        if self.emergence_heatmap is not None:
            self.emergence_heatmap.remove()
        
        self.emergence_heatmap = self.ax_emergence.imshow(
            emergence_slice, cmap='hot', interpolation='bilinear',
            vmin=0, vmax=np.max(self.system.emergence_field)
        )
        artists.append(self.emergence_heatmap)
        
        # 現実場ヒートマップ
        reality_slice = self.system.reality_field[:, :, z_center]
        
        if self.reality_heatmap is not None:
            self.reality_heatmap.remove()
        
        self.reality_heatmap = self.ax_reality.imshow(
            reality_slice, cmap='viridis', interpolation='bilinear',
            vmin=0, vmax=1.0
        )
        artists.append(self.reality_heatmap)
        
        return artists
    
    def update_timeseries(self):
        """時系列グラフの更新"""
        
        if len(self.time_history) < 2:
            return []
        
        artists = []
        
        # データの準備
        times = self.time_history
        
        # 生成率
        self.generation_line.set_data(times, self.generation_rate_history)
        self.ax_generation.relim()
        self.ax_generation.autoscale_view()
        artists.append(self.generation_line)
        
        # 複雑度
        self.complexity_line.set_data(times, self.complexity_history)
        self.ax_complexity.relim()
        self.ax_complexity.autoscale_view()
        artists.append(self.complexity_line)
        
        # 秩序パラメータ
        self.order_line.set_data(times, self.order_parameter_history)
        self.ax_order.relim()
        self.ax_order.autoscale_view()
        artists.append(self.order_line)
        
        # 現実体積
        self.volume_line.set_data(times, self.reality_volume_history)
        self.ax_volume.relim()
        self.ax_volume.autoscale_view()
        artists.append(self.volume_line)
        
        return artists
    
    def update_statistics(self):
        """統計情報の更新"""
        
        if not self.system.particles:
            return []
        
        # 現在の統計
        generation_rates = [p.generation_rate for p in self.system.particles]
        complexities = [p.complexity for p in self.system.particles]
        soc_levels = [p.self_organization_level for p in self.system.particles]
        
        # 統計テキストの作成
        stats_text = f"""🔬 リアルタイム統計 (ステップ {self.current_step})
⏰ 物理時間: {self.system.current_time:.3f}s
🔢 粒子数: {len(self.system.particles)}

📊 生成率統計:
  平均: {np.mean(generation_rates):.3f}
  最大: {np.max(generation_rates):.3f}
  標準偏差: {np.std(generation_rates):.3f}

🧠 複雑度統計:
  平均: {np.mean(complexities):.3f}
  最大: {np.max(complexities):.3f}

🌟 自己組織化:
  平均レベル: {np.mean(soc_levels):.3f}
  秩序パラメータ: {self.system.system_order_parameter:.3f}

🌍 場統計:
  創発場最大: {np.max(self.system.emergence_field):.3f}
  現実体積: {np.sum(self.system.reality_field > 0.1)}
  情報圧力: {self.system.total_information_pressure:.3f}

🎯 性能指標:
  生成効率: {(np.max(generation_rates)/np.mean(generation_rates)):.2f}倍
  複雑化効率: {(np.max(complexities)/np.mean(complexities)):.2f}倍
"""
        
        self.stats_text.set_text(stats_text)
        
        return [self.stats_text]
    
    def start_realtime_visualization(self):
        """リアルタイム可視化開始"""
        
        logger.info("Starting real-time visualization...")
        
        # 可視化セットアップ
        self.setup_visualization()
        
        # アニメーション作成
        self.anim = animation.FuncAnimation(
            self.fig, self.update_frame,
            frames=self.max_steps,
            interval=self.update_interval,
            blit=False,  # 複雑な更新のためblitはオフ
            repeat=False
        )
        
        # アニメーション開始
        self.animation_running = True
        
        logger.info("Real-time visualization started")
        
        # 表示
        plt.show()
        
        return self.anim

def create_interactive_demo():
    """インタラクティブデモの作成"""
    
    print("🎬 リアルタイム動的生成可視化システム")
    print("=" * 60)
    print("特徴: 動的生成プロセスのリアルタイム観察")
    print("機能: 3D軌道 × ヒートマップ × 統計更新")
    print("制御: 再生/一時停止/リセット/速度調整")
    print("=" * 60)
    
    # システム初期化
    print("\n🔧 システム初期化中...")
    system = SimpleDynamicSystem(grid_size=20, num_particles=60)
    
    # 可視化システム初期化
    print("🎨 可視化システム初期化中...")
    viz_system = RealtimeVisualizationSystem(system)
    
    print("✅ 初期化完了！")
    print("\n🚀 リアルタイム可視化を開始します...")
    print("制御方法:")
    print("  - 再生ボタン: アニメーション開始")
    print("  - 一時停止ボタン: アニメーション停止")
    print("  - リセットボタン: 初期状態に戻る")
    print("  - 速度スライダー: 再生速度調整")
    print("  - 保存ボタン: MP4ファイルで保存")
    
    # リアルタイム可視化開始
    anim = viz_system.start_realtime_visualization()
    
    return viz_system, anim

def main():
    """メイン実行関数"""
    
    try:
        # インタラクティブデモの実行
        viz_system, anim = create_interactive_demo()
        
        print("\n🎉 可視化セッション完了")
        print("結果:")
        print(f"  - 総実行ステップ: {viz_system.current_step}")
        print(f"  - 物理時間: {viz_system.system.current_time:.3f}秒")
        
        if viz_system.time_history:
            print(f"  - 最終生成率: {viz_system.generation_rate_history[-1]:.3f}")
            print(f"  - 最終複雑度: {viz_system.complexity_history[-1]:.3f}")
            print(f"  - 最終秩序度: {viz_system.order_parameter_history[-1]:.3f}")
        
        return viz_system, anim
        
    except KeyboardInterrupt:
        print("\n⏹️ ユーザーによる中断")
        return None, None
    
    except Exception as e:
        print(f"\n❌ エラーが発生しました: {e}")
        logger.error(f"Visualization error: {e}")
        return None, None

if __name__ == "__main__":
    viz_system, anim = main() 