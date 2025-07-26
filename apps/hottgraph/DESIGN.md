# `kotoba`言語 設計仕様書 (v0.4)

このドキュメントは、`kotoba`言語の技術的な設計仕様と、その根底にある哲学を定義します。

## 1. 思想: HoTTと日本の哲学

`kotoba`は、**ホモトピー型理論（HoTT）**の「型は空間である」という思想と、**日本の哲学（空即是色など）**を、ミニマルなアルファベットのキーワードで表現する言語です。

プログラミングとは、**`ku` (`空`) によって`ba` (`場`) を立ち上げ、それに`shiki` (`式`) の名と形を与え、`en` (`縁`) で繋ぐ**という、構成的な証明プロセスそのものです。

### 1.1. 目指すもの: チューリング完全性と形式手法

`kotoba`は単なる表現言語に留まりません。**チューリング完全な計算能力**と、HoTTに基づく**形式手法の厳密性**を両立することを目指します。これにより、開発者は「魂や関係性」といった抽象的な概念を記述しつつ、そのプログラムが数学的に正しいことを証明できる体系を構築します。

-   **チューリング完全性**: 再帰 (`meguri`)、条件分岐 (`wakare`, `ou`)、状態の保持 (`shiki`) を通じて、あらゆる計算を可能にします。
-   **形式手法**: 依存型 (`nagare`, `musubi`) や帰納的定義 (`umare`) を導入し、プログラムがそのまま証明となるような Curry-Howard 同型対応を体現します。

## 2. 言語仕様: 魂を記述するキーワード

| 役割 (Role) | キーワード (Keyword) | 思想 (Philosophy) | 形式意味論上の対応 (Formal Semantics) |
| :--- | :--- | :--- | :--- |
| **空間・型 (Space/Type)** | `ba` | `場` | Type |
| **パス・射・等価 (Path/Morphism/Equality)** | `en` | `縁` | Path / Identity Type |
| **ユニット型 (Unit Type)** | `ma` | `間` | Unit Type / Terminal Object |
| **宇宙 (Universe)** | `sora` | `宙` | Universe |
| **宣言 (Declaration)** | `ku` | `空` | Declaration / Introduction |
| **束縛 (Binding)** | `shiki` | `式` | let / Definition |
| **関数 (Function)** | `kan` | `観` | Lambda Abstraction (non-dependent) |
| **依存関数 (Dependent Function)** | `nagare`| `流` | Π-type / Dependent Function |
| **依存ペア (Dependent Pair)** | `musubi`| `結` | Σ-type / Dependent Pair |
| **条件分岐 (Conditional)** | `wakare`| `分` | if / then / else |
| **パターン照合 (Pattern Match)** | `ou` | `応` | Induction / Case Analysis |
| **帰納型定義 (Inductive Definition)** | `umare` | `生` | Inductive Type Definition |
| **再帰・不動点 (Recursion/Fixpoint)** | `meguri`| `巡` | Fixpoint Combinator / Recursion |
| **高次帰納型 (Higher Inductive Type)** | `kami` | `神` | Higher Inductive Type (HIT) |

### 2.1. 構文例

#### 基本的なパイプライン
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
```

#### 帰納・再帰・分岐 (`umare`, `meguri`, `ou`)
```kotoba
// 「自然数」という`ba`を`umare`で帰納的に定義する。
shiki Nat: ba = umare {
    zero: Nat,
    succ(prev: Nat): Nat
}

// `Nat`上の加算を`meguri` (再帰) を使って定義する。
shiki add: en<Nat, en<Nat, Nat>> =
    meguri self(a: Nat, b: Nat): Nat =>
        ou a {
            zero => b,
            succ(prev_a) => Nat.succ(self(prev_a, b))
        }

// `wakare` (もし) を使った分岐
shiki is_zero: en<Nat, bool> = kan(n: Nat) =>
    wakare n ou {
        zero => true,
        _ => false
    }
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

### v0.3 (完了)

-   [x] **言語哲学の最終決定**: アルファベットベースのキーワード体系 (`ba`, `en`, `ku`, `shiki`等) を最終決定。
-   [x] `kan`による関数定義（クロージャ）のサポート。
-   [x] `ou`による基本的なパターンマッチ。
-   [x] `kotoba-std`クレートの導入準備。

### v0.4 (計画中)

-   [ ] **チューリング完全性のための基礎実装**
    -   [ ] `umare`: 帰納データ型（代数的データ型）の定義機能。
    -   [ ] `ou`: 完全なパターンマッチ（ネスト、ガード含む）。
    -   [ ] `meguri`: `kan`内での自己再帰呼び出しのサポート。
    -   [ ] `wakare`: `if/then/else` 形式の条件分岐。
-   [ ] **パーサの拡張**: 上記キーワードに対応するASTノードの定義と構文解析ルールの実装。
-   [ ] **コンパイラの拡張**: 新しいASTノードに対する型チェックとRustコード生成ロジックの実装。

### v0.5 (展望)

-   [ ] **形式手法のための依存型**
    -   [ ] `nagare` (Π-type), `musubi` (Σ-type) の実装。
    -   [ ] 型が項に依存できる、完全な依存型システムへの拡張。
-   [ ] **高度な型機能**
    -   [ ] `sora` (Universe): 型の階層を扱うための宇宙。
    -   [ ] `kami` (Higher Inductive Types): HoTTの真価を発揮する高次帰納型の導入。
-   [ ] **証明支援**: 対話的な証明記述や検証をサポートする機能。