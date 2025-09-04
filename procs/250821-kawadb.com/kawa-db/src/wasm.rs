//! # WASM Module for KawaDB
//! 
//! ブラウザ環境でのイベントソーシング機能を提供するWASMモジュール

use crate::{
    config::DbConfig,
    error::{KawaDbError, KawaDbResult},
    schema::TableSchema,
    DbStats,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{Storage, window};

/// WASM環境用のイベントソーシング設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmEventStoreConfig {
    /// ストレージタイプ
    pub storage_type: WasmStorageType,
    /// 最大イベント数
    pub max_events: usize,
    /// 自動スナップショット間隔
    pub snapshot_interval: usize,
    /// オフライン同期有効化
    pub enable_offline_sync: bool,
}

/// WASM環境用ストレージタイプ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum WasmStorageType {
    LocalStorage,
    IndexedDB,
    Memory,
}

impl Default for WasmEventStoreConfig {
    fn default() -> Self {
        Self {
            storage_type: WasmStorageType::IndexedDB,
            max_events: 100_000,
            snapshot_interval: 1000,
            enable_offline_sync: true,
        }
    }
}

/// WASM環境でのイベントソーシング機能
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmEventStore {
    /// イベントリスト
    events: Vec<WasmEvent>,
    /// 現在の状態（スナップショット）
    current_state: HashMap<String, serde_json::Value>,
    /// 設定
    config: WasmEventStoreConfig,
    /// 最後の同期時刻
    last_sync_timestamp: Option<u64>,
}

/// WASM環境用のイベント
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmEvent {
    /// イベントID
    pub id: String,
    /// イベントタイプ
    pub event_type: String,
    /// データ
    pub data: serde_json::Value,
    /// タイムスタンプ
    pub timestamp: u64,
    /// メタデータ
    pub metadata: HashMap<String, String>,
}

impl WasmEventStore {
    /// 新しいイベントストアを作成
    pub fn new(config: WasmEventStoreConfig) -> Self {
        Self {
            events: Vec::new(),
            current_state: HashMap::new(),
            config,
            last_sync_timestamp: None,
        }
    }

    /// イベントを追加
    pub fn append_event(&mut self, event: WasmEvent) -> KawaDbResult<()> {
        // イベント数制限チェック
        if self.events.len() >= self.config.max_events {
            return Err(KawaDbError::InternalError("Event store is full".to_string()));
        }

        // イベントを追加
        self.events.push(event.clone());
        
        // 状態を更新
        self.apply_event_to_state(&event)?;
        
        // 必要に応じてスナップショットを作成
        if self.events.len() % self.config.snapshot_interval == 0 {
            self.create_snapshot()?;
        }
        
        Ok(())
    }

    /// イベントを状態に適用
    fn apply_event_to_state(&mut self, event: &WasmEvent) -> KawaDbResult<()> {
        match event.event_type.as_str() {
            "table_created" => {
                let table_name = event.data["table_name"].as_str()
                    .ok_or_else(|| KawaDbError::InternalError("Invalid table_created event".to_string()))?;
                self.current_state.insert(format!("table_{}", table_name), serde_json::json!({
                    "name": table_name,
                    "schema": event.data["schema"],
                    "data": []
                }));
            }
            "data_inserted" => {
                let table_name = event.data["table_name"].as_str()
                    .ok_or_else(|| KawaDbError::InternalError("Invalid data_inserted event".to_string()))?;
                
                let table_key = format!("table_{}", table_name);
                if let Some(table_data) = self.current_state.get_mut(&table_key) {
                    if let Some(data_array) = table_data["data"].as_array_mut() {
                        data_array.push(event.data["data"].clone());
                    }
                }
            }
            "table_dropped" => {
                let table_name = event.data["table_name"].as_str()
                    .ok_or_else(|| KawaDbError::InternalError("Invalid table_dropped event".to_string()))?;
                self.current_state.remove(&format!("table_{}", table_name));
            }
            _ => {
                // カスタムイベントハンドラーを呼び出し（将来実装）
            }
        }
        
        Ok(())
    }

