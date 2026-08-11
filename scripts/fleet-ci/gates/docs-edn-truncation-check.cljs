;; EDN 文書が「読めるのに中身が切れている」状態を捕まえる gate。
;;
;; なぜ既存の docs-edn-check では足りないか: あれは `edn/read-string` が通ることを
;; 検証する。**切断された文書は通る。** 本文の途中にエスケープされていない `"` が
;; あると、そこで文字列が終わり、残りは EDN のトークンとして読まれる —— ファイルは
;; 妥当な EDN のままで、entity は 1 個のまま、**本文だけが静かに短くなる**。
;;
;; 実測（2026-08-11、この gate が生まれた理由）: 2,053 件の ADR は 1 件残らず
;; パースに成功していたが、そのうち 1 件は本文の約 80 行が脱落していた
;; （`(str/replace "." "-")` というコード片の引用符が原因、ADR-2608102000）。
;; 同じ日に別のセッションでも同じ欠陥が 2 回起きている。**個人のミスではなく
;; failure class** であり、prose の注意書きでは止まらない。
;;
;; 読めない文書は気付かれる。読めるのに半分欠けている文書は気付かれない。
;;
;; ## 検査するもの
;;
;; **entity map のキーは全て keyword でなければならない。** 文字列が途中で終わると、
;; 残りのテキストはシンボル・数値・記号として読まれ、map のキー位置に現れる。
;; 実測した指紋: `(:db/id :adr/id :adr/status :adr/body 引用符)` ——
;; 最後の `引用符` がシンボル。これが切断の構造的な痕跡である。
;;
;; この不変条件は datom tx-data 全般に成り立つ（属性は keyword）。2,053 件の
;; 実測で違反は上記 1 件のみだった。
;;
;; ノード側で `npx nbb docs-edn-truncation-check.cljs <dir> [--dir 90-docs/adr]`。

(ns fleet-ci.gates.docs-edn-truncation-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def root (or (first (remove #(str/starts-with? % "--")
                             (remove #(some (fn [f] (= % (flag f nil))) ["--dir" "--min"]) args)))
              "."))
(def sub  (flag "--dir" "90-docs/adr"))
(def min-files (js/parseInt (flag "--min" "1") 10))

(defn- edn-files [d]
  (if-not (fs/existsSync d)
    []
    (->> (fs/readdirSync d)
         (filter #(str/ends-with? % ".edn"))
         (map #(path/join d %)))))

(def target (path/join root sub))
(def files (edn-files target))

(when (< (count files) min-files)
  ;; 絞り込みが壊れて 0 件 → trivially pass、を防ぐ床。
  (println (str "FAIL docs-edn-truncation: " target " の .edn が " (count files)
                " 件 — 下限 " min-files "。tree が届いていないのに合格させない"))
  (js/process.exit 1))

(def findings
  (vec (for [f files
             :let [r (try
                       (let [d (edn/read-string {:default (fn [_ v] v)}
                                                (fs/readFileSync f "utf8"))
                             ms (filter map? (if (sequential? d) d [d]))
                             nonkw (mapcat (fn [m] (remove keyword? (keys m))) ms)]
                         (when (seq nonkw) {:file f :keys (vec (take 3 nonkw))}))
                       (catch :default e
                         ;; パースできないのは別の gate（docs-edn-check）の担当だが、
                         ;; ここで黙って通すと「切断は無い」と読めてしまう。
                         {:file f :unreadable (str e)}))]
             :when r]
         r)))

(println (str "docs-edn-truncation: " (count files) " 件を検査"))

(if (empty? findings)
  (println "OK docs-edn-truncation: 切断された文書は無い")
  (do
    (doseq [{:keys [file keys unreadable]} findings]
      (if unreadable
        (println (str "  読めない: " file " — " unreadable))
        (println (str "  切断: " file
                      " — 本文の外に落ちたトークン " (pr-str keys)
                      "（エスケープされていない \" が本文中にある）"))))
    (println (str "FAIL docs-edn-truncation: " (count findings)
                  " 件。パースは通るが本文が欠けている —— 読めない文書は気付かれるが、"
                  "読めるのに半分欠けている文書は気付かれない"))
    (js/process.exit 1)))
