//! # Kawa Serialization
//! 
//! 高性能メッセージシリアライゼーションライブラリ
//! JSON、Avro、Protocol Buffers対応
//! 
//! ## 基本的な使用例
//! 
//! ```rust
//! use kawa_serialization::{MessageSerializer, SerializationFormat};
//! 
//! fn main() -> Result<(), Box<dyn std::error::Error>> {
//!     // JSON形式でシリアライズ
//!     let serializer = SerializationFormat::from_name("json")?;
//!     let data = b"Hello, Kawa!";
//!     
//!     let serialized = serializer.serialize(data)?;
//!     let deserialized = serializer.deserialize(&serialized)?;
//!     
//!     assert_eq!(data, deserialized.as_slice());
//!     Ok(())
//! }
//! ```

pub mod error;

#[cfg(feature = "json")]
pub mod json;

#[cfg(feature = "avro")]
pub mod avro;

#[cfg(feature = "protobuf")]
pub mod protobuf;

#[cfg(feature = "graphson")]
pub mod graphson;

// Re-exports
pub use error::{SerializationError, SerializationResult, ErrorSeverity};

#[cfg(feature = "json")]
pub use json::{JsonSerializer, JsonSerializerFactory, JsonEnvelope};

#[cfg(feature = "avro")]
pub use avro::{AvroSerializer, AvroSerializerFactory};

#[cfg(feature = "protobuf")]
pub use protobuf::{ProtobufSerializer, ProtobufSerializerFactory};

use std::fmt;

/// メッセージシリアライザートレイト
/// 
/// 異なるシリアライゼーション形式の統一インターフェース
pub trait MessageSerializer: Send + Sync + fmt::Debug {
    /// データをシリアライズ
    /// 
    /// # Arguments
    /// * `data` - 元のバイナリデータ
    /// 
    /// # Returns
    /// * `SerializationResult<Vec<u8>>` - シリアライズされたデータ
    fn serialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>>;
    
    /// データをデシリアライズ
    /// 
    /// # Arguments
    /// * `data` - シリアライズされたデータ
    /// 
    /// # Returns
    /// * `SerializationResult<Vec<u8>>` - 元のバイナリデータ
    fn deserialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>>;
    
    /// フォーマット名を取得
    fn format_name(&self) -> &'static str;
    
    /// スキーマ情報（オプション）
    fn schema(&self) -> Option<&str> { 
        None 
    }
    
    /// シリアライゼーション効率を取得（0.0-1.0）
    fn efficiency(&self) -> f32 { 
        0.5 // デフォルトは中程度
    }
    
    /// 圧縮率を取得（0.0-1.0）
    fn compression_ratio(&self) -> f32 { 
        0.8 // デフォルトは軽い圧縮
    }
}

/// サポートされているシリアライゼーション形式
#[derive(Debug, Clone)]
pub enum SerializationFormat {
    #[cfg(feature = "json")]
    Json(json::JsonSerializer),
    
    #[cfg(feature = "avro")]
    Avro(avro::AvroSerializer),
    
    #[cfg(feature = "protobuf")]
    Protobuf(protobuf::ProtobufSerializer),
}

impl SerializationFormat {
    /// フォーマット名から作成
    /// 
    /// # Arguments
    /// * `name` - フォーマット名 ("json", "avro", "protobuf")
    /// 
    /// # Returns
    /// * `SerializationResult<Self>` - シリアライザーインスタンス
    pub fn from_name(name: &str) -> SerializationResult<Self> {
        match name.to_lowercase().as_str() {
            #[cfg(feature = "json")]
            "json" => Ok(Self::Json(json::JsonSerializer::new())),
            
            #[cfg(feature = "avro")]
            "avro" => Ok(Self::Avro(avro::AvroSerializer::new()?)),
            
            #[cfg(feature = "protobuf")]
            "protobuf" | "proto" => Ok(Self::Protobuf(protobuf::ProtobufSerializer::new())),
            
            _ => Err(SerializationError::UnsupportedFormat(name.to_string())),
        }
    }
    