    /// スナップショットを作成
    fn create_snapshot(&self) -> KawaDbResult<()> {
        // 現在の状態をローカルストレージに保存
        if let Some(window) = window() {
            if let Ok(Some(storage)) = window.local_storage() {
                let snapshot_data = serde_json::to_string(&self.current_state)
                    .map_err(|e| KawaDbError::SerializationError(e))?;
                
                let snapshot_key = format!("kawa_snapshot_{}", js_sys::Date::now() as u64);
                storage.set_item(&snapshot_key, &snapshot_data)
                    .map_err(|_| KawaDbError::InternalError("Failed to save snapshot".to_string()))?;
            }
        }
        
        Ok(())
    }

    /// イベントストアを永続化
    pub async fn persist(&self) -> KawaDbResult<()> {
        match self.config.storage_type {
            WasmStorageType::LocalStorage => {
                self.persist_to_local_storage().await
            }
            WasmStorageType::IndexedDB => {
                self.persist_to_indexed_db().await
            }
            WasmStorageType::Memory => {
                Ok(()) // メモリストレージは何もしない
            }
        }
    }

    /// LocalStorageに永続化
    async fn persist_to_local_storage(&self) -> KawaDbResult<()> {
        if let Some(window) = window() {
            if let Ok(Some(storage)) = window.local_storage() {
                let events_data = serde_json::to_string(&self.events)
                    .map_err(|e| KawaDbError::SerializationError(e))?;
                
                let state_data = serde_json::to_string(&self.current_state)
                    .map_err(|e| KawaDbError::SerializationError(e))?;
                
                storage.set_item("kawa_events", &events_data)
                    .map_err(|_| KawaDbError::InternalError("Failed to save events".to_string()))?;
                
                storage.set_item("kawa_state", &state_data)
                    .map_err(|_| KawaDbError::InternalError("Failed to save state".to_string()))?;
                
                return Ok(());
            }
        }
        
        Err(KawaDbError::InternalError("LocalStorage not available".to_string()))
    }

    /// IndexedDBに永続化
    async fn persist_to_indexed_db(&self) -> KawaDbResult<()> {
        // IndexedDBの実装は複雑なので、後のフェーズで実装
        // 現在はLocalStorageにフォールバック
        self.persist_to_local_storage().await
    }

    /// データを復元
    pub async fn restore(&mut self) -> KawaDbResult<()> {
        match self.config.storage_type {
            WasmStorageType::LocalStorage => {
                self.restore_from_local_storage().await
            }
            WasmStorageType::IndexedDB => {
                self.restore_from_indexed_db().await
            }
            WasmStorageType::Memory => {
                Ok(()) // メモリストレージは何もしない
            }
        }
    }

    /// LocalStorageから復元
    async fn restore_from_local_storage(&mut self) -> KawaDbResult<()> {
        if let Some(window) = window() {
            if let Ok(Some(storage)) = window.local_storage() {
                // イベントを復元
                if let Ok(Some(events_data)) = storage.get_item("kawa_events") {
                    if let Ok(events) = serde_json::from_str::<Vec<WasmEvent>>(&events_data) {
                        self.events = events;
                    }
                }
                
                // 状態を復元
                if let Ok(Some(state_data)) = storage.get_item("kawa_state") {
                    if let Ok(state) = serde_json::from_str::<HashMap<String, serde_json::Value>>(&state_data) {
                        self.current_state = state;
                    }
                }
            }
        }
        
        Ok(())
    }

    /// IndexedDBから復元
    async fn restore_from_indexed_db(&mut self) -> KawaDbResult<()> {
        // IndexedDBの実装は複雑なので、後のフェーズで実装
        // 現在はLocalStorageにフォールバック
        self.restore_from_local_storage().await
    }

