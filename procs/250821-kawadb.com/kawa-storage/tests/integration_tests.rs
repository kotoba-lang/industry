#![allow(dead_code, unused_imports, unused_variables)]

//! # Kawa Storage Integration Tests
//!
//! ストレージエンジンの統合テストを実装。
//! 実際のファイルI/Oを使用した機能テスト。

use kawa_storage::{
    event::{Event, EventData, EventId, CompressionType},
    Offset, Partition, StorageConfig, StorageEngine, StorageResult, Topic,
};
use std::sync::Arc;
use std::time::Duration;
use tempfile::TempDir;
use tokio;
use std::fs::OpenOptions;
use std::io::{Write, Seek};

/// テスト用のストレージエンジンを作成
/// 
/// # Returns
/// * `(StorageEngine, TempDir)` - ストレージエンジンと一時ディレクトリ
async fn create_test_storage() -> StorageResult<(StorageEngine, TempDir)> {
    let temp_dir = TempDir::new().expect("Failed to create temp directory");
    let config = StorageConfig {
        data_dir: temp_dir.path().to_path_buf(),
        segment_size: 1024 * 1024, // 1MB
        sync_interval_ms: 100,
        enable_compression: false,
        compression_type: None,
        memory_pool_size: 64 * 1024 * 1024,
        batch_size: 1000,
        worker_count: Some(4),
    };
    
    let storage = StorageEngine::new(config).await?;
    Ok((storage, temp_dir))
}