    /// フォーマット名から作成（設定付き）
    pub fn from_name_with_config(name: &str, config: &str) -> SerializationResult<Self> {
        match name.to_lowercase().as_str() {
            #[cfg(feature = "json")]
            "json" => {
                let mut serializer = json::JsonSerializer::new();
                
                // 設定を解析
                if config.contains("pretty") {
                    serializer = serializer.with_pretty();
                }
                if config.contains("simple") {
                    serializer = serializer.with_metadata(false);
                }
                
                Ok(Self::Json(serializer))
            }
            
            _ => Self::from_name(name),
        }
    }
}

impl MessageSerializer for SerializationFormat {
    fn serialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        match self {
            #[cfg(feature = "json")]
            Self::Json(s) => s.serialize(data),
            
            #[cfg(feature = "avro")]
            Self::Avro(s) => s.serialize(data),
            
            #[cfg(feature = "protobuf")]
            Self::Protobuf(s) => s.serialize(data),
        }
    }
    
    fn deserialize(&self, data: &[u8]) -> SerializationResult<Vec<u8>> {
        match self {
            #[cfg(feature = "json")]
            Self::Json(s) => s.deserialize(data),
            
            #[cfg(feature = "avro")]
            Self::Avro(s) => s.deserialize(data),
            
            #[cfg(feature = "protobuf")]
            Self::Protobuf(s) => s.deserialize(data),
        }
    }
    
    fn format_name(&self) -> &'static str {
        match self {
            #[cfg(feature = "json")]
            Self::Json(s) => s.format_name(),
            
            #[cfg(feature = "avro")]
            Self::Avro(s) => s.format_name(),
            
            #[cfg(feature = "protobuf")]
            Self::Protobuf(s) => s.format_name(),
        }
    }
    
    fn schema(&self) -> Option<&str> {
        match self {
            #[cfg(feature = "json")]
            Self::Json(s) => s.schema(),
            
            #[cfg(feature = "avro")]
            Self::Avro(s) => s.schema(),
            
            #[cfg(feature = "protobuf")]
            Self::Protobuf(s) => s.schema(),
        }
    }
    
    fn efficiency(&self) -> f32 {
        match self {
            #[cfg(feature = "json")]
            Self::Json(_) => 0.6, // JSON: 可読性は高いが効率は中程度
            
            #[cfg(feature = "avro")]
            Self::Avro(_) => 0.9, // Avro: 高効率
            
            #[cfg(feature = "protobuf")]
            Self::Protobuf(_) => 0.95, // ProtoBuf: 最高効率
        }
    }
    
    fn compression_ratio(&self) -> f32 {
        match self {
            #[cfg(feature = "json")]
            Self::Json(_) => 0.7, // JSONは圧縮率が低い
            
            #[cfg(feature = "avro")]
            Self::Avro(_) => 0.85, // Avroは良い圧縮率
            
            #[cfg(feature = "protobuf")]
            Self::Protobuf(_) => 0.9, // ProtoBufは最高の圧縮率
        }
    }
}

/// シリアライザーファクトリー
pub struct SerializerFactory;

impl SerializerFactory {
    /// 利用可能なフォーマット一覧
    pub fn available_formats() -> Vec<&'static str> {
        let mut formats = Vec::new();
        
        #[cfg(feature = "json")]
        formats.push("json");
        
        #[cfg(feature = "avro")]
        formats.push("avro");
        
        #[cfg(feature = "protobuf")]
        formats.push("protobuf");
        