    /// 現在の状態を取得
    pub fn get_current_state(&self) -> &HashMap<String, serde_json::Value> {
        &self.current_state
    }

    /// イベント一覧を取得
    pub fn get_events(&self) -> &Vec<WasmEvent> {
        &self.events
    }

    /// 同期が必要かチェック
    pub fn needs_sync(&self) -> bool {
        self.config.enable_offline_sync && 
        (self.last_sync_timestamp.is_none() || 
         js_sys::Date::now() as u64 - self.last_sync_timestamp.unwrap() > 5 * 60 * 1000) // 5分
    }

    /// 統計情報を取得
    pub fn get_stats(&self) -> WasmEventStoreStats {
        WasmEventStoreStats {
            total_events: self.events.len(),
            total_tables: self.current_state.len(),
            storage_type: self.config.storage_type.clone(),
            last_sync: self.last_sync_timestamp,
            needs_sync: self.needs_sync(),
        }
    }
}

/// イベントストアの統計情報
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmEventStoreStats {
    pub total_events: usize,
    pub total_tables: usize,
    pub storage_type: WasmStorageType,
    pub last_sync: Option<u64>,
    pub needs_sync: bool,
}

/// WASM環境用のイベントファクトリー
pub struct WasmEventFactory;

impl WasmEventFactory {
    /// テーブル作成イベントを作成
    pub fn create_table_created_event(table_name: &str, schema: &TableSchema) -> WasmEvent {
        WasmEvent {
            id: format!("table_created_{}", js_sys::Date::now() as u64),
            event_type: "table_created".to_string(),
            data: serde_json::json!({
                "table_name": table_name,
                "schema": schema
            }),
            timestamp: js_sys::Date::now() as u64,
            metadata: HashMap::new(),
        }
    }

    /// データ挿入イベントを作成
    pub fn create_data_inserted_event(table_name: &str, data: &serde_json::Value) -> WasmEvent {
        WasmEvent {
            id: format!("data_inserted_{}", js_sys::Date::now() as u64),
            event_type: "data_inserted".to_string(),
            data: serde_json::json!({
                "table_name": table_name,
                "data": data
            }),
            timestamp: js_sys::Date::now() as u64,
            metadata: HashMap::new(),
        }
    }

    /// テーブル削除イベントを作成
    pub fn create_table_dropped_event(table_name: &str) -> WasmEvent {
        WasmEvent {
            id: format!("table_dropped_{}", js_sys::Date::now() as u64),
            event_type: "table_dropped".to_string(),
            data: serde_json::json!({
                "table_name": table_name
            }),
            timestamp: js_sys::Date::now() as u64,
            metadata: HashMap::new(),
        }
    }
}

/// WASM環境でのDBConfigを拡張
pub trait WasmDbConfigExt {
    fn enable_browser_features(&mut self);
    fn set_wasm_storage_type(&mut self, storage_type: WasmStorageType);
}

impl WasmDbConfigExt for DbConfig {
    fn enable_browser_features(&mut self) {
        self.wasm.enabled = true;
        self.wasm.enable_js_bindings = true;
        self.wasm.enable_console_log = true;
        self.wasm.memory_limit_bytes = 64 * 1024 * 1024; // 64MB
    }

    fn set_wasm_storage_type(&mut self, _storage_type: WasmStorageType) {
        // 設定を更新（将来実装）
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_wasm_event_store_creation() {
        let config = WasmEventStoreConfig::default();
        let store = WasmEventStore::new(config);
        
        assert_eq!(store.events.len(), 0);
        assert_eq!(store.current_state.len(), 0);
    }

    #[test]
    fn test_event_factory() {
        let event = WasmEventFactory::create_table_created_event("test_table", &TableSchema::new("test".to_string(), vec![]));
        
        assert_eq!(event.event_type, "table_created");
        assert_eq!(event.data["table_name"], "test_table");
    }
} 