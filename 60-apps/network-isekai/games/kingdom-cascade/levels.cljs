#!/usr/bin/env nbb
;; Level set report and gate.
;;
;;     npx nbb --classpath src:../common/src levels.cljs                # report
;;     npx nbb --classpath src:../common/src levels.cljs --gate         # non-zero on a bad level
;;
;; For each level: does it validate, can a greedy player finish it, how many
;; moves that took, and how much slack the move budget leaves. The gate fails
;; on a level that does not validate or that a greedy player cannot win —
;; both mean the level ships broken, and neither is visible by looking at it.
(ns levels
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [isekai.games.kingdom-cascade.core :as g]
            [isekai.games.kingdom-cascade.level :as level]
            [isekai.games.kingdom-cascade.solver :as solver]))

(def args (vec *command-line-args*))
(def gate? (some #{"--gate"} args))
(def dir (or (first (remove #(str/starts-with? % "--") args)) "resources/levels"))

(defn- load-level [f]
  (edn/read-string (fs/readFileSync (path/join dir f) "utf8")))

(defn- report [f]
  (let [lvl (load-level f)
        problems (level/validate lvl)]
    (if (seq problems)
      {:id (:level/id lvl) :ok? false :problems problems}
      (let [{:keys [state moves reshuffles exhausted?]} (solver/autoplay (g/new-game lvl))
            budget (:level/moves lvl)]
        {:id (:level/id lvl)
         :name (:level/name lvl)
         :ok? (= :won (:status state))
         :status (:status state)
         :budget budget
         :greedy-moves moves
         :slack (- budget moves)
         :reshuffles reshuffles
         :exhausted? exhausted?
         :score (:score state)
         :left (mapv :goal/remaining (:goals (g/hud state)))}))))

(let [files (sort (filter #(str/ends-with? % ".edn") (js->clj (fs/readdirSync dir))))
      rows (mapv report files)]
  (println (str "level set: " dir "  (" (count rows) " levels)"))
  (println)
  (doseq [r rows]
    (if (:problems r)
      (do (println (str "  " (:id r) "  INVALID"))
          (doseq [p (:problems r)] (println (str "      " (pr-str p)))))
      (println (str "  " (:id r)
                    "  " (if (:ok? r) "win " "LOSS")
                    "  greedy " (:greedy-moves r) "/" (:budget r)
                    "  slack " (:slack r)
                    (when (pos? (:reshuffles r)) (str "  reshuffles " (:reshuffles r)))
                    "  score " (:score r)
                    (when-not (:ok? r) (str "  still needed " (:left r)))))))
  (println)
  (let [bad (remove :ok? rows)]
    (println (str (- (count rows) (count bad)) "/" (count rows) " playable"))
    (when (and gate? (seq bad))
      (println (str "gate failed: " (str/join ", " (map :id bad))))
      (js/process.exit 1))))
