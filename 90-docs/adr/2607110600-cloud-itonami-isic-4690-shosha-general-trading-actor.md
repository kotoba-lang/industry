# ADR-2607110600: cloud-itonami-isic-4690 — 総合商社(non-specialized wholesale trade)を ShoshaAdvisor ⊣ :shosha-trading-governor で実装する general-trading actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーから「今の cloud-itonami で商社の actor は稼働済みか」と問われ、既存
actor を横断調査した結果、**総合商社(sogo-shosha)専用のアクターは存在しない**
と判明した。一番近い実装は `cloud-itonami-isic-4671`(ISIC 4671、燃料の卸売
商社)だが、これは単一商品カテゴリに特化した narrow vertical actor であり、
複数の無関係な商品カテゴリを横断して仲介する「非専門卸売(total/diversified
trading)」の業態ではない。

`kotoba-lang/industry` の registry(`resources/kotoba/industry/registry.edn`)
を調べたところ、総合商社に相当する **ISIC Rev.4 4690「Non-specialized
wholesale trade」が `:maturity :spec` のまま**(registry のみ、repo 未着手)
で登録されていた。`:repo` 欄も `https://github.com/gftdcojp/cloud-itonami-
G4690` という旧命名の死んだ URL のままで、実体は一度も作られていなかった
(4610/4620/4630/4649/4659 等の他の卸売系 ISIC コードと同じ、未着手 spec
プレースホルダー群の1つ)。`cloud-itonami-M6910`(6910 spec→実装)・
`cloud-itonami-isic-8291`(8291 spec→実装、ADR-2607110400)に続く「未着手
スロットを実装に昇格させる」前例に倣うのが最も筋が良いと判断した。

## Decision

新規 actor `cloud-itonami-isic-4690`(ISIC Rev.4 4690)を、`cloud-itonami`
org 直下に public/AGPL-3.0-or-later の open business blueprint として新設
する。governor アーキテクチャは `cloud-itonami-isic-8291` の Dossier/開示型
パターンではなく、**同じ「商取引 actuation を governor がゲートする」業態
である `cloud-itonami-isic-4671`(FuelTradeAdvisor ⊣ Fuel Trading Governor)
を直接の手本とする**(単一商品→複数商品への一般化、燃料の excise/物理rack
規制→export-control/sanctions 規制への差し替え)。

### 1. ShoshaAdvisor ⊣ :shosha-trading-governor(単一不変条件)

> **ShoshaAdvisor は、`:shosha-trading-governor` が拒否する trade-order の
> 発送(`:shipment/dispatch`)・請求確定(`:invoice/settle`)を決して行わない。**

`fueltrade.governor` の7 HARD チェックをそのまま継承した上で、総合商社の
本質的リスク(単一商品の物品規制ではなく、複数商品を跨ぐ越境貿易の輸出管理)
を反映した1件を新規追加、計8 HARD チェック + 1 SOFT ゲート:

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | spec-basis | HARD | 法域が `shosha.facts/catalog` に spec-basis を持たない限り `:contract/verify`/`:shipment/dispatch`/`:invoice/settle` を許可しない |
| 2 | evidence-incomplete | HARD | 与信確認・契約書/発注書・制裁スクリーニングの3点セットが揃っていない発送/請求を拒否 |
| 3 | credit-uncleared | HARD | カウンターパーティの与信が未クリアの発送を拒否 |
| 4 | contract-missing | HARD | 契約条件が台帳に存在しない発送を拒否 |
| 5 | **export-license-uncleared**(新規) | HARD | fueltrade に類例のない、総合商社固有の輸出許可/ライセンス未取得チェック。単一商品の excise 規制ではなく、複数商品カテゴリを跨ぐ越境貿易の輸出管理(下記法域カタログ参照)を直接エンコード |
| 6 | counterparty-sanctions-flag-unresolved | HARD(常時評価) | OFAC/EU/OFSI 等の制裁スクリーニングフラグが未解決なら発送・請求とも無条件で拒否 |
| 7 | already-dispatched | HARD | 同一 trade-order の二重発送防止(`:dispatched?` 専用フラグ) |
| 8 | already-invoiced | HARD | 同一 trade-order の二重請求防止(`:invoiced?` 専用フラグ) |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | `:shipment/dispatch`/`:invoice/settle` は governor がクリーンでも常に人間の trading supervisor へエスカレーション(自動commitしない) |

### 2. Phase 0→3 + 恒久人間ゲート

