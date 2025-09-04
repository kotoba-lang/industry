//! # Producer/Consumer統合テスト
//!
//! 実装されたProducer/Consumer機能の包括的テスト。
//! 実際のメッセージ送受信、オフセット管理、エラーハンドリングを検証。

use kawa_broker::{
    MessageBroker, BrokerConfig, SessionId,
};
use tempfile::TempDir;
use tracing::info;

/// テスト用のブローカー設定を作成
async fn create_test_broker() -> (MessageBroker, TempDir) {
    let temp_dir = TempDir::new().unwrap();
    let mut config = BrokerConfig::default();
    config.storage.data_dir = temp_dir.path().to_path_buf();
    config.network.bind_port = 0; // 動的ポート割り当て
    
    let broker = MessageBroker::new(config).await.unwrap();
    (broker, temp_dir)
}

/// 基本的なProducer機能テスト
#[tokio::test]
async fn test_basic_producer_functionality() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // トピック作成
    broker.create_topic("test_topic", 1, 1).await.unwrap();
    
    // メッセージ送信
            let message = b"Hello, Kawa!";
    let offset = broker.produce_message("test_topic", 0, message, session_id).await.unwrap();
    
    info!("Message produced with offset: {}", offset);
    assert_eq!(offset, 0); // 最初のメッセージは 0
    
    // 複数メッセージ送信
    for i in 1..5 {
        let msg = format!("Message {}", i);
        let offset = broker.produce_message("test_topic", 0, msg.as_bytes(), session_id).await.unwrap();
        assert_eq!(offset, i);
        info!("Message {} produced with offset: {}", i, offset);
    }
    
    broker.stop().await.unwrap();
}

/// 基本的なConsumer機能テスト
#[tokio::test]
async fn test_basic_consumer_functionality() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // トピック作成
    broker.create_topic("test_topic", 1, 1).await.unwrap();
    
    // メッセージ送信
    let messages = vec![
        "First message",
        "Second message", 
        "Third message",
    ];
    
    for (i, msg) in messages.iter().enumerate() {
        let offset = broker.produce_message("test_topic", 0, msg.as_bytes(), session_id).await.unwrap();
        assert_eq!(offset, i as i64);
        info!("Produced: {} -> offset {}", msg, offset);
    }
    
    // メッセージ読み取り
    let consumed_messages = broker.fetch_messages("test_topic", 0, 0, 10, session_id).await.unwrap();
    
    assert_eq!(consumed_messages.len(), 3);
    info!("Consumed {} messages", consumed_messages.len());
    
    for (i, msg) in consumed_messages.iter().enumerate() {
        assert_eq!(msg.offset, i as i64);
        assert_eq!(String::from_utf8_lossy(&msg.payload), messages[i]);
        info!("Message {}: {} (offset: {})", i, String::from_utf8_lossy(&msg.payload), msg.offset);
    }
    
    broker.stop().await.unwrap();
}

/// パーティション別メッセージテスト
#[tokio::test]
async fn test_multi_partition_messaging() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // 3パーティションのトピック作成
    broker.create_topic("multi_partition_topic", 3, 1).await.unwrap();
    
    // 各パーティションにメッセージ送信
    for partition in 0..3 {
        for i in 0..3 {
            let msg = format!("Partition {} - Message {}", partition, i);
            let offset = broker.produce_message("multi_partition_topic", partition, msg.as_bytes(), session_id).await.unwrap();
            info!("Produced to partition {}: {} -> offset {}", partition, msg, offset);
        }
    }
    
    // 各パーティションからメッセージ読み取り
    for partition in 0..3 {
        let messages = broker.fetch_messages("multi_partition_topic", partition, 0, 10, session_id).await.unwrap();
        assert_eq!(messages.len(), 3);
        
        for (i, msg) in messages.iter().enumerate() {
            assert_eq!(msg.offset, i as i64);
            let expected = format!("Partition {} - Message {}", partition, i);
            assert_eq!(String::from_utf8_lossy(&msg.payload), expected);
            info!("Consumed from partition {}: {}", partition, String::from_utf8_lossy(&msg.payload));
        }
    }
    
    broker.stop().await.unwrap();
}

