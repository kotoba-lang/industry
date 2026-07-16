---
id: adr-2607161945-kotoba-lang-pricing-oracle-market-analogy-prediction
title: "ADR-2607161945: kotoba-lang/pricing-oracle — market-analogy price prediction for quote-only verticals"
status: accepted
doc_type: adr
topic: kotoba-lang-pricing-oracle-market-analogy-prediction
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - "kotoba-lang/pricing-oracle の存在理由と方法論（same-cluster/same-unit median-of-comparables、四段階フォールバック）"
  - "予測は :pred/* として独自ファイル(predictions.edn)に出力し、cloud-itonami の pricing-intelligence-ledger.edn には書き戻さない、という provenance 分離の方針"
related:
  - 90-docs/pricing-intelligence/README.md
  - 90-docs/pricing-intelligence/pricing-intelligence.datoms.edn
  - 90-docs/design-quality/design-quality.datoms.edn
supersedes: []
superseded_by: []
---

# ADR-2607161945: kotoba-lang/pricing-oracle — market-analogy price prediction for quote-only verticals

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（「見積もり性が高いやつも価格予測の kotoba-lang repo で lib を作って、予測して、市場から予測」）

## Context

`90-docs/pricing-intelligence/`（cloud-itonami の 211 vertical 実地価格調査、
`pricing-intelligence.datoms.edn` + `pricing-intelligence-ledger.edn`）は、
実測した通り約48%の観測（302/635）が「見積もり制・完全非公開」（`:tier/opaque`）で、
vertical レベルの `:rec/*` recommendation でも 74/211 vertical が
「no self-serve band」（band-low/band-high とも null）のまま残っていた
（鉱業・上流石油ガス・産業機械・電力SCADA等、エンタープライズ商談前提の市場）。
オーナー指示は「見積もり性が高いやつも」実データが無い状態で終わらせず、
**市場の実データから予測する**ライブラリを作ること。

## Decision

新規リポジトリ `kotoba-lang/pricing-oracle` を作成し、以下の方針で実装した。

1. **手法は明言する: 学習済みモデルではなく "same-cluster/same-unit
   median-of-real-comparables" 推定器。** gap item（band が null）に対し、
   実データを持つ他 item から段階的に緩めて比較対象を探す:
   1. 同じ `:cluster` かつ同じ `:unit`（最も厳密）
   2. 同じ `:cluster`、unit は問わない（**主経路** — 自由記述の unit/rationale
      文字列は同一市場内でも文言が揃わないことが多く、業種クラスタの方が
      「比較可能な価格帯」としての実信号として強い）
   3. 同じ `:unit`、cluster は問わない（unit 文字列が別業種でたまたま一致）
   4. 粗い shape-family（per-seat / per-unit-managed / per-transaction /
      enterprise-contract 等）一致（業種横断・最終手段、月額per-seatと
      年額エンタープライズ契約を混ぜうるため常に confidence :low）
   `:confidence`（:high/:medium/:low/:none）は較正済み確率ではなく
   「実データの本数と直接比較可能性」の粗いラベル。全予測は
   `:basis-vertical-ids`（core 側は `:basis-ids`）を持ち、人間が実データの
   根拠を必ず遡れるようにする。
2. **予測は cloud-itonami 側の ledger に書き戻さない。** `pricing-intelligence-ledger.edn`
   の価値は「すべての数字が実ベンダー価格・ラベル付き第三者推定・明示的非公開の
   いずれか」であること。モデル予測はそのどれでもない別種の事実なので、
   `:pred/*` 名前空間・別ファイル（`predictions.edn`、pricing-oracle 側の
   成果物）に隔離し、`bin/predict.cljs` は cloud-itonami 側データを
   read-only にしか触らない。
3. **ランタイム優先順位（ADR-0016）に従い、core は純粋 `.cljc`。** I/O・host
   interop 皆無で kotoba wasm / clojurewasm / ClojureScript / nbb / JVM
   のいずれでも動く。cloud-itonami 固有の EDN 読み込み・join は
   `bin/predict.cljs`（nbb アダプタ）に隔離し、core は
   `{:id :cluster :unit :band-low :band-high}` という汎用 item 形状しか
   知らない（cloud-itonami 以外の「一部は価格が分かり一部は見積もり制」
   という同型問題に転用可能）。

## Result（実測、2026-07-16）

- nbb unit test 29件全PASS（`test/pricing_oracle/core_test.cljs`）。
- 実データ実行: cloud-itonami pricing-intelligence 211 vertical のうち
  137 が実データ band 持ち、74 が gap。全74件が `:cluster-fallback`
  （同業種クラスタの実データ）で `:confidence :medium` の予測を得た
  （`orgs/kotoba-lang/pricing-oracle/predictions.edn` に checked-in）。
- 例: ISIC 8610 (Hospital activities, cluster/healthcare, 元々
  「mid-five-figure annual module fee」としか書けなかった)
  → healthcare cluster の実データ7件（isic-8620医療歯科実務・isic-8730介護
  等）から $29–99 の中央値予測。

## Non-goals

較正済み統計/ML モデルではない。実際に商談が必要な高額市場の代替にはならない
——`:pred/*` の値は常に「市場推定であり観測価格ではない」と明示して扱う。

## Related

- GitHub: https://github.com/kotoba-lang/pricing-oracle
- `90-docs/pricing-intelligence/README.md`（cloud-itonami 側の実地調査データセット）
