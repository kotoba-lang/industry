#!/usr/bin/env nbb
(ns orgs-detectors-admission-check
  "manifest/orgs-detectors.edn admits, checked where an entry lands.

  The tick that runs those detectors admits them all-or-nothing: one entry
  missing a mandatory field refuses EVERY detector. That is the right design --
  an entry that cannot state its own limits should not run -- but until now the
  only thing that enforced it was the tick itself, six hours later, on a
  machine nobody was watching. When it fired, `state.edn` simply stopped being
  written, and SessionStart kept rendering the last good run with nothing
  saying it was frozen.

  It happened twice on 2026-08-20: `:verify-error-provenance` in the morning
  and `:verify-bridge-guest-cannot-execute` in the afternoon, both landing
  without `:evidence`, `:timeout-ms`, `:interval-ms` or `:writes`. Both were
  found by someone registering an unrelated detector and noticing nothing ran.

  This gate spawns the tick's own `--admit-only`, rather than restating its
  rules, so there is exactly one implementation of what a valid entry is."
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as p]
            [clojure.string :as str]))

(def tree (or (first (remove #(str/starts-with? % "--") (vec *command-line-args*))) "."))

(defn -main []
  (let [tick (p/join tree "scripts" "orgs-detector-tick.cljs")
        registry (p/join tree "manifest" "orgs-detectors.edn")]
    ;; Refuse rather than pass when the inputs are not here. A gate that
    ;; reports clean because it could not find the file it checks is the
    ;; failure class this whole registry exists to catch.
    (doseq [f [tick registry]]
      (when-not (fs/existsSync f)
        (println (str "UNANSWERED\t" f " is not in the shipped tree —"
                      " refusing to report a pass"))
        (js/process.exit 2)))
    (let [r (cp/spawnSync "nbb" (clj->js ["--classpath" (str tree ":" tree "/scripts/nbb_compat")
                                          tick "--repo" tree "--registry" registry
                                          "--admit-only"])
                          #js {:encoding "utf8"})
          out (str (.-stdout r) (.-stderr r))
          code (.-status r)]
      (print out)
      (cond
        (nil? code)
        (do (println "UNANSWERED\tcould not spawn nbb — refusing to report a pass")
            (js/process.exit 2))

        (zero? code)
        (do (println "OK — every registry entry states its own limits.")
            (js/process.exit 0))

        :else
        (do (println (str "FAIL — manifest/orgs-detectors.edn does not admit."
                          " Until this is fixed the orgs-detector tick runs NOTHING,"
                          " and its last state file keeps being displayed as if current."))
            (js/process.exit 1))))))

(-main)
