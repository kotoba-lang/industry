//! # CLI Configuration (Stub)
//!
//! CLI設定のプレースホルダー実装。

use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(dead_code)]
pub struct CliConfig {
    /// サーバーアドレス
    pub server_addr: String,
}

#[allow(dead_code)]
impl CliConfig {
    /// 新しい設定を作成
    pub fn new() -> Self {
        Self {
            server_addr: "127.0.0.1:9092".to_string(),
        }
    }
} 