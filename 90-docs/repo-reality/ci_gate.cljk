;; repo-reality CI gate — runs every verify_<project>.cljs sibling, compares each claim's new
;; score against its most recent PREVIOUS score in repo-reality-ledger.edn (not an absolute
;; threshold — a claim honestly scoring 0.2 for a disclosed gap is a legitimate steady state,
;; not a failure; what matters is whether it got WORSE since last run, i.e. actual drift), then
;; appends the new run's lines to the ledger. Exits 1 iff any claim regressed, printing which
;; ones and why, so CI can gate on real drift instead of permanent-red-on-known-gaps.
;;
;; Run: nbb 90-docs/repo-reality/ci_gate.cljs   (from the superproject root; expects
;; orgs/kotoba-lang/{kotoba,kotoba-lang,kotobase,aiueos,kototama} checked out and
;; npm ci already run so ["datascript"]-adjacent deps... actually this script needs no
;; datascript, only the sibling verify_*.cljs scripts run as child processes.)

(ns repo-reality.ci-gate
  (:require ["fs" :as fs]
            ["child_process" :as cp]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def ledger-path "90-docs/repo-reality/repo-reality-ledger.edn")
(def scripts ["verify_aiueos.cljs" "verify_kotoba.cljs" "verify_kotoba_lang.cljs"
              "verify_kotobase.cljs" "verify_kototama.cljs"])

(defn read-ledger-lines []
  (->> (str/split-lines (.readFileSync fs ledger-path "utf8"))
       (remove #(or (str/blank? %) (str/starts-with? (str/trim %) ";")))
       (map reader/read-string)))

;; baseline = most recent (by :eval/seq within the last run-id seen per claim, but simplest +
;; robust against interleaved run-ids: last OCCURRENCE in file order, since appends are always
;; chronological) score per claim.
(defn baseline-scores [lines]
  (reduce (fn [acc {:keys [eval/claim eval/score]}] (assoc acc claim score)) {} lines))

(defn run-script [script]
  (let [out (.toString (cp/execSync (str "nbb 90-docs/repo-reality/" script) #js {:cwd (.cwd js/process)}))]
    (->> (str/split-lines out)
         (remove str/blank?)
         (map reader/read-string))))

(defn -main []
  (let [baseline (baseline-scores (read-ledger-lines))
        new-lines (mapcat run-script scripts)
        regressions (keep (fn [{:keys [eval/claim eval/score eval/note] :as line}]
                             (when-let [prev (get baseline claim)]
                               (when (< score prev)
                                 {:claim claim :prev prev :new score :note note})))
                           new-lines)]
    (println (str "Ran " (count scripts) " verify scripts, " (count new-lines) " claim checks."))
    (doseq [{:keys [eval/claim eval/score eval/axis]} new-lines]
      (let [prev (get baseline claim)
            marker (cond (nil? prev) "NEW " (< score prev) "DOWN" (> score prev) "UP  " :else "==  ")]
        (println (str "  " marker " " (name claim) " [" (name axis) "] " prev " -> " score))))
    (binding [*print-namespace-maps* false]
      (doseq [line new-lines]
        (.appendFileSync fs ledger-path (str (pr-str line) "\n"))))
    (println (str "\nAppended " (count new-lines) " lines to " ledger-path "."))
    (if (seq regressions)
      (do (println (str "\nREGRESSIONS (" (count regressions) "): score dropped since last run —"))
          (doseq [{:keys [claim prev new note]} regressions]
            (println (str "  " (name claim) ": " prev " -> " new "\n    " note)))
          (.exit js/process 1))
      (do (println "\nNo regressions — no claim scored lower than its last recorded run.")
          (.exit js/process 0)))))

(-main)
