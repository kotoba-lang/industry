//! # Security Module for DoS Protection
//!
//! Rate limiting, Resource limits, GPU timeout mechanisms
//! Critical security improvements for production environment

use std::{
    sync::{Arc, atomic::{AtomicU64, AtomicUsize, Ordering}, RwLock},
    collections::HashMap,
    time::{Duration, Instant, SystemTime},
};
use tokio::time::timeout;

/// セキュリティ設定
#[derive(Debug, Clone)]
pub struct SecurityConfig {
    /// 最大イベントサイズ（バイト）
    pub max_event_size: usize,
    /// 最大バッチサイズ（イベント数）
    pub max_batch_size: usize,
    /// 最大同時バッチ数
    pub max_concurrent_batches: usize,
    /// GPUタイムアウト（ミリ秒）
    pub gpu_timeout_ms: u64,
    /// レート制限（events/sec）
    pub rate_limit_per_second: u64,
    /// メモリ使用量制限（バイト）
    pub max_memory_usage: usize,
    /// CPU使用率制限（パーセント）
    pub max_cpu_usage: f32,
    /// GPU使用率制限（パーセント）
    pub max_gpu_usage: f32,
}

impl Default for SecurityConfig {
    fn default() -> Self {
        Self {
            max_event_size: 1024 * 1024,        // 1MB
            max_batch_size: 10_000,             // 10K events
            max_concurrent_batches: 100,        // 100 batches
            gpu_timeout_ms: 5_000,              // 5 seconds
            rate_limit_per_second: 100_000,     // 100K events/sec
            max_memory_usage: 2 * 1024 * 1024 * 1024, // 2GB
            max_cpu_usage: 80.0,                // 80%
            max_gpu_usage: 90.0,                // 90%
        }
    }
}

/// セキュリティマネージャー
pub struct SecurityManager {
    config: SecurityConfig,
    rate_limiter: Arc<RateLimiter>,
    resource_monitor: Arc<ResourceMonitor>,
    gpu_timeout_manager: Arc<GPUTimeoutManager>,
    security_metrics: SecurityMetrics,
}

/// レート制限機能
pub struct RateLimiter {
    /// 現在の秒における処理済みイベント数
    current_second_count: AtomicU64,
    /// 現在の秒のタイムスタンプ
    current_second: AtomicU64,
    /// 制限値
    limit_per_second: u64,
    /// 違反回数
    violations: AtomicU64,
}

/// リソース監視機能
#[derive(Debug)]
#[allow(dead_code)]
pub struct ResourceMonitor {
    /// アクティブなバッチ数
    active_batches: AtomicUsize,
    /// 現在のメモリ使用量
    current_memory_usage: AtomicUsize,
    /// 現在のCPU使用率
    current_cpu_usage: AtomicU64, // f32 * 1000 for atomic
    /// 現在のGPU使用率
    current_gpu_usage: AtomicU64, // f32 * 1000 for atomic
    /// 最大値記録
    peak_memory: AtomicUsize,
    peak_cpu: AtomicU64,
    peak_gpu: AtomicU64,
}

/// GPU タイムアウト管理
#[derive(Debug)]
#[allow(dead_code)]
pub struct GPUTimeoutManager {
    /// アクティブなGPUタスク
    active_gpu_tasks: RwLock<HashMap<u64, GPUTaskInfo>>,
    /// タスクIDカウンター
    task_id_counter: AtomicU64,
    /// タイムアウト時間
    timeout_duration: Duration,
    /// タイムアウト発生回数
    timeout_count: AtomicU64,
}

/// GPUタスク情報
#[derive(Debug, Clone)]
pub struct GPUTaskInfo {
    pub task_id: u64,
    pub start_time: Instant,
    pub event_count: usize,
    pub gpu_device: String,
}

/// セキュリティメトリクス
#[derive(Debug, Default)]
pub struct SecurityMetrics {
    /// レート制限違反回数
    pub rate_limit_violations: AtomicU64,
    /// リソース制限違反回数
    pub resource_limit_violations: AtomicU64,
    /// GPUタイムアウト回数
    pub gpu_timeouts: AtomicU64,
    /// 拒否されたリクエスト数
    pub rejected_requests: AtomicU64,
    /// セキュリティイベント数
    pub security_events: AtomicU64,
}

/// セキュリティエラー
#[derive(Debug, Clone)]
pub enum SecurityError {
    RateLimitExceeded { current: u64, limit: u64 },
    BatchTooLarge { size: usize, limit: usize },
    TooManyActiveBatches { active: usize, limit: usize },
    EventTooLarge { size: usize, limit: usize },
    MemoryExhausted { current: usize, limit: usize },
    CPUExhausted { current: f32, limit: f32 },
    GPUExhausted { current: f32, limit: f32 },
    GPUTimeout { task_id: u64, duration: Duration },
    ResourceMonitoringFailed,
}

