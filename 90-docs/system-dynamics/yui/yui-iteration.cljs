;; yui-iteration.cljs — runs the co-scientist iteration 1 for 結 (yui):
;; sim gains from the yui XMILE scenarios feed the deterministic tournament.
;; Run (superproject root):
;;   nbb --classpath "90-docs/system-dynamics/nbb-shim:orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dynamics/src:90-docs/system-dynamics/yui" \
;;     90-docs/system-dynamics/yui/yui-iteration.cljs
(ns yui-iteration
  (:require [clojure.string :as str]
            [yui-coscientist :as cs]))

;; sim gains measured from yui-run.cljs (final contributors vs S0 base 1185.1):
(def sim-gains
  {"yui-h1-self-serve-onboarding-per-door" 1169.6   ; S1 2354.7-1185.1
   "yui-h2-contributor-starter-kits"       254.2    ; S2 1439.3-1185.1
   "yui-h3-active-retention-windows"       230.4    ; S3 1415.5-1185.1
   "yui-h5-reputation-public-ledger"       74.3     ; S5 1259.4-1185.1
   })

(defn r1 [v] (/ (.round js/Math (* 10 (double v))) 10.0))

(defn -main []
  (let [packet (cs/run-iteration sim-gains)]
    (println "═══ 結 (yui) co-scientist iteration 1 — 2026-09-02 ═══")
    (println "")
    (println "-- Review (charter gates) --")
    (println (str "surviving: " (count (:candidates packet))
                  "  vetoed: " (count (:vetoed packet))))
    (doseq [v (:vetoed packet)
            p (:problems v)]
      (println (str "  ✗ " (:gate p) ": " (:why p))))
    (println "")
    (println "-- Ranking (deterministic Elo; fitness = measured sim gain) --")
    (doseq [[i id] (map-indexed vector (:ranking packet))]
      (println (str "  " (inc i) ". " id)))
    (println "")
    (println "-- Evolved --")
    (when-let [e (:evolved packet)]
      (println (str "  " (:id e)))
      (println (str "  mechanisms: " (str/join " → " (:mechanisms e))))
      (println (str "  " (:prediction e))))
    (println "")
    (println "-- Meta --")
    (println (str "  " (:coverage-note (:meta packet))))
    (println "")
    (println "-- Honesty --")
    (println (str "  " (str/replace (:honesty-note packet) #"\n\s*" " ")))))
(yui-iteration/-main)
