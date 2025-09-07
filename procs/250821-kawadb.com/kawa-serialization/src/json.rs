//! # JSON Serializer
//!
//! JSON形式でのメッセージシリアライゼーション

use crate::{MessageSerializer, SerializationError, SerializationResult};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;

/// JSON シリアライザー
#[derive(Debug, Clone)]
pub struct JsonSerializer {
    /// プリティプリント有効
    pretty: bool,
    /// メタデータ埋め込み有効
    include_metadata: bool,
    /// カスタムメタデータ
    custom_metadata: HashMap<String, Value>,
}

/// JSONメッセージエンベロープ
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonEnvelope {
    /// エンコードされたデータ
    pub data: String,
    /// エンコーディング形式
    pub encoding: String,
    /// タイムスタンプ（Unix時間）
    pub timestamp: i64,
    /// データサイズ（バイト）
    pub size: usize,
    /// カスタムメタデータ
    #[serde(skip_serializing_if = "HashMap::is_empty", default)]
    pub metadata: HashMap<String, Value>,
    /// チェックサム（オプション）
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checksum: Option<String>,
}

impl JsonSerializer {
    /// 新しいJSONシリアライザーを作成
    pub fn new() -> Self {
        Self {
            pretty: false,
            include_metadata: true,
            custom_metadata: HashMap::new(),
        }
    }
    
    /// プリティプリント有効
    pub fn with_pretty(mut self) -> Self {
        self.pretty = true;
        self
    }
    
    /// メタデータ埋め込み設定
    pub fn with_metadata(mut self, include: bool) -> Self {
        self.include_metadata = include;
        self
    }
    
    /// カスタムメタデータを追加
    pub fn add_metadata<V: Into<Value>>(mut self, key: String, value: V) -> Self {
        self.custom_metadata.insert(key, value.into());
        self
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
        use base64::Engine;
        let json_data = base64::engine::general_purpose::STANDARD.encode(data);
        let result = if self.pretty {
            serde_json::to_vec_pretty(&json_data)?
        } else {
            serde_json::to_vec(&json_data)?
        };
        
        Ok(result)
    }
    
    /// シンプルモードでデシリアライズ
    pub fn deserialize_simple(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        use base64::Engine;
        let json_str: String = serde_json::from_slice(data)?;
        let decoded = base64::engine::general_purpose::STANDARD.decode(&json_str)?;
        Ok(decoded)
    }
}

impl Default for JsonSerializer {
    fn default() -> Self {
        Self::new()
    }
}

impl MessageSerializer for JsonSerializer {
    fn serialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        if !self.include_metadata {
            return self.serialize_simple(data);
        }
        
        // エンベロープ形式でシリアライズ
        use base64::Engine;
        let encoded_data = base64::engine::general_purpose::STANDARD.encode(data);
        let now = chrono::Utc::now().timestamp();
        
        let envelope = JsonEnvelope {
            data: encoded_data,
            encoding: "base64".to_string(),
            timestamp: now,
            size: data.len(),
            metadata: self.custom_metadata.clone(),
            checksum: Some(Self::calculate_checksum(data)),
        };
        
        let result = if self.pretty {
            serde_json::to_vec_pretty(&envelope)?
        } else {
            serde_json::to_vec(&envelope)?
        };
        
        tracing::debug!(
            "JSON serialized: {} bytes -> {} bytes", 
            data.len(), 
            result.len()
        );
        
        Ok(result)
    }
    
    fn deserialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        tracing::debug!("JSON deserializing: {} bytes", data.len());
        
        // まずエンベロープ形式として解析を試行
        if let Ok(envelope) = serde_json::from_slice::<JsonEnvelope>(data) {
            // チェックサム検証
            use base64::Engine;
            let decoded = base64::engine::general_purpose::STANDARD.decode(&envelope.data)?;
            
            if let Some(expected_checksum) = &envelope.checksum {
                let actual_checksum = Self::calculate_checksum(&decoded);
                if actual_checksum != *expected_checksum {
                    return Err(SerializationError::DeserializationFailed(
                        "Checksum mismatch".to_string()
                    ));
                }
            }
            
            // サイズ検証
            if decoded.len() != envelope.size {
                return Err(SerializationError::DeserializationFailed(
                    format!("Size mismatch: expected {}, got {}", envelope.size, decoded.len())
                ));
            }
            
            tracing::debug!(
                "JSON deserialized with metadata: timestamp={}, size={}", 
                envelope.timestamp, 
                envelope.size
            );
            
            return Ok(decoded);
        }
        
        // エンベロープ形式で失敗した場合、シンプル形式として試行
        if let Ok(simple_data) = self.deserialize_simple(data) {
            tracing::debug!("JSON deserialized as simple format");
            return Ok(simple_data);
        }
        
        // 古い形式（オブジェクト）として試行
        if let Ok(json_value) = serde_json::from_slice::<Value>(data) {
            if let Some(obj) = json_value.as_object() {
                if let Some(data_value) = obj.get("data") {
                    if let Some(data_str) = data_value.as_str() {
                        use base64::Engine;
                        let decoded = base64::engine::general_purpose::STANDARD.decode(data_str)?;
                        tracing::debug!("JSON deserialized as legacy format");
                        return Ok(decoded);
                    }
                }
            }
        }
        
        Err(SerializationError::DeserializationFailed(
            "Invalid JSON format".to_string()
        ))
    }
    
    fn format_name(&self) -> &'static str {
        "json"
    }
    
    fn schema(&self) -> Option<&str> {
        Some("kawa-json-envelope-v1")
    }
}

