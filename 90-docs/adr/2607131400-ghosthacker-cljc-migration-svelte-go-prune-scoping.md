# ADR-2607131400: Ghost Hacker cljc 移行スコーピング — Svelte/Go は「古い」ではない、段階移行を提案

**Status**: proposed
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki
**Related**: `manifest/README.md` の runtime 優先順位（kotoba wasm > clojurewasm > ClojureScript > nbb > 降格: jvm/bb）、skill `kotoba-uiux`

## Context

`com-junkawasaki/ghosthacker`（Ghost Hacker、既存IP、本セッションのmanga量産作業とは別スコープ）の技術構成を調査した結果:

- `apps/server/`: **Go 1.25**（最新安定版）。ConnectRPC（`proto/storyboard.proto` + 生成済み `storyboardpbconnect`）+ **Dapr Actors/Workflows/Activities**（`internal/dapr/actors.go` / `workflows.go` / `activities.go` —実際に使われている、名前だけの依存ではない）。`cuelang` も go.mod に直接依存として宣言・実使用（`internal/schema/storyboard.cue.go` にGo文字列リテラルで埋め込んだスキーマを `internal/service/storyboard.go` がJSONバリデーションに使用、後日追跡調査で確認——初回調査時は standalone `.cue` ファイルの有無だけを見て vestigial 疑いと誤判定していた）。
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
4. ~~`apps/server` の `cuelang` 依存（`.cue` ファイル実体ゼロ）は vestigial dependency の疑い~~ →
   **訂正（追跡調査済み）**: `cuelang` は実際に使われている。`.cue` 拡張子のファイルが無いのは、
   CUEスキーマが `internal/schema/storyboard.cue.go` にGo文字列リテラルとして埋め込まれている
   ためで（standalone `.cue` ファイルではない、という単純な見落とし）、`internal/service/
   storyboard.go` の `validateAndLoad`（`cueCtx.CompileBytes` → `Unify` → `Validate(cue.Final())`）
   がストーリーボードJSONの実スキーマ検証に使っている。go.mod でも indirect マーク無しの直接依存。
   **vestigialではない、削除しない（`go mod tidy` 禁止）。** この follow-up 項目は解決済みでクローズ。

## Consequences

- Ghost Hacker の Go バックエンド・Svelte フロントエンドは現状維持——「動いているものを、代替手段のないまま壊す」を回避する。
- image-gen のみ、本セッションで確立した `cloud-murakumo` 連携パターンをそのまま再利用でき、低リスクで cljc 化できる。
- Dapr/ConnectRPC/Lexical の cljc 代替が無いという事実がこのADRで明文化されたことで、将来「なぜ全面移行しなかったか」を再調査する必要がなくなる。

## Addendum（2026-07-13 同日）— 共通lib化: `kotoba-lang/murakumo` へ委譲

オーナー指示「apps image gen も kotoba-lang orgs に共通 lib として設計、移行」を受けて実装に着手したところ、
`apps/image-gen-clj`（この ADR の初版で作成）が `gftdcojp/cloud-murakumo` を直接叩く実装だったのに対し、
**`kotoba-lang/murakumo`（murakumo ファミリーの共通lib、ADR-2607041302で位置づけ済み）に、まさにこの用途の
既存実装 `murakumo.infer.media` / `murakumo.infer.gateway` が既に存在すること**が判明した。しかも
「ComfyUIプロセスがクラッシュして `/history` が空を返す」という、本セッションで実機デバッグして発見した
不具合への対処（consecutive-miss検出、`/queue`とのクロスチェック、fast/slowポーリングバックオフ）が
**既に本番レベルで実装済み**だった（コメント上に "observed live 2026-07-13" とあり、まさに同日発生した
現象と一致）。

**訂正した決定**: `apps/image-gen-clj` は `cloud-murakumo` への直接依存を止め、`kotoba-lang/murakumo` の
`murakumo.infer.media`/`.gateway`/`.fleet`/`.schedule` に委譲する（重複実装の解消）。実機で `gad`（高速
ROCmノード、~20秒/枚）が `kotoba-lang/murakumo` の `fleet.edn`（mesh 12ノードの棟）に未登録だったため、
同リポジトリに登録した（既存ノードは Mac mini/MPS で ~3分/枚、9倍遅い）。登録の過程で
`run-job!`/`run-custom-workflow!` が想定するリモートパス `comfyui/output/`（小文字）と gad の実際の
インストールパス `~/ComfyUI`（大文字、Linuxは大文字小文字を区別）の不一致も発見・解消済み（gad側に
シンボリックリンクを追加、コード変更なし）。

検証済み: `murakumo.infer.gateway/generate-image!` 経由で実行 → スケジューラが正しく `gad` を選択 →
21秒で実画像生成に成功。`apps/image-gen-clj/generator.clj` は現在、スタイルプリセット/アスペクト比の
合成（Ghost Hacker固有のプロダクト関心）のみを持ち、フリートディスパッチ自体は一切実装しない
（`kotoba-lang/murakumo` に委譲）。

