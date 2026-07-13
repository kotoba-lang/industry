# ADR-2607131400: Ghost Hacker cljc 移行スコーピング — Svelte/Go は「古い」ではない、段階移行を提案

**Status**: proposed
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki
**Related**: `manifest/README.md` の runtime 優先順位（kotoba wasm > clojurewasm > ClojureScript > nbb > 降格: jvm/bb）、skill `kotoba-uiux`

## Context

`com-junkawasaki/ghosthacker`（Ghost Hacker、既存IP、本セッションのmanga量産作業とは別スコープ）の技術構成を調査した結果:

- `apps/server/`: **Go 1.25**（最新安定版）。ConnectRPC（`proto/storyboard.proto` + 生成済み `storyboardpbconnect`）+ **Dapr Actors/Workflows/Activities**（`internal/dapr/actors.go` / `workflows.go` / `activities.go` —実際に使われている、名前だけの依存ではない）。`cuelang` は go.mod に依存宣言があるが `.cue` ファイルは repo 内にゼロ（vestigial/未使用の可能性）。
- `apps/web/`: **Svelte 5.0 + SvelteKit 2.0**（最新）。ConnectRPC-web クライアント + **Lexical**（リッチテキストエディタ）+ **Skeleton UI** + **Melt UI**。
- `apps/image-gen/`: Python 3ファイル、画像生成サービス。
- `260122-presentation/`: Svelte/Vite プレゼンテーションアプリ。
- `260123-jump/`: `.edn` 142ファイル——ストーリー原稿・キャラ設定等の**純データ**（実行コードではない）。
- リポジトリ全体で `.cljc`/`.clj`/`.cljs` はゼロ、`deps.edn`/`shadow-cljs.edn` も無し。DataScript/Datomic も宣言・使用ともにゼロ。

オーナー提案（2026-07-13）: 「svelte, go は古いので prune、typescript, python は cljc に移行して、py, ts は除去」。

## 検証結果 — 前提の訂正

**Svelte 5 と Go 1.25 は「古い」技術ではない**（`package.json`/`go.mod` で確認済み、いずれも当時点の最新安定版）。「古いので prune」という前提は技術的に成立しない。プルーンする場合の正しい理由は「このモノレポの runtime 優先順位（kotoba wasm > clojurewasm > cljs > nbb）に統一したい」という**アーキテクチャ方針**であるべきで、「陳腐化したから捨てる」ではない。

## 決定 — 段階移行、一括削除はしない

CLAUDE.md 既存原則「既存の専用実装は後から書き直さない。移行する場合は対象を決めて ADR 化してから着手する」に従い、**全面一括削除は行わず、実現可能性が確認できた範囲から段階的に移行する**。

### 実現可能性の評価

| 対象 | 移行先候補 | 実現可能性 | 判断 |
|---|---|---|---|
| `apps/image-gen/`（Python 3ファイル） | cljc + `cloud-murakumo`（native ComfyUI /prompt+/history+/view protocol） | **高**。本セッションで `mangaka.comfy/render-via-murakumo` として実装・実運用済みの直接的な前例がある（gftdcojp/ai-gftd-mangaka#5 マージ済み）。 | **第1弾で移行**。 |
| `apps/server/` の Dapr Actors/Workflows | kotoba-lang/langgraph の StateGraph、または JVM 互換層 | **低〜不明**。Dapr の分散 actor/workflow セマンティクスに対応する cljc ネイティブの実装は現状このモノレポに存在しない。langgraph-clj の StateGraph は単一プロセス内のグラフ実行で、Dapr の分散実行・耐障害性・sidecar モデルとは設計が異なる。 | **移行しない/別ADRで再評価**。実装なしに削除すれば ConnectRPC 経由の storyboard 編集機能が失われる。 |
| `apps/server/` の ConnectRPC service | — | ConnectRPC の Clojure/cljc 実装（gRPC-Web 相当）はこのモノレポに前例なし。 | **移行しない**。 |
| `apps/web/` の Lexical リッチテキスト編集 | — | Lexical 相当の cljc/ClojureScript ネイティブ実装は存在しない。 | **移行しない**。 |
| `apps/web/` の Skeleton UI / Melt UI | `kotoba-ui`（shitsuke.hig + liquid-glass-ui、skill `kotoba-uiux`） | UI 層自体は理論上 `kotoba-ui` で代替可能だが、Lexical 依存が残る限り frontend 全体の cljc 化は成立しない。 | **単独では移行しない**（Lexical 代替が無い限り frontend 全体を崩さない）。 |
| `260122-presentation/`（Svelte プレゼン） | — | 用途未精査（プレゼンデッキ or プロダクト機能かの切り分けが必要）。 | **今回スコープ外**。要否は別途確認。 |
| `260123-jump/`（`.edn` 142ファイル） | — | すでに EDN。移行対象ではない（データはそのまま）。 | **対象外（現状維持）**。 |

### 今回のアクション

1. **`apps/image-gen/` の Python 3ファイルを cljc + `cloud-murakumo` 経由に移行する**（第1弾、実現可能性が確認済みのスコープのみ）。移行後、この3ファイルのみ `.py` を除去する。
2. **`apps/server`（Go 全体）と `apps/web`（Svelte 全体）は削除しない。** Dapr actor/workflow・ConnectRPC・Lexical の cljc 代替が実在しない状態で削除すれば、動いている storyboard editor（バックエンド全体・エディタ機能）が失われる。
3. Go/Svelte を将来的にどうしても cljc へ統一したい場合は、**Dapr actor/workflow の cljc 代替方針**と**Lexical 相当のリッチテキスト編集の代替方針**を別ADRで先に決定してから着手する（今回のADRのスコープ外）。
4. `apps/server` の `cuelang` 依存（`.cue` ファイル実体ゼロ）は移行と無関係に vestigial dependency の疑いがあるため、別途確認・報告する（本ADRの決定事項ではない、次アクションとして記録のみ）。

## Consequences

- Ghost Hacker の Go バックエンド・Svelte フロントエンドは現状維持——「動いているものを、代替手段のないまま壊す」を回避する。
- image-gen のみ、本セッションで確立した `cloud-murakumo` 連携パターンをそのまま再利用でき、低リスクで cljc 化できる。
- Dapr/ConnectRPC/Lexical の cljc 代替が無いという事実がこのADRで明文化されたことで、将来「なぜ全面移行しなかったか」を再調査する必要がなくなる。
