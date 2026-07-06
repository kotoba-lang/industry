# ADR-2607070300: kotoba-lang/goyoukiki — real JP opportunity ingestion（kkj.go.jp + GEPS 落札実績オープンデータ）

**Status**: closed（実行完了。2コネクタとも実データで動作確認、テスト・lintまで完了。
candidate(全省庁統一資格)コネクタ・実Notifierは引き続きfollow-up）
**Date**: 2026-07-06
**Closed**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607070200（goyoukiki本体の設計）は「実データ取得(live fetcher)は
follow-up」と明記していた。オーナーの追加要望（原文: 「全省庁、団体から調達
情報をingest」）を受け、実データソースを調査・検証した上で実装した。

調査の結果、判明した実在データソース:

1. **官公需情報ポータルサイト（kkj.go.jp、中小企業庁運営）** — ライブの
   入札公告検索API（検索APIガイド V1.1、実在・文書化済み）。国の機関だけで
   なく独立行政法人・大学法人・地方公共団体を含む横断検索が可能（実測: 「情報
   システム」で検索し 343,405 件ヒット。国立大学法人岡山大学の実案件では
   公告文中に「国の競争参加資格(全省庁統一資格)において...A、B、C又はD等級」
   という要件が明記されており、goyoukikiのopportunity.required-categories/
   min-rank⟷candidate.categories/rankというeligibilityモデルが実際の公告
   文言と正確に対応することを確認した）。
2. **落札実績オープンデータ（調達ポータル/GEPS、デジタル庁運営）** — 全53
   府省コード（衆参両院・最高裁判所・会計検査院等を含む立法・司法・行政の
   全機関）を網羅する落札実績（＝AWARDED、公示済みではなく決定済み）のCSV/
   JSONオープンデータ。ファイル仕様書（令和8年3月版）をダウンロードし全項目
   を確認、実際に差分ファイル（successful_bid_record_info_diff_20260523.zip）
   をダウンロード・解凍・パースして実データで検証した。
3. 一方、**全省庁統一資格（候補団体＝candidateのレジストリ）は対話的HTML
   検索のみ**（chotatujoho.geps.go.jp）で、一括ダウンロード/APIは見つからず
   （e-Govデータポータルの当該エントリも「有資格者名簿閲覧(リンク)」という
   外部システムへのリンクのみで実体データを持たない）。したがって本ADRの
   スコープは **opportunity（案件）の実データ取得のみ**とし、candidateは
   引き続き `goyoukiki.store/demo-data` の手セットのまま据え置く。

## Decision

**`goyoukiki.jp.kkj`・`goyoukiki.jp.geps` の2実装を追加する（JVM専用、
`.cljc` コアには読み込まれないingest側アダプタ）。**

1. **`goyoukiki.jp.kkj`**: `http://www.kkj.go.jp/api/` へ実HTTP GETし、XML
   レスポンス（`<SearchResult>` 繰り返し）を `goyoukiki.model/opportunity`
   （`:kind :tender` `:status :open`）へ写像。`Certification`（A/B/C/D等の
   受理可能格付け列挙）から `:min-rank` を**最も緩い（最低）格付け**として
   導出（Certification="A B C"ならD以下は不可＝floor=C。最高格付けを
   floorにする誤りを避けた）。`Category`（物品/役務/工事）を
   `:required-categories` に写像。
2. **`goyoukiki.jp.geps`**: `https://api.p-portal.go.jp/pps-web-biz/UAB03/
   OAB0301?fileversion=v001&filename=<name>` から実際にzipをダウンロードし
   （ダウンロードページのdoDownload JS実装から実URLを特定、ファイル仕様書
   通りUTF-8+BOM+CRLF+ダブルクォート区切りCSVをパース）、
   `goyoukiki.model/opportunity`（`:kind :tender` `:status :awarded`）へ
   写像。府省コード表（53件）・入札方式コード表（16件）はファイル仕様書から
   転記した閉じた公式コード表 — 「全省庁」網羅は構成上保証される（54番目の
   コードが存在しえない）。落札事業者名は `candidate` を自動生成せず
   opportunity側の `:awarded-to`/`:awarded-corporation-no` メタデータとして
   保持する（「一度受注した」と「全省庁統一資格に登録された適格団体」は
   別の事実であり、混同しない）。
