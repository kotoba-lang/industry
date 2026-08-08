#!/usr/bin/env nbb
;; itonami-game-never-auto-check.cljs — 「クリーニング営み」の主張を機械検査する。
;;
;; 対象: `60-apps/network-isekai/public/games/itonami/isic-9601/`
;;
;; ## なぜこの gate が要るか
;;
;; このゲームの主張は 1 行に尽きる: **cloud-itonami の governed actor はどれも、
;; どの phase の `:auto` 集合にも入れない操作を最低 1 つ永久に持っている。**
;; 放置ゲーは「全部自動化する」ゲームなので、その 1 つが自動化されないことが
;; そのまま遊びになっている。
;;
;; **これは prose では守れない。** 数字は 8 つの別 repo
;; (cloud-itonami-isic-{9601,4520,9609,8121,8129,3700,3811,3900}) の
;; `phase.cljc` / `governor.cljc` から 2026-08-08 に読んで `world.cljc` に
;; 転写したもので、転写元と転写先は別 repo・別 owner。バランス調整のつもりで
;; `:auto-at-3` に 1 語足す、`:never-auto` を空にする、`phase-table` の
;; `:auto` に `:clean` を入れる —— どれも**ゲームは普通に動き続ける**。
;; 壊れるのは主張だけで、動作は壊れないので、遊んでも気づけない。
;;
;; repo 側のテスト (`test/logic_test.cljs` / `test/world3d_test.clj`) は同じ
;; 不変条件を見ているが、**誰も回していない** —— GitHub Actions は撤去済み
;; (ADR-2607300900) で、fleet gate に載って初めて毎 tip で走る。
;;
;; ## 検査する不変条件
;;
;;   1. `logic.cljc` の `phase-table` の**どの** phase の `:auto` にも
;;      `:clean` / `:return` が入っていない
;;   2. phase 3 の `:auto` はちょうど `#{:intake}`
;;   3. `world.cljc` の各 district の `:never-auto` が空でない
;;   4. 各 district の `:never-auto` の op が、その district 自身の
;;      `:auto-at-3` に入っていない
;;   5. district 数と、街全体の never-auto op 総数が申告どおり
;;   6. 各 district が実在の repo 名 (`cloud-itonami-isic-<code>`) を名乗る
;;
;; ネットワーク: 不要。tree だけを読む。
;;
;; 使い方: nbb itonami-game-never-auto-check.cljs <repo-tree>

(ns itonami-game-never-auto-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def root (or (first *command-line-args*) "."))
(def game-dir "60-apps/network-isekai/public/games/itonami/isic-9601/src/itonami/isic_9601")

(def failures (atom []))
(defn fail! [& xs] (swap! failures conj (str/join " " (map str xs))))

(defn- slurp* [rel]
  (let [p (path/join root game-dir rel)]
    (when-not (fs/existsSync p)
      (fail! "missing" (str game-dir "/" rel))
      nil)
    (when (fs/existsSync p) (fs/readFileSync p "utf8"))))

;; --------------------------------------------------------------------------
;; The sources are read as TEXT, not loaded.
;;
;; Loading them would need the game's own classpath inside a gate that only gets a file
;; tree, and — more to the point — a check that evaluates the thing it checks can be
;; satisfied by a file that computes the right answer at runtime. The invariant here is
;; about what is WRITTEN DOWN, so the gate reads what is written down.
;; --------------------------------------------------------------------------

(defn- section
  "The text between `start` and the first `end` after it, or nil."
  [s start end]
  (when-let [i (str/index-of s start)]
    (let [rest' (subs s i)
          j (str/index-of rest' end)]
      (when j (subs rest' 0 j)))))

(defn- auto-sets
  "Every `:auto #{...}` in the phase table, as a vector of sets of op names."
  [phase-text]
  (mapv (fn [m] (set (map (fn [w] (subs w 1))
                          (re-seq #":[a-z0-9-]+" (nth m 1)))))
        (re-seq #":auto\s+#\{([^}]*)\}" phase-text)))

(when-let [logic (slurp* "logic.cljc")]
  (let [pt (section logic "(def phase-table" "(def max-phase")]
    (if-not pt
      (fail! "logic.cljc: phase-table not found — the gate cannot see the invariant")
      (let [sets (auto-sets pt)]
        (when (< (count sets) 4)
          (fail! "logic.cljc: expected 4 phases, found" (count sets)))
        (doseq [[i s] (map-indexed vector sets)]
          (doseq [op ["clean" "return"]]
            (when (contains? s op)
              (fail! "logic.cljc: phase" i "auto-commits" (str ":" op)
                     "— the two actuation ops must never be automatable, at any phase"))))
        (when (and (= 4 (count sets)) (not= #{"intake"} (nth sets 3)))
          (fail! "logic.cljc: phase 3 :auto is" (pr-str (nth sets 3))
                 "but must be exactly #{:intake}"))))))

;; --------------------------------------------------------------------------

(defn- districts
  "Each district entry as `{:id :repo :never-auto #{} :auto3 #{}}`, parsed from the text."
  [world]
  (let [chunks (rest (str/split world #"\{:id \""))]
    (mapv (fn [c]
            (let [id (first (str/split c #"\""))
                  grab (fn [k]
                         (when-let [m (re-find (re-pattern (str k "\\s+\\[([^\\]]*)\\]")) c)]
                           (set (map (fn [w] (subs w 1)) (re-seq #":[a-z0-9/-]+" (nth m 1))))))]
              {:id id
               :repo (second (re-find #":repo \"([^\"]+)\"" c))
               :never-auto (grab ":never-auto")
               :auto3 (grab ":auto-at-3")
               :why (second (re-find #":never-auto-why \"([^\"]*)\"" c))}))
          chunks)))

(when-let [world (slurp* "world.cljc")]
  (let [ds (districts world)]
    (if (empty? ds)
      (fail! "world.cljc: no districts parsed — the gate cannot see the invariant")
      (do
        (when (not= 8 (count ds))
          (fail! "world.cljc: expected 8 districts, found" (count ds)))
        (doseq [d ds]
          (when-not (and (:repo d) (re-matches #"cloud-itonami-isic-\d+" (:repo d)))
            (fail! (:id d) "does not name a cloud-itonami-isic-* repo:" (pr-str (:repo d))))
          (when (empty? (:never-auto d))
            (fail! (:id d) "has no :never-auto op — every actor in this fleet keeps at"
                   "least one operation permanently un-automatable; a district that"
                   "claims otherwise is a district whose repo was misread"))
          (when (str/blank? (str (:why d)))
            (fail! (:id d) "does not say WHY its op never automates"))
          (doseq [op (:never-auto d)]
            (when (contains? (:auto3 d) op)
              (fail! (:id d) "lists" (str ":" op)
                     "as never-auto AND in its own phase-3 :auto set — one of the two"
                     "was edited without the other"))))
        (let [total (reduce + 0 (map (fn [d] (count (:never-auto d))) ds))]
          (println (str "  districts " (count ds)
                        "  never-auto ops " total
                        "  (9601 contributes 2, the rest 1 each)"))
          (when (not= 9 total)
            (fail! "the street declares" total "never-auto ops; 9 were read from the"
                   "eight repos on 2026-08-08 — a change here means a repo was re-read"
                   "(update world/district-evidence) or a transcription was lost")))))))

;; --------------------------------------------------------------------------

(if (seq @failures)
  (do (println "FAIL itonami-game-never-auto")
      (doseq [f @failures] (println "  -" f))
      (js/process.exit 1))
  (println "OK itonami-game-never-auto"))
