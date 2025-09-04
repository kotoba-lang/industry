//! # Browser Event Store
//! 
//! ブラウザ環境でのイベントソーシング機能を提供
//! - LocalStorage / IndexedDB 永続化
//! - リロード耐性
//! - オフライン対応
//! - 高性能イベント処理

use crate::storage::{WasmStorage, StorageType};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use web_sys::console;
use js_sys::Date;

/// ブラウザ用イベントストア設定
#[derive(Debug, Clone)]
pub struct BrowserEventStoreConfig {
    pub storage_type: StorageType,
    pub max_events: usize,
    pub enable_offline_sync: bool,
    pub debug_mode: bool,
}

impl Default for BrowserEventStoreConfig {
    fn default() -> Self {
        Self {
            storage_type: StorageType::IndexedDB,
            max_events: 100_000,
            enable_offline_sync: true,
            debug_mode: false,
        }
    }
}

/// ブラウザ用イベント
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BrowserEvent {
    pub id: String,
    pub event_type: String,
    pub data: String, // JSON string
    pub timestamp: u64,
    pub metadata: HashMap<String, String>,
}

/// イベントストア統計
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EventStoreStats {
    pub total_events: usize,
    pub storage_size_bytes: u64,
    pub oldest_event_timestamp: Option<u64>,
    pub newest_event_timestamp: Option<u64>,
    pub events_by_type: HashMap<String, usize>,
}

/// ブラウザ用イベントストア
pub struct BrowserEventStore {
    storage: WasmStorage,
    config: BrowserEventStoreConfig,
    cache: Vec<BrowserEvent>,
    is_loaded: bool,
}

impl BrowserEventStore {
    /// 新しいイベントストアを作成
    pub async fn new(config: BrowserEventStoreConfig) -> Result<Self, String> {
        if config.debug_mode {
            console::log_1(&"🔧 Creating BrowserEventStore...".into());
        }
        
        let storage = WasmStorage::new(config.storage_type.clone())
            .map_err(|_| "Failed to create storage".to_string())?;
        
        let mut store = Self {
            storage,
            config,
            cache: Vec::new(),
            is_loaded: false,
        };
        
        // 既存データを読み込み
        store.load_from_storage().await?;
        
        if store.config.debug_mode {
            console::log_1(&format!("✅ BrowserEventStore loaded with {} events", store.cache.len()).into());
        }
        
        Ok(store)
    }
    
    /// イベントを追加
    pub async fn append_event(&mut self, event: BrowserEvent) -> Result<(), String> {
        // イベント数制限チェック
        if self.cache.len() >= self.config.max_events {
            return Err("Event store is full".to_string());
        }
        
        if self.config.debug_mode {
            console::log_1(&format!("📝 Adding event: {} ({})", event.event_type, event.id).into());
        }
        
        // キャッシュに追加
        self.cache.push(event.clone());
        
        // ストレージに永続化
        self.persist_to_storage().await?;
        
        Ok(())
    }
    
    /// イベントを取得
    pub async fn get_events(&self, limit: Option<usize>) -> Result<Vec<BrowserEvent>, String> {
        let events = if let Some(limit) = limit {
            self.cache.iter().rev().take(limit).cloned().collect()
        } else {
            self.cache.clone()
        };
        
        Ok(events)
    }
    
    /// イベントタイプでフィルタリング
    pub async fn get_events_by_type(&self, event_type: &str) -> Result<Vec<BrowserEvent>, String> {
        let events: Vec<BrowserEvent> = self.cache.iter()
            .filter(|e| e.event_type == event_type)
            .cloned()
            .collect();
        
        Ok(events)
    }
    
    /// 現在の状態を取得（イベントから再構築）
    pub async fn get_current_state(&self) -> Result<HashMap<String, serde_json::Value>, String> {
        let mut state = HashMap::new();
        
        // 全イベントを順番に適用して状態を構築
        for event in &self.cache {
            self.apply_event_to_state(&mut state, event)?;
        }
        
        Ok(state)
    }
    
