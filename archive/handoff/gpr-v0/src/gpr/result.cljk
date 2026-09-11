(ns gpr.result
  "`[:ok v]` / `[:err {...}]`。

  この repo は例外で失敗を報告しない —— `throw` / `try` / `catch` を使わず
  結果値を返す（ADR-2608650000 の恒久制約。`.kotoba` の `[:result T E]` と
  同じ形をそのまま `.cljc` 側でも使い、境界で形が変わらないようにする）。")

(defn ok [v] [:ok v])

(defn err
  "`code` は keyword、`message` は人間向けの 1 行。`data` は任意の付随値。"
  ([code message] [:err {:code code :message message}])
  ([code message data] [:err {:code code :message message :data data}]))

(defn ok? [r] (and (vector? r) (= :ok (first r))))
(defn err? [r] (and (vector? r) (= :err (first r))))

(defn value
  "`[:ok v]` の v。err のときは `not-found`（既定 nil）。"
  ([r] (value r nil))
  ([r not-found] (if (ok? r) (second r) not-found)))

(defn error [r] (when (err? r) (second r)))

(defn fmap
  "ok のときだけ f を適用する。err はそのまま素通し。"
  [r f]
  (if (ok? r) (ok (f (second r))) r))

(defn bind
  "ok のときだけ f を適用する。f は result を返すこと。"
  [r f]
  (if (ok? r) (f (second r)) r))
