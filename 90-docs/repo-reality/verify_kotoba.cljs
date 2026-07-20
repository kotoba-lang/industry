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

   ;; 2026-07-16 gap fix: this claim declares TWO axes in repo-reality.datoms.edn
   ;; (functional-completeness + doc-code-drift), but originally only emitted ONE event on
   ;; functional-completeness with a score of 1.0 meaning "confirmed the claim's TEXT is
   ;; accurate" -- conflating claim-accuracy with feature-completeness. kotoba-shell is
   ;; genuinely NOT complete (zero runtime wiring, design-doc only), so functional-completeness
   ;; must score low; doc-code-drift is what should score high (the incompleteness IS honestly
   ;; disclosed). Split into two checks below, matching aiueos's convention
   ;; (claim/aiueos-device-access-stub / claim/aiueos-deadline-cycles) exactly.
   {:claim :claim/kotoba-shell-not-wired :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [launcher (slurp* "src/kotoba/launcher.clj")]
            (if (has? launcher #"\"shell\"")
              {:score 0.5 :note "launcher.clj now has a 'shell' string literal that wasn't there before -- possible partial progress on wiring the subcommand; re-verify by hand whether this is real routing or an unrelated string, and revise this score/claim accordingly."}
              {:score 0.1 :note "confirmed: launcher.clj has no \"shell\" string literal at all -- kotoba-shell has zero runtime presence beyond its ADR design doc, so functional-completeness is scored low (0.1), not merely 'claim confirmed'."})))}

   {:claim :claim/kotoba-shell-not-wired :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [readme (slurp* "README.md")
                launcher (slurp* "src/kotoba/launcher.clj")]
            (cond
              (not (has? readme #"design, not yet shipped"))
              {:score 0.3 :note "README no longer has the 'kotoba-shell release pipeline (design, not yet shipped)' heading -- either shipped (check launcher for a real 'shell' subcommand) or reworded; re-verify."}
              (has? launcher #"\"shell\"")
              {:score 0.4 :note "README still says kotoba-shell is not wired up, but launcher.clj now has a 'shell' string literal -- possible the subcommand landed and the README caveat is now STALE (undisclosed drift); re-verify by hand whether this is a real subcommand or an unrelated string."}
              :else
              {:score 1.0 :note "confirmed: README still says no 'kotoba shell' subcommand is wired, and launcher.clj has no \"shell\" string literal at all -- the disclosed gap matches the code, i.e. this is an honestly-reported incompleteness, not silent drift."})))}

   ;; 2026-07-20 weekly claim-discovery addition.
   {:claim :claim/kotoba-security-non-claims-disclosed :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "docs/ADR-security-kaizen-20260717.md")
            (let [adr (slurp* "docs/ADR-security-kaizen-20260717.md")]
              (if (has? adr #"Side channels, formal verification, FIPS, PQC production")
                {:score 1.0 :note "confirmed: docs/ADR-security-kaizen-20260717.md still lists 'Side channels, formal verification, FIPS, PQC production, and complete signer lifecycle' as explicit non-claims, right after describing the fail-closed kgraph/allowlist/consume-on-use hardening it DOES ship -- an honestly-scoped boundary, not a silent overclaim."}
                {:score 0.4 :note "expected 'Side channels, formal verification, FIPS, PQC production' non-claims language no longer found verbatim -- either scope was widened (re-verify what's now claimed) or the ADR was reworded; re-verify by hand."}))
            {:score 0.0 :note "docs/ADR-security-kaizen-20260717.md no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kotoba-cond-loop-recur-interpreter-gap :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [rt (slurp* "src/kotoba/runtime.clj")
                wasm-has-loop? (has? rt #"loop \(let \[\[bindings")
                interp-case (or (re-find #"(?s)\(case op\s+ns nil.*?call-fn \(eval-form op" rt) "")
                interp-has-cond-loop-recur? (has? (str interp-case) #"(?m)^\s+(cond|loop|recur)\s")]
            (cond
              (not wasm-has-loop?)
              {:score 0.4 :note "expected WASM-codegen 'loop (let [[bindings ...' transform case no longer found verbatim in runtime.clj -- codegen support for loop may have been restructured; re-verify."}
              interp-has-cond-loop-recur?
              {:score 0.9 :note "eval-form's case-op dispatch now appears to have a cond/loop/recur clause -- the interpreter-side gap this claim tracks may have closed; re-verify by hand and update claim/self-caveat if confirmed."}
              :else
              {:score 0.3 :note "confirmed: the WASM codegen's transform fn still has a real 'loop' case, but eval-form's case-op dispatch still has no cond/loop/recur clause -- the interpreter-side gap the 2026-07-13 roadmap ADR named (for both backends) persists on this one backend only, while the ADR text itself has not been updated to reflect the WASM-side fix."})))}

   {:claim :claim/kotoba-native-jvm-free-cli :axis :axis/production-readiness :layer :lint
    :fn (fn []
          (let [readme (slurp* "README.md")]
            (if (and (has? readme #"Neither a JVM nor Clojure CLI is required at runtime")
                     (has? readme #"authoritative JVM-free distribution paths")
                     (exists? ".github/workflows/native-release.yml"))
              {:score 1.0 :note "confirmed: README.md still states the native installer requires 'Neither a JVM nor Clojure CLI ... at runtime' and calls Homebrew/shell installs 'the authoritative JVM-free distribution paths', and .github/workflows/native-release.yml (GraalVM native-image build) still exists -- the JVM-free distribution claim is backed by a real release pipeline, not just README prose."}
              {:score 0.4 :note "expected JVM-free distribution language in README.md or .github/workflows/native-release.yml no longer found -- either the native release pipeline was removed/renamed or wording changed; re-verify by hand."})))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