impl SecurityManager {
    /// 新しいセキュリティマネージャーを作成
    pub fn new(config: SecurityConfig) -> Self {
        let rate_limiter = Arc::new(RateLimiter::new(config.rate_limit_per_second));
        let resource_monitor = Arc::new(ResourceMonitor::new());
        let gpu_timeout_manager = Arc::new(GPUTimeoutManager::new(
            Duration::from_millis(config.gpu_timeout_ms)
        ));
        
        tracing::info!(
            "SecurityManager initialized: rate_limit={}/sec, max_batch={}, gpu_timeout={}ms",
            config.rate_limit_per_second,
            config.max_batch_size,
            config.gpu_timeout_ms
        );
        
        Self {
            config,
            rate_limiter,
            resource_monitor,
            gpu_timeout_manager,
            security_metrics: SecurityMetrics::default(),
        }
    }
    
    /// バッチ処理前のセキュリティ検証
    pub async fn validate_batch_request(
        &self,
        event_count: usize,
        total_size: usize,
    ) -> Result<(), SecurityError> {
        // 1. レート制限チェック
        self.rate_limiter.check_rate(event_count as u64)
            .map_err(|_| {
                self.security_metrics.rate_limit_violations.fetch_add(1, Ordering::Relaxed);
                SecurityError::RateLimitExceeded {
                    current: event_count as u64,
                    limit: self.config.rate_limit_per_second,
                }
            })?;
        
        // 2. バッチサイズ検証
        if event_count > self.config.max_batch_size {
            self.security_metrics.rejected_requests.fetch_add(1, Ordering::Relaxed);
            return Err(SecurityError::BatchTooLarge {
                size: event_count,
                limit: self.config.max_batch_size,
            });
        }
        
        // 3. リソース制限チェック
        self.resource_monitor.check_resources(&self.config)
            .map_err(|e| {
                self.security_metrics.resource_limit_violations.fetch_add(1, Ordering::Relaxed);
                e
            })?;
        
        tracing::debug!(
            "Security validation passed: {} events, {} bytes",
            event_count,
            total_size
        );
        
        Ok(())
    }
    
    /// GPU処理のセキュアラッパー
    pub async fn secure_gpu_process<F, T>(
        &self,
        task_name: &str,
        event_count: usize,
        gpu_future: F,
    ) -> Result<T, SecurityError>
    where
        F: std::future::Future<Output = Result<T, crate::StorageError>>,
    {
        // GPUタスク登録
        let task_id = self.gpu_timeout_manager.register_task(
            task_name.to_string(),
            event_count,
        ).await;
        
        tracing::debug!(
            "Starting secure GPU process: task_id={}, events={}, timeout={}ms",
            task_id,
            event_count,
            self.config.gpu_timeout_ms
        );
        
        // タイムアウト付きGPU処理実行
        let result = timeout(
            Duration::from_millis(self.config.gpu_timeout_ms),
            gpu_future
        ).await;
        
        // タスク登録解除
        self.gpu_timeout_manager.unregister_task(task_id).await;
        
        match result {
            Ok(Ok(value)) => {
                tracing::debug!("GPU task completed successfully: task_id={}", task_id);
                Ok(value)
            }
            Ok(Err(_storage_err)) => {
                Err(SecurityError::ResourceMonitoringFailed)
            }
            Err(_timeout_err) => {
                self.security_metrics.gpu_timeouts.fetch_add(1, Ordering::Relaxed);
                tracing::error!(
                    "GPU task timeout: task_id={}, duration={}ms",
                    task_id,
                    self.config.gpu_timeout_ms
                );
                Err(SecurityError::GPUTimeout {
                    task_id,
                    duration: Duration::from_millis(self.config.gpu_timeout_ms),
                })
            }
        }
    }
    
    /// セキュリティメトリクス取得
    pub fn get_security_metrics(&self) -> SecurityMetrics {
        SecurityMetrics {
            rate_limit_violations: AtomicU64::new(
                self.security_metrics.rate_limit_violations.load(Ordering::Relaxed)
            ),
            resource_limit_violations: AtomicU64::new(
                self.security_metrics.resource_limit_violations.load(Ordering::Relaxed)
            ),
            gpu_timeouts: AtomicU64::new(
                self.security_metrics.gpu_timeouts.load(Ordering::Relaxed)
            ),
            rejected_requests: AtomicU64::new(
                self.security_metrics.rejected_requests.load(Ordering::Relaxed)
            ),
            security_events: AtomicU64::new(
                self.security_metrics.security_events.load(Ordering::Relaxed)
            ),
        }
    }
}

