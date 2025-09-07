#![allow(dead_code, unused_imports, unused_variables)]
use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use kawa_storage::{
    Event, EventData, EventId, Topic, Partition,
    UltraPerformanceEngine, LockFreeRingBuffer, SIMDBatchProcessor,
    StorageEngine, StorageConfig
};
use std::sync::Arc;
use tokio::runtime::Runtime;

/// Redpanda性能比較ベンチマーク
fn redpanda_vs_kawa_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    // 異なるバッチサイズでのテスト
    let batch_sizes = vec![100, 500, 1000, 5000, 10000, 50000];
    
    let mut group = c.benchmark_group("redpanda_killer");
    group.significance_level(0.1).sample_size(30);
    
    for batch_size in batch_sizes {
        // スループット測定のためのメタデータ設定
        group.throughput(Throughput::Elements(batch_size as u64));
        
        // 🚀 Ultra Performance Engine ベンチマーク
        group.bench_with_input(
            BenchmarkId::new("ultra_engine", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let mut engine = UltraPerformanceEngine::new().unwrap();
                        let events = create_test_events(size);
                        
                        let start = std::time::Instant::now();
                        let _results = engine.ultra_batch_process(events).await.unwrap();
                        let duration = start.elapsed();
                        
                        let throughput = size as f64 / duration.as_secs_f64();
                        
                        // 🚀 2M+ events/sec 達成の場合は特別ログ
                        if throughput > 2_000_000.0 {
                            println!("🚀 REDPANDA KILLER: {:.0} events/sec achieved with {} events!", 
                                   throughput, size);
                        }
                        
                        black_box(throughput)
                    })
                })
            },
        );
        
        // 📊 従来ストレージエンジンとの比較
        group.bench_with_input(
            BenchmarkId::new("traditional_storage", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let storage = create_test_storage().await;
                        let events = create_test_events_for_traditional(size);
                        
                        let start = std::time::Instant::now();
                        let _results = storage.append_events_batch(events).await.unwrap();
                        let duration = start.elapsed();
                        
                        let throughput = size as f64 / duration.as_secs_f64();
                        black_box(throughput)
                    })
                })
            },
        );
        
        // 🔥 Lock-Free Ring Buffer ベンチマーク
        group.bench_with_input(
            BenchmarkId::new("lock_free_ring_buffer", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let ring_buffer = Arc::new(
                            LockFreeRingBuffer::new(1024 * 1024).unwrap()
                        );
                        let events = create_test_events(size);
                        
                        let start = std::time::Instant::now();
                        
                        // 並列書き込み
                        let futures: Vec<_> = events.chunks(size / 8).map(|chunk| {
                            let ring = Arc::clone(&ring_buffer);
                            let chunk = chunk.to_vec();
                            tokio::spawn(async move {
                                for event in chunk {
                                    while ring.try_push(event.clone()).is_err() {
                                        tokio::task::yield_now().await;
                                    }
                                }
                            })
                        }).collect();
                        
                        for future in futures {
                            future.await.unwrap();
                        }
                        
                        let duration = start.elapsed();
                        let throughput = size as f64 / duration.as_secs_f64();
                        
                        black_box(throughput)
                    })
                })
            },
        );
        
        // ⚡ SIMD バッチプロセッサ ベンチマーク
        group.bench_with_input(
            BenchmarkId::new("simd_batch_processor", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let mut processor = SIMDBatchProcessor::new(None);
                        let events = create_test_events(size);
                        
                        let start = std::time::Instant::now();
                        let _results = processor.process_batch_simd(events).await.unwrap();
                        let duration = start.elapsed();
                        
                        let throughput = size as f64 / duration.as_secs_f64();
                        black_box(throughput)
                    })
                })
            },
        );
    }
    
    group.finish();
}