## Addendum 2（2026-07-13 同日）— `cuelang` も除去、EDN + cljs へ移行

オーナー指示「next, 移行を進めて, cue も除去して, edn, cljs のみで ok」。上記の「vestigial ではない、
削除しない」という訂正結果を踏まえた上で、**cuelang 自体を EDN+cljs で置き換える**方向での除去を実施
（「使われていないから消す」ではなく「使われているものを別の技術で置き換えて消す」）。

- `internal/schema/storyboard.cue.go` の CUE スキーマ（`#Storyboard`/`#Episode`/`#Panel` 等、
  15個超の型定義）を `kotoba-lang/spec`（EDN データ + 純関数の validate/explain、clojure.spec/malli
  依存なし、kotoba-WASM でも動く同モノレポの foundational stdlib）へ全フィールド移植。
- `apps/server/scripts/validate_storyboard.cljs`（nbb実行、CLAUDE.mdのNode側検証ハーネスは.mjs禁止
  nbb推奨方針に準拠）として実装。Union型（`#Panel = #StoryboardPanel | #GraphicNovelPanel` 等）は
  `kotoba-lang/spec` に `:or` 型が無いため `:fn` predicate（両方の spec を試して片方が valid なら通す）
  で表現。
- `internal/service/storyboard.go` の `validateAndLoad` は CUE のインプロセス呼び出しをやめ、
  `nbb scripts/validate_storyboard.cljs <path>` をサブプロセス実行する形に変更（返す data 自体は
  従来通り Go 自身の `encoding/json` で独立にパースしたもの、バリデータの解析結果とは無関係）。
- `cuelang.org/go` を `go mod tidy` で完全除去、`storyboard.cue.go` を削除。
- 新規Goテスト（`storyboard_validate_test.go`）で有効/無効フィクスチャに対する実サブプロセス呼び出しを
  検証、`go build`/`go vet` もクリーン。

既知の簡略化（ドキュメント化済み、CUEとの厳密な等価ではない）: `#Context` のリテラル文字列値ピン
（JSON-LD名前空間URI）は構造的な `:string` として検証（正確なリテラル一致はチェックしない）。CUEの
2要素タプル `[int, int]` は要素の型のみ検証し、要素数=2の制約は強制しない。

## Addendum 3（2026-07-13〜14）— 決定の訂正: Dapr/ConnectRPC/Lexical を除去（cljs 移行ではなく退役）

当初の決定（本文 #2/#3、「Dapr actor/workflow・ConnectRPC・Lexical の cljc 代替が実在しない状態で
削除すれば、動いている storyboard editor が失われる」）を**訂正する**。オーナーからの追加調査指示を受け、
実際に調べた結果、これらは調査前提が誤っていた（"動いている" という前提が成立しなかった）:

- `apps/web` の実UIは、パネル編集/読み込み等の実際に動く機能を `apps/web/src/lib/server/*`
  （TypeScript、localStorage ベース）で完結させており、`apps/server` の ConnectRPC サービスを
  **そもそも経由していない**（fetch ベースの独自クライアントに既に置き換わっていた）。
- `apps/server` の Dapr ワークフロー機能（"autopilot"/チャット自律生成）は、`ChatPanel.svelte` が
  実クライアントに存在しないメソッド（`startAutonomousGeneration` 等6個）を呼んでおり、UIから
  **到達不能**だった。
- `apps/web` の Lexical リッチテキストエディタ（`LexicalSceneEditor.svelte`）はどこにもマウントされて
  おらず、**参照ゼロ**（データは保存されず、`localStorage` にすら書かれていなかった）。

オーナー指示「ok, では 移行済みであれば lexical, dapar, connect は除去してok」（cljs での再実装ではなく、
すでに死んでいる/他所に移行済みなら単純除去でよい、という条件付き承認）に基づき、以下を実施:

- `apps/server` の外部インターフェース全体を退役: `internal/dapr/{activities,actors,workflows}.go`、
  `internal/service/*.go`（9ファイル）、`internal/mcp/server.go`、`internal/jsonld/`、
  `proto/{storyboard.proto,storyboard.pb.go,storyboardpbconnect/}`、
  `scripts/validate_storyboard.cljs`（唯一の呼び元が消えたため連鎖削除）、`cmd/server/main.go`、
  `apps/dapr/components/statestore.yaml`。**残したもの**: `cmd/mcp-cursor`（独立MCPサーバー）、
  `cmd/indexer`（`internal/index` のみに依存、無関係）——いずれも削除対象への依存ゼロ。
  `go mod tidy` で `connectrpc.com/connect`・`github.com/dapr/go-sdk`・
  `google.golang.org/protobuf` 等を除去、`go build`/`go vet`/`go test` クリーン。
