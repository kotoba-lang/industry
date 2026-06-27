---
id: adr-2606272202-statechart-clj-harel-statecharts
title: "ADR-2606272202: statechart-clj — Harel ステートチャート / SCXML サブセットを EDN/Clojure データとして扱う再利用ライブラリ。model(id-keyed + 階層/並列) + validate + 純粋インタプリタ(IAction/IGuard ports)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - Harel ステートチャート(SCXML サブセット)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - statechart-clj の責務境界(model/validate/ports/execute)と host-injected ports の設計
  - 階層状態(compound)・並列状態(parallel)・最終状態(final)の configuration 管理と LCA ベース exit/entry 計算
  - ダイアログ/予約ステートマシン(etzhayyim yadori / denwaban)を駆動する状態機械 kernel の3-org 配置
related:
  - orgs/com-junkawasaki/statechart-clj                  # 本 ADR のライブラリ
  - orgs/com-junkawasaki/bpmn-clj                        # 同型の再利用 kernel(host-injected ports の先例)
  - orgs/com-junkawasaki/koe-clj                         # 音声 kernel(姉妹)
  - 90-docs/adr/2606272200-bpmn-clj-edn-process-library.md  # bpmn-clj ADR(パターン元)
supersedes: []
superseded_by: []
---

# ADR-2606272202: statechart-clj — Harel ステートチャートを EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。ports を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

予約受付(etzhayyim/yadori)・音声受付(denwaban)・ロボタクシー乗降フロー(robotaxi-actor)
など、複数 org のアクターが「状態機械」を必要としている。選択肢として:

1. **既存 SCXML エンジン**(Apache Commons SCXML / Jakarta など) — JVM 専用・重い依存・
   状態が不透明 XML/DB に閉じ込められ Clojure の `assoc`/`diff`/Datomic と噛み合わない。
2. **アドホックな atom + cond** — 各アクターが車輪の再発明。階層や並列が必要になると
   ロジックが肥大し、テスト可能性が低い。
3. **本ライブラリ(採用)** — bpmn-clj・koe-clj と同じ設計パターン:
   third-party dep ゼロ・全 namespace `.cljc`・plain EDN で状態機械を表現・
   host-injected ports でオフラインテスト可能な純粋インタプリタ。

## Decision

`com-junkawasaki/statechart-clj` を新設する。責務を4層に分離する:

- **`statechart.model`** — Harel ステートチャート / SCXML サブセットの正準 EDN モデル。
  状態は **id-keyed map**(`O(1)` 参照)。状態タイプ: `:atomic`(葉)・`:compound`
  (`:sc/initial` + 入れ子 `:sc/states`)・`:parallel`(全子領域同時活性)・`:final`
  (終端)。遷移: `{:sc/target :sc/cond :sc/actions}`。threadable builder(`chart`/
  `state`/`compound`/`parallel`/`add-state`/`on`)とグラフ query(`ancestors`/
  `descendants`/`all-states`/`lookup`)。

- **`statechart.validate`** — 構造検証。`{:sc/severity :sc/code :sc/id :sc/msg}` の
  vector を返す純関数。error: `:sc/initial` 参照が存在しない・compound に `:sc/initial`
  なし・遷移 `:sc/target` が不在。warn: 到達不能状態。`valid?` は error 無しで真。

- **`statechart.ports`** — `IAction`(run: action-name → ctx → ctx')と `IGuard`
  (allow?: cond-name → ctx → boolean)の2プロトコル。カーネルは I/O を持たない。

- **`statechart.execute`** — **純粋インタプリタ**。Configuration はアクティブな state-id
  の集合(atomic + その compound/parallel 祖先)。`start`(初期 config に入り entry action
  を実行)・`send`(最内優先で遷移を探索し祖先へバブリング、LCA を基点に exit→遷移 actions
  →entry を実行)・`done?`(`:final` 状態が active なら真)。並列状態では各領域が独立に
  遷移を処理。`default-ports` でホスト無しでも制御フローを動かせる。

## Rationale

- **データ第一**: チャートが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  エンジン状態の不透明 blob を持たない。
- **依存ゼロ × 可搬**: 重い SCXML エンジンを避ける本リポ方針と、WASM/SCI host での
  実行要件を両立。
- **host-injected ports**: bpmn-clj / koe-clj と同じ分離。kernel は SDK・資格情報・
  式言語を持たず、オーケストレーションだけが純粋に残る → オフラインで fixture port に
  よりテスト可能。
- **LCA ベース exit/entry**: SCXML の最低共通祖先(LCA)アルゴリズムを採用。階層遷移で
  「通過する compound 状態に不要な exit/entry を発生させない」正しいセマンティクスを実現。
- **並列領域の独立遷移**: parallel 状態の各 atomic 子が同一イベントに対して独立に遷移を
  選択・実行。source-id で重複排除し、共有祖先を複数回 fire しない。

## Consequences

- 本ライブラリは SCXML の完全実装ではない(OR-join・history 状態・invoke・datamodel
  式言語は対象外)。実用的なダイアログ/予約フローを動かすサブセットに限定する。
  限界は README に明記し、逸脱時は issue で拡張を検討。
- `statechart.model/ancestors` / `descendants` / `statechart.execute/send` は
  `clojure.core` と名前が衝突し実行時 WARNING が出るが、名前空間が分離されているため
  機能上は問題ない(今後 `ancestor-ids` 等へのリネームで解消可能)。
- 最初の消費者は別 org の actor(etzhayyim/denwaban・yadori)が ports を注入して
  実ドメイン状態機械を駆動する(本ライブラリにドメインロジックは入れない)。

## Verification

`clojure -X:test` 緑(8 tests / 27 assertions)。確認項目:
- 単純フラット遷移(OPEN/CLOSE)・ガード false でブロック・ガード true で遷移
- compound 入場で `:sc/initial` 子に再帰・階層バブリング(子にハンドラなし→親が発火)
- parallel 入場で全領域アクティブ・1イベントで両領域が同時に前進
- entry/exit/遷移 action の実行順序(parent-first entry・child-first exit)
- `:final` 到達で `done?` = true
- validate: valid チャートは通過・存在しない `:sc/initial` と dangling target はエラー
