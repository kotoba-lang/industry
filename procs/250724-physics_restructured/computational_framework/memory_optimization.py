"""
メモリ最適化システム (Memory Optimization System)
ペタバイト級データの最適管理とアクセスフレームワーク

Author: Jun Kawasaki
Date: 2025/01/22
License: MIT

機能:
- 階層型メモリ管理
- 動的メモリ割り当て最適化
- キャッシュ最適化
- データ圧縮・展開
- 分散メモリ管理
- ガベージコレクション最適化
- メモリプール管理
"""

import numpy as np
import scipy as sp
import h5py
import zarr
import dask.array as da
import mmap
import os
import sys
import psutil
import gc
import weakref
from typing import Dict, List, Tuple, Optional, Union, Any
from dataclasses import dataclass, field
from concurrent.futures import ThreadPoolExecutor
import threading
import time
import logging
import pickle
import lz4.frame
import zstd
import blosc
import warnings
warnings.filterwarnings('ignore')

logger = logging.getLogger(__name__)

@dataclass
class MemoryConfig:
    """メモリ最適化設定"""
    # 階層型メモリ設定
    ram_size: float = 1024.0  # GB
    storage_size: float = 1000000.0  # 1PB
    cache_size: float = 128.0  # GB
    
    # 圧縮設定
    compression_enabled: bool = True
    compression_algorithm: str = 'lz4'  # lz4, zstd, blosc
    compression_level: int = 3
    
    # メモリプール設定
    memory_pool_enabled: bool = True
    pool_size: float = 512.0  # GB
    pool_growth_factor: float = 1.5
    
    # キャッシュ設定
    cache_policy: str = 'lru'  # lru, lfu, arc
    cache_write_policy: str = 'write_back'  # write_back, write_through
    
    # 分散メモリ設定
    distributed_memory: bool = True
    replication_factor: int = 2
    
    # ガベージコレクション設定
    gc_optimization: bool = True
    gc_threshold: float = 0.8  # メモリ使用率の閾値
    
    # 性能監視
    memory_monitoring: bool = True
    profiling_enabled: bool = True