impl RateLimiter {
    /// 新しいレート制限機能を作成
    pub fn new(limit_per_second: u64) -> Self {
        Self {
            current_second_count: AtomicU64::new(0),
            current_second: AtomicU64::new(Self::current_timestamp()),
            limit_per_second,
            violations: AtomicU64::new(0),
        }
    }
    
    /// レート制限チェック
    pub fn check_rate(&self, event_count: u64) -> Result<(), ()> {
        let now = Self::current_timestamp();
        let current_sec = self.current_second.load(Ordering::Relaxed);
        
        // 秒が変わった場合はカウンターリセット
        if now != current_sec {
            if self.current_second.compare_exchange(current_sec, now, Ordering::Relaxed, Ordering::Relaxed).is_ok() {
                self.current_second_count.store(0, Ordering::Relaxed);
            }
        }
        
        // 現在の秒でのカウント増加
        let new_count = self.current_second_count.fetch_add(event_count, Ordering::Relaxed) + event_count;
        
        if new_count > self.limit_per_second {
            // カウントを元に戻す
            self.current_second_count.fetch_sub(event_count, Ordering::Relaxed);
            self.violations.fetch_add(1, Ordering::Relaxed);
            
            tracing::warn!(
                "Rate limit exceeded: current={}, limit={}, violations={}",
                new_count,
                self.limit_per_second,
                self.violations.load(Ordering::Relaxed)
            );
            
            return Err(());
        }
        
        Ok(())
    }
    
    /// 現在のタイムスタンプ（秒）を取得
    fn current_timestamp() -> u64 {
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    }
}

impl ResourceMonitor {
    /// 新しいリソース監視機能を作成
    pub fn new() -> Self {
        Self {
            active_batches: AtomicUsize::new(0),
            current_memory_usage: AtomicUsize::new(0),
            current_cpu_usage: AtomicU64::new(0),
            current_gpu_usage: AtomicU64::new(0),
            peak_memory: AtomicUsize::new(0),
            peak_cpu: AtomicU64::new(0),
            peak_gpu: AtomicU64::new(0),
        }
    }
    
    /// リソース制限チェック
    pub fn check_resources(&self, config: &SecurityConfig) -> Result<(), SecurityError> {
        // アクティブバッチ数チェック
        let active = self.active_batches.load(Ordering::Relaxed);
        if active >= config.max_concurrent_batches {
            return Err(SecurityError::TooManyActiveBatches {
                active,
                limit: config.max_concurrent_batches,
            });
        }
        
        // メモリ使用量チェック
        let memory = self.current_memory_usage.load(Ordering::Relaxed);
        if memory > config.max_memory_usage {
            return Err(SecurityError::MemoryExhausted {
                current: memory,
                limit: config.max_memory_usage,
            });
        }
        
        Ok(())
    }
    
    /// バッチ開始通知
    pub fn start_batch(&self) {
        self.active_batches.fetch_add(1, Ordering::Relaxed);
    }
    
    /// バッチ終了通知
    pub fn end_batch(&self) {
        self.active_batches.fetch_sub(1, Ordering::Relaxed);
    }
}

impl GPUTimeoutManager {
    /// 新しいGPUタイムアウト管理機能を作成
    pub fn new(timeout_duration: Duration) -> Self {
        Self {
            active_gpu_tasks: RwLock::new(HashMap::new()),
            task_id_counter: AtomicU64::new(0),
            timeout_duration,
            timeout_count: AtomicU64::new(0),
        }
    }
    
    /// GPUタスク登録
    pub async fn register_task(&self, gpu_device: String, event_count: usize) -> u64 {
        let task_id = self.task_id_counter.fetch_add(1, Ordering::Relaxed);
        let task_info = GPUTaskInfo {
            task_id,
            start_time: Instant::now(),
            event_count,
            gpu_device,
        };
        
        if let Ok(mut tasks) = self.active_gpu_tasks.write() {
            tasks.insert(task_id, task_info);
        }
        
        tracing::trace!("GPU task registered: task_id={}, events={}", task_id, event_count);
        task_id
    }
    
    /// GPUタスク登録解除
    pub async fn unregister_task(&self, task_id: u64) {
        if let Ok(mut tasks) = self.active_gpu_tasks.write() {
            if let Some(task_info) = tasks.remove(&task_id) {
                let duration = task_info.start_time.elapsed();
                tracing::trace!(
                    "GPU task unregistered: task_id={}, duration={:?}",
                    task_id,
                    duration
                );
            }
        }
    }
} 