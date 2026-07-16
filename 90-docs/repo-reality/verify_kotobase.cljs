;; repo-reality verify script — kotobase prototype (2026-07-15). See verify_aiueos.cljs header
;; for the overall method. Run: nbb 90-docs/repo-reality/verify_kotobase.cljs >> 90-docs/repo-reality/repo-reality-ledger.edn

(ns verify-kotobase
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def root "orgs/kotoba-lang/kotobase/")

(defn slurp* [rel-path] (.readFileSync fs (str root rel-path) "utf8"))
(defn exists? [rel-path] (.existsSync fs (str root rel-path)))
(defn has? [s re] (boolean (re-find re s)))

(def run-id (str "repo-reality-kotobase-" (str/replace (.toISOString (js/Date.)) #"[-:]|\.\d+Z$" "")))
(def now (.toISOString (js/Date.)))

(def checks
  [{:claim :claim/kotobase-istore-seam :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (if (exists? "src/kotobase/store.cljc")
            (let [store (slurp* "src/kotobase/store.cljc")
                  local-exists? (exists? "src/kotobase/local.cljc")]
              (cond
                (not (has? store #"defprotocol IStore"))
                {:score 0.2 :note "src/kotobase/store.cljc exists but no longer defines 'defprotocol IStore' verbatim -- protocol may have been renamed/moved; re-verify."}
                (not local-exists?)
                {:score 0.5 :note "IStore protocol confirmed in store.cljc, but src/kotobase/local.cljc (the concrete LocalStore implementation cited) no longer exists -- partial drift."}
                :else
                {:score 1.0 :note "confirmed: src/kotobase/store.cljc defines 'defprotocol IStore' and src/kotobase/local.cljc exists as a concrete implementation -- claim holds."}))
            {:score 0.0 :note "src/kotobase/store.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kotobase-datomic-analogy :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [readme (slurp* "README.md")]
            (if (and (has? readme #"(?i)Datomic.{0,10}to kotoba.{0,3}s.{0,10}Clojure")
                     (has? readme #"(?i)Prolly Tree")
                     (has? readme #"(?i)commit DAG"))
              {:score 1.0 :note "confirmed: README still frames kotobase as 'the Datomic to kotoba's Clojure' and names the concrete architectural substitutions (Prolly Tree for B-tree, commit DAG for single log) rather than leaving the analogy unexplained."}
              {:score 0.4 :note "README no longer states the full Datomic-analogy + concrete-substitution language verbatim -- wording likely evolved; re-verify the framing is still accurate, not just present."})))}

   {:claim :claim/kotobase-m5-doc-inconsistency :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [versioning (slurp* "docs/versioning.md")
                cov (slurp* "docs/coverage.edn")
                versioning-says-open? (has? versioning #"(?i)open M5 gap")
                cov-says-resolved? (has? cov #"(?i)Resolved 2026-07-08")]
            (cond
              (and versioning-says-open? cov-says-resolved?)
              {:score 0.3 :note "confirmed inconsistency STILL PRESENT: docs/versioning.md's prose still calls M5 an 'open M5 gap', while docs/coverage.edn's own :m5 stage note says it was 'Resolved 2026-07-08' -- two docs in the same repo disagree; versioning.md needs updating. Scored low deliberately: this is real doc-vs-doc drift, not a disclosed/self-caveated gap."}
              (and (not versioning-says-open?) cov-says-resolved?)
              {:score 1.0 :note "confirmed fixed: versioning.md no longer says 'open M5 gap' and coverage.edn still says resolved -- matches the fix landed in kotobase PR #6 (2026-07-16). This check now guards against the inconsistency silently reappearing (e.g. a future edit reverting versioning.md's wording), not against the original bug."}
              :else
              {:score 0.6 :note "one or both of the expected strings ('open M5 gap' in versioning.md, 'Resolved 2026-07-08' in coverage.edn) no longer found verbatim -- wording changed on at least one side; re-verify by hand whether the underlying inconsistency (if any) still exists."})))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
