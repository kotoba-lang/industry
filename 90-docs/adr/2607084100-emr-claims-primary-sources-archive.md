---
id: adr-2607084100-emr-claims-primary-sources-archive
title: "ADR-2607084100: EMR/レセプト一次資料アーカイブ(DataLad+B2) kotoba-lang/emr-claims-primary-sources を新設し、kotoba-lang/insurance の医療機関コード検証番号実装を完成させる"
status: accepted
doc_type: adr
topic: emr-claims-primary-sources
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - kotoba-lang/insurance の 医療機関コード(iryokikan-bangou)検証番号アルゴリズム・点数表番号カテゴリ・医療機関(薬局)番号レンジ・都道府県番号(01-47+51-97)の一次資料根拠
  - EMR/claims実装の一次資料をコードから追跡可能にする恒久的アーカイブの置き場所と管理方式(DataLad+B2)
  - 4都県レガシー医療機関コード例外を実装しなかった判断
related:
  - orgs/kotoba-lang/emr-claims-primary-sources
  - orgs/kotoba-lang/insurance
  - 90-docs/adr/2607032000-cloud-itonami-insurance-real-estate-coverage.md
supersedes: []
superseded_by: []
---

# ADR-2607084100: emr-claims-primary-sources archive + iryokikan-bangou check digit

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Context

前回サイクル(`kotoba-lang/insurance@3f710c3`)で日本の医療機関コード(9桁)の
構造パースのみ実装し、検証番号(チェックデジット)算出と点数表番号の意味付けは
「一次資料の実例不足・資料間で矛盾する伝聞(医科=1/歯科=2/薬局=3 vs.
医科=1/歯科=3/薬局=4)のため要検証」として意図的に未実装のまま残していた。

今回、以下2件の一次資料PDFを実際に取得しテキスト抽出、該当箇所を目視確認・
検算した:

1. 厚生労働省「診療報酬請求書等の記載要領等について」等の一部改正について
   (2024-07-12付、https://www.mhlw.go.jp/content/12400000/001275316.pdf)
   別添２「保険者番号、公費負担者番号、公費負担医療の受給者番号並びに
   医療機関コード及び薬局コード設定要領」第４(医療機関コード及び薬局コード)。
2. 厚生労働省 2008年通知(https://www.mhlw.go.jp/topics/2008/03/dl/tp0305-1az_0004.pdf、
   cycle#3で保険者番号実装時に参照済みの旧版)。

## Decision

### 1. 一次資料の永続アーカイブ: `kotoba-lang/emr-claims-primary-sources`

上記2件のPDFを新規DataLadデータセット `kotoba-lang/emr-claims-primary-sources`
(text2gitコンフィグ)に格納し、GitHub上でpublic公開した。実体(PDFバイナリ)は
Backblaze B2(`gftdcojp-m365-annex`バケットを`m365-archive`/`mangaka-data`と
共有、`fileprefix=emr-claims-primary-sources/`で分離)、gitにはannexキーの
ポインタのみを残す既存のDataLad+B2運用パターン(CLAUDE.md「大容量バイナリの
扱い」節)を踏襲。README.mdに各PDFの出典URL・取得日・SHA-256・参照実装先を
明記し、実装判断が一次資料のどの箇所に基づくかを常にコードから逆引きできる
ようにした。

スコープはJP(今回)から開始し、将来US/EUのEMR/claims識別子仕様(NPIチェック
デジット、EHIC形式等)の一次資料も同データセットに追加していく前提の
ディレクトリ構成(`jp-mhlw/`、将来`us-cms/`・`eu-*/`)にした。

manifest登録: `manifest/repos.edn`の`:extra-projects`(kotoba-lang/insurance
直後)と`:datalad`map両方に追加。`nbb scripts/gen-west-manifest.cljs --entry
emr-claims-primary-sources`で当該entryのみの最小diffを生成、pin検証OK。

### 2. `kotoba-lang/insurance`の医療機関コード実装を完成

一次資料1の第４-５で確定した検証番号算出式(都道府県番号・点数表番号・
郡市区番号・医療機関番号の9桁に対し末尾を起点に×2,×1を交互適用、積が
2桁なら桁和に畳み込み、10から合計の下1桁を引く)を実装。一次資料1本文の
worked example(都道府県=34,点数表=1,郡市区=07,医療機関番号=1236 →
検証番号=2)で検算一致を確認済み(重み付き和28→10-8=2)。

これに伴い、医療機関番号のフィールド幅を旧実装の3桁から一次資料1で確定した
**4桁**に修正した(前回実装は「要検証」のまま置いていた3桁の推測値だった —
1000-2999/3000-3999/4000-4999という4桁前提のレンジ制約と整合させる必要が
あったため)。医療機関コード全体は 都道府県番号(2)+点数表番号(1)+
郡市区番号(2)+医療機関番号(4)+検証番号(1) = 10桁になる(実世界で言われる
「医療機関コードは10桁」という一般知識とも整合)。

点数表番号は一次資料1本文が明記する医科=1/歯科=3/薬局=4のみを実装対象とし、
二次資料(Wikipedia)が伝える2(健診等機関)・6(訪問看護)は一次資料1で確認
できないため対象外とコメントで明記した(未実装であり、当て推量で1/3/4以外の
値にラベルを付けない)。

医療機関(薬局)番号のレンジ制約(医科1000-2999/歯科3000-3999/薬局4000-4999、
中2桁または下2桁が90となる番号は欠番)も一次資料1第４-３の記載通りに実装。

都道府県番号表は別表２の記載に基づき、01-47(一次番号)に加え51-97
(代替番号、一次番号を使い切った都道府県から順次割り当て)も有効な現行コード
として追加した。

保険者番号(hokensha-bangou)の実装(cycle#3)については、一次資料1の
第１ worked example が2008年資料の実例と完全に同一の数値
(法別番号=06,都道府県番号=13,保険者別番号=048→検証番号=8)であることを
確認し、既存実装が独立した2つの一次資料で裏付けられていることを追加確認
した(実装変更なし)。

## 実装しなかったこと(正直な記録)

二次資料(Wikipedia「処方箋発行医療機関コード」)によれば、埼玉県・千葉県・
東京都・神奈川県の一部レガシーコード(1976年の10桁体系統一より前、1967年
導入の7桁コードに由来)は、都道府県番号・点数表番号を含めない「医療機関
コード7桁のみ」で検証番号を算出している例外があるとされる。一次資料1・2
いずれにもこの例外の明記を確認できなかったため、**この例外ロジックは
実装しない**。`kotoba-lang/insurance`のREADME/docstringに「4都県の一部
レガシーコードでは異なる算出方式の可能性があり未対応(要検証、出典:
二次資料)」と明記するに留める。

## Consequences

- `kotoba.insurance.jp`の`parse-iryokikan-bangou`が返すマップの
  `:iryokikan/kikan-bangou`が3桁文字列から4桁文字列に変わる(shape破壊的
  変更)。旧実装は「要検証」がREADME/docstringで明記された未完成機能で、
  他リポジトリからの依存が無いことを確認済み(monorepo全体grep)。
- 今後US/EUのEMR/claims識別子を実装する際は、まず
  `kotoba-lang/emr-claims-primary-sources`に一次資料PDFを追加してから
  実装する運用を標準にする(このADRが前例)。
