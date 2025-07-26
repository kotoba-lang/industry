//! `gen-core`クレートは、`gen`言語のコアとなるデータ構造を定義します。
//!
//! ここには、プロセスの根源となる「因」を表す`In`などの中心的な型が含まれます。

/// プロセスの根源となる「因」を表します。
///
/// `In`は、`gen`言語における計算や状態の基本単位です。
/// `id`は因を一位に識別するためのものです。
#[derive(Debug, Clone, PartialEq)]
pub struct In {
    pub id: String,
}

impl In {
    /// 新しい`In`インスタンスを生成します。
    pub fn new(id: &str) -> Self {
        Self {
            id: id.to_string(),
        }
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_in_creation() {
        let in_instance = In::new("system/timer/1s");
        assert_eq!(in_instance.id, "system/timer/1s");
    }
}
