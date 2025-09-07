//! # Avro Serializer
//!
//! Apache Avro形式でのメッセージシリアライゼーション
//! 高効率バイナリ形式 + スキーマエボリューション対応

use crate::{MessageSerializer, SerializationError, SerializationResult};
use apache_avro::{Schema, Writer, Reader};
use std::collections::HashMap;

/// Avro シリアライザー
#[derive(Debug, Clone)]
pub struct AvroSerializer {
    /// スキーマ定義
    schema: Schema,
    /// メタデータ埋め込み有効
    include_metadata: bool,
    /// カスタムメタデータ
    custom_metadata: HashMap<String, String>,
    /// スキーマフィンガープリント
    schema_fingerprint: String,
}



impl AvroSerializer {
    /// 新しいAvroシリアライザーを作成
    pub fn new() -> SerializationResult<Self> {
        let schema = Self::default_schema()?;
        let schema_fingerprint = Self::calculate_schema_fingerprint(&schema);
        
        Ok(Self {
            schema,
            include_metadata: true,
            custom_metadata: HashMap::new(),
            schema_fingerprint,
        })
    }
    
    /// カスタムスキーマでAvroシリアライザーを作成
    pub fn with_schema(schema_str: &str) -> SerializationResult<Self> {
        let schema = Schema::parse_str(schema_str)
            .map_err(|e| SerializationError::SchemaError(e.to_string()))?;
        let schema_fingerprint = Self::calculate_schema_fingerprint(&schema);
        
        Ok(Self {
            schema,
            include_metadata: true,
            custom_metadata: HashMap::new(),
            schema_fingerprint,
        })
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
    
    /// デフォルトスキーマを生成
    fn default_schema() -> SerializationResult<Schema> {
        let schema_str = r#"
        {
            "type": "record",
            "name": "KawaMessage",
            "namespace": "com.kawa.serialization",
            "fields": [
                {
                    "name": "data",
                    "type": "bytes",
                    "doc": "メッセージのバイナリデータ"
                },
                {
                    "name": "encoding",
                    "type": "string",
                    "default": "binary",
                    "doc": "エンコーディング形式"
                },
                {
                    "name": "timestamp",
                    "type": "long",
                    "doc": "タイムスタンプ（Unix時間）"
                },
                {
                    "name": "size",
                    "type": "long",
                    "doc": "データサイズ（バイト）"
                },
                {
                    "name": "schema_fingerprint",
                    "type": "string",
                    "doc": "スキーマフィンガープリント"
                },
                {
                    "name": "metadata",
                    "type": {
                        "type": "map",
                        "values": "string"
                    },
                    "default": {},
                    "doc": "カスタムメタデータ"
                },
                {
                    "name": "checksum",
                    "type": ["null", "string"],
                    "default": null,
                    "doc": "チェックサム（オプション）"
                }
            ]
        }
        "#;
        
        Schema::parse_str(schema_str)
            .map_err(|e| SerializationError::SchemaError(e.to_string()))
    }
    
    /// スキーマフィンガープリントを計算
    fn calculate_schema_fingerprint(schema: &Schema) -> String {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        
        let mut hasher = DefaultHasher::new();
        schema.canonical_form().hash(&mut hasher);
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
        // 単純なバイト配列スキーマを使用
        let simple_schema = Schema::parse_str(r#"{"type": "bytes"}"#)
            .map_err(|e| SerializationError::SchemaError(e.to_string()))?;
        
        let mut writer = Writer::new(&simple_schema, Vec::new());
        let value = apache_avro::types::Value::Bytes(data.to_vec());
        writer.append(value)?;
        
        Ok(writer.into_inner()?)
    }
    
    /// シンプルモードでデシリアライズ
    pub fn deserialize_simple(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let reader = Reader::new(data)?;
        
        for record_result in reader {
            let record = record_result?;
            if let apache_avro::types::Value::Bytes(bytes) = record {
                return Ok(bytes);
            }
        }
        
        Err(SerializationError::DeserializationFailed(
            "No valid bytes record found".to_string()
        ))
    }
    
    /// エンベロープ形式でシリアライズ
    fn serialize_envelope(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let now = chrono::Utc::now().timestamp();
        
        // メタデータを使用
        let metadata = &self.custom_metadata;
        
        // Avroレコードを構築
        let record = apache_avro::types::Record::new(&self.schema)
            .ok_or_else(|| SerializationError::SchemaError("Failed to create record".to_string()))?;
            
        let mut record = record;
        record.put("data", apache_avro::types::Value::Bytes(data.to_vec()));
        record.put("encoding", apache_avro::types::Value::String("binary".to_string()));
        record.put("timestamp", apache_avro::types::Value::Long(now));
        record.put("size", apache_avro::types::Value::Long(data.len() as i64));
        record.put("schema_fingerprint", apache_avro::types::Value::String(self.schema_fingerprint.clone()));
        record.put("metadata", apache_avro::types::Value::Map(
            metadata.iter()
                .map(|(k, v)| (k.clone(), apache_avro::types::Value::String(v.clone())))
                .collect()
        ));
        record.put("checksum", apache_avro::types::Value::Union(
            1, // "string" variant index
            Box::new(apache_avro::types::Value::String(Self::calculate_checksum(data)))
        ));
        
        // Avroライターでシリアライズ
        let mut writer = Writer::new(&self.schema, Vec::new());
        writer.append(apache_avro::types::Value::Record(record.fields))?;
        
        Ok(writer.into_inner()?)
    }
    
    /// エンベロープ形式でデシリアライズ
    fn deserialize_envelope(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        let reader = Reader::new(data)?;
        
        for record_result in reader {
            let record = record_result?;
            
            if let apache_avro::types::Value::Record(fields) = record {
                let mut data_bytes: Option<Vec<u8>> = None;
                let mut checksum: Option<String> = None;
                
                for (field_name, field_value) in fields {
                    match field_name.as_str() {
                        "data" => {
                            if let apache_avro::types::Value::Bytes(bytes) = field_value {
                                data_bytes = Some(bytes);
                            }
                        }
                        "checksum" => {
                            if let apache_avro::types::Value::Union(_, boxed_value) = field_value {
                                if let apache_avro::types::Value::String(cs) = *boxed_value {
                                    checksum = Some(cs);
                                }
                            }
                        }
                        _ => {} // 他のフィールドはスキップ
                    }
                }
                
                if let Some(bytes) = data_bytes {
                    // チェックサム検証
                    if let Some(expected_checksum) = checksum {
                        let actual_checksum = Self::calculate_checksum(&bytes);
                        if actual_checksum != expected_checksum {
                            return Err(SerializationError::DeserializationFailed(
                                "Checksum mismatch".to_string()
                            ));
                        }
                    }
                    
                    tracing::debug!(
                        "Avro deserialized with envelope: size={}", 
                        bytes.len()
                    );
                    
                    return Ok(bytes);
                }
            }
        }
        
        Err(SerializationError::DeserializationFailed(
            "No valid envelope record found".to_string()
        ))
    }
    
    /// スキーマバリデーション
    pub fn validate_schema(&self, other_schema: &Schema) -> bool {
        // スキーマの互換性チェック（簡易版）
        self.schema.canonical_form() == other_schema.canonical_form()
    }
    
    /// スキーマフィンガープリント取得
    pub fn get_schema_fingerprint(&self) -> &str {
        &self.schema_fingerprint
    }
}

impl Default for AvroSerializer {
    fn default() -> Self {
        Self::new().expect("Failed to create default AvroSerializer")
    }
}

impl MessageSerializer for AvroSerializer {
    fn serialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        if !self.include_metadata {
            return self.serialize_simple(data);
        }
        
        let result = self.serialize_envelope(data)?;
        
        tracing::debug!(
            "Avro serialized: {} bytes -> {} bytes", 
            data.len(), 
            result.len()
        );
        
        Ok(result)
    }
    
    fn deserialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        tracing::debug!("Avro deserializing: {} bytes", data.len());
        
        // まずエンベロープ形式として試行
        if let Ok(result) = self.deserialize_envelope(data) {
            return Ok(result);
        }
        
        // シンプル形式として試行
        self.deserialize_simple(data)
    }
    
