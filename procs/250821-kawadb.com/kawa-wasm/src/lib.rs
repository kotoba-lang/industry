//! # KawaDB WASM Module
//! 
//! Rustで実装されたKawaDBのWebAssembly版
//! ブラウザ環境でのサーバレス・イベントソーシング機能を提供

use wasm_bindgen::prelude::*;
use web_sys::{console, window};
use js_sys::Date;
use std::collections::HashMap;

extern crate console_error_panic_hook;

fn set_panic_hook() {
    // This provides better error messages in browsers.
    console_error_panic_hook::set_once();
}

/// コンソールログマクロ
macro_rules! console_log {
    ($($t:tt)*) => {
        console::log_1(&format!($($t)*).into());
    }
}

#[wasm_bindgen]
extern "C" {
    fn alert(s: &str);
}

#[wasm_bindgen]
pub fn greet() {
    alert("Hello, kawa-wasm!");
}

pub mod database;
pub mod event_store;
pub mod ksql;
pub mod query;
pub mod storage;
pub mod sync;
pub mod utils;

// Re-exports
pub use database::*;
pub use event_store::*;
pub use ksql::*;
pub use query::*;
pub use storage::*;
pub use sync::*;
pub use utils::*;

/// KawaDB Browser Edition初期化
#[wasm_bindgen(start)]
pub fn init() {
    #[cfg(feature = "console_error_panic_hook")]
    set_panic_hook();
    
    console_log!("🌐 KawaDB Browser Edition initialized");
    console_log!("📦 Features: Event Sourcing + Offline Sync + Cloud Sync");
}

/// バージョン情報を取得
#[wasm_bindgen]
pub fn version() -> String {
    format!("{}-browser", env!("CARGO_PKG_VERSION"))
}

/// ブラウザ環境情報を取得
#[wasm_bindgen]
pub fn browser_info() -> String {
    let mut features = Vec::new();
    
    if let Some(window) = window() {
        // LocalStorage対応確認
        if window.local_storage().is_ok() {
            features.push("LocalStorage");
        }
        
        // IndexedDB対応確認
        if js_sys::Reflect::has(&window, &JsValue::from_str("indexedDB")).unwrap_or(false) {
            features.push("IndexedDB");
        }
        
        // ServiceWorker対応確認
        let navigator = window.navigator();
        if js_sys::Reflect::has(&navigator, &JsValue::from_str("serviceWorker")).unwrap_or(false) {
            features.push("ServiceWorker");
        }
    }
    
    serde_json::json!({
        "version": version(),
        "features": features,
        "timestamp": Date::now() as u64
    }).to_string()
}

/// メモリ使用量を取得
#[wasm_bindgen]
pub fn memory_usage() -> u32 {
    if let Some(window) = window() {
        if let Some(performance) = window.performance() {
            if let Ok(memory) = js_sys::Reflect::get(&performance, &JsValue::from_str("memory")) {
                if let Ok(used_js_heap_size) = js_sys::Reflect::get(&memory, &JsValue::from_str("usedJSHeapSize")) {
                    return used_js_heap_size.as_f64().unwrap_or(0.0) as u32;
                }
            }
        }
    }
    0
}

/// ブラウザ用イベントソーシングDB
#[wasm_bindgen]
pub struct KawaBrowserDB {
    event_store: BrowserEventStore,
    sync_manager: SyncManager,
}

/// データベース設定
#[wasm_bindgen]
pub struct BrowserDBConfig {
    storage_type: StorageType,
    max_events: usize,
    enable_sync: bool,
    sync_endpoint: Option<String>,
    debug_mode: bool,
}

/// イベントデータ
#[wasm_bindgen]
pub struct EventData {
    event_type: String,
    data: String, // JSON string
    timestamp: u64,
}

/// クエリ結果
#[wasm_bindgen]
pub struct QueryResult {
    success: bool,
    data: String, // JSON string
    error: Option<String>,
}

#[wasm_bindgen]
impl BrowserDBConfig {
    /// 新しい設定を作成
    #[wasm_bindgen(constructor)]
    pub fn new() -> BrowserDBConfig {
        BrowserDBConfig {
            storage_type: StorageType::IndexedDB,
            max_events: 100_000,
            enable_sync: true,
            sync_endpoint: None,
            debug_mode: false,
        }
    }
    
    /// ストレージタイプを設定
    pub fn set_storage_type(&mut self, storage_type: StorageType) {
        self.storage_type = storage_type;
    }
    
    /// 最大イベント数を設定
    pub fn set_max_events(&mut self, max_events: usize) {
        self.max_events = max_events;
    }
    
    /// 同期機能を有効化
    pub fn enable_sync(&mut self, enable: bool) {
        self.enable_sync = enable;
    }
    
    /// 同期エンドポイントを設定
    pub fn set_sync_endpoint(&mut self, endpoint: String) {
        self.sync_endpoint = Some(endpoint);
    }
    
    /// デバッグモードを設定
    pub fn set_debug_mode(&mut self, debug: bool) {
        self.debug_mode = debug;
    }
}

#[wasm_bindgen]
impl KawaBrowserDB {
    /// 新しいブラウザDBを作成
    #[wasm_bindgen(js_name = createDB)]
    pub async fn new(config: BrowserDBConfig) -> Result<KawaBrowserDB, JsValue> {
        console_log!("🚀 Creating KawaBrowserDB...");
        
        // イベントストアを初期化
        let event_store = BrowserEventStore::new(BrowserEventStoreConfig {
            storage_type: config.storage_type.clone(),
            max_events: config.max_events,
            enable_offline_sync: config.enable_sync,
            debug_mode: config.debug_mode,
        }).await.map_err(|e| JsValue::from_str(&e))?;
        
        // 同期マネージャーを初期化
        let sync_manager = if config.enable_sync {
            SyncManager::new(SyncConfig {
                endpoint: config.sync_endpoint.clone(),
                auto_sync: true,
                sync_interval_ms: 30000, // 30秒
            })
        } else {
            SyncManager::disabled()
        };
        
        console_log!("✅ KawaBrowserDB created successfully");
        
        Ok(KawaBrowserDB {
            event_store,
            sync_manager,
        })
    }
    