3. **`goyoukiki.operation/register!` を公開関数として抽出**（`:record`
   ノードの処理そのもの）。バルクingest（数百〜数千件）が1件ごとに
   checkpointerスレッドを立てる（`g/run*`経由）のは無駄なので、両コネクタは
   `register!` を直接呼ぶ。単発の対話的登録（sim.cljc等）は引き続き
   `g/run*` 経由のStateGraph runを使う——ロジックは`register!`に一本化した
   ので二重実装ではない。
4. **テストは実データのフィクスチャに基づく**（`test/goyoukiki/jp/
   kkj_test.clj`・`geps_test.clj`）。ネットワークI/O（`fetch-xml`/
   `download-zip-bytes`）はテスト対象外——実際にcurlで取得した実レスポンス
   （岡山大学の実案件・GEPS実差分ファイルの実際の行）を静的フィクスチャとして
   埋め込み、パース/写像ロジックのみを検証する（CIをネットワーク依存に
   しない）。各コネクタの `-main` は実ネットワーク呼び出しの手動/運用
   エントリポイントとして別途用意し、本ADR作成時に実際にライブ実行して
   確認済み（kkj.go.jpで実際の入札5件、GEPSで実際の落札実績98件を登録）。

## Consequences

- (+) 「全省庁、団体から調達情報をingest」という要望を、実在・文書化済み・
  検証済みの2つの公式データソースで満たした。特にkkj.go.jpは国の機関に
  加え独立行政法人・大学法人・地方公共団体を単一feedで横断カバーし、
  「団体」の要件を文字通り満たす。GEPSは全53府省コードという閉じた公式
  コード表により「全省庁」を構成上完全網羅する。
- (+) goyoukiki.policy/eligible? のカテゴリ/ランクモデルが、実際の公告文言
  （全省庁統一資格の等級要件）と直接対応することを実データで確認できた
  ——設計が机上の空論でないことの実証。
- (−) GEPSは落札**実績**（既に決定済み）であり、ライブの「これから入札可能」
  な案件ではない。kkj.go.jpはライブだが、SearchHitsが数十万件規模で
  クロール的に収集されたデータのため、GEPSの53コード表のような網羅性の
  構成的保証はない（機関名の表記揺れ等の可能性がある——機関名検索は
  前後方一致で名称変更・合併に対応する仕組みが公式に用意されている）。
- (−) candidate（全省庁統一資格の候補団体）側の実データ取得は未着手
  ——対話的HTML検索のみで一括アクセス手段が見つからなかったため。この
  ギャップはv1のまま残る（ADR-2607070200のスコープ境界どおり）。
- (−) 実Notifier（候補への実通知）・米国/EU/中国展開は引き続きfollow-up。
- (−) 両APIとも利用規約に従う必要がある（kkj.go.jpは明示的な利用規約
  ページあり、GEPSは政府標準利用規約に準ずると推定——ダウンロードページ
  自体に明示のライセンス表記は見つからなかった）。本コネクタは文書化された
  公開API/ダウンロード導線のみを使い、それ以外のスクレイピングは行わない。

## References

- ADR-2607070200（goyoukiki本体設計・「実データ取得はfollow-up」の記述元）
- 官公需情報ポータルサイト検索APIガイド V1.1（http://www.kkj.go.jp/api/）
- 落札実績オープンデータファイル仕様 令和8年3月版
  （https://www.p-portal.go.jp/pps-web-biz/UAB02/OAB0201）
- 本ADRとペアの.edn
