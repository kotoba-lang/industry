//! # Consumer Service
//!
//! Kafka Consumer機能の実装。
//! メッセージの読み取り、オフセット管理、コンシューマーグループ対応。

use crate::{BrokerResult, SessionId, messaging::TopicManager, messaging::offset::OffsetManager};
use kawa_storage::{Topic, Partition};
use kawa_serialization::{SerializationFormat, SerializationPurpose, SerializerFactory};
use std::sync::Arc;
use std::collections::HashMap;

/// Consumerサービス
/// 
/// トピックからメッセージを消費し、オフセット管理を行う。
/// コンシューマーグループ機能もサポート。
#[derive(Debug)]
pub struct ConsumerService {
    /// ストレージエンジン
    storage: Arc<kawa_storage::StorageEngine>,
    /// トピック管理者
    topic_manager: Arc<TopicManager>,
    /// オフセット管理者
    offset_manager: Arc<OffsetManager>,
    /// メッセージデシリアライザー
    #[allow(dead_code)]
    serializer: SerializationFormat,
}

impl ConsumerService {
    /// 新しいConsumerサービスを作成
    pub fn new(
        storage: Arc<kawa_storage::StorageEngine>,
        topic_manager: Arc<TopicManager>,
        offset_manager: Arc<OffsetManager>,
    ) -> BrokerResult<Self> {
        // Storage用途のシリアライザーを使用（Producer側と同じ）
        let serializer = SerializerFactory::create_for_purpose(SerializationPurpose::Storage)
            .map_err(|e| crate::BrokerError::configuration_error(&format!("Serializer initialization failed: {}", e)))?;
        
        Ok(Self { 
            storage,
            topic_manager,
            offset_manager,
            serializer,
        })
    }
    
    /// カスタムシリアライザーでConsumerサービスを作成
    pub fn with_serializer(
        storage: Arc<kawa_storage::StorageEngine>,
        topic_manager: Arc<TopicManager>,
        offset_manager: Arc<OffsetManager>,
        serializer: SerializationFormat,
    ) -> Self {
        Self { 
            storage,
            topic_manager,
            offset_manager,
            serializer,
        }
    }
    
    /// メッセージを取得
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `offset` - 開始オフセット
    /// * `max_messages` - 最大取得メッセージ数
    /// * `session_id` - セッションID
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<ConsumedMessage>>` - 消費されたメッセージのリスト
    pub async fn consume_messages(
        &self,
        topic: &str,
        partition: u32,
        offset: i64,
        max_messages: usize,
        session_id: SessionId,
    ) -> BrokerResult<Vec<ConsumedMessage>> {
        tracing::debug!(
            "Consuming messages: topic={}, partition={}, offset={}, max={}, session={}",
            topic, partition, offset, max_messages, session_id
        );

        match self.storage.read_events(
            &Topic::new(topic),
            Partition::new(partition),
            kawa_storage::Offset::new(offset as u64),
            max_messages
        ).await {
            Ok(events) => {
                let mut consumed_messages = Vec::new();
                
                for event in events {
                    // メッセージを展開
                    let decompressed_payload = self.decompress_message(&event, session_id).await?;

                    let mut headers = std::collections::HashMap::new();
                    for (key, value) in &event.headers {
                        headers.insert(key.clone(), value.clone().into_bytes());
                    }

                    let consumed_message = ConsumedMessage {
                        offset: event.offset.map_or(0, |o| *o as i64),
                        timestamp: event.timestamp,
                        key: event.key.map(|k| k.to_vec()),
                        payload: decompressed_payload,
                        headers,
                    };
                    
                    consumed_messages.push(consumed_message);
                }
                
                tracing::info!(
                    "Consumed {} messages: topic={}, partition={}, session={}",
                    consumed_messages.len(), topic, partition, session_id
                );
                
                Ok(consumed_messages)
            }
            Err(e) => {
                tracing::error!(
                    "Failed to consume messages: topic={}, partition={}, error={}, session={}",
                    topic, partition, e, session_id
                );
                Err(e.into())
            }
        }
    }
    
    /// コンシューマーグループでメッセージを取得
    /// 
    /// # Arguments
    /// * `group_id` - コンシューマーグループID
    /// * `topic` - トピック名
    /// * `max_messages` - 最大取得メッセージ数
    /// * `session_id` - セッションID
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<ConsumedMessage>>` - 消費されたメッセージのリスト
    pub async fn fetch_group_messages(
        &self,
        group_id: &str,
        topic: &str,
        max_messages: usize,
        session_id: SessionId,
    ) -> BrokerResult<Vec<ConsumedMessage>> {
        tracing::debug!(
            "Fetching group messages: group={}, topic={}, max={}, session={}",
            group_id, topic, max_messages, session_id
        );
        
        // トピック存在確認
        if !self.topic_manager.topic_exists(topic).await {
            return Err(crate::BrokerError::topic_not_found(topic));
        }
        
        // トピックメタデータ取得
        let topic_metadata = self.topic_manager.get_topic_metadata(topic).await?;
        let mut all_messages = Vec::new();
        
        // 各パーティションからメッセージを取得
        for partition in 0..topic_metadata.partition_count {
            // グループの現在のオフセットを取得
            let current_offset = self.offset_manager
                .fetch_offset(group_id, topic, partition)
                .await?;
            
            // パーティションからメッセージを取得
            let partition_messages = self.consume_messages(
                topic,
                partition,
                current_offset,
                max_messages / topic_metadata.partition_count as usize,
                session_id,
            ).await?;
            
            all_messages.extend(partition_messages);
        }
        
        // メッセージ数制限
        all_messages.truncate(max_messages);
        
        tracing::info!(
            "Fetched {} group messages: group={}, topic={}, session={}",
            all_messages.len(), group_id, topic, session_id
        );
        
        Ok(all_messages)
    }
    
