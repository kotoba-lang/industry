#!/usr/bin/env nbb
;; Runs a shop through a shift and reports how it played. This is the balance
;; tool and the CI gate; it is the shop equivalent of the other game's
;; `levels.cljs`.
;;
;;     npx nbb --classpath src:../common/src shift.cljs
;;     npx nbb --classpath src:../common/src shift.cljs --gate
;;     npx nbb --classpath src:../common/src shift.cljs resources/shops/bs-flagship.edn --minutes 30
;;
;; The gate fails on a shop that does not validate, that a competent manager
;; cannot play to its last department, that loses more customers than it
;; serves, or that offers a hire nobody would ever buy. All four ship a shop
;; that *runs* — no exception, no visible error — and is broken anyway.
(ns shift
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [isekai.games.boutique-stack.economy :as econ]
            [isekai.games.boutique-stack.manager :as mgr]
            [isekai.games.boutique-stack.sim :as sim]
            [isekai.games.boutique-stack.world :as world]))

(def args (vec *command-line-args*))
(def gate? (some #{"--gate"} args))
(def positional (vec (remove #(str/starts-with? % "--") args)))

(defn- flag [name fallback]
  (if-let [i (first (keep-indexed (fn [i a] (when (= a name) i)) args))]
    (js/parseInt (nth args (inc i)) 10)
    fallback))

(def minutes (flag "--minutes" 20))
(def target (or (first positional) "resources/shops"))

(defn- shop-files []
  (if (str/ends-with? target ".edn")
    [target]
    (mapv #(path/join target %)
          (sort (filter #(str/ends-with? % ".edn") (js->clj (fs/readdirSync target)))))))

(defn- checkpoints
  "Where a department opened, in minutes. This is the number a designer
  actually tunes against — 'shoes at minute 31' is a decision, 'shoes
  eventually' is not."
  [state0 world ticks rate]
  (let [step (max 1 (quot ticks 40))]
    (loop [t 0, seen #{}, out [], st state0]
      (if (> t ticks)
        out
        (let [opened (remove seen (:unlocked st))]
          (recur (+ t step)
                 (into seen (:unlocked st))
                 (into out (map (fn [z] {:zone z :minute (quot (:tick st) (* 60 rate))})
                                (sort opened)))
                 (sim/run st world step mgr/policy)))))))

(defn- report [file]
  (let [shop (edn/read-string (fs/readFileSync file "utf8"))
        problems (world/validate shop)]
    (if (seq problems)
      {:id (:shop/id shop) :ok? false :problems problems}
      (let [[w s0] (sim/start shop)
            rate (:shop/tick-rate shop 20)
            ticks (* minutes 60 rate)
            final (sim/run s0 w ticks mgr/policy)
            p (mgr/progress final w)
            all-zones (set (map :zone/id (:shop/zones shop)))
            hireable (set (keys econ/staff-roles))
            hired (set (map :role (:staff final)))
            served (:served p)
            lost (:lost p)]
        {:id (:shop/id shop)
         :name (:shop/name shop)
         :minutes minutes
         :revenue (:revenue p)
         :served served
         :lost lost
         :loss-pct (quot (* 100 lost) (max 1 (+ served lost)))
         :lost-to-empty (:lost-to-empty p)
         :lost-to-queue (:lost-to-queue p)
         :departments (:departments p)
         :all-open? (= all-zones (:unlocked final))
         :opened-at (checkpoints s0 w ticks rate)
         :staff (:staff p)
         :unsold-roles (vec (sort (remove hired hireable)))
         :end-cash (:money p)
         :upgrades (:upgrades p)
         :digest (sim/digest final)
         :ok? (and (= all-zones (:unlocked final))
                   (< lost served)
                   (empty? (remove hired hireable)))}))))

(let [rows (mapv report (shop-files))]
  (doseq [r rows]
    (println)
    (if (:problems r)
      (do (println (str (:id r) "  INVALID"))
          (doseq [p (:problems r)] (println (str "    " (pr-str p)))))
      (do
        (println (str (:id r) " — " (:name r) "   (" (:minutes r) " minute shift)"))
        (println (str "  served      " (:served r)
                      "   lost " (:lost r) " (" (:loss-pct r) "%)"
                      "   empty " (:lost-to-empty r) " / queue " (:lost-to-queue r)))
        (println (str "  revenue     " (:revenue r) "   cash left " (:end-cash r)))
        (println (str "  departments " (str/join ", " (map name (:departments r)))
                      (if (:all-open? r) "   (all open)" "   (NOT all open)")))
        (doseq [c (:opened-at r)]
          (println (str "              " (name (:zone c)) " at minute " (:minute c))))
        (println (str "  staff       " (pr-str (:staff r))
                      (when (seq (:unsold-roles r))
                        (str "   NEVER HIRED: " (str/join ", " (map name (:unsold-roles r)))))))
        (println (str "  upgrades    " (pr-str (:upgrades r))))
        (println (str "  digest      " (:digest r))))))
  (println)
  (let [bad (remove :ok? rows)]
    (println (str (- (count rows) (count bad)) "/" (count rows) " shops playable"))
    (when (and gate? (seq bad))
      (println (str "gate failed: " (str/join ", " (map :id bad))))
      (js/process.exit 1))))
