# ADR-0012: Power Dynamics モデル — dyad(関係辺) + hypothesis(反証可能な latent)

- Status: Accepted (2026-06-11)
- 関連: ADR-0010 (life graph), facts/people.edn (人物レジストリ)

## 課題

人物レジストリ (23名) は「誰か」を持つが、「その人とどういう力学にあるか」を持たない。
立場・背景・潜在変数 (latent) ・力の非対称を、minimax/シャノン分析に使える形で構造化したい。

## 決定: 3つのモデリング原則

### 1. 力は person の属性ではなく dyad (辺) の属性

Emerson の資源依存理論: **P(A over B) = Dep(B on A)** — 力は依存の非対称として
しか存在しない。よって `:person/power` のようなスカラーは作らず、
self↔相手 の **dyad エンティティ**に以下を載せる:

| 属性 | 型 | 意味 |
|---|---|---|
| `:dyad/self-dependence` | 0..1 | 自分→相手の依存 (代替不能性 × 重要度 × 時間切迫) |
| `:dyad/their-dependence` | 0..1 | 相手→自分の依存 |
| `:dyad/power-bases` | keyword* | French & Raven: `:expert :legitimate :reward :coercive :referent :informational` |
| `:dyad/incentive` | string | 相手の報酬構造 (タイムチャージ/成功報酬/月謝/感情/評判) |
| `:dyad/alignment` | -1..1 | 利害の一致度 (報酬構造から導く。善意の仮定ではなく) |
| `:dyad/info-asymmetry` | keyword | `:they-hold` `:we-hold` `:balanced` — 専門知の偏在 |
| `:dyad/switching-cost` | keyword | `:low :mid :high :prohibitive` — BATNA の質 |
| `:dyad/worst-case` | string | この関係が最悪化した時に起こること (**minimax の直接入力**) |
| `:dyad/lever` | string | 非対称を是正する自分側の手 (依存を下げる / 相手の依存を上げる) |

導出値 (保存しない、Datalog ビューで計算):
**balance = their-dependence − self-dependence**。負に大きいほど露出。

### 2. latent は「反証可能な仮説」として保持する

立場・背景の推定 (「竹下先生は実務担当で最終判断は水野パートナー」等) を
評点として固定すると、間違いが構造に焼き付く。**hypothesis エンティティ**:
`:hypothesis/text` + `:hypothesis/confidence` + `:hypothesis/evidence` (ref→email/blob)
+ `:hypothesis/falsifier` (何を観測すれば棄却されるか) + `:hypothesis/status`。

falsifier があることで「次に何を確認すべきか」(= 情報利得が最大の観測) が
クエリで列挙できる。これがシャノン分析の実装形。

### 3. 家族 dyad は力学行使の対象としない (倫理ガードレール)

co-parent (美加恵) の辺は self-dep/their-dep とも高く alignment も高い
「相互依存・高信頼が正常」な辺であり、lever は「信頼残高への定期投資」のみを書く。
**レバレッジ最適化の対象は係争相手とベンダーに限る。** 家族辺の worst-case は
guardrail (00_nodoka_wellbeing) の直撃経路として minimax にのみ使う。

## 計測可能な信号への接続 (将来)

メール ingest (ADR-0009) が貯まれば、dyad ごとに通信パターンが KPI 化できる:
- 応答レイテンシの非対称 (どちらが先に・速く返すか)
- スレッド開始者の偏り / 依頼と応諾の比率
- CC 階層 (誰が誰を CC に入れるか = 組織内の決裁ライン推定)
これらは `:kpi/metric :dyad.latency など` で蓄積し、手動の confidence を
観測で置き換えていく。curated 評点 → 計測値への置換が成熟の方向。

## 採用しない案

- person への power スコア直付け: 関係ごとに力学が違う事実を表現できない。
- グラフ中心性 (PageRank 等) での自動算出: メールグラフが揃うまで入力が無く、
  揃っても「誰が誰に依存するか」は流量だけでは出ない。補助指標に留める。
- LLM による全自動評価: 評価は curated + evidence ref + falsifier で人間が校正する。
  自動化するのは「falsifier に該当する観測の検出」のほう。

## ファイル

- `personal/facts/dyads.edn` — dyad + hypothesis の curated SSoT (annex/暗号化)
- `personal/bin/datomic/queries/power.edn` — balance / risk / 検証アジェンダのビュー
- loader: `warehouse.load/dyad-tx, hypothesis-tx`
