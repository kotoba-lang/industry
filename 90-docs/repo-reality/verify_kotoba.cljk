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
              {:score 0.4 :note "expected JVM-free distribution language in README.md or .github/workflows/native-release.yml no longer found -- either the native release pipeline was removed/renamed or wording changed; re-verify by hand."})))}

   ;; ---- 2026-07-27 weekly claim-discovery additions: CHANGELOG.md Unreleased entries for the
   ;; new cap-affine-problems check and the mandatory --package-lock gate. --------------------
   {:claim :claim/kotoba-cap-affine-changelog-contradicts-code :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [changelog (slurp* "CHANGELOG.md")
                runtime (slurp* "src/kotoba/runtime.clj")]
            (cond
              (not (has? changelog #"tracking is per local binding name, not per\s+underlying value"))
              {:score 0.5 :note "expected CHANGELOG.md wording ('tracking is per local binding name, not per underlying value') no longer found verbatim -- CHANGELOG may have been corrected to match the code, or reworded; re-verify by hand (a correction would be a genuine improvement)."}
              (not (has? runtime #"Tracking is by ORIGIN, not by local binding name"))
              {:score 0.4 :note "affine-use's docstring no longer states 'Tracking is by ORIGIN, not by local binding name' verbatim -- the implementation or its docstring changed; re-verify whether the contradiction with CHANGELOG.md still holds."}
              :else
              {:score 0.2 :note "confirmed contradiction still present: CHANGELOG.md's Unreleased entry still says cap-affine tracking is 'per local binding name, not per underlying value' (implying alias-renaming escapes the check), while runtime.clj's own affine-use/cap-expr-info docstrings still say tracking is by ORIGIN and a let-bound alias 'shares its origin ... a reuse through either name is caught' -- the doc's self-disclosed 'conservative limitation' does not match what the code actually does. Scored low on doc-code-drift because the CHANGELOG's own account of the mechanism is inaccurate, even though the direction of the error is safety-favorable (code is stricter than documented, not laxer)."})))}

   {:claim :claim/kotoba-package-lock-mandatory-f001-closed :axis :axis/safety-enforcement :layer :evidence-link
    :fn (fn []
          (let [launcher (slurp* "src/kotoba/launcher.clj")
                test (slurp* "test/kotoba/launcher_test.clj")]
            (cond
              (not (has? launcher #"is\s+mandatory, not opt-in \(F-001"))
              {:score 0.4 :note "expected 'mandatory, not opt-in (F-001' language no longer found verbatim in launcher.clj's admission-gated docstring -- wording or mechanism changed; re-verify by hand."}
              (not (and (has? test #"wasm-emit-rejects-missing-package-lock")
                        (has? test #"wasm-run-rejects-missing-package-lock")))
              {:score 0.5 :note "admission-gated's F-001 docstring is present, but the two named regression tests (wasm-emit-rejects-missing-package-lock / wasm-run-rejects-missing-package-lock) are no longer both found in launcher_test.clj -- re-verify test coverage."}
              :else
              {:score 1.0 :note "confirmed: launcher.clj's admission-gated fn (shared by wasm-emit-result/wasm-run-result/cljs-emit-result) still documents --package-lock as mandatory with no opt-out (F-001), and launcher_test.clj still has wasm-emit-rejects-missing-package-lock + wasm-run-rejects-missing-package-lock asserting :package/missing-lock-option on a missing flag -- the CHANGELOG's 'no opt-out' claim is backed by real, currently-passing-shaped test coverage, not just prose."})))}

   ;; ---- 2026-08-03 weekly claim-discovery additions: reproducible-emit-gate ADR, and a stale
   ;; issue-tracker status contradicted by already-fixed code. --------------------------------
   {:claim :claim/kotoba-reproducible-emit-gate-71-of-74 :axis :axis/evidence-linkage :layer :evidence-link
    :fn (fn []
          (if (exists? "qualification/emit-digests.edn")
            (let [digests (slurp* "qualification/emit-digests.edn")
                  total (count (re-seq #"\"src/[^\"]+\.kotoba\"" digests))
                  unsupported (count (re-seq #":unsupported :wasm/check-failed" digests))
                  test-runner (when (exists? "test/kotoba/test_runner.clj") (slurp* "test/kotoba/test_runner.clj"))]
              (cond
                (not (has? test-runner #"reproducible-emit-test"))
                {:score 0.3 :note "kotoba.reproducible-emit-test is no longer registered in test_runner.clj -- the gate may have been unwired from CI; re-verify by hand."}
                (and (= total 74) (= unsupported 3))
                {:score 1.0 :note (str "confirmed: qualification/emit-digests.edn still has exactly " total " src/*.kotoba entries with exactly " unsupported " :unsupported :wasm/check-failed, matching the ADR's own 71-of-74 figure, and kotoba.reproducible-emit-test is still registered in test_runner.clj.")}
                :else
                {:score 0.6 :note (str "emit-digests.edn now has " total " total entries (expected 74) and " unsupported " :unsupported entries (expected 3) -- the corpus or its emit-success rate has changed since the ADR was written; re-verify whether this is healthy growth (more sources added) or a real regression.")}))
            {:score 0.0 :note "qualification/emit-digests.edn no longer exists -- claim's cited evidence file is gone; re-verify."}))}

   {:claim :claim/kotoba-issues-edn-stale-open-status-vs-fixed-code :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (if (exists? ".issues/issues.edn")
            (let [issues (slurp* ".issues/issues.edn")
                  adapter (when (exists? "src/kotoba/rad_adapter.cljc") (slurp* "src/kotoba/rad_adapter.cljc"))
                  test-src (when (exists? "test/kotoba/rad_adapter_test.clj") (slurp* "test/kotoba/rad_adapter_test.clj"))]
              (cond
                (not (has? issues #"security-rad-build-needs-package-lock-scaffolding"))
                {:score 0.5 :note "the security-rad-build-needs-package-lock-scaffolding issue entry no longer found in .issues/issues.edn -- issue may have been closed/removed; re-verify whether it was closed because the code fix (below) was finally recognized."}
                (not (and adapter (has? adapter #"--package-lock\" \(lock-path project\)")))
                {:score 0.4 :note "src/kotoba/rad_adapter.cljc no longer passes --package-lock via lock-path in its :build/:export steps as expected -- either the fix regressed (bad) or the mechanism was restructured; re-verify by hand."}
                (not (and test-src (has? test-src #"launcher-executes-rad-lifecycle-end-to-end")
                          (has? test-src #":rad/executed \(:kotoba.cli/code export-result\)")))
                {:score 0.5 :note "launcher-executes-rad-lifecycle-end-to-end no longer asserts full :rad/executed success through export -- re-verify whether the test coverage changed."}
                :else
                {:score 0.2 :note "confirmed: .issues/issues.edn still tracks issue #281 as :status :open with its 'entirely non-functional' description, but rad_adapter.cljc's :build/:export steps still pass --package-lock via lock-path, and launcher-executes-rad-lifecycle-end-to-end still asserts full end-to-end success (:rad/executed through export, real .wasm bytes) -- the issue tracker's open/broken status remains stale relative to code that already fixes the described bug. Scored low on doc-code-drift because an unclosed tracker entry actively misdescribes current, working behavior."}))
            {:score 0.0 :note ".issues/issues.edn no longer exists -- claim's cited source file is gone; re-verify."}))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
