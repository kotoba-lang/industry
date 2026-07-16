# ADR-2607031500: cloud-itonami-M6910 — 全世界の法人設立代行を実行する actor を独立 open business blueprint として新設

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

「全世界どこでも実際に法人設立を代行実行する actor」を新設したいという要望が
あった。現状の関連 actor を調査した結果、以下が判明した:

- **etzhayyim の `matsurigoto`(政)の `corp-registry` module** — ISO 17442 LEI
  発行 + 追記型 registry record の R0 参照実装を持つが、G1(no-operator-master-key)
  / G3(`:operated-by` ∈ `:etzhayyim-council` \| `:adopting-government`)という
  憲法的制約があり、principal は「etzhayyim 信徒向け自己統治」か「他国政府への
  譲渡」のいずれかに限定される。不特定多数の一般顧客向け商用代行という
  principal は構造的に想定されていない。
- **`legal-entity.etzhayyim.com`** — 194+カ国の**既存**法人データ収集/統合のみ。
  登記の執行は行わない読み取り専用データソース。
- **`ooyake`(公)の civic wayfinding map** — 各国の法人登記手続きの「どこで・
  何が必要か」を案内するのみ。読み取り専用、実行なし。
- **`cloud-itonami` 本体**（gftdcojp、private） — gftdcojp *自社* の
  business-activity オペレーション基盤（inbox/sales/contract/billing/plm/erp/
  mes/keiei lane）。顧客の KYC/custody/settlement を扱う規制対応サービスとは
  設計前提が異なる。
- **`cloud-itonami-{ISIC}` open business blueprint 系列**（35 repo、`cloud-itonami`
  org、public、AGPL-3.0、ADR-2607011000/2607012000/2607012100） — 「誰でも
  fork して独立事業を始められる」forkable OSS business blueprint。ただし
  ADR-2607011000 により全 vertical に robotics premise（物理領域作業を robot
  が行い、governor が gate する）が課されている。この中に `cloud-itonami-6310`
  (`gftd-talent-actor`) があり、**HR-LLM を PolicyGovernor で封じ込めた actor**
  自体が同時に open business blueprint としても発行されている実例だった。

「全世界どこでも実際に法人設立を代行実行する」は KYC で顧客の本人確認書類を
預かり、政府へ実提出し、手数料決済を行う **liability・custody・settlement を
伴う規制対応の商用サービス**であり、上記のどの既存 actor にもそのままでは
収まらない。しかし `cloud-itonami-6310` の実例（「actor であり、同時に
forkable SaaS blueprint でもある」）が、liability を単一 vendor に集中させず
「OSS actor を各法域の licensed operator が自己運用する」という解を示していた。

## Decision

新規 actor `cloud-itonami-M6910`（ISIC Rev.5 6910, Legal activities）を、
**`cloud-itonami-6310` / `ai-gftd-itonami` / `robotaxi-actor` と同型の
「封じ込め + 独立 governor + 不変台帳」actor パターン**で実装し、最初から
`cloud-itonami` org 直下に public/AGPL-3.0 の open business blueprint として
発行する（ADR-2607012100 の「新規 repo は transfer を経ず直接 `cloud-itonami`
org 直下に作成してよい」という慣行に従う）。

- **Registrar-LLM ⊣ RegistrarGovernor**: LLM は intake 正規化・法域要件
  チェックリスト・KYC/制裁スクリーニング・filing 提案の4種類の proposal のみを
  返す。RegistrarGovernor が spec-basis(G2 style)・sanctions-hit・
  document-complete（HARD、人間による上書き不可）+ confidence-floor・
  actuation-gate（SOFT）の5チェックを行う。
- **実アクチュエーションは governor と phase の2層で構造的に常に人間専用**:
  `:filing/submit`（実際の政府提出・実際の手数料送金）はどのフェーズの
  `:auto` 集合にも含まれず、governor の actuation gate も常に escalate する。
  片方の実装ミスをもう片方が吸収する二重の設計。
- **LEI/registry の spec 数学は matsurigoto から移植**: `formation.registry`
  は `matsurigoto`(etzhayyim/root)の corp-registry モジュールが実装した
  ISO 17442 LEI + ISO 7064 MOD 97-10 のロジックをそのまま移植した。principal
  は分離する（matsurigoto=政体自身の統治、この actor=licensed operator の
  支援）。
- **liability の非集中化**: このソフトウェアは特定の法域の登録代理人免許を
  代替しない。実運用（gftdcojp 自身か、各法域の免許を持つ第三者 registered
  agent か）は operator の選択であり、operator が各法域の liability を負う。
- **robotics premise は対象外**: 会社設立代行は物理領域作業を伴わないため、
  `cloud-itonami-6310`（HR SaaS）が robotics retrofit の対象外だった先例に
  倣う。

R0 スコープ（正直な現状）: 10法域（JPN/USA-DE/GBR/DEU/EST/KOR/IND/SGP/NZL/CAN）
のみ spec-basis を持つ（`formation.facts/coverage` で常に正直に報告、
~194法域中の一部にすぎない）。Store は `MemStore` のみ（Datomic/kotoba-server
backend は次のシームとして用意されているが未実装）。25 tests / 111
assertions、lint clean、`clojure -M:dev:run` で intake→assess→screen→filing
承認→登記ドラフト(有効な LEI 付き)の一連の流れと、制裁ヒット/法域要件捏造の
2つの HARD hold ケースを確認済み。

## Consequences

- (+) 「全世界どこでも」というスケール要求に、単一 vendor への liability
  集中を避けつつ応える構造ができた。
- (+) 実アクチュエーション不変条件（`:filing/submit` は常に人間専用）は
  `test/formation/phase_test.clj` の `filing-submit-never-auto-at-any-phase`
  でリグレッションを機械的に検出できる。
- (+) matsurigoto の LEI/MOD-97-10 実装を再利用（車輪の再発明をしない）。
- (-) spec-basis は10法域のみ。拡張は `formation.facts/catalog` への追記
  （必ず公式ソースを引用、捏造禁止）。
- (-) Datomic/kotoba-server backend 未接続、実 KYC/制裁スクリーニング
  プロバイダ・実政府ポータル・実決済統合は operator の責任範囲として
  スコープ外。
- superproject への反映: 本 ADR のみ（`cloud-itonami-M6910` は既存の
  `cloud-itonami-{ISIC}` blueprint 群と同じ慣例により `manifest/repos.edn` /
  `manifest/west.yml` には登録しない = standalone）。

## References

- `orgs/cloud-itonami/cloud-itonami-M6910/README.md` + `docs/DESIGN.md` +
  `docs/adr/0001-architecture.md`（実装側 ADR、本 ADR と対）
- `matsurigoto` corp-registry module（etzhayyim/root, ADR-2606062300）
- `90-docs/adr/2607011000-cloud-itonami-robotics-premise-and-isic-21-21.md`
- `90-docs/adr/2607012100-cloud-itonami-org-split.md`
- `90-docs/adr/2606271700-cloud-itonami-business-os.md`
- `cloud-itonami-6310`（旧 `gftd-talent-actor`）ADR-0001 -- 同型の actor パターン