- `apps/web` から Lexical関連パッケージ7個・ConnectRPC関連2個を除去。ただし `@bufbuild/protobuf`
  は**復元**——`manga-layouts.ts`（Storyboard系10コンポーネントが使用するローカルスキーマ構築、
  RPCとは無関係）が実際に必要としていたため、一度除去してリグレッションを確認してから戻した。
  `LexicalSceneEditor.svelte`/`lexical/KindleNodes.ts` は削除。
- 検証: TypeScript の `tsc --noEmit` エラー件数・エラーコード集合が変更前後で完全一致（リグレッション
  ゼロ）であることを2回（Dapr/ConnectRPC/Lexical除去後、下記Addendum 4の変更後）確認。
- 副次的訂正: README の別の警告（2026-06、「`kami-app-sip-clj` に後継された」）も誤りと判明——
  当該プロジェクトは `orgs/etzhayyim/com-etzhayyim-sip` に移動済みで、自身のREADME上も
  read-only な storyboard *reader*（保存/編集/RPC/チャット/PDF/ジョブキュー無し）であり、この
  パイプラインの編集機能を代替していない。実際の公開コンテンツ制作フローは
  `orgs/com-junkawasaki/org-spirit-in-physics-comics/` の手編集EDN + babashka静的サイト生成で、
  上記のいずれにも依存しない別経路。

## Addendum 4（2026-07-14）— 残存クリーンアップ4件

Addendum 3 の退役後もリポジトリに残っていた副産物4件を一括で清掃（オーナー指示「1,2,3,4」）:

1. **`internal/resolver/hybrid.go` + `internal/vectors/store.go` を削除** — Addendum 3 の前後どちらの
   時点でも `apps/server` 内に消費者ゼロだった既存の死んだコード（今回の退役の副産物ではない、
   別経路で以前から孤立していたもの）。`go build`/`go vet`/`go test` クリーン、`go.mod`/`go.sum`
   変更なし。
2. **`ChatPanel.svelte` の壊れたUI呼び出しを除去** — 実クライアントに存在しない6メソッド
   （`getChatSessions`/`saveChatSession`/`analyzeStructure`/`interactWithAI`/
   `startAutonomousGeneration`/`terminateAutonomousGeneration`、呼べば実行時 `TypeError`）を呼ぶ
   ボタン/ハンドラ一式を削除。実際に動く `/avatar <characterId>`（`generatePanelImage` 経由）と
   メッセージ一覧UIのみ残す。`StoryboardEditor.svelte` が `bind:this` 経由で呼ぶ命令的メソッド
   （`triggerAgent`/`addMessage`/`addContext`）は互換スタブとして維持（呼び元がクラッシュしないように。
   機能自体は元から壊れていたAI連携なので no-op で十分）。存在しない `onApplyPatches` prop への
   参照（`interactWithAI` が無いため元々到達不能だった patches 機能）も合わせて除去。
3. **`storyboard_pb.ts`（2688行）を394行に縮小** — 元は退役済み27RPCメソッド分のprotobuf-es v2
   生成コードだったが、`apps/web` が実際に使う型は7メッセージ（`Panel`/`PanelData`/`Dialogue`/
   `GeneratedImage`/`MangaLayout`/`MangaPanelLayout`/`MangaText`、Storyboard系コンポーネントの
   ローカルデータ型としてのみ使用、RPCとは無関係）のみ。元の`proto/storyboard.proto`から該当
   メッセージ定義をフィールド名/番号/型を保ったまま抽出した最小proto（`manga_layout.proto`）を
   スコープ限定の`buf.gen.yaml`（ES/TSプラグインのみ、Go/ConnectRPCプラグインは含めない）で
   再生成。
4. **README の Neo4j/producer/Wattpad 節を訂正** — 2026-07-10 addendum で「このチェックアウトに
   実装が存在しない」と確認済みだったにもかかわらず本文がそのまま残っていた、存在しない
   Next.js+Neo4j `producer/` アプリのアーキテクチャ図・技術スタック・プロジェクト構造・
   クイックスタート・Wattpad自動投稿CLI手順・ロードマップchecklistを削除し、実際の構成
   （`apps/web`/`apps/image-gen-clj`/`apps/server`（`cmd/mcp-cursor`,`cmd/indexer`のみ）/
   `org-spirit-in-physics-comics`）の短い説明に置き換えた。

検証: `go build`/`go vet`/`go test` クリーン。`tsc --noEmit` は変更前のmainと完全に同じエラー
集合（20件、同ファイル・同コード）。`svelte-check` は変更前より8件少ない（ChatPanel.svelteの
壊れた呼び出しに起因した既存エラーが解消）、新規エラーはゼロ（`StoryboardEditor.svelte`の
`onApplyPatches` 参照削除も含めて file:line 差分で確認）。
