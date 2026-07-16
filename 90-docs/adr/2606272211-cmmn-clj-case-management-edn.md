---
id: adr-2606272211-cmmn-clj-case-management-edn
title: "ADR-2606272211: cmmn-clj — CMMN(ケース管理記法)を EDN/Clojure データとして扱う再利用ライブラリ。model(id-keyed plan-item graph) + validate + host-injected ports(ITask/IGuard) + 純粋 sentry-driven lifecycle インタプリタ。third-party dep ゼロの portable .cljc。bpmn-clj + dmn-clj と並ぶ OMG トリオの完成"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - CMMN(適応型ケース管理記法)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - cmmn-clj の責務境界(モデル/検証/ports/実行)と host-injected ports(ITask/IGuard)の設計
  - sentry(entry/exit criteria)駆動の plan-item ライフサイクルを純関数で fixpoint 評価する戦略
  - ケース成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/kotoba-lang/org-omg-cmmn                       # 本 ADR のライブラリ
  - orgs/kotoba-lang/org-omg-bpmn                       # 構造化フロー(OMG トリオの姉妹)
  - orgs/kotoba-lang/org-omg-dmn                        # 決定表(OMG トリオの姉妹)
  - orgs/kotoba-lang/koe                        # 同型の再利用 kernel(host-injected ports の先例)
supersedes: []
superseded_by: []
---

# ADR-2606272211: cmmn-clj — CMMN を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。ports を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

業務の現場には、BPMN の構造化フローでは表せない**非定型・適応型の作業(ケース)** が
多い。担当者の判断で順不同に走るタスク、達成条件(マイルストン)、状況に応じて開閉する
ステージ — これらは OMG の **CMMN(Case Management Model and Notation)** が扱う領域で、
BPMN(プロセス)/DMN(決定)と相補する第3の標準である。本リポには既に
**bpmn-clj**(構造化フロー)と **dmn-clj**(決定表)があるが、CMMN だけが欠けており、
OMG プロセス標準トリオが未完だった。既存の CMMN エンジンは JVM 専用の重い依存を引き、
ケースを不透明な状態に閉じ込め、Clojure の `assoc`/`diff`/Datomic と噛み合わない。

## Decision

`com-junkawasaki/cmmn-clj` を新設し、**OMG トリオを完成**させる。**third-party 実行時
依存ゼロ**、全 namespace `.cljc`(JVM/CLJS/SCI)。bpmn-clj / dmn-clj と同型に責務を分離する:

- **`cmmn.model`** — CMMN-as-EDN の正準モデル。plan-item は **id-keyed map**(O(1)参照)、
  entry/exit criteria(sentry)は各 item のベクタとして持ち document order に依存しない。
  threadable builder(`case-model`/`item`/`entry`/`exit`/`child`)。plan-item type は
  `:human-task :process-task :stage :milestone :event-listener`。sentry は
  `{:cmmn/on <item-id> :cmmn/event :complete|:occur|:terminate :cmmn/if <cond?>}`。
- **`cmmn.validate`** — 構造検証。`{:cmmn/severity :cmmn/code :cmmn/id :cmmn/msg}` の
  vector を返す純関数。error(未知の plan-item type / 存在しない item を参照する criterion /
  存在しない stage child)と warn(criteria の循環)を分離、`valid?` は error 無しで真。
- **`cmmn.ports`** — host が注入する2プロトコル。`ITask`(run: item→ctx→ctx')で
  task plan-item の副作用を、`IGuard`(allow?: cond→ctx→bool)で sentry の `:cmmn/if`
  ガードを外部化。`default-ports` は ITask=identity / IGuard=`(get ctx (keyword cond))`
  の truthiness(キー欠如→true の open guard)で、host 無しでもケースを動かせる。
- **`cmmn.execute`** — **純粋 sentry-driven ライフサイクル インタプリタ**。各 item は
  `:available`/`:active`/`:completed` を持つ素データ state。`start` で entry-criteria を
  持たない item は `:active`(milestone は「発生済」)に、他は `:available` になる。
  `raise` で item を `:completed` 化(task は ITask 実行)し、全 available item の sentry を
  **fixpoint まで再評価** — 参照先が標準イベント(:complete⇒completed / :occur⇒milestone
  completed)に達し かつ IGuard が通れば criterion 充足、全 entry-criteria 充足で
  `:available→:active`。これにより t0→m1→t1 のような多段連鎖が1回の `raise` で発火する。

## Rationale

- **データ第一**: ケースが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  エンジン状態の不透明 blob を持たない。state も素データで inspectable/replayable。
- **依存ゼロ × 可搬**: 重いエンジン dep を避ける本リポ方針と、WASM/SCI host での実行
  要件を両立。`clojure.string`/`clojure.set`/core のみ使用。
- **host-injected ports**: koe-clj / bpmn-clj と同じ分離。kernel は SDK/資格情報/式言語を
  持たず、sentry オーケストレーションだけが純粋に残る → オフラインで fixture port により
  テスト可能。
- **OMG トリオの一貫性**: bpmn-clj(`:bpmn/...`)/ dmn-clj(`:dmn/...`)と同じ namespaced
  key・id-keyed map・`{severity code id msg}` validate 形・`default-ports` 規約を踏襲し、
  3標準を同型の API で扱える。

## Consequences

- 本 subset は tractable な実行モデルに限定する。milestone の `:active` は「発生済」を
  意味し、entry-criteria 無しの task は自動 active(auto-start)とみなす。exit-criteria /
  `:terminate` / event-listener のタイマ等のフル CMMN semantics は将来課題。
- criteria 循環は warn(検出のみ)で、実行は fixpoint 反復が自然に停止する範囲で扱う。
- 最初の消費者は別 org(公益=etzhayyim / 事業=gftdcojp)の actor が ports を注入して
  実ケースを駆動する(本ライブラリにドメインは入れない)。

## Verification

`clojure -X:test` 緑(13 tests / 37 assertions)。entry-criteria 無し item の start 自動
活性化、entry task 完了による milestone occur、stage children 活性化、依存完了までの
item ゲート、sentry ガードによる活性化阻止、t0→m1→t1 の2段連鎖を1回の `raise` で駆動、
dangling criterion の validate error を確認。
