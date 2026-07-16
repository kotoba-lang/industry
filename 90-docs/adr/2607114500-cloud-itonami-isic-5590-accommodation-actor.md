# ADR-2607114500: cloud-itonami-isic-5590 — 代替宿泊予約(Other Accommodation)を StayBooking-LLM ⊣ StayGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`kotoba-lang/industry` registry の未着手 `:spec` スロットから対象を選定
した。ISIC Rev.4 5590「Other accommodation」は、ホテル業(5510)や
Airbnb型の丸ごと貸し(通常別コード)とは区別される、ホステル/ゲストハウス/
キャンプキャビン/寮といった代替宿泊業態を指す。死んだ
`gftdcojp/cloud-itonami-I5590` プレースホルダー URL のまま `:spec` で
放置されていたスロットである。

## Decision

新規 actor `cloud-itonami-isic-5590`(ISIC Rev.4 5590)を `cloud-itonami`
org 直下に public/AGPL-3.0-or-later で新設する。`cloud-itonami-isic-6311`
(市場データ収集・保持・開示パターン)/`cloud-itonami-isic-7820`(default-
phase=1 の恒久保守的既定)を直接の手本としつつ、宿泊予約固有のリスク面
(オーバーブッキング、ライセンス/防火認証失効)に対応する新規 HARD チェック
を2つ追加した。

### 1. StayBooking-LLM ⊣ StayGovernor(単一不変条件)

> **StayBooking-LLM は、StayGovernor が拒否する施設登録(`:property/
> register`)・予約確定(`:booking/place`)・開示(`:report/query`)・紛争
> 解決(`:dispute/request`)を決して行わない。**

8チェック(5 HARD + 3 SOFT):

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | **capacity-overbooking-gate**(新規、業態固有) | HARD | `:booking/place` の新規予約が、同一施設で重複する日程の他の確定予約合計人数と合わせて総capacityを超えたら拒否。半開区間の日程重複判定+人数合計という実算術チェック、他の cloud-itonami actor に存在しない |
| 3 | **license-lapsed-gate**(新規、業態固有) | HARD | 対象施設の `:license-status` が active でない、または `:safety-cert-expiry` が check-in より前なら拒否 |
| 4 | source-provenance-gate | HARD | `:property/register`(license-basis)/`:booking/place`(booking channel)の出典クラスが `stay.facts/allowed-source-classes` に無ければ拒否。`:operator-attested-license` は加えてアクティブな license record を要求 |
| 5 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | guest-flagged gate | SOFT | 対象ゲストが要注意フラグ付き → 必ず人間承認 |
| 8 | dispute-request | SOFT(無条件) | 予約/精算紛争は確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**意図的に無い項目**: 支払処理・返金実行・物理的なドア/キーコード発行に
相当するチェックは存在しない — この actor は施設登録・予約確定・開示・
紛争解決のみを行い、決済執行や物理アクセス発行を一切含まないため。

### 2. default-phase = 1(恒久保守的既定)

`cloud-itonami-isic-6311`/`isic-7820` で確立された規律を新規実装時点から
適用。`:booking/place`/`:dispute/request` は phase 2 で初めて `:writes`
に入り、`:dispute/request` はどの phase の `:auto` 集合にも入らない構造的
恒久ゲート。

### 3. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/stay/facts.cljc`)は実在する2つの statutory 制度
(日本 旅館業法・英国 Fire Safety Order 2005)+ 1つの構造的クラス
`:operator-attested-license`(他法域は operator 登録の実ライセンスのみ
受理)+ 2つの実在 booking channel クラス(direct desk / OTA partner)。

### 4. Robotics premise: false

施設登録・予約管理は書面/システム上の業務であり、actor の境界の外に物理的
な作動(実際の宿泊自体)は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 5590 スロットが実装へ昇格
  (`M6910`・`isic-8291`・`isic-4690`・`isic-4610`・`isic-6311`・
  `isic-7820` 等に続く実装)。
- (+) capacity-overbooking-gate(実算術による重複日程占有チェック)・
  license-lapsed-gate(失効認証チェック)という、他の cloud-itonami actor
  に存在しない業態固有の HARD チェックを新設した。
- (+) `clojure -M:dev:test`: 33 tests / 144 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(新規施設登録→commit、出典なし予約→hold、
  tier超過/未契約開示→hold ×2、ライセンス失効施設への予約→hold、
  収容人数超過→hold、要注意フラグ付きゲスト→人間承認→commit、
  紛争申立て→人間承認→commit の7シナリオ全て正しく発火)。
- (-) R0 のライセンス regime は2法域のみ。他法域は operator の
  license-record 登録が必須。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。
- **west.yml の反映経路について**: `bb scripts/gen-west-manifest.bb` は
  `scripts/gen-west-manifest.cljs`(nbb)へ移行済みだが、移行が未完了で
  `clojure.java.shell` 名前空間が nbb に存在せず起動時に例外で落ちる
  (pending task として認識済み、本 ADR のスコープ外)。このため
  industry pin の前進は、CLAUDE.md の代替正経路(tip の blob SHA を
  取得→当該 entry の行だけ編集→blob SHA 一致で PUT)を用いて GitHub API
  で直接行った(コミット `1b2f811`)。差分は `industry` entry の revision
  1行のみ、他 entry には触れていない。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ。
  `cloud-itonami-isic-5590` は既存の `cloud-itonami-{ISIC}` blueprint 群と
  同じ慣例により `manifest/repos.edn` には登録しない(standalone、
  plain-git 子リポ)。

## 代替案と不採用理由

- **LLM に登録・確定権限を直接付与(エージェント自律)**: 出典なき断定・
  オーバーブッキング・失効施設への予約確定を構造的に防げない。単一不変
  条件(決定1)に反する。
- **capacity-overbooking-gate を SOFT にとどめる**: 物理的な収容力超過は
  人間承認で事後的に許容できる性質のものではない。HARD が必須と判断した。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-5590/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
  (収集・保持・開示パターンの直接の手本)
- `90-docs/adr/2607112900-cloud-itonami-isic-7820-temp-staffing-actor.md`
  (default-phase=1 恒久保守的既定の直接の手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "5590" エントリ)
