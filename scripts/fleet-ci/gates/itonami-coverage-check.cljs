#!/usr/bin/env nbb
;; verify-itonami-coverage.cljs — 90-docs/coverage/itonami-coverage.datoms.edn の形を検査する。
;;
;; なぜ要るか（実測 2026-08-30）。この台帳は `[ {…} {…} ]` という **1 個の** top-level
;; form でなければならないが、2 つの scout commit が閉じ括弧 `]` の **後ろ** に
;; entity を追記した。`clojure.edn/read-string` は最初の form だけを返して成功する
;; ので、その 4 entity は **どの consumer からも見えないまま**、ファイルは「読める」
;; ままだった。加えてそのうち 2 つは既に中に在る code の再追加だった。
;;
;; つまり「読めた」と「全部読めた」が出力で区別できていなかった。ADR-2608136000 の
;; 1 問目そのものなので、ここでは *数* を必ず印字する。
;;
;; 検査:
;;   1. text 中の列 0 の `{:db/id` の個数 == reader が返した entity 数
;;      （合わなければ閉じ括弧の外に entity が居る = 静かな切り捨て）
;;   2. :coverage/code の重複が無いこと
;;   3. 各 entity が :db/id と :source/dataset "itonami-coverage" を持つこと
;;
;; usage:  nbb --classpath ".:scripts/nbb_compat" scripts/fleet-ci/gates/itonami-coverage-check.cljs [<root-dir>]
;;   ⚠ <root-dir> は **引数の先頭**（fleet gate はそう渡す。--flag は無視する）
;;
;; exit: 0=clean  1=findings  2=答えられなかった（0 でも 1 でもない値にする。
;;       ファイルが無い / 読めない を「検査して問題なし」と同じ値にしない）

(ns itonami-coverage-check
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv)) "."))
(def rel "90-docs/coverage/itonami-coverage.datoms.edn")
(def file (path/join root rel))

(defn- die [code & msg] (apply println msg) (js/process.exit code))

(when-not (fs/existsSync file)
  (die 2 "REFUSING to report a pass:" rel "not present under" root
       "\n  (a check that could not read its input must not return the value that means clean)"))

(def text (str (fs/readFileSync file "utf8")))

;; 列 0 の `{:db/id` = entity の開始。note 文字列の中は必ず字下げされているので当たらない。
(def text-entities (count (re-seq #"(?m)^\{:db/id" text)))

(def parsed
  (try (edn/read-string text)
       (catch :default e (die 2 "REFUSING to report a pass: reader threw on" rel "\n " (.-message e)))))

(when-not (vector? parsed)
  (die 2 "REFUSING to report a pass: top-level form is" (type parsed) "not a vector in" rel))

(def ents (vec parsed))
(def codes (keep :coverage/code ents))
(def dups (map key (filter #(> (val %) 1) (frequencies codes))))
(def no-id (remove :db/id ents))
(def bad-ds (remove #(= "itonami-coverage" (:source/dataset %)) ents))

;; evidence floor — 数を必ず出す。0 件を clean にしない。
(println (str "SCANNED\tentities-in-text=" text-entities
              "\tentities-read=" (count ents)
              "\tcodes=" (count codes)
              "\tdistinct=" (count (distinct codes))))

(when (zero? text-entities)
  (die 2 "REFUSING to report a pass:" rel "contains no entity at all — that is not a clean ledger, that is an unread one"))

(def findings
  (cond-> []
    (not= text-entities (count ents))
    (conj (str "TRUNCATED: the text holds " text-entities " entities but the reader returned "
               (count ents) ". " (- text-entities (count ents))
               " entity(ies) sit OUTSIDE the closing `]` and are invisible to every consumer"
               " that uses read-string, while the file still parses. Move them inside the vector."))

    (seq dups)
    (conj (str "DUPLICATE :coverage/code — " (str/join ", " (sort dups))
               ". Each code may appear once; a re-add with reworded :coverage/note is still a duplicate."))

    (seq no-id)
    (conj (str (count no-id) " entity(ies) lack :db/id (the house shape for tx-data)"))

    (seq bad-ds)
    (conj (str (count bad-ds) " entity(ies) lack :source/dataset \"itonami-coverage\""))))

(if (seq findings)
  (do (doseq [f findings] (println "FINDING:" f))
      (js/process.exit 1))
  (do (println "OK:" (count ents) "entities, all distinct, one top-level form")
      (js/process.exit 0)))
