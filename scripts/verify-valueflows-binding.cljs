#!/usr/bin/env nbb
;; scripts/verify-valueflows-binding.cljs — keep the cloud-itonami Valueflows
;; binding from rotting silently.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-valueflows-binding.cljs \
;;     --root $HOME/github/com-junkawasaki [--findings]
;;
;; Registered in manifest/orgs-detectors.edn rather than scripts/fleet-ci/gates.edn
;; for the structural reason that file exists: this reads `orgs/`, a fleet node
;; ships one repo's tree, so a gate here could never go green (ADR-2608124800).
;;
;; Three checks, none of which re-implements the derivation — the binder is
;; invoked for that. A second copy of the classification logic is how one copy
;; gets fixed and the other does not.
;;
;;   1. the committed business binding still matches the workspace
;;   2. the ISIC Rev.5 mirror still matches its recorded sha256
;;   3. the Rev.4 provenance conflict is still exactly what was measured
;;   4. the committed uchiwake resource projection still matches the workspace
;;   5. the committed uchiwake recipe projection still matches the workspace
;;
;; Check 3 is the interesting one. org-un-isic's Rev.4 half is unpinned and
;; disputed against the UN's legacy structure file on 33 of 414 class titles
;; (data/PROVENANCE.edn). 122 businesses take an industry name from it. If that
;; record and the mirror stop agreeing, either the mirror was re-ingested or the
;; record was edited, and both need a human — silently is the one way it must
;; not happen.
;;
;; The Rev.4 check deliberately compares the mirror against the RECORD, not
;; against the network. Refetching would make this detector fail whenever the UN
;; site is down, which says nothing about this workspace.

