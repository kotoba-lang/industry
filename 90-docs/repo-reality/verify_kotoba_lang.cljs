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

   {:claim :claim/kotoba-lang-packages-deferred :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [readme (slurp* "docs/lang/README.md")
                cli (slurp* "src/kotoba/cli.cljc")
                contract-exists? (exists? "src/kotoba/lang/package_contract.cljc")
                registry-wired? (has? cli #"\"registry\"")]
            (cond
              (not (has? readme #"deferred"))
              {:score 0.5 :note "docs/lang/README.md no longer says 'registry' is deferred -- may have landed (check for a real registry subcommand) or wording changed; re-verify."}
              registry-wired?
              {:score 0.3 :note "docs still say registry/:packages is deferred, but cli.cljc now has a \"registry\" string literal -- possible the CLI landed and docs are now STALE; re-verify by hand whether this is a real subcommand."}
              (not contract-exists?)
              {:score 0.5 :note "package_contract.cljc no longer exists -- either the deferred track was abandoned entirely or restructured; re-verify."}
              :else
              {:score 1.0 :note "confirmed: docs still describe :packages/registry as deferred/out-of-scope, package_contract.cljc exists as a data-contract-only file, and cli.cljc has no \"registry\" subcommand -- disclosed gap matches the code exactly (contract shape exists, CLI wiring does not)."})))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
