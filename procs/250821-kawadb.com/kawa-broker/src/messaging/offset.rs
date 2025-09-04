//! # Offset Management
//!
//! Kafkaオフセット管理機能。
//! コンシューマーグループのオフセット管理を行う。

use crate::BrokerResult;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use std::path::PathBuf;

/// コンシューマーグループオフセット
/// 
/// Kafkaコンシューマーグループの現在のオフセット状態を表現。
/// パーティション単位でオフセットを管理し、永続化サポート。
/// 
/// # @todo
/// - [ ] タイムスタンプ付きオフセット
/// - [ ] リテンション管理
/// - [ ] 圧縮サポート
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsumerGroupOffset {
    /// グループID
    pub group_id: String,
    /// トピック名
    pub topic: String,
    /// パーティション番号
    pub partition: u32,
    /// オフセット
    pub offset: i64,
    /// メタデータ
    pub metadata: Option<String>,
    /// 最終更新タイムスタンプ
    pub last_updated: u64,
}

impl ConsumerGroupOffset {
    /// 新しいオフセットエントリを作成
    pub fn new(group_id: &str, topic: &str, partition: u32, offset: i64) -> Self {
        Self {
            group_id: group_id.to_string(),
            topic: topic.to_string(),
            partition,
            offset,
            metadata: None,
            last_updated: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
        }
    }
    
    /// オフセットキーを生成（一意識別用）
    pub fn key(&self) -> String {
        format!("{}:{}:{}", self.group_id, self.topic, self.partition)
    }
}

/// オフセット管理者
/// 
/// コンシューマーグループのオフセット状態を管理。
/// メモリ内キャッシュ + ファイル永続化による高速アクセス。
/// 
/// # 機能
/// - オフセットコミット・取得
/// - コンシューマーグループ管理
/// - オフセット永続化
/// - リーダーシップ管理（将来）
/// 
/// # @todo
/// - [ ] 分散オフセット管理
/// - [ ] 自動クリーンアップ
/// - [ ] メトリクス収集
#[derive(Debug)]
pub struct OffsetManager {
    /// オフセットキャッシュ (group_id:topic:partition -> offset)
    offsets: Arc<RwLock<HashMap<String, ConsumerGroupOffset>>>,
    /// ストレージパス
    storage_path: PathBuf,
    /// グループメタデータ
    groups: Arc<RwLock<HashMap<String, ConsumerGroupMetadata>>>,
}

/// コンシューマーグループメタデータ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsumerGroupMetadata {
    /// グループID
    pub group_id: String,
    /// グループ状態
    pub state: GroupState,
    /// メンバー一覧
    pub members: Vec<String>,
    /// プロトコル
    pub protocol: String,
    /// リーダー
    pub leader: Option<String>,
    /// 最終更新時刻
    pub last_updated: u64,
}

/// コンシューマーグループ状態
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum GroupState {
    /// 空の状態
    Empty,
    /// 準備中
    PreparingRebalance,
    /// リバランス中
    CompletingRebalance,
    /// 安定状態
    Stable,
    /// デッド状態
    Dead,
}

impl OffsetManager {
    /// 新しいオフセット管理者を作成
    /// 
    /// # Arguments
    /// * `storage_path` - オフセット永続化用ディレクトリ
    /// 
    /// # Returns
    /// * `Result<Self>` - オフセット管理者インスタンス
    pub async fn new(storage_path: PathBuf) -> BrokerResult<Self> {
        // ストレージディレクトリを作成
        tokio::fs::create_dir_all(&storage_path).await
            .map_err(|e| crate::BrokerError::from(e))?;
        
        let manager = Self {
            offsets: Arc::new(RwLock::new(HashMap::new())),
            storage_path: storage_path.clone(),
            groups: Arc::new(RwLock::new(HashMap::new())),
        };
        
        // 既存オフセットを読み込み
        manager.load_offsets().await?;
        
        tracing::info!("OffsetManager initialized with storage: {:?}", storage_path);
        Ok(manager)
    }
    
    /// オフセットをコミット
    /// 
    /// # Arguments
    /// * `offset` - コミットするオフセット情報
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 成功または失敗
    pub async fn commit_offset(&self, offset: ConsumerGroupOffset) -> BrokerResult<()> {
        let key = offset.key();
        
        tracing::debug!("Committing offset: key={}, offset={}", key, offset.offset);
        
        // メモリキャッシュに保存
        {
            let mut offsets = self.offsets.write().await;
            offsets.insert(key.clone(), offset.clone());
        }
        
        // ディスクに永続化
        self.persist_offset(&offset).await?;
        
        tracing::info!("Offset committed: {}", key);
        Ok(())
    }
    
    /// オフセットを取得
    /// 
    /// # Arguments
    /// * `group_id` - コンシューマーグループID
    /// * `topic` - トピック名
    /// * `partition` - パーティション番号
    /// 
    /// # Returns
    /// * `BrokerResult<i64>` - 現在のオフセット（デフォルト: 0）
    pub async fn fetch_offset(&self, group_id: &str, topic: &str, partition: u32) -> BrokerResult<i64> {
        let key = format!("{}:{}:{}", group_id, topic, partition);
        
        let offsets = self.offsets.read().await;
        let offset = offsets.get(&key)
            .map(|o| o.offset)
            .unwrap_or(0);
        
        tracing::debug!("Fetched offset: key={}, offset={}", key, offset);
        Ok(offset)
    }
    