(ns verify-valueflows-binding
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            ["child_process" :as cp]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def args (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (boolean (some #{"--findings"} args)))
(def repo (.cwd js/process))
(def root (or (second (drop-while #(not= "--root" %) args)) repo))

(def findings (atom []))
(defn finding! [sev k detail] (swap! findings conj [sev k detail]))

(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- exists? [p] (fs/existsSync p))
(defn- sha256 [p] (-> (crypto/createHash "sha256")
                      (.update (fs/readFileSync p))
                      (.digest "hex")))

(def projection "90-docs/valueflows/itonami-business-vf.datoms.edn")
(def resource-projection "90-docs/valueflows/uchiwake-resources-vf.datoms.edn")
(def recipe-projection "90-docs/valueflows/uchiwake-recipes-vf.datoms.edn")

(defn- run-generator
  "Invoke a projection's own generator in --check mode. Never re-derive here: a
   second copy of the derivation is how one copy gets fixed and the other does
   not."
  [script-name out key-prefix]
  (let [script (path/join repo "scripts" script-name)]
    (if-not (exists? script)
      (finding! "high" (str key-prefix "-generator-absent")
                (str script " is missing; " out " cannot be checked"))
      (let [r (.spawnSync cp "nbb"
                          (clj->js ["--classpath" (str repo (str ":") repo "/scripts/nbb_compat")
                                    script "--data-root" root "--check"])
                          #js {:cwd repo :encoding "utf8" :timeout 600000})
            code (.-status r)
            out* (str/trim (str (or (.-stdout r) "")))]
        (cond
          (= 0 code) :ok
          (= 1 code) (finding! "high" (str key-prefix "-stale")
                               (str "the committed " out " disagrees with the workspace;"
                                    " regenerate with scripts/" script-name))
          (= 2 code) (finding! "high" (str key-prefix "-inputs-missing")
                               (str "the generator could not answer -- " out*))
          :else (finding! "high" (str key-prefix "-check-failed")
                          (str "scripts/" script-name " --check exited " code
                               " -- " (str/trim (str (or (.-stderr r) ""))))))))))

;; ── 1. the projection matches the workspace ───────────────────────────────

(defn check-projection! []
  (run-generator "itonami-valueflows-bind.cljs" projection "binding"))

(defn check-resource-projection! []
  (run-generator "uchiwake-valueflows-resources.cljs" resource-projection "resources"))

(defn check-recipe-projection! []
  ;; The only projection with quantities, so the only one the algorithms can run
  ;; on. A silent regeneration that changed a mass would change an explosion.
  (run-generator "uchiwake-valueflows-recipes.cljs" recipe-projection "recipes"))

;; ── 2. the Rev.5 mirror matches its recorded pin ──────────────────────────

(defn check-rev5-pin! []
  (let [dir (path/join root "orgs" "cloud-itonami" "org-un-isic" "data" "rev5")
        up (path/join dir "upstream.edn")
        csv (path/join dir "ISIC_Rev_5_english_structure.csv")]
    (cond
      (not (exists? up))
      (finding! "high" "rev5-upstream-record-absent"
                (str up " is missing; the Rev.5 mirror has no pin"))

      (not (exists? csv))
      (finding! "high" "rev5-source-absent" (str csv " is missing"))

      :else
      (let [pinned (:sha256 (edn/read-string (slurp* up)))
            actual (sha256 csv)]
        (when-not (= pinned actual)
          (finding! "high" "rev5-pin-drift"
                    (str "ISIC Rev.5 CSV sha256 " actual " does not match the pin "
                         pinned "; the mirror changed without its record")))))))

;; ── 3. the Rev.4 conflict is still what was measured ──────────────────────

(defn check-rev4-conflict! []
  (let [prov (path/join root "orgs" "cloud-itonami" "org-un-isic" "data" "PROVENANCE.edn")
        cls (path/join root "orgs" "cloud-itonami" "org-un-isic" "data" "classes")]
    (if-not (exists? prov)
      (finding! "medium" "rev4-provenance-record-absent"
                (str prov " is missing; the Rev.4 mirror's disputed status is no"
                     " longer recorded anywhere"))
      (let [p (edn/read-string (slurp* prov))
            recorded (get-in p [:rev4-conflict :class-titles-that-differ])
            codes (set (get-in p [:rev4-conflict :differing-codes]))]
        (when-not (number? recorded)
          (finding! "medium" "rev4-conflict-unrecorded"
                    "PROVENANCE.edn does not state how many class titles differ"))
        (when (and (number? recorded) (not= recorded (count codes)))
          (finding! "medium" "rev4-conflict-count-mismatch"
                    (str "PROVENANCE.edn says " recorded " titles differ but lists "
                         (count codes) " codes")))
        (when (and (seq codes) (exists? cls))
          (let [mirrored (into #{}
                               (keep (fn [f]
                                       (when (str/ends-with? f ".json")
                                         (get (js->clj (js/JSON.parse (slurp* (path/join cls f))))
                                              "code"))))
                               (js->clj (fs/readdirSync cls)))
                missing (remove #(contains? mirrored %) codes)]
            (when (seq missing)
              (finding! "medium" "rev4-conflict-record-stale"
                        (str (count missing) " of the recorded differing codes are no"
                             " longer in the mirror (" (str/join " " (take 5 missing))
                             "); the Rev.4 half was re-ingested without updating"
                             " PROVENANCE.edn")))))))))

;; ── report ────────────────────────────────────────────────────────────────

(defn- businesses-scanned []
  (let [f (path/join repo projection)]
    (if-not (exists? f)
      0
      (or (:vf.coverage/businesses (last (edn/read-string (slurp* f)))) 0))))

(check-projection!)
(check-resource-projection!)
(check-recipe-projection!)
(check-rev5-pin!)
(check-rev4-conflict!)

(let [n (businesses-scanned)]
  ;; The evidence floor. A run that scanned nothing must not be recorded clean —
  ;; orgs-detectors.edn fails an entry whose SCANNED count is 0.
  (println (str "SCANNED\t" n "\tvalueflows-binding"))
  (if findings-mode?
    (doseq [[sev k detail] @findings]
      (println (str "FINDING\t" sev "\t" k "\t" detail)))
    (do (doseq [[sev k detail] @findings]
          (println (str sev " " k " -- " detail)))
        (println (str (count @findings) " finding(s)"))))
  (js/process.exit (cond (zero? n) 2
                         (seq @findings) 1
                         :else 0)))
