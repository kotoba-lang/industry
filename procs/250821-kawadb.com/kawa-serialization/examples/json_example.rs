//! # JSON Serializer Example
//! 
//! kawa-serializationのJSONシリアライザーの使用例

use kawa_serialization::{
    MessageSerializer, SerializationFormat, SerializerFactory, SerializationPurpose,
    json::{JsonSerializer, JsonSerializerFactory},
};
use serde_json::Value;
use std::collections::HashMap;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // ログ初期化
    tracing_subscriber::fmt::init();
    
    println!("🚀 Kawa Serialization JSON Example");
    println!("================================");
    
    // 基本的な使用例
    basic_usage_example()?;
    
    // ファクトリーパターンの使用例
    factory_pattern_example()?;
    
    // カスタムメタデータの使用例
    custom_metadata_example()?;
    
    // プリティプリントの例
    pretty_print_example()?;
    
    // パフォーマンステスト
    performance_test()?;
    
    // フォーマット比較
    format_comparison_example()?;
    
    Ok(())
}

/// 基本的な使用例
fn basic_usage_example() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n📝 Basic Usage Example");
    println!("----------------------");
    
    // JSONシリアライザーを作成
    let serializer = JsonSerializer::new();
    
    // テストデータ
    let original_data = b"Hello, Kawa Serialization!";
    println!("Original data: {}", String::from_utf8_lossy(original_data));
    
    // シリアライズ
    let serialized = serializer.serialize(original_data)?;
    println!("Serialized size: {} bytes", serialized.len());
    println!("Serialized data: {}", String::from_utf8_lossy(&serialized));
    
    // デシリアライズ
    let deserialized = serializer.deserialize(&serialized)?;
    println!("Deserialized: {}", String::from_utf8_lossy(&deserialized));
    
    // 検証
    assert_eq!(original_data, deserialized.as_slice());
    println!("✅ Serialization/Deserialization successful!");
    
    Ok(())
}

/// ファクトリーパターンの使用例
fn factory_pattern_example() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n🏭 Factory Pattern Example");
    println!("--------------------------");
    
    // 利用可能なフォーマット確認
    let formats = SerializerFactory::available_formats();
    println!("Available formats: {:?}", formats);
    
    // デフォルトシリアライザー
    let default_serializer = SerializerFactory::create_default()?;
    println!("Default format: {}", default_serializer.format_name());
    
    // 用途別シリアライザー
    let debug_serializer = SerializerFactory::create_for_purpose(SerializationPurpose::Debugging)?;
    let compat_serializer = SerializerFactory::create_for_purpose(SerializationPurpose::Compatibility)?;
    
    println!("Debug format: {}", debug_serializer.format_name());
    println!("Compatibility format: {}", compat_serializer.format_name());
    
    // フォーマット名から作成
    let json_serializer = SerializationFormat::from_name("json")?;
    println!("JSON format: {}", json_serializer.format_name());
    
    // 各シリアライザーでテスト
    let test_data = b"Factory test data";
    
    for (name, serializer) in [
        ("default", &default_serializer),
        ("debug", &debug_serializer),
        ("compatibility", &compat_serializer),
        ("json", &json_serializer),
    ] {
        let serialized = serializer.serialize(test_data)?;
        let deserialized = serializer.deserialize(&serialized)?;
        assert_eq!(test_data, deserialized.as_slice());
        println!("✅ {} serializer test passed", name);
    }
    
    Ok(())
}

