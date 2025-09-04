//! # Kawa CLI
//!
//! Kawaブローカーの管理用コマンドラインインターフェース。
//! ブローカーの起動、設定、モニタリング、管理機能を提供。

use anyhow::Result;

#[tokio::main]
async fn main() -> Result<()> {
    // 実際の処理はlib.rsのmain関数に委譲
    kawa_cli::main().await
} 