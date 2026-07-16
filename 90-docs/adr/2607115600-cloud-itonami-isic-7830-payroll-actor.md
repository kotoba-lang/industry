# ADR-2607115600: cloud-itonami-isic-7830 — 給与計算・申告処理(Other Human Resources Provision, narrowed: Payroll Processing & Administration)を PayrollProcessor-LLM ⊣ PayrollGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という指示に続く2回目の並列バッチの1件。
ISIC Rev.4 7830「Other human resources provision」は、既に実装済みの
`isic-7810`(一時金/紹介料モデルのあっせん業者、雇用主にならない)・
`isic-7820`(派遣元が雇用主になる派遣モデル)のいずれとも異なる HR 業態を
指す広いコードである。`cloud-itonami-isic-6311`/`isic-4610`/`isic-8299`
と同じ narrowing の作法で、**給与計算・申告処理のアウトソーシング**
(クライアントが法的雇用主であり続け、この actor は給与計算・源泉徴収・
申告提出のみを代行する、ADP/Gusto/Paychex 級の業態)へ具体的に絞った。

## Decision

新規 actor `cloud-itonami-isic-7830` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した(namespace `payroll`)。

### PayrollProcessor-LLM ⊣ PayrollGovernor(単一不変条件)

> **PayrollProcessor-LLM は、PayrollGovernor が拒否する給与計算確定・
> 申告提出・紛争解決を決して行わない。**

8チェック(HARD: rbac・**tax-withholding-calculation-gate**・
**filing-deadline-gate**・source-provenance-gate・licensed-disclosure、
SOFT: 確信度フロア・disputed-employee-status gate・dispute-request 無条件)。

`tax-withholding-calculation-gate` と `filing-deadline-gate` は他の
cloud-itonami actor に存在しない domain-unique HARD チェック — 提案された
源泉徴収額が R0 簡易フラットレート推定から20%を超えて乖離したら拒否し、
対象 form が R0 filing-catalog に無い、または提出時点が申告期限を過ぎて
いる場合は拒否する(申告遅延を「期限内提出」として静かに記録することを
構造的に防ぐ)。

R0 出典カタログは実在する3つの連邦/州源泉徴収表参照(IRS Pub 15-T 連邦・
CA DE 44・NY IT-2104、tax year 2026)+ 実在する2つの連邦申告期限(Form
941・W-2)のみ。`demo-flat-rate-estimate` は governor のトレランスチェック
専用の簡易近似であり、本番の税額計算基盤ではないことを明記した。
`default-phase` は実装当初から保守的な `1` を採用(isic-6311 兄弟
テンプレートで見つかった fail-open バグを最初から回避、遡って直す必要
なし)。

Robotics premise: false(給与計算・申告提出は書面/システム上の処理のみ)。

## Consequences

- (+) `kotoba-lang/industry` registry の 7830 スロットが実装へ昇格。
  `isic-7810`/`isic-7820` との構造的差異(あっせん/派遣 vs 雇用主責任を
  負わない給与計算アウトソーシング)を ADR に明記した。
- (+) `clojure -M:dev:test`: 33 tests / 115 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも8シナリオ
  全て正しく発火。
- (-) R0 出典は3法域のみ、`demo-flat-rate-estimate` は簡易近似であり
  本番の税額計算には使えない(operator が実ブラケット計算に差し替える
  必要がある)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry、`--entry industry` の最小
  diff)。`cloud-itonami-isic-7830` は standalone、plain-git 子リポとして
  `manifest/repos.edn` には登録しない。

## 代替案と不採用理由

- **isic-7820(派遣)のスロットを拡張して給与計算も含める**: 派遣モデルは
  雇用主責任を負うが、給与計算アウトソーシングは雇用主責任を負わない
  (クライアントが雇用主のまま)。業態の法的責任構造が根本的に異なる
  ため、別 actor として独立させるのが正確。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7830/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(フリート標準パターンの手本)
- `90-docs/adr/2607112900-cloud-itonami-isic-7820-temp-staffing-actor.md`(HR系統の直接の姉妹actor、構造的差異の対比先)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "7830" エントリ)
