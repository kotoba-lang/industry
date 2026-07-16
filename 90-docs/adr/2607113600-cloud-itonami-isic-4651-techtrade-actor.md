# ADR-2607113600: cloud-itonami-isic-4651 — コンピュータ・周辺機器・ソフトウェア卸売を TechTradeAdvisor ⊣ :tech-export-governor で実装するデュアルユース輸出管理分類 actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-4669`(廃棄物・スクラップ、Basel Convention PIC、
ADR-2607113400)に続く fleet 継続タスクとして ISIC 4651「Wholesale of
computers, computer peripheral equipment and software」を選定した。
死んだ `gftdcojp/cloud-itonami-G4651` URL のまま `:spec` で放置されて
いた。

## Decision

新規 actor `cloud-itonami-isic-4651` を `cloud-itonami` org 直下に public/
AGPL-3.0-or-later で新設する。既存の `cloud-itonami-isic-4690`(総合商社
ShoshaAdvisor)も export-control チェックを持つが、4690 のそれは**法域が
輸出管理体制を持つかどうかの粗い引用ベースの1個のブール値**であるのに
対し、4651 は**実在する分類メカニズム(ECCN/Commerce Control List)に
基づく、より技術的で構造の異なるチェック**を実装した -- 単なる relabel
ではないことを本ADRで明示する。

### 1. TechTradeAdvisor ⊣ :tech-export-governor(単一不変条件、fleet 初の
3メンバー high-stakes 集合)

> **TechTradeAdvisor は、`:tech-export-governor` が拒否する
> `:delivery/dispatch`・`:technology/release`・`:invoice/settle` を
> 決して行わない。**

これまでの全兄弟は high-stakes actuation が2つ(発送+請求)だったが、
4651は**3つ**に拡張した。理由: **みなし輸出(deemed export)ドクトリン**
(15 C.F.R. §734.13)-- 米国内で外国人に管理技術/ソースコードを開示する
行為は、物理的な国境通過が一切なくても法的に「その者の本国への輸出」と
みなされる。これは他のどの兄弟の actuation とも形が異なる(物理出荷では
ない実在の規制対象イベント)ため、`:delivery/dispatch` に無理に押し込む
のではなく独立した `:technology/release` op として new する判断をした。

### 2. 分類欠如 vs. ライセンス未取得の分離(設計上の核心判断)

4690の単一 `export-license-uncleared` ブール値を、4651では**構造的に
別の2つの失敗モード**に分割した:

- `eccn-classification-missing`: 品目がそもそも管理リストに対して分類
  されていない(`:eccn` が nil)
- `license-required-unauthorized`: 分類済みだが、宛先/エンドユーザーの
  組み合わせに対してライセンスが必要で、有効なライセンス/ライセンス
  例外が未取得

テストは各注文がこの2つのうちちょうど1つでholdすることを証明しており、
1つのチェックに畳み込んでいない。暗号品目(EAR Category 5 Part 2、
ECCN 5A002/5D002非マスマーケット・5A992/5D992マスマーケット)は
**意図的に別建てのチェックを起こさず**、同じ「分類→ライセンス判定」の
2段階メカニズムに折り込んだ -- 実装側 ADR にこの判断の理由が明記されて
いる。

`denied-party-list-flag-unresolved` も新規: BIS の Entity List(15 CFR
Part 744 Supplement No.4)/ Denied Persons List(15 CFR Part 764)は、
汎用の OFAC 制裁スクリーニングとは別のメカニズムであり、OFAC を通過
しても denied-party スクリーニングで失敗するテストフィクスチャで区別を
実証している。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1-4 | no-spec-basis / evidence-incomplete / credit-uncleared / contract-missing | HARD | 兄弟共通 |
| 5 | **eccn-classification-missing** | HARD | 品目未分類 |
| 6 | **license-required-unauthorized** | HARD | 分類済みだが宛先向けライセンス未取得 |
| 7 | counterparty-sanctions-flag-unresolved | HARD | 汎用OFAC等 |
| 8 | **denied-party-list-flag-unresolved** | HARD | BIS Entity List/Denied Persons List、OFACとは別メカニズム |
| 9-10 | already-dispatched / already-released / already-invoiced | HARD | 3actuationそれぞれの二重防止(fleet初の3種) |
| — | 高額/actuation SOFT ゲート | SOFT→escalate | 3つのactuation全てが常に人間承認 |

### 3. Robotics premise: true(fleet 初のパス別判断)

`:delivery/dispatch`(AS/RS goods-to-person ロボットシャトル、ESD安全
電子機器取扱い)のみに適用し、`:technology/release`(物理的行為が一切
ない)には適用しない -- 1つの actor 内で actuation op ごとに robotics
premise を分けた fleet 初の実例。

### 4. 法域カタログ(ADR起票時に WebSearch で裏取り済み、すべて正確、
ECCN番号まで一致)

- **米国** — EAR/BIS: CCL Category 4 + Category 5 Part 2、ECCN
  5A002/5D002(非マスマーケット暗号)+ 5A992/5D992(マスマーケット
  暗号)、License Exception ENC(§740.17)、Entity List(15 CFR Part
  744 Supplement No.4)、Denied Persons List(15 CFR Part 764)、
  みなし輸出(15 C.F.R. §734.13)
- **日本** — 経済産業省 安全保障貿易審査課、該非判定・キャッチオール
  規制(大量破壊兵器/通常兵器2種)、輸出貿易管理令別表第一
- **EU(ドイツ代表)** — Regulation (EU) 2021/821 Annex I Category 4/5
  Part 2 + 第5条サイバー監視キャッチオール規制、BAFA
- **英国** — Export Control Order 2008、ECJU

## Consequences

- (+) `kotoba-lang/industry` registry の 4651 スロットが実装へ昇格。
- (+) 4690(粗い輸出管理チェック)と4651(技術的分類ベースの輸出管理
  チェック)という2つの下位パターンを、両者を明示的に対比する形で
  確立した -- 同じ「輸出管理」というラベルでも実装が本質的に異なる
  ことを示した。
- (+) fleet 初の3メンバーhigh-stakes actuation集合、fleet 初のパス別
  robotics 判断という2つの新しい設計パターンを追加した。
- (+) `clojure -M:dev:test`: 46 tests / 265 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。デモは分類欠如・ライセンス未取得
  ・みなし輸出の3ケースがそれぞれ別のruleでholdすることをEnd-to-Endで
  確認済み。
- (-) 現行のライセンス例外適格性・Entity/Denied Persons Listの現在の
  掲載状況は実装エージェント自身が要検証と明記(学習知識ベースの
  静的引用であり、実運用ではリアルタイムのリスト参照が必要)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`nbb scripts/gen-west-manifest.cljs --entry industry` 最小 diff)。
  `cloud-itonami-isic-4651` は standalone(manifest/repos.edn には
  登録しない)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4651/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対、
  Decision に分類/ライセンス分離、みなし輸出、パス別robotics判断)
- `orgs/cloud-itonami/cloud-itonami-isic-4690/docs/business-model.md`
  (対比対象、粗い輸出管理チェックの先例)
- `90-docs/adr/2607113400-cloud-itonami-isic-4669-wastetrade-actor.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4651" エントリ)
