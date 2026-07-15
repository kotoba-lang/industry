# ADR-2607163000: cloud-murakumo-market-intel — SEC EDGAR全上場企業への本格拡大（28社→5,200社）、staleness/負売上高バグ修正、生アーカイブのスケール限界の発見

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

ADR-2607150200で構築したSEC EDGAR company-facts ingestion pipelineは、28社の
seedデータで実装・検証済みだった。オーナー確認済みのスコープ（SEC EDGAR全
上場企業8000社超）を実際に満たすため、フル投入を実行した。

## Decision

### 1. 初回フル実行(8012社)がsanitize gateで全体abort — 想定通りの安全動作

`clojure -M:feed:ingest`(引数なし)を実行、8012 CIK中5203社のfetchに成功
(2809社は`companyfacts` APIから404——IFRS専用海外発行体/シェル企業、正常な
挙動)、HTTPエラー0件。しかし**10社が`sane-entity?`チェックで「insane」と
判定され、`data/company-facts.edn`への書き込みが全体abortされた**——これは
既存のsanitize-or-abort設計(ADR-2607150200)が正しく機能した結果であり、
部分的に汚染されたデータの静かなcommitを防いだ。

### 2. 根本原因調査 — 2種類の異なる問題

- **(A) 8社**: iShares Gold/Silver Trust、US Oil/Gas/Brent/Natural Gas Fund等の
  **商品トラスト/ETF**。実際に確認: iShares Gold Trustの`us-gaap:Revenues`
  タグは2013-12-31時点の値(-6.06億ドル)が「そのタグ内で最新」——このトラストは
  近年`Revenues`タグ自体を使わなくなり(`GainLossOnInvestments`/
  `NetIncomeLoss`は2025年まで最新データがある)、`revenue-tag-priority`の中で
  唯一データが存在するタグがこの13年前の古い値だけになっていた。`Assets`
  タグも2014年が最新。既存の`pick-annual`(候補タグ間で最新を選ぶロジック、
  ADR-2607150200で確認済みのApple stale-tagバグ修正)自体は正しく動作して
  いたが、「選ばれた最新候補がそれでも実行時点から見て極端に古い」ケースを
  弾く仕組みが無かった——符号チェックがたまたまカナリアになっただけで、
  根本問題はstaleness。
- **(B) 2社**: Beneficient(CIK 0001775734)とVIP Play, Inc.(CIK 0001832161)。
  実際に確認: 両社とも**現在時点(2026-03-31/2025-06-30)で正確に負の
  売上高**を報告している——金融/投資会社でcontra-revenue項目や公正価値
  評価損がRevenuesラインに含まれるケースは会計上実在する。これはバグでは
  なく正当なビジネス実態。

### 3. 2つの独立した修正

- **staleness filter**: `pick-annual`の選択結果の`:end`年が現在年から
  5年以上古ければ、その値を採用せずnilにする(=フィールドを単に欠落として
  扱う、古い値を「現在の値」として偽らない)。`.cljc`のportable/no-wall-clock
  規律を守り(`market-analyst/policy.cljc`の`staleness-violation`と同じ
  パターン)、現在年はJVM専用の`ingest.clj`側(`market-intel.ingest/now-year`)
  からのみ供給する。revenue/assets/net-income全てが対象(iShares Gold Trustは
  Assetsもstaleだった)。
- **`non-negative-financials-ok?`のrevenue側チェックを削除**: assets側の
  non-negativeチェックは残す(資産が負というのは会計上ほぼ常に不正データを
  意味する真の不変条件)。revenueは実例(Beneficient/VIP Play)が示す通り
  正当に負になり得るため、符号での一律rejectをやめた。

### 4. フル再実行 — 8021社中5200社を実際に取り込み

修正後、10社全てを個別に再検証(8トラストはstaleな値が正しく省略、2社は
負のrevenueがそのまま採用)してから、フル8021社を再実行。結果:
fetch ok=5200、not-found=2821、errors=0(0.0%エラー率)、sanitize gate
**全通過**、`data/company-facts.edn`に5200社の要約財務ファクトを書き込み。
これは単一commitとして最大規模のデータ変更のため、既存の運用ルール
(「初回の大量投入は自動マージせずPRで人間レビュー」)に従いPRを開いてから
マージした。

