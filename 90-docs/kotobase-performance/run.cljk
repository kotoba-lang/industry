#!/usr/bin/env nbb
;; Runs the cost Co-Scientist and prints the ranking.
;;
;;   nbb --classpath 90-docs/kotobase-performance/src 90-docs/kotobase-performance/run.cljs
;;
;; The engine lives under src/kotobase_performance/ rather than beside the
;; receipts because ClojureScript resolves `kotobase-performance.x` to the
;; directory `kotobase_performance` — with the file next to the .edn receipts
;; the namespace could not be required from where it lived, and this loop's
;; own engine had never been run in place.
(require '[kotobase-performance.cost-coscientist :as c])

(defn- r2 [x] (/ (js/Math.round (* 100 x)) 100))

(let [r (c/run)
      base (:baseline-cost-per-million r)]
  (println "cost Co-Scientist —" (:source (:prices r)))
  (println (str "baseline $" (r2 base) " per million served reads"
                "  (cpu " (:cpu-ms (:baseline r)) "ms "
                (name (:cpu-ms (:sources (:baseline r)))) ")"))
  (println)
  (doseq [h (:roadmap r)]
    (println (str "  elo=" (:elo h)
                  "  " (:id h)
                  "\n         after $" (r2 (:cost-after h))
                  "   saves $" (r2 (:saving h))
                  "   " (r2 (* 100 (:saving-share h))) "% of cost"
                  "   risk=" (name (:risk (:reflection h))))))
  (println)
  (println "batch:" (:order (:batch r)))
  (doseq [n (:what-the-judge-cannot-see r)]
    (println (str "  ! " (first (clojure.string/split-lines n))))))
