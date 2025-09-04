//! # Storage Error Types
//!
//! ストレージエンジンのエラー型定義。
//! 詳細なエラー情報とコンテキストを提供する。

use thiserror::Error;
use std::io;

/// ストレージエンジンのエラー型
/// 
/// # エラーカテゴリ
/// - I/Oエラー: ファイルシステム関連
/// - データ整合性エラー: CRCチェック、フォーマット不正
/// - 設定エラー: 無効な設定値
/// - 容量エラー: ディスク容量不足
/// 
/// # @todo
/// - [ ] エラーメトリクスの追加
/// - [ ] リトライ可能エラーの分類
/// - [ ] 詳細なエラーコード体系
#[derive(Error, Debug)]
pub enum StorageError {
    /// I/Oエラー
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    
    /// データ整合性エラー - CRCチェック失敗
    #[error("Data integrity error: CRC mismatch at offset {offset}, expected {expected:x}, got {actual:x}")]
    CrcMismatch {
        offset: u64,
        expected: u32,
        actual: u32,
    },
    
    /// データフォーマットエラー
    #[error("Invalid data format: {message}")]
    InvalidFormat { message: String },
    
    /// セグメントが見つからない
    #[error("Segment not found: {segment_id:?}")]
    SegmentNotFound { segment_id: u64 },
    
    /// オフセット範囲外エラー
    #[error("Offset out of range: {offset} is beyond available data")]
    OffsetOutOfRange { offset: u64 },
    
    /// 設定エラー
    #[error("Configuration error: {message}")]
    Configuration { message: String },
    
    /// ディスク容量不足
    #[error("Insufficient disk space: need {required} bytes, available {available} bytes")]
    InsufficientSpace {
        required: u64,
        available: u64,
    },
    
    /// 同期エラー
    #[error("Synchronization error: {message}")]
    Synchronization { message: String },
    
    /// 内部エラー（予期しないエラー）
    #[error("Internal error: {message}")]
    Internal { message: String },
    
    #[error("Event too large: size={size}, max_size={max_size}")]
    EventTooLarge { size: u64, max_size: u64 },
    
    #[error("Bincode serialization/deserialization error: {0}")]
    Bincode(#[from] Box<bincode::ErrorKind>),

    #[cfg(feature = "compression")]
    #[error("Compression/decompression error: {0}")]
    Compression(#[from] snap::Error),
}

/// # @todo
/// - [ ] より詳細なエラー分類
/// - [ ] エラーコードの標準化
/// 
/// `StorageError`をラップする結果型。
pub type StorageResult<T> = Result<T, StorageError>;

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