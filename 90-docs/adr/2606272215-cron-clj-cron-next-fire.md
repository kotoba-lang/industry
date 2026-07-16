---
id: adr-2606272215-cron-clj-cron-next-fire
title: "ADR-2606272215: cron-clj — cron 式を EDN データとして扱い純粋な next-fire-time を計算する再利用ライブラリ。model(5フィールド parse + フィールド predicates) + validate + execute(days_from_civil helper + fires-at? + next-fire)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - cron 式を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - cron-clj の責務境界(model/validate/execute)と純粋カレンダー算術の設計
  - java.time/Date を使わずに純粋 Clojure でプロレプティックグレゴリオ暦日数計算を行う方針
  - dom/dow の OR セマンティクス(Vixie cron 互換)の実装方針
  - スケジューリング/loop actor が利用する next-fire-time の依存ライブラリとしての位置付け
related:
  - orgs/kotoba-lang/cron                        # 本 ADR のライブラリ
  - orgs/kotoba-lang/org-omg-bpmn                        # 同型の再利用 kernel(先例)
  - orgs/kotoba-lang/org-omg-dmn                         # 同型の再利用 kernel(先例)
  - 90-docs/adr/2606272200-bpmn-clj-edn-process-library  # bpmn-clj の設計 ADR
supersedes: []
superseded_by: []
---

# ADR-2606272215: cron-clj — cron を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。loop/scheduler actor からの利用は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

定期実行スケジュールを cron 式で宣言する需要は多いが、既存の選択肢は
(1) JVM 専用の `java.time`/`quartz` に依存して CLJS/SCI で動かない、
(2) 非ポータブルな文字列として保持して EDN データとして `assoc`/`diff`/比較できない、
(3) サードパーティライブラリを引いて本リポジトリの「dep ゼロ可搬」方針に反する、
という問題を持つ。

本リポの方針（shallow 既定・大容量 dep 回避・portable `.cljc`・host-injected ports —
bpmn-clj / dmn-clj の先例）に沿う、**cron 式を素の EDN として扱い純粋に next-fire を
計算する軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/cron-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`（JVM/CLJS/SCI）。責務を3層に分離する:

- **`cron.model`** — cron 式の正準 EDN モデル。`parse` は 5 フィールド文字列
  （min hour dom month dow）または `@daily` 等のマクロ省略形を受け取り、各フィールドを
  整数集合 (`#{...}`) に展開する。キーは namespaced `:cron/*`。フィールド構文:
  `*` / `n` / `a-b` / `a-b/s` / `*/s` / `a,b,c`（混在可）。3文字名称:
  `JAN`–`DEC`（month）、`SUN`–`SAT`（dow）、大文字小文字不問。
  `:cron/dom-restricted?` / `:cron/dow-restricted?` フラグが dom/dow の `*` 非使用を記録し、
  後段の OR セマンティクス判定に使われる。

- **`cron.validate`** — 構造検証。`{:cron/severity :cron/code :cron/id :cron/msg}` の
  vector を返す純関数。error（フィールド値が合法範囲外 / 空集合）を分離し、
  `valid?` は error 無しで真。

- **`cron.execute`** — 純粋 next-fire 評価器。datetime は素の map `{:y :m :d :hh :mm}`
  — `java.time` / `Date` は一切使わない。カレンダー算術は **Howard Hinnant の
  `days_from_civil` / `civil_from_days`**（proleptic Gregorian 日数 ↔ 年月日の
  相互変換）を Clojure で実装した純粋関数で行う。`weekday` は Unix epoch（= 0）が
  木曜日であることから `(mod (+ day-number 4) 7)` で 0=Sun..6=Sat を返す。

  **`fires-at?`** の day マッチング（Vixie cron 互換 OR セマンティクス）:
  - `dom-restricted? AND dow-restricted?` → dom 一致 OR dow 一致で発火
  - `dom-restricted? のみ` → dom 一致で発火
  - `dow-restricted? のみ` → dow 一致で発火
  - どちらも非制限 → 常に発火

  **`next-fire`** は `from` より厳密に後ろの最初の発火時刻を1分ステップで探索する。
  上限は約3年（≈ 1,576,980 分）、到達したら `nil` を返す。advance-minute は
  `days-from-civil` / `civil-from-days` で日ロールオーバーを正確に処理する。

## Rationale

- **データ第一**: cron 式が EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  文字列 cron を伝搬させず、parse 済み map を回路の「一級市民」として扱える。
- **依存ゼロ × 可搬**: `java.time` / `quartz` / 外部 cron パーサを引かない。
  CLJS / SCI host での実行（WASM エージェントなど）と両立する。
- **純粋カレンダー算術**: Hinnant アルゴリズムは well-known・テスト可能・ブランチレスで
  うるう年・月末ロールオーバーを正確に処理する。ホスト時刻 API に触れず、
  `fires-at?` / `next-fire` は副作用ゼロで決定的。
- **bpmn-clj / dmn-clj と同型**: `parse` → 集合展開、`validate` → problem vector、
  `execute` → 純粋評価器、の三層構成は既存 kernel と対称的でオンボーディングが容易。
- **スケジューリング/loop actor との連携**: `next-fire` が返す plain map は
  loop/scheduler actor が次の目覚め時刻を計算する際にそのまま使える（変換不要）。

## Consequences

- `next-fire` の探索は min-step（最大 1,576,980 回）。現実の cron 式では数回〜数千回
  程度で収束するが、2月31日のような不能式は上限まで走って `nil` を返す。
  パフォーマンスが問題になる場合はフィールド最小値への「スキップ」最適化を後から追加できる。
- calendar 算術は proleptic Gregorian のみ（ユリウス暦への切り替えなし）。
  西暦 1582 年以前の日付は正しくない（実用上問題なし）。
- 最初の消費者は別 org（公益 = etzhayyim / 事業 = gftdcojp）の loop/scheduler actor
  が `next-fire` を呼んで次回実行時刻を決定する（本ライブラリにドメインは入れない）。

## Verification

`clojure -X:test` 緑（16 tests / 43 assertions）。`days-from-civil` round-trip /
`weekday` 既知日（2026-06-27=土曜=6、1970-01-01=木曜=4）/ `*/15` parse /
`@daily` macro / range+step / `fires-at?` true/false / `next-fire` 時間越え・日越え /
dom/dow OR セマンティクス（`0 0 13 * FRI`：13日 OR 金曜）を確認。
