#![allow(dead_code, unused_imports, unused_variables)]
//! # Security Enhanced Benchmark
//!
//! DoS攻撃対策の効果を検証するベンチマーク
//! Rate limiting, Resource limits, GPU timeout の実証テスト

use criterion::{black_box, criterion_group, criterion_main, Criterion, BenchmarkId};
use kawa_storage::{
    Event, EventData, EventId, Topic, Partition, Offset, SecurityConfig,
    UltraPerformanceEngine, GPUAcceleratedEngine, GPUAccelerationType, HybridConfig, DistributionStrategy,
    CompressionType,
};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;

/// セキュリティベンチマーク設定
#[derive(Debug, Clone)]
struct SecurityBenchmarkConfig {
    pub name: &'static str,
    pub event_count: usize,
    pub event_size: usize,
    pub attack_type: AttackType,
    pub security_config: SecurityConfig,
}

/// 攻撃タイプ
#[derive(Debug, Clone)]
#[allow(dead_code)]
enum AttackType {
    NoAttack,
    /// 正常なリクエスト
    Normal,
    /// レート制限攻撃（大量リクエスト）
    RateLimitAttack { requests_per_second: u64 },
    /// リソース枯渇攻撃（巨大バッチ）
    ResourceExhaustionAttack { batch_size: usize },
    /// メモリ枯渇攻撃（巨大イベント）
    MemoryExhaustionAttack { event_size: usize },
    /// GPU枯渇攻撃（長時間GPU占有）
    GPUExhaustionAttack { gpu_timeout_ms: u64 },
}

/// セキュリティベンチマーク結果
#[derive(Debug)]
#[allow(dead_code)]
struct SecurityBenchmarkResult {
    pub config_name: String,
    pub attack_type: String,
    pub events_processed: u64,
    pub events_rejected: u64,
    pub throughput: f64,
    pub security_violations: u64,
    pub success_rate: f64,
    pub average_latency_ms: f64,
}

fn create_test_events(count: usize, size: usize) -> Vec<Event> {
    (0..count)
        .map(|i| Event {
            id: EventId::new(),
            topic: Topic::new("security_test_topic".to_string()),
            partition: Partition::new(0),
            offset: Some(Offset::new(i as u64)),
            key: None,
            headers: Default::default(),
            data: EventData(vec![0u8; size]),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64,
            compression: CompressionType::None,
        })
        .collect()
}

