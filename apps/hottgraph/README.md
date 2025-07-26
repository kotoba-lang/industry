# kotoba: HoTTと日本の哲学で記述するプロセス言語

[![Rust](https://github.com/gftdcojp/hottgraph/actions/workflows/rust.yml/badge.svg)](https://github.com/gftdcojp/hottgraph/actions/workflows/rust.yml)

`kotoba`は、**ホモトピー型理論（HoTT）**と**日本の哲学**を融合し、動的なプロセスを記述する新しいプログラミング言語です。
「言葉（kotoba）によって、事（koto）が起こる場（ba）が生まれる」という思想を核とします。

## 思想: `ba`と`en`の哲学

`kotoba`言語は、システムの振る舞いを、**`ba`（場）**と**`en`（縁）**の相互作用として捉えます。

-   **`ba` (場):** HoTTにおける**型/空間 (Type/Space)**。
-   **`en` (縁):** HoTTにおける**パス/射 (Path/Morphism)**。
-   **`ma` (間):** HoTTにおける**ユニット型/終対象 (Unit Type)**。

プログラミングは、`ku`(`空`)から`ba`を立ち上げ、`shiki`(`式`)で名を定め、`en`で繋ぐという、構成的なプロセスです。

## 設計思想とロードマップ

`kotoba`言語のより詳細な設計思想、キーワード体系、技術仕様、そして段階的な実装ロードマップについては、以下のドキュメントを参照してください。

-   [**DESIGN.md](./DESIGN.md)**

## プロジェクトの現在の状態

現在は、`kotoba`言語のコードをRustコードに変換する**トランスパイラ**の開発の初期段階にあります。

### 次のステップ

1.  **パーサーの再構築**: パーサーをアルファベットベースの新キーワード体系 (`ku`, `shiki`, etc.) に完全対応させます。
2.  **コンパイラの適合**: コンパイラを新キーワード体系に完全対応させます。

## 貢献

このプロジェクトへの貢献に興味がある方は、まず `DESIGN.md` をお読みいただき、IssueやPull Requestをお送りください。
