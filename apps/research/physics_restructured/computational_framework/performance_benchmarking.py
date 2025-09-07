"""
パフォーマンスベンチマーキングシステム (Performance Benchmarking System)
世界最高性能計算機との比較評価フレームワーク

Author: Jun Kawasaki
Date: 2025/01/22
License: MIT

機能:
- 世界最高性能計算機との比較
- FLOPS、メモリ帯域、通信性能の測定
- スケーラビリティ評価
- 効率性指標の計算
- 性能プロファイリング
- 最適化推奨事項の生成
"""

import numpy as np
import scipy as sp
import time
import psutil
import socket
import threading
import multiprocessing as mp
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor
from typing import Dict, List, Tuple, Optional, Union, Any
from dataclasses import dataclass, field
import logging
import json
import matplotlib.pyplot as plt
from mpi4py import MPI
import warnings
warnings.filterwarnings('ignore')

logger = logging.getLogger(__name__)

@dataclass
class BenchmarkConfig:
    """ベンチマーク設定"""
    # 計算性能測定
    flops_test_size: int = 10000
    matrix_sizes: List[int] = field(default_factory=lambda: [100, 1000, 5000, 10000])
    
    # メモリ性能測定
    memory_test_sizes: List[int] = field(default_factory=lambda: [1024, 1024*1024, 1024*1024*1024])
    
    # 通信性能測定
    communication_message_sizes: List[int] = field(default_factory=lambda: [1, 1024, 1024*1024])
    
    # スケーラビリティ測定
    process_counts: List[int] = field(default_factory=lambda: [1, 2, 4, 8, 16, 32, 64, 128])
    
    # 比較対象システム
    reference_systems: Dict[str, Dict] = field(default_factory=lambda: {
        'fugaku': {
            'peak_flops': 537.2e15,  # 537.2 PFlops
            'nodes': 158976,
            'cores': 158976 * 48,
            'memory': 158976 * 32,  # GB
            'interconnect': 'TofuD'
        },
        'summit': {
            'peak_flops': 200.0e15,  # 200 PFlops
            'nodes': 4608,
            'cores': 4608 * 44,
            'memory': 4608 * 512,  # GB
            'interconnect': 'InfiniBand'
        },
        'sierra': {
            'peak_flops': 125.0e15,  # 125 PFlops
            'nodes': 4320,
            'cores': 4320 * 44,
            'memory': 4320 * 256,  # GB
            'interconnect': 'InfiniBand'
        },
        'sunway': {
            'peak_flops': 125.4e15,  # 125.4 PFlops
            'nodes': 40960,
            'cores': 40960 * 260,
            'memory': 40960 * 32,  # GB
            'interconnect': 'Sunway Network'
        }
    })

class SystemProfiler:
    """システムプロファイラー"""
    
    def __init__(self):
        self.system_info = self.collect_system_info()
        
    def collect_system_info(self) -> Dict[str, Any]:
        """システム情報の収集"""
        return {
            'hostname': socket.gethostname(),
            'cpu_count': psutil.cpu_count(logical=False),
            'cpu_count_logical': psutil.cpu_count(logical=True),
            'memory_total': psutil.virtual_memory().total,
            'memory_available': psutil.virtual_memory().available,
            'disk_usage': psutil.disk_usage('/').total,
            'network_interfaces': list(psutil.net_if_addrs().keys()),
            'boot_time': psutil.boot_time(),
            'python_version': f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
        }
    
    def get_cpu_info(self) -> Dict[str, Any]:
        """CPU情報の取得"""
        try:
            import cpuinfo
            info = cpuinfo.get_cpu_info()
            return {
                'brand': info.get('brand_raw', 'Unknown'),
                'arch': info.get('arch', 'Unknown'),
                'frequency': info.get('hz_advertised_raw', 0),
                'cache_size': info.get('l3_cache_size', 0),
                'features': info.get('flags', [])
            }
        except ImportError:
            return {'brand': 'Unknown', 'arch': 'Unknown'}
    
    def get_gpu_info(self) -> List[Dict[str, Any]]:
        """GPU情報の取得"""
        try:
            import cupy as cp
            gpu_info = []
            
            for i in range(cp.cuda.runtime.getDeviceCount()):
                with cp.cuda.Device(i):
                    props = cp.cuda.runtime.getDeviceProperties(i)
                    memory_info = cp.cuda.runtime.memGetInfo()
                    
                    gpu_info.append({
                        'device_id': i,
                        'name': props['name'].decode('utf-8'),
                        'compute_capability': f"{props['major']}.{props['minor']}",
                        'memory_total': memory_info[1],
                        'memory_free': memory_info[0],
                        'multiprocessors': props['multiProcessorCount'],
                        'max_threads_per_block': props['maxThreadsPerBlock']
                    })
            
            return gpu_info
        except:
            return []