class MemoryHierarchy:
    """階層型メモリ管理"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        
        # メモリ階層の定義
        self.memory_layers = {
            'l1_cache': {'size': 32e3, 'latency': 1e-9, 'bandwidth': 1e12},  # 32KB, 1ns, 1TB/s
            'l2_cache': {'size': 256e3, 'latency': 3e-9, 'bandwidth': 5e11},  # 256KB, 3ns, 500GB/s
            'l3_cache': {'size': 8e6, 'latency': 10e-9, 'bandwidth': 1e11},  # 8MB, 10ns, 100GB/s
            'ram': {'size': config.ram_size * 1e9, 'latency': 100e-9, 'bandwidth': 1e11},  # RAM
            'ssd': {'size': 10e12, 'latency': 100e-6, 'bandwidth': 1e9},  # 10TB SSD
            'hdd': {'size': config.storage_size, 'latency': 10e-3, 'bandwidth': 1e8}  # HDD
        }
        
        # 階層間のデータ移動管理
        self.data_migration = DataMigrationManager(self.memory_layers)
        
        # メモリ使用量の追跡
        self.memory_usage = {layer: 0 for layer in self.memory_layers.keys()}
        
        logger.info("Memory hierarchy initialized")
    
    def allocate_memory(self, size: int, access_pattern: str = 'random') -> str:
        """メモリの割り当て"""
        # 最適な階層の選択
        optimal_layer = self.select_optimal_layer(size, access_pattern)
        
        # メモリ割り当て
        if self.can_allocate(optimal_layer, size):
            self.memory_usage[optimal_layer] += size
            return optimal_layer
        else:
            # 階層を下げて再試行
            return self.allocate_with_migration(size, access_pattern)
    
    def select_optimal_layer(self, size: int, access_pattern: str) -> str:
        """最適な階層の選択"""
        # アクセスパターンに基づく階層選択
        if access_pattern == 'sequential' and size > 1e9:  # 1GB以上のシーケンシャル
            return 'ssd'
        elif access_pattern == 'random' and size < 1e6:  # 1MB以下のランダム
            return 'ram'
        elif size < 1e8:  # 100MB以下
            return 'ram'
        else:
            return 'ssd'
    
    def can_allocate(self, layer: str, size: int) -> bool:
        """割り当て可能性の確認"""
        available = self.memory_layers[layer]['size'] - self.memory_usage[layer]
        return available >= size
    
    def allocate_with_migration(self, size: int, access_pattern: str) -> str:
        """データ移動を伴う割り当て"""
        # 利用可能な階層を探す
        for layer in ['ram', 'ssd', 'hdd']:
            if self.can_allocate(layer, size):
                # データ移動の実行
                self.data_migration.migrate_cold_data(layer, size)
                self.memory_usage[layer] += size
                return layer
        
        raise MemoryError("Cannot allocate memory in any layer")

class DataMigrationManager:
    """データ移行管理"""
    
    def __init__(self, memory_layers: Dict[str, Dict]):
        self.memory_layers = memory_layers
        self.migration_history = []
        self.hot_data_tracker = HotDataTracker()
    
    def migrate_cold_data(self, target_layer: str, required_size: int):
        """コールドデータの移行"""
        # ホットデータの特定
        cold_data = self.hot_data_tracker.identify_cold_data(required_size)
        
        # 移行先の決定
        destination_layer = self.select_migration_destination(target_layer)
        
        # データ移行の実行
        self.execute_migration(cold_data, destination_layer)
    
    def select_migration_destination(self, source_layer: str) -> str:
        """移行先の選択"""
        layer_hierarchy = ['l1_cache', 'l2_cache', 'l3_cache', 'ram', 'ssd', 'hdd']
        source_index = layer_hierarchy.index(source_layer)
        
        # より下位の階層を選択
        for i in range(source_index + 1, len(layer_hierarchy)):
            layer = layer_hierarchy[i]
            if self.memory_layers[layer]['size'] > 0:
                return layer
        
        return 'hdd'  # 最後の手段
    
    def execute_migration(self, data_blocks: List[Dict], destination: str):
        """データ移行の実行"""
        for block in data_blocks:
            # 非同期移行の実行
            self.async_migrate_block(block, destination)
    
    def async_migrate_block(self, block: Dict, destination: str):
        """非同期ブロック移行"""
        # 実際の移行処理（簡略化）
        migration_record = {
            'block_id': block['id'],
            'source': block['layer'],
            'destination': destination,
            'timestamp': time.time(),
            'size': block['size']
        }
        
        self.migration_history.append(migration_record)

class HotDataTracker:
    """ホットデータ追跡システム"""
    
    def __init__(self):
        self.access_history = {}
        self.data_temperature = {}  # ホット/コールドの指標
        
    def record_access(self, data_id: str, access_type: str):
        """アクセス記録"""
        current_time = time.time()
        
        if data_id not in self.access_history:
            self.access_history[data_id] = []
        
        self.access_history[data_id].append({
            'timestamp': current_time,
            'access_type': access_type
        })
        
        # 古いアクセス記録の削除
        self.cleanup_old_records(data_id, current_time)
        
        # 温度の更新
        self.update_temperature(data_id)
    
    def cleanup_old_records(self, data_id: str, current_time: float):
        """古いレコードのクリーンアップ"""
        cutoff_time = current_time - 3600  # 1時間前
        
        self.access_history[data_id] = [
            record for record in self.access_history[data_id]
            if record['timestamp'] > cutoff_time
        ]
    
    def update_temperature(self, data_id: str):
        """データ温度の更新"""
        if data_id not in self.access_history:
            return
        
        recent_accesses = len(self.access_history[data_id])
        
        # 温度の計算（アクセス頻度に基づく）
        if recent_accesses > 10:
            temperature = 'hot'
        elif recent_accesses > 3:
            temperature = 'warm'
        else:
            temperature = 'cold'
        
        self.data_temperature[data_id] = temperature
    
    def identify_cold_data(self, required_size: int) -> List[Dict]:
        """コールドデータの特定"""
        cold_data = []
        current_size = 0
        
        for data_id, temperature in self.data_temperature.items():
            if temperature == 'cold' and current_size < required_size:
                cold_data.append({
                    'id': data_id,
                    'temperature': temperature,
                    'size': self.estimate_data_size(data_id),
                    'layer': self.get_current_layer(data_id)
                })
                current_size += cold_data[-1]['size']
        
        return cold_data
    
    def estimate_data_size(self, data_id: str) -> int:
        """データサイズの推定"""
        # 実際の実装では、データカタログから取得
        return 1024 * 1024  # 1MB as default
    
    def get_current_layer(self, data_id: str) -> str:
        """現在の階層の取得"""
        # 実際の実装では、メモリマネージャーから取得
        return 'ram'

class CompressionManager:
    """データ圧縮管理"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        self.compression_stats = {}
        
        # 圧縮アルゴリズムの設定
        self.compressors = {
            'lz4': self.lz4_compress,
            'zstd': self.zstd_compress,
            'blosc': self.blosc_compress
        }
        
        self.decompressors = {
            'lz4': self.lz4_decompress,
            'zstd': self.zstd_decompress,
            'blosc': self.blosc_decompress
        }
    
    def compress_data(self, data: np.ndarray) -> Tuple[bytes, Dict]:
        """データの圧縮"""
        start_time = time.time()
        
        # 圧縮アルゴリズムの選択
        algorithm = self.select_compression_algorithm(data)
        
        # 圧縮の実行
        compressed_data = self.compressors[algorithm](data)
        
        # 圧縮統計の更新
        compression_time = time.time() - start_time
        compression_ratio = len(compressed_data) / data.nbytes
        
        compression_info = {
            'algorithm': algorithm,
            'original_size': data.nbytes,
            'compressed_size': len(compressed_data),
            'compression_ratio': compression_ratio,
            'compression_time': compression_time,
            'dtype': data.dtype,
            'shape': data.shape
        }
        
        self.update_compression_stats(algorithm, compression_info)
        
        return compressed_data, compression_info
    
    def decompress_data(self, compressed_data: bytes, compression_info: Dict) -> np.ndarray:
        """データの展開"""
        algorithm = compression_info['algorithm']
        
        # 展開の実行
        decompressed_data = self.decompressors[algorithm](compressed_data)
        
        # 元の形状とデータ型に復元
        data = np.frombuffer(decompressed_data, dtype=compression_info['dtype'])
        data = data.reshape(compression_info['shape'])
        
        return data
    
    def select_compression_algorithm(self, data: np.ndarray) -> str:
        """圧縮アルゴリズムの選択"""
        # データの特性に基づく選択
        if data.dtype == np.float64:
            return 'blosc'  # 数値データに最適
        elif data.size > 1e6:
            return 'lz4'    # 大きなデータには高速圧縮
        else:
            return 'zstd'   # 小さなデータには高圧縮率
    
    def lz4_compress(self, data: np.ndarray) -> bytes:
        """LZ4圧縮"""
        return lz4.frame.compress(data.tobytes(), compression_level=self.config.compression_level)
    
    def lz4_decompress(self, compressed_data: bytes) -> bytes:
        """LZ4展開"""
        return lz4.frame.decompress(compressed_data)
    
    def zstd_compress(self, data: np.ndarray) -> bytes:
        """Zstandard圧縮"""
        return zstd.compress(data.tobytes(), self.config.compression_level)
    
    def zstd_decompress(self, compressed_data: bytes) -> bytes:
        """Zstandard展開"""
        return zstd.decompress(compressed_data)
    
    def blosc_compress(self, data: np.ndarray) -> bytes:
        """Blosc圧縮"""
        return blosc.compress(data.tobytes(), cname='lz4', clevel=self.config.compression_level)
    
    def blosc_decompress(self, compressed_data: bytes) -> bytes:
        """Blosc展開"""
        return blosc.decompress(compressed_data)
    
    def update_compression_stats(self, algorithm: str, info: Dict):
        """圧縮統計の更新"""
        if algorithm not in self.compression_stats:
            self.compression_stats[algorithm] = {
                'count': 0,
                'total_original_size': 0,
                'total_compressed_size': 0,
                'total_time': 0
            }
        
        stats = self.compression_stats[algorithm]
        stats['count'] += 1
        stats['total_original_size'] += info['original_size']
        stats['total_compressed_size'] += info['compressed_size']
        stats['total_time'] += info['compression_time']

