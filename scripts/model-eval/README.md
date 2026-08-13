# scripts/model-eval — fleet に載せる候補モデルを実測で比べる

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
