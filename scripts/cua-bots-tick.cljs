#!/usr/bin/env nbb
;; scripts/cua-bots-tick.cljs — CUA bot 名簿（manifest/cua-bots.edn）の due 判定。
;; **決定論。モデルは居ない。** `scripts/cua-bots-loop.cljs` が毎周これを先に回す。
;;
;; ## 何を測るか
;;
;;   1. 名簿の形。読めない / 形が違う / id 重複 → **:not-measured（exit 2）**。
;;      壊れた名簿を「候補 0 件」と読ませない —— 測れなかった検査が、測って
;;      問題が無かった検査と同じ値を返してはならない（ADR-2608136000）。
;;   2. bot ごとの due?。~/.itonami/cua-bots/receipts/<bot-id>/ の最新 receipt が
;;      :bot/interval-s より古い、または receipt が 1 つも無い → due。
;;
;; backend keyword の**実在**はここでは検査しない —— backend registry の正本は
;; kotoba-lang/computer-use で、写しをここに持つと registry が進むたびにこの
;; tick が嘘をつく。実在と probe は lib 側 `--dry-run` の仕事。
;;
;; ## 出力と exit
;;
;;   stdout 最終行に EDN を 1 行:
;;   {:outcome :due|:no-due :due [...] :skipped [...] :roster-invalid? false}
;;   exit 0 = 測れた（due の有無は :outcome で区別）
;;   exit 2 = 名簿が測れなかった（:roster-invalid? true、:problems に理由）
;;
;; usage:
;;   nbb scripts/cua-bots-tick.cljs [--roster <path>]

(ns cua-bots-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))

(def argv (vec *command-line-args*))
(defn- opt [n] (let [i (.indexOf argv n)]
                 (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def roster-file (or (opt "--roster") (str root "/manifest/cua-bots.edn")))
(def receipts-root (str home "/.itonami/cua-bots/receipts"))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))

;; ---------------------------------------------------------------- validate

(def id-re #"^[a-z0-9][a-z0-9-]{0,63}$")
(def action-set #{:pointer :key :type :script})

(defn- entry-problems [i e]
  (let [p (fn [msg] (str "entry " i " (" (pr-str (:bot/id e)) "): " msg))]
    (cond-> []
      (not (map? e)) (conj (p "map ではない"))
      (not (and (string? (:bot/id e)) (re-matches id-re (:bot/id e))))
      (conj (p ":bot/id が [a-z0-9][a-z0-9-]{0,63} でない"))
      (not (and (string? (:bot/goal e)) (seq (:bot/goal e))))
      (conj (p ":bot/goal が非空 string でない"))
      (not (keyword? (:bot/backend e))) (conj (p ":bot/backend が keyword でない"))
      (not (map? (:bot/target e))) (conj (p ":bot/target が map でない"))
      (not (and (int? (:bot/interval-s e)) (pos? (:bot/interval-s e))))
      (conj (p ":bot/interval-s が正の int でない"))
      (not (and (int? (:bot/max-steps e)) (pos? (:bot/max-steps e))))
      (conj (p ":bot/max-steps が正の int でない"))
      (not (and (set? (:bot/allowed-actions e))
                (every? action-set (:bot/allowed-actions e))))
      (conj (p ":bot/allowed-actions が #{:pointer :key :type :script} の部分集合でない"))
      (not (and (int? (:bot/budget-tokens e)) (pos? (:bot/budget-tokens e))))
      (conj (p ":bot/budget-tokens が正の int でない")))))

(defn- roster-problems [roster]
  (cond
    (nil? roster) [(if (exists? roster-file)
                     "roster が EDN として読めない"
                     "roster ファイルが存在しない")]
    (not (vector? roster)) ["roster が vector でない"]
    (empty? roster) ["roster が空"]
    :else
    (let [per-entry (vec (mapcat #(entry-problems %1 %2) (range) roster))
          ids (keep :bot/id (filter map? roster))
          dups (for [[id n] (frequencies ids) :when (> n 1)] id)]
      (cond-> per-entry
        (seq dups) (conj (str ":bot/id が重複: " (str/join ", " dups)))))))

;; ---------------------------------------------------------------- due?

(defn- newest-receipt-ms
  "receipt dir の最新ファイル mtime（ms）。無ければ nil。"
  [bot-id]
  (let [dir (str receipts-root "/" bot-id)]
    (when (exists? dir)
      (let [ts (->> (try (vec (.readdirSync fs dir)) (catch :default _ []))
                    (keep #(try (.getTime (.-mtime (.statSync fs (str dir "/" %))))
                                (catch :default _ nil))))]
        (when (seq ts) (apply max ts))))))

(defn- due-row [now-ms e]
  (let [t (newest-receipt-ms (:bot/id e))
        age-s (when t (js/Math.floor (/ (- now-ms t) 1000)))]
    {:bot/id (:bot/id e)
     :bot/backend (:bot/backend e)
     :last-receipt (when t (.toISOString (js/Date. t)))
     :age-s age-s
     :due? (or (nil? t) (> age-s (:bot/interval-s e)))}))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [roster (try (edn/read-string (.readFileSync fs roster-file "utf8"))
                    (catch :default _ nil))
        problems (roster-problems roster)]
    (if (seq problems)
      (do (prn {:outcome :not-measured :roster-invalid? true
                :roster roster-file :problems problems})
          (js/process.exit 2))
      (let [now (.now js/Date)
            rows (mapv #(due-row now %) roster)
            due (filterv :due? rows)
            skipped (filterv (complement :due?) rows)]
        (prn {:outcome (if (seq due) :due :no-due)
              :roster-invalid? false
              :due (mapv #(dissoc % :due?) due)
              :skipped (mapv #(dissoc % :due?) skipped)})
        (js/process.exit 0)))))

(-main)
