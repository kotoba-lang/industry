//! # Kafka Protocol Implementation
//!
//! Kafka互換バイナリプロトコルの完全実装。
//! リクエスト/レスポンス処理、APIキールーティング、シリアライゼーション。

pub mod binary;
pub mod produce;
pub mod fetch;
pub mod metadata;
pub mod handler;
pub mod types;

pub use handler::KafkaProtocolHandler;
pub use types::{ProduceRequest, ConsumeRequest, ApiKey, ApiVersion};
pub use binary::{KafkaRequest, KafkaResponse, ProtocolReader, ProtocolWriter}; 