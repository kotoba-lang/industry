//! # Broker Error Types
//!
//! ブローカーモジュールのエラー型定義。
//! ネットワーク、プロトコル、ストレージエラーを統合的に管理。

use thiserror::Error;
use std::{io, net::SocketAddr};

/// ブローカーエラー型
/// 
/// # エラーカテゴリ
/// - ネットワークエラー: 接続、TLS、タイムアウト関連
/// - プロトコルエラー: Kafkaプロトコル処理関連
/// - ストレージエラー: 永続化層関連
/// - 認証エラー: SASL、権限関連
/// - 設定エラー: 無効な設定値
/// 
/// # @todo
/// - [ ] エラーコード体系の統一
/// - [ ] クライアント向けエラーメッセージ
/// - [ ] メトリクス連携
/// - [ ] ログレベル設定
#[derive(Error, Debug)]
pub enum BrokerError {
    /// I/Oエラー
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    
    /// ストレージエラー
    #[error("Storage error: {0}")]
    Storage(#[from] kawa_storage::StorageError),
    
    /// ネットワーク接続エラー
    #[error("Network connection error: {message} (address: {address})")]
    NetworkConnection {
        message: String,
        address: SocketAddr,
    },
    
    /// TLS/SSL エラー
    #[error("TLS error: {message}")]
    Tls { message: String },
    
    /// プロトコルエラー - 無効なKafkaプロトコル
    #[error("Invalid Kafka protocol: {message}")]
    InvalidProtocol { message: String },
    
    /// プロトコルエラー - サポートされていないバージョン
    #[error("Unsupported protocol version: {version}")]
    UnsupportedVersion { version: i16 },
    
    /// 認証エラー - SASL認証失敗
    #[error("SASL authentication failed: {mechanism}")]
    SaslAuthFailed { mechanism: String },
    
    /// 認証エラー - 権限不足
    #[error("Access denied: {resource} (user: {user})")]
    AccessDenied {
        resource: String,
        user: String,
    },
    
    /// セッションエラー - 無効なセッション
    #[error("Invalid session: {session_id}")]
    InvalidSession { session_id: String },
    
    /// セッションエラー - セッションタイムアウト
    #[error("Session timeout: {session_id}")]
    SessionTimeout { session_id: String },
    
    /// トピックエラー - 存在しないトピック
    #[error("Topic not found: {topic}")]
    TopicNotFound { topic: String },
    
    /// パーティションエラー - 無効なパーティション
    #[error("Invalid partition: {partition} for topic {topic}")]
    InvalidPartition { topic: String, partition: u32 },
    
    /// 設定エラー
    #[error("Configuration error: {message}")]
    Configuration { message: String },
    
    /// サーバーエラー - バインド失敗
    #[error("Failed to bind server: {address}")]
    BindFailed { address: SocketAddr },
    
    /// サーバーエラー - サーバー未開始
    #[error("Server not started")]
    ServerNotStarted,
    
    /// リソース制限エラー
    #[error("Resource limit exceeded: {resource} (limit: {limit}, current: {current})")]
    ResourceLimitExceeded {
        resource: String,
        limit: u64,
        current: u64,
    },
    
    /// タイムアウトエラー
    #[error("Operation timeout: {operation} (timeout: {timeout_ms}ms)")]
    Timeout {
        operation: String,
        timeout_ms: u64,
    },
    
    /// シリアライゼーションエラー
    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),
    
    /// 内部エラー（予期しないエラー）
    #[error("Internal error: {message}")]
    Internal { message: String },
}

impl BrokerError {
    /// ストレージエラーを作成
    pub fn storage(err: kawa_storage::StorageError) -> Self {
        Self::Storage(err)
    }
    
    /// ネットワーク接続エラーを作成
    pub fn network_connection(message: impl Into<String>, address: SocketAddr) -> Self {
        Self::NetworkConnection {
            message: message.into(),
            address,
        }
    }
    
    /// TLSエラーを作成
    pub fn tls(message: impl Into<String>) -> Self {
        Self::Tls {
            message: message.into(),
        }
    }
    
    /// 無効なプロトコルエラーを作成
    pub fn invalid_protocol(message: impl Into<String>) -> Self {
        Self::InvalidProtocol {
            message: message.into(),
        }
    }
    
    /// 設定エラーを作成
    pub fn configuration(message: impl Into<String>) -> Self {
        Self::Configuration {
            message: message.into(),
        }
    }
    
    /// 設定エラーを作成（エイリアス）
    pub fn configuration_error(message: impl Into<String>) -> Self {
        Self::configuration(message)
    }
    
    /// シリアライゼーションエラーを作成
    pub fn serialization_error(message: impl Into<String>) -> Self {
        Self::Internal {
            message: format!("Serialization error: {}", message.into()),
        }
    }
    
    /// 内部エラーを作成
    pub fn internal(message: impl Into<String>) -> Self {
        Self::Internal {
            message: message.into(),
        }
    }
    
    /// セッションタイムアウトエラーを作成
    pub fn session_timeout(session_id: impl Into<String>) -> Self {
        Self::SessionTimeout {
            session_id: session_id.into(),
        }
    }
    
    /// トピック未発見エラーを作成
    pub fn topic_not_found(topic: impl Into<String>) -> Self {
        Self::TopicNotFound {
            topic: topic.into(),
        }
    }
    
    /// 無効なパーティションエラーを作成
    pub fn invalid_partition(topic: impl Into<String>, partition: u32) -> Self {
        Self::InvalidPartition {
            topic: topic.into(),
            partition,
        }
    }
    