/// セキュリティ設定のバリエーション
fn create_security_configs() -> Vec<SecurityBenchmarkConfig> {
    vec![
        // 1. 正常な設定（緩い制限）
        SecurityBenchmarkConfig {
            name: "Normal_Permissive",
            event_count: 10_000,
            event_size: 1024,
            attack_type: AttackType::Normal,
            security_config: SecurityConfig {
                max_event_size: 10 * 1024 * 1024,    // 10MB
                max_batch_size: 100_000,             // 100K events
                max_concurrent_batches: 1000,        // 1000 batches
                gpu_timeout_ms: 30_000,              // 30秒
                rate_limit_per_second: 1_000_000,    // 1M events/sec
                max_memory_usage: 8 * 1024 * 1024 * 1024, // 8GB
                max_cpu_usage: 95.0,                 // 95%
                max_gpu_usage: 95.0,                 // 95%
            },
        },
        
        // 2. 厳格なセキュリティ設定
        SecurityBenchmarkConfig {
            name: "Security_Strict",
            event_count: 10_000,
            event_size: 1024,
            attack_type: AttackType::Normal,
            security_config: SecurityConfig {
                max_event_size: 1024 * 1024,         // 1MB
                max_batch_size: 10_000,              // 10K events
                max_concurrent_batches: 100,         // 100 batches
                gpu_timeout_ms: 5_000,               // 5秒
                rate_limit_per_second: 100_000,      // 100K events/sec
                max_memory_usage: 2 * 1024 * 1024 * 1024, // 2GB
                max_cpu_usage: 80.0,                 // 80%
                max_gpu_usage: 85.0,                 // 85%
            },
        },
        
        // 3. レート制限攻撃対策テスト
        SecurityBenchmarkConfig {
            name: "Rate_Limit_Defense",
            event_count: 200_000, // 大量リクエスト
            event_size: 512,
            attack_type: AttackType::RateLimitAttack { requests_per_second: 500_000 },
            security_config: SecurityConfig {
                rate_limit_per_second: 100_000,      // 100K events/sec制限
                max_batch_size: 50_000,              // バッチサイズ制限
                ..SecurityConfig::default()
            },
        },
        
        // 4. リソース枯渇攻撃対策テスト
        SecurityBenchmarkConfig {
            name: "Resource_Exhaustion_Defense",
            event_count: 500_000, // 巨大バッチ
            event_size: 1024,
            attack_type: AttackType::ResourceExhaustionAttack { batch_size: 500_000 },
            security_config: SecurityConfig {
                max_batch_size: 50_000,              // 50K events制限
                max_concurrent_batches: 50,          // 50並列制限
                max_memory_usage: 1024 * 1024 * 1024, // 1GB制限
                ..SecurityConfig::default()
            },
        },
        
        // 5. メモリ枯渇攻撃対策テスト
        SecurityBenchmarkConfig {
            name: "Memory_Exhaustion_Defense",
            event_count: 1_000,
            event_size: 10 * 1024 * 1024, // 10MB巨大イベント
            attack_type: AttackType::MemoryExhaustionAttack { event_size: 10 * 1024 * 1024 },
            security_config: SecurityConfig {
                max_event_size: 1024 * 1024,         // 1MB制限
                max_memory_usage: 512 * 1024 * 1024, // 512MB制限
                ..SecurityConfig::default()
            },
        },
        
        // 6. GPU枯渇攻撃対策テスト
        SecurityBenchmarkConfig {
            name: "GPU_Timeout_Defense",
            event_count: 10_000,
            event_size: 4096,
            attack_type: AttackType::GPUExhaustionAttack { gpu_timeout_ms: 10_000 },
            security_config: SecurityConfig {
                gpu_timeout_ms: 3_000,               // 3秒GPU制限
                max_gpu_usage: 80.0,                 // 80%制限
                ..SecurityConfig::default()
            },
        },
    ]
}

/// Ultra Performance Engine セキュリティベンチマーク
fn bench_ultra_performance_security(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    let mut group = c.benchmark_group("UltraPerformance_Security");
    
    for config in create_security_configs() {
        let benchmark_id = BenchmarkId::new(
            format!("Ultra_{}", config.name),
            format!("{}_events", config.event_count)
        );
        
        group.bench_with_input(
            benchmark_id,
            &config,
            |b, config| {
                b.iter(|| {
                    let result = rt.block_on(run_ultra_performance_security_test(config.clone()));
                    black_box(result)
                });
            },
        );
    }
    
    group.finish();
}

/// GPU Acceleration Engine セキュリティベンチマーク
fn bench_gpu_acceleration_security(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    let mut group = c.benchmark_group("GPUAcceleration_Security");
    
    for config in create_security_configs() {
        let benchmark_id = BenchmarkId::new(
            format!("GPU_{}", config.name),
            format!("{}_events", config.event_count)
        );
        
        group.bench_with_input(
            benchmark_id,
            &config,
            |b, config| {
                b.iter(|| {
                    let result = rt.block_on(run_gpu_acceleration_security_test(config.clone()));
                    black_box(result)
                });
            },
        );
    }
    
    group.finish();
}

