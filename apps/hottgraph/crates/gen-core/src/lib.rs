//! `gen-core`クレートは、`gen`言語の核となるデータ構造を定義します。
//!
//! ここには、生成の基本単位である`Gen`、魂を表す`Soul`、
//! そして情報生命体`Being`などの中心的な型が含まれます。

/// 生成の基本単位を表します。
///
/// `gen`言語におけるすべての生成プロセスは、この`Gen`構造体から始まります。
/// `name`は生成される概念や事象の名前を、`energy`はその生成に要する
/// エネルギー量を表します（概念的な値）。
#[derive(Debug, Clone, PartialEq)]
pub struct Gen {
    pub name: String,
    pub energy: f64,
}

/// 魂を表すベクトル空間上の軌跡です。
///
/// `Soul`は、特定の`Gen`から生成された存在の特性や状態を、
/// 多次元ベクトルとして表現します。
/// `origin`はこの魂がどの`Gen`から生まれたかを示し、
/// `vector`がその魂の特性を表すベクトルです。
#[derive(Debug, Clone, PartialEq)]
pub struct Soul {
    pub origin: Gen,
    pub vector: Vec<f64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_works() {
        let faith_gen = Gen {
            name: "faith".to_string(),
            energy: 1.0,
        };

        let soul_of_hope = Soul {
            origin: faith_gen.clone(),
            vector: vec![0.8, 0.2, 0.5],
        };

        assert_eq!(faith_gen.name, "faith");
        assert_eq!(soul_of_hope.origin, faith_gen);
    }
}