class MemoryPool:
    """メモリプール管理"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        self.pools = {}
        self.pool_usage = {}
        self.allocation_history = []
        
        # デフォルトプールの作成
        self.create_default_pools()
    
    def create_default_pools(self):
        """デフォルトプールの作成"""
        pool_sizes = {
            'small': 1024 * 1024,      # 1MB
            'medium': 64 * 1024 * 1024,  # 64MB
            'large': 1024 * 1024 * 1024,  # 1GB
            'xlarge': 8 * 1024 * 1024 * 1024  # 8GB
        }
        
        for name, size in pool_sizes.items():
            self.pools[name] = {
                'size': size,
                'allocated': 0,
                'blocks': {},
                'free_blocks': []
            }
            self.pool_usage[name] = 0
    
    def allocate_from_pool(self, size: int, pool_name: str = None) -> str:
        """プールからの割り当て"""
        if pool_name is None:
            pool_name = self.select_pool(size)
        
        pool = self.pools[pool_name]
        
        # 利用可能ブロックの確認
        if pool['free_blocks']:
            block_id = pool['free_blocks'].pop()
            pool['allocated'] += size
            return block_id
        
        # 新しいブロックの作成
        if pool['allocated'] + size <= pool['size']:
            block_id = self.create_block(pool_name, size)
            pool['allocated'] += size
            return block_id
        
        # プールの拡張
        if self.can_expand_pool(pool_name):
            self.expand_pool(pool_name)
            return self.allocate_from_pool(size, pool_name)
        
        raise MemoryError(f"Cannot allocate {size} bytes from pool {pool_name}")
    
    def select_pool(self, size: int) -> str:
        """最適なプールの選択"""
        if size <= 1024 * 1024:
            return 'small'
        elif size <= 64 * 1024 * 1024:
            return 'medium'
        elif size <= 1024 * 1024 * 1024:
            return 'large'
        else:
            return 'xlarge'
    
    def create_block(self, pool_name: str, size: int) -> str:
        """ブロックの作成"""
        block_id = f"{pool_name}_{len(self.pools[pool_name]['blocks'])}"
        
        self.pools[pool_name]['blocks'][block_id] = {
            'size': size,
            'allocated_at': time.time(),
            'last_accessed': time.time()
        }
        
        return block_id
    
    def can_expand_pool(self, pool_name: str) -> bool:
        """プール拡張可能性の確認"""
        total_memory = psutil.virtual_memory().total
        current_usage = sum(pool['allocated'] for pool in self.pools.values())
        
        return current_usage < total_memory * 0.8  # 80%以下の使用率
    
    def expand_pool(self, pool_name: str):
        """プールの拡張"""
        pool = self.pools[pool_name]
        new_size = int(pool['size'] * self.config.pool_growth_factor)
        
        pool['size'] = new_size
        logger.info(f"Expanded pool {pool_name} to {new_size} bytes")
    
    def deallocate_from_pool(self, block_id: str):
        """プールからの解放"""
        # プールとブロックの特定
        pool_name = block_id.split('_')[0]
        pool = self.pools[pool_name]
        
        if block_id in pool['blocks']:
            size = pool['blocks'][block_id]['size']
            del pool['blocks'][block_id]
            pool['allocated'] -= size
            pool['free_blocks'].append(block_id)

class CacheManager:
    """キャッシュ管理システム"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        self.cache = {}
        self.cache_stats = {
            'hits': 0,
            'misses': 0,
            'evictions': 0
        }
        
        # キャッシュポリシーの設定
        self.cache_policy = CachePolicy(config.cache_policy)
        
        # キャッシュサイズの管理
        self.max_cache_size = config.cache_size * 1024**3  # GB to bytes
        self.current_cache_size = 0
    
    def get_from_cache(self, key: str) -> Optional[np.ndarray]:
        """キャッシュからの取得"""
        if key in self.cache:
            self.cache_stats['hits'] += 1
            self.cache_policy.on_access(key)
            return self.cache[key]['data']
        else:
            self.cache_stats['misses'] += 1
            return None
    
    def put_to_cache(self, key: str, data: np.ndarray):
        """キャッシュへの書き込み"""
        data_size = data.nbytes
        
        # キャッシュ容量の確認
        if self.current_cache_size + data_size > self.max_cache_size:
            self.evict_cache_entries(data_size)
        
        # キャッシュへの追加
        self.cache[key] = {
            'data': data,
            'size': data_size,
            'timestamp': time.time()
        }
        
        self.current_cache_size += data_size
        self.cache_policy.on_insert(key)
    
    def evict_cache_entries(self, required_size: int):
        """キャッシュエントリの削除"""
        evicted_size = 0
        
        # 削除対象の選択
        eviction_candidates = self.cache_policy.get_eviction_candidates()
        
        for key in eviction_candidates:
            if evicted_size >= required_size:
                break
            
            if key in self.cache:
                entry_size = self.cache[key]['size']
                del self.cache[key]
                self.current_cache_size -= entry_size
                evicted_size += entry_size
                self.cache_stats['evictions'] += 1
    
    def get_cache_stats(self) -> Dict[str, float]:
        """キャッシュ統計の取得"""
        total_accesses = self.cache_stats['hits'] + self.cache_stats['misses']
        
        if total_accesses == 0:
            return {'hit_rate': 0.0, 'miss_rate': 0.0}
        
        return {
            'hit_rate': self.cache_stats['hits'] / total_accesses,
            'miss_rate': self.cache_stats['misses'] / total_accesses,
            'eviction_rate': self.cache_stats['evictions'] / total_accesses
        }

