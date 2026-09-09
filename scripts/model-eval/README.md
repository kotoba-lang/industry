# scripts/model-eval — fleet に載せる候補モデルを実測で比べる

## 6公開サイトの言語別翻訳モデル

`manifest/public-site-locales.edn` の17言語には `manifest/public-language-models.edn`
の選択順を使う。既存の本ツールに公開文の翻訳経路を追加した。サイトの閲覧時や
Bot全体のモデル設定を変更するものではない。出力はレビューする翻訳案であり、
生成だけで翻訳品質の認定・法務承認・公開は成立しない。

```bash
# superproject rootで実行。表示だけなら認証情報は不要。
nbb --classpath scripts/model-eval scripts/model-eval/bench.cljs language-plan ja 100000
nbb --classpath scripts/model-eval scripts/model-eval/bench.cljs language-plan ja 1000000

# 入力は文字列値だけのJSON。公開原文を20キー・1500 UTF-8バイト以下に分割する。
# OPENROUTER_API_KEYは既存の認証環境から渡す。値をログやファイルに書かない。
BENCH_OUT=/tmp/public-translation-receipts nbb --classpath scripts/model-eval scripts/model-eval/bench.cljs translate ja public-source.json 1000000 --public-input

nbb --classpath scripts/model-eval scripts/model-eval/bench.cljs language-self-test
```

月間出力100万token未満は通常量、それ以上は大量生成という**運用上の初期値**。
実際のサイト流量を測った閾値ではない。通常は固定語検査に通った候補の実測応答時間、
大量生成は同じ原文に対する実請求額で順を決める。単発サンプルのため、普遍的な
品質・速度ランキングとは扱わない。更新時はモデル全体の公開利用量と、言語別の
検査・実費を別々に記録し、欠測を0にしない。母語・地域語の確認は別工程。

JSON形、符号・数値・通貨・URL・placeholderの個数、最低限の文字種を検査する。
失敗/打切り/HTTPエラーは次の明示候補へ進むが、不正出力は採用しない。
全候補が失敗すれば非0で終了する。英語原文は生成せずそのまま返す。
`translation-attempts.edn` にモデル・provider・generation ID・token・実費・失敗を、
`translations.edn` に採用した案を追記する。URLには空白区切りを要求する。

価格はproviderの `max_price` でも制限し、1回4096出力tokenまで。
1バッチの入場予算は$0.05の**推定値**で、アカウント全体のハード上限ではない。
請求不明の失敗は保守的な推定で計上し、実費超過が判明したら記録して停止する。
初期証拠は2026-10-09で失効し、更新するまで推論を止める。

再検査例（HTTP成功・stop・構造/固定語すべてを要求する）:

```bash
nbb --classpath scripts/model-eval scripts/model-eval/bench.cljs language-check 90-docs/reports/language-models-20260909/evaluations-v2.json 90-docs/reports/language-models-20260909/sample.json
```

調査正本: `90-docs/reports/260909-language-model-cost-usage.edn`。
元のOllama用 `speed/tasks/needle/ctx` の動作は維持している。

**「速い」「賢い」を人の印象や LLM-judge で決めない。** 出力を実際に走らせて
PASS/FAIL を取り、速度と文脈上限は ollama が返す実測値だけを使う。
初出は ADR-2608140200（laguna-xs-2.1 vs qwen3.6-35b-a3b、16GB M4 mac mini）。

## 使い方

ノードの ollama は localhost にしか bind していないのでトンネルを掘る:

```bash
ssh -N -L 11435:127.0.0.1:11434 judah &
nbb --classpath . bench.cljs speed  <model>   # decode / prefill を 3 回
nbb --classpath . bench.cljs tasks  <model>   # 15 問を実行して pass/fail
nbb --classpath . bench.cljs needle <model>   # 4K/16K/32K の retrieval
nbb --classpath . bench.cljs ctx    <model>   # num_ctx を上げて GPU 常駐と実 decode
```

| 環境変数 | 既定 | 用途 |
|---|---|---|
| `BENCH_ENDPOINT` | `http://127.0.0.1:11435` | 別ポートの ollama を測るとき |
| `BENCH_OUT` | `./bench-results` | 1 行 1 EDN の追記ログの置き場 |
| `THINK` | 無効 | `on` で reasoning を有効にする（cap は 16,000 tok） |
| `ONLY` | 全問 | `lru-cache,semver` のようにカンマ区切りで絞る |

## 読むときの注意（実際に踏んだもの）

- **`:truncated` を `:fail` と一緒に数えない。** reasoning model は thinking で
  `num_predict` を使い切り、本文を 1 字も出さずに終わることがある。ADR-2608140200 の
  初回測定では 15 問中 13 問がこれで、そのまま集計すれば正反対の結論になっていた。
  harness は `out-tok >= num_predict` を `:truncated` として分離する。
- **runtime を揃える。** ollama のバージョンが違うと同じモデルでも数字が動く。
  比較する 2 モデルは必ず同じ ollama・同じ options で測り直す。
- **GPU 常駐率だけで結論しない。** `ctx` モードが常駐率と実 decode を同じ行に出すのは、
  溢れた結果どれだけ遅くなるかが判断材料だから（実測: 68.6% で 45 → 4.3 tok/s）。
- **評価用に別ポートの ollama を立てたら、終了時に PID で殺す。**
  `pkill -f "<dir>/ollama serve"` は当たらない —— `cd <dir> && ./ollama serve` で
  起動したプロセスの argv は `./ollama serve` なのでパターンに一致しない。
  残った runner が 10 GB を掴んだままになり、**常駐側の推論が `Compute error` で
  落ちる**（ADR-2608140200 で実際に起こした）。`ps aux | grep llama-server` で
  runner ごと確認してから閉じる。

## 課題を足す

`tasks.cljs` の vector に 1 つ足すだけ。`:kind :python` なら `:harness` に
assert を並べて最後に `print('PASS')`、`:kind :json` なら stdin で JSON を受ける
checker を書く。**checker は決定論的にする** —— 判定に LLM を使った時点で、
このディレクトリを作った意味が消える。