        formats
    }
    
    /// デフォルトシリアライザーを作成
    pub fn create_default() -> SerializationResult<SerializationFormat> {
        // 優先順位: JSON -> Avro -> ProtoBuf
        #[cfg(feature = "json")]
        {
            Ok(SerializationFormat::Json(json::JsonSerializer::new()))
        }
        #[cfg(not(feature = "json"))]
        #[cfg(feature = "avro")]
        {
            SerializationFormat::from_name("avro")
        }
        #[cfg(not(any(feature = "json", feature = "avro")))]
        #[cfg(feature = "protobuf")]
        {
            SerializationFormat::from_name("protobuf")
        }
        #[cfg(not(any(feature = "json", feature = "avro", feature = "protobuf")))]
        {
            Err(SerializationError::NoFormatAvailable)
        }
    }
    
    /// 用途別推奨シリアライザーを作成
    pub fn create_for_purpose(purpose: SerializationPurpose) -> SerializationResult<SerializationFormat> {
        match purpose {
            SerializationPurpose::Debugging => {
                #[cfg(feature = "json")]
                {
                    Ok(SerializationFormat::Json(json::JsonSerializer::new().with_pretty()))
                }
                #[cfg(not(feature = "json"))]
                {
                    Self::create_default()
                }
            }
            
            SerializationPurpose::Performance => {
                #[cfg(feature = "protobuf")]
                {
                    SerializationFormat::from_name("protobuf")
                }
                #[cfg(not(feature = "protobuf"))]
                #[cfg(feature = "avro")]
                {
                    SerializationFormat::from_name("avro")
                }
                #[cfg(not(any(feature = "protobuf", feature = "avro")))]
                {
                    Self::create_default()
                }
            }
            
            SerializationPurpose::Compatibility => {
                #[cfg(feature = "json")]
                {
                    Ok(SerializationFormat::Json(json::JsonSerializer::new()))
                }
                #[cfg(not(feature = "json"))]
                {
                    Self::create_default()
                }
            }
            
            SerializationPurpose::Storage => {
                #[cfg(feature = "avro")]
                {
                    SerializationFormat::from_name("avro")
                }
                #[cfg(not(feature = "avro"))]
                #[cfg(feature = "protobuf")]
                {
                    SerializationFormat::from_name("protobuf")
                }
                #[cfg(not(any(feature = "avro", feature = "protobuf")))]
                {
                    Self::create_default()
                }
            }
        }
    }
    
    /// 全フォーマットを比較
    pub fn compare_formats() -> Vec<FormatComparison> {
        let mut comparisons = Vec::new();
        
        for format_name in Self::available_formats() {
            if let Ok(format) = SerializationFormat::from_name(format_name) {
                comparisons.push(FormatComparison {
                    name: format.format_name().to_string(),
                    efficiency: format.efficiency(),
                    compression: format.compression_ratio(),
                    schema_support: format.schema().is_some(),
                });
            }
        }
        
        comparisons
    }
}

/// シリアライゼーションの用途
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SerializationPurpose {
    /// デバッグ用（可読性重視）
    Debugging,
    /// 性能重視
    Performance,
    /// 互換性重視
    Compatibility,
    /// ストレージ効率重視
    Storage,
}

/// フォーマット比較情報
#[derive(Debug, Clone)]
pub struct FormatComparison {
    pub name: String,
    pub efficiency: f32,
    pub compression: f32,
    pub schema_support: bool,
}

impl fmt::Display for FormatComparison {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}: efficiency={:.1}%, compression={:.1}%, schema={}",
            self.name,
            self.efficiency * 100.0,
            self.compression * 100.0,
            if self.schema_support { "✓" } else { "✗" }
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use serde_json::Value;

    #[test]
    fn test_graphson_vertex_serialization() {
        let mut data = HashMap::new();
        data.insert("name".to_string(), Value::String("marko".to_string()));
        data.insert("age".to_string(), Value::Number(29.into()));

        let result = graphson::GraphSONv3Serializer::serialize_to_vertex("person1", "person", &data);
        assert!(result.is_ok());

        let json_str = String::from_utf8(result.unwrap()).unwrap();
        let v: Value = serde_json::from_str(&json_str).unwrap();

        assert_eq!(v["@type"], "g:Vertex");
        assert_eq!(v["@value"]["id"]["@type"], "g:String");
        assert_eq!(v["@value"]["id"]["@value"], "person1");
        assert_eq!(v["@value"]["label"], "person");
        
        let properties = &v["@value"]["properties"];
        assert_eq!(properties["name"][0]["@type"], "g:VertexProperty");
        assert_eq!(properties["name"][0]["@value"]["value"], "marko");
        assert_eq!(properties["name"][0]["@value"]["label"], "name");
        
        assert_eq!(properties["age"][0]["@value"]["value"], 29);
    }
} 