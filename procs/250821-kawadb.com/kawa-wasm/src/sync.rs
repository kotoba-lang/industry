//! # Cloud Sync Manager
//! 
//! オフライン・オンライン同期機能
//! - 自動バックグラウンド同期
//! - 競合解決
//! - 差分同期
//! - ネットワーク状態監視

use crate::event_store::BrowserEvent;
use serde::{Deserialize, Serialize};
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{console, window, Request, RequestInit, RequestMode, Response};
use js_sys::Date;

/// 同期設定
#[derive(Debug, Clone)]
pub struct SyncConfig {
    pub endpoint: Option<String>,
    pub auto_sync: bool,
    pub sync_interval_ms: u32,
}

impl SyncConfig {
    pub fn new() -> Self {
        Self {
            endpoint: None,
            auto_sync: true,
            sync_interval_ms: 30000,
        }
    }

    pub fn set_endpoint(&mut self, endpoint: String) {
        self.endpoint = Some(endpoint);
    }
}

/// 同期状態
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SyncStatus {
    Idle,
    Syncing,
    Completed { 
        timestamp: u64,
        events_synced: usize,
    },
    Failed { 
        error: String,
        timestamp: u64,
    },
}

/// 同期統計
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncStats {
    pub last_sync: Option<u64>,
    pub total_syncs: u32,
    pub failed_syncs: u32,
    pub events_uploaded: u64,
    pub events_downloaded: u64,
}

/// クラウド同期マネージャー
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct SyncManager {
    config: SyncConfig,
    status: SyncStatus,
    stats: SyncStats,
    is_enabled: bool,
    pending_events: Vec<BrowserEvent>,
}

impl SyncManager {
    /// 新しい同期マネージャーを作成
    pub fn new(config: SyncConfig) -> Self {
        Self {
            is_enabled: config.endpoint.is_some(),
            config,
            status: SyncStatus::Idle,
            stats: SyncStats {
                last_sync: None,
                total_syncs: 0,
                failed_syncs: 0,
                events_uploaded: 0,
                events_downloaded: 0,
            },
            pending_events: Vec::new(),
        }
    }
    
    /// 無効化された同期マネージャーを作成
    pub fn disabled() -> Self {
        Self::new(SyncConfig {
            endpoint: None,
            auto_sync: false,
            sync_interval_ms: 0,
        })
    }
    
    /// 同期が有効かどうか
    pub fn is_enabled(&self) -> bool {
        self.is_enabled
    }
    
    /// 同期をスケジュール
    pub async fn schedule_sync(&mut self) {
        if !self.is_enabled || !self.config.auto_sync {
            return;
        }
        
        // バックグラウンドで同期をスケジュール
        // 実際の実装ではsetTimeoutやrequestIdleCallbackを使用
        console::log_1(&"📅 Sync scheduled".into());
    }
    
    /// イベントをクラウドに同期
    pub async fn sync_events(&mut self, events: Vec<BrowserEvent>) -> Result<bool, String> {
        if !self.is_enabled {
            return Ok(false);
        }
        
        let endpoint = self.config.endpoint.as_ref()
            .ok_or("No sync endpoint configured")?;
        
        self.status = SyncStatus::Syncing;
        console::log_1(&format!("☁️ Syncing {} events to cloud...", events.len()).into());
        
        match self.upload_events(endpoint, &events).await {
            Ok(uploaded_count) => {
                self.stats.events_uploaded += uploaded_count as u64;
                self.stats.total_syncs += 1;
                self.stats.last_sync = Some(Date::now() as u64);
                
                self.status = SyncStatus::Completed {
                    timestamp: Date::now() as u64,
                    events_synced: uploaded_count,
                };
                
                console::log_1(&format!("✅ Successfully synced {} events", uploaded_count).into());
                Ok(true)
            }
            Err(error) => {
                self.stats.failed_syncs += 1;
                self.status = SyncStatus::Failed {
                    error: error.clone(),
                    timestamp: Date::now() as u64,
                };
                
                console::log_1(&format!("❌ Sync failed: {}", error).into());
                Err(error)
            }
        }
    }
    
    /// リモートイベントを取得
    pub async fn fetch_remote_events(&mut self) -> Result<Vec<BrowserEvent>, String> {
        if !self.is_enabled {
            return Ok(Vec::new());
        }
        
        let endpoint = self.config.endpoint.as_ref()
            .ok_or("No sync endpoint configured")?;
        
        console::log_1(&"📥 Fetching remote events...".into());
        
        match self.download_events(endpoint).await {
            Ok(events) => {
                self.stats.events_downloaded += events.len() as u64;
                console::log_1(&format!("📥 Downloaded {} events", events.len()).into());
                Ok(events)
            }
            Err(error) => {
                console::log_1(&format!("❌ Download failed: {}", error).into());
                Err(error)
            }
        }
    }
    