/// テスト用のイベントデータを作成
fn create_test_event_data(content: &str) -> EventData {
    EventData::from_json(&format!(r#"{{"message": "{}"}}"#, content))
}

#[tokio::test]
async fn test_storage_engine_basic_operations() -> StorageResult<()> {
    // テスト用ストレージを作成
    let (storage, _temp_dir) = create_test_storage().await?;
    
    let topic = Topic::new("test-topic");
    let partition = Partition::new(0);
    
    // イベントを追加
    let event_data = create_test_event_data("hello world");
    let (event_id, offset) = storage.append_event(&topic, partition, event_data).await?;
    
    // 追加されたイベントを確認
    assert_eq!(offset, Offset::new(0));
    assert!(!event_id.to_string().is_empty());
    
    // 最新オフセットを確認
    let latest_offset = storage.get_latest_offset(&topic, partition).await?;
    assert_eq!(latest_offset, Some(Offset::new(0)));
    
    Ok(())
}

#[tokio::test]
async fn test_event_read_write() -> StorageResult<()> {
    let (storage, _temp_dir) = create_test_storage().await?;
    
    let topic = Topic::new("test-topic");
    let partition = Partition::new(0);
    
    // 複数のイベントを追加
    let messages = vec!["message1", "message2", "message3"];
    let mut event_ids = Vec::new();
    
    for (i, msg) in messages.iter().enumerate() {
        let event_data = create_test_event_data(msg);
        let (event_id, offset) = storage.append_event(&topic, partition, event_data).await?;
        event_ids.push(event_id);
        assert_eq!(offset, Offset::new(i as u64));
    }
    
    // イベントを読み取り
    let events = storage.read_events(&topic, partition, Offset::new(0), 10).await?;
    assert_eq!(events.len(), 3);
    
    // イベント内容を確認
    for (i, event) in events.iter().enumerate() {
        assert_eq!(event.id, event_ids[i]);
        assert_eq!(event.topic, topic);
        assert_eq!(event.partition, partition);
        
        let data_str = event.data.as_str().expect("Invalid UTF-8");
        assert!(data_str.contains(&messages[i]));
    }
    
    Ok(())
}

#[tokio::test]
async fn test_multiple_partitions() -> StorageResult<()> {
    let (storage, _temp_dir) = create_test_storage().await?;
    
    let topic = Topic::new("multi-partition-topic");
    
    // 複数のパーティションにイベントを追加
    for partition_num in 0..3 {
        let partition = Partition::new(partition_num);
        
        for i in 0..5 {
            let event_data = create_test_event_data(&format!("p{}_msg{}", partition_num, i));
            let (_, offset) = storage.append_event(&topic, partition, event_data).await?;
            assert_eq!(offset, Offset::new(i as u64));
        }
    }
    
    // 各パーティションの最新オフセットを確認
    for partition_num in 0..3 {
        let partition = Partition::new(partition_num);
        let latest_offset = storage.get_latest_offset(&topic, partition).await?;
        assert_eq!(latest_offset, Some(Offset::new(4))); // 0-indexed, so 4 is the 5th event
    }
    
    // 各パーティションからイベントを読み取り
    for partition_num in 0..3 {
        let partition = Partition::new(partition_num);
        let events = storage.read_events(&topic, partition, Offset::new(0), 10).await?;
        assert_eq!(events.len(), 5);
        
        for (i, event) in events.iter().enumerate() {
            let data_str = event.data.as_str().expect("Invalid UTF-8");
            let expected_content = format!("p{}_msg{}", partition_num, i);
            assert!(data_str.contains(&expected_content));
        }
    }
    
    Ok(())
}

#[tokio::test]
async fn test_offset_range_reading() -> StorageResult<()> {
    let (storage, _temp_dir) = create_test_storage().await?;
    
    let topic = Topic::new("range-test-topic");
    let partition = Partition::new(0);
    
    // 10個のイベントを追加
    for i in 0..10 {
        let event_data = create_test_event_data(&format!("message_{}", i));
        storage.append_event(&topic, partition, event_data).await?;
    }
    
    // 部分的な範囲を読み取り
    let events = storage.read_events(&topic, partition, Offset::new(3), 3).await?;
    assert_eq!(events.len(), 3);
    
    for (i, event) in events.iter().enumerate() {
        let data_str = event.data.as_str().expect("Invalid UTF-8");
        let expected_content = format!("message_{}", i + 3);
        assert!(data_str.contains(&expected_content));
    }
    
    // 範囲外のオフセットから読み取り
    let events = storage.read_events(&topic, partition, Offset::new(20), 5).await?;
    assert_eq!(events.len(), 0);
    
    Ok(())
}

#[tokio::test]
async fn test_storage_persistence() -> StorageResult<()> {
    let temp_dir = TempDir::new().expect("Failed to create temp directory");
    let config = StorageConfig {
        data_dir: temp_dir.path().to_path_buf(),
        segment_size: 1024 * 1024, // 1MB
        sync_interval_ms: 100,
        enable_compression: false,
        compression_type: None,
        memory_pool_size: 64 * 1024 * 1024,
        batch_size: 1000,
        worker_count: Some(4),
    };
    
    let topic = Topic::new("persistence-topic");
    let partition = Partition::new(0);
    
    // 最初のストレージインスタンスでデータを書き込み
    {
        let storage = StorageEngine::new(config.clone()).await?;
        
        for i in 0..5 {
            let event_data = create_test_event_data(&format!("persistent_msg_{}", i));
            storage.append_event(&topic, partition, event_data).await?;
        }
        
        // 明示的に同期
        storage.shutdown().await?;
    }
    
    // 新しいストレージインスタンスでデータを読み取り
    {
        let storage = StorageEngine::new(config).await?;

        let latest_offset = storage.get_latest_offset(&topic, partition).await?;
        assert_eq!(latest_offset, Some(Offset::new(4)));

        // 復旧処理が実装されたら以下のテストを有効化
        let events = storage.read_events(&topic, partition, Offset::new(0), 10).await?;
        assert_eq!(events.len(), 5);
    }
    
    Ok(())
}

#[tokio::test]
async fn test_concurrent_writes() -> StorageResult<()> {
    let (storage, _temp_dir) = create_test_storage().await?;
    let storage = std::sync::Arc::new(storage);
    
    let topic = Topic::new("concurrent-topic");
    let partition = Partition::new(0);
    
    // 並列書き込みのテスト
    let mut handles = Vec::new();
    
    for i in 0..10 {
        let storage_clone = std::sync::Arc::clone(&storage);
        let topic_clone = topic.clone();
        
        let handle = tokio::spawn(async move {
            let event_data = create_test_event_data(&format!("concurrent_msg_{}", i));
            storage_clone.append_event(&topic_clone, partition, event_data).await
        });
        
        handles.push(handle);
    }
    
    // すべての書き込みが完了することを確認
    let mut results = Vec::new();
    for handle in handles {
        let result = handle.await.expect("Task failed")?;
        results.push(result);
    }
    
    assert_eq!(results.len(), 10);
    
    // 最終的なオフセットを確認
    let latest_offset = storage.get_latest_offset(&topic, partition).await?;
    assert_eq!(latest_offset, Some(Offset::new(9)));
    
    Ok(())
}

#[cfg(test)]
mod bench {
    use super::*;
    use std::time::Instant;
    
    /// 書き込み性能のベンチマークテスト
    /// 
    /// @todo
    /// - [ ] より詳細な性能測定
    /// - [ ] メモリ使用量測定
    /// - [ ] 異なるデータサイズでのテスト
    #[tokio::test]
    #[ignore]
    async fn bench_write_performance() -> StorageResult<()> {
        let (storage, _temp_dir) = create_test_storage().await?;
        
        let topic = Topic::new("bench-topic");
        let partition = Partition::new(0);
        
        let num_events = 1000;
        let start_time = Instant::now();
        
        for i in 0..num_events {
            let event_data = create_test_event_data(&format!("bench_msg_{}", i));
            storage.append_event(&topic, partition, event_data).await?;
        }
        
        let duration = start_time.elapsed();
        let events_per_sec = num_events as f64 / duration.as_secs_f64();
        
        println!("Write performance: {} events in {:?} ({:.2} events/sec)", 
                 num_events, duration, events_per_sec);
        
        // 基本的な性能要件をチェック（調整可能）
        assert!(events_per_sec > 100.0, "Write performance too low: {:.2} events/sec", events_per_sec);
        
        Ok(())
    }
    
    /// 読み取り性能のベンチマークテスト
    #[tokio::test]
    #[ignore]
    async fn bench_read_performance() -> StorageResult<()> {
        let (storage, _temp_dir) = create_test_storage().await?;
        
        let topic = Topic::new("read-bench-topic");
        let partition = Partition::new(0);
        
        // テストデータを準備
        let num_events = 1000;
        for i in 0..num_events {
            let event_data = create_test_event_data(&format!("read_bench_msg_{}", i));
            storage.append_event(&topic, partition, event_data).await?;
        }
        
        // 読み取り性能を測定
        let start_time = Instant::now();
        let events = storage.read_events(&topic, partition, Offset::new(0), num_events).await?;
        let duration = start_time.elapsed();
        
        let events_per_sec = events.len() as f64 / duration.as_secs_f64();
        
        println!("Read performance: {} events in {:?} ({:.2} events/sec)", 
                 events.len(), duration, events_per_sec);
        
        assert_eq!(events.len(), num_events);
        assert!(events_per_sec > 1000.0, "Read performance too low: {:.2} events/sec", events_per_sec);
        
        Ok(())
    }
    
    /// 高性能メモリプール性能テスト
    #[tokio::test]
    #[ignore]
    async fn bench_memory_pool_performance() -> StorageResult<()> {
        let pool = std::sync::Arc::new(kawa_storage::memory::MemoryPool::new(
            128 * 1024 * 1024, // 128MB
            64 * 1024           // 64KB buffers
        )?);
        
        let num_operations = 10000;
        let start_time = Instant::now();
        
        // 並列でバッファ取得・使用・返却
        let mut handles = Vec::new();
        for _ in 0..10 {
            let pool_clone = std::sync::Arc::clone(&pool);
            let handle = tokio::spawn(async move {
                for _ in 0..num_operations / 10 {
                    let mut buffer = pool_clone.get_buffer().await.unwrap();
                    buffer.write(b"Performance test data").unwrap();
                    // バッファは自動的に返却される（Drop実装）
                }
            });
            handles.push(handle);
        }
        
        // すべてのタスク完了を待機
        for handle in handles {
            handle.await.unwrap();
        }
        
        let duration = start_time.elapsed();
        let ops_per_sec = num_operations as f64 / duration.as_secs_f64();
        
        println!("Memory pool performance: {} ops in {:?} ({:.0} ops/sec)", 
                 num_operations, duration, ops_per_sec);
        
        // 統計確認
        let stats = pool.stats();
        println!("Pool stats: hit_rate={:.2}%, utilization={:.2}%", 
                 stats.hit_rate() * 100.0, stats.utilization() * 100.0);
        
        // 期待する性能要件
        assert!(ops_per_sec > 50000.0, "Memory pool performance too low: {:.0} ops/sec", ops_per_sec);
        assert!(stats.hit_rate() > 0.8, "Cache hit rate too low: {:.2}", stats.hit_rate());
        
        Ok(())
    }
    
    /// バッチ書き込み性能テスト（1M+ events/sec目標）
    #[tokio::test]
    #[ignore]
    async fn bench_batch_write_performance() -> StorageResult<()> {
        let (storage, _temp_dir) = create_test_storage().await?;
        
        let topic = Topic::new("high-perf-topic");
        let partition = Partition::new(0);
        
        // 大規模バッチテスト
        let batch_sizes = vec![100, 500, 1000, 2000, 5000];
        
        for batch_size in batch_sizes {
            println!("Testing batch size: {}", batch_size);
            
            // バッチデータ準備
            let events: Vec<(Topic, Partition, EventData)> = (0..batch_size)
                .map(|i| (
                    topic.clone(),
                    partition,
                    create_test_event_data(&format!("batch_perf_msg_{}", i)),
                ))
                .collect();
            
            let start_time = Instant::now();
            let results = storage.append_events_batch(events).await?;
            let duration = start_time.elapsed();
            
            let events_per_sec = batch_size as f64 / duration.as_secs_f64();
            
            println!("Batch size {}: {} events in {:?} ({:.0} events/sec)", 
                     batch_size, results.len(), duration, events_per_sec);
            
            assert_eq!(results.len(), batch_size);
            
            // 高い性能要件をチェック
            if batch_size >= 1000 {
                assert!(events_per_sec > 100000.0, 
                        "Batch write performance too low: {:.0} events/sec for batch size {}", 
                        events_per_sec, batch_size);
            }
        }
        
        Ok(())
    }
    
    /// 並列バッチ書き込み性能テスト
    #[tokio::test]
    #[ignore]
    async fn bench_concurrent_batch_writes() -> StorageResult<()> {
        let (storage, _temp_dir) = create_test_storage().await?;
        let storage = std::sync::Arc::new(storage);
        
        let topic = Topic::new("concurrent-batch-topic");
        let partition = Partition::new(0);
        let batch_size = 1000;
        let num_concurrent = 10;
        let total_events = batch_size * num_concurrent;
        
        let start_time = Instant::now();
        
        // 並列バッチ書き込み
        let mut handles = Vec::new();
        for i in 0..num_concurrent {
            let storage_clone = std::sync::Arc::clone(&storage);
            let topic_clone = topic.clone();
            
            let handle = tokio::spawn(async move {
                let partition = Partition::new(i % 4); // 4パーティションに分散
                
                let events: Vec<(Topic, Partition, EventData)> = (0..batch_size)
                    .map(|j| (
                        topic_clone.clone(),
                        partition,
                        create_test_event_data(&format!("concurrent_batch_{}_{}", i, j)),
                    ))
                    .collect();
                
                storage_clone.append_events_batch(events).await
            });
            
            handles.push(handle);
        }
        
        // すべてのバッチ完了を待機
        let mut total_written = 0;
        for handle in handles {
            let results = handle.await.unwrap()?;
            total_written += results.len();
        }
        
        let duration = start_time.elapsed();
        let events_per_sec = total_written as f64 / duration.as_secs_f64();
        
        println!("Concurrent batch writes: {} events in {:?} ({:.0} events/sec)", 
                 total_written, duration, events_per_sec);
        
        assert_eq!(total_written, total_events as usize);
        
        // 1M+ events/sec の性能要件
        assert!(events_per_sec > 500000.0, 
                "Concurrent batch performance too low: {:.0} events/sec", events_per_sec);
        
        Ok(())
    }
    
    /// メモリプール統合性能テスト
    #[tokio::test]
    #[ignore]
    async fn bench_integrated_memory_pool_storage() -> StorageResult<()> {
        let (storage, _temp_dir) = create_test_storage().await?;
        
        let topic = Topic::new("memory-pool-integration");
        let partition = Partition::new(0);
        let num_batches = 100;
        let batch_size = 1000;
        
        let start_time = Instant::now();
        
        // メモリプール統計を取得
        let initial_stats = storage.memory_pool_stats();
        
        for batch_num in 0..num_batches {
            let events: Vec<(Topic, Partition, EventData)> = (0..batch_size)
                .map(|i| (
                    topic.clone(),
                    partition,
                    create_test_event_data(&format!("integrated_{}_{}", batch_num, i)),
                ))
                .collect();
            
            storage.append_events_batch(events).await?;
        }
        
        let duration = start_time.elapsed();
        let total_events = num_batches * batch_size;
        let events_per_sec = total_events as f64 / duration.as_secs_f64();
        
        // 最終統計を取得
        let final_stats = storage.memory_pool_stats();
        
        println!("Integrated test: {} events in {:?} ({:.0} events/sec)", 
                 total_events, duration, events_per_sec);
        println!("Memory pool efficiency: {:.2}% hit rate, {:.2}% utilization", 
                 final_stats.hit_rate() * 100.0, final_stats.utilization() * 100.0);
        
        // 性能要件とメモリ効率をチェック
        assert!(events_per_sec > 800000.0, 
                "Integrated performance too low: {:.0} events/sec", events_per_sec);
        assert!(final_stats.hit_rate() > 0.85, 
                "Memory pool hit rate too low: {:.2}", final_stats.hit_rate());
        
        Ok(())
    }
    
    /// 読み取り性能テスト（最適化版）
    #[tokio::test]
    #[ignore]
    async fn bench_optimized_read_performance() -> StorageResult<()> {
        let (storage, _temp_dir) = create_test_storage().await?;
        
        let topic = Topic::new("read-perf-topic");
        let partition = Partition::new(0);
        let num_events = 10000;
        
        // テストデータを準備
        let events: Vec<(Topic, Partition, EventData)> = (0..num_events)
            .map(|i| (
                topic.clone(),
                partition,
                create_test_event_data(&format!("read_perf_msg_{}", i)),
            ))
            .collect();
        
        storage.append_events_batch(events).await?;
        
        // 読み取り性能測定
        let read_sizes = vec![100, 500, 1000, 2000];
        
        for read_size in read_sizes {
            let start_time = Instant::now();
            
            let mut total_read = 0;
            let mut offset = 0;
            
            while total_read < num_events {
                let batch = storage.read_events(
                    &topic,
                    partition,
                    Offset::new(offset),
                    read_size.min(num_events - total_read),
                ).await?;
                
                total_read += batch.len();
                offset += batch.len() as u64;
                
                if batch.is_empty() {
                    break;
                }
            }
            
            let duration = start_time.elapsed();
            let events_per_sec = total_read as f64 / duration.as_secs_f64();
            
            println!("Read batch size {}: {} events in {:?} ({:.0} events/sec)", 
                     read_size, total_read, duration, events_per_sec);
            
            // 読み取り性能要件
            assert!(events_per_sec > 500000.0, 
                    "Read performance too low: {:.0} events/sec for batch size {}", 
                    events_per_sec, read_size);
        }
        
        Ok(())
    }
} 

#[tokio::test]
async fn test_recovery_with_corrupted_segments() {
    let _ = tracing_subscriber::fmt().try_init();
    let temp_dir = TempDir::new().unwrap();
    let data_dir = temp_dir.path();

    // 1. Create a healthy segment
    let healthy_config = StorageConfig { data_dir: data_dir.to_path_buf(), ..Default::default() };
    let storage = StorageEngine::new(healthy_config).await.unwrap();
    let topic = Topic::new("healthy_topic");
    storage.append_event(&topic, Partition::new(0), EventData::from_bytes(b"event_1".to_vec())).await.unwrap();
    storage.append_event(&topic, Partition::new(0), EventData::from_bytes(b"event_2".to_vec())).await.unwrap();
    storage.shutdown().await.unwrap(); // Ensure data is flushed
    drop(storage);
    tokio::time::sleep(Duration::from_millis(100)).await;

    // 2. Create a segment with a corrupt header
    let corrupt_header_path = data_dir.join("2.seg");
    let mut file = OpenOptions::new().write(true).create(true).open(&corrupt_header_path).unwrap();
    file.write_all(&[0; 256]).unwrap(); // Write a dummy header that will fail CRC

    // 3. Create a segment with corrupt event data
    let corrupt_data_path = data_dir.join("3.seg");
    let mut file = OpenOptions::new().write(true).create(true).open(&corrupt_data_path).unwrap();
    let header = kawa_storage::segment::SegmentHeader::new(kawa_storage::segment::SegmentId(3), 1024);
    file.write_all(&bincode::serialize(&header).unwrap()).unwrap(); // Write a valid header
    // Now write an event with a bad CRC
    let event = Event::new(EventId::new(), Topic::new("corrupt_topic"), Partition::new(0), EventData::from_bytes(b"bad_event".to_vec()));
    let event_bytes = bincode::serialize(&event).unwrap();
    let bad_crc = 9999u32.to_le_bytes();
    let size_bytes = (event_bytes.len() as u32).to_le_bytes();
    file.seek(std::io::SeekFrom::Start(kawa_storage::segment::HEADER_SIZE as u64)).unwrap();
    file.write_all(&size_bytes).unwrap();
    file.write_all(&event_bytes).unwrap();
    file.write_all(&bad_crc).unwrap();

    // 4. Re-initialize the storage engine to trigger recovery
    let recovered_config = StorageConfig { data_dir: data_dir.to_path_buf(), ..Default::default() };
    let recovered_storage = StorageEngine::new(recovered_config).await.unwrap();

    // 5. Verify that only the healthy segment's data is recovered
    let events = recovered_storage.read_events(&topic, Partition::new(0), Offset(0), 10).await.unwrap();
    assert_eq!(events.len(), 2, "Should only recover events from the healthy segment");
    assert_eq!(&*events[0].data, b"event_1");
    assert_eq!(&*events[1].data, b"event_2");
} 

#[tokio::test]
async fn test_segment_optimization() {
    let _ = tracing_subscriber::fmt().try_init();
    let temp_dir = TempDir::new().unwrap();
    let config = StorageConfig {
        data_dir: temp_dir.path().to_path_buf(),
        segment_size: 256, // Very small segments to force rotation
        ..Default::default()
    };
    let storage = StorageEngine::new(config).await.unwrap();
    let topic = Topic::new("optimization_test");

    // Write enough events to create multiple segments
    for i in 0..10 {
        let event_data = EventData::from_bytes(format!("event_{}", i).as_bytes());
        storage.append_event(&topic, Partition::new(0), event_data).await.unwrap();
    }
    storage.shutdown().await.unwrap();
    
    let initial_segments = std::fs::read_dir(temp_dir.path()).unwrap().count();
    assert!(initial_segments > 1, "Should have multiple segments initially");
    
    // Re-open and optimize
    let config2 = StorageConfig { data_dir: temp_dir.path().to_path_buf(), ..Default::default() };
    let storage2 = StorageEngine::new(config2).await.unwrap();
    storage2.optimize().await.unwrap();
    storage2.shutdown().await.unwrap();

    // Verify that segments were merged
    let final_segments = std::fs::read_dir(temp_dir.path()).unwrap().count();
    assert_eq!(final_segments, 1, "Should have only one segment after optimization");

    // Verify data integrity
    let config3 = StorageConfig { data_dir: temp_dir.path().to_path_buf(), ..Default::default() };
    let final_storage = StorageEngine::new(config3).await.unwrap();
    let events = final_storage.read_events(&topic, Partition::new(0), Offset(0), 20).await.unwrap();
    assert_eq!(events.len(), 10, "Should recover all events after optimization");
    for i in 0..10 {
        assert_eq!(&*events[i].data, format!("event_{}", i).as_bytes());
    }
} 