/// カスタムメタデータの使用例
fn custom_metadata_example() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n📋 Custom Metadata Example");
    println!("---------------------------");
    
    // メタデータを準備
    let mut metadata = HashMap::new();
    metadata.insert("application".to_string(), Value::String("kawa-example".to_string()));
    metadata.insert("version".to_string(), Value::Number(1.into()));
    metadata.insert("author".to_string(), Value::String("Kawa Team".to_string()));
    
    // メタデータ付きシリアライザー
    let serializer = JsonSerializerFactory::with_metadata(metadata);
    
    // テストデータ
    let test_data = b"Data with custom metadata";
    println!("Test data: {}", String::from_utf8_lossy(test_data));
    
    // シリアライズ
    let serialized = serializer.serialize(test_data)?;
    let serialized_str = String::from_utf8_lossy(&serialized);
    println!("Serialized with metadata:\n{}", serialized_str);
    
    // メタデータの内容を確認
    let envelope: kawa_serialization::json::JsonEnvelope = serde_json::from_slice(&serialized)?;
    println!("Metadata:");
    for (key, value) in &envelope.metadata {
        println!("  {}: {}", key, value);
    }
    println!("Timestamp: {}", envelope.timestamp);
    println!("Size: {}", envelope.size);
    println!("Checksum: {:?}", envelope.checksum);
    
    // デシリアライズ
    let deserialized = serializer.deserialize(&serialized)?;
    assert_eq!(test_data, deserialized.as_slice());
    println!("✅ Metadata serialization test passed");
    
    Ok(())
}

/// プリティプリントの例
fn pretty_print_example() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n🎨 Pretty Print Example");
    println!("-----------------------");
    
    // 通常のシリアライザー
    let normal_serializer = JsonSerializer::new();
    // プリティプリントシリアライザー
    let pretty_serializer = JsonSerializer::new().with_pretty();
    
    let test_data = b"Pretty print test data";
    
    // 通常のシリアライズ
    let normal_serialized = normal_serializer.serialize(test_data)?;
    println!("Normal JSON (compact):");
    println!("{}", String::from_utf8_lossy(&normal_serialized));
    
    // プリティプリント
    let pretty_serialized = pretty_serializer.serialize(test_data)?;
    println!("\nPretty JSON (formatted):");
    println!("{}", String::from_utf8_lossy(&pretty_serialized));
    
    // 両方ともデシリアライズ可能
    let normal_deserialized = normal_serializer.deserialize(&normal_serialized)?;
    let pretty_deserialized = pretty_serializer.deserialize(&pretty_serialized)?;
    
    assert_eq!(test_data, normal_deserialized.as_slice());
    assert_eq!(test_data, pretty_deserialized.as_slice());
    
    println!("✅ Pretty print test passed");
    
    Ok(())
}

/// パフォーマンステスト
fn performance_test() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n⚡ Performance Test");
    println!("------------------");
    
    let serializer = JsonSerializer::new();
    let test_sizes = [100, 1_000, 10_000, 100_000];
    
    for size in test_sizes {
        let test_data: Vec<u8> = (0..size).map(|i| (i % 256) as u8).collect();
        
        // シリアライゼーション性能測定
        let start = std::time::Instant::now();
        let serialized = serializer.serialize(&test_data)?;
        let serialize_duration = start.elapsed();
        
        // デシリアライゼーション性能測定
        let start = std::time::Instant::now();
        let deserialized = serializer.deserialize(&serialized)?;
        let deserialize_duration = start.elapsed();
        
        // 検証
        assert_eq!(test_data, deserialized);
        
        // 圧縮率計算
        let compression_ratio = serialized.len() as f32 / test_data.len() as f32;
        
        println!("Size: {} bytes", size);
        println!("  Serialize: {:?}", serialize_duration);
        println!("  Deserialize: {:?}", deserialize_duration);
        println!("  Compressed size: {} bytes (ratio: {:.2})", serialized.len(), compression_ratio);
        println!("  Throughput: {:.2} MB/s", 
                 (size as f64 / 1_000_000.0) / serialize_duration.as_secs_f64());
    }
    
    println!("✅ Performance test completed");
    
    Ok(())
}

/// フォーマット比較の例
fn format_comparison_example() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n📊 Format Comparison");
    println!("-------------------");
    
    let comparisons = SerializerFactory::compare_formats();
    
    for comparison in comparisons {
        println!("{}", comparison);
    }
    
    println!("✅ Format comparison completed");
    
    Ok(())
} 