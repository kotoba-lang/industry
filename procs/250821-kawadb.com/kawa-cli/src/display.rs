//! # CLI Display Utilities (Stub)
//!
//! CLI表示ユーティリティのプレースホルダー実装。

/// 表示フォーマッタ
#[derive(Debug)]
#[allow(dead_code)]
pub struct DisplayFormatter {
    /// テーブルのスタイル
    table_style: String,
}

#[allow(dead_code)]
impl DisplayFormatter {
    /// 新しいフォーマッターを作成
    pub fn new() -> Self {
        Self {
            table_style: "rounded".to_string(),
        }
    }
} 