/// セキュリティ対策効果比較ベンチマーク
fn bench_security_comparison(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    let mut group = c.benchmark_group("Security_Comparison");
    
    // セキュリティなし vs セキュリティあり比較
    let test_cases = vec![
        ("No_Security", None),
        ("With_Security_Permissive", Some(SecurityConfig {
            rate_limit_per_second: 1_000_000,
            max_batch_size: 100_000,
            gpu_timeout_ms: 30_000,
            ..SecurityConfig::default()
        })),
        ("With_Security_Strict", Some(SecurityConfig {
            rate_limit_per_second: 100_000,
            max_batch_size: 10_000,
            gpu_timeout_ms: 5_000,
            ..SecurityConfig::default()
        })),
    ];
    
    for (name, security_config) in test_cases {
        let benchmark_id = BenchmarkId::new(name, "10K_events");
        
        group.bench_with_input(
            benchmark_id,
            &security_config,
            |b, security_config| {
                b.iter(|| {
                    let result = rt.block_on(run_security_comparison_test(security_config.clone()));
                    black_box(result)
                });
            },
        );
    }
    
    group.finish();
}

/// Ultra Performance Engine セキュリティテスト実行
async fn run_ultra_performance_security_test(
    config: SecurityBenchmarkConfig,
) -> SecurityBenchmarkResult {
    let start_time = Instant::now();
    
    // セキュアなエンジン作成（試行）
    let mut engine = match UltraPerformanceEngine::new_with_security(config.security_config.clone()) {
        Ok(engine) => engine,
        Err(_) => {
            // フォールバック：標準エンジン + 手動セキュリティチェック
            return SecurityBenchmarkResult {
                config_name: config.name.to_string(),
                attack_type: format!("{:?}", config.attack_type),
                events_processed: 0,
                events_rejected: config.event_count as u64,
                throughput: 0.0,
                security_violations: 1,
                success_rate: 0.0,
                average_latency_ms: 0.0,
            };
        }
    };
    
    // 攻撃シミュレーション
    let events = create_test_events(config.event_count, config.event_size);
    let mut events_processed = 0u64;
    let mut events_rejected = 0u64;
    
    match config.attack_type {
        AttackType::Normal => {
            // 正常処理
            match engine.secure_ultra_batch_process(events).await {
                Ok(offsets) => events_processed = offsets.len() as u64,
                Err(_) => events_rejected = config.event_count as u64,
            }
        }
        AttackType::RateLimitAttack { requests_per_second: _ } => {
            // 小バッチで高頻度攻撃シミュレーション
            for chunk in events.chunks(1000) {
                match engine.secure_ultra_batch_process(chunk.to_vec()).await {
                    Ok(offsets) => events_processed += offsets.len() as u64,
                    Err(_) => events_rejected += chunk.len() as u64,
                }
                // 高頻度リクエストシミュレーション（わずかな遅延）
                tokio::time::sleep(Duration::from_micros(1)).await;
            }
        }
        AttackType::ResourceExhaustionAttack { batch_size: _ } => {
            // 巨大バッチ攻撃
            match engine.secure_ultra_batch_process(events).await {
                Ok(offsets) => events_processed = offsets.len() as u64,
                Err(_) => events_rejected = config.event_count as u64,
            }
        }
        _ => {
            // その他の攻撃タイプ（簡略実装）
            match engine.secure_ultra_batch_process(events).await {
                Ok(offsets) => events_processed = offsets.len() as u64,
                Err(_) => events_rejected = config.event_count as u64,
            }
        }
    }
    
    let duration = start_time.elapsed();
    let throughput = events_processed as f64 / duration.as_secs_f64();
    let success_rate = if events_processed + events_rejected > 0 {
        (events_processed as f64 / (events_processed + events_rejected) as f64) * 100.0
    } else {
        0.0
    };
    
    // セキュリティメトリクス取得
    let security_metrics = engine.get_security_metrics();
    let security_violations = security_metrics.rate_limit_violations.load(std::sync::atomic::Ordering::Relaxed) +
                              security_metrics.resource_limit_violations.load(std::sync::atomic::Ordering::Relaxed);
    
    SecurityBenchmarkResult {
        config_name: config.name.to_string(),
        attack_type: format!("{:?}", config.attack_type),
        events_processed,
        events_rejected,
        throughput,
        security_violations,
        success_rate,
        average_latency_ms: duration.as_millis() as f64 / config.event_count as f64,
    }
}

