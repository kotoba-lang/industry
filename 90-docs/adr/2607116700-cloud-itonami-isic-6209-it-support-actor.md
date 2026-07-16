# ADR-2607116700: cloud-itonami-isic-6209 — その他IT/コンピュータサービス(narrowed: マネージドサービス/ヘルプデスク・チケットルーティング)を TicketRouter-LLM ⊣ TicketGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という指示のもと、`kotoba-lang/industry` registry
の未着手 `:spec` スロットから複数件を選んで並列で actor を新設した(本ADRは
その1件)。ISIC Rev.4 6209「Other information technology and computer
service activities」は n.e.c. の広いコードであり、`cloud-itonami-isic-6311`/
`isic-4610`/`isic-8299` と同じ narrowing の作法で、**IT
マネージドサービス/ヘルプデスクのチケットルーティング**へ具体的に絞った。

## Decision

新規 actor `cloud-itonami-isic-6209` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した。クライアントの IT インシデントを
トリアージし、契約済みの technician プールへ access-tier とセキュリティ
インシデント対応認定に基づいて割り当てるサービス。

### TicketRouter-LLM ⊣ TicketGovernor(単一不変条件)

> **TicketRouter-LLM は、TicketGovernor が拒否するルーティング確定・
> 開示・紛争解決を決して行わない。**

8チェック(HARD: rbac・**access-tier-clearance-gate**・
**security-incident-misrouting-gate**・source-provenance-gate・
licensed-disclosure、SOFT: 確信度フロア・sla-breach-imminent gate・
dispute-request 無条件)。

`access-tier-clearance-gate`(NIST SP 800-53 AC-6 least-privilege 準拠の
3段階 tier スケール)と `security-incident-misrouting-gate`(GIAC GCIH・
CISSP・CHFI の実在3認定からなる閉じたカタログ)は他の cloud-itonami actor
に存在しない domain-unique HARD チェック。決済は一切扱わず、雇用主責任も
負わない(`cloud-itonami-isic-7820` の派遣モデルとは明確に異なる)。
`default-phase` は実装当初から保守的な `1` を採用。

## Consequences

- (+) `kotoba-lang/industry` registry の 6209 スロットが実装へ昇格。
  narrowing の判断を ADR に明記。
- (+) `clojure -M:dev:test`: 26 tests / 82 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも8シナリオ
  全て正しく発火。
- (-) R0 認定カタログは3種のみ(GIAC GCIH/CISSP/CHFI)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ。
  `cloud-itonami-isic-6209` は standalone、plain-git 子リポとして
  `manifest/repos.edn` には登録しない。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6209/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(フリート標準パターンの手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "6209" エントリ)