class CachePolicy:
    """キャッシュポリシー"""
    
    def __init__(self, policy_type: str):
        self.policy_type = policy_type
        self.access_history = {}
        self.insertion_order = []
        
    def on_access(self, key: str):
        """アクセス時の処理"""
        if self.policy_type == 'lru':
            self.access_history[key] = time.time()
        elif self.policy_type == 'lfu':
            self.access_history[key] = self.access_history.get(key, 0) + 1
    
    def on_insert(self, key: str):
        """挿入時の処理"""
        self.insertion_order.append(key)
        self.on_access(key)
    
    def get_eviction_candidates(self) -> List[str]:
        """削除候補の取得"""
        if self.policy_type == 'lru':
            return self.get_lru_candidates()
        elif self.policy_type == 'lfu':
            return self.get_lfu_candidates()
        else:
            return self.get_fifo_candidates()
    
    def get_lru_candidates(self) -> List[str]:
        """LRU候補の取得"""
        sorted_keys = sorted(self.access_history.keys(), 
                           key=lambda k: self.access_history[k])
        return sorted_keys
    
    def get_lfu_candidates(self) -> List[str]:
        """LFU候補の取得"""
        sorted_keys = sorted(self.access_history.keys(), 
                           key=lambda k: self.access_history[k])
        return sorted_keys
    
    def get_fifo_candidates(self) -> List[str]:
        """FIFO候補の取得"""
        return self.insertion_order

