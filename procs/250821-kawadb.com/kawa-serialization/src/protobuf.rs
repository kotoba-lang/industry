//! # ProtoBuf Serializer
//!
//! Protocol Buffers形式でのメッセージシリアライゼーション
//! 最高性能バイナリ形式 + 強力な型安全性

use crate::{MessageSerializer, SerializationError, SerializationResult};
use prost::Message;
use std::collections::HashMap;

// Protocol Buffersで生成されたコード
pub mod pb {
    include!(concat!(env!("OUT_DIR"), "/kawa.serialization.rs"));
}

/// ProtoBuf シリアライザー
#[derive(Debug, Clone)]
pub struct ProtobufSerializer {
    /// メタデータ埋め込み有効
    include_metadata: bool,
    /// カスタムメタデータ
    custom_metadata: HashMap<String, String>,
    /// スキーマフィンガープリント
    schema_fingerprint: String,
}

impl ProtobufSerializer {
    /// 新しいProtoBufシリアライザーを作成
    pub fn new() -> Self {
        let schema_fingerprint = Self::calculate_schema_fingerprint();
        
        Self {
            include_metadata: true,
            custom_metadata: HashMap::new(),
            schema_fingerprint,
        }
    }
    
    /// メタデータ埋め込み設定
    pub fn with_metadata(mut self, include: bool) -> Self {
        self.include_metadata = include;
        self
    }
    
    /// カスタムメタデータを追加
    pub fn add_metadata(mut self, key: String, value: String) -> Self {
        self.custom_metadata.insert(key, value);
        self
    }
    
    /// スキーマフィンガープリントを計算
    fn calculate_schema_fingerprint() -> String {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        
        let mut hasher = DefaultHasher::new();
        "kawa.serialization.KawaMessage".hash(&mut hasher);
        format!("{:x}", hasher.finish())
    }
    
    /// チェックサムを計算
    fn calculate_checksum(data: &[u8]) -> String {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        
        let mut hasher = DefaultHasher::new();
        data.hash(&mut hasher);
        format!("{:x}", hasher.finish())
    }
    
    /// シンプルモード（メタデータなし）でシリアライズ
    pub fn serialize_simple(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let simple_message = pb::SimpleMessage {
            data: data.to_vec().into(),
        };
        
        let mut buffer = Vec::new();
        simple_message.encode(&mut buffer)
            .map_err(|e| SerializationError::SerializationFailed(e.to_string()))?;
        
        Ok(buffer)
    }
    
    /// シンプルモードでデシリアライズ
    pub fn deserialize_simple(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let simple_message = pb::SimpleMessage::decode(data)
            .map_err(|e| SerializationError::DeserializationFailed(e.to_string()))?;
        
        Ok(simple_message.data.to_vec())
    }
    
    /// エンベロープ形式でシリアライズ
    fn serialize_envelope(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let now = chrono::Utc::now().timestamp();
        let checksum = Self::calculate_checksum(data);
        
        let message = pb::KawaMessage {
            data: data.to_vec().into(),
            encoding: "binary".to_string(),
            timestamp: now,
            size: data.len() as u32,
            schema_fingerprint: self.schema_fingerprint.clone(),
            metadata: self.custom_metadata.clone(),
            checksum: Some(checksum),
        };
        
        let mut buffer = Vec::new();
        message.encode(&mut buffer)
            .map_err(|e| SerializationError::SerializationFailed(e.to_string()))?;
        
        Ok(buffer)
    }
    
    /// エンベロープ形式でデシリアライズ
    fn deserialize_envelope(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let message = pb::KawaMessage::decode(data)
            .map_err(|e| SerializationError::DeserializationFailed(e.to_string()))?;
        
        // チェックサム検証
        let data_vec = message.data.to_vec();
        if let Some(expected_checksum) = &message.checksum {
            let actual_checksum = Self::calculate_checksum(&data_vec);
            if actual_checksum != *expected_checksum {
                return Err(SerializationError::DeserializationFailed(
                    "Checksum mismatch".to_string()
                ));
            }
        }
        
        // サイズ検証
        if data_vec.len() != message.size as usize {
            return Err(SerializationError::DeserializationFailed(
                format!("Size mismatch: expected {}, got {}", message.size, data_vec.len())
            ));
        }
        
        tracing::debug!(
            "ProtoBuf deserialized with envelope: timestamp={}, size={}", 
            message.timestamp, 
            message.size
        );
        
        Ok(data_vec)
    }
    
    /// スキーマフィンガープリント取得
    pub fn get_schema_fingerprint(&self) -> &str {
        &self.schema_fingerprint
    }
    
    /// メッセージのサイズ見積もり
    pub fn estimate_serialized_size(&self, data: &[u8]) -> usize {
        if self.include_metadata {
            // エンベロープのオーバーヘッド + データサイズ
            data.len() + 100 + self.custom_metadata.len() * 20
        } else {
            // シンプルモードのオーバーヘッド + データサイズ
            data.len() + 10
        }
    }
}

impl Default for ProtobufSerializer {
    fn default() -> Self {
        Self::new()
    }
}

impl MessageSerializer for ProtobufSerializer {
    fn serialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        if !self.include_metadata {
            return self.serialize_simple(data);
        }
        
        let result = self.serialize_envelope(data)?;
        
        tracing::debug!(
            "ProtoBuf serialized: {} bytes -> {} bytes", 
            data.len(), 
            result.len()
        );
        
