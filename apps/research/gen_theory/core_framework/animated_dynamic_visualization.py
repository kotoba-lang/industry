#!/usr/bin/env python3
"""
アニメーション動的生成可視化システム - Animated Dynamic Generation Visualization
============================================================================

動的生成プロセスのアニメーション可視化システム：
- 動的生成粒子の3D軌道アニメーション
- 創発場の時間変化をGIF出力
- 現実場の進化プロセス記録
- 複数の観点からの同時可視化
- Web表示対応のHTML出力

Author: Jun Kawasaki
Date: 2025-01-27
Version: 1.0 - Animated Visualization

特徴:
- matplotlib.animation による高品質アニメーション
- GIF/MP4/HTML形式での出力
- tkinter非依存の安定動作
- 軽量で高速な処理
"""

import numpy as np
import matplotlib
matplotlib.use('Agg')  # 非インタラクティブバックエンド
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d import Axes3D
import matplotlib.animation as animation
from matplotlib.gridspec import GridSpec
import time
from typing import Dict, List, Tuple, Any
import logging
import os
from simple_dynamic_simulation import SimpleDynamicSystem, DynamicGenerationParticle

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class AnimatedVisualizationSystem:
    """アニメーション可視化システム"""
    
    def __init__(self, system: SimpleDynamicSystem, animation_duration: float = 10.0):
        self.system = system
        self.animation_duration = animation_duration  # アニメーション長（秒）
        self.dt = 0.05  # 時間刻み
        self.total_frames = int(animation_duration / self.dt)
        
        # データ収集
        self.frame_data = []
        self.particle_positions = []
        self.emergence_fields = []
        self.reality_fields = []
        self.statistics = []
        
        logger.info(f"Animated Visualization System initialized: {self.total_frames} frames")
    
    def collect_simulation_data(self):
        """シミュレーションデータの収集"""
        
        logger.info("Collecting simulation data...")
        print(f"🎬 動的生成プロセスの記録開始 ({self.animation_duration}秒間)")
        
        for frame in range(self.total_frames):
            # システムの進化
            self.system.evolve_system(self.dt)
            
            # データの記録
            frame_data = {
                'time': self.system.current_time,
                'particle_positions': np.array([p.position.copy() for p in self.system.particles]),
                'particle_generation_rates': np.array([p.generation_rate for p in self.system.particles]),
                'particle_complexities': np.array([p.complexity for p in self.system.particles]),
                'particle_soc_levels': np.array([p.self_organization_level for p in self.system.particles]),
                'emergence_field': self.system.emergence_field.copy(),
                'reality_field': self.system.reality_field.copy(),
                'complexity_field': self.system.complexity_field.copy(),
                'system_order_parameter': self.system.system_order_parameter,
                'information_pressure': self.system.total_information_pressure
            }
            
            self.frame_data.append(frame_data)
            
            # 進捗表示
            if frame % 20 == 0:
                progress = (frame + 1) / self.total_frames * 100
                print(f"  進捗: {progress:.1f}% ({frame+1}/{self.total_frames})", end='\r')
        
        print(f"\n✅ データ収集完了: {len(self.frame_data)}フレーム")
        logger.info(f"Data collection completed: {len(self.frame_data)} frames")
    
    def create_comprehensive_animation(self, output_filename: str = "dynamic_generation_animation.gif"):
        """包括的アニメーションの作成"""
        
        logger.info("Creating comprehensive animation...")
        print(f"\n🎨 包括的アニメーション作成中...")
        
        # フィギュアの設定
        fig = plt.figure(figsize=(16, 12))
        fig.suptitle('動的生成プロセス - リアルタイム変化', fontsize=16, fontweight='bold')
        
        # グリッドレイアウト
        gs = GridSpec(3, 3, figure=fig, hspace=0.4, wspace=0.3)
        
        # 1. メイン3D可視化
        ax_main = fig.add_subplot(gs[:2, :2], projection='3d')
        ax_main.set_title('動的生成粒子の3D軌道')
        ax_main.set_xlabel('X')
        ax_main.set_ylabel('Y')
        ax_main.set_zlabel('Z')
        
        grid_size = self.system.grid_size
        ax_main.set_xlim(0, grid_size)
        ax_main.set_ylim(0, grid_size)
        ax_main.set_zlim(0, grid_size)
        
        # 2. 創発場ヒートマップ
        ax_emergence = fig.add_subplot(gs[0, 2])
        ax_emergence.set_title('創発場の時間変化')
        ax_emergence.set_xlabel('X')
        ax_emergence.set_ylabel('Y')
        
        # 3. 現実場ヒートマップ
        ax_reality = fig.add_subplot(gs[1, 2])
        ax_reality.set_title('現実場の進化')
        ax_reality.set_xlabel('X')
        ax_reality.set_ylabel('Y')
        
        # 4. 生成率時系列
        ax_generation = fig.add_subplot(gs[2, 0])
        ax_generation.set_title('平均生成率')
        ax_generation.set_xlabel('時間 [s]')
        ax_generation.set_ylabel('生成率')
        ax_generation.grid(True, alpha=0.3)
        
        # 5. 複雑度時系列
        ax_complexity = fig.add_subplot(gs[2, 1])
        ax_complexity.set_title('平均複雑度')
        ax_complexity.set_xlabel('時間 [s]')
        ax_complexity.set_ylabel('複雑度')
        ax_complexity.grid(True, alpha=0.3)
        
        # 6. 秩序パラメータ
        ax_order = fig.add_subplot(gs[2, 2])
        ax_order.set_title('秩序パラメータ')
        ax_order.set_xlabel('時間 [s]')
        ax_order.set_ylabel('秩序度')
        ax_order.grid(True, alpha=0.3)
        
        # アニメーション更新関数
        def animate(frame_idx):
            # すべての軸をクリア
            ax_main.clear()
            ax_emergence.clear()
            ax_reality.clear()
            
            # 軸の再設定
            ax_main.set_xlim(0, grid_size)
            ax_main.set_ylim(0, grid_size)
            ax_main.set_zlim(0, grid_size)
            ax_main.set_title(f'動的生成粒子 (t={self.frame_data[frame_idx]["time"]:.2f}s)')
            ax_main.set_xlabel('X')
            ax_main.set_ylabel('Y')
            ax_main.set_zlabel('Z')
            
            # 現在のフレームデータ
            data = self.frame_data[frame_idx]
            
            # 1. 3D粒子表示
            positions = data['particle_positions']
            generation_rates = data['particle_generation_rates']
            complexities = data['particle_complexities']
            
            colors = complexities
            sizes = generation_rates * 30 + 10
            
            scatter = ax_main.scatter(
                positions[:, 0], positions[:, 1], positions[:, 2],
                c=colors, s=sizes, cmap='plasma', alpha=0.8
            )
            
            # 軌道の描画（最初の5粒子）
            trail_length = 10
            start_frame = max(0, frame_idx - trail_length)
            
            for particle_idx in range(min(5, len(positions))):
                trail_positions = []
                for f in range(start_frame, frame_idx + 1):
                    if f < len(self.frame_data):
                        trail_positions.append(self.frame_data[f]['particle_positions'][particle_idx])
                
                if len(trail_positions) > 1:
                    trail_array = np.array(trail_positions)
                    ax_main.plot(trail_array[:, 0], trail_array[:, 1], trail_array[:, 2],
                               alpha=0.6, linewidth=2, color=plt.cm.plasma(complexities[particle_idx]/np.max(complexities)))
            
            # 2. 創発場ヒートマップ
            z_center = grid_size // 2
            emergence_slice = data['emergence_field'][:, :, z_center]
            
            ax_emergence.set_title(f'創発場 (t={data["time"]:.2f}s)')
            ax_emergence.set_xlabel('X')
            ax_emergence.set_ylabel('Y')
            
            em_im = ax_emergence.imshow(emergence_slice, cmap='hot', interpolation='bilinear',
                                       vmin=0, vmax=np.max([np.max(d['emergence_field']) for d in self.frame_data]))
            
            # 3. 現実場ヒートマップ
            reality_slice = data['reality_field'][:, :, z_center]
            
            ax_reality.set_title(f'現実場 (t={data["time"]:.2f}s)')
            ax_reality.set_xlabel('X')
            ax_reality.set_ylabel('Y')
            
            re_im = ax_reality.imshow(reality_slice, cmap='viridis', interpolation='bilinear',
                                     vmin=0, vmax=1.0)
            
            # 4. 時系列データの更新
            times = [d['time'] for d in self.frame_data[:frame_idx+1]]
            
            # 生成率
            avg_generation_rates = [np.mean(d['particle_generation_rates']) for d in self.frame_data[:frame_idx+1]]
            ax_generation.clear()
            ax_generation.plot(times, avg_generation_rates, 'b-', linewidth=2, label='平均生成率')
            ax_generation.set_title('平均生成率')
            ax_generation.set_xlabel('時間 [s]')
            ax_generation.set_ylabel('生成率')
            ax_generation.grid(True, alpha=0.3)
            ax_generation.legend()
            
            # 複雑度
            avg_complexities = [np.mean(d['particle_complexities']) for d in self.frame_data[:frame_idx+1]]
            ax_complexity.clear()
            ax_complexity.plot(times, avg_complexities, 'g-', linewidth=2, label='平均複雑度')
            ax_complexity.set_title('平均複雑度')
            ax_complexity.set_xlabel('時間 [s]')
            ax_complexity.set_ylabel('複雑度')
            ax_complexity.grid(True, alpha=0.3)
            ax_complexity.legend()
            
            # 秩序パラメータ
            order_parameters = [d['system_order_parameter'] for d in self.frame_data[:frame_idx+1]]
            ax_order.clear()
            ax_order.plot(times, order_parameters, 'r-', linewidth=2, label='秩序パラメータ')
            ax_order.set_title('秩序パラメータ')
            ax_order.set_xlabel('時間 [s]')
            ax_order.set_ylabel('秩序度')
            ax_order.grid(True, alpha=0.3)
            ax_order.legend()
            
            # 現在時刻の表示
            fig.suptitle(f'動的生成プロセス - リアルタイム変化 (t={data["time"]:.2f}s)', 
                        fontsize=16, fontweight='bold')
        
        # アニメーション作成
        anim = animation.FuncAnimation(
            fig, animate, frames=len(self.frame_data),
            interval=100, blit=False, repeat=True
        )
        
        # GIFとして保存
        print(f"💾 アニメーションを保存中: {output_filename}")
        
        # PIL writer for GIF
        Writer = animation.writers['pillow']
        writer = Writer(fps=10, metadata=dict(artist='Dynamic Generation System'))
        
        anim.save(output_filename, writer=writer)
        
        plt.close(fig)
        
        print(f"✅ アニメーション保存完了: {output_filename}")
        logger.info(f"Animation saved: {output_filename}")
        
        return anim
    
    def create_particle_trajectory_animation(self, output_filename: str = "particle_trajectories.gif"):
        """粒子軌道特化アニメーション"""
        
        logger.info("Creating particle trajectory animation...")
        print(f"\n🎯 粒子軌道アニメーション作成中...")
        
        fig = plt.figure(figsize=(12, 10))
        ax = fig.add_subplot(111, projection='3d')
        
        grid_size = self.system.grid_size
        ax.set_xlim(0, grid_size)
        ax.set_ylim(0, grid_size)
        ax.set_zlim(0, grid_size)
        ax.set_xlabel('X')
        ax.set_ylabel('Y')
        ax.set_zlabel('Z')
        
        def animate_trajectories(frame_idx):
            ax.clear()
            ax.set_xlim(0, grid_size)
            ax.set_ylim(0, grid_size)
            ax.set_zlim(0, grid_size)
            ax.set_xlabel('X')
            ax.set_ylabel('Y')
            ax.set_zlabel('Z')
            
            data = self.frame_data[frame_idx]
            ax.set_title(f'動的生成粒子の軌道 (t={data["time"]:.2f}s)')
            
            # 現在の粒子位置
            positions = data['particle_positions']
            generation_rates = data['particle_generation_rates']
            complexities = data['particle_complexities']
            
            # 粒子の表示
            colors = complexities
            sizes = generation_rates * 50 + 20
            
            ax.scatter(positions[:, 0], positions[:, 1], positions[:, 2],
                      c=colors, s=sizes, cmap='plasma', alpha=0.9)
            
            # 全粒子の軌道を表示
            trail_length = 20
            start_frame = max(0, frame_idx - trail_length)
            
            for particle_idx in range(len(positions)):
                trail_positions = []
                trail_colors = []
                
                for f in range(start_frame, frame_idx + 1):
                    if f < len(self.frame_data):
                        trail_positions.append(self.frame_data[f]['particle_positions'][particle_idx])
                        # 軌道の色を時間で変化させる
                        alpha = (f - start_frame) / max(1, trail_length)
                        trail_colors.append(alpha)
                
                if len(trail_positions) > 1:
                    trail_array = np.array(trail_positions)
                    
                    # 軌道を段階的な色で描画
                    for i in range(len(trail_array) - 1):
                        alpha = trail_colors[i]
                        ax.plot(trail_array[i:i+2, 0], trail_array[i:i+2, 1], trail_array[i:i+2, 2],
                               alpha=alpha, linewidth=1, color=plt.cm.plasma(complexities[particle_idx]/np.max(complexities)))
            
            # 統計情報を表示
            stats_text = f"""粒子数: {len(positions)}
平均生成率: {np.mean(generation_rates):.3f}
平均複雑度: {np.mean(complexities):.3f}
秩序パラメータ: {data['system_order_parameter']:.3f}"""
            
            ax.text2D(0.02, 0.98, stats_text, transform=ax.transAxes, 
                     verticalalignment='top', bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))
        
        # アニメーション作成
        anim = animation.FuncAnimation(
            fig, animate_trajectories, frames=len(self.frame_data),
            interval=150, blit=False, repeat=True
        )
        
        # 保存
        print(f"💾 軌道アニメーションを保存中: {output_filename}")
        Writer = animation.writers['pillow']
        writer = Writer(fps=8, metadata=dict(artist='Particle Trajectory System'))
        anim.save(output_filename, writer=writer)
        
        plt.close(fig)
        
        print(f"✅ 軌道アニメーション保存完了: {output_filename}")
        logger.info(f"Trajectory animation saved: {output_filename}")
        
        return anim
    
    def create_field_evolution_animation(self, output_filename: str = "field_evolution.gif"):
        """場の進化特化アニメーション"""
        
        logger.info("Creating field evolution animation...")
        print(f"\n🌊 場の進化アニメーション作成中...")
        
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(12, 10))
        fig.suptitle('動的生成場の時間進化', fontsize=16, fontweight='bold')
        
        def animate_fields(frame_idx):
            # 軸をクリア
            ax1.clear()
            ax2.clear()
            ax3.clear()
            ax4.clear()
            
            data = self.frame_data[frame_idx]
            z_center = self.system.grid_size // 2
            
            # 1. 創発場
            emergence_slice = data['emergence_field'][:, :, z_center]
            im1 = ax1.imshow(emergence_slice, cmap='hot', interpolation='bilinear')
            ax1.set_title(f'創発場 (t={data["time"]:.2f}s)')
            ax1.set_xlabel('X')
            ax1.set_ylabel('Y')
            
            # 2. 現実場
            reality_slice = data['reality_field'][:, :, z_center]
            im2 = ax2.imshow(reality_slice, cmap='viridis', interpolation='bilinear', vmin=0, vmax=1)
            ax2.set_title(f'現実場 (t={data["time"]:.2f}s)')
            ax2.set_xlabel('X')
            ax2.set_ylabel('Y')
            
            # 3. 複雑度場
            complexity_slice = data['complexity_field'][:, :, z_center]
            im3 = ax3.imshow(complexity_slice, cmap='plasma', interpolation='bilinear')
            ax3.set_title(f'複雑度場 (t={data["time"]:.2f}s)')
            ax3.set_xlabel('X')
            ax3.set_ylabel('Y')
            
            # 4. 統計の時系列
            times = [d['time'] for d in self.frame_data[:frame_idx+1]]
            emergence_maxs = [np.max(d['emergence_field']) for d in self.frame_data[:frame_idx+1]]
            reality_volumes = [np.sum(d['reality_field'] > 0.1) for d in self.frame_data[:frame_idx+1]]
            
            ax4.plot(times, emergence_maxs, 'r-', linewidth=2, label='創発場最大')
            ax4_twin = ax4.twinx()
            ax4_twin.plot(times, reality_volumes, 'b-', linewidth=2, label='現実体積')
            
            ax4.set_title('場統計の時間変化')
            ax4.set_xlabel('時間 [s]')
            ax4.set_ylabel('創発場最大', color='r')
            ax4_twin.set_ylabel('現実体積', color='b')
            ax4.grid(True, alpha=0.3)
            
            # 現在フレームの統計表示
            stats_text = f"""最大創発: {np.max(emergence_slice):.3f}
現実体積: {np.sum(reality_slice > 0.1)}
複雑度最大: {np.max(complexity_slice):.3f}
秩序パラメータ: {data['system_order_parameter']:.3f}"""
            
            fig.text(0.02, 0.02, stats_text, bbox=dict(boxstyle='round', facecolor='lightblue', alpha=0.8))
        
        # アニメーション作成
        anim = animation.FuncAnimation(
            fig, animate_fields, frames=len(self.frame_data),
            interval=120, blit=False, repeat=True
        )
        
        # 保存
        print(f"💾 場進化アニメーションを保存中: {output_filename}")
        Writer = animation.writers['pillow']
        writer = Writer(fps=8, metadata=dict(artist='Field Evolution System'))
        anim.save(output_filename, writer=writer)
        
        plt.close(fig)
        
        print(f"✅ 場進化アニメーション保存完了: {output_filename}")
        logger.info(f"Field evolution animation saved: {output_filename}")
        
        return anim
    
    def generate_summary_report(self):
        """総合レポートの生成"""
        
        print(f"\n📊 動的生成プロセス - 総合レポート")
        print("=" * 60)
        
        # 統計計算
        initial_data = self.frame_data[0]
        final_data = self.frame_data[-1]
        
        # 生成率の変化
        initial_gen_rate = np.mean(initial_data['particle_generation_rates'])
        final_gen_rate = np.mean(final_data['particle_generation_rates'])
        gen_rate_growth = (final_gen_rate - initial_gen_rate) / self.animation_duration
        
        # 複雑度の変化
        initial_complexity = np.mean(initial_data['particle_complexities'])
        final_complexity = np.mean(final_data['particle_complexities'])
        complexity_growth = (final_complexity - initial_complexity) / self.animation_duration
        
        # 現実体積の変化
        initial_reality_volume = np.sum(initial_data['reality_field'] > 0.1)
        final_reality_volume = np.sum(final_data['reality_field'] > 0.1)
        reality_volume_growth = (final_reality_volume - initial_reality_volume) / self.animation_duration
        
        # 秩序パラメータの変化
        initial_order = initial_data['system_order_parameter']
        final_order = final_data['system_order_parameter']
        order_change = final_order - initial_order
        
        print(f"🔬 基本統計:")
        print(f"  アニメーション時間: {self.animation_duration:.1f}秒")
        print(f"  総フレーム数: {len(self.frame_data)}")
        print(f"  粒子数: {len(initial_data['particle_positions'])}")
        
        print(f"\n📈 成長率解析:")
        print(f"  生成率成長: {gen_rate_growth:.3f} /s")
        print(f"  複雑度成長: {complexity_growth:.3f} /s")
        print(f"  現実体積成長: {reality_volume_growth:.1f} /s")
        print(f"  秩序パラメータ変化: {order_change:+.3f}")
        
        print(f"\n🎯 最終状態:")
        print(f"  最終生成率: {final_gen_rate:.3f}")
        print(f"  最終複雑度: {final_complexity:.3f}")
        print(f"  最終現実体積: {final_reality_volume}")
        print(f"  最終秩序度: {final_order:.3f}")
        
        print(f"\n🌟 創発効果:")
        max_emergence = max([np.max(d['emergence_field']) for d in self.frame_data])
        max_reality_density = max([np.max(d['reality_field']) for d in self.frame_data])
        
        print(f"  最大創発強度: {max_emergence:.3f}")
        print(f"  最大現実密度: {max_reality_density:.3f}")
        print(f"  創発ホットスポット: {len(np.where(final_data['emergence_field'] > np.percentile(final_data['emergence_field'], 95))[0])}")
        
        return {
            'animation_duration': self.animation_duration,
            'total_frames': len(self.frame_data),
            'generation_rate_growth': gen_rate_growth,
            'complexity_growth': complexity_growth,
            'reality_volume_growth': reality_volume_growth,
            'order_change': order_change,
            'final_stats': {
                'generation_rate': final_gen_rate,
                'complexity': final_complexity,
                'reality_volume': final_reality_volume,
                'order_parameter': final_order
            }
        }

