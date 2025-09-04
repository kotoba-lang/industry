//! # Protocol Buffer Build Script
//! 
//! Protocol Buffersスキーマをコンパイルする

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // protobuf機能が有効な場合のみコンパイル
    if cfg!(feature = "protobuf") {
        prost_build::Config::new()
            .bytes(["."])
            .compile_protos(&["proto/message.proto"], &["proto/"])?;
        
        println!("cargo:rerun-if-changed=proto/message.proto");
    }
    
    Ok(())
} 