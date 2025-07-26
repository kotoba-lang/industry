//! `gen-core`クレートは、`gen`言語のコアとなるデータ構造を定義します。
//!
//! ここには、プロセスグラフの頂点を表す`Node`などの中心的な型が含まれます。

/// プロセスグラフのノード（頂点）を表します。
///
/// `Node`は、`gen`言語における計算や状態の基本単位です。
/// `id`はグラフ内でノードを一位に識別するためのものです。
#[derive(Debug, Clone, PartialEq)]
pub struct Node {
    pub id: String,
}

impl Node {
    /// 新しい`Node`インスタンスを生成します。
    pub fn new(id: &str) -> Self {
        Self { id: id.to_string() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_node_creation() {
        let node = Node::new("system/timer/1s");
        assert_eq!(node.id, "system/timer/1s");
    }
}
