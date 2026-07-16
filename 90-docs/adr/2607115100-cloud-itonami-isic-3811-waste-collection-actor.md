# ADR-2607115100: cloud-itonami-isic-3811 — 非危険廃棄物収集(Collection of Non-Hazardous Waste)を WasteDispatch-LLM ⊣ WasteDispatchGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という指示のもと、`kotoba-lang/industry` registry
の未着手 `:spec` スロットから6件を選んで並列で actor を新設した(本ADRはその
1件)。ISIC Rev.4 3811「Collection of non-hazardous waste」は死んだ
`gftdcojp/cloud-itonami-E3811` プレースホルダー URL のまま `:spec` で放置
されていた。

## Decision

新規 actor `cloud-itonami-isic-3811` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した。廃棄物収集のディスパッチ・スケジュー
リングサービス — 自治体/商業廃棄物収集業者向けの回収ルート最適化・容器/
コンテナのスケジューリング・重量/容積マニフェスト作成、および**収集時点
での危険物混入スクリーニング**(「非危険廃棄物」収集業者が電池・医療廃棄物・
化学物質混入の危険物ストリームを誤って受け入れ・運搬してしまうのは実際の
規制違反リスク)を担う。

### WasteDispatch-LLM ⊣ WasteDispatchGovernor(単一不変条件)

> **WasteDispatch-LLM は、WasteDispatchGovernor が拒否する収集スケジュール
> 確定・マニフェスト記録・開示・紛争解決を決して行わない。**

8チェック(HARD: rbac・**hazard-misclassification-gate**・
**facility-permit-capacity-gate**・source-provenance-gate・
licensed-disclosure、SOFT: 確信度フロア・bulk-volume gate・
dispute-request)。`hazard-misclassification-gate` と
`facility-permit-capacity-gate` は他の cloud-itonami actor に存在しない
domain-unique HARD チェック — 危険物フラグが立った時点で確信度に関わらず
無条件拒否(この actor は構造的に非危険廃棄物収集のみを扱う)、施設の環境
許可証が定める1日あたり廃棄物クラス別受入上限を超える収集は拒否する。

R0 分類根拠カタログは実在する3つの規制枠組み(米国 RCRA、EU Waste Framework
Directive、日本 廃棄物処理法)。`default-phase` はセッション開始時点から
保守的な `1` を採用(isic-6311 兄弟テンプレートで見つかった fail-open
バグの事前適用)。

Robotics premise: false(スケジューリング・マニフェスト管理のみで、実際の
収集作業自体は actor の境界外)。

## Consequences

- (+) `kotoba-lang/industry` registry の 3811 スロットが実装へ昇格。
- (+) `clojure -M:dev:test`: 33 tests / 126 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。
- (-) R0 分類根拠は3法域のみ(米・EU・日本)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry)。`cloud-itonami-isic-3811` は
  standalone、plain-git 子リポとして `manifest/repos.edn` には登録しない。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-3811/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(フリート標準パターンの手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "3811" エントリ)