    /// イベントを追加
    pub async fn add_event(&mut self, event_type: &str, data: &str) -> Result<String, JsValue> {
        let event = BrowserEvent {
            id: format!("event_{}", Date::now() as u64),
            event_type: event_type.to_string(),
            data: data.to_string(),
            timestamp: Date::now() as u64,
            metadata: HashMap::new(),
        };
        
        self.event_store.append_event(event.clone()).await
            .map_err(|e| JsValue::from_str(&e))?;
        
        // 同期が必要な場合は同期スケジュール
        if self.sync_manager.is_enabled() {
            self.sync_manager.schedule_sync().await;
        }
        
        Ok(event.id)
    }
    
    /// イベントを取得
    pub async fn get_events(&self, limit: Option<usize>) -> Result<String, JsValue> {
        let events = self.event_store.get_events(limit).await
            .map_err(|e| JsValue::from_str(&e))?;
        
        serde_json::to_string(&events)
            .map_err(|e| JsValue::from_str(&e.to_string()))
    }
    
    /// 現在の状態を取得
    pub async fn get_current_state(&self) -> Result<String, JsValue> {
        let state = self.event_store.get_current_state().await
            .map_err(|e| JsValue::from_str(&e))?;
        
        serde_json::to_string(&state)
            .map_err(|e| JsValue::from_str(&e.to_string()))
    }
    
    /// クエリを実行
    pub async fn query(&self, query_type: &str, _params: &str) -> Result<QueryResult, JsValue> {
        match query_type {
            "get_events" => {
                let events = self.get_events(None).await?;
                Ok(QueryResult {
                    success: true,
                    data: events,
                    error: None,
                })
            }
            "get_state" => {
                let state = self.get_current_state().await?;
                Ok(QueryResult {
                    success: true,
                    data: state,
                    error: None,
                })
            }
            _ => Ok(QueryResult {
                success: false,
                data: "{}".to_string(),
                error: Some(format!("Unknown query type: {}", query_type)),
            })
        }
    }
    
    /// データをクラウドに同期
    pub async fn sync_to_cloud(&mut self) -> Result<JsValue, JsValue> {
        if !self.sync_manager.is_enabled() {
            return Ok(JsValue::from_bool(false));
        }
        
        let events = self.event_store.get_events(None).await
            .map_err(|e| JsValue::from_str(&e))?;
        
        self.sync_manager.sync_events(events).await
            .map(|b| JsValue::from_bool(b))
            .map_err(|e| JsValue::from_str(&e))
    }
    
    /// クラウドからデータを同期
    pub async fn sync_from_cloud(&mut self) -> Result<JsValue, JsValue> {
        if !self.sync_manager.is_enabled() {
            return Ok(JsValue::from_bool(false));
        }
        
        let remote_events = self.sync_manager.fetch_remote_events().await
            .map_err(|e| JsValue::from_str(&e))?;
        
        for event in remote_events {
            self.event_store.append_event(event).await
                .map_err(|e| JsValue::from_str(&e))?;
        }
        
        Ok(JsValue::from_bool(true))
    }
    
    /// 統計情報を取得
    pub async fn get_stats(&self) -> Result<String, JsValue> {
        let stats = self.event_store.get_stats().await
            .map_err(|e| JsValue::from_str(&e))?;
        
        serde_json::to_string(&stats)
            .map_err(|e| JsValue::from_str(&e.to_string()))
    }
    
    /// データをクリア
    pub async fn clear_data(&mut self) -> Result<bool, JsValue> {
        self.event_store.clear().await
            .map_err(|e| JsValue::from_str(&e))
    }
}

#[wasm_bindgen]
impl EventData {
    /// 新しいイベントデータを作成
    #[wasm_bindgen(constructor)]
    pub fn new(event_type: String, data: String) -> EventData {
        EventData {
            event_type,
            data,
            timestamp: Date::now() as u64,
        }
    }
    
    /// イベントタイプを取得
    #[wasm_bindgen(getter)]
    pub fn event_type(&self) -> String {
        self.event_type.clone()
    }
    
    /// データを取得
    #[wasm_bindgen(getter)]
    pub fn data(&self) -> String {
        self.data.clone()
    }
    
    /// タイムスタンプを取得
    #[wasm_bindgen(getter)]
    pub fn timestamp(&self) -> u64 {
        self.timestamp
    }
}

#[wasm_bindgen]
impl QueryResult {
    /// 成功したかどうか
    #[wasm_bindgen(getter)]
    pub fn success(&self) -> bool {
        self.success
    }
    
    /// データを取得
    #[wasm_bindgen(getter)]
    pub fn data(&self) -> String {
        self.data.clone()
    }
    
    /// エラーメッセージを取得
    #[wasm_bindgen(getter)]
    pub fn error(&self) -> Option<String> {
        self.error.clone()
    }
}

/// JavaScript用のヘルパー関数
#[wasm_bindgen]
pub fn create_default_config() -> BrowserDBConfig {
    BrowserDBConfig::new()
}

/// サンプルイベントを作成
#[wasm_bindgen]
pub fn create_sample_event() -> EventData {
    EventData::new(
        "user_action".to_string(),
        serde_json::json!({
            "action": "login",
            "user_id": "user123",
            "timestamp": Date::now() as u64
        }).to_string()
    )
} 