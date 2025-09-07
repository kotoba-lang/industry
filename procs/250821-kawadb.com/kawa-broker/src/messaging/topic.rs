//! # Topic Management
//!
//! Kafkaトピックの管理機能。
//! トピックの作成、削除、メタデータ管理を行う。

use crate::{BrokerError, BrokerResult};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

/// トピックメタデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TopicMetadata {
    /// トピック名
    pub name: String,
    /// パーティション数
    pub partition_count: u32,
    /// レプリケーション係数
    pub replication_factor: u16,
    /// トピック設定
    pub config: HashMap<String, String>,
    /// 作成時刻
    pub created_at: u64,
    /// 最終更新時刻
    pub updated_at: u64,
}

impl TopicMetadata {
    /// 新しいトピックメタデータを作成
    pub fn new(name: String, partition_count: u32, replication_factor: u16) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        
        Self {
            name,
            partition_count,
            replication_factor,
            config: HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }
    
    /// パーティション情報を取得
    pub fn partitions(&self) -> Vec<u32> {
        (0..self.partition_count).collect()
    }
    
    /// トピックサイズ概算を計算
    pub fn estimated_size(&self) -> usize {
        self.name.len() + 
        std::mem::size_of::<u32>() + 
        std::mem::size_of::<u16>() +
        self.config.len() * 50 + // 設定項目の概算
        std::mem::size_of::<u64>() * 2
    }
}

/// トピック管理エラー
#[derive(Debug, thiserror::Error)]
pub enum TopicError {
    /// トピックが既に存在
    #[error("Topic already exists: {topic}")]
    AlreadyExists { topic: String },
    
    /// トピックが存在しない
    #[error("Topic not found: {topic}")]
    NotFound { topic: String },
    
    /// 無効なパーティション数
    #[error("Invalid partition count: {count}")]
    InvalidPartitionCount { count: u32 },
    
    /// 無効なレプリケーション係数
    #[error("Invalid replication factor: {factor}")]
    InvalidReplicationFactor { factor: u16 },
}

/// トピック管理者
/// 
/// Kafkaトピックの生成、削除、メタデータ管理を行う。
/// 高性能でスレッドセーフな操作を提供。
/// 
/// # 機能
/// - トピック作成・削除
/// - メタデータ管理
/// - パーティション管理
/// - 設定管理
/// 
/// # @todo
/// - [ ] 永続化機能
/// - [ ] レプリケーション管理
/// - [ ] 設定変更機能
/// - [ ] パーティション追加機能
#[derive(Debug)]
#[allow(dead_code)]
pub struct TopicManager {
    /// トピックメタデータ（トピック名 -> メタデータ）
    topics: Arc<RwLock<HashMap<String, TopicMetadata>>>,
    /// ストレージエンジン参照
    storage: Arc<kawa_storage::StorageEngine>,
}

impl TopicManager {
    /// 新しいトピック管理者を作成
    pub fn new(storage: Arc<kawa_storage::StorageEngine>) -> Self {
        Self {
            topics: Arc::new(RwLock::new(HashMap::new())),
            storage,
        }
    }
    
    /// トピックを作成
    /// 
    /// # Arguments
    /// * `name` - トピック名
    /// * `partition_count` - パーティション数
    /// * `replication_factor` - レプリケーション係数
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 作成結果
    pub async fn create_topic(
        &self,
        name: String,
        partition_count: u32,
        replication_factor: u16,
    ) -> BrokerResult<()> {
        // 入力検証
        if partition_count == 0 {
            return Err(BrokerError::invalid_partition(name, partition_count));
        }
        
        if replication_factor == 0 {
            return Err(TopicError::InvalidReplicationFactor { factor: replication_factor }.into());
        }
        
        let mut topics = self.topics.write().await;
        
        // 既存チェック
        if topics.contains_key(&name) {
            return Err(TopicError::AlreadyExists { topic: name }.into());
        }
        
        // メタデータ作成
        let metadata = TopicMetadata::new(name.clone(), partition_count, replication_factor);
        
        // トピックを登録
        topics.insert(name.clone(), metadata);
        
        tracing::info!(
            "Topic created: {} (partitions: {}, replication: {})",
            name,
            partition_count,
            replication_factor
        );
        
        Ok(())
    }
    
    /// トピックを削除
    /// 
    /// # Arguments
    /// * `name` - トピック名
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 削除結果
    pub async fn delete_topic(&self, name: &str) -> BrokerResult<()> {
        let mut topics = self.topics.write().await;
        
        match topics.remove(name) {
            Some(_) => {
                tracing::info!("Topic deleted: {}", name);
                Ok(())
            }
            None => Err(TopicError::NotFound { topic: name.to_string() }.into()),
        }
    }
    
    /// トピックメタデータを取得
    /// 
    /// # Arguments
    /// * `name` - トピック名
    /// 
    /// # Returns
    /// * `BrokerResult<TopicMetadata>` - メタデータ
    pub async fn get_topic_metadata(&self, name: &str) -> BrokerResult<TopicMetadata> {
        let topics = self.topics.read().await;
        
        topics
            .get(name)
            .cloned()
            .ok_or_else(|| TopicError::NotFound { topic: name.to_string() }.into())
    }
    
