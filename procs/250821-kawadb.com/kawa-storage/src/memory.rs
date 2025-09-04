//! # 高性能メモリプール
//!
//! 1M+ events/secを達成するための高性能メモリ管理システム。
//! プリアロケートされたバッファプールによりGC圧力を軽減し、
//! 高速なメモリアロケーション/デアロケーションを実現。

use crate::{StorageError, StorageResult};
use std::{
    collections::VecDeque,
    sync::{Arc, Mutex, atomic::{AtomicUsize, Ordering}},
};
use tokio::sync::Semaphore;

/// プールされたバッファ
/// 
/// 高性能なバッファ管理のためのRAII型。
/// ドロップ時に自動的にプールに戻される。
pub struct PooledBuffer {
    /// バッファデータ
    pub buffer: Vec<u8>,
    /// プールへの参照（戻すため）
    pool: Arc<MemoryPoolInner>,
    /// バッファサイズ
    size: usize,
}

impl PooledBuffer {
    /// バッファをクリア
    pub fn clear(&mut self) {
        self.buffer.clear();
    }
    
    /// バッファサイズを取得
    pub fn capacity(&self) -> usize {
        self.buffer.capacity()
    }

    /// バッファの現在の長さを取得
    pub fn len(&self) -> usize {
        self.buffer.len()
    }
    
    /// データを書き込み
    pub fn write(&mut self, data: &[u8]) -> StorageResult<()> {
        if self.buffer.len() + data.len() > self.buffer.capacity() {
            return Err(StorageError::InsufficientSpace {
                required: data.len() as u64,
                available: (self.buffer.capacity() - self.buffer.len()) as u64,
            });
        }
        self.buffer.extend_from_slice(data);
        Ok(())
    }
    
    /// バッファ内のデータを取得
    pub fn data(&self) -> &[u8] {
        &self.buffer
    }
    
    /// バッファのミュータブル参照を取得
    pub fn as_mut(&mut self) -> &mut Vec<u8> {
        &mut self.buffer
    }
}

