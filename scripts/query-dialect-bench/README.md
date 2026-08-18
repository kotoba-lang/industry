# scripts/query-dialect-bench — LLM は kotobase 方言のクエリを書けるか、実データで測る

**ADR-2608189300 が「次の一手は測定」と書いた、その測定。** あの ADR は agent の
query 入口を kotobase 方言（Datomic-shaped EDN Datalog）に決めたが、その根拠のうち
1 つは**測っていない主張**だった:

> 素の text-to-query 精度は Cypher > SPARQL > Datalog 系。それでも方言を採るのは、
> 方言側の不利が **schema 注入 + few-shot + validator + repair loop** で埋まるのに対し、
> Cypher の injection / unbounded path / redaction 再実装を後から塞ぐのは高いから。

前半は一般知識、**後半はこの workspace 固有の賭け**である。ここが測る対象は後半 ——
*schema と validator を足したとき、方言はどこまで届くか*。

## 何を測るか

| 条件 | prompt | 意味 |
|---|---|---|
| `bare` | 方言名と記法規則だけ | schema を渡さない素の状態 |
| `schema+repair` | 属性一覧（24 個）+ few-shot 3 例 | ADR が想定した実運用の形。加えて validator が弾いたら**構造化エラーを返して最大 2 回**書き直させる |

判定は**実行結果の集合一致**（順序非依存）。reference query が既知正解で、
別解でも同じ集合を返せば pass —— これは正しい。問われているのは書き方ではなく答え。

## 結果（2026-08-18、初回）

被験者 **qwen3.8-27b**（`murakumo-main` の当時の解決先）、temperature 0、20 問。

| 条件 | pass / answered | |
|---|---|---|
| **bare** | **5 / 11 = 45.5%** | 方言名と記法規則だけ |
| **schema + few-shot + validator + repair(≤2)** | **16 / 18 = 88.9%** | ADR が想定した実運用の形 |

**ADR-2608189300 D5 の条件節は支持された。** 内訳が要点:

- **bare の失敗 5 件はすべて属性の捏造**で、**5 件とも validator が実行前に捕まえた** ——
  `company/revenue`（実際は `company/revenue-usd`）/ `company/isic-code`（実際は
  `company/isic`）/ `market-intel/lei` / `repo-taxonomy/jurisdiction`（dataset 名を
  接頭辞にした属性を発明）。**何も実行せずに捕まる**のが EDN 面の実利で、それが
  数字として出た。schema 条件では repair loop がこの 5 件をすべて直した。
- **validator が捕まえられないのは意味の誤り。** schema 条件の残り 2 件は
  `Missing rules var '%' in :in` と `Cannot compare company/sic to <` ——
  構造検証は属性の捏造を止めるが**意味の誤用は止めない**。これが残余ギャップ。

**分母**: 40 call のうち **11 件が Cloudflare の HTTP 524** で落ちた。モデルの失敗では
ないので分母から外す。転送障害を失敗として数えると 25.0% / 80.0%。
（`--self-test` と同じ理由で、この 2 つを両方印字する。）

## 使い方

```bash
# reference の健全性だけ（LLM を呼ばない）
nbb --classpath ".:scripts/nbb_compat" scripts/query-dialect-bench/bench.cljs \
  --questions scripts/query-dialect-bench/questions.edn \
  --plane-script scripts/query-dialect-bench/plane.cljs \
  --out /tmp/off.edn --offline

# 本番（LLM を呼ぶ）
nbb --classpath ".:scripts/nbb_compat" scripts/query-dialect-bench/bench.cljs \
  --questions scripts/query-dialect-bench/questions.edn \
  --plane-script scripts/query-dialect-bench/plane.cljs \
  --out scripts/query-dialect-bench/result.edn
```

| flag | 既定 | 意味 |
|---|---|---|
| `--plane-script` | `manifest/edn-query.cljs`（本番の面） | 下記のとおり実質 `plane.cljs` 一択 |
| `--limit N` | 全問 | 先頭 N 問だけ |
| `--max-repair` | 2 | repair の回数 |
| `--offline` | — | LLM を呼ばず reference の検証だけ |
| `--self-test` | — | validator と抽出器の**両方向**を 14 fixture で検査（下記） |

```bash
# 検査そのものを検査する。measurement を回す前に必ず通す
nbb --classpath ".:scripts/nbb_compat" scripts/query-dialect-bench/bench.cljs --self-test
```

モデルは **`murakumo-main` alias を実行時に解決**する（CLAUDE.md: concrete な model id を
焼かない）。`BENCH_LLM_ENDPOINT` / `BENCH_LLM_MODEL` で上書きできる。

## `plane.cljs` は subset である（黙らせない）

`plane.cljs` は本番の面の代わりではない。読むのは**本番と同じ実ファイル 2 本**で、
変換規則も同じ:

```
market-intel   <market-intel repo>/data/company-facts.edn   SEC EDGAR 財務  5,200 entity
repo-taxonomy  manifest/repo-taxonomy.edn                    repo 3 面分類   5,529 entity
```

失われているのは**他の dataset との join 可能性**だけで、この 20 問はその 2 つしか
参照しない。**subset であることは起動のたびに stderr で名乗る。**

なぜ分けたか —— **本番の面がベンチに使えなかった**。実測 2026-08-18、
`manifest/edn-query.cljs` はクエリ 3 本で **600 秒の timeout に到達して未完了**
（load average 246〜281 の環境）。subset 面は同じ 2 dataset を **38 秒**で組む。
**40 分かかるベンチは二度と回らない** ——「落ちない gate は劇場」の裏返しで、
**回せない gate も同じだけ無内容**である。

⚠ **この数字を定数として引用しない。** 測ったのは load 246〜281 のこのマシンでの
1 回であって、面の恒久的な性質ではない。必要なら測り直す。

## 読むときの注意（実際に踏んだもの）

- **`:skipped-truncated` を `:wrong-answer` と一緒に数えない。** このエンドポイントは
  推論モデル（Qwen3.8）で、思考は `reasoning_content` に入り `content` とは別枠。
  **`max_tokens` を使い切ると `content` が空文字で返る。** これを「答えられなかった」と
  同じ値で数えると、**予算不足がモデルの失敗に化ける**。harness は `finish_reason=length` を
  別立てにする。
  → 同じ罠は `scripts/model-eval/README.md` が先に文書化していた（ADR-2608140200、
  15 問中 13 問がこれで、集計すれば正反対の結論になっていた）。**先にそちらを読めば
  自分で踏まずに済んだ。**
- **reference が 0 行の問いがあれば、結果を出さずに exit 2。** 0 行は間違ったクエリとも
  一致するので、その問いでは pass を判定できない。
  → これは実際に**問いのバグを捕まえた**。`company/legal-name` は repo entity ではなく
  `:entity/kind "organisation"` という**別の entity 面**にあり、パスは `repo/path` では
  なく `entity/repo` だった。0 行を合格と区別しない設計が、そのまま検出器になった。
- **executed < asked なら結果を出さない**（evidence floor）。飛ばした問いを分母から
  黙って落とさない。
- **測ったときの load average を値の隣に置く。** このマシンは並行 agent・推論・
  マイニングが同居する。

### validator が 2 回壊れていた —— どちらも「指摘」の顔をして出てきた

`--self-test` が在る理由。実測 2026-08-18、この harness は**同じ日に 2 通りの壊れ方**をした。

1. **正しいクエリを拒否した。** 最初の validator は *query 中の全文字列*を属性として
   allowlist と照合していたので、`[?e "source/dataset" "market-intel"]` の**値**
   `"market-intel"` を未知属性として報告した。schema 条件 20 問のうち **18 問が
   この偽のエラーで差し戻され**、repair loop はモデルに嘘を返していた。
   **約 60 分走った測定が丸ごと無効**になった。
   → 属性は **data pattern `[?e <attr> ?v]` の位置 1 だけ**を見る。述語節
   `[(>= ?r 1e11)]` は data pattern ではない。
2. **何も検査せずに「妥当」を返した。** その修正で `:where` の位置を
   `(.indexOf (to-array q) :where)` で求めたが、**JS の `indexOf` は boxed な cljs
   keyword を strict equality で比べるので -1 を返す**。節を **0 件**走査したうえで
   nil（= 妥当）を返していた。**測れなかった検査が、測って問題が無かった検査と
   同じ値を返す**（ADR-2608136000 の 5 問の 2）。
   → cljs の `=` で数える。そして**この 2 つ目は、1 つ目を直した結果として入った** ——
   直した検査は、直した瞬間に検査されていない。

**教訓は「validator を書いたら validator を検査する」**であって、注意深く書けという
ことではない。1 つ目は注意深く書いても起きるし、2 つ目は 1 つ目の修正が生んだ。

### 高い段の結果を、安い段の失敗で捨てない

修正版を回したら、**stage 4（実行）で 1 本のクエリが DataScript に拒否され、
batch 全体が落ちて 90 分ぶんの推論結果が消えた。** harness は正しく exit 2 で
refuse したが、refuse する前に**捨ててはいけないものを捨てていた**。

この harness の段は費用が桁で違う:

| 段 | 費用（実測、load 20〜280） |
|---|---|
| 推論（40 call） | **60〜90 分** |
| 面のロード | 38 秒 |
| クエリ実行 | ミリ秒 |

**安い段の失敗が高い段の成果を消す構造にしない。** 直したのは 3 点:

1. **実行の前に生成クエリを `<out>.generated.edn` へ書き出す。** 実行段が何をしても
   推論結果は残る。
2. **`q*` は 1 本ずつ try/catch する。** 失敗したものは `{:query-error "…"}` という
   **印**を返す —— 空の結果集合で返すと「0 件だった」と区別できない。
   grade も `:query-error` を `:wrong-answer` と別立てにする（エンジンが拒否した
   ことは「違う答え」ではない）。
3. **`:empty-find` を validator で捕まえる。** `[:find :where …]` は構造としては
   ベクタで `:find` 始まりで `:where` もあるので前の版を通り抜け、DataScript が
   `Cannot parse :find` で throw していた。実行前に弾く。

これも 5 問の 2（実行できないとき何を返すか）だが、**答えるのは harness 全体では
なく個々の item** である、という粒度の話。全体で refuse するだけでは粗すぎた。

### 4 つ目 —— harness が、自分の存在理由を集計層で破っていた

初回の結果を印字したとき、pass 率の分母は `asked`（20）だった。40 call のうち
**11 件は Cloudflare の HTTP 524 で落ちており**、これはモデルが間違えた証拠では
ない。それを失敗として数えると bare は **45.5% ではなく 25.0%** に見える。

**「測れなかったものを、測って落ちたものと同じ値で数えない」** —— この harness が
存在する理由そのものを、harness の**最後の 3 行**でやっていた。検査の本体を何度
直しても、集計層は別に検査されていない。

直したのは 2 点: 主指標を `pass / answered` にして測定不能件数を明示する
（`pass / asked` も併記するが主指標にしない）、そして **gateway エラーは 2 回まで
再試行する**（モデルの能力と無関係なノイズを測定値に入れない）。

**この日、同じ class を 4 回踏んだ。** 1 回直すたびに、直した箇所の隣で再発している。
ADR-2608136000 が「一度直した種類の誤りが、次の話題で再発する」と書いているのは
比喩ではない。

## 「検査を書く前・緑を信じる前の 5 問」への回答（CLAUDE.md / ADR-2608136000）

1. **入力が無いとき** — `questions.edn` が空なら exit 2。pass にしない。
2. **実行できないとき** — LLM 到達不能 / 面が組めない / 生成クエリを実行できない →
   すべて **exit 2**（0 でも 1 でもない = 「答えられなかった」専用）。
3. **エラー本文を捨てない** — LLM の HTTP エラーは status ではなく本文を記録する。
4. **飛ばしたと落ちたを区別する** — `:pass` / `:wrong-answer` / `:invalid` /
   `:malformed` / `:query-error` / `:skipped-truncated` / `:skipped-llm-error` を
   別の値にする。とくに `:query-error`（エンジンが拒否）と `:wrong-answer`
   （実行できて答えが違う）を混ぜない。
5. **両方向を出したか** — reference query 自身を同じ比較器に通す。加えて
   `--self-test` が validator 10 件（通すべき 4・弾くべき 6）と抽出器 5 件を検査する。
   **通すべきものが通ることを見ない検査は、上の 1 つ目の壊れ方を見逃す。**

## これが測っていないもの

- **3 方言の比較になっていない。** ここにあるのは **1 方言の絶対値**であって
  方言間の相対値ではない。
  ⚠ 当初この理由を「surface がどれも deploy されていないから」と書いたが、
  **2026-08-18 に実測して訂正した** —— `sparql` / `cypher` / `graphql` /
  `gremlin`.kotobase.net はいずれも **401（Bearer 認証）で live**、`datomic` は 200。
  `kotobase-worker-shell` の `kotobase-protocols-worker.query` が 4 surface を
  deploy shell に合成している。ADR-2608039975 の「deploy shell が1つも無い」は
  2026-08-03 時点の事実で、今の事実ではなかった。
  実際に阻んでいるのは **①live surface が見るのは kotobase graph でこの 2 dataset の
  面ではない ②Bearer token ③harness が HTTP を叩かない（面 script を子プロセスで
  回す形）** の 3 つ。前より小さく具体的な gap。
- **1 モデルの 1 回の測定**。qwen3.8-27b（`murakumo-main` の現在の解決先）1 本、
  temperature 0、20 問。他のモデルでの再現は取っていない。
- **prompt の最適化をしていない。** few-shot は 3 例で固定。ここを詰めれば数字は動く。

## 隣接するものとの境界

- **`scripts/model-eval`** — *どのモデルを fleet に載せるか*（速度・文脈上限・汎用
  コーディング 15 問）。こちらは *どの query surface を agent の入口にするか*。
  測る対象が違う（モデル vs surface）。truncation の扱いだけ同じ規律を共有する。
- **`manifest/edn-query.cljs`** — 本番の query 面。ここはその面の**部分集合を**
  ベンチ用に組み直したもので、置き換えではない。
