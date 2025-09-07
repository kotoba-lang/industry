//! # Messaging API
//!
//! Kafka互換のメッセージング機能を提供。
//! トピック管理、パーティション管理、オフセット管理を統合。

pub mod topic;
pub mod partition;
pub mod offset;
pub mod producer;
pub mod consumer;

pub use topic::{TopicManager, TopicMetadata};
pub use partition::{PartitionManager, PartitionInfo};
pub use offset::{OffsetManager, ConsumerGroupOffset};
pub use producer::ProducerService;
pub use consumer::ConsumerService; 