//! KawaDB Error Types

use thiserror::Error;

#[derive(Error, Debug)]
pub enum KawaDbError {
    #[error("Storage error: {0}")]
    StorageError(String),

    #[error("SQL parse error: {0}")]
    SqlParseError(String),

    #[error("Query execution error: {0}")]
    QueryExecutionError(String),

    #[error("Table not found: {0}")]
    TableNotFound(String),

    #[error("Column not found: {0}")]
    ColumnNotFound(String),

    #[error("Schema validation error: {0}")]
    SchemaValidationError(String),

    #[error("Data type mismatch: expected {expected}, got {actual}")]
    DataTypeMismatch { expected: String, actual: String },

    #[error("Invalid configuration: {0}")]
    InvalidConfiguration(String),

    #[error("I/O error: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Serialization error: {0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("DataFusion error: {0}")]
    DataFusionError(String),

    #[error("WASM runtime error: {0}")]
    WasmError(String),

    #[error("Internal error: {0}")]
    InternalError(String),
}

/// KawaDBの結果型
pub type KawaDbResult<T> = Result<T, KawaDbError>;

impl From<datafusion::error::DataFusionError> for KawaDbError {
    fn from(err: datafusion::error::DataFusionError) -> Self {
        KawaDbError::DataFusionError(err.to_string())
    }
}

impl From<sqlparser::parser::ParserError> for KawaDbError {
    fn from(err: sqlparser::parser::ParserError) -> Self {
        KawaDbError::SqlParseError(err.to_string())
    }
} 