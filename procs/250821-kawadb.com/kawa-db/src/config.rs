//! KawaDB Configuration

use kawa_storage::StorageConfig;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// KawaDBの設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DbConfig {
    /// ストレージ設定
    pub storage: StorageConfig,
    
    /// クエリエンジン設定
    pub query_engine: QueryEngineConfig,
    
    /// WASM設定
    pub wasm: WasmConfig,
    
    /// データベース固有設定
    pub database: DatabaseConfig,
}

impl DbConfig {
    /// デフォルト設定で新しいDbConfigを作成
    pub fn new(data_dir: PathBuf) -> Self {
        Self {
            storage: StorageConfig {
                data_dir: data_dir.clone(),
                segment_size: 1024 * 1024 * 1024, // 1GB
                sync_interval_ms: 1000,
                enable_compression: false,
                compression_type: None,
                memory_pool_size: 128 * 1024 * 1024, // 128MB
                batch_size: 1000,
                worker_count: Some(num_cpus::get() * 2),
            },
            query_engine: QueryEngineConfig::default(),
            wasm: WasmConfig::default(),
            database: DatabaseConfig {
                data_dir,
                ..Default::default()
            },
        }
    }
}

impl Default for DbConfig {
    fn default() -> Self {
        Self::new("./kawa-data".into())
    }
}

/// クエリエンジン設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryEngineConfig {
    /// 並行実行するクエリの最大数
    pub max_concurrent_queries: usize,
    
    /// クエリタイムアウト（秒）
    pub query_timeout_seconds: u64,
    
    /// メモリ制限（バイト）
    pub memory_limit_bytes: usize,
    
    /// パーティション数（並列処理）
    pub target_partitions: usize,
    
    /// バッチサイズ
    pub batch_size: usize,
}

impl Default for QueryEngineConfig {
    fn default() -> Self {
        Self {
            max_concurrent_queries: 10,
            query_timeout_seconds: 300, // 5分
            memory_limit_bytes: 1024 * 1024 * 1024, // 1GB
            target_partitions: num_cpus::get(),
            batch_size: 8192,
        }
    }
}

/// WASM設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WasmConfig {
    /// WASM機能を有効にするか
    pub enabled: bool,
    
    /// JavaScriptバインディングを有効にするか
    pub enable_js_bindings: bool,
    
    /// コンソールログを有効にするか
    pub enable_console_log: bool,
    
    /// WASM実行時のメモリ制限（バイト）
    pub memory_limit_bytes: usize,
}

impl Default for WasmConfig {
    fn default() -> Self {
        Self {
            enabled: cfg!(feature = "wasm"),
            enable_js_bindings: true,
            enable_console_log: true,
            memory_limit_bytes: 64 * 1024 * 1024, // 64MB
        }
    }
}

/// データベース固有設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    /// データディレクトリ
    pub data_dir: PathBuf,
    
    /// テーブル定義の永続化を有効にするか
    pub persist_schema: bool,
    
    /// 自動バックアップを有効にするか
    pub enable_auto_backup: bool,
    
    /// バックアップ間隔（秒）
    pub backup_interval_seconds: u64,
    
    /// 統計情報の自動更新を有効にするか
    pub enable_auto_stats: bool,
    
    /// WALモード（Write-Ahead Logging）
    pub wal_mode: WalMode,
}

impl Default for DatabaseConfig {
    fn default() -> Self {
        Self {
            data_dir: "./kawa-data".into(),
            persist_schema: true,
            enable_auto_backup: false,
            backup_interval_seconds: 3600, // 1時間
            enable_auto_stats: true,
            wal_mode: WalMode::Synchronous,
        }
    }
}

/// WALモード
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub enum WalMode {
    /// 同期書き込み（安全、低速）
    Synchronous,
    
    /// 非同期書き込み（高速、リスクあり）
    Asynchronous,
    
    /// メモリのみ（テスト用）
    Memory,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_default_config() {
        let config = DbConfig::default();
        assert_eq!(config.query_engine.max_concurrent_queries, 10);
        assert!(config.database.persist_schema);
    }

    #[test]
    fn test_custom_config() {
        let temp_dir = TempDir::new().unwrap();
        let config = DbConfig::new(temp_dir.path().to_path_buf());
        
        assert_eq!(config.storage.data_dir, temp_dir.path());
        assert_eq!(config.database.data_dir, temp_dir.path());
    }

    #[test]
    fn test_config_serialization() {
        let config = DbConfig::default();
        let serialized = serde_json::to_string(&config).unwrap();
        let deserialized: DbConfig = serde_json::from_str(&serialized).unwrap();
        
        assert_eq!(config.query_engine.max_concurrent_queries, 
                   deserialized.query_engine.max_concurrent_queries);
    }
} 