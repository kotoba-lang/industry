(ns kotobase-performance.fold-cosci
  "Co-scientist tournament (Generate → Reflect → Rank(Elo) → Evolve → Meta)
  over write-triggered AppView fold strategies for app-aozora-pds /
  kotobase-peer, following the SAME shape as
  `orgs/gftdcojp/cloud-murakumo/src/cloud_murakumo/cosci.cljc` (do not
  re-derive that shape — this file mirrors it function-for-function, only
  the domain model (`kotobase-performance.fold-cost-model`, this ns's sibling)
  is new). Per the repo-wide instruction not to design co-scientist loops
  from scratch (root CLAUDE.md 'Co-Scientist kaizen loop' section).

  No LLM in the loop — fully deterministic, calibrated against REAL
  production measurements (ADR-2607199970 + addendum), not invented
  numbers. See fold_cost_model.cljc's docstring for the calibration
  sources. Correctness of THIS cost model is itself the thing worth
  scrutinizing (same honesty note cosci.cljc makes about its own model)."
  (:require [clojure.string :as str]
            [kotobase-performance.fold-cost-model :as fc]))

(defn meta-review
  [{:keys [ranked clusters passed-n failed-n generation]}]
  (let [top-cluster (first clusters)
        top (first top-cluster)
        strat (get-in top [:candidate :strategy])
        cat (get fc/strategy-catalog strat)]
    {:generation generation
     :passed passed-n
     :failed failed-n
     :top-strategy strat
     :top-title (:title cat)
     :top-elo (:elo top)
     :top-work (:work top)
     :top-cluster-size (count top-cluster)
     :top-params (select-keys (:candidate top) [:rate :max-novelty :threshold])
     :recommendation
     (case strat
       :threshold-if-available
       "Switch project!/maybe-fold! from blind Math/random sampling to reading
        novelty_size off the SAME transact response already received (zero
        extra round-trip) and firing only when it crosses :threshold — with
        graceful degrade to a low-rate sample if the field is absent. This
        needs an empirical check first: does the LIVE (source-lost)
        kotobase.aozora.app backend actually include novelty_size in its
        transact response, matching kotobase-server's reference handler
        shape? Verify via one live write + a temporary diagnostic log before
        committing to this as the sole mechanism."
       :sampled
       "Blind sampling remains the winner at the evaluated rate/max-novelty —
        keep the already-deployed WRITE_FOLD_SAMPLE_RATE=0.02 mechanism as-is."
       :cron-only
       "The tournament's own control candidate won — i.e. at the evaluated
        write-rate assumptions, NO write-trigger is actually the safest
        option. Re-examine whether the assumed write-rate range still matches
        real traffic before trusting this."
       "Inspect top candidate genes directly.")
     :leaderboard
     (mapv (fn [r]
             {:strategy (get-in r [:candidate :strategy])
              :elo (:elo r) :work (:work r)
              :rate (get-in r [:candidate :rate])
              :max-novelty (get-in r [:candidate :max-novelty])
              :threshold (get-in r [:candidate :threshold])})
           (take 10 ranked))}))

(defn run-generation
  ([population] (run-generation population {} 0))
  ([population opts generation]
   (let [{:keys [passed failed]} (fc/reflect-all population opts)
         ranked (fc/rank passed opts)
         clusters (fc/cluster-by-proximity ranked)
         next-pop (when (seq ranked) (fc/evolve-round ranked))
         review (meta-review {:ranked ranked :clusters clusters
                              :passed-n (count passed) :failed-n (count failed)
                              :generation generation})]
     {:generation generation :passed passed :failed failed :ranked ranked
      :clusters clusters :next-population next-pop :meta-review review})))

(defn run-tournament
  ([] (run-tournament {}))
  ([{:keys [n-generations pool opts] :or {n-generations 3}}]
   (let [pool (or pool fc/hypothesis-pool)
         base-opts (merge {:write-rates [0.05 0.2 0.75 2.0] :draw-tol 0.02} opts)]
     (loop [g 0
            population (fc/generate-candidates pool)
            prior {}
            history []
            last-result nil]
       (if (>= g n-generations)
         {:final last-result :history history
          :winner (get-in last-result [:meta-review :top-strategy])
          :recommendation (get-in last-result [:meta-review :recommendation])}
         (let [gen-opts (assoc base-opts :prior-ratings prior)
               result (run-generation population gen-opts g)
               ratings (into {} (map (fn [r] [(:candidate r) (:elo r)]) (:ranked result)))]
           (recur (inc g)
                  (or (:next-population result) population)
                  ratings
                  (conj history (:meta-review result))
                  result)))))))

(defn report
  [tournament-result]
  (let [m (get-in tournament-result [:final :meta-review])]
    (str/join
     "\n"
     (concat
      ["=== kotobase-performance fold-cosci · write-triggered-fold tournament ==="
       (str "winner: " (name (:top-strategy m)) "  elo=" (format "%.1f" (double (:top-elo m))))
       (str "title:  " (:top-title m))
       (str "params: " (pr-str (:top-params m)))
       (str "passed/failed (last gen): " (:passed m) "/" (:failed m)
            "  top-cluster: " (:top-cluster-size m))
       ""
       "recommendation:"
       (:recommendation m)
       ""
       "leaderboard:"]
      (map (fn [e]
             (format "  %-24s elo=%7.1f work=%.4f rate=%.3f max-novelty=%-4d threshold=%d"
                     (name (:strategy e)) (double (:elo e)) (double (:work e))
                     (double (:rate e)) (long (:max-novelty e)) (long (:threshold e))))
           (:leaderboard m))
      [""
       "generation history (top strategy per gen):"]
      (map-indexed
       (fn [i h] (format "  g%d -> %s (elo %.1f)" i (name (:top-strategy h)) (double (:top-elo h))))
       (:history tournament-result))))))

(defn -main [& _]
  (let [t (run-tournament {:n-generations 3})]
    (println (report t))
    t))