    /// オフセットをコミット
    /// 
    /// # Arguments
    /// * `group_id` - コンシューマーグループID
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `offset` - コミットするオフセット
    /// * `session_id` - セッションID
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 成功または失敗
    pub async fn commit_offset(
        &self,
        group_id: &str,
        topic: &str,
        partition: u32,
        offset: i64,
        session_id: SessionId,
    ) -> BrokerResult<()> {
        tracing::debug!(
            "Committing offset: group={}, topic={}, partition={}, offset={}, session={}",
            group_id, topic, partition, offset, session_id
        );
        
        let consumer_offset = crate::messaging::offset::ConsumerGroupOffset {
            group_id: group_id.to_string(),
            topic: topic.to_string(),
            partition,
            offset,
            metadata: None,
            last_updated: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
        };
        
        self.offset_manager
            .commit_offset(consumer_offset)
            .await?;
        
        tracing::info!(
            "Offset committed: group={}, topic={}, partition={}, offset={}, session={}",
            group_id, topic, partition, offset, session_id
        );
        
        Ok(())
    }
    
    /// トピック統計を取得
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// 
    /// # Returns
    /// * `BrokerResult<ConsumerTopicStats>` - トピック統計
    pub async fn get_topic_stats(&self, topic: &str) -> BrokerResult<ConsumerTopicStats> {
        // トピック存在確認
        if !self.topic_manager.topic_exists(topic).await {
            return Err(crate::BrokerError::topic_not_found(topic));
        }
        
        let topic_metadata = self.topic_manager.get_topic_metadata(topic).await?;
        let mut partition_stats = HashMap::new();
        
        // 各パーティションの統計を取得
        for partition in 0..topic_metadata.partition_count {
            let latest_offset = self.storage
                .get_latest_offset(&Topic::new(topic), Partition::new(partition))
                .await?
                .map(|o| *o as i64)
                .unwrap_or(0);
            
            partition_stats.insert(partition, latest_offset);
        }
        
        let stats = ConsumerTopicStats {
            topic: topic.to_string(),
            partition_count: topic_metadata.partition_count,
            partition_offsets: partition_stats,
        };
        
        Ok(stats)
    }

    // ヘルパー: メッセージを展開
    async fn decompress_message(&self, event: &kawa_storage::Event, session_id: SessionId) -> BrokerResult<Vec<u8>> {
        #[cfg(feature = "compression")]
        {
            if event.compression == kawa_storage::CompressionType::Snappy {
                return snap::raw::Decoder::new().decompress_vec(event.data.as_ref())
                    .map_err(|e| {
                        tracing::error!("Decompression failed: event_id={}, session_id={}, error={}", event.id, session_id, e);
                        crate::BrokerError::internal("Message decompression failed")
                    });
            }
        }
        // 無視された未使用変数警告を抑制
        let _ = session_id;
        let _ = event;
        
        Ok(event.data.to_vec())
    }
}

/// Consumerが受信するメッセージ
#[derive(Debug, Clone)]
pub struct ConsumedMessage {
    /// オフセット
    pub offset: i64,
    /// タイムスタンプ
    pub timestamp: u64,
    /// キー
    pub key: Option<Vec<u8>>,
    /// ペイロード
    pub payload: Vec<u8>,
    /// ヘッダー
    pub headers: HashMap<String, Vec<u8>>,
}

/// Consumer トピック統計
#[derive(Debug, Clone)]
pub struct ConsumerTopicStats {
    /// トピック名
    pub topic: String,
    /// パーティション数
    pub partition_count: u32,
    /// パーティション別最新オフセット
    pub partition_offsets: HashMap<u32, i64>,
} 