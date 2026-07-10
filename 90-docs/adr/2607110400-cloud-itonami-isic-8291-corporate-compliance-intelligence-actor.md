# ADR-2607110400: cloud-itonami-isic-8291 — 全世界の企業・役職者(職務上)・関係性を Dossier-LLM ⊣ DisclosureGovernor で保持し契約者限定開示する corporate/compliance intelligence actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

「全世界の企業・公人・政府・組織図・連絡先・人間関係を分析する actor」の要否を
問われ、既存 actor を横断調査した結果、該当するものは存在しないと判明した
(近傍だが別目的: `cloud-itonami-M6910`=法人設立の**執行**、`cloud-itonami-6310`
(旧 `gftd-talent-actor`)=**自社**従業員のみ、`ai-gftd-arms` の
`global-satellite-intelligence-actors.edn`=衛星OSINT・軍事施設/船舶検出限定
で個人の標的化は `:safety-boundary` により明示的にスコープ外、`kakure`=個人
データ露出の**削除**で逆方向)。

オーナーから「Palantir のように内部データとして保持し、cloud-itonami の産業
SaaS ごとに情報を保持して契約した user のみに開示する」という業態方針が示され、
これは実世界の **credit bureau / business-information-services**
(Dun & Bradstreet, Moody's Orbis(BvD), Refinitiv World-Check, LexisNexis Risk
Solutions 等)と同型の SaaS であることを確認した。`kotoba-lang/industry` の
registry (`resources/kotoba/industry/registry.edn`) を調べたところ、**ISIC
Rev.4 8291「Activities of collection agencies and credit bureaus」が
`:maturity :spec`(registry のみ、repo 未着手)で既に登録済み**であり、
`cloud-itonami-M6910`(6910 spec→実装)と同型の「未着手スロットを実装に
昇格させる」前例に倣うのが最も筋が良いと判断した。

情報スコープについてはオーナーに確認を取り、**「職務上の公開情報のみ」**
(法人登記・役員/取締役/UBO(公開分のみ)・政府機関の職位/役職者名・M&A/JV等の
公表済み取引関係・法人としての連絡先)を採用し、**私生活・家族関係・思想信条・
所在地追跡・SNS由来の推論プロファイル等は対象外**と決定した。この境界は
D&B/Moody's Orbis/World-Check という実在業態の適法性の根拠そのものであり、
`ai-gftd-arms` の `:safety-boundary`(標的選定・攻撃計画は対象外)や `kakure`
の PII ルール(私生活データを持たない)と同じ設計思想を踏襲する。

## Decision

新規 actor `cloud-itonami-isic-8291`(ISIC Rev.4 8291)を、`cloud-itonami-6310`
/ `cloud-itonami-M6910` / `robotaxi-actor` と同型の **「封じ込め + 独立
governor + 不変台帳」actor パターン**で実装し、`cloud-itonami` org 直下に
public/AGPL-3.0-or-later の open business blueprint として新設する(ADR-2607012100
の「新規 repo は直接 `cloud-itonami` org 直下に作成してよい」慣行に従う。
命名は ADR-2607051300 follow-up で確定した `cloud-itonami-isic-{code}` 表記を
最初から採用し、旧 `cloud-itonami-{code}` 表記は経由しない)。

### 1. Dossier-LLM ⊣ DisclosureGovernor(単一不変条件)

> **Dossier-LLM は、DisclosureGovernor が拒否する事実の書き込み・開示・
> 訂正確定を決して行わない。**

Dossier-LLM は以下 4 種の *proposal のみ* を返す助言者: エンティティ名寄せ
(entity-resolution)、関係性ドラフト(relationship-draft、出典引用必須)、
契約 tier 別開示列セット提案、訂正申立てへの解決案ドラフト。実書き込み・
実開示は独立した DisclosureGovernor の6チェックを通ってから台帳に commit
する(`cloud-itonami-6310` の PolicyGovernor、`cloud-itonami-M6910` の
RegistrarGovernor と同型)。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | scope-gate | HARD | proposal が私生活/家族/思想信条/健康/性的指向/リアルタイム所在地 等のスキーマ外フィールドを含まないか(スキーマ自体にこれらのフィールドが存在しない = 構造的除外) |
| 2 | source-basis | HARD | 全ての事実/関係edgeが許可された出典クラス(公式法人登記・裁判記録・規制当局提出書類・ライセンス済みデータフィード・当事者自身のプレスリリース)を引用しているか。無出典・推論のみは拒否 |
| 3 | licensed-disclosure | HARD | `:disclosure/query` は有効な契約(tenant×tier×purpose)を持つ呼び出し元にのみ結果を返す。匿名/公開の問い合わせ経路は operation セットに一切存在しない |
| 4 | confidence-floor | SOFT→escalate | 名寄せ/関係性推論の確信度が閾値未満 → 人間レビューへ |
| 5 | high-stakes gate | SOFT→escalate | 対象が政府職員(職務上)・制裁/PEPフラグ付きの場合は常に人間承認へ(名誉毀損・政治的リスクの非対称性) |
| 6 | correction-request | HARD(常時 escalate) | データ主体(法人または職務上の役職者)からの訂正申立ては、どのフェーズでも自動解決を許さない(FCRA型のdispute権を構造で保証) |

