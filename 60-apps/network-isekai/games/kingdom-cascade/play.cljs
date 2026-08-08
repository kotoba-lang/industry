#!/usr/bin/env nbb
;; Headless play. Runs a level to a terminal state and prints the board after
;; every move, so the cascade rules can be reviewed without a GPU.
;;
;;     npx nbb --classpath src play.cljs resources/levels/kc-001.edn
;;     npx nbb --classpath src play.cljs resources/levels/kc-001.edn --quiet
;;
;; `--quiet` prints only the outcome line and the replay digest, which is the
;; form the CI gate and the browser/shell parity check both consume.
(ns play
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [isekai.games.kingdom-cascade.board :as b]
            [isekai.games.kingdom-cascade.core :as g]
            [isekai.games.kingdom-cascade.level :as level]
            [isekai.games.kingdom-cascade.solver :as solver]))

;; `*command-line-args*` already excludes nbb's own flags and the script name;
;; slicing `process.argv` by a fixed offset picks up `--classpath`'s value.
(def args (vec *command-line-args*))
(def path (or (first (remove #(str/starts-with? % "--") args))
              "resources/levels/kc-001.edn"))
(def quiet? (some #{"--quiet"} args))
;; `--dumb` picks the first legal swap in reading order instead of the best
;; one. Useful for seeing how much of a level's difficulty is the level and
;; how much is the player.
(def choose (if (some #{"--dumb"} args) g/hint solver/best-move))

(def lvl (edn/read-string (fs/readFileSync path "utf8")))

(when-let [problems (seq (level/validate lvl))]
  (println "level does not validate:")
  (doseq [p problems] (println " " (pr-str p)))
  (js/process.exit 1))

(defn show [state label]
  (when-not quiet?
    (println)
    (println (str "── " label
                  "  moves " (:moves-left state)
                  "  score " (:score state)
                  "  " (str/join ", " (map (fn [goal]
                                             (str (name (or (:goal/block goal)
                                                            (:goal/cover goal)
                                                            (:goal/color goal)))
                                                  " x" (:goal/remaining goal)))
                                           (:goals (g/hud state))))))
    (doseq [row (b/render-ascii (:board state))] (println "   " row))))

(let [start (g/new-game lvl)]
  (show start "start")
  (loop [state start, n 0]
    (cond
      (not= :playing (:status state))
      (do (show state (str "final — " (name (:status state))))
          (println)
          (println (str (:level/id lvl) "  " (name (:status state))
                        "  moves-used " (- (:level/moves lvl) (:moves-left state))
                        "  score " (:score state)
                        "  digest " (g/digest state)))
          (js/process.exit (if (= :won (:status state)) 0 0)))

      (> n 200)
      (do (println "gave up after 200 iterations — the level never terminated")
          (js/process.exit 1))

      :else
      (if-let [[a bpos] (choose state)]
        (let [state' (g/swap state a bpos)]
          (show state' (str "swap " a " <-> " bpos
                            "  [" (str/join " " (map (comp name :frame/kind)
                                                     (:frames state'))) "]"))
          (recur state' (inc n)))
        (do (when-not quiet? (println "   no legal move — reshuffling"))
            (recur (g/reshuffle state) (inc n)))))))