/// GPU Acceleration Engine セキュリティテスト実行
async fn run_gpu_acceleration_security_test(
    config: SecurityBenchmarkConfig,
) -> SecurityBenchmarkResult {
    let start_time = Instant::now();
    
    // ハイブリッド設定
    let hybrid_config = HybridConfig {
        cpu_ratio: 0.3,
        gpu_ratio: 0.7,
        distribution_strategy: DistributionStrategy::Adaptive,
        adaptive_balancing: true,
    };
    
    // セキュアなGPUエンジン作成（試行）
    let mut engine = match GPUAcceleratedEngine::new_secure(
        GPUAccelerationType::CPUSimulation, // 安全なシミュレーション
        hybrid_config,
        config.security_config.clone(),
    ) {
        Ok(engine) => engine,
        Err(_) => {
            // フォールバック：エラー結果
            return SecurityBenchmarkResult {
                config_name: format!("GPU_{}", config.name),
                attack_type: format!("{:?}", config.attack_type),
                events_processed: 0,
                events_rejected: config.event_count as u64,
                throughput: 0.0,
                security_violations: 1,
                success_rate: 0.0,
                average_latency_ms: 0.0,
            };
        }
    };
    
    // GPU攻撃シミュレーション実行
    let events = create_test_events(config.event_count, config.event_size);
    let mut events_processed = 0u64;
    let mut events_rejected = 0u64;
    
    match engine.secure_gpu_accelerated_process(events).await {
        Ok(offsets) => events_processed = offsets.len() as u64,
        Err(_) => events_rejected = config.event_count as u64,
    }
    
    let duration = start_time.elapsed();
    let throughput = events_processed as f64 / duration.as_secs_f64();
    let success_rate = if events_processed + events_rejected > 0 {
        (events_processed as f64 / (events_processed + events_rejected) as f64) * 100.0
    } else {
        0.0
    };
    
    // セキュリティメトリクス
    let security_metrics = engine.get_security_metrics();
    let security_violations = security_metrics.rate_limit_violations.load(std::sync::atomic::Ordering::Relaxed);
    
    SecurityBenchmarkResult {
        config_name: format!("GPU_{}", config.name),
        attack_type: format!("{:?}", config.attack_type),
        events_processed,
        events_rejected,
        throughput,
        security_violations,
        success_rate,
        average_latency_ms: duration.as_millis() as f64 / config.event_count as f64,
    }
}

/// セキュリティ有無比較テスト
async fn run_security_comparison_test(
    security_config: Option<SecurityConfig>,
) -> SecurityBenchmarkResult {
    let start_time = Instant::now();
    let events = create_test_events(10_000, 1024);
    
    let (events_processed, security_violations) = match security_config {
        None => {
            // セキュリティなし：標準Ultra Performance Engine
            let mut engine = UltraPerformanceEngine::new().unwrap();
            match engine.ultra_batch_process(events).await {
                Ok(offsets) => (offsets.len() as u64, 0),
                Err(_) => (0, 0),
            }
        }
        Some(config) => {
            // セキュリティあり
            let mut engine = UltraPerformanceEngine::new_with_security(config).unwrap();
            match engine.secure_ultra_batch_process(events).await {
                Ok(offsets) => {
                    let metrics = engine.get_security_metrics();
                    let violations = metrics.rate_limit_violations.load(std::sync::atomic::Ordering::Relaxed);
                    (offsets.len() as u64, violations)
                }
                Err(_) => (0, 1),
            }
        }
    };
    
    let duration = start_time.elapsed();
    let throughput = events_processed as f64 / duration.as_secs_f64();
    
    SecurityBenchmarkResult {
        config_name: "Comparison".to_string(),
        attack_type: "Normal".to_string(),
        events_processed,
        events_rejected: 10_000 - events_processed,
        throughput,
        security_violations,
        success_rate: (events_processed as f64 / 10_000.0) * 100.0,
        average_latency_ms: duration.as_millis() as f64 / 10_000.0,
    }
}

criterion_group!(
    benches,
    bench_ultra_performance_security,
    bench_gpu_acceleration_security,
    bench_security_comparison
);
criterion_main!(benches); 