#!/usr/bin/env nbb
;; yui-weekly.cljs — yui (結) weekly resident: one observe → evaluate →
;; decide → act → record cycle, self-contained (no chat context needed).
;;
;; This is the CRON-side runner (task 2 of the yui landing). Each run:
;;   1. OBSERVE   — live-probe the five doors' funnel surfaces (read-only GET)
;;   2. EVALUATE  — re-run the yui five-domain participation scenarios
;;                  (yui_xmile.cljs) against the measured reach
;;   3. DECIDE    — re-rank the co-scientist catalog with the fresh sim gains
;;   4. ACT       — write a report under 90-docs/system-dynamics/yui/reports/
;;   5. RECORD    — append one evidence line to the ledger (append-only)
;;
;; Honesty contract (ADR-2609022000 G4): every measured value carries its
;; probe date; unmeasured parameters stay labelled assumptions; scenario
;; rankings are ranks, never headcount forecasts.
;;
;; Run (superproject root):
;;   nbb --classpath "scripts:scripts/nbb_compat" scripts/yui-weekly.cljs
;; Env: SD_OUT to redirect report dir (default 90-docs/system-dynamics/yui).

(require '[clojure.string :as str]
         '[scripts.nbb-compat :refer [sh]]
         '["fs" :as fs])

(def ^:private root
  (let [r (sh "git" "rev-parse" "--show-toplevel")]
    (if (zero? (:exit r))
      (str/trim (str (:out r)))
      (js/process.cwd))))
(def sd-dir (str root "/90-docs/system-dynamics/yui"))
(def reports-dir (str sd-dir "/reports"))
(def ledger (str sd-dir "/yui-xmile-ledger.edn"))

(defn- probe-status [url]
  (let [{:keys [exit out]} (sh "curl" "-sS" "-m" "10" "-A" "yui/1 (weekly resident)"
                               "-o" "/dev/null" "-w" "%{http_code} %{content_type}" url)]
    (if (zero? exit)
      (let [[code ct] (str/split (str/trim (str out)) #" " 2)]
        {:status (js/parseInt code 10) :content-type (str/lower-case (str ct))})
      {:status nil :content-type nil})))

(def doors
  [{:domain "kotobase.net"   :url "https://kotobase.net/api/funnel"}
   {:domain "murakumo.cloud" :url "https://murakumo.cloud/api/funnel"}
   {:domain "isekai.network" :url "https://isekai.network/feed/fork-stats.edn"}
   {:domain "kotoba.cloud"   :url "https://kotoba.cloud/api/funnel"}
   {:domain "itonami.cloud"  :url "https://itonami.cloud/api/funnel"}])

(defn- classify [{:keys [status content-type] :as p}]
  (assoc p :kind (cond
                   (nil? status) :probe-failed
                   (= 404 status) :no-endpoint
                   (str/includes? (str content-type) "html") :spa-fallback
                   (= 200 status) :machine-readable
                   :else :other)))

;; ── 1. observe ─────────────────────────────────────────────────────────────
(defn observe []
  {:as-of (js/Date.)
   :doors (mapv classify (map (fn [d] (merge d (probe-status (:url d)))) doors))})

(defn- r3 [v] (/ (.round js/Math (* 1000 (double v))) 1000.0))

;; ── 2+3. evaluate & decide — run yui-run.cljs as a subprocess (same
;; entry the operator runs; no nbb __load trickery, which doesn't exist) ────
(defn evaluate-decide []
  (let [{:keys [exit out err]} (sh "nbb"
                                   "--classpath"
                                   (str "90-docs/system-dynamics/nbb-shim:"
                                        "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/org-oasis-open-xmile/src:"
                                        "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/dynamics/src:"
                                        "90-docs/system-dynamics/yui")
                                   "90-docs/system-dynamics/yui/yui-run.cljs")]
    (when-not (zero? exit)
      (println (str "yui sim run failed (exit " exit "): " err)))
    {:exit exit
     :stdout (str out)
     :failed? (not (zero? exit))}))

(defn- parse-final [stdout name]
  (when-let [[_ v] (re-find (re-pattern (str "\\| " name " \\| ([0-9.]+) \\|")) stdout)]
    (js/parseFloat v 10)))

;; ── 4. act ─────────────────────────────────────────────────────────────────
(defn act! [obs ev]
  (.mkdirSync fs reports-dir #js {:recursive true})
  (let [f (str reports-dir "/weekly-" (subs (str (:as-of obs)) 0 10) ".md")]
    (.writeFileSync fs f
      (str "# yui weekly — " (:as-of obs) "\n\n"
           "## Doors (machine-readable funnel surface)\n\n"
           (str/join "\n" (map (fn [d] (str "- " (:domain d) " → " (name (:kind d))))
                               (:doors obs))) "\n\n"
           "## Sim run (yui-run.cljs, full stdout)\n\n"
           "```\n" (:stdout ev) "```\n\n"
           "## Honesty\n\n"
           "Rankings are scenario outputs of an ASSUMPTION-parameterized model —\n"
           "they rank interventions, they do not forecast headcounts.\n"))
    f))

;; ── 5. record-evidence ────────────────────────────────────────────────────
(defn record! [obs ev report-path]
  (.appendFileSync fs ledger
    (str (pr-str {:event/as-of (str (:as-of obs))
                  :event/kind :yui-weekly-cycle
                  :event/doors (mapv (juxt :domain :kind) (:doors obs))
                  :event/sim-exit (:exit ev)
                  :event/sim-failed? (:failed? ev)
                  :event/report report-path}) "\n")))

(defn -main []
  (let [obs (observe)
        ev (evaluate-decide)
        report (act! obs ev)]
    (record! obs ev report)
    (println (str "yui weekly cycle complete: " report
                  (when (:failed? ev) " (SIM RUN FAILED — report records the failure)")))))

(-main)