### 5. 生アーカイブ(`etzhayyim/gov.sec.edgar`)の全社規模化を保留 — 重要な発見

同じフル実行の副産物として`gov.sec.edgar`にも生JSONを書き込んだところ、
**`.git`が11GBに達することが判明した**。大手金融機関等は1社あたり8MB超の
XBRL全履歴JSONを持ち、6989社分を積み上げると計11GB規模になる。

ADR-2607151950では28社サンプル(11.6KB)を根拠に「DataLadの想定する大容量
バイナリとは桁違いに小さい」と判断し`text2git`(テキストは通常gitで管理)を
採用したが、**この判断は小サンプルに基づく誤りだったことが今回判明した**。
`text2git`規約は「バイナリだけをannex/B2へ、テキストはgitのまま」という
ルールのため、大きなJSONテキストであってもgit履歴に直接乗ってしまう——
これはまさにCLAUDE.mdの大容量バイナリ運用方針(`large-binary-datalad`
skill)が防ごうとしている「clone/pull肥大化」そのものである。

**この11GBのcommitは一切pushしていない**(ローカルのみで作成・確認後に
破棄)。`etzhayyim/gov.sec.edgar`のmainは引き続き元の28社のまま。全社規模の
生アーカイブを実現するには、`.gitattributes`を「大きいテキストファイルも
annex化する」設定に変更した上で、ADR-2607151950で既に記録済みのB2
special remote未確立問題(1Password Master keyが非対話で解決できない)を
先に解消する必要がある——両者とも本ADRのスコープ外、follow-upとする。

## Consequences

- (+) `cloud-murakumo-market-intel`が28社→5200社に本格拡大、当初オーナーが
  確認したスコープ(SEC EDGAR全上場企業)に大きく近づいた。
- (+) staleness filter/負revenue許容という2つの修正は、今後追加される
  companyについても同様の問題を構造的に防ぐ(10社限定のhardcode回避で
  終わらせなかった)。
- (+) 大容量データセットの実際のスケール特性(小サンプルでの見積もりの
  危険性)という、今後同種の判断をする際に活きる教訓を得た。
- (-) `gov.sec.edgar`の生アーカイブは28社のまま——本来の目的(全社の生
  レスポンス保全)を全社規模で満たせていない、follow-up。
- (-) 2821社(8021中)はXBRL companyfactsデータが無く要約ファクトを持たない
  (IFRS専用海外発行体/シェル企業、正直な既知の限界)。
- superproject への反映: 本ADRのみ。`cloud-murakumo-market-intel`の
  west.yml pinは既存entryの前進のみ(該当あれば別途`--entry`で反映)。

## 代替案と不採用理由

- **staleな値でも符号が正なら黙って通す**: iShares Gold Trustの2013年の
  revenueがたまたま正の値だったら今回のsanity checkに引っかからず、
  「2013年の数字を2026年の現在値として報告する」という同種の誤りが
  検出されないまま混入していた可能性が高い。符号チェックをカナリアとして
  頼るのではなく、根本のstaleness自体を直接検出する設計にした。
- **11GBのままpushしてから後で.gitattributesを直す**: git履歴は追記専用で、
  一度pushした大容量blobは履歴書き換え(禁止事項)無しには消えない。
  push前に発見できたため、この禁じ手を避けられた。
- **B2が使えないので生アーカイブ自体を諦める**: 28社分は既に価値のある
  実データとして存在しており、全社規模化を今すぐ強行せず「B2解決後の
  follow-up」として明示的に保留する方が、11GBの汚染よりずっと安全。

## References

- `gftdcojp/cloud-murakumo-market-intel` PR #5(staleness/negative-revenue
  修正、merge commit `d2c294e`)+ PR #6(5200社データ、merge commit
  `caf9d49`)
- `90-docs/adr/2607150200-cloud-murakumo-market-intel-sec-edgar-company-facts.md`
  (前段、company-facts pipeline新設)
- `90-docs/adr/2607151950-etzhayyim-gov-sec-edgar-raw-xbrl-archive.md`
  (前段、11.6KBサンプルに基づくtext2git採用判断——本ADRが規模面の誤りを
  修正)