class FLOPSBenchmark:
    """FLOPS性能測定"""
    
    def __init__(self, config: BenchmarkConfig):
        self.config = config
        self.results = {}
    
    def run_flops_benchmark(self) -> Dict[str, float]:
        """FLOPS ベンチマークの実行"""
        results = {}
        
        # 単精度浮動小数点演算
        results['single_precision'] = self.measure_single_precision_flops()
        
        # 倍精度浮動小数点演算
        results['double_precision'] = self.measure_double_precision_flops()
        
        # 行列乗算
        results['matrix_multiply'] = self.measure_matrix_multiply_flops()
        
        # FFT
        results['fft'] = self.measure_fft_flops()
        
        self.results = results
        return results
    
    def measure_single_precision_flops(self) -> float:
        """単精度FLOPS測定"""
        size = self.config.flops_test_size
        a = np.random.random((size, size)).astype(np.float32)
        b = np.random.random((size, size)).astype(np.float32)
        
        start_time = time.time()
        c = np.dot(a, b)
        end_time = time.time()
        
        operations = 2 * size**3  # 行列乗算の演算数
        time_taken = end_time - start_time
        
        return operations / time_taken
    
    def measure_double_precision_flops(self) -> float:
        """倍精度FLOPS測定"""
        size = self.config.flops_test_size
        a = np.random.random((size, size)).astype(np.float64)
        b = np.random.random((size, size)).astype(np.float64)
        
        start_time = time.time()
        c = np.dot(a, b)
        end_time = time.time()
        
        operations = 2 * size**3
        time_taken = end_time - start_time
        
        return operations / time_taken
    
    def measure_matrix_multiply_flops(self) -> Dict[str, float]:
        """行列乗算FLOPS測定"""
        results = {}
        
        for size in self.config.matrix_sizes:
            a = np.random.random((size, size))
            b = np.random.random((size, size))
            
            start_time = time.time()
            c = np.dot(a, b)
            end_time = time.time()
            
            operations = 2 * size**3
            time_taken = end_time - start_time
            flops = operations / time_taken
            
            results[f'size_{size}'] = flops
        
        return results
    
    def measure_fft_flops(self) -> float:
        """FFT FLOPS測定"""
        size = self.config.flops_test_size
        data = np.random.random(size * size) + 1j * np.random.random(size * size)
        
        start_time = time.time()
        fft_result = np.fft.fft(data)
        end_time = time.time()
        
        # FFTの演算数の近似
        operations = 5 * size * size * np.log2(size * size)
        time_taken = end_time - start_time
        
        return operations / time_taken

