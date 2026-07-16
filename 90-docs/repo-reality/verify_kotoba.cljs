;; repo-reality verify script — kotoba prototype (2026-07-15). See verify_aiueos.cljs header
;; for the overall method (executable claims + evidence linkage, output = ledger event lines).
;; Run: nbb 90-docs/repo-reality/verify_kotoba.cljs >> 90-docs/repo-reality/repo-reality-ledger.edn

(ns verify-kotoba
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def root "orgs/kotoba-lang/kotoba/")

(defn slurp* [rel-path] (.readFileSync fs (str root rel-path) "utf8"))
(defn exists? [rel-path] (.existsSync fs (str root rel-path)))
(defn has? [s re] (boolean (re-find re s)))

(def run-id (str "repo-reality-kotoba-" (str/replace (.toISOString (js/Date.)) #"[-:]|\.\d+Z$" "")))
(def now (.toISOString (js/Date.)))

(def checks
  [{:claim :claim/kotoba-maturity-m6 :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [cov (slurp* "docs/lang/coverage.edn")]
            (if (has? cov #":kotoba\.lang\.coverage/maturity :m6")
              {:score 1.0 :note "docs/lang/coverage.edn still declares :maturity :m6 verbatim."}
              {:score 0.2 :note "expected ':kotoba.lang.coverage/maturity :m6' no longer found verbatim -- maturity value changed or file restructured; re-verify by hand."})))}

   {:claim :claim/kotoba-rust-removed :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [crates-gone? (not (exists? "crates"))
                cargo-gone? (not (exists? "Cargo.toml"))]
            (if (and crates-gone? cargo-gone?)
              {:score 1.0 :note "confirmed: no crates/ directory and no root Cargo.toml -- the README's 'Rust workspace fully removed' claim holds against the actual tree, not just prose."}
              {:score 0.0 :note (str "crates/ present=" (not crates-gone?) ", Cargo.toml present=" (not cargo-gone?) " -- README claims Rust fully removed but Rust artifacts are back; investigate whether this is a reintroduction or a doc that needs updating.")})))}

   {:claim :claim/kotoba-cljs-reinstated :axis :axis/evidence-linkage :layer :evidence-link
    :fn (fn []
          (let [changelog (slurp* "CHANGELOG.md")
                demo-exists? (exists? "src/demo.cljs")
                demo-src (when demo-exists? (slurp* "src/demo.cljs"))]
            (cond
              (not (has? changelog #"Reinstated `\.cljs`"))
              {:score 0.3 :note "CHANGELOG.md no longer has the 'Reinstated .cljs' bullet in the form expected -- entry may have moved to a released version section or been reworded; re-verify."}
              (not demo-exists?)
              {:score 0.2 :note "CHANGELOG cites src/demo.cljs as proof but the file no longer exists -- claim's own evidence artifact is gone, this is exactly the kind of drift periodic re-runs are meant to catch."}
              (has? demo-src #"\(ns demo\)")
              {:score 1.0 :note "confirmed: CHANGELOG's '.cljs reinstated' bullet is present, and src/demo.cljs exists with a bare (ns demo) + defn main -- matches the claim that a plain .cljs file is accepted with no special namespacing."}
              :else
              {:score 0.6 :note "demo.cljs exists but its content changed shape from the simple (ns demo) form originally cited -- likely fine (file evolved) but worth a manual glance."})))}

   {:claim :claim/kotoba-shell-not-wired :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [readme (slurp* "README.md")
                launcher (slurp* "src/kotoba/launcher.clj")]
            (cond
              (not (has? readme #"design, not yet shipped"))
              {:score 0.3 :note "README no longer has the 'kotoba-shell release pipeline (design, not yet shipped)' heading -- either shipped (check launcher for a real 'shell' subcommand) or reworded; re-verify."}
              (has? launcher #"\"shell\"")
              {:score 0.4 :note "README still says kotoba-shell is not wired up, but launcher.clj now has a 'shell' string literal -- possible the subcommand landed and the README caveat is now STALE (undisclosed drift); re-verify by hand whether this is a real subcommand or an unrelated string."}
              :else
              {:score 1.0 :note "confirmed: README still says no 'kotoba shell' subcommand is wired, and launcher.clj has no \"shell\" string literal at all -- the disclosed gap matches the code."})))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
