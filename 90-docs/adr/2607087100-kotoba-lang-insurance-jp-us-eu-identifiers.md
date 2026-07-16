---
id: adr-2607087100-kotoba-lang-insurance-jp-us-eu-identifiers
title: "ADR-2607087100: kotoba-lang/insurance の JP/US/EU 医療保険・レセプト識別子検証実装(全6コミット)を振り返って記録する"
status: accepted
doc_type: adr
topic: kotoba-lang-insurance-identifiers
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - kotoba-lang/insurance が JP/US/EU の医療保険/レセプト識別子検証ライブラリとして育ってきた経緯と、cloud-itonami の governed actor 群との役割分担
  - JP(保険者番号・医療機関コード)/US(NAIC company code・Payer ID)/EU(EHIC)それぞれの実装内容・確度・意図的な未実装事項の全体像
  - superproject 全体を俯瞰する視点でのこのライブラリの位置づけ(各子repo README は個別実装の一次情報源のまま、本ADRはそれを束ねる索引)
related:
  - orgs/kotoba-lang/insurance
  - orgs/kotoba-lang/emr-claims-primary-sources
  - 90-docs/adr/2607032000-cloud-itonami-insurance-real-estate-coverage.md
  - 90-docs/adr/2607084100-emr-claims-primary-sources-archive.md
supersedes: []
superseded_by: []
---

# ADR-2607087100: kotoba-lang/insurance の JP/US/EU 医療保険・レセプト識別子検証実装を振り返って記録する

- Status: accepted (2026-07-08、実装済みの既成事実を追認する形で記録)
- Deciders: Jun Kawasaki

## Context

`kotoba-lang/insurance` はもともと ADR-2607032000 で、`cloud-itonami` の
ISIC 65(生命保険/損害保険/再保険/年金基金)・662(リスク評価・保険仲介・その他
保険補助業務)群が共有する capability library として新設された
(`cd274baf`, 2026-07-03: policy/premium/claim/underwriting-decision の
pure-data contract、実際の料率表は持たない、`.cljc` で JVM/ClojureScript/
SCI/GraalVM 可搬)。`kotoba-lang/property`・`labor`・`retail` と同型の
「governed actor が使う、actuation を持たない安全な汎用ドメインモデル層」
という位置づけである。

その後、EMR(電子カルテ)/レセプト(claims)の日本・米国・EU対応の成熟度を
上げる別系統の自動改善ループ(サイクル #3, #7, #11, #15, #19 + サイクル外の
1タスク、いずれも2026-07-08の同一日に実行)が、この `kotoba-lang/insurance`
を実装対象に選んだ。cloud-itonami 側の保険関連 actor(6511/6512/6520/6530/
6621/6622/6629 等)に直接実装しなかったのは、以下の理由による(詳細は
「Alternatives Considered」節):

- cloud-itonami の各 actor は「知能ノードは proposal のみ返し、独立した
  Governor が可決/拒否/人間承認に振る」governed actuation の構造そのものが
  価値の中心であり、識別子の書式/チェックデジット検証は actuation を一切
  伴わない純粋関数である。これを特定の1 actor repo に置くと、保険関連の
  7つの actor が同じパース/検証ロジックを重複実装またはフォークすることに
  なる。
- `kotoba-lang/insurance` は既にこれら複数の cloud-itonami 保険 actor が
  参照しうる共有 capability layer として存在しており、実世界の識別子検証を
  ここに足すことは「actuation なし・I/O なし・pure function・portable
  `.cljc`」という既存の安全な汎用ドメインモデル層の性質と完全に整合する。

## Decision

`kotoba-lang/insurance` に対し、2026-07-08 の1日で以下6コミットが積まれた
(shaは `git log`/GitHub API で実際に確認した正確な値、日時は commit author
date, JST表記は `+09:00`):

