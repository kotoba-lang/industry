# ADR-2607114772: cloud-itonami-isic-4772 — 医薬品・医療品小売(Pharmacy Retail)を PharmacyOrder-LLM ⊣ PharmacyGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-6311`(market-data actor)・`cloud-itonami-isic-7820`
(温staffing actor)に続き、`kotoba-lang/industry` registry の未着手 `:spec`
スロットから次の対象を選定した。ISIC Rev.4 4772「Retail sale of
pharmaceutical and medical goods, cosmetic and toilet articles」は、
オンライン/実店舗薬局の調剤・OTC医薬品販売業態であり、実定法上の制約
(処方箋要件・麻薬取締スケジュール・規制対象OTCの年齢/数量制限)が極めて
明確な、フリート内でも屈指の「なぜLLMに直接やらせてはいけないか」の
説得力を持つドメインである。死んだ `gftdcojp/cloud-itonami-G4772`
プレースホルダー URL のまま `:spec` で放置されていたスロットである。

## Decision

新規 actor `cloud-itonami-isic-4772`(ISIC Rev.4 4772)を `cloud-itonami`
org 直下に public/AGPL-3.0-or-later で新設する。`cloud-itonami-isic-6311`
(収集・保持・契約者限定開示パターン)を直接の手本としつつ、薬局固有の
リスク面(無資格調剤・麻薬取締法制違反・未成年への規制品販売・薬物相互
作用の見落とし)に対応する新規 HARD チェックを2つ追加した。

### 1. PharmacyOrder-LLM ⊣ PharmacyGovernor(単一不変条件)

> **PharmacyOrder-LLM は、PharmacyGovernor が拒否する調剤・リフィル・
> 開示・紛争解決を決して行わない。**

8チェック(HARD5 + SOFT3):

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role(pharmacist/otc-clerk/subscriber)が operation の権限を持つか |
| 2 | prescription-verification-gate | HARD | 処方箋の存在・検証済み・未期限・(リフィルのみ)残数を確認 |
| 3 | **restricted-quantity-gate**(新規、業態固有) | HARD | 品目のper-fill上限、Schedule II のリフィル禁止(処方箋レコードの残数値に関わらず構造的に強制する defense-in-depth)、規制対象OTC(疑似エフェドリン等)の年齢/数量制限 |
| 4 | source-provenance-gate | HARD | 出典クラス + `:licensed-erx-network` の場合はアクティブな network 要求 |
| 5 | licensed-disclosure | HARD | 契約 tier 超過列の拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | **interaction-flag gate**(新規、業態固有) | SOFT | 患者アレルギー×品目相互作用のフラグが立てば必ず薬剤師承認 |
| 8 | dispute requests | SOFT(無条件) | 紛争申立ては常に人間レビュー、どの phase でも auto 化しない |

### 2. `:rx/dispense`/`:rx/refill` は Phase 3 でも auto 化しない(構造的恒久ゲート)

`cloud-itonami-isic-6311`/`isic-7820` の「governor-clean なら Phase 3 で
auto-commit 可能」パターンを部分的に踏襲しつつ、Rx調剤に限っては governor
が完全にクリーンであっても**構造的に**人間(薬剤師)承認を要求する —
処方箋薬の最終確認は薬剤師の法的義務であり、rollout の成熟度で解除できる
性質のものではないという判断。OTC(規制なし品目)のみ Phase 3 で
auto-commit 可能。

### 3. default-phase = 1 を初期実装から採用

`cloud-itonami-isic-7820` で確立された fail-open 対策(`:phase` を省略
した呼び出し元は最も保守的な phase を得る)を、後から修正するのではなく
最初から正しい設計として採用した。

### 4. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/pharmacy/facts.cljc`)は実在する3つの自由・公式参照
ソース(FDA National Drug Code Directory、DEA Controlled Substance
Schedules、NPPES NPI Registry)+ 1つの構造的クラス
`:licensed-erx-network`。処方箋の実在性そのものは、operator が自前の
ライセンス済み e-prescribing/PDMP ネットワークを `erx-network` レコード
として登録して初めて取込可能 — 無料の公式ソースを偽装しない。

### 5. Robotics premise: false

調剤可否判断と記録のみを行う actor であり、注文執行・配送・決済を一切
含まない。

## Consequences

- (+) `kotoba-lang/industry` registry の 4772 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-G4772` URL)から実装へ昇格。
- (+) prescription-verification-gate・restricted-quantity-gate・
  interaction-flag gate という、他の cloud-itonami actor に存在しない
  薬局固有のチェックを新設した。
- (+) `clojure -M:dev:test`: 39 tests / 138 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(10シナリオ全て正しく発火)。
- (+) 実装過程で DatomicStore の実バグを発見・修正: 訂正パッチが
  Datomic 側の固定スキーマに無い任意フィールドだと MemStore では通るが
  Datomic では黙って消えることが判明し、既存スキーマフィールド
  (`:verified?`)を使う設計に修正した — この教訓は他 dossier 系 actor の
  correction-apply パターンにも一般化しうる。
- (-) R0 の自由公式ソースは3種のみ。処方箋の実在性は operator の
  erx-network 登録が必須。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ。
  pin は本 ADR 起票時点で既に別セッションにより API 直接編集で前進済み
  (`manifest/west.yml` の `industry` entry、`nbb` 移行版
  `gen-west-manifest.cljs` が `clojure.java.shell` 名前空間欠如で一時的に
  動作不能なため — 修正は別途トラッキング中)。`cloud-itonami-isic-4772`
  は既存の `cloud-itonami-{ISIC}` blueprint 群と同じ慣例により
  `manifest/repos.edn` には登録しない(standalone、plain-git 子リポ)。

## 代替案と不採用理由

- **Rx調剤も Phase 3 で auto-commit 可能にする**: governor-clean なら
  安全という前提は、薬剤師の法的最終確認義務という別レイヤーの要件を
  見落とす。構造的に禁止するのが正確。
- **restricted-quantity-gate を SOFT にとどめる**: 麻薬取締法制違反や
  未成年への規制品販売は高確信のまま起こりうるため、SOFT(確信度フロア
  依存)では防げない。HARD が必須と判断した。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4772/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
  (収集・保持・契約者限定開示パターンの直接の手本)
- `90-docs/adr/2607112900-cloud-itonami-isic-7820-temp-staffing-actor.md`
  (default-phase fail-open 対策の直接の手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4772" エントリ)
