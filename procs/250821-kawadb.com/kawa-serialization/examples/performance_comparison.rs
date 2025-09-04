//! # Performance Comparison Example
//! 
//! 全シリアライゼーション形式のパフォーマンス比較

use kawa_serialization::{
    MessageSerializer, SerializationFormat, SerializerFactory, SerializationPurpose,
};
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();
    
    println!("🚀 Kawa Serialization Performance Comparison");
    println!("============================================");
    
    // 利用可能な形式を確認
    let available_formats = SerializerFactory::available_formats();
    println!("Available formats: {:?}\n", available_formats);
    
    // フォーマット比較
    let comparisons = SerializerFactory::compare_formats();
    println!("📊 Format Specifications:");
    for comparison in &comparisons {
        println!("  {}", comparison);
    }
    println!();
    
    // 各種サイズでパフォーマンステスト
    let test_sizes = [1_000, 10_000, 100_000, 1_000_000];
    
    for &size in &test_sizes {
        println!("🧪 Testing with {} bytes", size);
        println!("{}", "-".repeat(40));
        
        let test_data: Vec<u8> = (0..size).map(|i| (i % 256) as u8).collect();
        
        for format_name in &available_formats {
            test_format(format_name, &test_data)?;
        }
        
        println!();
    }
    
    // 用途別推奨テスト
    println!("🎯 Purpose-based Recommendations:");
    println!("{}", "-".repeat(40));
    
    let purposes = [
        ("Debugging", SerializationPurpose::Debugging),
        ("Performance", SerializationPurpose::Performance),
        ("Compatibility", SerializationPurpose::Compatibility),
        ("Storage", SerializationPurpose::Storage),
    ];
    
    for (purpose_name, purpose) in purposes {
        if let Ok(serializer) = SerializerFactory::create_for_purpose(purpose) {
            println!("{}: {} (efficiency: {:.1}%, compression: {:.1}%)", 
                     purpose_name, 
                     serializer.format_name(),
                     serializer.efficiency() * 100.0,
                     serializer.compression_ratio() * 100.0);
        }
    }
    
    println!("\n✅ Performance comparison completed");
    Ok(())
}

fn test_format(format_name: &str, test_data: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    let serializer = SerializationFormat::from_name(format_name)?;
    
    // シリアライゼーション性能測定
    let start = Instant::now();
    let serialized = serializer.serialize(test_data)?;
    let serialize_duration = start.elapsed();
    
    // デシリアライゼーション性能測定
    let start = Instant::now();
    let deserialized = serializer.deserialize(&serialized)?;
    let deserialize_duration = start.elapsed();
    
    // 検証
    assert_eq!(test_data, deserialized.as_slice());
    
    // 統計計算
    let compression_ratio = serialized.len() as f32 / test_data.len() as f32;
    let total_duration = serialize_duration + deserialize_duration;
    let throughput_mbps = (test_data.len() as f64 / 1_000_000.0) / total_duration.as_secs_f64();
    
    println!("  {}: {} bytes -> {} bytes ({:.2}x) | Ser: {:?} | Des: {:?} | Total: {:?} | {:.2} MB/s",
             format_name,
             test_data.len(),
             serialized.len(),
             compression_ratio,
             serialize_duration,
             deserialize_duration,
             total_duration,
             throughput_mbps);
    
    Ok(())
} 