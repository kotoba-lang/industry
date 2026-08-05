# 営み OS 成熟度ループ — 1 サイクルの指示

超project ADR-2608060000。あなたはこのサイクルで**接続済みの営みを 1 本だけ増やす**。
作業ツリーはこの clone（`~/.tamaki/itonami-os/…`）で、**west 管理の共有 checkout
（`~/github/com-junkawasaki/orgs/…`）には触らない**。

## 0. まず測る（推測しない）

```
nbb --classpath ".:scripts/nbb_compat" \
  /Users/junkawasaki/github/com-junkawasaki/scripts/itonami-os-maturity-tick.cljs
```

出力の「次の 1 手」に従う。状態を記憶や推測から再構成しない —— `:unbound-reason` は
書いた日の観測であって今日の事実ではない、というのがこの tick が在る理由そのもの。

## 1. 優先順位

1. **API gate が 401 を返していない** → 認証境界の確認を最優先。他は何もしない。
2. **op の :drift がある** → それを直す。面が嘘をついている状態で新しい営みを繋がない。
   正本は各 actor 自身の allowlist であって `os.edn` ではない。
3. **機械的に繋げられる営みがある** → そのうち **1 本だけ** 接続する（次節）。
4. **無い** → 残りは repo 側の `.clj` を `.cljc` に割る作業で、機械的ではない。
   **1 本だけ調査して所見を書き、コードは書かずに終わる。**

## 2. 1 本繋ぐ手順

- `src/cloud_itonami/os/adapters/<ns>.cljc` を書く。中身は `jobsearchops.cljc` の写しで、
  変えるのは require する ns 名だけ。**adapter は翻訳しか持たない** —— advisor も
  governor も phase gate も vertical 側のものがそのまま動く。判定を 1 つでも足したら
  その変更は捨てる。
- `src/cloud_itonami/os/hydrate.cljc` に store の復元枝を足す。
- `os.edn` の当該 vertical を `:binding :native` にし、`:unbound-reason` を消す。
- `nbb scripts/generate-os-registry.cljs` で射影を再生成。
- `test/cloud_itonami/os_test.cljc` に、その営みの実 op が本当に回ることを足す。
  **commit する道と、governor が実際に拒む hold の道の両方**を書く。片方だけでは
  「拒否が本物か」を示せない。

## 3. gate（1 つでも落ちたら PR を出さない）

```
clojure -M:dev:test                                   # os-test 込み。既存の失敗数を変えないこと
nbb scripts/generate-os-registry.cljs --check
nbb scripts/verify-sites-bundle.cljs
npx shadow-cljs release os-api                        # compile ではない
```

`clojure -M:dev:test` は**既存の失敗が 7 failures / 12 errors ある**（2026-08-05 実測、
本 OS とは無関係）。その数を増やしていないことを確認する。減っていたらそれも報告する。

## 4. 着地は PR まで。**merge も deploy もしない**

```
git switch -c agent/os-connect-<repo>
git commit
git push -u origin agent/os-connect-<repo>
gh pr create --repo network-awai/cloud-itonami --base main
```

PR 本文には **gate の実際の出力**（テスト数・失敗数、`--check` の結果、bundle バイト数）を
そのまま貼る。「通った」とだけ書かない。

**merge しない理由**: adapter を足すと公開面の主張が変わる（「この営みは live」）。
gate が緑でも、公開する判断は人がする。**動かないものを live と宣言することが、
この面が避けようとしている唯一の失敗**なので、そこだけは自動化しない。

## 5. やらないこと

- 未接続を「とりあえず `:native`」にする
- governor / phase gate の判定を緩める
- 他セッションの未コミット WIP に触れる、共有 checkout を編集する
- 履歴書き換え / force-push
- 本番 deploy（このループは PR まで）

## 6. 終わり方

やったこと・gate の実数・PR URL を 5 行以内で述べる。
繋げるものが無かったならそう言う —— **無理に何か変えない。**
