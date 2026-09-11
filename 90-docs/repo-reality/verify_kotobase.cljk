;; repo-reality verify script — kotobase prototype (2026-07-15). See verify_aiueos.cljs header
;; for the overall method. Run: nbb 90-docs/repo-reality/verify_kotobase.cljs >> 90-docs/repo-reality/repo-reality-ledger.edn

(ns verify-kotobase
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def root "orgs/kotoba-lang/kotobase/")

(defn slurp* [rel-path] (.readFileSync fs (str root rel-path) "utf8"))
(defn exists? [rel-path] (.existsSync fs (str root rel-path)))
(defn has? [s re] (boolean (re-find re s)))

(def ^:private skip-dirs
  #{"node_modules" ".git" "target" ".cpcache" ".clj-kondo" ".lsp" ".shadow-cljs"})

(defn list-source-files
  "Recursively list relative paths under the kotobase root whose names end in one of
   `exts` (e.g. [\".kotoba\" \".clj\" \".cljc\"]). Skips common non-source dirs so a
   repo-wide claim check stays cheap and stable across local west checkouts / scratch clones."
  [exts]
  (let [abs-root root]
    (if-not (.existsSync fs abs-root)
      []
      (letfn [(walk [dir prefix]
                (->> (.readdirSync fs dir #js {:withFileTypes true})
                     (mapcat (fn [e]
                               (let [name (.-name e)
                                     rel (if (str/blank? prefix) name (str prefix "/" name))]
                                 (cond
                                   (and (.isDirectory e) (contains? skip-dirs name)) []
                                   (.isDirectory e) (walk (str dir "/" name) rel)
                                   (some #(str/ends-with? name %) exts) [rel]
                                   :else []))))))]
        (vec (walk abs-root ""))))))

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
              {:score 0.6 :note "one or both of the expected strings ('open M5 gap' in versioning.md, 'Resolved 2026-07-08' in coverage.edn) no longer found verbatim -- wording changed on at least one side; re-verify by hand whether the underlying inconsistency (if any) still exists."})))}

   ;; 2026-07-20 weekly claim-discovery addition. The code-graph module (added 2026-07-14)
   ;; self-discloses that CID/content-addressing alone confers no authority -- checking that
   ;; both the README prose AND the module's own docstrings still carry this caveat, not just
   ;; one or the other.
   {:claim :claim/kotobase-code-graph-cid-not-authority :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "src/kotobase/code_graph.cljc")
            (let [readme (slurp* "README.md")
                  code (slurp* "src/kotobase/code_graph.cljc")
                  readme-caveat? (has? readme #"CID possession is never authority")
                  code-caveat? (and (has? code #"never grants authority by itself")
                                     (has? code #"not authority"))]
              (cond
                (and readme-caveat? code-caveat?)
                {:score 1.0 :note "confirmed: README.md still states 'CID possession is never authority' verbatim, and src/kotobase/code_graph.cljc's own docstrings (put-execution-receipt!, pin-root!) still echo the same authority-scope limit -- disclosed at both the doc and source layer, not just prose."}
                readme-caveat?
                {:score 0.5 :note "README still carries the 'CID possession is never authority' caveat, but the matching docstring language ('never grants authority by itself' / 'not authority') no longer found verbatim in code_graph.cljc -- re-verify whether the code-level disclosure moved or was dropped."}
                :else
                {:score 0.3 :note "expected 'CID possession is never authority' text no longer found verbatim in README.md -- re-verify whether the caveat was reworded or silently dropped while the underlying authorization-scope limitation still applies."}))
            {:score 0.0 :note "src/kotobase/code_graph.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   ;; ---- 2026-07-27 weekly claim-discovery addition: security-adoption.edn (:adoption/version
   ;; 3) required-control-namespace manifest vs the two named sensitive-operations. ----------
   {:claim :claim/kotobase-security-adoption-manifest-v3 :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "security-adoption.edn")
            (let [manifest (slurp* "security-adoption.edn")
                  kotobase-src (if (exists? "src/kotobase/kotobase.cljc") (slurp* "src/kotobase/kotobase.cljc") "")
                  code-graph-src (if (exists? "src/kotobase/code_graph.cljc") (slurp* "src/kotobase/code_graph.cljc") "")
                  authorize-wired? (and (has? kotobase-src #"kotoba\.security\.abac")
                                        (has? kotobase-src #"abac/evaluate")
                                        (has? kotobase-src #"defn authorize-xrpc"))
                  revoke-wired? (and (has? code-graph-src #"kotoba\.security\.effect")
                                     (has? code-graph-src #"effect/guard!")
                                     (has? code-graph-src #"defn revoke-pin!"))]
              (cond
                (not (has? manifest #":adoption/version 3"))
                {:score 0.5 :note "security-adoption.edn no longer declares :adoption/version 3 -- manifest may have been revised; re-verify the sensitive-operations list before trusting this score."}
                (and authorize-wired? revoke-wired?)
                {:score 1.0 :note "confirmed: both :sensitive-operations the manifest names are genuinely wired -- kotobase.kotobase/authorize-xrpc requires kotoba.security.abac and calls abac/evaluate before permitting an operation, and kotobase.code-graph/revoke-pin! requires kotoba.security.effect and wraps its state transition in effect/guard! -- the manifest describes real enforcement, not aspirational policy."}
                :else
                {:score 0.3 :note (str "security-adoption.edn still declares authorize-xrpc->abac and revoke-pin!->effect as required, but source no longer confirms both wirings (authorize-xrpc wired=" authorize-wired? ", revoke-pin! wired=" revoke-wired? ") -- re-verify by hand, this is exactly the kind of drift periodic re-runs are meant to catch.")}))
            {:score 0.0 :note "security-adoption.edn no longer exists -- claim's cited source file is gone; re-verify."}))}

   ;; ---- 2026-08-03 weekly claim-discovery additions: ADR-stack-topology vs deps.edn drift,
   ;; a self-disclosed CI-red bug fixed at the tip commit, and the transparency-retention module.
   {:claim :claim/kotobase-adr-topology-deps-drift :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [adr (slurp* "docs/ADR-stack-topology.md")
                deps (slurp* "deps.edn")
                dep-names ["io.github.kotoba-lang/security" "io.github.kotoba-lang/abi"
                           "io.github.kotoba-lang/kotobase-storage" "io.github.kotoba-lang/kotobase-engine"]
                present (filter #(has? deps (re-pattern (str "(?i)" (str/replace % "/" "\\/")))) dep-names)]
            (cond
              (not (has? adr #"runtime deps are\s*\n?\s*`security` only"))
              {:score 0.4 :note "ADR-stack-topology.md no longer states 'runtime deps are security only' verbatim -- either the ADR was corrected to match deps.edn (a genuine fix; re-verify) or reworded; re-verify by hand."}
              (= (count present) 1)
              {:score 0.2 :note "ADR-stack-topology.md still claims 'runtime deps are security only', and deps.edn's top-level :deps now genuinely has only 1 of the 4 previously-observed deps -- the drift may have been resolved (deps trimmed back down); re-verify by hand and consider retiring this claim if confirmed."}
              :else
              {:score 0.3 :note (str "confirmed: ADR-stack-topology.md still states runtime deps are 'security only', but deps.edn's top-level :deps map has " (count present) "/4 of security/abi/kotobase-storage/kotobase-engine present -- the drift persists. Scored low on doc-code-drift because the ADR's own account of its dependency surface is stale relative to current deps.edn.")})))}

   {:claim :claim/kotobase-critical-path-cljc-ci-red-bug :axis :axis/production-readiness :layer :evidence-link
    :fn (fn []
          (cond
            (exists? "test/kotobase/critical_path_test.cljc")
            {:score 0.2 :note "test/kotobase/critical_path_test.cljc exists again (the .cljc extension is back) -- this is exactly the bug the docstring described (file-seq/slurp do not exist under cljs); re-verify whether this is a regression of the original CI-red bug."}
            (exists? "test/kotobase/critical_path_test.clj")
            (let [doc (slurp* "test/kotobase/critical_path_test.clj")]
              (if (has? doc #"took CI red from 2026-07-30")
                {:score 1.0 :note "confirmed: test/kotobase/critical_path_test.clj is still a .clj file (not .cljc), and its docstring still self-discloses the 2026-07-30 CI-red incident and the fix ('The extension was the bug.') -- the fix has held."}
                {:score 0.6 :note "critical_path_test.clj still has the correct .clj extension, but the self-disclosing docstring about the 2026-07-30 CI-red incident is no longer present verbatim -- may have been trimmed/reworded; re-verify by hand."}))
            :else
            {:score 0.0 :note "neither test/kotobase/critical_path_test.clj nor .cljc exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kotobase-transparency-retention-implemented :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "src/kotobase/transparency_log.clj")
            (let [src (slurp* "src/kotobase/transparency_log.clj")
                  checks-present? (and (has? src #":transparency/key-epoch")
                                       (has? src #":transparency/checkpoint-chain")
                                       (has? src #":transparency/witness-threshold")
                                       (has? src #":transparency/rollback"))
                  retention-present? (and (has? src #"defn retention-decision")
                                          (has? src #"legal-holds"))]
              (if (and checks-present? retention-present?)
                {:score 1.0 :note "confirmed: transparency_log.clj's verify-checkpoint still checks :transparency/key-epoch, :transparency/checkpoint-chain, :transparency/witness-threshold, and :transparency/rollback, and retention-decision still consults legal-holds -- the doc's description of fail-closed checkpoint verification and class-based retention with legal-hold override is genuinely wired, not aspirational."}
                {:score 0.4 :note (str "transparency_log.clj no longer has the expected checkpoint-verification checks (found=" checks-present? ") and/or retention-decision/legal-holds wiring (found=" retention-present? ") -- module may have been refactored; re-verify by hand whether the doc's claims still hold.")}))
            {:score 0.0 :note "src/kotobase/transparency_log.clj no longer exists -- claim's cited source file is gone; re-verify."}))}

   ;; ---- added 2026-08-10, weekly claim-discovery pass ----
   {:claim :claim/kotobase-cid-multi-page-scheduler-not-implemented :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (if (exists? "docs/adr/2608090000-rust-free-cid-canonical-route.md")
            (let [adr (slurp* "docs/adr/2608090000-rust-free-cid-canonical-route.md")
                  replay-exists? (exists? "kotoba/cid_external_transaction_replay.kotoba")
                  test-exists? (exists? "qualification/kotobase/cid_crypto_qualification_test.clj")]
              (cond
                (not (has? adr #"multi-page scheduler remains an open gate"))
                {:score 0.4 :note "ADR-2608090000 no longer states the global multi-page scheduler qualification remains an open gate verbatim -- either the scheduler landed (real progress -- re-verify) or the ADR text changed; re-verify by hand."}
                (not replay-exists?)
                {:score 0.3 :note "kotoba/cid_external_transaction_replay.kotoba no longer exists -- claim's cited source file is gone; re-verify."}
                (not test-exists?)
                {:score 0.3 :note "qualification/kotobase/cid_crypto_qualification_test.clj no longer exists -- claim's cited qualification test is gone; re-verify."}
                :else
                {:score 1.0 :note "confirmed: ADR-2608090000 still discloses the global multi-page scheduler as an open gate, and kotoba/cid_external_transaction_replay.kotoba plus its qualification test still exist implementing only the bounded single-page replay described -- the disclosed gap remains current."}))
            {:score 0.0 :note "docs/adr/2608090000-rust-free-cid-canonical-route.md no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kotobase-cid-multi-page-scheduler-not-implemented :axis :axis/doc-code-drift :layer :evidence-link
    :fn (fn []
          ;; Claim text asserts a repo-wide search across .kotoba/.clj/.cljc (not only
          ;; kotoba/*.kotoba). Walk the same extensions from the repo root so a host-side
          ;; .clj/.cljc scheduler would fail this check the same way a guest-side hit would.
          (let [source-files (list-source-files [".kotoba" ".clj" ".cljc"])
                scheduler-re #"(?i)multi-page.scheduler|page-dag.scheduling"
                scheduler-hits (filter (fn [f] (has? (slurp* f) scheduler-re)) source-files)]
            (cond
              (empty? source-files)
              {:score 0.0 :note "no .kotoba/.clj/.cljc source files found under the kotobase root -- claim's cited search scope is empty; re-verify checkout path."}
              (empty? scheduler-hits)
              {:score 1.0 :note (str "confirmed: repo-wide search across " (count source-files) " .kotoba/.clj/.cljc files found zero multi-page-scheduler / page-DAG-scheduling hits -- the ADR's disclosed gap (bounded single-page replay only, no global scheduler) matches the code, not an overclaim.")}
              :else
              {:score 0.5 :note (str "found a possible multi-page-scheduler reference in: " (str/join ", " scheduler-hits) " -- re-verify by hand whether this is a real implementation (would close the disclosed gap) or just a comment/TODO.")})))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
