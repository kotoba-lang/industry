# ADR-2607141600: cloud-itonami — 自社(gftdcojp)の実組織活動・identity 記録の置き場所

**Status**: accepted（activity 層は既存実装の再確認、identity 層は設計のみ・repo 作成は実 LEI 取得までブロック）
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki（+ Claude、オーナー承認のうえ設計）
**Scope**: `orgs/gftdcojp/cloud-itonami`（既存）、`orgs/cloud-itonami/*`（将来の self-entity identity repo、未作成）

## Context

セッションでの質問: `etzhayyim`(宗教活動) / `cloud-itonami`(business 活動) /
`kotoba-lang`(OSS) の3 org 構成で、**実際の組織の情報や活動**を記録していくなら
どこに保持するのがいいか。

調査の結果、関連する決定が独立に2つ既に存在していた。

1. **ADR-2606271700**（`cloud-itonami business OS`、closed）: `orgs/gftdcojp/
   cloud-itonami` は `manimani` の企業版として設計・実装済みで、
   `:itonami.activity/*` `:itonami.decision/*` `:itonami.effect/*`
   `:itonami.audit/*` の語彙を持つ append-only ledger + agent loop +
   approval workflow を備えた実際の business activity operating system。
   M365 archive（mail/calendar/contract/invoice/CRM/HR/SES 等）を実データとして
   取り込み済み（`clojure -M:test`: 44 tests/190 assertions green、実データ
   `activities=10938〜33423` 規模の import 検証済み）。**これが「gftdcojp の
   実活動記録」の正本として既に存在している。**
2. **ADR-2607110300**（`cloud-itonami-lei-corporate-tos-catalog`、accepted）:
   実在する第三者上場企業の Terms of Service を `cloud-itonami-lei-<LEIコード>`
   （LEI = ISO 17442, GLEIF 発行）という命名で `cloud-itonami` org 直下に
   1社=1リポジトリでアーカイブする決定。同 ADR の org 選定ロジックは
   「`etzhayyim` は非営利・自己主権の宗教法人レイヤーで商業分析の対象外、
   `kotoba-lang` は言語/インフラ層で企業コンテンツの置き場ではない、
   `cloud-itonami` を採用」——今回問うている問いと**全く同じ理由構造**で
   既に一度 owner 判断済みだった。

つまり両 ADR は独立に同じ結論（`cloud-itonami`）に達しているが、(1) は
**自社の活動ログ**（private、既存実装）、(2) は**第三者企業の**
identity/契約カタログ（public）であり、「**自社(gftdcojp)自身の**
法人 identity 記録」（LEI・法人名・法域・web 等）は両者のどちらにも
属さない空白として残っていた。

## Decision

`cloud-itonami` を実際の組織情報・活動記録の統一的な置き場所とし、
可視性の異なる 2 層に明確に分離して運用する。

### 1. 活動層（private、既存、変更なし）

`orgs/gftdcojp/cloud-itonami` — ADR-2606271700 で実装済みの
`:itonami.activity/decision/effect/audit` ストアを正本として使い続ける。
日次の実 business activity（mail/CRM/契約/請求/HR/SES/経営判断）は
このリポジトリに記録され続ける。本 ADR はこれを**再確認するのみ**で、
移設・スキーマ変更は行わない。

### 2. identity 層（public、新設予定・現時点はブロック）

gftdcojp 自身の法人 identity 記録は、ADR-2607110300 が第三者向けに
確立した **`cloud-itonami-lei-<LEIコード>` と同一のスキーマ**
（`:company/legal-name` `:company/lei` `:company/jurisdiction`
`:company/website` `:company/ticker` + `80-data/public/*.journal.edn`
provenance パターン）を自社にもそのまま適用する。ただし:

- 本セッション時点で gftdcojp の実 LEI はこのワークスペースに存在しない
  （リポジトリ内・manifest・ADR 全域を検索し確認済み、2026-07-14）。
- ADR-2607110300 自身の規律「LEI は GLEIF 公式レジストリからのみ引用し
  捏造禁止」は自社に対しても当然適用される。したがって
  **実際の LEI 取得（GLEIF 認定 LOU への申請、KYC、費用）という
  実世界の法人手続きが先に必要**であり、これは agent が代行できる
  範囲外（CLAUDE.md の標準作業常時許可はコード/リポジトリ操作の範囲であり、
  実世界の法人登録行為はその対象外）。