/// コンシューマーグループテスト
#[tokio::test]
async fn test_consumer_group_functionality() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // トピック作成
    broker.create_topic("group_topic", 2, 1).await.unwrap();
    
    // コンシューマーグループ作成
    broker.create_consumer_group("test_group", "range").await.unwrap();
    
    // メッセージ送信
    for i in 0..10 {
        let msg = format!("Group message {}", i);
        let partition = i % 2; // パーティション0と1に交互に送信
        let offset = broker.produce_message("group_topic", partition, msg.as_bytes(), session_id).await.unwrap();
        info!("Produced to partition {}: {} -> offset {}", partition, msg, offset);
    }
    
    info!("Consumer group test completed");
    broker.stop().await.unwrap();
}

/// オフセット管理テスト
#[tokio::test]
async fn test_offset_management() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // トピック作成
    broker.create_topic("offset_topic", 1, 1).await.unwrap();
    
    // 5個のメッセージ送信
    for i in 0..5 {
        let msg = format!("Offset test message {}", i);
        let offset = broker.produce_message("offset_topic", 0, msg.as_bytes(), session_id).await.unwrap();
        assert_eq!(offset, i);
    }
    
    // オフセット1から読み取り（メッセージ1〜4を取得）
    let messages = broker.fetch_messages("offset_topic", 0, 1, 10, session_id).await.unwrap();
    assert_eq!(messages.len(), 4);
    assert_eq!(messages[0].offset, 1);
    assert_eq!(messages[3].offset, 4);
    info!("Offset-based reading: {} messages from offset 1", messages.len());
    
    // オフセット3から2個読み取り
    let messages = broker.fetch_messages("offset_topic", 0, 3, 2, session_id).await.unwrap();
    assert_eq!(messages.len(), 2);
    assert_eq!(messages[0].offset, 3);
    assert_eq!(messages[1].offset, 4);
    info!("Limited reading: {} messages from offset 3", messages.len());
    
    broker.stop().await.unwrap();
}

/// エラーハンドリングテスト
#[tokio::test]
async fn test_error_handling() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // 存在しないトピックへのProducer試行
    let result = broker.produce_message("nonexistent_topic", 0, b"test", session_id).await;
    assert!(result.is_err());
    info!("Error handling: Producer to nonexistent topic failed as expected");
    
    // 存在しないトピックからのConsumer試行
    let result = broker.fetch_messages("nonexistent_topic", 0, 0, 1, session_id).await;
    assert!(result.is_err());
    info!("Error handling: Consumer from nonexistent topic failed as expected");
    
    // トピック作成してから無効なパーティションアクセス
    broker.create_topic("test_topic", 2, 1).await.unwrap(); // 2パーティション
    
    let result = broker.produce_message("test_topic", 5, b"test", session_id).await; // パーティション5は無効
    assert!(result.is_err());
    info!("Error handling: Producer to invalid partition failed as expected");
    
    broker.stop().await.unwrap();
}

