//! # Broker Configuration
//!
//! ブローカーの設定管理モジュール。
//! ネットワーク、認証、パフォーマンス等の設定を統合管理。

use serde::{Deserialize, Serialize};
use std::{
    net::SocketAddr,
    path::PathBuf,
    time::Instant,
};

/// ブローカー設定
/// 
/// # 設定カテゴリ
/// - ネットワーク設定: バインドアドレス、ポート、TLS
/// - ストレージ設定: データディレクトリ、セグメントサイズ
/// - 認証設定: SASL、ACL
/// - パフォーマンス設定: 接続数制限、タイムアウト
/// - ログ設定: ログレベル、出力先
/// 
/// # @todo
/// - [ ] 環境変数からの設定読み込み
/// - [ ] 設定の動的更新
/// - [ ] 設定検証の強化
/// - [ ] 設定テンプレート
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BrokerConfig {
    /// ネットワーク設定
    pub network: NetworkConfig,
    
    /// ストレージ設定
    pub storage: StorageConfig,
    
    /// 認証設定
    pub auth: AuthConfig,
    
    /// パフォーマンス設定
    pub performance: PerformanceConfig,
    
    /// ログ設定
    pub logging: LoggingConfig,
    
    /// 管理API設定
    pub management: ManagementConfig,
    
    /// 開始時刻（実行時に設定）
    #[serde(skip, default = "std::time::Instant::now")]
    pub started_at: Instant,
}

impl Default for BrokerConfig {
    fn default() -> Self {
        Self {
            network: NetworkConfig::default(),
            storage: StorageConfig::default(),
            auth: AuthConfig::default(),
            performance: PerformanceConfig::default(),
            logging: LoggingConfig::default(),
            management: ManagementConfig::default(),
            started_at: Instant::now(),
        }
    }
}

impl BrokerConfig {
    /// 設定ファイルから読み込み
    /// 
    /// # Arguments
    /// * `path` - 設定ファイルのパス
    /// 
    /// # Returns
    /// * `Result<Self>` - 設定インスタンス
    /// 
    /// # @todo
    /// - [ ] YAML、JSON形式サポート
    /// - [ ] 設定の継承機能
    /// - [ ] 設定の暗号化サポート
    pub fn from_file<P: AsRef<std::path::Path>>(path: P) -> Result<Self, Box<dyn std::error::Error>> {
        let content = std::fs::read_to_string(path)?;
        let mut config: Self = toml::from_str(&content)?;
        config.started_at = Instant::now();
        config.validate()?;
        Ok(config)
    }
    
    /// 設定を文字列として保存
    /// 
    /// # Returns
    /// * `Result<String>` - TOML形式の設定文字列
    pub fn to_string(&self) -> Result<String, Box<dyn std::error::Error>> {
        Ok(toml::to_string_pretty(self)?)
    }
    
    /// 設定をファイルに保存
    /// 
    /// # Arguments
    /// * `path` - 保存先ファイルパス
    /// 
    /// # Returns
    /// * `Result<()>` - 保存結果
    pub fn save_to_file<P: AsRef<std::path::Path>>(&self, path: P) -> Result<(), Box<dyn std::error::Error>> {
        let content = self.to_string()?;
        std::fs::write(path, content)?;
        Ok(())
    }
    
    /// 設定の検証
    /// 
    /// # Returns
    /// * `Result<()>` - 検証結果
    /// 
    /// # @todo
    /// - [ ] より詳細な検証ルール
    /// - [ ] 相互依存関係のチェック
    /// - [ ] リソース制限の妥当性チェック
    pub fn validate(&self) -> Result<(), Box<dyn std::error::Error>> {
        // ネットワーク設定の検証
        if self.network.bind_port == 0 {
            return Err("Invalid bind port: must be non-zero".into());
        }
        
        // ストレージ設定の検証
        if self.storage.segment_size == 0 {
            return Err("Invalid segment size: must be non-zero".into());
        }
        
        // パフォーマンス設定の検証
        if self.performance.max_connections == 0 {
            return Err("Invalid max connections: must be non-zero".into());
        }
        
        Ok(())
    }
    
    /// 簡単なアクセサーメソッド
    pub fn bind_address(&self) -> SocketAddr {
        SocketAddr::new(self.network.bind_address, self.network.bind_port)
    }
    
    pub fn data_dir(&self) -> &PathBuf {
        &self.storage.data_dir
    }
    
    pub fn segment_size(&self) -> u64 {
        self.storage.segment_size
    }
    
    pub fn sync_interval_ms(&self) -> u64 {
        self.storage.sync_interval_ms
    }
    
    pub fn enable_compression(&self) -> bool {
        self.storage.enable_compression
    }
    
    pub fn session_timeout_secs(&self) -> u64 {
        self.performance.session_timeout_secs
    }
}

/// ネットワーク設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NetworkConfig {
    /// バインドアドレス
    pub bind_address: std::net::IpAddr,
    
    /// バインドポート
    pub bind_port: u16,
    
    /// TLS有効フラグ
    pub enable_tls: bool,
    
    /// TLS証明書ファイルパス
    pub tls_cert_file: Option<PathBuf>,
    
    /// TLS秘密鍵ファイルパス
    pub tls_key_file: Option<PathBuf>,
    
    /// TLS CA証明書ファイルパス（クライアント認証用）
    pub tls_ca_file: Option<PathBuf>,
    
    /// TCP_NODELAY設定
    pub tcp_nodelay: bool,
    
    /// SO_REUSEADDR設定
    pub so_reuseaddr: bool,
    
    /// 受信バッファサイズ
    pub recv_buffer_size: Option<usize>,
    
    /// 送信バッファサイズ
    pub send_buffer_size: Option<usize>,
}