class MemoryOptimizer:
    """メモリ最適化システム統合"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        
        # 各コンポーネントの初期化
        self.memory_hierarchy = MemoryHierarchy(config)
        self.compression_manager = CompressionManager(config)
        self.memory_pool = MemoryPool(config)
        self.cache_manager = CacheManager(config)
        
        # メモリ監視
        self.memory_monitor = MemoryMonitor(config)
        
        # ガベージコレクション最適化
        self.gc_optimizer = GCOptimizer(config)
        
        logger.info("Memory optimizer initialized")
    
    def optimize_memory_allocation(self, data: np.ndarray, access_pattern: str = 'random') -> str:
        """メモリ割り当ての最適化"""
        # キャッシュの確認
        cache_key = self.generate_cache_key(data)
        cached_data = self.cache_manager.get_from_cache(cache_key)
        
        if cached_data is not None:
            return 'cache'
        
        # 圧縮の検討
        if self.should_compress(data):
            compressed_data, compression_info = self.compression_manager.compress_data(data)
            allocation_layer = self.memory_hierarchy.allocate_memory(
                len(compressed_data), access_pattern
            )
        else:
            allocation_layer = self.memory_hierarchy.allocate_memory(
                data.nbytes, access_pattern
            )
        
        # キャッシュへの追加
        self.cache_manager.put_to_cache(cache_key, data)
        
        return allocation_layer
    
    def should_compress(self, data: np.ndarray) -> bool:
        """圧縮の必要性判定"""
        # データサイズに基づく判定
        if data.nbytes > 1024 * 1024:  # 1MB以上
            return True
        
        # データの特性に基づく判定
        if data.dtype in [np.float32, np.float64]:
            return True
        
        return False
    
    def generate_cache_key(self, data: np.ndarray) -> str:
        """キャッシュキーの生成"""
        # データのハッシュ値を使用
        data_hash = hash(data.tobytes())
        return f"data_{data_hash}_{data.shape}_{data.dtype}"
    
    def optimize_memory_usage(self):
        """メモリ使用量の最適化"""
        # メモリ使用量の監視
        memory_stats = self.memory_monitor.get_memory_stats()
        
        # 閾値を超えた場合の最適化
        if memory_stats['usage_percent'] > self.config.gc_threshold * 100:
            self.gc_optimizer.optimize_garbage_collection()
        
        # キャッシュの最適化
        self.optimize_cache_performance()
    
    def optimize_cache_performance(self):
        """キャッシュ性能の最適化"""
        cache_stats = self.cache_manager.get_cache_stats()
        
        # ヒット率が低い場合の最適化
        if cache_stats['hit_rate'] < 0.7:
            logger.info("Optimizing cache performance")
            # キャッシュサイズの調整
            # キャッシュポリシーの変更
            # プリフェッチの実装
    
    def get_optimization_report(self) -> Dict[str, Any]:
        """最適化レポートの生成"""
        return {
            'memory_usage': self.memory_monitor.get_memory_stats(),
            'cache_stats': self.cache_manager.get_cache_stats(),
            'compression_stats': self.compression_manager.compression_stats,
            'gc_stats': self.gc_optimizer.get_gc_stats()
        }

class MemoryMonitor:
    """メモリ監視システム"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        self.monitoring_enabled = config.memory_monitoring
        
        # 監視データの保存
        self.memory_history = []
        
        # 監視スレッドの開始
        if self.monitoring_enabled:
            self.start_monitoring()
    
    def start_monitoring(self):
        """メモリ監視の開始"""
        self.monitoring_thread = threading.Thread(target=self.monitor_memory)
        self.monitoring_thread.daemon = True
        self.monitoring_thread.start()
    
    def monitor_memory(self):
        """メモリ監視処理"""
        while self.monitoring_enabled:
            stats = self.collect_memory_stats()
            self.memory_history.append(stats)
            
            # 履歴の管理
            if len(self.memory_history) > 1000:
                self.memory_history.pop(0)
            
            time.sleep(5)  # 5秒間隔で監視
    
    def collect_memory_stats(self) -> Dict[str, float]:
        """メモリ統計の収集"""
        memory = psutil.virtual_memory()
        
        return {
            'timestamp': time.time(),
            'total': memory.total,
            'available': memory.available,
            'used': memory.used,
            'usage_percent': memory.percent,
            'process_memory': psutil.Process().memory_info().rss
        }
    
    def get_memory_stats(self) -> Dict[str, float]:
        """最新のメモリ統計の取得"""
        if self.memory_history:
            return self.memory_history[-1]
        else:
            return self.collect_memory_stats()

