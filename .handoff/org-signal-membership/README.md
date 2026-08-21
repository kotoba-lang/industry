# handoff: `kotoba.signal.membership` → `kotoba-lang/org-signal`

**この 3 ファイルは `kotoba-lang/org-signal` に port するためにここに置いてある。
ここから require しないこと。** superproject に置いたまま消費すると、org-signal の
vendor コピーが 1 つ増えるだけで、片方だけが直る状態を作る（CLAUDE.md が
`club-shinshi-app` の vendor で踏んだのと同じ壊れ方）。

```
src/kotoba/signal/membership.cljc      実装（鍵も暗号も持たない純粋な決定核）
test/kotoba/signal/membership_test.cljc テスト
run-tests.cljs                          nbb のエントリ
```

## なぜ superproject に置いてあるのか

この session は `kotoba-lang` への push 権を持たない —— `add_repo` が
`cross-tier adds are not supported in v1` を返す。**org-signal に直接 PR を出せない
ので、実装を捨てるか、port 待ちとして置くかの二択だった。**

## port するとき

1. `kotoba-lang/org-signal` に `src/kotoba/signal/membership.cljc` と
   `test/kotoba/signal/membership_test.cljc` をそのまま置く。
2. org-signal の既存テスト実行経路（JVM / nbb 双方）に載せる。**この namespace は
   crypto backend を要求しない**ので、どちらでも追加の依存は要らない。
3. README の「out of scope」から group membership management の行を消す
   —— **消すのは port が実際に landed してから**。
4. superproject 側でこの `.handoff/` を削除し、`manifest/clearance.edn` の
   `:membership-layer :status` を `:ported` にする。

## 何をする層か

org-signal の README が自ら欠けていると書いている層:

> the sender-keys chain ratchet itself is implemented, **membership bookkeeping
> is not** … Key revocation / rotation policy … **a scheduler/policy for *when*
> does not [exist]**

この namespace がその *when* と *to whom*。**鍵を持たず暗号もしない** ——
epoch 数と member 集合の上の値→値の関数で、どの `group/create-sender-key` と
`group/distribution-message` を呼ぶかを返すだけ。

`group.clj` と `group.cljs` は別 backend なので、primitive に触れない bookkeeping を
1 本の `.cljc` にしておけば **2 つの host が「誰が group に居るか」で食い違えない**。

## 設計上いちばん大事な 1 点

**除名は既に読まれたものを取り消さないし、既に配った chain key も回収できない。**
`remove-member` は `:still-readable-by` と `:re-seal-required` を返して、
**除名が守れなかった epoch 範囲を呼び出し側に返す**。除名を完了として報告しない
——そう思わせる membership 層は、無いより悪い。

## 検証

```
nbb --classpath "src:test" run-tests.cljs
# Ran 9 tests containing 29 assertions. 0 failures, 0 errors.
```

**通ることだけでなく、落ちることも確かめてある**（2026-08-08）:

| 壊し方 | 結果 |
|---|---|
| join が epoch を進めない | 9 failures |
| 除名が漏れ（`:still-readable-by`）を報告しない | 1 failure |
| 再加入が過去 epoch を開く | 5 failures |
| `members-at` が `:left` を無視する | 1 failure |

設計: ADR-2608084000 / ADR-2608085000、政策: `manifest/clearance.edn` の
`:protocols :membership-layer`。