/// JSONシリアライザーファクトリー
pub struct JsonSerializerFactory;

impl JsonSerializerFactory {
    /// 標準JSONシリアライザーを作成
    pub fn standard() -> JsonSerializer {
        JsonSerializer::new()
    }
    
    /// プリティプリント用JSONシリアライザーを作成
    pub fn pretty() -> JsonSerializer {
        JsonSerializer::new().with_pretty()
    }
    
    /// シンプル形式JSONシリアライザーを作成
    pub fn simple() -> JsonSerializer {
        JsonSerializer::new().with_metadata(false)
    }
    
    /// カスタムメタデータ付きJSONシリアライザーを作成
    pub fn with_metadata(metadata: HashMap<String, Value>) -> JsonSerializer {
        let mut serializer = JsonSerializer::new();
        for (key, value) in metadata {
            serializer = serializer.add_metadata(key, value);
        }
        serializer
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn test_json_serialize_deserialize() {
        let serializer = JsonSerializer::new();
        let original_data = b"Hello, Kawa!";
        
        let serialized = serializer.serialize(original_data).unwrap();
        let deserialized = serializer.deserialize(&serialized).unwrap();
        
        assert_eq!(original_data, deserialized.as_slice());
    }
    
    #[test]
    fn test_json_simple_mode() {
        let serializer = JsonSerializer::new().with_metadata(false);
        let original_data = b"Simple test";
        
        let serialized = serializer.serialize(original_data).unwrap();
        let deserialized = serializer.deserialize(&serialized).unwrap();
        
        assert_eq!(original_data, deserialized.as_slice());
    }
    
    #[test]
    fn test_json_with_custom_metadata() {
        let mut metadata = HashMap::new();
        metadata.insert("source".to_string(), Value::String("test".to_string()));
        metadata.insert("version".to_string(), Value::Number(1.into()));
        
        let serializer = JsonSerializerFactory::with_metadata(metadata);
        let original_data = b"Metadata test";
        
        let serialized = serializer.serialize(original_data).unwrap();
        let deserialized = serializer.deserialize(&serialized).unwrap();
        
        assert_eq!(original_data, deserialized.as_slice());
        
        // シリアライズされたデータに含まれるメタデータを確認
        let envelope: JsonEnvelope = serde_json::from_slice(&serialized).unwrap();
        assert_eq!(envelope.metadata.get("source").unwrap(), "test");
        assert_eq!(envelope.metadata.get("version").unwrap(), &1);
    }
    
    #[test]
    fn test_json_pretty_print() {
        let serializer = JsonSerializer::new().with_pretty();
        let original_data = b"Pretty test";
        
        let serialized = serializer.serialize(original_data).unwrap();
        let serialized_str = String::from_utf8(serialized).unwrap();
        
        // プリティプリントなので改行が含まれているはず
        assert!(serialized_str.contains('\n'));
    }
    
    #[test]
    fn test_json_checksum_validation() {
        let serializer = JsonSerializer::new();
        let original_data = b"Checksum test";
        
        let serialized = serializer.serialize(original_data).unwrap();
        
        // チェックサムを破損させる
        let mut envelope: JsonEnvelope = serde_json::from_slice(&serialized).unwrap();
        envelope.checksum = Some("invalid_checksum".to_string());
        let corrupted = serde_json::to_vec(&envelope).unwrap();
        
        // デシリアライズがエラーになることを確認
        let result = serializer.deserialize(&corrupted);
        assert!(result.is_err());
    }
} 