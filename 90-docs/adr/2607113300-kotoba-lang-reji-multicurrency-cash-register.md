---
id: adr-2607113300-kotoba-lang-reji-multicurrency-cash-register
title: "ADR-2607113300: kotoba-lang/reji — multi-currency cash-register coin counter (till tally + minimal-change calculator), integer minor units only, no floats"
status: accepted
doc_type: adr
topic: kotoba-lang-reji-multicurrency-cash-register
authoritative: true
last_verified: 2026-07-10
authoritative_for:
  - "till-tally + minimal-piece change-making として新設した kotoba-lang/reji の設計方針(整数の最小通貨単位のみ、float禁止)"
  - "汎用基盤 OSS ライブラリの新規登録先は com-junkawasaki でなく kotoba-lang とする適用実例(ADR-2606302300 移行方針)"
related:
  - orgs/kotoba-lang/reji                    # 新設: 多通貨レジ小銭カウント/釣銭計算
  - orgs/kotoba-lang/banking                 # 同型スキャフォールド(deps.edn/LICENSE/README/.cljc)の手本
  - 90-docs/adr/2606302300-org-taxonomy-4-orgs.md   # org 配置ポリシーの根拠(com-junkawasaki は汎用基盤の transitional foundation、最終的に kotoba-lang へ全移行)
supersedes: []
superseded_by: []
---

# ADR-2607113300: kotoba-lang/reji — 多通貨対応レジ小銭カウント/釣銭計算

- Status: accepted (2026-07-10)
- Deciders: Jun Kawasaki (+ Claude, standing authorization 2026-06-28 — 新規 project scaffold→登録の一気通貫フロー)

## Context

オーナーから「日本のレジで小銭を数えるシステムを OSS で設計・実装、インド・
中国・ブラジル・メキシコ・アラビアなどの通貨にも対応」という依頼。レジ運用の
実務は大きく2つ: (1) 締め時にドロワー内の硬貨/紙幣を数えて合計を出す
(till-close tally)、(2) 会計時に釣銭として渡す最小枚数の硬貨/紙幣を決める
(change-making)。両方とも金額を扱うので、浮動小数点の丸め誤差はそのまま
レジが締まらない不具合になる。

## 決定

### 1. 金額はすべて「その通貨の最小流通単位」の整数で表現する(float 不使用)

`kotoba.reji.currency` は通貨コード(ISO 4217)→ 額面テーブル(硬貨/紙幣の
minor-unit 整数 + kind + label)の静的レジストリ。`parse-amount`/
`format-amount` だけが10進文字列⇄整数の橋渡しをし、どちらも文字列/整数演算
のみで浮動小数点を経由しない。`subdivision`(1 major unit あたりの minor
unit 数、JPY=1、他は基本100)をテーブルに持たせるだけで3進数通貨(KWD/BHD/
OMR の1000)も型を変えずに追加できる。

### 2. 収録通貨は JPY(基準) + INR/CNY/BRL/MXN/SAR/AED

依頼にあったインド・中国・ブラジル・メキシコに加え、「アラビア」は特定の
国名ではないため、代表としてサウジアラビア・リヤル(SAR)と UAE ディルハム
(AED)の2通貨を収録(いずれも湾岸協力会議の主要通貨)。額面は現行流通デー
タに基づく手作業キュレーションで、法定通貨の一次ソースではない旨を README
に明記(中央銀行は額面を随時改廃するため)。

### 3. `tally`(無制限枚数の集計)と `make-change`(釣銭の最小枚数化)の2関数

`make-change` は DP(unbounded coin-change、標準的な単一パス動的計画法)で
最小枚数を求める。額面の在庫上限(bounded/stock-limited 版)は実装しない —
実店舗の「レジに小銭が足りない」状況をモデル化する追加機能だが、現時点の
依頼は「数える」ことが主眼であり、投機的な将来要件を先取りしない
(CLAUDE.md の over-engineering 回避方針)。ちょうど割り切れない金額
(例: MXN の最小硬貨5センターボ未満)は例外を投げず、実現可能な最大額 +
`remainder` を返す設計とし、実店舗の「近い額で妥協して渡す」実務に合わせた。

### 4. 新規登録先は `com-junkawasaki` でなく `kotoba-lang`(public)

`repos.edn` の org taxonomy(ADR-2606302300)で `com-junkawasaki` は
`:transitional-foundation`(汎用基盤 `-clj` ライブラリは最終的に全て
`kotoba-lang` へ移行予定、当org はデータのみへ縮退予定)と明記されている。
本ライブラリは特定ドメイン(actor/business)に属さない汎用計算ライブラリな
ので、移行を待たず新規から `kotoba-lang` に置く。副次的に、オーナーが
明示的に依頼した「OSS」(公開)は `kotoba-lang` の org 既定 visibility が
public であることでそのまま満たされる(`com-junkawasaki` は既定 private)。
スキャフォールド(`deps.edn`/Apache-2.0 `LICENSE`/`README`/`.cljc` src+test)
は同 org の `banking` を手本にした同型構成。

### 5. フロントエンドは nbb CLI(`script/cli.cljs`)

`.cljc` コアはランタイム非依存。ユーザーが実際に手で叩ける「システム」と
して、`.cljc/.kotoba` ランタイム優先順位(kotoba wasm > clojurewasm > cljs
> nbb > jvm/nbb)に従い nbb 製 CLI を1本追加(`currencies`/`tally`/
`change` サブコマンド)。ブラウザ UI や kotoba wasm 化は本 ADR の対象外
(需要が出たら別途)。

## 検証

`clojure -M:test`(cognitect test-runner、JVM)16 tests / 771 assertions
green。`clojure -M:lint`(clj-kondo)0 errors / 0 warnings。`npx nbb -cp src
script/cli.cljs {currencies,tally,change}` を実通貨(JPY/INR/MXN/AED)で
手動スモークテストし、`change` の非整除ケース(MXN 7 centavos → 5 centavos
+ remainder 2)を含め期待通りの出力を確認。

## 帰結

- 1リポ新設(`kotoba-lang/reji`、public、Apache-2.0)。CI(`ci.yml`)は
  JVM テスト + nbb スモークの2ジョブ。
- `manifest/repos.edn` の `:manifest.repos/extra-projects` に
  `orgs/kotoba-lang/reji` を追加し、`nbb scripts/gen-west-manifest.cljs
  --entry reji` で west.yml に最小 diff 登録(本 ADR と同じコミットで実施)。

## 却下案

- **bounded/stock-limited change-making(釣銭在庫の枚数上限)**: 実店舗の
  リアリティは増すが、依頼の主眼(数える/釣銭を出す)を超える投機的機能。
  `stock` 引数を後付けする形で `make-change` を拡張できるよう関数シグネチャ
  は素直に保ってあるので、必要になったら追加は容易。
- **`com-junkawasaki` へ新規登録してから後で `kotoba-lang` へ移行**: ADR-
  2606302300 が既に移行方針を決定済みで、新規ライブラリを一旦 private の
  transitional org に置く理由がない(むしろ OSS 公開の依頼と矛盾する)。
- **フル Web UI / kotoba wasm 化**: 現時点で需要がなく、CLI で依頼の要件
  (数える・釣銭を出す)は満たせる。ランタイム優先順位の上位選択肢は温存
  し、必要になった時に ADR 化する。
