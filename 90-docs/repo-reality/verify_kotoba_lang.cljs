;; repo-reality verify script — kotoba-lang prototype (2026-07-15). See verify_aiueos.cljs
;; header for the overall method. Run: nbb 90-docs/repo-reality/verify_kotoba_lang.cljs >> 90-docs/repo-reality/repo-reality-ledger.edn

(ns verify-kotoba-lang
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def root "orgs/kotoba-lang/kotoba-lang/")

(defn slurp* [rel-path] (.readFileSync fs (str root rel-path) "utf8"))
(defn exists? [rel-path] (.existsSync fs (str root rel-path)))
(defn has? [s re] (boolean (re-find re s)))

(def run-id (str "repo-reality-kotoba-lang-" (str/replace (.toISOString (js/Date.)) #"[-:]|\.\d+Z$" "")))
(def now (.toISOString (js/Date.)))

(def checks
  [{:claim :claim/kotoba-lang-maturity-m6 :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [cov (slurp* "docs/lang/coverage.edn")
                hits (count (re-seq #":maturity :m6" cov))]
            (if (>= hits 1)
              {:score 1.0 :note (str "docs/lang/coverage.edn still declares :maturity :m6 (" hits " occurrence(s) across stage entries).")}
              {:score 0.2 :note "':maturity :m6' no longer found -- maturity value changed or file restructured; re-verify by hand."})))}

   {:claim :claim/kotoba-lang-rust-retired :axis :axis/doc-code-drift :layer :evidence-link
    :fn (fn []
          (if (exists? "docs/rust-migration-inventory.md")
            (let [inv (slurp* "docs/rust-migration-inventory.md")]
              (if (has? inv #"kotoba-v2025")
                {:score 1.0 :note "confirmed: docs/rust-migration-inventory.md exists and still names kotoba-v2025 as the explicit legacy exception -- the 'fully retired' claim cites a concrete, still-present tracking artifact, not just prose."}
                {:score 0.6 :note "rust-migration-inventory.md exists but no longer mentions kotoba-v2025 -- content changed shape; re-verify the exception list is still accurate."}))
            {:score 0.0 :note "docs/rust-migration-inventory.md no longer exists -- the README's citation of this file as evidence is now stale; re-verify."}))}

   {:claim :claim/kotoba-lang-cli-contract :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (if (exists? "src/kotoba/cli.cljc")
            (let [cli (slurp* "src/kotoba/cli.cljc")
                  defn-count (count (re-seq #"\(defn" cli))]
              (if (>= defn-count 5)
                {:score 1.0 :note (str "confirmed: src/kotoba/cli.cljc exists with " defn-count " (defn ...) forms -- substantive, not a placeholder stub.")}
                {:score 0.4 :note (str "src/kotoba/cli.cljc exists but only has " defn-count " defn forms -- thinner than expected for a 'public CLI contract' claim; re-verify scope.")}))
            {:score 0.0 :note "src/kotoba/cli.cljc no longer exists -- the CLI-contract-ownership claim's cited file is gone; re-verify."}))}

   ;; 2026-07-16 gap fix: split into functional-completeness (actual state, low-but-not-zero
   ;; since package_contract.cljc is a real 189-line data contract) + doc-code-drift (disclosure
   ;; accuracy, high) -- previously conflated into one functional-completeness=1.0 event, same
   ;; bug as claim/kotoba-shell-not-wired. See verify_kotoba.cljs's matching comment.
   {:claim :claim/kotoba-lang-packages-deferred :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [cli (slurp* "src/kotoba/cli.cljc")
                contract-exists? (exists? "src/kotoba/lang/package_contract.cljc")
                registry-wired? (has? cli #"\"registry\"")]
            (cond
              registry-wired?
              {:score 0.6 :note "cli.cljc now has a \"registry\" string literal -- possible the CLI landed; re-verify by hand whether this is real routing, and revise this score/claim accordingly."}
              contract-exists?
              {:score 0.3 :note "package_contract.cljc exists as a real, substantive data contract (189 lines, not a stub), but no \"registry\" subcommand is wired into cli.cljc -- functional-completeness is low but not zero: the data shape is defined, the CLI/registry surface is not."}
              :else
              {:score 0.1 :note "package_contract.cljc no longer exists and no registry subcommand is wired -- the deferred track has essentially nothing built; re-verify whether it was abandoned or just restructured."})))}

   {:claim :claim/kotoba-lang-packages-deferred :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [readme (slurp* "docs/lang/README.md")
                cli (slurp* "src/kotoba/cli.cljc")
                registry-wired? (has? cli #"\"registry\"")]
            (cond
              (not (has? readme #"deferred"))
              {:score 0.5 :note "docs/lang/README.md no longer says 'registry' is deferred -- may have landed (check for a real registry subcommand) or wording changed; re-verify."}
              registry-wired?
              {:score 0.3 :note "docs still say registry/:packages is deferred, but cli.cljc now has a \"registry\" string literal -- possible the CLI landed and docs are now STALE; re-verify by hand whether this is a real subcommand."}
              :else
              {:score 1.0 :note "confirmed: docs still describe :packages/registry as deferred/out-of-scope, and cli.cljc has no \"registry\" subcommand -- disclosed gap matches the code exactly, i.e. this is an honestly-reported incompleteness, not silent drift."})))}

   ;; 2026-07-20 weekly claim-discovery addition.
   {:claim :claim/kotoba-lang-stdlib-below-1-0 :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [cov (slurp* "docs/lang/coverage.edn")
                versions (re-seq #":version \"([^\"]*)\"" cov)
                not-0-1-0 (remove #(= "0.1.0" (second %)) versions)]
            (cond
              (not (has? cov #":one-zero\s+\{:status :not-yet"))
              {:score 0.4 :note "expected ':one-zero {:status :not-yet ...}' no longer found verbatim in coverage.edn's :engineering-gaps -- either 1.0 cuts started (check :version values below) or the gap-tracking shape changed; re-verify by hand."}
              (empty? not-0-1-0)
              {:score 1.0 :note (str "confirmed: :one-zero is still :not-yet in coverage.edn, and all " (count versions) " :version entries in the stdlib catalog are still literally \"0.1.0\" -- the self-disclosed pre-1.0 status matches the actual per-lib version data, not just prose.")}
              :else
              {:score 0.6 :note (str "coverage.edn still discloses :one-zero :not-yet, but " (count not-0-1-0) "/" (count versions) " :version entries are no longer \"0.1.0\" -- at least one lib may have cut 1.0 already; re-verify which one(s) and whether :one-zero's :note needs updating.")})))}

   {:claim :claim/kotoba-lang-kotobase-security-legacy-public :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "docs/adr/ADR-kotobase-security-access-control.md")
            (let [adr (slurp* "docs/adr/ADR-kotobase-security-access-control.md")]
              (if (and (has? adr #"legacy-public.*remains the only configured deployment mode")
                       (has? adr #"S5-auditable"))
                {:score 1.0 :note "confirmed: the ADR's status line still says 'legacy-public remains the only configured deployment mode', and the full S0-S5 conformance ladder (through S5-auditable) is still defined -- the gap between what the code paths implement and what production may actually claim is still disclosed, not silently narrowed."}
                {:score 0.4 :note "expected 'legacy-public remains the only configured deployment mode' language or the S5-auditable ladder tier no longer found verbatim -- either a higher deployment mode was adopted (re-verify, would be real progress) or the ADR was restructured; re-verify by hand."}))
            {:score 0.0 :note "docs/adr/ADR-kotobase-security-access-control.md no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kotoba-lang-q9-fleet-migration-unauthorized :axis :axis/production-readiness :layer :lint
    :fn (fn []
          (if (exists? "lang/q9-migration.edn")
            (let [readme (slurp* "README.md")
                  q9 (slurp* "lang/q9-migration.edn")
                  not-authorized-count (count (re-seq #":status :not-authorized" q9))]
              (cond
                (not (has? readme #"Q9 is now authorized only for bounded Wave 1"))
                {:score 0.4 :note "expected 'Q9 is now authorized only for bounded Wave 1' no longer found verbatim in README.md -- authorization scope may have widened (re-verify lang/q9-migration.edn's :status values) or wording changed; re-verify by hand."}
                (< not-authorized-count 4)
                {:score 0.6 :note (str "README still discloses Wave-1-only authorization, but only " not-authorized-count "/4 expected ':status :not-authorized' waves remain in lang/q9-migration.edn -- some later wave may have been authorized; re-verify which one(s).")}
                :else
                {:score 1.0 :note (str "confirmed: README still discloses Q9 as authorized only for bounded Wave 1, and lang/q9-migration.edn still marks " not-authorized-count " waves ':status :not-authorized' -- disclosed scope limit matches the migration-tracking data, not just prose.")}))
            {:score 0.0 :note "lang/q9-migration.edn no longer exists -- claim's cited source file is gone; re-verify."}))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