    /// SASL認証失敗エラーを作成
    pub fn sasl_auth_failed(mechanism: impl Into<String>) -> Self {
        Self::SaslAuthFailed {
            mechanism: mechanism.into(),
        }
    }
    
    /// アクセス拒否エラーを作成
    pub fn access_denied(resource: impl Into<String>, user: impl Into<String>) -> Self {
        Self::AccessDenied {
            resource: resource.into(),
            user: user.into(),
        }
    }
    
    /// タイムアウトエラーを作成
    pub fn timeout(operation: impl Into<String>, timeout_ms: u64) -> Self {
        Self::Timeout {
            operation: operation.into(),
            timeout_ms,
        }
    }
    
    /// リソース制限エラーを作成
    pub fn resource_limit_exceeded(
        resource: impl Into<String>,
        limit: u64,
        current: u64,
    ) -> Self {
        Self::ResourceLimitExceeded {
            resource: resource.into(),
            limit,
            current,
        }
    }
    
    /// このエラーがリトライ可能かどうかを判定
    /// 
    /// # Returns
    /// * `true` - リトライ可能なエラー
    /// * `false` - リトライ不可能なエラー
    /// 
    /// # @todo
    /// - [ ] より詳細なリトライ判定ロジック
    /// - [ ] 指数バックオフ戦略
    pub fn is_retryable(&self) -> bool {
        match self {
            Self::Io(_) => true,
            Self::Storage(_) => false,
            Self::NetworkConnection { .. } => true,
            Self::Tls { .. } => false,
            Self::InvalidProtocol { .. } => false,
            Self::UnsupportedVersion { .. } => false,
            Self::SaslAuthFailed { .. } => false,
            Self::AccessDenied { .. } => false,
            Self::InvalidSession { .. } => false,
            Self::SessionTimeout { .. } => true,
            Self::TopicNotFound { .. } => false,
            Self::InvalidPartition { .. } => false,
            Self::Configuration { .. } => false,
            Self::BindFailed { .. } => false,
            Self::ServerNotStarted => false,
            Self::ResourceLimitExceeded { .. } => true,
            Self::Timeout { .. } => true,
            Self::Serialization(_) => false,
            Self::Internal { .. } => false,
        }
    }
    
    /// エラーの重要度を取得
    /// 
    /// # Returns
    /// - `High`: 即座に対処が必要
    /// - `Medium`: 監視が必要
    /// - `Low`: 通常のエラー
    pub fn severity(&self) -> ErrorSeverity {
        match self {
            Self::Internal { .. } => ErrorSeverity::High,
            Self::BindFailed { .. } => ErrorSeverity::High,
            Self::Storage(_) => ErrorSeverity::High,
            Self::Configuration { .. } => ErrorSeverity::Medium,
            Self::Tls { .. } => ErrorSeverity::Medium,
            Self::UnsupportedVersion { .. } => ErrorSeverity::Medium,
            Self::ResourceLimitExceeded { .. } => ErrorSeverity::Medium,
            Self::Io(_) => ErrorSeverity::Low,
            Self::NetworkConnection { .. } => ErrorSeverity::Low,
            Self::InvalidProtocol { .. } => ErrorSeverity::Low,
            Self::SaslAuthFailed { .. } => ErrorSeverity::Low,
            Self::AccessDenied { .. } => ErrorSeverity::Low,
            Self::InvalidSession { .. } => ErrorSeverity::Low,
            Self::SessionTimeout { .. } => ErrorSeverity::Low,
            Self::TopicNotFound { .. } => ErrorSeverity::Low,
            Self::InvalidPartition { .. } => ErrorSeverity::Low,
            Self::ServerNotStarted => ErrorSeverity::Low,
            Self::Timeout { .. } => ErrorSeverity::Low,
            Self::Serialization(_) => ErrorSeverity::Low,
        }
    }
    
    /// クライアント向けのエラーコードを取得
    /// 
    /// Kafkaプロトコルのエラーコード体系に対応。
    /// 
    /// # Returns
    /// * `i16` - Kafkaエラーコード
    /// 
    /// # @todo
    /// - [ ] 完全なKafkaエラーコード対応
    /// - [ ] カスタムエラーコード定義
    pub fn kafka_error_code(&self) -> i16 {
        match self {
            Self::TopicNotFound { .. } => 3, // UNKNOWN_TOPIC_OR_PARTITION
            Self::InvalidPartition { .. } => 3, // UNKNOWN_TOPIC_OR_PARTITION
            Self::SaslAuthFailed { .. } => 58, // SASL_AUTHENTICATION_FAILED
            Self::AccessDenied { .. } => 29, // TOPIC_AUTHORIZATION_FAILED
            Self::InvalidProtocol { .. } => 43, // INVALID_REQUEST
            Self::UnsupportedVersion { .. } => 35, // UNSUPPORTED_VERSION
            Self::SessionTimeout { .. } => 25, // REQUEST_TIMED_OUT
            Self::Timeout { .. } => 25, // REQUEST_TIMED_OUT
            Self::ResourceLimitExceeded { .. } => 80, // POLICY_VIOLATION
            _ => -1, // UNKNOWN_SERVER_ERROR
        }
    }
}

/// エラーの重要度
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorSeverity {
    /// 高: 即座に対処が必要
    High,
    /// 中: 監視が必要
    Medium,
    /// 低: 通常のエラー
    Low,
}

/// ブローカー結果型の型エイリアス
pub type BrokerResult<T> = Result<T, BrokerError>; 