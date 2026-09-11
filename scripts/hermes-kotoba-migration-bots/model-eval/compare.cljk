#!/usr/bin/env nbb
(ns compare-runs
  "Side-by-side of two eval result files. Reports the ONE number that decides
   a model choice -- cost per ACCEPTED migration -- plus the denominators it
   depends on, because a pass rate over tasks the harness could not grade is
   not a pass rate."
  (:require [clojure.string :as str]
            [cljs.reader :as edn]
            ["node:fs" :as fs]))

(def argv (vec (or *command-line-args* [])))
(defn slurp-edn [p] (edn/read-string (.readFileSync fs p "utf8")))

(defn stats [label file]
  (let [d  (slurp-edn file)
        rs (:results d)
        n  (count rs)
        by (frequencies (map :verdict rs))
        pass (get by :pass 0)
        ;; a task is GRADED only if the harness could actually compare values
        graded (remove #(= :parity-not-measured (:verdict %)) rs)
        ptok (reduce + (map #(get-in % [:usage :prompt] 0) rs))
        ctok (reduce + (map #(get-in % [:usage :completion] 0) rs))
        ms   (reduce + (map #(or (:ms %) 0) rs))
        rounds (reduce + (map #(:rounds % 0) rs))]
    {:label label :n n :pass pass :by by
     :graded (count graded)
     :graded-pass-rate (if (pos? (count graded))
                         (* 100.0 (/ pass (count graded))) 0)
     :tokens (+ ptok ctok) :ms ms :rounds rounds
     :tok-per-accept (if (pos? pass) (js/Math.round (/ (+ ptok ctok) pass)) nil)
     :sec-per-accept (if (pos? pass) (/ (/ ms 1000.0) pass) nil)}))

(def a (stats (nth argv 0) (nth argv 1)))
(def b (stats (nth argv 2) (nth argv 3)))

(println (str "                          " (:label a) "        " (:label b)))
(println (str "runs                      " (:n a) "                " (:n b)))
(println (str "graded (comparable)       " (:graded a) "                " (:graded b)))
(println (str "pass                      " (:pass a) "                " (:pass b)))
(println (str "pass-rate over graded     " (.toFixed (:graded-pass-rate a) 1) "%            "
              (.toFixed (:graded-pass-rate b) 1) "%"))
(println (str "total tokens              " (:tokens a) "            " (:tokens b)))
(println (str "repair rounds used        " (:rounds a) "                " (:rounds b)))
(println (str "TOKENS PER ACCEPTED       " (or (:tok-per-accept a) "INF") "             " (or (:tok-per-accept b) "INF")))
(println (str "SECONDS PER ACCEPTED      " (if (:sec-per-accept a) (.toFixed (:sec-per-accept a) 1) "INF")
              "             " (if (:sec-per-accept b) (.toFixed (:sec-per-accept b) 1) "INF")))
(println)
(println "verdict breakdown")
(doseq [k [:pass :parity-fail :compile-fail :parity-not-measured :no-code :api-error]]
  (println (str "  " (name k) (apply str (repeat (max 1 (- 22 (count (name k)))) " "))
                (get (:by a) k 0) "                " (get (:by b) k 0))))
