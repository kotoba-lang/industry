//! # Kawa Broker
//!
//! Kafka互換メッセージブローカーの実装。
//! 高性能なイベントストリーミングプラットフォームを提供。

pub mod client;
pub mod config;
pub mod error;
pub mod protocol;
pub mod server;
pub mod messaging;


// 公開エクスポート
pub use config::BrokerConfig;
pub use error::{BrokerError, BrokerResult, ErrorSeverity};
pub use messaging::{
    consumer::ConsumedMessage,
    TopicManager,
};
pub use server::BrokerServer;

use derive_more::{Deref, DerefMut, From, Into};
use serde::{Deserialize, Serialize};
use std::{
    fmt::Debug,
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant},
};
use tracing::info;
use uuid::Uuid;

/// クライアントID型（Newtypeパターン）
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, Deref, DerefMut, From, Into)]
pub struct ClientId(pub String);

impl ClientId {
    /// 新しいクライアントIDを生成
    pub fn new() -> Self {
        Self(Uuid::new_v4().to_string())
    }
    
    /// 文字列からクライアントIDを作成
    pub fn from_string(id: impl Into<String>) -> Self {
        Self(id.into())
    }
}

impl Default for ClientId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for ClientId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// セッションID型（Newtypeパターン）
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Deref, DerefMut, From, Into)]
pub struct SessionId(pub u64);

impl SessionId {
    /// 新しいセッションIDを生成
    pub fn new() -> Self {
        use std::sync::atomic::{AtomicU64, Ordering};
        static COUNTER: AtomicU64 = AtomicU64::new(1);
        Self(COUNTER.fetch_add(1, Ordering::Relaxed))
    }
}

impl Default for SessionId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for SessionId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// クライアントセッション情報
#[derive(Debug, Clone)]
pub struct ClientSession {
    /// セッションID
    pub session_id: SessionId,
    /// クライアントID
    pub client_id: ClientId,
    /// リモートアドレス
    pub remote_addr: SocketAddr,
    /// 接続時刻
    pub connected_at: Instant,
    /// 最終アクティビティ時刻
    pub last_activity: Instant,
}

impl ClientSession {
    /// 新しいクライアントセッションを作成
    pub fn new(client_id: ClientId, remote_addr: SocketAddr) -> Self {
        let now = Instant::now();
        Self {
            session_id: SessionId::new(),
            client_id,
            remote_addr,
            connected_at: now,
            last_activity: now,
        }
    }
    
    /// 最終アクティビティ時刻を更新
    pub fn update_activity(&mut self) {
        self.last_activity = Instant::now();
    }
    
    /// セッションの持続時間を取得
    pub fn duration(&self) -> Duration {
        Instant::now().duration_since(self.connected_at)
    }
}

/// Kawa メッセージブローカー
/// 
/// Kafka互換のメッセージブローカーサービスを提供。
/// Producer/Consumer API、トピック管理、オフセット管理を統合。
/// 
/// # 機能
/// - Kafka互換プロトコル処理
/// - 高性能メッセージ永続化
/// - コンシューマーグループ管理
/// - メトリクス・モニタリング
/// 
/// # @todo
/// - [ ] クラスター機能
/// - [ ] レプリケーション
/// - [ ] 管理API
#[derive(Debug)]
pub struct MessageBroker {
    /// ブローカー設定
    config: BrokerConfig,
    /// ストレージエンジン
    storage: Arc<kawa_storage::StorageEngine>,
    /// TCPサーバー
    server: Option<BrokerServer>,
    /// トピック管理
    topic_manager: Arc<TopicManager>,
    /// Producer サービス
    producer: Arc<messaging::ProducerService>,
    /// Consumer サービス
    consumer: Arc<messaging::ConsumerService>,
    /// オフセット管理
    offset_manager: Arc<messaging::offset::OffsetManager>,
}

impl MessageBroker {
    /// 新しいメッセージブローカーを作成
    /// 
    /// # Arguments
    /// * `config` - ブローカー設定
    /// 
    /// # Returns
    /// * `Result<Self>` - ブローカーインスタンス
    pub async fn new(config: BrokerConfig) -> BrokerResult<Self> {
        // ストレージエンジンを初期化
        let storage_config = kawa_storage::StorageConfig {
            data_dir: config.data_dir().clone(),
            segment_size: config.segment_size(),
            sync_interval_ms: config.sync_interval_ms(),
            enable_compression: false,
            compression_type: None, // @todo configから反映
            memory_pool_size: 128 * 1024 * 1024, // 128MB
            batch_size: 1000,
            worker_count: None, // 自動設定
        };
        let storage = Arc::new(kawa_storage::StorageEngine::new(storage_config).await?);
        
        // オフセット管理を初期化
        let offset_path = config.data_dir().join("offsets");
        let offset_manager = Arc::new(messaging::offset::OffsetManager::new(offset_path).await?);
        
        // トピック管理を初期化
        let topic_manager = Arc::new(TopicManager::new(storage.clone()));
        
        // Producer/Consumer サービスを初期化
        let producer = Arc::new(messaging::ProducerService::new(
            storage.clone(),
            topic_manager.clone(),
        )?);
        let consumer = Arc::new(messaging::ConsumerService::new(
            storage.clone(),
            topic_manager.clone(),
            offset_manager.clone(),
        )?);
        
        info!("MessageBroker initialized with storage: {:?}", config.data_dir());
        
        Ok(Self {
            config,
            storage,
            server: None,
            topic_manager,
            producer,
            consumer,
            offset_manager,
        })
    }
    
