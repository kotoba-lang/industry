# ADR-2607117000: cloud-itonami-isic-4659 — その他機械・設備卸売を MachToolTradeAdvisor ⊣ :precision-machinery-export-governor で実装する精密加工能力輸出管理 actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4663`(建設資材・配管・暖房設備、飲用水安全、
ADR-2607116900)に続く fleet 継続タスクとして ISIC 4659「Wholesale of
other machinery and equipment」を選定した。46xx卸売業ファミリーの
残りスロットは本エントリ完了後 4661(fueltrade=isic-4671と実質重複する
真のISICコード)のみとなる。死んだ `gftdcojp/cloud-itonami-G4659` URL
のまま `:spec` で放置されていた。

## Decision

新規 actor `cloud-itonami-isic-4659` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。既存の `cloud-itonami-isic-4651`
(TechTradeAdvisor)も ECCN 分類ベースの輸出管理を扱うが、あちらは
**情報セキュリティ/暗号化技術**(データを扱えるかどうか)が規制対象
であるのに対し、本 actor は**精密加工能力そのもの**(この工作機械で
何を製造できるか)が規制対象という、質的に異なる規制関心を持つ。

### 1. MachToolTradeAdvisor ⊣ :precision-machinery-export-governor
(単一不変条件)

> **MachToolTradeAdvisor は、`:precision-machinery-export-governor`
> が拒否する `:delivery/dispatch`・`:invoice/settle` を決して行わない。**

### 2. fleet 初の真に並列な二軸チェック設計(設計上の核心判断)

4651(逐次: 分類→ライセンス判定、同種の事実に依存)・4653(独立だが
同種: エンジン搭載/搭乗型、どちらも同一機体の製品特性)のいずれとも
異なる、**構造的に無関係な二軸を並列に評価**する設計:

- `capability-threshold-uncertified`: **アドバイザーの申告を信用せず、
  純粋なレンジチェック関数**(`machtooltrade.registry/capability-
  threshold-crossed?`)により、同時制御軸数・位置決め精度がECCN
  2B001相当の閾値を超えるかを**独立に再計算**する。fleetのプリンシパル
  トレーディング系列で初めて、原油採掘系兄弟(貯留層/環状部/含水率/
  H2Sチェック)と同種の「物理的レンジチェック関数」パターンを採用。
- `military-end-use-unresolved`: 軍事エンドユース/エンドユーザーの
  事実のみを参照し、Axis 1とは事実集合が完全に重ならない。EAR の
  Military End User Rule(15 C.F.R. §744.21)の「たとえ機体スペックが
  閾値未満でも、軍事エンドユーザー向けなら依然として規制対象」という
  キャッチオール構造をモデル化。

**独立性の実証**: 閾値未満の機体(通常のクリーンシナリオと同一スペック)
を軍事エンドユーザー旗フラグ付きで発送しようとするフィクスチャは
`military-end-use-unresolved` **のみ**でhold(`capability-threshold-
uncertified` は明示的に発火しないことをテストでassert)。逆に閾値超過
機体を通常のエンドユーザーに発送しようとするフィクスチャは
`capability-threshold-uncertified` **のみ**でhold。両方が同時に発火
するケース、両軸解消後にクリーン発送するケースもテストで実証。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **capability-threshold-uncertified** | HARD | 物理レンジチェック関数で独立に再計算 |
| 6 | **military-end-use-unresolved** | HARD | Axis 1と事実集合の重なりゼロ |
| 7 | counterparty-sanctions-flag-unresolved | HARD | 汎用OFAC |
| 8-9 | already-dispatched / already-invoiced | HARD | 二重actuation防止 |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 兄弟共通 |

### 3. Robotics premise: false(理由付きの再考)

重量精密工作機械は質量分布が不規則で、案件ごとの専用リギング計画を
要する -- 均一なバルク在庫(コイル・プレート)を扱う金属卸売兄弟の
自動クレーン/グラップルシステムとは対照的。直近の兄弟の値を機械的に
コピーしない判断のさらなる実例。

### 4. 法域カタログ(確信度の勾配を明示、ADR起票時に WebSearch で裏付け)

**高確信度(規制体系そのものの実在)**: 米国 EAR/BIS Category 2
「Materials Processing」、ECCN 2B001(数値制御工作機械)、15 C.F.R.
§744.21 Military End User Rule -- いずれも WebSearch で正確な構造を
確認。Wassenaar Arrangement(1996年発足、COCOM後継、約42participating
states)-- 発足年・COCOM後継という性質を確認。

**中〜低確信度(具体的な数値閾値)**: `capability-threshold-crossed?`
内の「同時5軸以上 かつ 精度6マイクロメートル以下」という具体的な数値は、
実装エージェント自身が「例示的な簡略化された合成値であり、2B001の
実際の機種別構造の検証済み再現ではない」と明記している。本ADRもこの
区別を踏襲し、規制体系の実在(高確信度)と具体的数値パラメータ(要検証)
を意図的に分けて報告する。日本の輸出貿易管理令別表第一における工作
機械の正確な項番も、意図的に引用を避けている(要検証)。

## Consequences

- (+) `kotoba-lang/industry` registry の 4659 スロットが実装へ昇格。
- (+) fleet 初の「真に並列(逐次でも同種独立でもない)」二軸チェック
  設計を導入し、輸出管理という同じラベルの中でも規制メカニズムの
  構造が3通り(逐次・同種独立・異種並列)に分岐しうることを示した。
- (+) fleet 初のプリンシパルトレーディング系列における物理レンジ
  チェック関数(原油採掘系兄弟の手法をトレーディング系に輸入)。
- (+) 確信度の勾配(規制体系の実在 vs. 具体的数値)を明示的に分離して
  報告する規律をさらに強化した実例。
- (+) `clojure -M:dev:test`: 43 tests / 233 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは閾値超過/軍事エンドユース
  フラグの両方向の独立性をEnd-to-Endで確認済み。
- (-) 数値閾値・日本の項番は要検証。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`nbb scripts/gen-west-manifest.cljs --entry industry` 最小 diff)。
  `cloud-itonami-isic-4659` は standalone(manifest/repos.edn には
  登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4659/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision 4 に二軸設計の理由、Decision 9 に robotics 判断)
- `90-docs/adr/2607113600-cloud-itonami-isic-4651-techtrade-actor.md`
  (対比対象、逐次分類パイプライン)
- `90-docs/adr/2607115000-cloud-itonami-isic-4653-agmachtrade-actor.md`
  (対比対象、同種独立2ゲート)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4659" エントリ)
