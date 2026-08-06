(ns lib.cljc-literal
  "`.cljc` のソースから `(def <name> ... <literal>)` の literal だけを切り出して
  読む。ファイル全体を EDN として読むと、後方の def に混じる `#()` や
  reader conditional で落ちるため、**対象フォームだけを括弧バランスで切り出して**
  から読む。

  ⚠ **同じ実装が `scripts/gen-market-entry-registry.cljs` にもある**（そちらが
  先行実装で、この ns はそこから起こした）。あちらを移していないのは、動いている
  生成器の出力が変わらないことを確かめる手段がこの周に無かったため —— 2 つある
  ことは既知の負債であり、次に market-entry 側を触る人がここに寄せる。

  同じ規則が 2 実装に分かれると片方だけ直る、というのがこの workspace が
  何度も踏んでいる壊れ方なので、**3 つ目を作らないこと。**"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn extract-top-form
  "`src` 内の `marker` から始まる、括弧バランスの取れたトップレベルフォームを
  文字列で返す。文字列リテラル（エスケープ含む）と `;` 行コメントの中の括弧は
  数えない。見つからなければ nil。"
  [src marker]
  (when-let [start (str/index-of src marker)]
    (loop [i start depth 0 in-str? false esc? false in-comment? false]
      (let [ch (.charAt src i)]
        (if (empty? ch)
          nil                                   ; EOF = 不均衡。投げずに nil。
          (cond
            in-comment? (recur (inc i) depth false false (not= ch "\n"))
            in-str? (cond esc? (recur (inc i) depth true false false)
                          (= ch "\\") (recur (inc i) depth true true false)
                          (= ch "\"") (recur (inc i) depth false false false)
                          :else (recur (inc i) depth true false false))
            (= ch "\"") (recur (inc i) depth true false false)
            (= ch ";") (recur (inc i) depth false false true)
            (= ch "(") (recur (inc i) (inc depth) false false false)
            (= ch ")") (if (= depth 1)
                         (subs src start (inc i))
                         (recur (inc i) (dec depth) false false false))
            :else (recur (inc i) depth false false false)))))))

(defn read-def-literal
  "`src` から `(def <def-name> ...)` の最終要素（literal）を返す。

  marker は改行ありと空白ありの両方を試す —— fleet には `(def catalog\\n` と
  `(def catalog ` の両方が実在する（実測 2026-08-06）。
  読めなければ `{::error <理由>}` を返す。**nil を返して「空」と区別が付かなく
  しない。**"
  [src def-name]
  (let [form (or (extract-top-form src (str "(def " def-name "\n"))
                 (extract-top-form src (str "(def " def-name " ")))]
    (cond
      (nil? form) {::error :form-not-found}
      :else (try (last (edn/read-string {:default (fn [_tag v] v)} form))
                 (catch :default e {::error :unreadable ::message (str e)})))))

(defn error? [x] (and (map? x) (contains? x ::error)))
