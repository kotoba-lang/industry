# 独立再構成 — 所見

Package B 開封前の暫定所見。判定基準は `PRESPEC_AGREEMENT_CRITERIA.md` に事前規定済み。

進捗: **91 claim すべて確定**（過不足なし）。導出値・横断整合の検証 105 件中
103 件 PASS、2 件 FAIL（いずれも下記の所見であり再構成側の欠陥ではない）。

| artifact | claim | 水準 |
|---|---|---|
| FIG1 | 6 | L1 独立再計算 |
| TABLE1 / FIG2 / FIG3 / FIG4 / TABLE2 / SuppTable S1 | 80 | L2 checkpoint 再構成 |
| SuppFig S1 | 5 | L3 視覚再構成 |

| ID | 内容 | 深刻度 |
|---|---|---|
| F-01 | 事後除外バリアントが analysis-ready データに表現されていない | 中 |
| F-02 | 打ち切り頻度値（`<0.0001` / `>100`）の規則が M03 に無い | 低 |
| **F-03** | **`DS_TABLE_S1_CURRENT` が投稿版と一致しない（checkpoint が古い）** | **中〜高** |
| F-04 | Panel A の二項 P が 4 有効数字でしか保存されていない | 低 |
| F-05 | カテゴリラベルの大文字小文字が M03 と checkpoint で不一致 | 低（実害あり） |

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

## F-03 — `DS_TABLE_S1_CURRENT` が投稿版 Supplementary Table S1 と一致しない

**深刻度: 中〜高／状態: OPEN**

M07 は「`DS_TABLE_S1_CURRENT` を起点とせよ」と定め、claim
`SUPPTABLE_S1_SHA256` も "Exact **current** source SHA-256" と規定する。しかし
payload の checkpoint をセル単位で投稿版と突き合わせたところ、**rs146572333 の
2 セルが異なる。**

| 列 | payload checkpoint | 投稿版 CSV |
|---|---|---|
| `EAS_EUR_Fold_Difference` | **38.6** | **38.7** |
| `Notes` | `Lead variant; East Asian enriched (38x vs EUR)` | AC/AN・full precision・丸め規則を含む長文 |

真値は投稿版 Notes 欄が明記するとおり **38.6515970515970516**（gnomAD v4.1.1、
EAS 84/5,180 ÷ 統合ヨーロッパ系プロキシ 33/78,656）。小数第 1 位への四捨五入は
**38.7** であり、**payload の 38.6 は切り捨て**である。原稿本文も SI も 38.7 で
一貫しており、**誤っているのは payload checkpoint のみ**。

行集合・列構成・他の 24 行は完全一致しているため、これは内容の相違ではなく
**バージョンの相違**である。payload は投稿前の版と判断される。

**影響**: `DS_TABLE_S1_CURRENT` を起点とする再構成は 38.6 を再現し、原稿の 38.7 と
食い違う。`SUPPTABLE_S1_SHA256` claim も古い版のハッシュになる。

**推奨**: custodian に対し、どちらが canonical かを確認し `DS_TABLE_S1_CURRENT` を
投稿版で再発行するよう要請する。これは answer key の開示ではないので result lock に
抵触しない。なお本監査は契約どおり payload を起点とし、claim 値は payload 由来の
ままとして相違を本所見に記録した。

---

## F-02 — 打ち切り頻度値の規則が契約に無い

**深刻度: 低／状態: DECLARED_CHOICE**

`gnomAD_EUR_MAF` に `<0.0001`、`EAS_EUR_Fold_Difference` に `>100` が入る
（該当は rs139129152 の 1 行、原稿の「ヨーロッパ系リファレンスでほぼ不在」と一致）。

M03 は「EUR がゼロで EAS が正なら東アジア系富化、欠測は未分類として記録せよ」と
定めるが、**打ち切り値は零でも欠測でもない**。規則が存在しない。

本監査は打ち切りを**境界値**として扱い、境界だけで閾値を超える場合にのみ分類を
確定させる実装とした（`<0.0001` かつ EAS = 0.0272 なら比は 272 超で 2 を確実に
上回るため「East Asian enriched」が確定する）。fold difference は点推定にできない
ため境界のままとし、検証も等値でなく**境界の整合性**で行った。

**推奨**: 次版で M03 に打ち切り値の規則を明記する。

---

## F-04 — Panel A の二項 P が full precision で保存されていない

**深刻度: 低／状態: OPEN**

claim matrix は全 claim に "Return full precision" を要求するが、
`DS_FIG3_PANEL_A` の `one_sided_binomial_P_vs_0.5` は **4 有効数字**でしか
保存されていない（例: K=100 で `0.135600`。独立再計算値は `0.135626512037`）。
同一ファイル内の Clopper-Pearson 境界は小数 6 桁で保存されており、**精度が
一貫していない**。

4 有効数字での比較では 4 つの K すべてが完全一致するため、値そのものは正しい。
本監査の claim には独立再計算した full precision 値を記載した。

---

## F-05 — カテゴリラベルの大文字小文字不一致

**深刻度: 低（ただし実害あり）／状態: OPEN**

