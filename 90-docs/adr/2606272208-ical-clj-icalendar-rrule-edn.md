---
id: adr-2606272208-ical-clj-icalendar-rrule-edn
title: "ADR-2606272208: ical-clj — iCalendar(RFC 5545) + RRULE を EDN/Clojure データとして扱い、純粋な再帰反復(occurrence)展開を提供する再利用ライブラリ。model + validate + minimal iCal I/O(parse/emit) + Howard Hinnant 日付算術による pure recurrence engine。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - iCalendar(RFC 5545)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - ical-clj の責務境界(model/validate/ical I/O/recurrence 実行)と設計
  - java.time / Date 依存を完全排除するための pure 日付算術戦略(Howard Hinnant proleptic-Gregorian day-number)
  - RRULE(FREQ/INTERVAL/COUNT/UNTIL/BYDAY)の純粋展開ロジックの参照実装
  - カレンダー成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/ical-clj                        # 本 ADR のライブラリ
  - orgs/com-junkawasaki/koe-clj                         # voice-reception / auto-booking との連携先
  - orgs/com-junkawasaki/bpmn-clj                        # 同型の再利用 kernel(設計先例)
  - orgs/com-junkawasaki/dmn-clj                         # 同型の再利用 kernel(設計先例)
supersedes: []
superseded_by: []
---

# ADR-2606272208: ical-clj — iCalendar を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。actor 側 booking フローとの接続は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

予約・スケジューリング需要において、イベントの繰り返し規則(RRULE)を展開し、
CalDAV や `.ics` ファイルとして外部カレンダーと連携する必要がある。既存の
Clojure iCalendar ライブラリは (1) JVM 専用の java.time 依存を引く、(2) パーサが
外部 XML/CSV ライブラリに依存する、(3) CLJS/SCI で動かない、のいずれかであり、
本リポの方針(shallow 既定・third-party dep 回避・portable `.cljc`・host-injected
ports — koe-clj / bpmn-clj / dmn-clj の先例)に適合しない。

加えて、koe-clj の denwaban(電話番号/予約)フローは booking 成果物を EDN で保持
しており、それをそのまま `.ics` 形式で出力・再帰展開できる薄いカーネルが必要だった。

## Decision

`com-junkawasaki/ical-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を4層に分離する:

- **`ical.model`** — iCalendar-as-EDN の正準モデル。`:ical/*` 名前空間付きキー。
  日付時刻は素の map `{:y :m :d :hh :mm}`(java.time / Date 一切なし)。
  threadable builder(`calendar`/`event`/`todo`/`rrule`/`add-event`/`add-todo`/`dt`)。
- **`ical.validate`** — 構造検証。`{:ical/severity :ical/code :ical/id :ical/msg}` の
  vector を返す純関数。error(VEVENT の uid/dtstart 欠落 / RRULE の未知 freq / COUNT と
  UNTIL の同時指定 / 未知 BYDAY トークン)と warn を分離。`valid?` は error 無しで真。
- **`ical.ical`** — RFC 5545 テキスト ⇄ model。**自前の minimal reader/emitter**で
  BEGIN/END ネスト・RFC 5545 行アンフォールド・プロパティ文法・値アンエスケープ・
  DTSTART/DTEND/RRULE の parse/emit をカバー。汎用 iCal パーサではないが、
  機械生成の well-formed subset を dep 無しで往復できる。
- **`ical.execute`** — **純粋 recurrence 展開**。`occurrences` は (event, from-dt, to-dt)
  → `[dtstart-map …]` を返す。Howard Hinnant proleptic-Gregorian day-number 算術
  (`days_from_civil` / `civil_from_days` / `weekday_from_days`)を pure Clojure で
  実装し、:daily/:weekly/:monthly/:yearly + :interval + :count/:until + :byday を
  展開。月末クランプ(例: 1/31 → 2/28)も純整数算術で処理。

## Rationale

- **データ第一**: カレンダーが EDN なので生成・差分・バージョニング・Datomic 格納が
  自明。パーサ状態や外部オブジェクトを持たない。
- **依存ゼロ × 可搬**: java.time を持たない CLJS/SCI host での実行要件を満たすため、
  日付算術を Howard Hinnant アルゴリズム(pure 整数)で実装。外部カレンダーライブラリ
  の巨大 dep を完全排除。
- **koe-clj / bpmn-clj との一貫性**: 同じ thin-kernel 設計(model / validate / I/O /
  execute 4層、host-injected ports パターン)を踏襲。学習コストと設計乖離を最小化。
- **RRULE 正確性**: BYDAY の週内複数曜日(MO,WE → 月曜・水曜を両方展開)、UNTIL の
  包含的終端、月末クランプ、non-recurring フォールバックをテスト済み(53 assertions)。

## Consequences

- 自前の iCal reader は well-formed subset 限定(DTD/CDATA 非対応、複数カレンダー
  の VCALENDAR 連結非対応)。逸脱は consume 側で前処理するか、`parse-str` の前段で
  整形する。
- BYDAY に `+n` / `-n` プレフィックス(MONTHLY の「第2月曜」等)は未実装。将来課題。
- WKST(週開始曜日)は MO 固定。カスタム WKST が必要なら execute.cljc の
  `monday-of-week` を拡張する。
- 最初の消費者: koe-clj の booking actor が予約 EDN を ical-clj で `.ics` に直列化
  し、外部 CalDAV サーバへ PUT する(ical-clj にドメインは入れない)。

## Verification

`clojure -X:test` 緑(12 tests / 53 assertions)。parse sample.ics → VEVENT フィールド、
round-trip emit→parse、validate(正常/uid 欠落/count+until 同時指定)、
weekday-from-days 既知日付(1970-01-01=Thu / 2024-01-01=Mon / 2026-06-27=Sat)、
weekly COUNT 展開 / BYDAY=MO,WE 展開 / daily INTERVAL=2 / monthly 月末クランプ /
UNTIL 境界 / non-recurring フォールバックをすべて確認。
