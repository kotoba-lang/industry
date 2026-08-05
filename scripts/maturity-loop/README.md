# maturity-loop

**テストではなく、テストのテスト。** `mutations.edn` に書いた壊し方を当てて、
**赤くなることを確認する**。赤くならなかったものは「その不変条件を守っている
テストが無い」という具体的な TODO として報告する。

```bash
nbb scripts/maturity-loop/run.cljs                  # 全 suite
nbb scripts/maturity-loop/run.cljs --only inga      # repo 名で絞る
nbb scripts/maturity-loop/run.cljs --keep-worktree  # 調査用に worktree を残す
```

## なぜ要るか

CLAUDE.md は fleet gate について「**gate は「落ちること」を確かめてから landed と
する。落ちない gate は劇場**」と書いている。原則は書かれているが、それを
**時間をまたいで保つ機構**が無かった。

テストは静かに噛まなくなる。実装が変わって経路が通らなくなっても、テストは緑の
ままだからである。**緑を見ているかぎり、永久に気づけない。**

2026-08-05 の 1 セッションで、これが 2 回起きた:

1. `prolly-tree` の inclusion proof を書いたとき、最初の版は descent 規則を壊しても
   落ちなかった。木が well-formed なら CID 連鎖だけで inclusion は健全なので、
   descent 検査は「本物の拒否」になっていなかった。手で木を細工した shadow-path
   テストを足して初めて赤くなった。
2. `codebase` を `.cljc` にしたとき、cljs テストが f32/f64/i64 の decode を一度も
   通っていなかった。`Double/longBitsToDouble` に戻しても cljs は WARNING を出す
   だけで通過した（その経路が実行されないので）。

どちらも mutation を手で当てて初めて分かった。手でやる限り、次は忘れる。

## 共有 checkout を壊さない

ソースを書き換える道具なので、事故ると他人の作業が消える。3 重に守っている:

1. **使い捨て worktree で作業する。** 共有 checkout は読まない・書かない。この
   マシンは多数の agent セッションが並行しており、共有 tree を触る定期ジョブは
   他人の WIP を壊す（実測: 2026-08-05 の 1 日で west.yml の pin 退行が 3 回、
   別々のセッションから発生している）。
2. **worktree は west の pin から切る。** 「今たまたま checkout されているもの」
   ではなく、**manifest が指しているもの**を検査する。
3. mutation の復元は例外でも走り、そのうえで worktree ごと捨てる。

重い build は `scripts/resource-guard.mjs` の build lock を通す。lock が他セッション
に握られていたら**待つ** —— 奪わない。奪える仕組みにすると、この loop が他人の
build を壊す側になる。

## 判定

mutation が「噛んだ」と言うには 2 つ要る:

- suite の**緑マーカーが消える**
- `:must-fail` に挙げた**テスト名が実際に出力に現れる**

名前まで見るのは、別の理由で赤くなったのを「噛んだ」と読まないため —— それが
この種の道具の一番ありがちな嘘だから。

## mutation を足すとき

**落ちるのを実際に見てから足す。** 推測で足すと、この仕組み自体が劇場になる。
壊し方は「JVM 専用に戻す」「検査を外す」など**実際に起こりうる退行の形**にする。
構文を壊すだけの mutation は「コンパイルが通らない」しか証明しない。

`:find` は対象ファイルに**ちょうど 1 回**現れること。0 回／2 回以上は loop 自身の
バグとして `BUG` 報告される（壊せていないのに緑を「噛まなかった」と誤報告するのを
防ぐため）。
