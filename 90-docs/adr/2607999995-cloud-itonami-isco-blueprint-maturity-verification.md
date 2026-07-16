---
id: adr-2607999995-cloud-itonami-isco-blueprint-maturity-verification
title: "ADR-2607999995: cloud-itonami ISCO occupation registry の5件の :blueprint ホールドアウトを検証する"
status: accepted
date: 2026-07-16
deciders:
  - Jun Kawasaki（/loop 15min「成熟度を向上」継続。ISIC側（kotoba-lang/industry）の
    唯一の:blueprintホールドアウト（isic-6611-cryptoexchange）を検証・昇格させた後、
    ISCO側（kotoba-lang/occupation、436 unit-group、211:implemented/220:spec/
    5:blueprint）にも同型のホールドアウトが5件残っていることを発見）
related:
  - 90-docs/adr/2607999990-cloud-itonami-isic-6611-cryptoexchange-maturity-verification.md（同型の検証規律の直接の踏襲元）
  - 90-docs/adr/2607141920-cloud-itonami-isic-0710-iron-ore-mining-coverage.md（"必ず実テストを実行"教訓の原典）
supersedes: []
superseded_by: []
last_verified: 2026-07-16
doc_type: adr
topic: cloud-itonami-occupation-actor-maturity
authoritative: true
authoritative_for:
  - "cloud-itonami-isco-{3331,5419,8343,9321,9329} の :maturity 判定は、実際に
    依存解決してテストスイートを実走した結果のみを根拠とする、という検証規律の正本
    （ISIC側ADR-2607999990と同型）"
---

# ADR-2607999995: cloud-itonami ISCO occupation registry の5件の :blueprint ホールドアウトを検証する

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki

## Context

`kotoba-lang/occupation`の`resources/kotoba/occupation/registry.edn`（ISCO-08
436 unit-group、`:kotoba.registry/note`に "436/436" full coverage・
"Most entries are :maturity :spec... curated entries have a published
cloud-itonami-isco-{code} blueprint repo" と明記）を確認したところ、
211 `:implemented` / 220 `:spec` / **5 `:blueprint`**という分布だった。
ISIC側（`kotoba-lang/industry`）で先行実施したisic-6611-cryptoexchangeの
検証（ADR-2607999990）と同型のホールドアウト解消作業をISCO側にも適用する。

対象5件:
- isco-3331 Clearing and Forwarding Agents
- isco-5419 Protective Services Workers Not Elsewhere Classified
- isco-8343 Crane, Hoist and Related Plant Operators
- isco-9321 Hand Packers
- isco-9329 Manufacturing Labourers Not Elsewhere Classified

**registry.edn構造上の注意**: `kotoba-lang/occupation`のregistry.ednは
`kotoba-lang/industry`のregistry.ednと異なり、トップレベルが1要素のvectorで、
その中の`:kotoba.occupation/occupations`キーの値が**EDN文字列としてエンコード
された**occupationsのvector（二重エンコード）である。安全な更新には
outer EDNをparse→`:kotoba.occupation/occupations`の文字列値を別途parse→
該当entryを編集→内側vectorを再度EDN文字列化→outerに埋め戻す、という手順が
必要。

## Decision

isic-6611-cryptoexchange検証（ADR-2607999990）と同じ規律を5件全てに適用する:

1. **推測で昇格させない。** 各occupationリポジトリについて、実際に依存解決
   できるisolated環境を構築し、`clojure -M:test`（または実際のalias名）を
   実行、実測結果のみを根拠にする。
2. **green ならば**: `kotoba-lang/occupation`のregistry.edn（上記の二重
   エンコード構造に注意した安全な更新手順で）を`:implemented`へ昇格。
   `kotoba-lang/occupation`自身のtest/industry_test.clj相当（もしあれば）
   のcorroboration assertionも、ISIC側の先例同様、追従更新する。
3. **red、または依存解決不可能ならば**: 昇格させず、具体的な原因を報告する
   だけに留める（コードは直さない、follow-up判断は別途）。
4. **5件は互いに独立**——並行して検証してよい。
5. registryへの書き込みは、書き込み直前に現HEADを再取得し、並行fleetとの
   衝突を検出してから行う（既存の安全な単一entry更新規律を踏襲）。

## Consequences

(+) ISCO側のregistry全体の`:blueprint`ホールドアウトが実測に基づいて整理される
（ISIC側同様、registry全体で唯一残っていたホールドアウト種別が解消される）。
(−) 5件同時に検証するため、registry.edn書き込みの衝突リスクはISIC側の
単発検証より高い——直前再取得での検出に頼る。

## References

- https://github.com/kotoba-lang/occupation
- https://github.com/cloud-itonami/cloud-itonami-isco-3331
- https://github.com/cloud-itonami/cloud-itonami-isco-5419
- https://github.com/cloud-itonami/cloud-itonami-isco-8343
- https://github.com/cloud-itonami/cloud-itonami-isco-9321
- https://github.com/cloud-itonami/cloud-itonami-isco-9329