class MemoryBenchmark:
    """メモリ性能測定"""
    
    def __init__(self, config: BenchmarkConfig):
        self.config = config
        self.results = {}
    
    def run_memory_benchmark(self) -> Dict[str, float]:
        """メモリベンチマークの実行"""
        results = {}
        
        # メモリ帯域測定
        results['bandwidth'] = self.measure_memory_bandwidth()
        
        # メモリレイテンシ測定
        results['latency'] = self.measure_memory_latency()
        
        # キャッシュ性能測定
        results['cache_performance'] = self.measure_cache_performance()
        
        self.results = results
        return results
    
    def measure_memory_bandwidth(self) -> Dict[str, float]:
        """メモリ帯域測定"""
        results = {}
        
        for size in self.config.memory_test_sizes:
            # 読み込み帯域
            data = np.random.random(size)
            
            start_time = time.time()
            sum_result = np.sum(data)
            end_time = time.time()
            
            time_taken = end_time - start_time
            bandwidth = (data.nbytes) / time_taken
            results[f'read_bandwidth_{size}'] = bandwidth
            
            # 書き込み帯域
            output = np.zeros(size)
            
            start_time = time.time()
            output[:] = data
            end_time = time.time()
            
            time_taken = end_time - start_time
            bandwidth = (data.nbytes) / time_taken
            results[f'write_bandwidth_{size}'] = bandwidth
        
        return results
    
    def measure_memory_latency(self) -> float:
        """メモリレイテンシ測定"""
        size = 1024 * 1024  # 1MB
        data = np.random.random(size)
        indices = np.random.randint(0, size, 10000)
        
        start_time = time.time()
        for i in indices:
            value = data[i]
        end_time = time.time()
        
        time_taken = end_time - start_time
        latency = time_taken / len(indices)
        
        return latency
    
    def measure_cache_performance(self) -> Dict[str, float]:
        """キャッシュ性能測定"""
        results = {}
        
        # L1キャッシュテスト
        size = 32 * 1024  # 32KB
        data = np.random.random(size // 8)  # 8バイト/要素
        
        start_time = time.time()
        for _ in range(1000):
            sum_result = np.sum(data)
        end_time = time.time()
        
        results['l1_cache_time'] = (end_time - start_time) / 1000
        
        # L3キャッシュテスト
        size = 8 * 1024 * 1024  # 8MB
        data = np.random.random(size // 8)
        
        start_time = time.time()
        for _ in range(100):
            sum_result = np.sum(data)
        end_time = time.time()
        
        results['l3_cache_time'] = (end_time - start_time) / 100
        
        return results

class CommunicationBenchmark:
    """通信性能測定"""
    
    def __init__(self, config: BenchmarkConfig):
        self.config = config
        self.results = {}
        
        # MPI初期化
        self.comm = MPI.COMM_WORLD
        self.rank = self.comm.Get_rank()
        self.size = self.comm.Get_size()
    
    def run_communication_benchmark(self) -> Dict[str, float]:
        """通信ベンチマークの実行"""
        results = {}
        
        if self.size > 1:
            # 点対点通信
            results['point_to_point'] = self.measure_point_to_point()
            
            # 集団通信
            results['collective'] = self.measure_collective_communication()
            
            # 全対全通信
            results['all_to_all'] = self.measure_all_to_all()
        
        self.results = results
        return results
    
    def measure_point_to_point(self) -> Dict[str, float]:
        """点対点通信測定"""
        results = {}
        
        if self.rank == 0:
            # 送信側
            for size in self.config.communication_message_sizes:
                data = np.random.random(size)
                
                start_time = time.time()
                for _ in range(100):
                    self.comm.send(data, dest=1)
                    self.comm.recv(source=1)
                end_time = time.time()
                
                time_taken = (end_time - start_time) / 100
                bandwidth = (data.nbytes * 2) / time_taken  # 双方向
                results[f'bandwidth_{size}'] = bandwidth
                results[f'latency_{size}'] = time_taken / 2
        
        elif self.rank == 1:
            # 受信側
            for size in self.config.communication_message_sizes:
                for _ in range(100):
                    data = self.comm.recv(source=0)
                    self.comm.send(data, dest=0)
        
        return results
    
    def measure_collective_communication(self) -> Dict[str, float]:
        """集団通信測定"""
        results = {}
        
        for size in self.config.communication_message_sizes:
            data = np.random.random(size)
            
            # Broadcast
            start_time = time.time()
            for _ in range(100):
                self.comm.bcast(data, root=0)
            end_time = time.time()
            
            time_taken = (end_time - start_time) / 100
            results[f'broadcast_time_{size}'] = time_taken
            
            # Reduce
            start_time = time.time()
            for _ in range(100):
                result = self.comm.reduce(data, op=MPI.SUM, root=0)
            end_time = time.time()
            
            time_taken = (end_time - start_time) / 100
            results[f'reduce_time_{size}'] = time_taken
        
        return results
    
    def measure_all_to_all(self) -> Dict[str, float]:
        """全対全通信測定"""
        results = {}
        
        for size in self.config.communication_message_sizes:
            send_data = np.random.random((self.size, size))
            recv_data = np.zeros((self.size, size))
            
            start_time = time.time()
            for _ in range(10):
                self.comm.Alltoall(send_data, recv_data)
            end_time = time.time()
            
            time_taken = (end_time - start_time) / 10
            results[f'alltoall_time_{size}'] = time_taken
        
        return results

class ScalabilityBenchmark:
    """スケーラビリティ測定"""
    
    def __init__(self, config: BenchmarkConfig):
        self.config = config
        self.results = {}
    
    def run_scalability_benchmark(self) -> Dict[str, float]:
        """スケーラビリティベンチマークの実行"""
        results = {}
        
        # 強スケーラビリティ
        results['strong_scaling'] = self.measure_strong_scaling()
        
        # 弱スケーラビリティ
        results['weak_scaling'] = self.measure_weak_scaling()
        
        # 効率性指標
        results['efficiency'] = self.calculate_efficiency()
        
        self.results = results
        return results
    
    def measure_strong_scaling(self) -> Dict[str, float]:
        """強スケーラビリティ測定"""
        results = {}
        
        # 固定問題サイズでプロセス数を変更
        problem_size = 10000
        
        for num_processes in self.config.process_counts:
            if num_processes <= mp.cpu_count():
                execution_time = self.run_parallel_computation(problem_size, num_processes)
                results[f'processes_{num_processes}'] = execution_time
        
        return results
    
    def measure_weak_scaling(self) -> Dict[str, float]:
        """弱スケーラビリティ測定"""
        results = {}
        
        # プロセス数に比例した問題サイズ
        base_problem_size = 1000
        
        for num_processes in self.config.process_counts:
            if num_processes <= mp.cpu_count():
                problem_size = base_problem_size * num_processes
                execution_time = self.run_parallel_computation(problem_size, num_processes)
                results[f'processes_{num_processes}'] = execution_time
        
        return results
    
    def run_parallel_computation(self, problem_size: int, num_processes: int) -> float:
        """並列計算の実行"""
        def compute_task(size):
            data = np.random.random((size, size))
            result = np.linalg.eigvals(data)
            return result
        
        chunk_size = problem_size // num_processes
        
        start_time = time.time()
        
        with ProcessPoolExecutor(max_workers=num_processes) as executor:
            futures = [executor.submit(compute_task, chunk_size) for _ in range(num_processes)]
            results = [future.result() for future in futures]
        
        end_time = time.time()
        
        return end_time - start_time
    
    def calculate_efficiency(self) -> Dict[str, float]:
        """効率性の計算"""
        strong_scaling = self.results.get('strong_scaling', {})
        
        if not strong_scaling:
            return {}
        
        # 1プロセス実行時間を基準とする
        base_time = strong_scaling.get('processes_1', 0)
        
        if base_time == 0:
            return {}
        
        efficiency = {}
        
        for key, execution_time in strong_scaling.items():
            if key.startswith('processes_'):
                num_processes = int(key.split('_')[1])
                theoretical_time = base_time / num_processes
                actual_efficiency = theoretical_time / execution_time
                efficiency[key] = actual_efficiency
        
        return efficiency

class PerformanceComparator:
    """性能比較システム"""
    
    def __init__(self, config: BenchmarkConfig):
        self.config = config
        self.system_profiler = SystemProfiler()
    
    def compare_with_reference_systems(self, benchmark_results: Dict[str, Any]) -> Dict[str, Any]:
        """参照システムとの比較"""
        comparison_results = {}
        
        # 現在のシステムの推定ピーク性能
        current_peak_flops = self.estimate_peak_flops()
        
        # 参照システムとの比較
        for system_name, system_specs in self.config.reference_systems.items():
            comparison = self.compare_with_system(
                benchmark_results, 
                system_specs, 
                current_peak_flops
            )
            comparison_results[system_name] = comparison
        
        return comparison_results
    
    def estimate_peak_flops(self) -> float:
        """ピーク性能の推定"""
        cpu_info = self.system_profiler.get_cpu_info()
        system_info = self.system_profiler.system_info
        
        # 簡略化された推定
        cpu_count = system_info['cpu_count']
        frequency = cpu_info.get('frequency', 2.5e9)  # 2.5GHz as default
        
        # 1クロックあたり2演算と仮定
        peak_flops = cpu_count * frequency * 2
        
        return peak_flops
    
    def compare_with_system(self, benchmark_results: Dict[str, Any], 
                           system_specs: Dict[str, Any], 
                           current_peak_flops: float) -> Dict[str, Any]:
        """特定システムとの比較"""
        comparison = {}
        
        # FLOPS比較
        reference_peak_flops = system_specs['peak_flops']
        flops_ratio = current_peak_flops / reference_peak_flops
        comparison['flops_ratio'] = flops_ratio
        
        # メモリ比較
        current_memory = self.system_profiler.system_info['memory_total']
        reference_memory = system_specs['memory'] * 1024**3  # GB to bytes
        memory_ratio = current_memory / reference_memory
        comparison['memory_ratio'] = memory_ratio
        
        # ノード比較
        current_nodes = 1  # 単一ノードと仮定
        reference_nodes = system_specs['nodes']
        node_ratio = current_nodes / reference_nodes
        comparison['node_ratio'] = node_ratio
        
        # 効率性比較
        if 'flops' in benchmark_results:
            actual_flops = benchmark_results['flops'].get('double_precision', 0)
            efficiency = actual_flops / current_peak_flops
            comparison['efficiency'] = efficiency
        
        return comparison
    
    def generate_performance_report(self, benchmark_results: Dict[str, Any], 
                                   comparison_results: Dict[str, Any]) -> str:
        """性能レポートの生成"""
        report = []
        
        report.append("=== Performance Benchmarking Report ===\n")
        
        # システム情報
        report.append("System Information:")
        system_info = self.system_profiler.system_info
        report.append(f"  Hostname: {system_info['hostname']}")
        report.append(f"  CPU Cores: {system_info['cpu_count']} physical, {system_info['cpu_count_logical']} logical")
        report.append(f"  Memory: {system_info['memory_total'] / 1024**3:.1f} GB")
        report.append("")
        
        # FLOPS結果
        if 'flops' in benchmark_results:
            report.append("FLOPS Performance:")
            flops_results = benchmark_results['flops']
            if 'single_precision' in flops_results:
                report.append(f"  Single Precision: {flops_results['single_precision']:.2e} FLOPS")
            if 'double_precision' in flops_results:
                report.append(f"  Double Precision: {flops_results['double_precision']:.2e} FLOPS")
            report.append("")
        
        # メモリ結果
        if 'memory' in benchmark_results:
            report.append("Memory Performance:")
            memory_results = benchmark_results['memory']
            if 'bandwidth' in memory_results:
                for key, value in memory_results['bandwidth'].items():
                    report.append(f"  {key}: {value / 1024**3:.2f} GB/s")
            report.append("")
        
        # 比較結果
        report.append("Comparison with Reference Systems:")
        for system_name, comparison in comparison_results.items():
            report.append(f"  {system_name.upper()}:")
            report.append(f"    FLOPS Ratio: {comparison['flops_ratio']:.6f}")
            report.append(f"    Memory Ratio: {comparison['memory_ratio']:.6f}")
            report.append(f"    Node Ratio: {comparison['node_ratio']:.6f}")
            if 'efficiency' in comparison:
                report.append(f"    Efficiency: {comparison['efficiency']:.3f}")
            report.append("")
        
        return "\n".join(report)

class OptimizationRecommender:
    """最適化推奨システム"""
    
    def __init__(self):
        self.recommendations = []
    
    def analyze_benchmark_results(self, benchmark_results: Dict[str, Any]) -> List[str]:
        """ベンチマーク結果の分析と推奨事項の生成"""
        recommendations = []
        
        # FLOPS分析
        if 'flops' in benchmark_results:
            flops_recommendations = self.analyze_flops_performance(benchmark_results['flops'])
            recommendations.extend(flops_recommendations)
        
        # メモリ分析
        if 'memory' in benchmark_results:
            memory_recommendations = self.analyze_memory_performance(benchmark_results['memory'])
            recommendations.extend(memory_recommendations)
        
        # 通信分析
        if 'communication' in benchmark_results:
            comm_recommendations = self.analyze_communication_performance(benchmark_results['communication'])
            recommendations.extend(comm_recommendations)
        
        # スケーラビリティ分析
        if 'scalability' in benchmark_results:
            scalability_recommendations = self.analyze_scalability_performance(benchmark_results['scalability'])
            recommendations.extend(scalability_recommendations)
        
        self.recommendations = recommendations
        return recommendations
    
    def analyze_flops_performance(self, flops_results: Dict[str, Any]) -> List[str]:
        """FLOPS性能の分析"""
        recommendations = []
        
        if 'single_precision' in flops_results and 'double_precision' in flops_results:
            sp_flops = flops_results['single_precision']
            dp_flops = flops_results['double_precision']
            
            if sp_flops / dp_flops < 1.5:
                recommendations.append("Consider using single precision for better performance where precision allows")
        
        if 'matrix_multiply' in flops_results:
            mm_results = flops_results['matrix_multiply']
            performance_trend = self.analyze_performance_trend(mm_results)
            
            if performance_trend == 'decreasing':
                recommendations.append("Matrix multiplication performance decreases with size - consider blocking or tiling")
        
        return recommendations
    
    def analyze_memory_performance(self, memory_results: Dict[str, Any]) -> List[str]:
        """メモリ性能の分析"""
        recommendations = []
        
        if 'bandwidth' in memory_results:
            bandwidth_results = memory_results['bandwidth']
            
            # 読み書き性能の比較
            read_bandwidths = {k: v for k, v in bandwidth_results.items() if 'read' in k}
            write_bandwidths = {k: v for k, v in bandwidth_results.items() if 'write' in k}
            
            if read_bandwidths and write_bandwidths:
                avg_read = np.mean(list(read_bandwidths.values()))
                avg_write = np.mean(list(write_bandwidths.values()))
                
                if avg_read > avg_write * 1.5:
                    recommendations.append("Write bandwidth is significantly lower than read - consider write optimization")
        
        if 'cache_performance' in memory_results:
            cache_results = memory_results['cache_performance']
            
            if 'l1_cache_time' in cache_results and 'l3_cache_time' in cache_results:
                l1_time = cache_results['l1_cache_time']
                l3_time = cache_results['l3_cache_time']
                
                if l3_time / l1_time > 10:
                    recommendations.append("High L3 cache latency - optimize data locality")
        
        return recommendations
    
    def analyze_communication_performance(self, comm_results: Dict[str, Any]) -> List[str]:
        """通信性能の分析"""
        recommendations = []
        
        if 'point_to_point' in comm_results:
            p2p_results = comm_results['point_to_point']
            
            # レイテンシとバンド幅の分析
            latencies = {k: v for k, v in p2p_results.items() if 'latency' in k}
            bandwidths = {k: v for k, v in p2p_results.items() if 'bandwidth' in k}
            
            if latencies:
                avg_latency = np.mean(list(latencies.values()))
                if avg_latency > 1e-3:  # 1ms以上
                    recommendations.append("High communication latency - consider message aggregation")
        
        return recommendations
    
    def analyze_scalability_performance(self, scalability_results: Dict[str, Any]) -> List[str]:
        """スケーラビリティ性能の分析"""
        recommendations = []
        
        if 'efficiency' in scalability_results:
            efficiency_results = scalability_results['efficiency']
            
            efficiencies = list(efficiency_results.values())
            if efficiencies:
                min_efficiency = min(efficiencies)
                if min_efficiency < 0.7:  # 70%以下
                    recommendations.append("Poor parallel efficiency - investigate load imbalance or communication overhead")
        
        return recommendations
    
    def analyze_performance_trend(self, performance_data: Dict[str, float]) -> str:
        """性能トレンドの分析"""
        values = list(performance_data.values())
        
        if len(values) < 2:
            return 'insufficient_data'
        
        # 線形回帰による傾向分析
        x = np.arange(len(values))
        slope = np.polyfit(x, values, 1)[0]
        
        if slope > 0:
            return 'increasing'
        elif slope < 0:
            return 'decreasing'
        else:
            return 'stable'

class PerformanceBenchmarkRunner:
    """ベンチマーク実行統合システム"""
    
    def __init__(self, config: BenchmarkConfig):
        self.config = config
        self.system_profiler = SystemProfiler()
        self.performance_comparator = PerformanceComparator(config)
        self.optimization_recommender = OptimizationRecommender()
    
    def run_full_benchmark(self) -> Dict[str, Any]:
        """完全ベンチマークの実行"""
        logger.info("Starting full performance benchmark...")
        
        results = {}
        
        # FLOPS測定
        logger.info("Running FLOPS benchmark...")
        flops_benchmark = FLOPSBenchmark(self.config)
        results['flops'] = flops_benchmark.run_flops_benchmark()
        
        # メモリ測定
        logger.info("Running memory benchmark...")
        memory_benchmark = MemoryBenchmark(self.config)
        results['memory'] = memory_benchmark.run_memory_benchmark()
        
        # 通信測定
        logger.info("Running communication benchmark...")
        comm_benchmark = CommunicationBenchmark(self.config)
        results['communication'] = comm_benchmark.run_communication_benchmark()
        
        # スケーラビリティ測定
        logger.info("Running scalability benchmark...")
        scalability_benchmark = ScalabilityBenchmark(self.config)
        results['scalability'] = scalability_benchmark.run_scalability_benchmark()
        
        # 参照システムとの比較
        logger.info("Comparing with reference systems...")
        comparison_results = self.performance_comparator.compare_with_reference_systems(results)
        
        # 最適化推奨事項の生成
        logger.info("Generating optimization recommendations...")
        recommendations = self.optimization_recommender.analyze_benchmark_results(results)
        
        # 最終結果の構築
        final_results = {
            'system_info': self.system_profiler.system_info,
            'benchmark_results': results,
            'comparison_results': comparison_results,
            'recommendations': recommendations,
            'timestamp': time.time()
        }
        
        logger.info("Benchmark completed successfully")
        return final_results
    
    def save_results(self, results: Dict[str, Any], filename: str = 'benchmark_results.json'):
        """結果の保存"""
        with open(filename, 'w') as f:
            json.dump(results, f, indent=2, default=str)
        
        logger.info(f"Results saved to {filename}")
    
    def generate_report(self, results: Dict[str, Any]) -> str:
        """レポートの生成"""
        report = self.performance_comparator.generate_performance_report(
            results['benchmark_results'], 
            results['comparison_results']
        )
        
        # 推奨事項の追加
        report += "\nOptimization Recommendations:\n"
        for i, recommendation in enumerate(results['recommendations'], 1):
            report += f"  {i}. {recommendation}\n"
        
        return report

def main():
    """メイン実行関数"""
    # 設定
    config = BenchmarkConfig()
    
    # ベンチマーク実行
    benchmark_runner = PerformanceBenchmarkRunner(config)
    results = benchmark_runner.run_full_benchmark()
    
    # 結果の保存
    benchmark_runner.save_results(results)
    
    # レポートの生成と表示
    report = benchmark_runner.generate_report(results)
    print(report)
    
    # 結果の可視化
    visualize_results(results)

def visualize_results(results: Dict[str, Any]):
    """結果の可視化"""
    benchmark_results = results['benchmark_results']
    
    # FLOPS性能の可視化
    if 'flops' in benchmark_results and 'matrix_multiply' in benchmark_results['flops']:
        plt.figure(figsize=(10, 6))
        
        mm_results = benchmark_results['flops']['matrix_multiply']
        sizes = [int(k.split('_')[1]) for k in mm_results.keys()]
        flops = list(mm_results.values())
        
        plt.loglog(sizes, flops, 'bo-', linewidth=2, markersize=8)
        plt.xlabel('Matrix Size')
        plt.ylabel('FLOPS')
        plt.title('Matrix Multiplication Performance')
        plt.grid(True, alpha=0.3)
        plt.tight_layout()
        plt.savefig('flops_performance.png', dpi=300)
        plt.show()
    
    # 比較結果の可視化
    if 'comparison_results' in results:
        comparison_data = results['comparison_results']
        
        plt.figure(figsize=(12, 8))
        
        systems = list(comparison_data.keys())
        flops_ratios = [comparison_data[sys]['flops_ratio'] for sys in systems]
        memory_ratios = [comparison_data[sys]['memory_ratio'] for sys in systems]
        
        x = np.arange(len(systems))
        width = 0.35
        
        plt.bar(x - width/2, flops_ratios, width, label='FLOPS Ratio', alpha=0.7)
        plt.bar(x + width/2, memory_ratios, width, label='Memory Ratio', alpha=0.7)
        
        plt.xlabel('Reference Systems')
        plt.ylabel('Ratio (Current System / Reference)')
        plt.title('Performance Comparison with Reference Systems')
        plt.xticks(x, systems)
        plt.legend()
        plt.yscale('log')
        plt.grid(True, alpha=0.3)
        plt.tight_layout()
        plt.savefig('performance_comparison.png', dpi=300)
        plt.show()

if __name__ == "__main__":
    main() 