/// パフォーマンステスト（簡易版）
#[tokio::test]
async fn test_basic_performance() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // トピック作成
    broker.create_topic("perf_topic", 1, 1).await.unwrap();
    
    let message_count = 1000;
    let message = b"Performance test message with some content to make it realistic";
    
    // Producer性能測定
    let start = std::time::Instant::now();
    for i in 0..message_count {
        let _offset = broker.produce_message("perf_topic", 0, message, session_id).await.unwrap();
        if i % 100 == 0 {
            print!(".");
        }
    }
    let producer_duration = start.elapsed();
    println!();
    
    let producer_rate = message_count as f64 / producer_duration.as_secs_f64();
    info!("Producer performance: {} messages/sec", producer_rate as u64);
    
    // Consumer性能測定
    let start = std::time::Instant::now();
    let mut total_consumed = 0;
    let mut offset = 0;
    
    while total_consumed < message_count {
        let messages = broker.fetch_messages("perf_topic", 0, offset, 100, session_id).await.unwrap();
        let consumed = messages.len();
        if consumed == 0 {
            break;
        }
        offset += consumed as i64;
        total_consumed += consumed;
    }
    
    let consumer_duration = start.elapsed();
    let consumer_rate = total_consumed as f64 / consumer_duration.as_secs_f64();
    info!("Consumer performance: {} messages/sec", consumer_rate as u64);
    
    // 結果表示
    println!("📊 Performance Summary:");
    println!("   - Messages: {}", message_count);
    println!("   - Producer: {} msg/sec", producer_rate as u64);
    println!("   - Consumer: {} msg/sec", consumer_rate as u64);
    println!("   - Message size: {} bytes", message.len());
    
    // 性能要件確認（目標値との比較）
    assert!(producer_rate > 100.0, "Producer rate too low: {}", producer_rate);
    assert!(consumer_rate > 100.0, "Consumer rate too low: {}", consumer_rate);
    
    broker.stop().await.unwrap();
}

/// トピック管理テスト
#[tokio::test]
async fn test_topic_management() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // トピック一覧（空）
    let topics = broker.list_topics().await.unwrap();
    assert_eq!(topics.len(), 0);
    info!("Initial topics: {}", topics.len());
    
    // トピック作成
    broker.create_topic("topic1", 1, 1).await.unwrap();
    broker.create_topic("topic2", 3, 1).await.unwrap();
    broker.create_topic("topic3", 2, 1).await.unwrap();
    
    // トピック一覧確認
    let topics = broker.list_topics().await.unwrap();
    assert_eq!(topics.len(), 3);
    assert!(topics.contains(&"topic1".to_string()));
    assert!(topics.contains(&"topic2".to_string()));
    assert!(topics.contains(&"topic3".to_string()));
    info!("Created topics: {:?}", topics);
    
    // トピック削除
    broker.delete_topic("topic2").await.unwrap();
    let topics = broker.list_topics().await.unwrap();
    assert_eq!(topics.len(), 2);
    assert!(!topics.contains(&"topic2".to_string()));
    info!("After deletion: {:?}", topics);
    
    // 重複作成エラー
    let result = broker.create_topic("topic1", 1, 1).await;
    assert!(result.is_err());
    info!("Duplicate topic creation failed as expected");
    
    // 存在しないトピック削除エラー
    let result = broker.delete_topic("nonexistent").await;
    assert!(result.is_err());
    info!("Nonexistent topic deletion failed as expected");
    
    broker.stop().await.unwrap();
}

/// ブローカー統計テスト
#[tokio::test]
async fn test_broker_statistics() {
    let (mut broker, _temp_dir) = create_test_broker().await;
    let session_id = SessionId::new();
    
    // ブローカー開始
    let _addr = broker.start().await.unwrap();
    
    // 初期統計
    let stats = broker.get_stats().await.unwrap();
    assert_eq!(stats.total_topics, 0);
    assert_eq!(stats.total_consumer_groups, 0);
    info!("Initial stats: {:?}", stats);
    
    // トピック・グループ作成
    broker.create_topic("stats_topic", 2, 1).await.unwrap();
    broker.create_consumer_group("stats_group", "range").await.unwrap();
    
    // メッセージ送信
    for i in 0..5 {
        let msg = format!("Stats message {}", i);
        let _offset = broker.produce_message("stats_topic", 0, msg.as_bytes(), session_id).await.unwrap();
    }
    
    // 更新された統計
    let stats = broker.get_stats().await.unwrap();
    assert_eq!(stats.total_topics, 1);
    assert_eq!(stats.total_consumer_groups, 1);
    info!("Updated stats: {:?}", stats);
    
    broker.stop().await.unwrap();
} 