M03 は `similar frequency` と小文字で規定するが、checkpoint は
`Similar frequency` と大文字始まりで格納する。分類自体は全行一致する。

**実害**: M03 の綴りでグループ化すると中央カテゴリが**黙って 0 件になり**、
Figure 2 の群区切りとラベルが崩れる。本監査でも中間版のレンダリングが実際に
壊れ、目視 QA で検出した。数値 claim には影響しない。

---

## 導出値の独立再計算（L2 で検証できたこと）

checkpoint の数値を転記するだけでは何も検証したことにならない。本監査は
**checkpoint 内の導出値を隣接する素の値から再計算**して照合した。

| 検証 | 結果 |
|---|---|
| Panel A の一致率 = n_concordant / K | 4/4 一致 |
| Panel A の片側正確二項 P（scipy 独立計算） | 4/4 一致（4 有効数字、F-04） |
| Panel A の Clopper-Pearson 95% CI | 4/4 一致（小数 6 桁） |
| Panel B の片側 P = 正規上側裾(Z) | 5/5 一致 |
| Panel B の α=0.05 参照 Z = 1.644854 | 5/5 一致 |
| Figure 2 のカテゴリを EAS/EUR 比から再計算 | 17/17 一致（大小文字を除く） |
| Table S1 のカテゴリを再計算 | 26/26 一致 |
| Table S1 の OR = exp(Beta) | 26/26 一致 |
| Table S1 の Case/Control 比 | 26/26 一致 |
| Table S1 の fold difference（区間重なり判定） | 26/26 整合 |
| Table 1 の行選択と順序を `DS_GWAS_CURRENT` から独立に再ランク | 10/10 一致 |
| LD clump サイズ合計 = Table S1 行数 | 26 = 26 |
| Figure 2 のリード集合 = LD primary のリード集合 | 17/17 一致 |
| Table S1 リードのカテゴリ内訳 = Figure 2 の内訳 | 5/5/7 一致 |

**Panel B の Z 自体は検証できていない。** M04 は Z を per-lead の符号と重みから
定義するが、その per-lead データは Package A に含まれない。L2 の射程外であり、
`SCOPE_LIMIT` として deviation log に記録した。Z から導かれる P と α 参照値は
検証済み。

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

## PGS 連鎖で検証できたこと（FIG4 / TABLE2 / SuppFig S1）

| 検証 | 結果 |
|---|---|
| Panel A の 12 集計値を個票 91 件から再計算（n・z_mean・z_sd(ddof1)・z_min・z_max・z_lt_0・bandwidth・grid 境界・bin 数・control 平均/SD） | 12/12 一致 |
| KDE バンド幅 = `case_sample_SD × n^(-1/5)` | 小数 9 桁まで一致 |
| KDE 600 点・正規 600 点の密度値を再計算 | 一致 |
| ヒストグラム 18 ビンの度数を再計算、合計 = 91 | 一致 |
| Panel B 全 7 tier を 6,000 行の PRSice2 スキャンと突合（SNP 数・R²・P） | 7/7 一致 |
| Table 2 が Panel B の表示丸めであること | 一致 |
| Table 2 の 3 AUC が独立の `DS_ROC_AUC_SOURCE` と一致、かつ PGS < 共変量 < フル | 一致 |
| ROC ラスタ 2 枚が 1920×1440 であること、対角較正が正方であること | 一致 |

**個票の取り扱い**: 個票スコアは 1 関数スコープ内でのみ読み、出力・記録・送信は
一切していない。返却パッケージに個票は含まれない（M05 の要求どおり）。

**軸較正の落とし穴**（deviation log に `DECLARED_CHOICE` として記録）: M06 の
canonical crop は**軸矩形**であり約 5% のマージンと枠線を含む。これをそのまま
`[0,1]` に写すと曲線が全辺で 5% 内側にずれる。crop 全体の ink 境界でも枠線を
拾って機能しない。対角参照線が定義上 (0,0)–(1,1) を張る性質で較正した——
参照線の端点を読むだけなので、M06 が禁じる「曲線座標のトレース・捏造」には
当たらない。実際に中間版でこのずれが出て、目視 QA で検出した。

---

## 残る作業（オーナー実施）

1. **F-03 を custodian と解決する。** どちらの Supplementary Table S1 が canonical か
   確認し、payload が古い場合は `SUPPTABLE_S1` claim を再発行データから
   導出し直すか判断する。**lock 前に処理すれば Round 2 を回避できる。**
2. **attestation に署名する。** 意図的に未署名で残してある（AI/cloud 条項の留保と、
   第三者独立性ではない旨の 2 点を明記済み）。
3. **AES-256 暗号化アーカイブを作成し SHA-256 を記録する。** パスフレーズを要するため
   自動化していない（認証情報の取り扱いは対象外）。
4. ハッシュ・completion block・署名済み attestation を custodian へ送付。
   custodian がハッシュを記録して初めて Package B を開封できる。

返却ツリー内の `RETURN_MANIFEST.tsv` / `SHA256SUMS.txt` は**平文ツリーの目録**であって
result lock ではない。lock は手順 3 で作る暗号化アーカイブの SHA-256 である。