### 2. Phase 0→3 + 恒久人間ゲート

`cloud-itonami-6310`/`M6910` と同じ段階導入。ただし **`:correction/request`
と、政府職員/制裁フラグ関連の `:disclosure/query`・`:relationship/draft` は
どの phase の `:auto` 集合にも入らない**構造的恒久ゲート(`M6910` の
`:filing/submit` 恒久人間専用と同型の実装不変条件)。

### 3. データモデル(職務上公開情報のみ、構造的スコープ)

`:entity/company`(法人登記事実)・`:entity/official`(氏名・役職・所属法人or
政府機関・capacity=officer|director|ubo|government-official、**職務上のみ**)・
`:entity/government-agency`・`:relationship/edge`(ownership|directorship|
employment|joint-venture|regulatory-oversight|business-contact、出典・時点
必須)・`:contact/business`(登記住所・IR・広報等の法人としての連絡先のみ)。
私生活フィールドはスキーマに存在しない。

### 4. R0 の正直なスコープ

`M6910` の「10法域のみ spec-basis」に倣い、R0 の出典カタログは実在する
無料/公開の一次情報源のみを載せる: 日本(法人番号公表サイト)、UK(Companies
House、officers/PSC 無料API)、Germany(Unternehmensregister)、Estonia
(e-Business Register)、USA(SEC EDGAR、上場企業のみ)、EU consolidated
financial sanctions list(制裁/PEPフラグの出典 — これ自体が政府公表データ
であり private surveillance ではない)。Store は `MemStore`/`DatomicStore`
(`langchain.db` 経由、`cloud-itonami-6310` と同じ swap 可能設計)。

### 5. cloud-itonami 産業SaaS群への卸し(オーナーの業態方針の実装)

`cloud-itonami-isic-8291` 自体を直接契約者へ販売するのに加え、他の
`cloud-itonami-{ISIC}` blueprint(175+ fleet)は本 actor を `:required-
technologies`/`:optional-technologies` の新規 capability keyword
`:corporate-intelligence` として宣言的に消費できる(`:identity`/`:audit-
ledger` が universal required-technology として全blueprintから参照される
のと同じ、ADR-2607051300 の vertical-capability-lib パターン)。各業種
blueprintが自前でデータを持たず、同じ DisclosureGovernor ゲート越しに
licensed 契約を通じて問い合わせる — 「産業SaaSごとに情報を保持し契約者限定
開示」というオーナー方針をそのまま構造化する。本ADRでは capability keyword
の宣言のみ行い、175 blueprintへの一斉配線は別ADRのフォローアップとする。

## Consequences

- (+) `kotoba-lang/industry` registry の 8291 スロットが `:spec` から実装へ
  昇格し、`M6910` に続く2件目の「昇格実例」になる。
- (+) 「保持は自由・開示は契約者限定」という Palantir 型の業態が、
  `cloud-itonami-6310`/`M6910` と同じ「封じ込め+独立governor+不変台帳」actor
  パターンの中に無理なく収まることを示した(新しいパターンの発明は不要だった)。
  disclosure=最大のリスク面という認識のもと、governor 名を Policy→Disclosure
  に、単一不変条件の対象を「書き込み」から「書き込み・開示・訂正確定」の3つに
  拡張した。
- (+) 私生活/家族/思想信条等の除外はスキーマレベル(存在しないフィールド)
  であり、実行時チェックだけに頼らない — `cloud-itonami-6310` の
  `protected-attrs` runtime チェックより一段厳格。
- (-) R0 の出典カバレッジは6ソースのみ(全世界のごく一部)。`M6910` と同じ
  正直な報告方針を採り、`facts/catalog` への追記でのみ拡大する(捏造禁止)。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の与信/
  制裁データベンダー統合・実法人登記APIとの実結線は operator の責任範囲。
- (-) 175 blueprintへの `:corporate-intelligence` capability 配線は本ADRの
  スコープ外(フォローアップ)。
- superproject への反映: 本 ADR のみ。`cloud-itonami-isic-8291` は既存の
  `cloud-itonami-{ISIC}` blueprint 群と同じ慣例により `manifest/repos.edn` /
  `manifest/west.yml` には登録しない(standalone)。`kotoba-lang/industry`
  registry.edn の 8291 エントリのみ実 repo URL へ更新する。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8291/README.md` + `docs/DESIGN.md` +
  `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607031500-cloud-itonami-m6910-global-incorporation-actor.md`
  (spec→実装昇格・R0正直スコープの先例)
- `cloud-itonami-6310`(旧 `gftd-talent-actor`)ADR-0001(封じ込め+governor+
  台帳パターンの原型)
- `90-docs/adr/2607051300-cross-org-capability-map.edn`(vertical-capability-lib
  パターン、`:required-technology-index`)
- `90-docs/adr/2606081510-arms-public-defense-industrial-and-satellite-osint-data.md`
  (`:safety-boundary` パターンの参照元)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "8291"
  エントリ)
