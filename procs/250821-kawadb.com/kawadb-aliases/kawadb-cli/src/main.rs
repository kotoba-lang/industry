//! # kawadb-cli
//!
//! Command-line interface for Kawa message broker.
//! 
//! This is an alias for `kawa-cli` to provide alternative naming.
//! All functionality is delegated to the original `kawa-cli` crate.

use anyhow::Result;

#[tokio::main]
async fn main() -> Result<()> {
    // 実際の処理は kawa-cli に委譲
    kawa_cli::main().await
} 