impl Default for NetworkConfig {
    fn default() -> Self {
        Self {
            bind_address: "0.0.0.0".parse().unwrap(),
            bind_port: 9092,
            enable_tls: false,
            tls_cert_file: None,
            tls_key_file: None,
            tls_ca_file: None,
            tcp_nodelay: true,
            so_reuseaddr: true,
            recv_buffer_size: Some(64 * 1024), // 64KB
            send_buffer_size: Some(64 * 1024), // 64KB
        }
    }
}

/// ストレージ設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    /// データディレクトリ
    pub data_dir: PathBuf,
    
    /// セグメントサイズ（バイト）
    pub segment_size: u64,
    
    /// 同期間隔（ミリ秒）
    pub sync_interval_ms: u64,
    
    /// 圧縮有効フラグ
    pub enable_compression: bool,
    
    /// セグメント保持期間（日）
    pub retention_days: Option<u32>,
    
    /// 最大ディスク使用量（バイト）
    pub max_disk_usage: Option<u64>,
}

impl Default for StorageConfig {
    fn default() -> Self {
        Self {
            data_dir: PathBuf::from("./data"),
            segment_size: 1024 * 1024 * 1024, // 1GB
            sync_interval_ms: 1000,
            enable_compression: false,
            retention_days: Some(7), // 7日間
            max_disk_usage: None,
        }
    }
}

/// 認証設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthConfig {
    /// SASL有効フラグ
    pub enable_sasl: bool,
    
    /// サポートするSASLメカニズム
    pub sasl_mechanisms: Vec<String>,
    
    /// SASL設定ファイルパス
    pub sasl_config_file: Option<PathBuf>,
    
    /// ACL有効フラグ
    pub enable_acl: bool,
    
    /// ACL設定ファイルパス
    pub acl_config_file: Option<PathBuf>,
    
    /// 匿名アクセス許可フラグ
    pub allow_anonymous: bool,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            enable_sasl: false,
            sasl_mechanisms: vec![
                "PLAIN".to_string(),
                "SCRAM-SHA-256".to_string(),
                "SCRAM-SHA-512".to_string(),
            ],
            sasl_config_file: None,
            enable_acl: false,
            acl_config_file: None,
            allow_anonymous: true,
        }
    }
}

/// パフォーマンス設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceConfig {
    /// 最大同時接続数
    pub max_connections: usize,
    
    /// セッションタイムアウト（秒）
    pub session_timeout_secs: u64,
    
    /// リクエストタイムアウト（ミリ秒）
    pub request_timeout_ms: u64,
    
    /// 最大リクエストサイズ（バイト）
    pub max_request_size: usize,
    
    /// ワーカースレッド数
    pub worker_threads: Option<usize>,
    
    /// I/Oスレッド数
    pub io_threads: Option<usize>,
    
    /// バッファプール初期サイズ
    pub buffer_pool_size: usize,
    
    /// 最大バッチサイズ
    pub max_batch_size: usize,
}

impl Default for PerformanceConfig {
    fn default() -> Self {
        Self {
            max_connections: 1000,
            session_timeout_secs: 300, // 5分
            request_timeout_ms: 30000, // 30秒
            max_request_size: 1024 * 1024, // 1MB
            worker_threads: None, // CPUコア数に基づいて自動設定
            io_threads: None, // CPUコア数に基づいて自動設定
            buffer_pool_size: 64 * 1024 * 1024, // 64MB
            max_batch_size: 500,
        }
    }
}

/// ログ設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingConfig {
    /// ログレベル
    pub level: String,
    
    /// コンソール出力有効フラグ
    pub enable_console: bool,
    
    /// ファイル出力有効フラグ
    pub enable_file: bool,
    
    /// ログファイルパス
    pub file_path: Option<PathBuf>,
    
    /// ログローテーション有効フラグ
    pub enable_rotation: bool,
    
    /// 最大ログファイルサイズ（バイト）
    pub max_file_size: Option<u64>,
    
    /// 保持するログファイル数
    pub max_files: Option<usize>,
    
    /// JSON形式出力フラグ
    pub json_format: bool,
}

impl Default for LoggingConfig {
    fn default() -> Self {
        Self {
            level: "info".to_string(),
            enable_console: true,
            enable_file: false,
            file_path: Some(PathBuf::from("./logs/kawa-broker.log")),
            enable_rotation: true,
            max_file_size: Some(100 * 1024 * 1024), // 100MB
            max_files: Some(10),
            json_format: false,
        }
    }
}

/// 管理API設定
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ManagementConfig {
    /// 管理API有効フラグ
    pub enable: bool,
    
    /// 管理APIバインドアドレス
    pub bind_address: std::net::IpAddr,
    
    /// 管理APIバインドポート
    pub bind_port: u16,
    
    /// メトリクス有効フラグ
    pub enable_metrics: bool,
    
    /// ヘルスチェック有効フラグ
    pub enable_health_check: bool,
    
    /// 管理API認証有効フラグ
    pub enable_auth: bool,
    
    /// 管理API認証トークン
    pub auth_token: Option<String>,
}

impl Default for ManagementConfig {
    fn default() -> Self {
        Self {
            enable: true,
            bind_address: "127.0.0.1".parse().unwrap(),
            bind_port: 8080,
            enable_metrics: true,
            enable_health_check: true,
            enable_auth: false,
            auth_token: None,
        }
    }
} 