    /// コンシューマーグループの全オフセットを取得
    /// 
    /// # Arguments
    /// * `group_id` - コンシューマーグループID
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<ConsumerGroupOffset>>` - グループの全オフセット
    pub async fn get_group_offsets(&self, group_id: &str) -> BrokerResult<Vec<ConsumerGroupOffset>> {
        let offsets = self.offsets.read().await;
        let group_offsets: Vec<ConsumerGroupOffset> = offsets
            .values()
            .filter(|offset| offset.group_id == group_id)
            .cloned()
            .collect();
        
        tracing::debug!("Retrieved {} offsets for group: {}", group_offsets.len(), group_id);
        Ok(group_offsets)
    }
    
    /// コンシューマーグループを作成
    /// 
    /// # Arguments
    /// * `group_id` - グループID
    /// * `protocol` - プロトコル名
    /// 
    /// # Returns
    /// * `BrokerResult<()>` - 成功または失敗
    pub async fn create_group(&self, group_id: &str, protocol: &str) -> BrokerResult<()> {
        let metadata = ConsumerGroupMetadata {
            group_id: group_id.to_string(),
            state: GroupState::Empty,
            members: Vec::new(),
            protocol: protocol.to_string(),
            leader: None,
            last_updated: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
        };
        
        let mut groups = self.groups.write().await;
        groups.insert(group_id.to_string(), metadata);
        
        tracing::info!("Consumer group created: {}", group_id);
        Ok(())
    }
    
    /// コンシューマーグループ情報を取得
    /// 
    /// # Arguments
    /// * `group_id` - グループID
    /// 
    /// # Returns
    /// * `BrokerResult<Option<ConsumerGroupMetadata>>` - グループメタデータ
    pub async fn get_group(&self, group_id: &str) -> BrokerResult<Option<ConsumerGroupMetadata>> {
        let groups = self.groups.read().await;
        Ok(groups.get(group_id).cloned())
    }
    
    /// 全コンシューマーグループを一覧
    /// 
    /// # Returns
    /// * `BrokerResult<Vec<String>>` - グループID一覧
    pub async fn list_groups(&self) -> BrokerResult<Vec<String>> {
        let groups = self.groups.read().await;
        Ok(groups.keys().cloned().collect())
    }
    
    /// オフセットを永続化
    async fn persist_offset(&self, offset: &ConsumerGroupOffset) -> BrokerResult<()> {
        let file_path = self.storage_path.join(format!("{}.json", offset.key()));
        let json = serde_json::to_string_pretty(offset)
            .map_err(|e| crate::BrokerError::from(e))?;
        
        tokio::fs::write(&file_path, json).await
            .map_err(|e| crate::BrokerError::from(e))?;
        
        Ok(())
    }
    
    /// オフセットを読み込み
    async fn load_offsets(&self) -> BrokerResult<()> {
        let mut count = 0;
        let mut entries = tokio::fs::read_dir(&self.storage_path).await
            .map_err(|e| crate::BrokerError::from(e))?;
        
        while let Some(entry) = entries.next_entry().await
            .map_err(|e| crate::BrokerError::from(e))? {
            
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) == Some("json") {
                if let Ok(content) = tokio::fs::read_to_string(&path).await {
                    if let Ok(offset) = serde_json::from_str::<ConsumerGroupOffset>(&content) {
                        let key = offset.key();
                        let mut offsets = self.offsets.write().await;
                        offsets.insert(key, offset);
                        count += 1;
                    }
                }
            }
        }
        
        if count > 0 {
            tracing::info!("Loaded {} offsets from storage", count);
        }
        Ok(())
    }
    
    /// オフセット統計を取得
    /// 
    /// # Returns
    /// * `BrokerResult<OffsetStats>` - オフセット統計
    pub async fn get_stats(&self) -> BrokerResult<OffsetStats> {
        let offsets = self.offsets.read().await;
        let groups = self.groups.read().await;
        
        let stats = OffsetStats {
            total_offsets: offsets.len(),
            total_groups: groups.len(),
            active_groups: groups.values()
                .filter(|g| matches!(g.state, GroupState::Stable))
                .count(),
        };
        
        Ok(stats)
    }
}

impl Default for OffsetManager {
    fn default() -> Self {
        // 注意: これは同期的なdefaultなので、非同期の初期化は行えません
        // 実際の使用では `OffsetManager::new()` を使用してください
        Self {
            offsets: Arc::new(RwLock::new(HashMap::new())),
            storage_path: PathBuf::from("./data/offsets"),
            groups: Arc::new(RwLock::new(HashMap::new())),
        }
    }
}

/// オフセット管理統計
#[derive(Debug, Clone)]
pub struct OffsetStats {
    /// 総オフセット数
    pub total_offsets: usize,
    /// 総グループ数
    pub total_groups: usize,
    /// アクティブグループ数
    pub active_groups: usize,
} 