def create_dynamic_generation_animations():
    """動的生成アニメーションセットの作成"""
    
    print("🎬 動的生成アニメーションシステム")
    print("=" * 60)
    print("特徴: 動的生成プロセスのリアルタイム変化を記録・可視化")
    print("出力: 複数のアニメーションGIF + 総合レポート")
    print("=" * 60)
    
    # システム初期化
    print("\n🔧 動的生成システム初期化中...")
    system = SimpleDynamicSystem(grid_size=16, num_particles=40)  # 軽量化
    
    # アニメーション可視化システム初期化
    print("🎨 アニメーション可視化システム初期化中...")
    animation_duration = 8.0  # 8秒間のアニメーション
    viz_system = AnimatedVisualizationSystem(system, animation_duration)
    
    print("✅ 初期化完了！")
    
    # シミュレーションデータの収集
    viz_system.collect_simulation_data()
    
    # 複数のアニメーション作成
    print(f"\n🎞️ アニメーション作成開始...")
    
    # 1. 包括的アニメーション
    comprehensive_anim = viz_system.create_comprehensive_animation("dynamic_generation_comprehensive.gif")
    
    # 2. 粒子軌道特化アニメーション
    trajectory_anim = viz_system.create_particle_trajectory_animation("dynamic_generation_trajectories.gif")
    
    # 3. 場進化特化アニメーション
    field_anim = viz_system.create_field_evolution_animation("dynamic_generation_fields.gif")
    
    # 4. 総合レポート生成
    report = viz_system.generate_summary_report()
    
    print(f"\n🎉 動的生成アニメーション作成完了！")
    print(f"生成ファイル:")
    print(f"  - dynamic_generation_comprehensive.gif: 包括的可視化")
    print(f"  - dynamic_generation_trajectories.gif: 粒子軌道特化")
    print(f"  - dynamic_generation_fields.gif: 場進化特化")
    
    return viz_system, report

def main():
    """メイン実行関数"""
    
    try:
        # 動的生成アニメーション作成
        viz_system, report = create_dynamic_generation_animations()
        
        print(f"\n💫 動的生成プロセス解析完了")
        print(f"実行時間: {report['animation_duration']}秒")
        print(f"記録フレーム: {report['total_frames']}個")
        
        # ファイルサイズ確認
        if os.path.exists("dynamic_generation_comprehensive.gif"):
            size = os.path.getsize("dynamic_generation_comprehensive.gif") / (1024*1024)
            print(f"メインアニメーションサイズ: {size:.1f}MB")
        
        return viz_system, report
        
    except Exception as e:
        print(f"\n❌ エラーが発生しました: {e}")
        logger.error(f"Animation creation error: {e}")
        return None, None

if __name__ == "__main__":
    viz_system, report = main() 