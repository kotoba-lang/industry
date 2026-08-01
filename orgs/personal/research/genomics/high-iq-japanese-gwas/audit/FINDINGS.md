# 独立再構成 — 所見

Package B 開封前の暫定所見。判定基準は `PRESPEC_AGREEMENT_CRITERIA.md` に事前規定済み。

進捗: 91 claim 中 **6 件確定**（FIG1 / L1）。

---

## F-01 — 事後除外バリアントが analysis-ready データに表現されていない

**深刻度: 中（下流 artifact に波及しうる）／状態: OPEN**

### 事実

`DS_GWAS_CURRENT` には `P < 1×10⁻⁵` のバリアントが **27 件**含まれる。原稿は
「26 retained」と記載しており、1 件多い。差分は 1 件で、`seq-rs376128944`
（chr14:55,433,073、P ≈ 4×10⁻⁶、27 件中 20 位）である。

このバリアントの `qc_status` は **`PASS_DIRECTLY_GENOTYPED`** であり、
除外は当該フィールドに一切表現されていない。

除外の記録が存在するのは次の 2 箇所のみで、いずれも自由文である。

1. 原稿 Methods —「事後の技術的レビューにより rs376128944 を除外した。アレイ由来の
   頻度が日本人リファレンスデータと著しく不一致であり、シグナルが近傍マーカーと
   高度に一致していたため」
2. `table_s1_integrated_variant_annotation.tsv` の `rs75790544` 行の `Notes` 列 —
   "Replacement lead for WDHD1 locus after rs376128944 exclusion (probe artifact)"

**method contract（M01–M07）には一切言及がない。** パッケージ内の機械可読な指示は
存在しない。

### 集合の突合

| 集合 | 件数 | rs376128944 |
|---|---|---|
| `DS_GWAS_CURRENT` の P < 1e-5 | 27 | 含む |
| `table_s1_integrated_variant_annotation.tsv`（payload） | 26 | 含まない |
| 投稿版 `Supplementary_Table_S1.csv` | 26 | 含まない |

payload 版と投稿版の Table S1 は集合として完全一致。27 − rs376128944 = 26 で
下流と整合する。**つまり除外は下流 checkpoint には適用済みで、上流の
analysis-ready データにのみ未適用。**

### なぜ問題か

M01 は L1 として `DS_GWAS_CURRENT` からの再計算のみを指示し、有効性判定は
「有限の position と 0 < p ≤ 1」だけと定める。さらに「qc_status を保持せよ」
「現行スナップショットに存在しないバリアントを黙って復元するな」と明示する。

**この契約に literal に従う再構成者は、rs376128944 を除外できない。** 除外を
指示する条項がなく、除外を示すフラグもないためである。結果として Manhattan は
示唆的閾値下に 27 点を描き、原稿の記述（26 retained）と食い違う。

本監査は契約どおり 27 点で描画し、deviation log に `OPEN_FINDING` として記録した。

### 影響範囲

**影響しない**（いずれも全 362,797 バリアントまたは GW 有意 1 件に基づくため）:
`FIG1_VALID_VARIANTS`、`FIG1_LAMBDA_GC`、`FIG1_SOURCE_SHA256`、閾値定数、パネル数。

**影響しうる**: canonical Figure 1 が 26 点で描かれている場合、視覚的意味の照合で
不一致となる（Package B 未開封のため現時点では判定不能）。加えて、`DS_GWAS_CURRENT`
から retained 集合を導出する再構成者は 27 件を得て、17 遺伝子座という LD 構造も
再現できない。ただし FIG2 / FIG3 / SuppTable S1 は checkpoint 由来なので、
checkpoint を起点にする限りこの影響は出ない。

### 推奨

- **短期**: canonical Figure 1 が 27 点か 26 点かを custodian に確認する。これは
  answer key の開示ではなく、契約の曖昧性の解消であり、result lock に抵触しない。
- **恒久**: 次版パッケージで `qc_status` に機械可読な除外値
  （例 `EXCLUDED_PROBE_ARTIFACT`）を導入するか、M01/M02 に除外を明記する。
  自由文の Notes 欄と原稿 prose だけでは、独立再構成者に伝わらない。

---

## 原稿記載値との一致状況（FIG1）

`PRESPEC_AGREEMENT_CRITERIA.md`「原稿記載値との事前比較」に基づく参考照合。
Package B との正式突合ではない。

| 項目 | 独立再計算値 | 原稿記載 | 判定 |
|---|---|---|---|
| 解析バリアント数 | 362,797 | 362,797 | 一致 |
| λGC | 1.0536825577354483 | 1.054 | 一致（表示丸めで一致） |
| ゲノムワイド有意 | 1 件（rs146572333） | 1 件（rs146572333） | 一致 |
| 最小 P 値 | 1.1673 × 10⁻⁸ | 1.1673 × 10⁻⁸（Firth 感度） | 一致 |
| P < 1e-5 | 27 件 | 26 retained | **F-01 参照** |

有効性フィルタによる除外は 0 行。重複バリアントキー 0 件。
`qc_status` は全行 `PASS_DIRECTLY_GENOTYPED`。

---

## 入力ハッシュ監査

全 **25 データセット**が `SHA256SUMS.txt` と **MATCH**（`04_INPUT_HASH_AUDIT.tsv`）。
不一致・欠落なし。

なお `README.md` に「26 datasets」と記載していたのは誤りで、
`DATASET_MASTER_INDEX.tsv` のデータ行数は 25 である（修正済み）。

---

## 未確定

FIG2 / FIG3 / FIG4 / TABLE1 / TABLE2 / SuppTable S1 / SuppFig S1 の 85 claim。
`04_INPUT_HASH_AUDIT.tsv` は全 25 データセット分を先行して確定済みなので、
以降の artifact は入力の同一性を再検証せずに着手できる。