- 本 ADR では **identity repo の設計を確定するのみ**とし、リポジトリ
  作成自体は LEI 取得後の follow-up として残す（ADR-2607110300 が
  「pilot 企業未選定」を未決事項として残した前例と同型）。
- 命名は取得後 `cloud-itonami-lei-<gftdcojpの実LEIコード小文字>` を
  第一候補とする。GTIN・ticker 等の代替キーを採らない理由は
  ADR-2607110300 の Alternatives と同一。

### 3. etzhayyim / kotoba-lang の除外

ADR-2607110300 が既に確立した除外理由をそのまま踏襲する:
`etzhayyim` は非営利・自己主権の宗教法人レイヤーで商業的な実体記録の
管轄外、`kotoba-lang` は言語/インフラ層で企業コンテンツの置き場ではない。
（`etzhayyim` 自身の宗教活動の実組織記録は `orgs/etzhayyim/root`——
`COUNCIL.md`/`MEMBERS.md`/`CHARTER-RIDER.md`/`90-docs`/`_observations`——
が既に正本として機能しており、本 ADR のスコープ外。）

## Consequences

- (+) 「実際の組織情報・活動をどこに置くか」という問いに、自社活動
  （`gftdcojp/cloud-itonami`）/ 自社 identity（`cloud-itonami-lei-*`、
  取得後）/ 第三者 identity（既存 `cloud-itonami-lei-*`）/ 宗教
  （`etzhayyim/root`）/ OSS（各 kotoba-lang repo + 横断 `90-docs/adr`）の
  全象限に一貫した答えが揃う。
- (+) 既存 2 ADR（2606271700・2607110300）を接合するだけで済み、新しい
  構造原理を発明していない。
- (+) activity（private）と identity（public）を**別リポジトリ・別可視性**
  に厳密分離するため、private な活動データが誤って public identity
  archive に混入するリスクを構造的に排除する。
- (−) 自社 identity repo は実 LEI 未取得のため未着手のまま
  ブロックされる。owner（Jun）が GLEIF 認定 LOU 経由で gftdcojp の
  LEI を実際に取得するまで、agent 側でできることはない。
- (−) LEI 取得後も、identity repo を public にするかどうかは
  ADR-2607110300 の第三者カタログが public である慣例に合わせるのが
  既定だが、自社の場合は法人名・法域・web の公開が実務上妥当かを
  取得時に改めて確認する（既定は public 継続、変更する場合は
  本 ADR への addendum で記録する）。

## Alternatives considered

- **etzhayyim または kotoba-lang への配置** — 却下。理由は
  ADR-2607110300 と同型（宗教/インフラ層は商業実体記録の管轄外）。
- **自社 LEI を GLEIF 確認なしに仮値で先行登録** — 却下。
  ADR-2607110300 自身の「捏造禁止」規律に反する。
- **`orgs/gftdcojp/cloud-itonami` 内に identity データも同居** — 却下。
  activity と identity は可視性ポリシーが逆（private vs public）であり、
  同居させると private データを public repo へ誤って commit するリスクが
  生まれる。既存の `cloud-itonami-lei-*` 群も actuation actor
  （`gftdcojp/cloud-itonami`）とは分離された read-only archive であり、
  この分離を自社にも踏襲する。
- **`orgs/etzhayyim/root` を模した `cloud-itonami-root` を新設し
  そこに identity も activity ログの pointer も置く** — 検討したが
  見送り。activity 正本は既に `gftdcojp/cloud-itonami` として実装済みで
  移設の実益がなく、identity は LEI キー方式が既に確立済みのため、
  第三の「root」構造を追加すると 3 つ目の置き場が生まれてしまい
  本 ADR が解決しようとした「置き場が分散する」問題を悪化させる。

## References

- ADR-2606271700（`cloud-itonami business OS`、closed — 活動層の正本）
- ADR-2607110300（`cloud-itonami-lei-corporate-tos-catalog` — identity 層の
  スキーマ・org 選定ロジックの初出）
- ADR-2607031500（`cloud-itonami-M6910` — LEI/ISO-7064-MOD-97-10 実装の出典、
  `matsurigoto`/`etzhayyim/root` 由来）
- ADR-2607121000（`cloud-itonami` 全世界展開 5-wave rollout 計画）
