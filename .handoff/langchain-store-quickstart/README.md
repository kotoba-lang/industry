# handoff: `docs/operator-quickstart.md` → `kotoba-lang/langchain-store`

**この 1 ファイルは `kotoba-lang/langchain-store` に port するためにここに置いてある。**
superproject から参照しないこと（vendor コピーを作らない）。

```
docs/operator-quickstart.md   → langchain-store の docs/ にそのまま置く
```

## なぜここに在るのか

成熟度 tick（`scripts/itonami-maturity-improve-tick.cljs`）が
**`orgs/kotoba-lang/langchain-store` の `axis-docs`** を次の 1 手に指名した
（own=0.220 / eff=0.182 / gain=30.26、`axis-docs` は 1034bp で満点まで +2242bp）。
lib 種別なので `business-model` / `pricing` は当たらず、実質の穴は
**`docs/operator-quickstart.md` が無いこと**だった。

書いて、**素の clone に対して全手順を実際に踏んで**確かめた。しかし
**この session は `kotoba-lang` に push できない** —— `add_repo` が
`cross-tier adds are not supported in v1` を返し、GitHub MCP も
`Access denied: repository "kotoba-lang/langchain-store" is not configured`
を返す（read すら通らない。git の匿名 clone だけが通る）。

**したがって成熟度スコアはまだ動いていない。** 動くのは port されたときである。

## port するとき

1. `kotoba-lang/langchain-store` に `docs/operator-quickstart.md` を置く。
2. **貼ってある出力をもう一度確かめる。** この文書の価値は「実際にその出力が出る」
   ことに懸かっており、pin が動けば嘘になりうる。特に §1 のテスト件数
   （13 tests / 40 assertions）と §3 の実体数。
3. superproject 側でこの `.handoff/` を削除する。
4. 次周の tick が `axis-docs` の伸びを測る。**自分で「上がった」と書かない。**

## 何を確かめて書いたか（2026-08-09）

| 節 | 確かめたこと |
|---|---|
| §1 | 新規 clone → `clojure -M:ci:test` = 13 tests / 40 assertions、`-M:cljs` の documented コマンドも同じ 13 件。**両方とも verbatim で通る** |
| §1 注 | `:ci` 無しでも cljs は解決する（`:deps` が既に公開 URL）。`:ci` が差し替えるのは pin だけ |
| §2 | 10 行の smoke を実行。`append-blob!` が**同じ seq を upsert する**（`{:kind :b}` が消えて `:b2` になる）ことを出力で確認 |
| §3 | **`identity-schema` を忘れると例外も警告も無く、実体が 2 件でき、`blob-lookup` が古い方を返す**。schema 有りでは 1 件・新しい方。両方を実行して対比 |
| §4 | `pull-pattern` / `map->tx` / `pull->map` の往復、`:default` の適用、identity 不在で `nil`。`pull->map` は 3 引数（2 引数で `ArityException` を実際に踏んだ） |
| §5 | README の非保証（`append-record!` は atomic でない / `now-ms` は本物の時計）を運用の言葉に直しただけ。新しい主張はしていない |

**§3 がこの文書の中心。** エラーにならず黙って古い値を返す失敗は、README の API
一覧からは読み取れない。316 repo が依存するライブラリで、これが最も高くつく。