class GCOptimizer:
    """ガベージコレクション最適化"""
    
    def __init__(self, config: MemoryConfig):
        self.config = config
        self.gc_stats = {
            'collections': 0,
            'objects_collected': 0,
            'time_spent': 0.0
        }
    
    def optimize_garbage_collection(self):
        """ガベージコレクションの最適化"""
        start_time = time.time()
        
        # 明示的なガベージコレクション
        collected_objects = gc.collect()
        
        # 統計の更新
        gc_time = time.time() - start_time
        self.gc_stats['collections'] += 1
        self.gc_stats['objects_collected'] += collected_objects
        self.gc_stats['time_spent'] += gc_time
        
        logger.info(f"GC collected {collected_objects} objects in {gc_time:.3f}s")
    
    def get_gc_stats(self) -> Dict[str, float]:
        """GC統計の取得"""
        return self.gc_stats.copy()

def main():
    """メイン実行関数"""
    # 設定
    config = MemoryConfig()
    
    # メモリ最適化システムの初期化
    optimizer = MemoryOptimizer(config)
    
    # テストデータ
    test_data = np.random.random((10000, 10000))
    
    # メモリ最適化の実行
    allocation_layer = optimizer.optimize_memory_allocation(test_data, 'sequential')
    
    # 最適化レポート
    report = optimizer.get_optimization_report()
    print_optimization_report(report)

def print_optimization_report(report: Dict[str, Any]):
    """最適化レポートの表示"""
    print("=== Memory Optimization Report ===")
    
    # メモリ使用量
    memory_stats = report['memory_usage']
    print(f"Memory Usage: {memory_stats['usage_percent']:.1f}%")
    print(f"Available Memory: {memory_stats['available'] / 1024**3:.2f} GB")
    
    # キャッシュ統計
    cache_stats = report['cache_stats']
    print(f"Cache Hit Rate: {cache_stats['hit_rate']:.3f}")
    print(f"Cache Miss Rate: {cache_stats['miss_rate']:.3f}")
    
    # 圧縮統計
    compression_stats = report['compression_stats']
    for algorithm, stats in compression_stats.items():
        if stats['count'] > 0:
            avg_ratio = stats['total_compressed_size'] / stats['total_original_size']
            print(f"{algorithm} Compression Ratio: {avg_ratio:.3f}")
    
    # GC統計
    gc_stats = report['gc_stats']
    print(f"GC Collections: {gc_stats['collections']}")
    print(f"Objects Collected: {gc_stats['objects_collected']}")

if __name__ == "__main__":
    main() 