    fn format_name(&self) -> &'static str {
        "avro"
    }
    
    fn schema(&self) -> Option<&str> {
        Some(&self.schema_fingerprint)
    }
    
    fn efficiency(&self) -> f32 {
        0.9 // Avroは高効率
    }
    
    fn compression_ratio(&self) -> f32 {
        0.85 // 良い圧縮率
    }
}

/// Avroシリアライザーファクトリー
pub struct AvroSerializerFactory;

impl AvroSerializerFactory {
    /// 標準Avroシリアライザーを作成
    pub fn standard() -> SerializationResult<AvroSerializer> {
        AvroSerializer::new()
    }
    
    /// カスタムスキーマAvroシリアライザーを作成
    pub fn with_schema(schema_str: &str) -> SerializationResult<AvroSerializer> {
        AvroSerializer::with_schema(schema_str)
    }
    
    /// シンプル形式Avroシリアライザーを作成
    pub fn simple() -> SerializationResult<AvroSerializer> {
        Ok(AvroSerializer::new()?.with_metadata(false))
    }
    
    /// メタデータ付きAvroシリアライザーを作成
    pub fn with_metadata(metadata: HashMap<String, String>) -> SerializationResult<AvroSerializer> {
        let mut serializer = AvroSerializer::new()?;
        for (key, value) in metadata {
            serializer = serializer.add_metadata(key, value);
        }
        Ok(serializer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_avro_serialize_deserialize() -> SerializationResult<()> {
        let serializer = AvroSerializer::new()?;
        let original_data = b"Hello, Kawa Avro!";
        
        let serialized = serializer.serialize(original_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        
        assert_eq!(original_data, deserialized.as_slice());
        Ok(())
    }
    
    #[test]
    fn test_avro_simple_mode() -> SerializationResult<()> {
        let serializer = AvroSerializer::new()?.with_metadata(false);
        let original_data = b"Simple Avro test";
        
        let serialized = serializer.serialize(original_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        
        assert_eq!(original_data, deserialized.as_slice());
        Ok(())
    }
    
    #[test]
    fn test_avro_custom_schema() -> SerializationResult<()> {
        // シンプルなバイト配列スキーマで検証
        let custom_schema = r#"{"type": "bytes"}"#;
        
        let serializer = AvroSerializer::with_schema(custom_schema)?.with_metadata(false);
        let original_data = b"Custom schema test";
        
        let serialized = serializer.serialize(original_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        
        assert_eq!(original_data, deserialized.as_slice());
        Ok(())
    }
    
    #[test]
    fn test_avro_schema_fingerprint() -> SerializationResult<()> {
        let serializer = AvroSerializer::new()?;
        let fingerprint = serializer.get_schema_fingerprint();
        
        assert!(!fingerprint.is_empty());
        assert_eq!(fingerprint.len(), 16); // DefaultHasher produces u64 -> 16 hex chars
        Ok(())
    }
    
    #[test]
    fn test_avro_checksum_validation() -> SerializationResult<()> {
        let serializer = AvroSerializer::new()?;
        let original_data = b"Checksum test";
        
        let serialized = serializer.serialize(original_data)?;
        
        // データを破損させる
        let mut corrupted = serialized.clone();
        if let Some(last) = corrupted.last_mut() {
            *last = last.wrapping_add(1);
        }
        
        // デシリアライズは成功するが、チェックサムエラーが発生する可能性
        // （Avroの構造的完全性が保たれる場合）
        let result = serializer.deserialize(&corrupted);
        
        // 破損の程度によって結果が変わるため、エラーまたは異なるデータのいずれかを期待
        match result {
            Ok(data) => assert_ne!(original_data, data.as_slice()),
            Err(_) => {}, // エラーも期待される結果
        }
        
        Ok(())
    }
    
    #[test]
    fn test_avro_factory() -> SerializationResult<()> {
        let standard = AvroSerializerFactory::standard()?;
        let simple = AvroSerializerFactory::simple()?;
        
        assert_eq!(standard.format_name(), "avro");
        assert_eq!(simple.format_name(), "avro");
        
        let test_data = b"Factory test";
        
        let std_serialized = standard.serialize(test_data)?;
        let std_deserialized = standard.deserialize(&std_serialized)?;
        assert_eq!(test_data, std_deserialized.as_slice());
        
        let simple_serialized = simple.serialize(test_data)?;
        let simple_deserialized = simple.deserialize(&simple_serialized)?;
        assert_eq!(test_data, simple_deserialized.as_slice());
        
        Ok(())
    }
} 