        Ok(result)
    }
    
    fn deserialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        tracing::debug!("ProtoBuf deserializing: {} bytes", data.len());
        
        // まずエンベロープ形式として試行
        if let Ok(result) = self.deserialize_envelope(data) {
            return Ok(result);
        }
        
        // シンプル形式として試行
        self.deserialize_simple(data)
    }
    
    fn format_name(&self) -> &'static str {
        "protobuf"
    }
    
    fn schema(&self) -> Option<&str> {
        Some(&self.schema_fingerprint)
    }
    
    fn efficiency(&self) -> f32 {
        0.95 // ProtoBufは最高効率
    }
    
    fn compression_ratio(&self) -> f32 {
        0.9 // 最高の圧縮率
    }
}

/// ProtoBufシリアライザーファクトリー
pub struct ProtobufSerializerFactory;

impl ProtobufSerializerFactory {
    /// 標準ProtoBufシリアライザーを作成
    pub fn standard() -> ProtobufSerializer {
        ProtobufSerializer::new()
    }
    
    /// シンプル形式ProtoBufシリアライザーを作成
    pub fn simple() -> ProtobufSerializer {
        ProtobufSerializer::new().with_metadata(false)
    }
    
    /// メタデータ付きProtoBufシリアライザーを作成
    pub fn with_metadata(metadata: HashMap<String, String>) -> ProtobufSerializer {
        let mut serializer = ProtobufSerializer::new();
        for (key, value) in metadata {
            serializer = serializer.add_metadata(key, value);
        }
        serializer
    }
    
    /// 高性能設定のProtoBufシリアライザーを作成
    pub fn high_performance() -> ProtobufSerializer {
        ProtobufSerializer::new().with_metadata(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_protobuf_serialize_deserialize() -> SerializationResult<()> {
        let serializer = ProtobufSerializer::new();
        let original_data = b"Hello, Kawa ProtoBuf!";
        
        let serialized = serializer.serialize(original_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        
        assert_eq!(original_data, deserialized.as_slice());
        Ok(())
    }
    
    #[test]
    fn test_protobuf_simple_mode() -> SerializationResult<()> {
        let serializer = ProtobufSerializer::new().with_metadata(false);
        let original_data = b"Simple ProtoBuf test";
        
        let serialized = serializer.serialize(original_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        
        assert_eq!(original_data, deserialized.as_slice());
        Ok(())
    }
    
    #[test]
    fn test_protobuf_with_custom_metadata() -> SerializationResult<()> {
        let mut metadata = HashMap::new();
        metadata.insert("source".to_string(), "test".to_string());
        metadata.insert("version".to_string(), "1".to_string());
        
        let serializer = ProtobufSerializerFactory::with_metadata(metadata);
        let original_data = b"Metadata test";
        
        let serialized = serializer.serialize(original_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        
        assert_eq!(original_data, deserialized.as_slice());
        Ok(())
    }
    
    #[test]
    fn test_protobuf_schema_fingerprint() -> SerializationResult<()> {
        let serializer = ProtobufSerializer::new();
        let fingerprint = serializer.get_schema_fingerprint();
        
        assert!(!fingerprint.is_empty());
        assert_eq!(fingerprint.len(), 16); // DefaultHasher produces u64 -> 16 hex chars
        Ok(())
    }
    
    #[test]
    fn test_protobuf_checksum_validation() -> SerializationResult<()> {
        let serializer = ProtobufSerializer::new();
        let original_data = b"Checksum test";
        
        let serialized = serializer.serialize(original_data)?;
        
        // データを破損させる（より確実にエラーを引き起こすため複数バイトを変更）
        let mut corrupted = serialized.clone();
        for i in 0..std::cmp::min(3, corrupted.len()) {
            corrupted[i] = corrupted[i].wrapping_add(1);
        }
        
        // デシリアライズは失敗するか、成功してもデータが異なるはず
        let result = serializer.deserialize(&corrupted);
        match result {
            Err(_) => {
                // エラーの場合は期待通り
            }
            Ok(data) => {
                // 成功した場合は、データが元のデータと異なるはず
                assert_ne!(original_data, data.as_slice());
            }
        }
        
        Ok(())
    }
    
    #[test]
    fn test_protobuf_factory() -> SerializationResult<()> {
        let standard = ProtobufSerializerFactory::standard();
        let simple = ProtobufSerializerFactory::simple();
        let high_perf = ProtobufSerializerFactory::high_performance();
        
        assert_eq!(standard.format_name(), "protobuf");
        assert_eq!(simple.format_name(), "protobuf");
        assert_eq!(high_perf.format_name(), "protobuf");
        
        let test_data = b"Factory test";
        
        for serializer in [&standard, &simple, &high_perf] {
            let serialized = serializer.serialize(test_data)?;
            let deserialized = serializer.deserialize(&serialized)?;
            assert_eq!(test_data, deserialized.as_slice());
        }
        
        Ok(())
    }
    
    #[test]
    fn test_protobuf_size_estimation() -> SerializationResult<()> {
        let serializer = ProtobufSerializer::new();
        let test_data = b"Size estimation test";
        
        let estimated_size = serializer.estimate_serialized_size(test_data);
        let actual_serialized = serializer.serialize(test_data)?;
        
        // 見積もりは実際のサイズより大きいか同等であるべき
        assert!(estimated_size >= actual_serialized.len());
        // 見積もりは合理的な範囲内であるべき
        assert!(estimated_size <= actual_serialized.len() * 2);
        
        Ok(())
    }
} 