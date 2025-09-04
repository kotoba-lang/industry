//! # Serialization Error Types
//!
//! エラー型定義とハンドリング

use thiserror::Error;

/// シリアライゼーションエラー
#[derive(Error, Debug)]
pub enum SerializationError {
    #[error("Unsupported format: {0}")]
    UnsupportedFormat(String),
    
    #[error("Serialization failed: {0}")]
    SerializationFailed(String),
    
    #[error("Deserialization failed: {0}")]
    DeserializationFailed(String),
    
    #[error("Schema error: {0}")]
    SchemaError(String),
    
    #[error("No serialization format available")]
    NoFormatAvailable,
    
    #[error("I/O error: {0}")]
    IoError(#[from] std::io::Error),
    
    #[error("JSON error: {0}")]
    JsonError(#[from] serde_json::Error),
    
    #[error("Base64 decode error: {0}")]
    Base64Error(#[from] base64::DecodeError),
    
    #[cfg(feature = "avro")]
    #[error("Avro error: {0}")]
    AvroError(apache_avro::Error),
    
    #[cfg(feature = "protobuf")]
    #[error("ProtoBuf error: {0}")]
    ProtobufError(prost::DecodeError),
    
    #[error("Invalid data format")]
    InvalidDataFormat,
    
    #[error("Schema validation failed: {0}")]
    SchemaValidationFailed(String),
}

/// シリアライゼーション結果型
pub type SerializationResult<T> = Result<T, SerializationError>;

#[cfg(feature = "avro")]
impl From<apache_avro::Error> for SerializationError {
    fn from(err: apache_avro::Error) -> Self {
        Self::AvroError(err)
    }
}

impl SerializationError {
    /// エラーが再試行可能かどうか判定
    pub fn is_retryable(&self) -> bool {
        match self {
            Self::IoError(_) => true,
            Self::SerializationFailed(_) => false,
            Self::DeserializationFailed(_) => false,
            Self::UnsupportedFormat(_) => false,
            Self::SchemaError(_) => false,
            Self::NoFormatAvailable => false,
            Self::JsonError(_) => false,
            Self::Base64Error(_) => false,
            Self::InvalidDataFormat => false,
            Self::SchemaValidationFailed(_) => false,
            #[cfg(feature = "avro")]
            Self::AvroError(_) => false,
            #[cfg(feature = "protobuf")]
            Self::ProtobufError(_) => false,
        }
    }
    
    /// エラーの重要度を判定
    pub fn severity(&self) -> ErrorSeverity {
        match self {
            Self::NoFormatAvailable => ErrorSeverity::Critical,
            Self::IoError(_) => ErrorSeverity::High,
            Self::SchemaError(_) => ErrorSeverity::High,
            Self::UnsupportedFormat(_) => ErrorSeverity::Medium,
            Self::SerializationFailed(_) => ErrorSeverity::Medium,
            Self::DeserializationFailed(_) => ErrorSeverity::Medium,
            Self::JsonError(_) => ErrorSeverity::Low,
            Self::Base64Error(_) => ErrorSeverity::Low,
            Self::InvalidDataFormat => ErrorSeverity::Low,
            Self::SchemaValidationFailed(_) => ErrorSeverity::Medium,
            #[cfg(feature = "avro")]
            Self::AvroError(_) => ErrorSeverity::Medium,
            #[cfg(feature = "protobuf")]
            Self::ProtobufError(_) => ErrorSeverity::Medium,
        }
    }
}

/// エラーの重要度
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorSeverity {
    /// 致命的エラー
    Critical,
    /// 高優先度エラー
    High,
    /// 中優先度エラー
    Medium,
    /// 低優先度エラー
    Low,
} 