impl Drop for PooledBuffer {
    fn drop(&mut self) {
        // バッファをクリアしてプールに戻す
        self.buffer.clear();
        let buffer = std::mem::take(&mut self.buffer);
        
        // 容量が期待値と一致する場合のみプールに戻す
        if buffer.capacity() >= self.size {
            let mut pool = self.pool.buffers.lock().unwrap();
            if pool.len() < self.pool.max_buffers {
                pool.push_back(buffer);
                self.pool.available_count.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}

/// メモリプール内部構造
#[derive(Debug)]
struct MemoryPoolInner {
    /// プールされたバッファ
    buffers: Mutex<VecDeque<Vec<u8>>>,
    /// 利用可能バッファ数
    available_count: AtomicUsize,
    /// 最大バッファ数
    max_buffers: usize,
    /// バッファサイズ
    buffer_size: usize,
    /// バッファ取得セマフォ
    semaphore: Arc<Semaphore>,
}

/// 高性能メモリプール
/// 
/// プリアロケートされたバッファプールによる高速メモリ管理。
/// 
/// # 機能
/// - ゼロアロケーション書き込み
/// - バッファ再利用による GC 圧力軽減
/// - 非同期バッファ取得
/// - 自動サイズ調整
/// 
/// # 性能特性
/// - バッファ取得: O(1)
/// - バッファ返却: O(1)
/// - メモリ断片化: 最小限
#[derive(Debug)]
pub struct MemoryPool {
    inner: Arc<MemoryPoolInner>,
    /// プール統計
    stats: Arc<PoolStats>,
}

/// メモリプールの統計情報
#[derive(Debug, Default)]
pub struct PoolStats {
    /// 総取得回数
    pub total_gets: AtomicUsize,
    /// 総返却回数
    pub total_returns: AtomicUsize,
    /// キャッシュヒット数
    pub cache_hits: AtomicUsize,
    /// キャッシュミス数
    pub cache_misses: AtomicUsize,
    /// 現在利用中バッファ数
    pub active_buffers: AtomicUsize,
    /// プールサイズ
    pub pool_size: AtomicUsize,
}

impl PoolStats {
    /// ヒット率を計算
    pub fn hit_rate(&self) -> f64 {
        let hits = self.cache_hits.load(Ordering::Relaxed) as f64;
        let total = hits + self.cache_misses.load(Ordering::Relaxed) as f64;
        if total > 0.0 {
            hits / total
        } else {
            0.0
        }
    }
    
    /// 利用率を計算
    pub fn utilization(&self) -> f64 {
        let active = self.active_buffers.load(Ordering::Relaxed) as f64;
        let total = self.pool_size.load(Ordering::Relaxed) as f64;
        if total > 0.0 {
            active / total
        } else {
            0.0
        }
    }
}

impl MemoryPool {
    /// 新しいメモリプールを作成
    /// 
    /// # Arguments
    /// * `pool_size_bytes` - プール総サイズ（バイト）
    /// * `buffer_size` - 個別バッファサイズ
    /// 
    /// # Returns
    /// * `Result<Self, StorageError>`
    pub fn new(pool_size: usize, buffer_size: usize) -> StorageResult<Self> {
        if pool_size < buffer_size {
            return Err(StorageError::Configuration {
                message: "Pool size too small for requested buffer size".to_string(),
            });
        }
        
        let num_buffers = pool_size / buffer_size;
        
        let mut buffers = VecDeque::with_capacity(num_buffers);
        
        // プールを事前に満たす（ウォームアップ）
        for _ in 0..num_buffers {
            let mut buffer = Vec::with_capacity(buffer_size);
            buffer.reserve_exact(buffer_size);
            buffers.push_back(buffer);
        }
        
        let stats = Arc::new(PoolStats::default());
        stats.pool_size.store(num_buffers, Ordering::Relaxed);
        
        let inner = Arc::new(MemoryPoolInner {
            buffers: Mutex::new(buffers),
            available_count: AtomicUsize::new(num_buffers),
            max_buffers: num_buffers,
            buffer_size,
            semaphore: Arc::new(Semaphore::new(num_buffers)),
        });
        
        tracing::info!(
            "MemoryPool initialized: {} buffers, {} bytes each, {} MB total",
            num_buffers, 
            buffer_size,
            pool_size / (1024 * 1024)
        );
        
        Ok(Self { inner, stats })
    }
    
    /// バッファを取得（非同期）
    /// 
    /// # Returns
    /// * `Result<PooledBuffer, StorageError>` - プールされたバファ
    pub async fn get_buffer(&self) -> StorageResult<PooledBuffer> {
        let _permit = Arc::clone(&self.inner.semaphore).acquire_owned().await
            .map_err(|_| StorageError::Internal { message: "Failed to acquire buffer permit".to_string() })?;
        
        self.stats.total_gets.fetch_add(1, Ordering::Relaxed);
        
        // バッファプールから取得を試行
        if let Some(buffer) = self.try_get_from_pool() {
            self.stats.cache_hits.fetch_add(1, Ordering::Relaxed);
            self.stats.active_buffers.fetch_add(1, Ordering::Relaxed);
            
            return Ok(PooledBuffer {
                buffer,
                pool: Arc::clone(&self.inner),
                size: self.inner.buffer_size,
            });
        }
        
        // プールが空の場合は新しいバッファを作成
        self.stats.cache_misses.fetch_add(1, Ordering::Relaxed);
        self.stats.active_buffers.fetch_add(1, Ordering::Relaxed);
        
        let mut buffer = Vec::with_capacity(self.inner.buffer_size);
        buffer.reserve_exact(self.inner.buffer_size);
        
        Ok(PooledBuffer {
            buffer,
            pool: Arc::clone(&self.inner),
            size: self.inner.buffer_size,
        })
    }
    
    /// プールから直接バッファ取得を試行
    fn try_get_from_pool(&self) -> Option<Vec<u8>> {
        let mut buffers = self.inner.buffers.lock().ok()?;
        if let Some(buffer) = buffers.pop_front() {
            self.inner.available_count.fetch_sub(1, Ordering::Relaxed);
            Some(buffer)
        } else {
            None
        }
    }
    
    /// プール統計を取得
    pub fn stats(&self) -> PoolStats {
        PoolStats {
            total_gets: AtomicUsize::new(self.stats.total_gets.load(Ordering::Relaxed)),
            total_returns: AtomicUsize::new(self.stats.total_returns.load(Ordering::Relaxed)),
            cache_hits: AtomicUsize::new(self.stats.cache_hits.load(Ordering::Relaxed)),
            cache_misses: AtomicUsize::new(self.stats.cache_misses.load(Ordering::Relaxed)),
            active_buffers: AtomicUsize::new(self.stats.active_buffers.load(Ordering::Relaxed)),
            pool_size: AtomicUsize::new(self.stats.pool_size.load(Ordering::Relaxed)),
        }
    }
    
    /// プールの健全性をチェック
    pub fn health_check(&self) -> bool {
        let available = self.inner.available_count.load(Ordering::Relaxed);
        let active = self.stats.active_buffers.load(Ordering::Relaxed);
        let total = available + active;
        
        // 総数が期待範囲内かチェック
        total <= self.inner.max_buffers
    }
    
    /// プールをウォームアップ（事前バッファ作成）
    pub async fn warmup(&self) -> StorageResult<()> {
        tracing::info!("Warming up memory pool...");
        
        let warmup_count = self.inner.max_buffers / 2;
        let mut buffers = Vec::new();
        
        // バッファを事前取得してすぐ戻す（ウォームアップ）
        for _ in 0..warmup_count {
            if let Ok(buffer) = self.get_buffer().await {
                buffers.push(buffer);
            }
        }
        
        // バッファを解放（自動的にプールに戻る）
        drop(buffers);
        
        tracing::info!("Memory pool warmup completed");
        Ok(())
    }
}

/// 並列バッファプロセッサ
/// 
/// 複数のワーカーでバッファ処理を並列化
pub struct ParallelBufferProcessor {
    pool: Arc<MemoryPool>,
    worker_count: usize,
}

impl ParallelBufferProcessor {
    /// 新しい並列プロセッサを作成
    pub fn new(pool: Arc<MemoryPool>, worker_count: Option<usize>) -> Self {
        let worker_count = worker_count.unwrap_or_else(|| {
            std::thread::available_parallelism()
                .map(|n| n.get() * 2)
                .unwrap_or(8)
        });
        
        Self { pool, worker_count }
    }
    
    /// バッチデータを並列処理
    pub async fn process_batch<T, F, Fut>(
        &self,
        items: Vec<T>,
        processor: F,
    ) -> StorageResult<Vec<StorageResult<()>>>
    where
        T: Send + 'static + Clone,
        F: Fn(T, PooledBuffer) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = StorageResult<()>> + Send,
    {
        let processor = Arc::new(processor);
        let chunk_size = (items.len() + self.worker_count - 1) / self.worker_count;
        
        let mut handles = Vec::new();
        
        for chunk in items.chunks(chunk_size) {
            let chunk = chunk.to_vec();
            let pool = Arc::clone(&self.pool);
            let processor = Arc::clone(&processor);
            
            let handle = tokio::spawn(async move {
                let mut results = Vec::new();
                
                for item in chunk {
                    match pool.get_buffer().await {
                        Ok(buffer) => {
                            let result = processor(item, buffer).await;
                            results.push(result);
                        }
                        Err(e) => {
                            results.push(Err(e));
                        }
                    }
                }
                
                results
            });
            
            handles.push(handle);
        }
        
        let mut all_results = Vec::new();
        for handle in handles {
            match handle.await {
                Ok(results) => all_results.extend(results),
                Err(e) => return Err(StorageError::Internal { message: format!("Worker task failed: {}", e) }),
            }
        }
        
        Ok(all_results)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio;
    
    #[tokio::test]
    async fn test_memory_pool_basic_operations() {
        let pool = MemoryPool::new(1024 * 1024, 1024).unwrap(); // 1MB pool, 1KB buffers
        
        // バッファ取得
        let mut buffer = pool.get_buffer().await.unwrap();
        assert_eq!(buffer.capacity(), 1024);
        
        // データ書き込み
        let test_data = b"Hello, Memory Pool!";
        buffer.write(test_data).unwrap();
        assert_eq!(buffer.data(), test_data);
        
        // バッファ解放（ドロップ時に自動的にプールに戻る）
        drop(buffer);
        
        // 統計確認
        let stats = pool.stats();
        assert_eq!(stats.total_gets.load(Ordering::Relaxed), 1);
    }
    
    #[tokio::test]
    async fn test_concurrent_buffer_access() {
        let pool = Arc::new(MemoryPool::new(10 * 1024, 1024).unwrap()); // 10KB pool
        
        let mut handles = Vec::new();
        
        // 並行でバッファ取得
        for i in 0..5 {
            let pool_clone = Arc::clone(&pool);
            let handle = tokio::spawn(async move {
                let mut buffer = pool_clone.get_buffer().await.unwrap();
                buffer.write(format!("Data {}", i).as_bytes()).unwrap();
                // 少し待機してからドロップ
                tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
                buffer
            });
            handles.push(handle);
        }
        
        // すべてのタスクが完了するまで待機
        for handle in handles {
            let _buffer = handle.await.unwrap();
        }
        
        // プールの健全性をチェック
        assert!(pool.health_check());
    }
} 