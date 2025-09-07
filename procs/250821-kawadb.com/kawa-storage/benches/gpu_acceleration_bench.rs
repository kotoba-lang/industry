use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use kawa_storage::{
    Event, EventData, EventId, Topic, Partition,
    GPUAcceleratedEngine, GPUAccelerationType, HybridConfig,
    UltraPerformanceEngine,
};
use tokio::runtime::Runtime;

/// Phase 2: GPU加速 vs Phase 1: Ultra Performance Engine ベンチマーク
fn gpu_vs_ultra_performance_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    // 大規模バッチサイズでテスト
    let batch_sizes = vec![1000, 5000, 10000, 25000, 50000, 100000];
    
    let mut group = c.benchmark_group("gpu_vs_ultra_performance");
    group.significance_level(0.1).sample_size(20);
    
    for batch_size in batch_sizes {
        group.throughput(Throughput::Elements(batch_size as u64));
        
        // 🚀 Phase 2: GPU加速エンジン ベンチマーク
        group.bench_with_input(
            BenchmarkId::new("gpu_accelerated_engine", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let mut gpu_engine = GPUAcceleratedEngine::new(GPUAccelerationType::WebGPU, HybridConfig::default())
                            .unwrap();
                        let events = create_test_events(size);
                        
                        let start = std::time::Instant::now();
                        let _results = gpu_engine.gpu_ultra_batch_process(events).await.unwrap();
                        let duration = start.elapsed();
                        
                        let throughput = size as f64 / duration.as_secs_f64();
                        
                        // 🚀 10M+ events/sec 達成の場合は特別ログ
                        if throughput > 10_000_000.0 {
                            println!("🚀🚀🚀 10M+ GPU ACCELERATION: {:.0} events/sec achieved with {} events! 🚀🚀🚀", 
                                   throughput, size);
                        } else if throughput > 5_000_000.0 {
                            println!("🚀 GPU ACCELERATION SUCCESS: {:.0} events/sec achieved with {} events! 🚀", 
                                   throughput, size);
                        }
                        
                        black_box(throughput)
                    })
                })
            },
        );
        
        // 📊 Phase 1: Ultra Performance Engine ベンチマーク（比較用）
        group.bench_with_input(
            BenchmarkId::new("ultra_performance_engine", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let mut ultra_engine = UltraPerformanceEngine::new().unwrap();
                        let events = create_test_events(size);
                        
                        let start = std::time::Instant::now();
                        let _results = ultra_engine.ultra_batch_process(events).await.unwrap();
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

/// 10M+ events/sec 目標チャレンジベンチマーク
fn gpu_10m_target_challenge(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    // Phase 2 の最終目標: 10M+ events/sec
    const TARGET_10M: f64 = 10_000_000.0;
    const TARGET_5M: f64 = 5_000_000.0;
    
    let mut group = c.benchmark_group("gpu_10m_target_challenge");
    group.significance_level(0.1).sample_size(10);
    
    // 超大規模バッチテスト（1M events）
    let challenge_batch_size = 1_000_000;
    group.throughput(Throughput::Elements(challenge_batch_size));
    
    group.bench_function("gpu_1m_events_challenge", |b| {
        b.iter(|| {
            rt.block_on(async {
                let mut gpu_engine = GPUAcceleratedEngine::new(GPUAccelerationType::WebGPU, HybridConfig::default())
                    .unwrap();
                let events = create_test_events(challenge_batch_size as usize);
                
                let start = std::time::Instant::now();
                let _results = gpu_engine.gpu_ultra_batch_process(events).await.unwrap();
                let duration = start.elapsed();
                
                let throughput = challenge_batch_size as f64 / duration.as_secs_f64();
                
                // 🎯 10M+ events/sec 目標との比較
                let target_10m_ratio = throughput / TARGET_10M;
                let target_5m_ratio = throughput / TARGET_5M;
                
                println!("\n📊 GPU 1M Events Challenge:");
                println!("   🚀 GPU Ultra: {:.0} events/sec", throughput);
                println!("   🎯 vs 10M target: {:.2}x", target_10m_ratio);
                println!("   🎯 vs 5M target: {:.2}x", target_5m_ratio);
                
                if throughput > TARGET_10M {
                    println!("   🏆🏆🏆 10M+ EVENTS/SEC ACHIEVED!!! 🏆🏆🏆");
                } else if throughput > TARGET_5M {
                    println!("   🏆🏆 5M+ EVENTS/SEC ACHIEVED!! 🏆🏆");
                } else if throughput > 3_000_000.0 {
                    println!("   🏆 3M+ EVENTS/SEC - REDPANDA KILLER! 🏆");
                } else {
                    println!("   🟡 Performance improvement needed for 10M target");
                }
                
                black_box(throughput)
            })
        })
    });
    
    group.finish();
}

/// GPU加速タイプ別性能比較
fn gpu_acceleration_types_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    let acceleration_types = vec![
        ("WebGPU", GPUAccelerationType::WebGPU),
        ("CPUSimulation", GPUAccelerationType::CPUSimulation),
    ];
    
    let batch_size = 50_000;
    
    let mut group = c.benchmark_group("gpu_acceleration_types");
    group.significance_level(0.1).sample_size(20);
    group.throughput(Throughput::Elements(batch_size));
    
    for (name, accel_type) in acceleration_types {
        group.bench_with_input(
            BenchmarkId::new("gpu_type", name),
            &accel_type,
            |b, &ref gpu_type| {
                b.iter(|| {
                    rt.block_on(async {
                        let mut gpu_engine = GPUAcceleratedEngine::new(gpu_type.clone(), HybridConfig::default())
                            .unwrap();
                        let events = create_test_events(batch_size as usize);
                        
                        let start = std::time::Instant::now();
                        let _results = gpu_engine.gpu_ultra_batch_process(events).await.unwrap();
                        let duration = start.elapsed();
                        
                        let throughput = batch_size as f64 / duration.as_secs_f64();
                        
                        println!("📊 {} Performance: {:.0} events/sec", name, throughput);
                        
                        black_box(throughput)
                    })
                })
            },
        );
    }
    
    group.finish();
}

/// Hybrid CPU-GPU スケーラビリティベンチマーク
fn hybrid_scalability_benchmark(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    // 異なるバッチサイズでHybrid処理の効果を測定
    let batch_sizes = vec![1000, 10000, 50000, 100000];
    
    let mut group = c.benchmark_group("hybrid_scalability");
    group.significance_level(0.1).sample_size(15);
    
    for batch_size in batch_sizes {
        group.throughput(Throughput::Elements(batch_size as u64));
        
        group.bench_with_input(
            BenchmarkId::new("hybrid_gpu_cpu", batch_size),
            &batch_size,
            |b, &size| {
                b.iter(|| {
                    rt.block_on(async {
                        let mut gpu_engine = GPUAcceleratedEngine::new(GPUAccelerationType::WebGPU, HybridConfig::default())
                            .unwrap();
                        let events = create_test_events(size);
                        
                        let start = std::time::Instant::now();
                        let _results = gpu_engine.gpu_ultra_batch_process(events).await.unwrap();
                        let duration = start.elapsed();
                        
                        let throughput = size as f64 / duration.as_secs_f64();
                        
                        // GPU メトリクス取得
                        let metrics = gpu_engine.get_gpu_metrics();
                        let peak_throughput = metrics.peak_throughput.load(std::sync::atomic::Ordering::Relaxed);
                        
                        println!("📊 Hybrid Scalability: {} events -> {:.0} events/sec (peak: {})", 
                               size, throughput, peak_throughput);
                        
                        black_box(throughput)
                    })
                })
            },
        );
    }
    
    group.finish();
}

/// Phase 1 vs Phase 2 最終比較ベンチマーク
fn phase1_vs_phase2_final_comparison(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();
    
    let mut group = c.benchmark_group("phase1_vs_phase2_final");
    group.significance_level(0.1).sample_size(10);
    
    // 最終比較用の大規模バッチ
    let final_batch_size = 200_000;
    group.throughput(Throughput::Elements(final_batch_size));
    
    // Phase 1: Ultra Performance Engine (3M+ events/sec 実証済み)
    group.bench_function("phase1_ultra_final", |b| {
        b.iter(|| {
            rt.block_on(async {
                let mut ultra_engine = UltraPerformanceEngine::new().unwrap();
                let events = create_test_events(final_batch_size as usize);
                
                let start = std::time::Instant::now();
                let _results = ultra_engine.ultra_batch_process(events).await.unwrap();
                let duration = start.elapsed();
                
                let throughput = final_batch_size as f64 / duration.as_secs_f64();
                
                println!("📊 Phase 1 Final: {:.0} events/sec (Redpanda Killer)", throughput);
                
                black_box(throughput)
            })
        })
    });
    
    // Phase 2: GPU Acceleration Engine (10M+ events/sec 目標)
    group.bench_function("phase2_gpu_final", |b| {
        b.iter(|| {
            rt.block_on(async {
                let mut gpu_engine = GPUAcceleratedEngine::new(GPUAccelerationType::WebGPU, HybridConfig::default())
                    .unwrap();
                let events = create_test_events(final_batch_size as usize);
                
                let start = std::time::Instant::now();
                let _results = gpu_engine.gpu_ultra_batch_process(events).await.unwrap();
                let duration = start.elapsed();
                
                let throughput = final_batch_size as f64 / duration.as_secs_f64();
                
                println!("📊 Phase 2 Final: {:.0} events/sec (GPU Acceleration)", throughput);
                
                // Phase間の比較
                let phase1_baseline = 3_000_000.0; // Phase 1の実証済み性能
                let improvement_ratio = throughput / phase1_baseline;
                
                println!("   📈 Phase 1 vs Phase 2: {:.2}x improvement", improvement_ratio);
                
                if throughput > 10_000_000.0 {
                    println!("   🎊🎊🎊 PHASE 2 COMPLETE: 10M+ EVENTS/SEC! 🎊🎊🎊");
                } else if throughput > 5_000_000.0 {
                    println!("   🎊🎊 PHASE 2 SUCCESS: 5M+ EVENTS/SEC! 🎊🎊");
                } else if improvement_ratio > 1.5 {
                    println!("   🎊 PHASE 2 PROGRESS: {:.1}x faster than Phase 1! 🎊", improvement_ratio);
                }
                
                black_box(throughput)
            })
        })
    });
    
    group.finish();
}

/// テスト用イベントを生成
fn create_test_events(count: usize) -> Vec<Event> {
    (0..count)
        .map(|i| Event::new(
            EventId::new(),
            Topic::new("gpu-benchmark-topic"),
            Partition::new((i % 16) as u32),
            EventData::from_bytes(format!("GPU acceleration test event {}", i).into_bytes()),
        ))
        .collect()
}

criterion_group!(
    phase2_gpu_benches,
    gpu_vs_ultra_performance_benchmark,
    gpu_10m_target_challenge,
    gpu_acceleration_types_benchmark,
    hybrid_scalability_benchmark,
    phase1_vs_phase2_final_comparison
);

criterion_main!(phase2_gpu_benches); 