    /// イベントを状態に適用
    fn apply_event_to_state(&self, state: &mut HashMap<String, serde_json::Value>, event: &BrowserEvent) -> Result<(), String> {
        match event.event_type.as_str() {
            "user_created" => {
                let data: serde_json::Value = serde_json::from_str(&event.data)
                    .map_err(|e| format!("Invalid JSON in event: {}", e))?;
                
                if let Some(user_id) = data["user_id"].as_str() {
                    state.insert(format!("user_{}", user_id), data);
                }
            }
            "user_updated" => {
                let data: serde_json::Value = serde_json::from_str(&event.data)
                    .map_err(|e| format!("Invalid JSON in event: {}", e))?;
                
                if let Some(user_id) = data["user_id"].as_str() {
                    let key = format!("user_{}", user_id);
                    if let Some(existing_user) = state.get_mut(&key) {
                        if let Some(user_object) = existing_user.as_object_mut() {
                             if let Some(updates) = data["updates"].as_object() {
                                 // Merge updates into the user_data field
                                 if let Some(user_data) = user_object.get_mut("user_data") {
                                     if let Some(user_data_obj) = user_data.as_object_mut() {
                                         for (k, v) in updates {
                                             user_data_obj.insert(k.clone(), v.clone());
                                         }
                                     }
                                 }
                             }
                        }
                    }
                }
            }
            "user_deleted" => {
                let data: serde_json::Value = serde_json::from_str(&event.data)
                    .map_err(|e| format!("Invalid JSON in event: {}", e))?;
                
                if let Some(user_id) = data["user_id"].as_str() {
                    state.remove(&format!("user_{}", user_id));
                }
            }
            "data_inserted" => {
                let data: serde_json::Value = serde_json::from_str(&event.data)
                    .map_err(|e| format!("Invalid JSON in event: {}", e))?;
                
                if let Some(collection) = data["collection"].as_str() {
                    let key = format!("collection_{}", collection);
                    let items = state.entry(key).or_insert_with(|| serde_json::json!([]));
                    
                    if let Some(items_array) = items.as_array_mut() {
                        items_array.push(data["item"].clone());
                    }
                }
            }
            _ => {
                // カスタムイベントタイプの処理
                if self.config.debug_mode {
                    console::log_1(&format!("⚠️ Unknown event type: {}", event.event_type).into());
                }
            }
        }
        
        Ok(())
    }
    
    /// ストレージから読み込み
    async fn load_from_storage(&mut self) -> Result<(), String> {
        if self.is_loaded {
            return Ok(());
        }
        
        if let Ok(Some(events_json)) = self.storage.get_item("kawa_events") {
            if let Ok(events) = serde_json::from_str::<Vec<BrowserEvent>>(&events_json) {
                self.cache = events;
                
                if self.config.debug_mode {
                    console::log_1(&format!("📂 Loaded {} events from storage", self.cache.len()).into());
                }
            }
        }
        
        self.is_loaded = true;
        Ok(())
    }
    
    /// ストレージに永続化
    async fn persist_to_storage(&self) -> Result<(), String> {
        let events_json = serde_json::to_string(&self.cache)
            .map_err(|e| format!("Failed to serialize events: {}", e))?;
        
        self.storage.set_item("kawa_events", &events_json)
            .map_err(|_| "Failed to save events to storage".to_string())?;
        
        // メタデータも保存
        let metadata = serde_json::json!({
            "last_updated": Date::now() as u64,
            "event_count": self.cache.len(),
            "version": "1.0.0"
        });
        
        let metadata_json = serde_json::to_string(&metadata)
            .map_err(|e| format!("Failed to serialize metadata: {}", e))?;
        
        self.storage.set_item("kawa_metadata", &metadata_json)
            .map_err(|_| "Failed to save metadata to storage".to_string())?;
        
        if self.config.debug_mode {
            console::log_1(&format!("💾 Persisted {} events to storage", self.cache.len()).into());
        }
        
        Ok(())
    }
    
    /// 統計情報を取得
    pub async fn get_stats(&self) -> Result<EventStoreStats, String> {
        let mut events_by_type: HashMap<String, usize> = HashMap::new();
        let mut oldest_timestamp: Option<u64> = None;
        let mut newest_timestamp: Option<u64> = None;
        
        for event in &self.cache {
            // イベントタイプ別カウント
            *events_by_type.entry(event.event_type.clone()).or_insert(0) += 1;
            
            // 最古・最新タイムスタンプ
            oldest_timestamp = Some(oldest_timestamp.map_or(event.timestamp, |ts| ts.min(event.timestamp)));
            newest_timestamp = Some(newest_timestamp.map_or(event.timestamp, |ts| ts.max(event.timestamp)));
        }
        
        let storage_size = self.storage.size()
            .map_err(|_| "Failed to get storage size".to_string())?;
        
        Ok(EventStoreStats {
            total_events: self.cache.len(),
            storage_size_bytes: storage_size,
            oldest_event_timestamp: oldest_timestamp,
            newest_event_timestamp: newest_timestamp,
            events_by_type,
        })
    }
    