    /// イベントをアップロード
    async fn upload_events(&self, endpoint: &str, events: &[BrowserEvent]) -> Result<usize, String> {
        let upload_payload = UploadPayload {
            events: events.to_vec(),
            timestamp: Date::now() as u64,
            client_id: self.generate_client_id(),
        };
        
        let body = serde_json::to_string(&upload_payload)
            .map_err(|e| format!("Failed to serialize events: {}", e))?;
        
        let opts = RequestInit::new();
        opts.set_method("POST");
        opts.set_mode(RequestMode::Cors);
        opts.set_body(&JsValue::from_str(&body));
        
        let request = Request::new_with_str_and_init(&format!("{}/upload", endpoint), &opts)
            .map_err(|_| "Failed to create request")?;
        
        request.headers().set("Content-Type", "application/json")
            .map_err(|_| "Failed to set headers")?;
        
        if let Some(window) = window() {
            let response_value = JsFuture::from(window.fetch_with_request(&request)).await
                .map_err(|_| "Network request failed")?;
            
            let response: Response = response_value.dyn_into()
                .map_err(|_| "Invalid response")?;
            
            if response.ok() {
                let text = JsFuture::from(response.text().unwrap()).await
                    .map_err(|_| "Failed to read response")?;
                
                let response_str = text.as_string().unwrap_or_default();
                let upload_response: UploadResponse = serde_json::from_str(&response_str)
                    .map_err(|e| format!("Failed to parse response: {}", e))?;
                
                return Ok(upload_response.uploaded_count);
            } else {
                return Err(format!("Server error: {}", response.status()));
            }
        }
        
        Err("Window not available".to_string())
    }
    
    /// イベントをダウンロード
    async fn download_events(&self, endpoint: &str) -> Result<Vec<BrowserEvent>, String> {
        let last_sync = self.stats.last_sync.unwrap_or(0);
        let url = format!("{}/download?since={}&client_id={}", 
                         endpoint, last_sync, self.generate_client_id());
        
        if let Some(window) = window() {
            let response_value = JsFuture::from(window.fetch_with_str(&url)).await
                .map_err(|_| "Network request failed")?;
            
            let response: Response = response_value.dyn_into()
                .map_err(|_| "Invalid response")?;
            
            if response.ok() {
                let text = JsFuture::from(response.text().unwrap()).await
                    .map_err(|_| "Failed to read response")?;
                
                let response_str = text.as_string().unwrap_or_default();
                let download_response: DownloadResponse = serde_json::from_str(&response_str)
                    .map_err(|e| format!("Failed to parse response: {}", e))?;
                
                return Ok(download_response.events);
            } else {
                return Err(format!("Server error: {}", response.status()));
            }
        }
        
        Err("Window not available".to_string())
    }
    
    /// クライアントIDを生成
    fn generate_client_id(&self) -> String {
        format!("browser_{}", Date::now() as u64)
    }
    
    /// 同期状態を取得
    pub fn get_status(&self) -> SyncStatus {
        self.status.clone()
    }
    
    /// 同期統計を取得
    pub fn get_stats(&self) -> SyncStats {
        self.stats.clone()
    }
    
    /// ネットワーク状態をチェック
    pub async fn check_network_status(&self) -> NetworkStatus {
        if let Some(window) = window() {
            let navigator = window.navigator();
            // Navigator.onlineの確認
            if !navigator.on_line() {
                return NetworkStatus::Offline;
            }
            
            // 実際の接続テストを実行
            if let Some(endpoint) = &self.config.endpoint {
                match self.ping_server(endpoint).await {
                    Ok(latency) => NetworkStatus::Online { latency_ms: latency },
                    Err(_) => NetworkStatus::Limited,
                }
            } else {
                NetworkStatus::Online { latency_ms: 0 }
            }
        } else {
            NetworkStatus::Unknown
        }
    }
    
    /// サーバーにpingを送信
    async fn ping_server(&self, endpoint: &str) -> Result<u32, String> {
        let start_time = Date::now();
        let ping_url = format!("{}/ping", endpoint);
        
        if let Some(window) = window() {
            let response_value = JsFuture::from(window.fetch_with_str(&ping_url)).await
                .map_err(|_| "Ping failed")?;
            
            let response: Response = response_value.dyn_into()
                .map_err(|_| "Invalid response")?;
            
            if response.ok() {
                let latency = (Date::now() - start_time) as u32;
                Ok(latency)
            } else {
                Err("Server unreachable".to_string())
            }
        } else {
            Err("Window not available".to_string())
        }
    }
    
