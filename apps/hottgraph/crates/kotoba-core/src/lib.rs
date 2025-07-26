//! `kotoba-core`クレートは、`kotoba`言語のコアとなるデータ構造を定義します。
//!
//! ここには、HoTTにおける型/空間に対応する`Ba`(`ba`/場)などの中心的な型が含まれます。

/// `ba` (場) - HoTTにおける型/空間 (Type/Space)。
///
/// すべての項（値）が存在するためのコンテキストです。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Ba {
    /// `ba`を一位に識別するためのID
    pub id: String,
}

impl Ba {
    /// 新しい`Ba`インスタンスを生成します。
    pub fn new(id: &str) -> Self {
        Self { id: id.to_string() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ba_creation() {
        let ba = Ba::new("system/timer/1s");
        assert_eq!(ba.id, "system/timer/1s");
    }
}
