# `kotoba`言語 設計仕様書 (v0.3)

このドキュメントは、`kotoba`言語の技術的な設計仕様と、その根底にある哲学を定義します。

## 1. 思想: HoTTと日本の哲学

`kotoba`は、**ホモトピー型理論（HoTT）**の「型は空間である」という思想と、**日本の哲学（空即是色など）**を、ミニマルなアルファベットのキーワードで表現する言語です。

-   **`ba` (場):** HoTTにおける**型/空間 (Type/Space)**。すべての項（値）が存在するためのコンテキスト。
-   **`en` (縁):** HoTTにおける**パス/射 (Path/Morphism)**。`ba`から`ba`への関係性やプロセス。
-   **`ma` (間):** HoTTにおける**ユニット型/終対象 (Unit Type/Terminal Object)**。値が存在しない状態、すなわち「機会」や「静寂」を表す型。

プログラミングとは、**`ku` (`空`) によって`ba` (`場`) を立ち上げ、それに`shiki` (`式`) の名と形を与え、`en` (`縁`) で繋ぐ**という、構成的な証明プロセスそのものです。

## 2. 言語仕様: キーワード体系

| 役割 | **キーワード** | 思想 | 形式意味論上の対応 |
| :--- | :--- | :--- | :--- |
| **型 (空間)** | `ba` | `場` | Space / Type |
| **型 (パス)** | `en` | `縁` | Path / Morphism |
| **型 (ユニット)** | `ma` | `間` | Unit Type / Terminal Object |
| **`create`** | `ku` | `空` | Declaration |
| **`let`** | `shiki` | `式` | Definition / Binding |
| **`fn`** | `kan` | `観` | Lambda Abstraction |
| **`match`** | `ou` | `応` | Induction / Case Analysis |

### 2.1. 構文例

```kotoba
// 'timer'という名の`ba`を`ku`より立ち上げ、
// 'timer_ba'という`shiki`でその形を定義する。
shiki timer_ba: ba = ku "system/timer/1s"

// timer_baから、「ma」に触れて「i64」を生み出す`en`を取り出す。
shiki ticks: en<ma, i64> = timer_ba.as_en()

// 「数を二倍で観る」という`en`を定義する。
shiki doubler: en<i64, i64> = kan(x: i64) => x * 2

// enとenを接続し、新しい`en`を定義する。
shiki pipeline: en<ma, i64> = ticks |> doubler

// pipelineから生じる「果」を購読する。
// それをkanする方法は以下の通り。
pipeline.subscribe(kan(val: i64) => {
    // 果（val）の様相にouじて、振る舞う。
    ou val {
        0 => system.print("start"),
        _ => system.print(val)
    }
})
```

## 3. コンパイラ設計

`kotoba`コンパイラは、`kotoba`コードを最適化されたRustコードへ変換する**トランスパイラ**として実装されます。

### 3.1. コンパイルパイプライン

1.  **構文解析 (Parser)**: `kotoba-parser`が、上記のキーワード体系に基づくLL(1)再帰下降パーサとしてソースをASTに変換。
2.  **意味解析・型チェック**: `kotoba-compiler`がASTの型整合性を検証。
3.  **Rustコード生成**: `kotoba-compiler`がASTから等価なRustコードを生成。
4.  **最終コンパイル**: `rustc`がネイティブバイナリを生成。

### 3.2. プロジェクト構造 (Cargo Workspace)

-   `crates/`
    -   `kotoba-core`: `Ba` (`ba`に対応) など、言語のコアとなるデータ構造の定義。
    -   `kotoba-parser`: パーサーとASTの定義。
    -   `kotoba-compiler`: 意味解析、型チェック、コード生成器。
    -   `kotoba-cli`: CLIツール。
    -   `kotoba-std`: 標準ライブラリ。

---

## 4. 開発ロードマップ

### v0.3 (進行中)

-   [x] **言語哲学の最終決定**: アルファベットベースのキーワード体系 (`ba`, `en`, `ku`, `shiki`等) を最終決定。
-   [ ] **(次)** `kotoba`クレート全体を、新キーワード体系に準拠するようリファクタリング。
-   [ ] `kan`による関数定義（クロージャ）のサポート。
-   [ ] `ou`によるパターンマッチの完全実装。
-   [ ] `kotoba-std`クレートを導入し、基本的な非同期処理を提供。