    /// 全トピック一覧を取得
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<TopicMetadata>>` - トピック一覧
    pub async fn list_topics(&self) -> BrokerResult<Vec<TopicMetadata>> {
        let topics = self.topics.read().await;
        Ok(topics.values().cloned().collect())
    }
    
    /// トピックが存在するかチェック
    /// 
    /// # Arguments
    /// * `name` - トピック名
    /// 
    /// # Returns
    /// * `bool` - 存在するかどうか
    pub async fn topic_exists(&self, name: &str) -> bool {
        let topics = self.topics.read().await;
        topics.contains_key(name)
    }
    
    /// トピック数を取得
    /// 
    /// # Returns
    /// * `usize` - トピック数
    pub async fn topic_count(&self) -> usize {
        let topics = self.topics.read().await;
        topics.len()
    }
    
    /// トピック設定を更新
    /// 
    /// # Arguments
    /// * `name` - トピック名
    /// * `config` - 新しい設定
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 更新結果
    /// 
    /// # @todo
    /// - [ ] 設定検証
    /// - [ ] 設定変更の永続化
    pub async fn update_topic_config(
        &self,
        name: &str,
        config: HashMap<String, String>,
    ) -> BrokerResult<()> {
        let mut topics = self.topics.write().await;
        
        match topics.get_mut(name) {
            Some(metadata) => {
                metadata.config = config;
                metadata.updated_at = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_secs();
                
                tracing::info!("Topic config updated: {}", name);
                Ok(())
            }
            None => Err(TopicError::NotFound { topic: name.to_string() }.into()),
        }
    }
}

impl From<TopicError> for BrokerError {
    fn from(err: TopicError) -> Self {
        match err {
            TopicError::AlreadyExists { topic } => {
                BrokerError::invalid_protocol(format!("Topic already exists: {}", topic))
            }
            TopicError::NotFound { topic } => {
                BrokerError::topic_not_found(topic)
            }
            TopicError::InvalidPartitionCount { count } => {
                BrokerError::invalid_protocol(format!("Invalid partition count: {}", count))
            }
            TopicError::InvalidReplicationFactor { factor } => {
                BrokerError::invalid_protocol(format!("Invalid replication factor: {}", factor))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;
    
    
    async fn create_test_topic_manager() -> (TopicManager, TempDir) {
        let temp_dir = TempDir::new().unwrap();
        let storage_config = kawa_storage::StorageConfig {
            data_dir: temp_dir.path().to_path_buf(),
            segment_size: 1024 * 1024,
            sync_interval_ms: 100,
            enable_compression: false,
            compression_type: None,
            memory_pool_size: 16 * 1024 * 1024,
            batch_size: 100,
            worker_count: Some(1),
        };
        
        let storage = Arc::new(kawa_storage::StorageEngine::new(storage_config).await.unwrap());
        let topic_manager = TopicManager::new(storage);
        
        (topic_manager, temp_dir)
    }
    
    #[tokio::test]
    async fn test_topic_creation() {
        let (manager, _temp_dir) = create_test_topic_manager().await;
        
        // トピック作成
        manager.create_topic("test_topic".to_string(), 3, 1).await.unwrap();
        
        // トピック存在確認
        assert!(manager.topic_exists("test_topic").await);
        
        // メタデータ確認
        let metadata = manager.get_topic_metadata("test_topic").await.unwrap();
        assert_eq!(metadata.name, "test_topic");
        assert_eq!(metadata.partition_count, 3);
        assert_eq!(metadata.replication_factor, 1);
    }
    
    #[tokio::test]
    async fn test_topic_deletion() {
        let (manager, _temp_dir) = create_test_topic_manager().await;
        
        // トピック作成
        manager.create_topic("test_topic".to_string(), 3, 1).await.unwrap();
        assert!(manager.topic_exists("test_topic").await);
        
        // トピック削除
        manager.delete_topic("test_topic").await.unwrap();
        assert!(!manager.topic_exists("test_topic").await);
    }
    
    #[tokio::test]
    async fn test_topic_listing() {
        let (manager, _temp_dir) = create_test_topic_manager().await;
        
        // 複数トピック作成
        manager.create_topic("topic1".to_string(), 1, 1).await.unwrap();
        manager.create_topic("topic2".to_string(), 2, 1).await.unwrap();
        manager.create_topic("topic3".to_string(), 3, 1).await.unwrap();
        
        // トピック一覧取得
        let topics = manager.list_topics().await.unwrap();
        assert_eq!(topics.len(), 3);
        
        let topic_names: Vec<String> = topics.iter().map(|t| t.name.clone()).collect();
        assert!(topic_names.contains(&"topic1".to_string()));
        assert!(topic_names.contains(&"topic2".to_string()));
        assert!(topic_names.contains(&"topic3".to_string()));
    }
} 