`fueltrade.phase` と同型: `:order/intake` のみ phase 3 で auto-commit
可能、`:shipment/dispatch`/`:invoice/settle` は**どの phase の `:auto`
集合にも入らない**構造的恒久ゲート(governor の SOFT ゲートと phase table
の二重独立実施、`fueltrade`/`M6910` と同じ二層防御)。

### 3. 法域カタログ(正直な R0 スコープ)

`fueltrade.facts/catalog` が燃料 excise/通関を中心に4法域を種付けしたのに
対し、総合商社の本質的規制対象である**輸出管理・制裁**を中心に4法域を種付け:

- **日本** — 外国為替及び外国貿易法(外為法)+ 輸出貿易管理令、経済産業省
  (METI)安全保障貿易管理
- **米国** — Export Administration Regulations(15 C.F.R. Parts 730-774、
  BIS)+ OFAC 制裁プログラム
- **英国** — Export Control Order 2008(SI 2008/3231)+ OFSI 金融制裁
- **ドイツ/EU** — Regulation (EU) 2021/821(dual-use 輸出管理 recast)+
  BAFA、AWG/AWV(対外経済法/対外経済令)

4/~194法域のみの種付けであり、全世界カバレッジの主張ではない(`fueltrade`/
`M6910`/`isic-8291` と同じ正直な R0 方針)。追加は `shosha.facts/catalog`
への1エントリ追記のみで、実在しない法域要件を捏造しない。

### 4. Robotics premise: false

`blueprint.edn` は `:itonami.blueprint/robotics false`、`:required-
technologies` から `:robotics` を省く(値を false にするだけでなく
キー自体を持たない)。総合商社の `:shipment/dispatch` は自社が操作する
物理ロボットではなく、**ライセンスされたフレイトフォワーダー/通関業者への
物流仲介リファラル**であり、`cloud-itonami-6310`/`-isic-6910`/`-isic-8291`
と同じ「デジタル/紙業務で actor が制御する物理領域を持たない」除外クラスに
属する(`fueltrade` の燃料ラックバルブロボットとは対照的)。

## Consequences

- (+) `kotoba-lang/industry` registry の 4690 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-G4690` URL)から実装(`cloud-itonami/cloud-itonami-
  isic-4690`)へ昇格し、`M6910`・`isic-8291` に続く「昇格実例」になる。
- (+) `fueltrade` の governed-trading パターンが単一商品(燃料)から
  複数商品を跨ぐ一般貿易へ一般化できることを示した(新しいアーキテクチャの
  発明は不要だった)。excise/物理rack規制→export-control/sanctions規制への
  差し替えのみで済み、export-license-uncleared チェック1件の追加が本質的な
  ドメイン差分。
- (+) `clojure -M:dev:test`: 36 tests / 174 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも end-to-end
  で確認済み(7つの HARD ルール全てが正しくトリガー)。
- (-) R0 の法域カバレッジは4法域のみ(全世界のごく一部)。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の与信/
  輸出許可/制裁データベンダー統合は operator の責任範囲。
- (-) 4つの法域引用(外為法/EAR+OFAC/Export Control Order 2008+OFSI/
  EU 2021/821+BAFA)はいずれも実在の安定した法令として学習知識ベースで
  確信度が高いが、実装エージェントは live web access を持たなかったため
  未検証で組み込んだ。本 ADR 執筆時に WebSearch で4件とも独立に裏取りし、
  いずれも正確であることを確認した。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進
  (`manifest/west.yml` の `industry` entry のみ、`--entry industry` の
  最小 diff)。`cloud-itonami-isic-4690` は既存の `cloud-itonami-{ISIC}`
  blueprint 群と同じ慣例により `manifest/repos.edn` には登録しない
  (standalone、plain-git 子リポ)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4690/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `orgs/cloud-itonami/cloud-itonami-isic-4671/docs/business-model.md`
  (直接の手本、FuelTradeAdvisor ⊣ Fuel Trading Governor の原型)
- `90-docs/adr/2607100400-cloud-itonami-petroleum-supply-chain-coverage.md`
  (`cloud-itonami-isic-4671` を含む petroleum-fleet wave の ADR)
- `90-docs/adr/2607110400-cloud-itonami-isic-8291-corporate-compliance-intelligence-actor.md`
  (spec→実装昇格・正直な R0 スコープの直近先例)
- `90-docs/adr/2607031500-cloud-itonami-m6910-global-incorporation-actor.md`
  (spec→実装昇格の最初の先例)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4690" エントリ)
