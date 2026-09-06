#!/usr/bin/env nbb
;; What the resident's Bots actually spend, so a ceiling can be chosen from a
;; measurement instead of a guess. ADR-2609062600 stage 1.
;;
;;   nbb scripts/measure-bot-usage.cljs [--store <path to state.edn>]
;;
;; exit 0  measured
;; exit 2  COULD NOT ANSWER — no store, or no turn carried a usage record
;;
;; ## Why this exists rather than a number in a docstring
;;
;; `bot-bounds.cljc` quotes p50 43,299 / p90 250,926 / max 29,231,364 from one
;; day. CLAUDE.md says repeatedly that a measured value written into prose
;; becomes a constant the moment someone quotes it without the date. This is
;; the way to get today's.
;;
;; ## The level this reads, and the one it does not
;;
;; Turns live at `[:bots :turn-history <bot-id>]` — the `:bots` PARTITION, not
;; the top level. Read one level up and the store answers `:bots` 15 and
;; `:turn-history` nil, which reads exactly like a fleet that has never taken a
;; turn. Measured 2026-09-06: that is what the first version of this
;; measurement reported, and it was only caught because 15 disagreed with the
;; 231 the API had just returned. A wrong level does not error; it answers.
;;
;; So this REFUSES when it finds no usage at all rather than printing zeros: at
;; this level, zero turns is far more likely to mean the reader is wrong than
;; that the fleet is idle.

(ns measure-bot-usage
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def node-path (js/require "node:path"))
(def argv (vec (drop 2 js/process.argv)))

(defn- arg [flag]
  (second (drop-while #(not= flag %) argv)))

(def store-path
  (or (arg "--store")
      (.join node-path (or (aget js/process.env "HOME") ".")
             ".cloud-itonami" "data" "state.edn")))

(defn- refuse! [& lines]
  (doseq [l lines] (println l))
  (.exit js/process 2))

(defn- tokens [t]
  (long (or (get-in t [:turn/usage :total_tokens])
            (get-in t [:turn/usage "total_tokens"])
            0)))

(defn- quantile [sorted f]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted)) (int (* f (count sorted)))))))

(defn -main []
  (when-not (.existsSync fs store-path)
    (refuse! (str "REFUSED\tstore がありません: " store-path)
             "  --store <path> で場所を渡してください"))
  (let [st (edn/read-string {:default (fn [_ v] v)}
                            (str (.readFileSync fs store-path "utf8")))
        p (:bots st)
        bots (:bots p)
        th (:turn-history p)]
    (when-not (map? bots)
      (refuse! "REFUSED\t[:bots :bots] が map ではありません。store の形が変わっています"
               "  この script は partition を読みます。top level ではありません"))
    (let [all (vec (for [[_ turns] th, t turns] (tokens t)))
          nz (vec (sort (filter pos? all)))
          per (into {} (for [[bid turns] th]
                         [bid {:n (count turns)
                               :sum (reduce + 0 (map tokens turns))
                               :mx (reduce max 0 (map tokens turns))}]))
          active (filter #(pos? (:sum (val %))) per)]
      (println (str "store=" store-path))
      (println (str "bots=" (count bots)
                    "  with-turn-history=" (count th)
                    "  turns=" (count all)
                    "  turns-with-usage=" (count nz)))
      (when (empty? nz)
        (refuse! "REFUSED\tusage を持つ turn が 1 件もありません。"
                 "  これは「fleet が一度も動いていない」より「読む階層が違う」ほうが"
                 "  ずっとありそうです（この file の docstring 参照）"))
      (println (str "per-turn total_tokens"
                    "  min=" (first nz)
                    "  p50=" (quantile nz 0.5)
                    "  p90=" (quantile nz 0.9)
                    "  p99=" (quantile nz 0.99)
                    "  max=" (last nz)))
      (let [sums (vec (sort (map (comp :sum val) active)))
            mxs (vec (sort (map (comp :mx val) active)))]
        (println (str "per-bot window sum   p50=" (quantile sums 0.5)
                      "  p90=" (quantile sums 0.9)
                      "  max=" (last sums)))
        (println (str "per-bot worst turn   p50=" (quantile mxs 0.5)
                      "  p90=" (quantile mxs 0.9)
                      "  max=" (last mxs))))
      ;; The count that stage 1 is about: how many carry no ceiling. Absence is
      ;; reported rather than read as `unlimited`.
      (let [unbounded (count (remove #(:bot/budget-tokens (val %)) bots))]
        (println (str "bots with no :bot/budget-tokens = " unbounded
                      " / " (count bots))))
      (println)
      (println "⚠ これは今日の値です。docstring に書き写さないでください。"))))

(-main)