    /// ブローカーを開始
    /// 
    /// # Returns
    /// * `Result<SocketAddr>` - 実際のバインドアドレス
    pub async fn start(&mut self) -> BrokerResult<SocketAddr> {
        let server = BrokerServer::new(
            self.config.clone(),
            self.storage.clone(),
        ).await?;
        
        let addr = server.start_and_get_addr().await?;
        self.server = Some(server);
        
        info!("MessageBroker started on {}", addr);
        Ok(addr)
    }
    
    /// ブローカーを停止
    pub async fn stop(&mut self) -> BrokerResult<()> {
        if let Some(mut server) = self.server.take() {
            server.stop().await?;
        }
        
        // ストレージを優雅にシャットダウン
        self.storage.shutdown().await?;
        
        info!("MessageBroker stopped");
        Ok(())
    }
    
    /// メッセージを送信（Producer API）
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `message` - メッセージデータ
    /// * `session_id` - セッションID
    /// 
    /// # Returns
    /// * `BrokerResult<i64>` - 割り当てられたオフセット
    pub async fn produce_message(
        &self,
        topic: &str,
        partition: u32,
        message: &[u8],
        session_id: SessionId,
    ) -> BrokerResult<i64> {
        self.producer.produce_message(topic, partition, message, session_id).await
    }
    
    /// メッセージを取得（Consumer API）
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// * `offset` - 開始オフセット
    /// * `max_messages` - 最大取得メッセージ数
    /// * `session_id` - セッションID
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<ConsumedMessage>>` - 取得したメッセージリスト
    pub async fn fetch_messages(
        &self,
        topic: &str,
        partition: u32,
        offset: i64,
        max_messages: usize,
        session_id: SessionId,
    ) -> BrokerResult<Vec<ConsumedMessage>> {
        tracing::debug!("Fetching messages from topic: {}", topic);
        self.consumer.consume_messages(topic, partition, offset, max_messages, session_id).await
    }
    
    /// トピックを作成
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// * `partition_count` - パーティション数
    /// * `replication_factor` - レプリケーション係数
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 成功または失敗
    pub async fn create_topic(
        &self,
        topic: &str,
        partition_count: u32,
        replication_factor: u16,
    ) -> BrokerResult<()> {
        self.topic_manager.create_topic(topic.to_string(), partition_count, replication_factor).await
    }
    
    /// トピックを削除
    /// 
    /// # Arguments
    /// * `topic` - トピック名
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 成功または失敗
    pub async fn delete_topic(&self, topic: &str) -> BrokerResult<()> {
        self.topic_manager.delete_topic(topic).await
    }
    
    /// トピック一覧を取得
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<String>>` - トピック名一覧
    pub async fn list_topics(&self) -> BrokerResult<Vec<String>> {
        let topics = self.topic_manager.list_topics().await?;
        Ok(topics.into_iter().map(|t| t.name).collect())
    }
    
    /// コンシューマーグループを作成
    /// 
    /// # Arguments
    /// * `group_id` - グループID
    /// * `protocol` - プロトコル名
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 成功または失敗
    pub async fn create_consumer_group(&self, group_id: &str, protocol: &str) -> BrokerResult<()> {
        self.offset_manager.create_group(group_id, protocol).await
    }
    
    /// ブローカー統計を取得
    /// 
    /// # Returns
    /// * `BrokerResult<BrokerStats>` - ブローカー統計
    pub async fn get_stats(&self) -> BrokerResult<BrokerStats> {
        let offset_stats = self.offset_manager.get_stats().await?;
        let topics = self.topic_manager.list_topics().await?;
        
        let stats = BrokerStats {
            active_sessions: 0, // @todo
            total_topics: topics.len(),
            total_consumer_groups: offset_stats.total_groups,
            active_consumer_groups: offset_stats.active_groups,
            total_offsets: offset_stats.total_offsets,
            uptime_seconds: 0, // @todo: 実装
        };
        
        Ok(stats)
    }
    
    /// 新しいセッションを作成
    pub async fn create_session(&self, client_id: ClientId, remote_addr: SocketAddr) -> SessionId {
        let session_id = SessionId::new();
        let _session = ClientSession::new(client_id, remote_addr);
        
        // let mut sessions = self.sessions.write().await;
        // sessions.insert(session_id, session);
        
        session_id
    }

    /// セッション情報を取得
    pub async fn get_session(&self, _session_id: SessionId) -> Option<ClientSession> {
        // let sessions = self.sessions.read().await;
        // sessions.get(&session_id).cloned()
        None
    }

    /// セッションを削除
    pub async fn remove_session(&self, _session_id: SessionId) -> Option<ClientSession> {
        // let mut sessions = self.sessions.write().await;
        // sessions.remove(&session_id)
        None
    }
}

/// ブローカー統計
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BrokerStats {
    /// アクティブセッション数
    pub active_sessions: usize,
    /// 総トピック数
    pub total_topics: usize,
    /// 総コンシューマーグループ数
    pub total_consumer_groups: usize,
    /// アクティブコンシューマーグループ数
    pub active_consumer_groups: usize,
    /// 総オフセット数
    pub total_offsets: usize,
    /// 稼働時間（秒）
    pub uptime_seconds: u64,
} 