    /// データをクリア
    pub async fn clear(&mut self) -> Result<bool, String> {
        if self.config.debug_mode {
            console::log_1(&"🧹 Clearing event store...".into());
        }
        
        self.cache.clear();
        
        // ストレージもクリア
        self.storage.remove_item("kawa_events")
            .map_err(|_| "Failed to clear events from storage".to_string())?;
        
        self.storage.remove_item("kawa_metadata")
            .map_err(|_| "Failed to clear metadata from storage".to_string())?;
        
        Ok(true)
    }
    
    /// 指定期間より古いイベントを削除
    pub async fn cleanup_old_events(&mut self, retention_days: u32) -> Result<usize, String> {
        let cutoff_timestamp = Date::now() as u64 - (retention_days as u64 * 24 * 60 * 60 * 1000);
        let initial_count = self.cache.len();
        
        self.cache.retain(|event| event.timestamp > cutoff_timestamp);
        
        let removed_count = initial_count - self.cache.len();
        
        if removed_count > 0 {
            self.persist_to_storage().await?;
            
            if self.config.debug_mode {
                console::log_1(&format!("🧹 Cleaned up {} old events", removed_count).into());
            }
        }
        
        Ok(removed_count)
    }
    
    /// イベントストアを最適化
    pub async fn optimize(&mut self) -> Result<(), String> {
        if self.config.debug_mode {
            console::log_1(&"⚡ Optimizing event store...".into());
        }
        
        // イベントをタイムスタンプでソート
        self.cache.sort_by_key(|event| event.timestamp);
        
        // 重複するイベントを削除
        let initial_count = self.cache.len();
        self.cache.dedup_by_key(|event| event.id.clone());
        let deduped_count = initial_count - self.cache.len();
        
        if deduped_count > 0 {
            self.persist_to_storage().await?;
            
            if self.config.debug_mode {
                console::log_1(&format!("⚡ Removed {} duplicate events", deduped_count).into());
            }
        }
        
        Ok(())
    }
}

/// イベントファクトリー
pub struct BrowserEventFactory;

impl BrowserEventFactory {
    /// ユーザー作成イベント
    pub fn create_user_created(user_id: &str, user_data: &serde_json::Value) -> BrowserEvent {
        BrowserEvent {
            id: format!("user_created_{}", Date::now() as u64),
            event_type: "user_created".to_string(),
            data: serde_json::json!({
                "user_id": user_id,
                "user_data": user_data
            }).to_string(),
            timestamp: Date::now() as u64,
            metadata: HashMap::new(),
        }
    }
    
    /// ユーザー更新イベント
    pub fn create_user_updated(user_id: &str, updates: &serde_json::Value) -> BrowserEvent {
        BrowserEvent {
            id: format!("user_updated_{}", Date::now() as u64),
            event_type: "user_updated".to_string(),
            data: serde_json::json!({
                "user_id": user_id,
                "updates": updates
            }).to_string(),
            timestamp: Date::now() as u64,
            metadata: HashMap::new(),
        }
    }
    
    /// データ挿入イベント
    pub fn create_data_inserted(collection: &str, item: &serde_json::Value) -> BrowserEvent {
        BrowserEvent {
            id: format!("data_inserted_{}", Date::now() as u64),
            event_type: "data_inserted".to_string(),
            data: serde_json::json!({
                "collection": collection,
                "item": item
            }).to_string(),
            timestamp: Date::now() as u64,
            metadata: HashMap::new(),
        }
    }
    
    /// カスタムイベント
    pub fn create_custom_event(event_type: &str, data: &serde_json::Value) -> BrowserEvent {
        BrowserEvent {
            id: format!("{}_{}", event_type, Date::now() as u64),
            event_type: event_type.to_string(),
            data: data.to_string(),
            timestamp: Date::now() as u64,
            metadata: HashMap::new(),
        }
    }
} 