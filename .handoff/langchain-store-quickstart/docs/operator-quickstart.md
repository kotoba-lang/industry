# langchain-store — operator quickstart

**この文書の手順はすべて実行して確かめてある**（2026-08-09、素の checkout に対して）。
貼られている出力は実際に出たものであり、期待値ではない。手順が動かなくなったら
それはこの文書のバグなので、直すか消すこと。

このライブラリは **316 repo が依存する**（ADR-2608052000 の測定）。依存側から見た
「最初の 10 分」をここに置く。API の一覧は README、設計判断は `docs/adr/`。

---

## 1. 取得してテストを通す

```bash
git clone https://github.com/kotoba-lang/langchain-store.git
cd langchain-store
```

**JVM:**

```bash
clojure -M:ci:test
```

```
Running tests in #{"test"}
Testing langchain-store.core-test
Ran 13 tests containing 40 assertions.
0 failures, 0 errors.
```

**ClojureScript（README が primary gate と呼んでいる方）:**

```bash
clojure -Sdeps '{:paths ["src" "test"]}' -M:cljs \
  -m cljs.main --target node -m langchain-store.cljs-runner
```

```
Testing langchain-store.core-test
Ran 13 tests containing 40 assertions.
0 failures, 0 errors.
```

同じ 13 test が両方で走る（suite は `.cljc` 1 本）。

> **`:ci` alias は何のためか。** `:deps` の `langchain-clj` は既に公開 URL
> （`kotoba-lang/langchain`）を指しているので、cljs のコマンドは alias 無しでも
> 解決する（実測）。`:ci` が差し替えるのは **pin だけ**で、sibling checkout の
> 無い環境で main tip に合わせるためにある。monorepo の中に居るなら素の
> `clojure -M:test` でよい。

初回は maven の解決で数分かかる。2 回目以降は数秒。

---

## 2. 消費者として使う（10 行）

`/tmp/smoke.clj` に置いて `clojure -M:ci -e '(load-file "/tmp/smoke.clj")'`:

```clojure
(require '[langchain.db :as d] '[langchain-store.core :as ls])

;; keyed EDN-blob store
(def conn (d/create-conn (ls/identity-schema [:enlisted/id])))
(ls/put-blob!   conn :enlisted/id :enlisted/payload "e-1" {:rank :sgt})
(println (ls/blob-lookup conn :enlisted/id :enlisted/payload "e-1"))   ; {:rank :sgt}
(println (ls/blob-lookup conn :enlisted/id :enlisted/payload "gone"))  ; nil

;; seq-keyed event stream
(def ev (d/create-conn (ls/identity-schema [:ev/seq])))
(ls/append-blob! ev :ev/seq :ev/edn 1 {:kind :a})
(ls/append-blob! ev :ev/seq :ev/edn 2 {:kind :b})
(ls/append-blob! ev :ev/seq :ev/edn 2 {:kind :b2})
(println (ls/read-stream ev :ev/seq :ev/edn))   ; [{:kind :a} {:kind :b2}]
```

**最後の行に注意。** `append-blob!` は名前に反して **同じ seq への 2 度目の書き込みを
upsert する**（`{:kind :b}` が消えて `{:kind :b2}` になっている）。append-only なのは
seq が単調に増える限りであって、seq を再利用したら上書きになる。

---

## 3. 一番踏みやすい失敗 —— `identity-schema` を忘れる

**これはエラーにならない。黙って古い値を返し続ける。** 実測:

```clojure
(def bad (d/create-conn {}))                      ; ← schema 無し
(ls/put-blob! bad :enlisted/id :enlisted/payload "e-1" {:rank :sgt})
(ls/put-blob! bad :enlisted/id :enlisted/payload "e-1" {:rank :maj})   ; 更新のつもり
(ls/blob-lookup bad :enlisted/id :enlisted/payload "e-1")
;; => {:rank :sgt}    ← 更新が消えている
;; 実体数 = 2         ← 同じキーで 2 件できている
```

```clojure
(def ok (d/create-conn (ls/identity-schema [:enlisted/id])))   ; ← 正しい
;; 同じ 2 回の put-blob! のあと
;; => {:rank :maj}    ← 期待どおり
;; 実体数 = 1
```

`:db.unique/identity` が無いと langchain.db は 2 件目を**別の実体**として足し、
`blob-lookup` は最初に見つけた方を返す。**例外も警告も出ない。**

> したがって: **`d/create-conn` に渡す schema は必ず `ls/identity-schema` を通す。**
> 手で書いた schema に key を足し忘れたときも同じ壊れ方をする。

---

## 4. field-spec による entity mapping

論理マップ ↔ langchain.db の attr を 1 つの spec で往復させる。

```clojure
(def spec {:id            {:attr :app/id}
           :status        {:attr :app/status}
           :beneficiaries {:attr :app/beneficiaries :blob? true :default []}})

(ls/pull-pattern spec)      ; => [:app/id :app/status :app/beneficiaries]

(def c (d/create-conn (ls/identity-schema [:app/id])))
(d/transact! c [(ls/map->tx spec {:id "a-1" :status :open :beneficiaries [{:n "x"}]})])

(ls/pull->map spec :id (d/pull (d/db c) (ls/pull-pattern spec) [:app/id "a-1"]))
;; => {:id "a-1", :status :open, :beneficiaries [{:n "x"}]}
```

実測した 2 つの挙動:

- **`:default` は blob が nil のときに効く** ——
  `(ls/pull->map spec :id {:app/id "a-2" :app/status :new})`
  → `{:id "a-2", :status :new, :beneficiaries []}`
- **identity attr が無ければ全体が nil** ——
  `(ls/pull->map spec :id {:app/status :new})` → `nil`
  （「空の実体」ではなく「実体が無い」を返す）

`pull->map` は **3 引数**（`spec` `id-key` `pulled`）。2 引数で呼ぶと
`ArityException` になる —— これは踏んだ。

---

## 5. 使う前に知っておくべき非保証

README の "Things this library deliberately does not do" の運用上の要点:

| 事実 | 運用でどうするか |
|---|---|
| **`append-record!` は atomic ではない** —— stream を読んで次の seq を決めてから append する | 並行 writer が居るなら **単一 writer にする**か、seq を外から与えて `append-blob!` を直接使う |
| **`now-ms` は本物の時計**（注入できない） | テストで時刻を固定するなら `stamp` に timestamp を渡す（`append-record!` の 6-arity も時計を受け取る） |
| **`Store` protocol は無い** | 各 actor の protocol は自分のドメイン面。ここに抽象を足さない |

**「集約したが保証は増えていない」** —— `append-record!` の非原子性は、各 actor が
手で書いていた `add-record!` にも元から無かった。コードを 1 箇所にしても、
元から無かった保証が生えるわけではない。

---

## 6. 詰まったとき

| 症状 | 原因 |
|---|---|
| `Could not locate langchain/db` | `-M:ci` を付けずに sibling checkout の無い場所で走らせている |
| 更新したのに古い値が返る | §3。`identity-schema` を通していない |
| `append-blob!` で前の値が消えた | §2。同じ seq に 2 度書いている |
| `pull->map` が `ArityException` | 引数は 3 つ（`spec` `id-key` `pulled`） |
| 初回の `clojure -M:ci:test` が数分止まる | maven の初回解決。2 回目以降は数秒 |