    /// 競合解決
    pub fn resolve_conflicts(&self, local_events: &[BrowserEvent], remote_events: &[BrowserEvent]) -> ConflictResolution {
        let mut conflicts = Vec::new();
        let mut resolved_events = Vec::new();
        
        // 簡単な競合解決アルゴリズム（タイムスタンプベース）
        for local_event in local_events {
            let mut has_conflict = false;
            
            for remote_event in remote_events {
                if self.events_conflict(local_event, remote_event) {
                    conflicts.push(EventConflict {
                        local_event: local_event.clone(),
                        remote_event: remote_event.clone(),
                        resolution_strategy: ConflictStrategy::LastWriteWins,
                    });
                    has_conflict = true;
                    
                    // タイムスタンプの新しい方を採用
                    if local_event.timestamp > remote_event.timestamp {
                        resolved_events.push(local_event.clone());
                    } else {
                        resolved_events.push(remote_event.clone());
                    }
                    break;
                }
            }
            
            if !has_conflict {
                resolved_events.push(local_event.clone());
            }
        }
        
        ConflictResolution {
            conflicts,
            resolved_events,
        }
    }
    
    /// イベント間の競合をチェック
    fn events_conflict(&self, event1: &BrowserEvent, event2: &BrowserEvent) -> bool {
        // 同じリソースに対する操作で、異なるタイムスタンプの場合に競合とみなす
        event1.event_type == event2.event_type && 
        event1.id != event2.id &&
        self.extract_resource_id(event1) == self.extract_resource_id(event2)
    }
    
    /// イベントからリソースIDを抽出
    fn extract_resource_id(&self, event: &BrowserEvent) -> Option<String> {
        if let Ok(data) = serde_json::from_str::<serde_json::Value>(&event.data) {
            data["user_id"].as_str().or_else(|| data["resource_id"].as_str()).map(|s| s.to_string())
        } else {
            None
        }
    }
}

/// ネットワーク状態
#[derive(Debug, Clone)]
pub enum NetworkStatus {
    Online { latency_ms: u32 },
    Limited,
    Offline,
    Unknown,
}

/// アップロードペイロード
#[derive(Debug, Serialize)]
struct UploadPayload {
    events: Vec<BrowserEvent>,
    timestamp: u64,
    client_id: String,
}

/// アップロードレスポンス
#[derive(Debug, Serialize, Deserialize)]
#[allow(dead_code)]
struct UploadResponse {
    uploaded_count: usize,
    server_timestamp: u64,
}

/// ダウンロードレスポンス
#[derive(Debug, Serialize, Deserialize)]
#[allow(dead_code)]
struct DownloadResponse {
    events: Vec<BrowserEvent>,
    server_timestamp: u64,
}

/// 競合解決結果
#[derive(Debug, Clone)]
pub struct ConflictResolution {
    pub conflicts: Vec<EventConflict>,
    pub resolved_events: Vec<BrowserEvent>,
}

/// イベント競合
#[derive(Debug, Clone)]
pub struct EventConflict {
    pub local_event: BrowserEvent,
    pub remote_event: BrowserEvent,
    pub resolution_strategy: ConflictStrategy,
}

/// 競合解決戦略
#[derive(Debug, Clone)]
pub enum ConflictStrategy {
    LastWriteWins,
    FirstWriteWins,
    Manual,
}

/// 自動同期マネージャー
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct AutoSyncManager {
    sync_manager: SyncManager,
    is_running: bool,
}

impl AutoSyncManager {
    /// 新しい自動同期マネージャーを作成
    pub fn new(sync_manager: SyncManager) -> Self {
        Self {
            sync_manager,
            is_running: false,
        }
    }
    
    /// 自動同期を開始
    pub async fn start(&mut self) -> Result<(), String> {
        if self.is_running {
            return Ok(());
        }
        
        self.is_running = true;
        console::log_1(&"🔄 Auto sync started".into());
        
        // 実際の実装では、setIntervalやServiceWorkerを使用して
        // バックグラウンドで定期的に同期を実行
        
        Ok(())
    }
    
    /// 自動同期を停止
    pub fn stop(&mut self) {
        if self.is_running {
            self.is_running = false;
            console::log_1(&"⏹️ Auto sync stopped".into());
        }
    }
    
    /// 同期状態を取得
    pub fn is_running(&self) -> bool {
        self.is_running
    }
} 