/// 実際のRedpanda性能目標との比較
fn redpanda_performance_target_comparison(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    // Redpandaの公称性能: 1M+ events/sec
    const REDPANDA_TARGET: f64 = 1_000_000.0;
    const SUPER_TARGET: f64 = 2_000_000.0; // 目標: Redpandaの2倍
    
    let mut group = c.benchmark_group("redpanda_target_comparison");
    group.significance_level(0.1).sample_size(10);
    
    // 大規模バッチテスト（100K events）
    let large_batch_size = 100_000;
    group.throughput(Throughput::Elements(large_batch_size));
    
    group.bench_function("ultra_engine_vs_redpanda", |b| {
        b.iter(|| {
            rt.block_on(async {
                let mut engine = UltraPerformanceEngine::new().unwrap();
                let events = create_test_events(large_batch_size as usize);
                
                let start = std::time::Instant::now();
                let _results = engine.ultra_batch_process(events).await.unwrap();
                let duration = start.elapsed();
                
                let throughput = large_batch_size as f64 / duration.as_secs_f64();
                
                // 🎯 性能目標との比較
                let redpanda_ratio = throughput / REDPANDA_TARGET;
                let super_ratio = throughput / SUPER_TARGET;
                
                println!("\n📊 Performance Comparison:");
                println!("   🚀 Kawa Ultra: {:.0} events/sec", throughput);
                println!("   📈 vs Redpanda: {:.2}x faster", redpanda_ratio);
                println!("   🎯 Super Target: {:.2}x of 2M target", super_ratio);
                
                if throughput > SUPER_TARGET {
                    println!("   🏆 REDPANDA KILLER ACHIEVED! 🏆");
                } else if throughput > REDPANDA_TARGET {
                    println!("   ✅ Redpanda performance exceeded!");
                } else {
                    println!("   🟡 Performance improvement needed");
                }
                
                black_box(throughput)
            })
        })
    });
    
    group.finish();
}

/// 並列性能スケーラビリティテスト
fn parallelism_scalability_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    let thread_counts = vec![1, 2, 4, 8, 16];
    let batch_size = 10_000;
    
    let mut group = c.benchmark_group("parallelism_scalability");
    group.significance_level(0.1).sample_size(20);
    group.throughput(Throughput::Elements(batch_size));
    
    for thread_count in thread_counts {
        group.bench_with_input(
            BenchmarkId::new("parallel_ultra_engine", thread_count),
            &thread_count,
            |b, &threads| {
                b.iter(|| {
                    rt.block_on(async {
                        // 複数のエンジンで並列処理
                        let futures: Vec<_> = (0..threads).map(|_| {
                            tokio::spawn(async move {
                                let mut engine = UltraPerformanceEngine::new().unwrap();
                                let events = create_test_events(batch_size as usize / threads);
                                
                                let start = std::time::Instant::now();
                                let _results = engine.ultra_batch_process(events).await.unwrap();
                                let duration = start.elapsed();
                                
                                (batch_size as usize / threads, duration)
                            })
                        }).collect();
                        
                        let mut total_events = 0;
                        let mut max_duration = std::time::Duration::ZERO;
                        
                        for future in futures {
                            let (events, duration) = future.await.unwrap();
                            total_events += events;
                            max_duration = max_duration.max(duration);
                        }
                        
                        let throughput = total_events as f64 / max_duration.as_secs_f64();
                        
                        println!("📊 {} threads: {:.0} events/sec", threads, throughput);
                        
                        black_box(throughput)
                    })
                })
            },
        );
    }
    
    group.finish();
}

/// テスト用イベントを生成
fn create_test_events(count: usize) -> Vec<Event> {
    (0..count)
        .map(|i| Event::new(
            EventId::new(),
            Topic::new("benchmark-topic"),
            Partition::new((i % 4) as u32),
            EventData::from_bytes(format!("Redpanda killer test event {}", i).into_bytes()),
        ))
        .collect()
}

/// 従来ストレージ用のテストイベントを生成
fn create_test_events_for_traditional(count: usize) -> Vec<(Topic, Partition, EventData)> {
    (0..count)
        .map(|i| (
            Topic::new("benchmark-topic"),
            Partition::new((i % 4) as u32),
            EventData::from_bytes(format!("Traditional storage test event {}", i).into_bytes()),
        ))
        .collect()
}

/// テスト用ストレージエンジンを作成
async fn create_test_storage() -> StorageEngine {
    let temp_dir = tempfile::tempdir().unwrap();
    let config = StorageConfig {
        data_dir: temp_dir.path().to_path_buf(),
        segment_size: 64 * 1024 * 1024, // 64MB
        sync_interval_ms: 100,
        enable_compression: false,
        memory_pool_size: 128 * 1024 * 1024, // 128MB
        batch_size: 1000,
        worker_count: None,
        compression_type: None,
    };
    
    StorageEngine::new(config).await.unwrap()
}

criterion_group!(
    redpanda_killer_benches,
            redpanda_vs_kawa_benchmark,
    redpanda_performance_target_comparison,
    parallelism_scalability_benchmark
);

criterion_main!(redpanda_killer_benches); 