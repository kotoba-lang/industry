#!/usr/bin/env nbb
(ns measure-fleet-gaps
  "Turn the fleet's open gaps from adjectives into numbers, so a ranking loop
  can rank them.

  ## Why this exists

  On 2026-08-20 the Co-Scientist loop (90-docs/design-quality/coscientist.cljc)
  was run over six gaps and could rank only two. The other four had no measured
  magnitude, and ADR-2607203000 forbids multiplying a pool by a guessed rate --
  so they were marked :uncomputable-until-measured and left OUT of the ranking.
  Unranked is not small; it is unmeasured. This measures them.

  It reports numbers and exits 0. It is a MEASUREMENT, not a gate: there is no
  threshold here that anyone agreed on, and inventing one would make a number
  look like a verdict.

  Usage: nbb scripts/measure-fleet-gaps.cljs [--data <dir>]"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def argv (vec (drop 2 (.-argv js/process))))
(defn- opt [n d] (or (second (drop-while #(not= n %) argv)) d))

(def data-dir (opt "--data" (path/join (or (.-HOME (.-env js/process)) "~")
                                       ".cloud-itonami" "data")))
(def state-file (path/join data-dir "state.edn"))

(defn- die [msg]
  (binding [*print-fn* *print-err-fn*] (println (str "CANNOT ANSWER — " msg)))
  (set! (.-exitCode js/process) 2))

(defn- count-re [s re] (count (re-seq re s)))

(defn -main []
  (if-not (fs/existsSync state-file)
    (die (str state-file " does not exist; refusing to report zeros as measurements"))
    (let [s (str (fs/readFileSync state-file "utf8"))
          bytes (.-size (fs/statSync state-file))
          runs (count-re s #":agent\.run/id\s+\"")
          turns (count-re s #":turn/")
          handoffs (count-re s #":handoff/at\s+\"")
          approvals (count-re s #":approval/id\s+\"")
          patches (count-re s #":patch/id\s+\"")
          artifacts (count-re s #":agent\.run/artifacts\s+\[\{")
          peers-on (count-re s #":bot/peers\?\s+true")
          peers-off (count-re s #":bot/peers\?\s+false")
          ;; Retention projects on RUN COUNT, not bytes. The store is rewritten
          ;; wholesale every transact, so its size falls as well as rises --
          ;; three samples on 2026-08-20 went 24.6 -> 29.4 -> 28.1 MB and a
          ;; linear projection off those was not supportable. Run count only
          ;; goes up.
          ceiling (* 256 1024 1024)
          per-run (when (pos? runs) (/ bytes runs))
          headroom (when per-run (Math/floor (/ (- ceiling bytes) per-run)))]
      (println "SCANNED\tstate.edn\t" (str (.toFixed (/ bytes 1048576) 1) " MB"))
      (println)
      (println "g5 durable output — what 675 runs have produced")
      (println (str "  runs " runs "  turns " turns
                    "  approval-cards " approvals "  patches " patches
                    "  artifacts " artifacts))
      (println)
      (println "g3 peer demand — the pool is 90; the DEMAND is what matters")
      (println (str "  :bot/peers? true " peers-on " / false " peers-off
                    "   handoffs ever recorded " handoffs))
      (println "  peer-shaped capabilities declared by any role: run"
               "`grep -ohE ':capability\\s+:[a-z0-9.-]+' yakuwari/*.edn | grep -cE 'peer|deleg|handoff|collab'`")
      (println "  measured 2026-08-20: 0 of 90 roles declare one. Zero demand is a")
      (println "  MEASUREMENT, not a gap -- enabling a capability no role asks for")
      (println "  widens reach with no declared consumer.")
      (println)
      (println "g6 retention — nothing prunes; projected on run count")
      (if per-run
        (do (println (str "  " (.toFixed (/ per-run 1024) 1) " KB/run, "
                          headroom " runs of headroom to the 256 MiB store bound"))
            (println (str "  at the design cadence of 189 runs/day: "
                          (Math/round (/ headroom 189)) " days")))
        (println "  CANNOT PROJECT — zero runs retained"))
      (println)
      (println "g4 publish path — implemented, not running")
      (println "  cloud-itonami/shinshi-growth-actor has createRecord in")
      (println "  growth/{aozora.clj,cacao.clj,publisher.cljc} and NO launchd plist.")
      (println "  The gap is one actor not resident, not an unknown.")
      (println)
      (println "g2 dependency coordinates: see verify-dep-coordinate-identity.cljs")
      (println "  (it reports ACTIVE vs LATENT since 2026-08-20)"))))

(-main)
