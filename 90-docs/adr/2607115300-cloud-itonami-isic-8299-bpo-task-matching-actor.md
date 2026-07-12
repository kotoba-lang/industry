# ADR-2607115300: cloud-itonami-isic-8299 — その他事業支援サービス(narrowed: VA/BPOタスクマッチング)を TaskRouter-LLM ⊣ RoutingGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という指示のもと、`kotoba-lang/industry` registry
の未着手 `:spec` スロットから6件を選んで並列で actor を新設した(本ADRは
その1件)。ISIC Rev.4 8299「Other business support service activities
n.e.c.」は n.e.c.(not elsewhere classified)の広いコードであり、
`cloud-itonami-isic-6311`/`cloud-itonami-isic-4610` と同じ narrowing の
作法で、**バーチャルアシスタント/BPO タスクマッチング**へ具体的に絞った
(単純な relabeling ではなく、ADR で narrowing 決定を明記)。

## Decision

新規 actor `cloud-itonami-isic-8299` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した(namespace `bizsupport`)。クライアント
の業務依頼(リサーチ・スケジューリング・データ入力・カスタマーサポート
overflow)を分解し、契約済みの人間オペレータのプールへ割り当てるサービス。

### TaskRouter-LLM ⊣ RoutingGovernor(単一不変条件)

8チェック(HARD: rbac・**clearance-tier-gate**・**capacity-gate**・
scope-gate・licensed-disclosure、SOFT: 確信度フロア・high-value-task
gate・dispute-request 無条件)。

`clearance-tier-gate` と `capacity-gate` は他の cloud-itonami actor に
存在しない domain-unique HARD チェック — オペレータがタスクの要求する
すべての認証(HIPAA・PCI DSS・SOC 2・GDPR data-processor・ISO/IEC 27001
の実在5規格からなる閉じた R0 カタログ)を保持していなければ割当を拒否し、
オペレータの申告週次キャパシティを超える割当も拒否する。決済は一切扱わず、
雇用主責任も負わない(`cloud-itonami-isic-7820` の派遣モデルとは明確に
異なる)。`default-phase` はセッション開始時点から保守的な `1` を採用。

## Consequences

- (+) `kotoba-lang/industry` registry の 8299 スロットが `:spec`(死んだ
  プレースホルダー)から実装へ昇格。narrowing の判断(VA/BPO タスク
  マッチングへの具体化)を ADR に明記した。
- (+) `clojure -M:dev:test`: 32 tests / 127 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。
- (-) R0 認証カタログは5規格のみ(HIPAA/PCI-DSS/SOC2/GDPR-processor/
  ISO27001)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ。
  `cloud-itonami-isic-8299` は standalone、plain-git 子リポとして
  `manifest/repos.edn` には登録しない。

## 代替案と不採用理由

- **8299 を narrowing せずに汎用「業務支援全般」actor として実装**:
  スコープが際限なく広がり、`cloud-itonami-isic-4610`/`isic-6311` が
  確立した「n.e.c. コードは具体的な1業態へ narrow する」規律に反する。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8299/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(narrowing 手法・フリート標準パターンの手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "8299" エントリ)