| # | 時刻(JST) | commit | 内容 |
|---|---|---|---|
| 1 | 04:27:55 | [`ba4b9221`](https://github.com/kotoba-lang/insurance/commit/ba4b9221cd49732facb72eea1ae31523dd8379af) | JP: 保険者番号(8桁: 法別番号2+都道府県番号2+保険者別番号3+検証番号1)のチェックデジット検証を実装 |
| 2 | 06:12:40 | [`3f710c39`](https://github.com/kotoba-lang/insurance/commit/3f710c39536beb7dd79514d945f281c29bdc3c7a) | JP: 都道府県番号テーブルを埋め込み、医療機関コードの構造パースのみ実装(検証番号は未実装のまま保留) |
| 3 | 06:50:29 | [`bc1184a2`](https://github.com/kotoba-lang/insurance/commit/bc1184a2f532b0dd72359a2dfe434d0028ff00a2) | JP: 医療機関コードの検証番号を算出/検証、点数表番号をデコード(医科1/歯科3/薬局4)、都道府県番号表を51-97の代替コードまで94エントリに拡張(サイクル外タスク。一次資料PDFを新規アーカイブし完成させた回で、ADR-2607084100 が詳細を記録) |
| 4 | 08:02:43 | [`2e6c4b83`](https://github.com/kotoba-lang/insurance/commit/2e6c4b8385bd707e7e0ef574d0b186c648488276) | US: NAIC company code(5桁数字)と Payer ID(ASC X12 data element 67 の AN 2/80 構造)の形状検証を実装 |
| 5 | 10:05:05 | [`bce7ad73`](https://github.com/kotoba-lang/insurance/commit/bce7ad73c5d4193fa2d72bd738a6c177f30126c7) | EU: EHIC(欧州健康保険カード)の構造フィールド検証を実装 |
| 6 | 11:57:55 | [`fccd64e0`](https://github.com/kotoba-lang/insurance/commit/fccd64e04cf67c4e956d07627301c4abe469f92a) | fix: `parse-hokensha-bangou`/`parse-iryokikan-bangou` が非文字列入力で `ClassCastException` を起こすバグを修正(サイクル#19の健全性確認で発見) |

各国・地域ごとの実装内容と確度は以下の通り(すべて一次資料に当たって確認
済み、未確認の伝聞は実装しない/明記するという方針を徹底):

### JP(日本) — チェックデジットまで実装、最も確度が高い

- **保険者番号**(8桁): 法別番号(2)+都道府県番号(2)+保険者別番号(3)+
  検証番号(1)。検証番号の算出式・MHLWの worked example(法別番号=06,
  都道府県番号=13,保険者別番号=048→検証番号=8)で検算一致を確認。2024年版
  通知と2008年版通知の独立2件で同一の worked example を確認済み。
- **医療機関コード**(10桁): 都道府県番号(2)+点数表番号(1)+郡市区番号(2)+
  医療機関(薬局)番号(4)+検証番号(1)。検証番号は保険者番号と同じ交互2/1畳込み
  mod10 式で、一次資料の worked example(都道府県=34,点数表=1,郡市区=07,
  医療機関番号=1236→検証番号=2)で検算一致を確認。点数表番号は一次資料が
  明記する医科=1/歯科=3/薬局=4のみをデコード対象とし(二次資料が伝える
  2=健診等機関/6=訪問看護は一次資料で未確認のため未実装、`nil` を返す)。
  医療機関(薬局)番号のレンジ制約(医科1000-2999/歯科3000-3999/薬局
  4000-4999、中2桁または下2桁が90の欠番)も実装。
- 一次資料: 厚生労働省「診療報酬請求書等の記載要領等について」等の一部
  改正について(2024-07-12付、`https://www.mhlw.go.jp/content/12400000/001275316.pdf`)
  別添２、および2008年版通知(旧版、独立した裏付けとして使用)。両方とも
  `kotoba-lang/emr-claims-primary-sources` の `jp-mhlw/` にアーカイブ済み
  (詳細は ADR-2607084100)。
- **意図的な未実装**: 埼玉県・千葉県・東京都・神奈川県の一部レガシー
  医療機関コード(1976年の10桁体系統一以前、1967年導入の7桁コード由来)が
  異なる検証番号算出方式(都道府県番号・点数表番号を含めない7桁のみでの
  算出)を持つという二次資料(Wikipedia)の記述は、一次資料で確認できな
  かったため実装していない。

### US(米国) — 一次資料の結論そのものが「チェックデジットは存在しない」

- **NAIC company code**(5桁数字): NAIC公式 Glossary of Insurance Terms
  (`https://content.naic.org/glossary-insurance-terms`, 2026-07-08取得)と
  HL7 Terminology の `NAICCompanyCodes` NamingSystem(OID
  `2.16.840.1.113883.6.300`)の独立2件で、チェックデジットに関する記述が
  一切ないことを確認(グロッサリーページの生HTML全文検索で"check digit"が
  0件ヒット)。したがって5桁数字の形状検証のみを実装 — これは手抜きでは
  なく「一次資料を確認した結果、実装すべきアルゴリズムが存在しないと判明
  した」という調査結論そのもの。
- **Payer ID**: 公的な単一標準レジストリが存在しないことを確認
  (Availity/Change Healthcare/Stedi 等クレアリングハウスごとに独自採番、
  同一payerでも業者ごとに異なるIDを持つ)。ASC X12 data element 67
  (`NM109`, type `AN`, 長さ2-80)という標準化された構造のみを実装。
- **意図的な未実装**: NAIC company code が10000未満だと損害保険統合様式の
  レガシーカテゴリを意味するという二次資料の主張は、検証可能な一次資料
  (該当PDFは文字抽出不能な圧縮/フォントサブセットストリームだった)に
  当たれなかったため実装していない。

### EU/EEA — 「EU全域統一フォーマットが存在しない」こと自体が一次資料で確認された結論

- Decision No S2 of 12 June 2009(Administrative Commission for the
  Coordination of Social Security Systems、CELEX `32010D0424(09)`)により、
  EHIC の個人識別番号フィールドは「発行加盟国が用いる個人識別番号の詳細」
  としか定義されておらず、**EU全域で統一されたチェックデジットや桁数は
  存在しない**ことを一次資料で確認。ルクセンブルクが2014年に国内個人識別
  番号の構成変更で全EHICを再発行した、という European Commission の別報告
  (Pacolet & De Wispelaere 2016)がこれを運用面から裏付ける。
- 実装したのは EU規格として明文化されている構造のみ: 発行国コード(2文字、
  "UK"例外あり)、個人識別番号(20文字以内、形状のみ)、機関識別番号
  (4-10文字)、カード論理識別番号(20桁固定、EN1867準拠の10文字発行者
  識別子+10桁通し番号)。
- **意図的な未実装**: Section 3.5 の印字文字集合規定(EN 1387 準拠)は
  EN 1387 自体が非公開規格であるため実装せず、独自に文字集合を推測する
  ことも避けた。

## Consequences

- `kotoba-lang/emr-claims-primary-sources`(DataLad + B2、GitHub public:
  `https://github.com/kotoba-lang/emr-claims-primary-sources`)が、この
  ライブラリのJP実装の一次資料アーカイブ先として確立した(ADR-2607084100
  が最初の前例)。今後US/EUの一次資料PDFが見つかり次第、同データセットの
  `us-naic/`・`eu-ehic/` 等に追加していく運用が標準になる。
- サイクル#19の健全性確認で `ClassCastException` の実バグ(`fccd64e0`)が
  見つかり修正された — 外部データを受け取るパース関数に非文字列入力を
  与えるテストの価値を裏付ける実例として記録する。
- README(`orgs/kotoba-lang/insurance/README.md`)の "Maturity" 表によれば
  本ADR執筆時点で216アサーションが全てグリーン(policy/premium/claim の
  既存機能を含む全体のテスト数であり、識別子検証だけの数ではない)。
- JP/US/EUのいずれも「確認できなかったことは実装しない」という一貫した
  規律が保たれており、`valid-*?`/`validate-*` が `true`/`:insurance/valid?
  true` を返すことは常に「一次資料で確認された範囲内の形状/検証番号が
  正しい」という意味に限定される(実在する払い戻し可能な識別子である
  ことの保証ではない、特にUS Payer IDとEU個人識別番号)。
- 今後の拡張候補: 他のJP識別子(公費負担者番号等)、US NPI、他EU加盟国
  固有の個人識別番号アルゴリズム(存在すれば)。いずれも
  `emr-claims-primary-sources` への一次資料追加を先行させる運用を踏襲する。

## Alternatives Considered

| 代替案 | 却下理由 |
|---|---|
| JP/US/EU識別子検証を各 cloud-itonami 保険 actor repo(6511/6512/6520/6530/6621/6622/6629等)に個別実装 | governed actuation を持つ actor repo は「知能ノード封じ込め+独立Governor+不変台帳」という actuation境界の実装に特化すべきで、actuationを伴わない識別子パース/検証を7つのrepoに重複実装/フォークさせる理由がない。既存の共有capability layerである `kotoba-lang/insurance` に置く方が保守コストが低い |
| 識別子検証専用の新規repoを新設 | `kotoba-lang/insurance` が既に最も近い既存の置き場所であり、他repoからこのshapeに依存しているものが無い(monorepo全体grepで確認済み、ADR-2607084100参照)。scopeが将来大きく広がった場合の切り出しは選択肢として残すが、現時点では過剰な分割 |
| US NAIC company codeの10000未満レガシーカテゴリや、点数表番号2(健診等機関)/6(訪問看護)を二次資料から類推して実装 | 一次資料で確認できない当て推量を実装するのはこのライブラリ全体の「一次資料のみに基づく」規律に反する。未実装であることを明記する方を選んだ |

## References

- `orgs/kotoba-lang/insurance/README.md`(JP/US/EU各セクションの実装詳細・
  worked example・出典URL)
- `orgs/kotoba-lang/insurance/src/kotoba/insurance/{jp,us,eu}.cljc`
  (各namespaceのdocstringに一次資料の全文引用・確度の記録あり)
- kotoba-lang/insurance@cd274baf — 原初のcapability lib作成
  (`https://github.com/kotoba-lang/insurance/commit/cd274baf4312577a959eea3e4daf2937ac48a7a2`)
- kotoba-lang/insurance@ba4b9221 — JP 保険者番号チェックデジット
  (`https://github.com/kotoba-lang/insurance/commit/ba4b9221cd49732facb72eea1ae31523dd8379af`)
- kotoba-lang/insurance@3f710c39 — JP 医療機関コード構造パース(検証番号未実装)
  (`https://github.com/kotoba-lang/insurance/commit/3f710c39536beb7dd79514d945f281c29bdc3c7a`)
- kotoba-lang/insurance@bc1184a2 — JP 医療機関コード検証番号完成(サイクル外)
  (`https://github.com/kotoba-lang/insurance/commit/bc1184a2f532b0dd72359a2dfe434d0028ff00a2`)
- kotoba-lang/insurance@2e6c4b83 — US NAIC/Payer ID
  (`https://github.com/kotoba-lang/insurance/commit/2e6c4b8385bd707e7e0ef574d0b186c648488276`)
- kotoba-lang/insurance@bce7ad73 — EU EHIC
  (`https://github.com/kotoba-lang/insurance/commit/bce7ad73c5d4193fa2d72bd738a6c177f30126c7`)
- kotoba-lang/insurance@fccd64e0 — 非文字列入力クラッシュ修正
  (`https://github.com/kotoba-lang/insurance/commit/fccd64e04cf67c4e956d07627301c4abe469f92a`)
- `kotoba-lang/emr-claims-primary-sources`
  (`https://github.com/kotoba-lang/emr-claims-primary-sources`)
- ADR-2607032000(`cloud-itonami-insurance-real-estate-coverage`) —
  `kotoba-lang/insurance` 新設の原ADR
- ADR-2607084100(`emr-claims-primary-sources-archive`) — 一次資料
  アーカイブ新設とJP医療機関コード完成の詳細記録(本ADRの前身・詳細版)
