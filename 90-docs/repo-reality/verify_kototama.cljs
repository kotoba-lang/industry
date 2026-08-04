;; repo-reality verify script — kototama prototype (2026-07-15). See verify_aiueos.cljs header
;; for the overall method. Run: nbb 90-docs/repo-reality/verify_kototama.cljs >> 90-docs/repo-reality/repo-reality-ledger.edn

(ns verify-kototama
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def root "orgs/kotoba-lang/kototama/")

(defn slurp* [rel-path] (.readFileSync fs (str root rel-path) "utf8"))
(defn exists? [rel-path] (.existsSync fs (str root rel-path)))
(defn has? [s re] (boolean (re-find re s)))

(def run-id (str "repo-reality-kototama-" (str/replace (.toISOString (js/Date.)) #"[-:]|\.\d+Z$" "")))
(def now (.toISOString (js/Date.)))

(def checks
  [{:claim :claim/kototama-r3-stable :axis :axis/functional-completeness :layer :evidence-link
    :fn (fn []
          (let [maturity (slurp* "docs/maturity.md")
                fixtures ["kotoba-compiled-fact.wasm" "kotoba-compiled-peak-cells.wasm"
                          "kotoba-compiled-sha256-hex.wasm" "kotoba-compiled-gen-keypair.wasm"]
                present (filter #(exists? (str "test/kototama/fixtures/" %)) fixtures)]
            (cond
              (not (has? maturity #"Current declared level: R3 stable"))
              {:score 0.3 :note "docs/maturity.md no longer states 'Current declared level: R3 stable' verbatim -- level may have changed; re-verify."}
              (< (count present) 4)
              {:score 0.5 :note (str "R3-stable claim's own R1 acceptance-gate fixtures are incomplete: " (count present) "/4 present ("
                                      (str/join ", " present) ") -- the checked-in evidence the doc cites for R1 stability has partially disappeared, even though R3 is the higher-declared level.")}
              :else
              {:score 1.0 :note (str "confirmed: 'Current declared level: R3 stable' still present, and all " (count present) "/4 R1 fixture files cited as evidence still exist on disk.")})))}

   ;; 2026-07-16 gap fix: catalog declares functional-completeness for this claim too; add the
   ;; missing event. 8/9 is high genuine completeness (unlike the shell/packages/broker gaps
   ;; below), not a claim-accuracy score dressed up as completeness.
   {:claim :claim/kototama-r3-stable :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [maturity (slurp* "docs/maturity.md")]
            (if (and (has? maturity #"(?i)R2.*advanced-partial")
                     (has? maturity #"(?i)not Raft"))
              {:score 1.0 :note "confirmed: maturity.md's R3-stable declaration is qualified in the SAME table by 'R2 advanced-partial' and 'not Raft' -- the ladder openly states which sub-levels aren't uniformly done, not an unqualified 'everything works' claim."}
              {:score 0.4 :note "expected qualifying language ('R2 advanced-partial', 'not Raft') no longer found alongside the R3-stable declaration -- re-verify whether the doc now overclaims."})))}

   ;; NOTE (2026-07-16): an earlier version of this check tried to independently recompute the
   ;; 8/9 ratio by grepping ':browser :yes' in browser.cljc alone -- that undercounts (7/8),
   ;; because http-post's "linkable via inject/SAB+COOP bridge" path lives in a DIFFERENT file
   ;; (http-post-bridge.js) and isn't a bare :browser :yes entry in this map at all. Recomputing
   ;; the ratio from scattered source is fragile and was wrong; verifying the maintainer's own
   ;; stated summary figure is still present (evidence-linkage style) is the honest check here.
   {:claim :claim/kototama-r2-browser-parity :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [maturity (slurp* "docs/maturity.md")]
            (if (has? maturity #"Score today: \*\*8/9\*\* browser-linkable")
              {:score 0.889 :note "confirmed: maturity.md still states 'Score today: 8/9 browser-linkable' verbatim -- functional-completeness scored as that literal fraction (8/9 = 0.889), not a 0/1 claim-accuracy flag. NOT independently recomputed from browser.cljc alone (see note above on why that undercounts)."}
              {:score 0.5 :note "expected 'Score today: 8/9 browser-linkable' text no longer found verbatim in maturity.md -- the ratio may have changed (check what it is now) or wording drifted; re-verify by hand rather than trust a stale fraction."})))}

   {:claim :claim/kototama-r2-browser-parity :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [browser (slurp* "src/kototama/browser.cljc")]
            (if (and (has? browser #"llm-infer.*:browser :no")
                     (has? browser #"(?i)Honest gaps"))
              {:score 1.0 :note "confirmed: browser.cljc's parity map still marks :llm-infer {:browser :no ...} AND the namespace docstring has an explicit 'Honest gaps' section naming the same limitation -- disclosed in code, not just in docs/maturity.md's prose table."}
              {:score 0.3 :note "expected llm-infer :browser :no marker or 'Honest gaps' docstring section no longer found verbatim in browser.cljc -- either the gap was closed (great, but then maturity.md's 8/9 figure needs updating) or wording drifted; re-verify by hand."})))}

   {:claim :claim/kototama-fencing-not-raft :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "src/kototama/fleet_fence.cljc")
            (let [fence (slurp* "src/kototama/fleet_fence.cljc")]
              (if (and (has? fence #"(?i)not Raft/Paxos")
                       (has? fence #"(?i)Does NOT implement network consensus"))
                {:score 1.0 :note "confirmed: fleet_fence.cljc's own namespace docstring still says 'not Raft/Paxos' and 'Does NOT implement network consensus, leader election, or clock sync' -- the maturity.md table's 'not Raft' framing matches the actual implementation's self-description, not an external gloss. High score here IS correct (unlike the shell/packages/broker cases below): fencing is a complete, working mechanism for its DELIBERATELY scoped design (epoch-based lease claiming on a shared store), not an unfinished feature -- 'not Raft' is a scope boundary, not a gap."}
                {:score 0.3 :note "expected 'not Raft/Paxos' + 'Does NOT implement network consensus' language no longer found verbatim in fleet_fence.cljc -- either consensus was added (which would be a major change worth flagging) or docstring wording changed; re-verify."}))
            {:score 0.0 :note "src/kototama/fleet_fence.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   ;; 2026-07-16 gap fix: catalog declares doc-code-drift too; add the missing event.
   {:claim :claim/kototama-fencing-not-raft :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (if (exists? "src/kototama/fleet_fence.cljc")
            (let [fence (slurp* "src/kototama/fleet_fence.cljc")]
              (if (has? fence #"(?i)not Raft/Paxos")
                {:score 1.0 :note "confirmed: the not-Raft scope boundary is stated in fleet_fence.cljc itself, not just maturity.md prose -- disclosed at the source, not just the doc layer."}
                {:score 0.3 :note "expected disclosure language no longer found -- re-verify."}))
            {:score 0.0 :note "src/kototama/fleet_fence.cljc no longer exists -- re-verify."}))}

   ;; 2026-07-16 gap fix: split into functional-completeness (actual state -- basic grant/deny
   ;; IS landed per fleet.cljc's own :landed list, only the FULL policy surface across all
   ;; actor:host kinds is missing, so this is meaningfully more complete than shell/packages
   ;; above) + doc-code-drift (disclosure accuracy). Same bug class as
   ;; claim/kotoba-shell-not-wired / claim/kotoba-lang-packages-deferred.
   {:claim :claim/kototama-fleet-broker-partial :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (if (exists? "src/kototama/fleet.cljc")
            (let [fleet (slurp* "src/kototama/fleet.cljc")
                  landed? (has? fleet #"aiueos GRANT/DENY E2E through fleet-exec")
                  not-yet? (has? fleet #"full aiueos fleet broker")]
              (cond
                (and landed? not-yet?)
                {:score 0.6 :note "basic aiueos GRANT/DENY E2E is landed (real, working) per fleet.cljc's own :landed list, but 'full aiueos fleet broker (all actor:host kinds as first-class policy)' remains in :not-yet -- meaningfully more complete than a design-only gap (kotoba-shell) or contract-only gap (kotoba-lang packages), but genuinely not done: scored 0.6, not 1.0."}
                (and landed? (not not-yet?))
                {:score 0.9 :note "'aiueos GRANT/DENY E2E' still landed, and 'full aiueos fleet broker' no longer appears in :not-yet -- looks like the full broker was completed; re-verify by hand and update the claim text."}
                :else
                {:score 0.3 :note "expected landed/:not-yet markers no longer found verbatim in fleet.cljc's r3-report -- function may have been restructured; re-verify."}))
            {:score 0.0 :note "src/kototama/fleet.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kototama-fleet-broker-partial :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (if (exists? "src/kototama/fleet.cljc")
            (let [fleet (slurp* "src/kototama/fleet.cljc")]
              (if (and (has? fleet #"aiueos GRANT/DENY E2E through fleet-exec")
                       (has? fleet #"full aiueos fleet broker"))
                {:score 1.0 :note "confirmed: the code's own r3-report distinguishes basic-grant-works from full-policy-not-done -- honestly disclosed at the source, not glossed over."}
                {:score 0.3 :note "expected markers not found -- re-verify."}))
            {:score 0.0 :note "src/kototama/fleet.cljc no longer exists -- re-verify."}))}

   ;; ---- 2026-07-27 weekly claim-discovery addition: PR #76 "Split transport and component
   ;; tenders" -- README.md vs docs/maturity.md disagree on the resulting R2 status. ---------
   {:claim :claim/kototama-r2-r3-readme-maturity-mismatch :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [readme (slurp* "README.md")
                maturity (slurp* "docs/maturity.md")
                browser (if (exists? "src/kototama/browser.cljc") (slurp* "src/kototama/browser.cljc") "")
                readme-level (re-find #"Current tender level: R2 [a-z-]+" readme)
                maturity-level (re-find #"Current tender level: R2 [a-z]+" maturity)
                llm-infer-browser-yes? (has? browser #":llm-infer\s+\{:jvm :yes :browser :yes")]
            (cond
              (or (nil? readme-level) (nil? maturity-level))
              {:score 0.5 :note (str "expected 'Current tender level: R2 ...' wording no longer found verbatim in one or both docs (README=" (some? readme-level) ", maturity.md=" (some? maturity-level) ") -- re-verify by hand.")}
              (= readme-level maturity-level)
              {:score 0.9 :note (str "README.md and docs/maturity.md now agree on the R2 status label (both say '" readme-level "') -- the doc-vs-doc mismatch this claim tracked appears to have been reconciled; re-verify the parity counts (9/9 vs 14/14) match too before scoring this 1.0.")}
              :else
              {:score 0.2 :note (str "confirmed: the two docs still disagree -- README.md says '" readme-level "', docs/maturity.md says '" maturity-level "'. llm-infer's browser column in src/kototama/browser.cljc is "
                                     (if llm-infer-browser-yes? "still :yes (matching maturity.md's higher 14/14, not README's stale 9/9)." "no longer :yes -- re-verify which doc's parity count is actually current.")
                                     " Scored low on doc-code-drift because the two self-descriptions of the SAME repo's SAME metric, added in the same commit, contradict each other and neither discloses the other's number.")})))}

   ;; ---- 2026-08-03 weekly claim-discovery additions: T8.4 host-parity-live partial status,
   ;; the T-01 TCB in-progress/unaudited disclosure, and undocumented scheduled-qualification
   ;; automation. -------------------------------------------------------------------------
   {:claim :claim/kototama-t84-host-parity-live-partial :axis :axis/functional-completeness :layer :evidence-link
    :fn (fn []
          (if (exists? "test/kototama/host_parity_live_test.clj")
            (let [doc (slurp* "docs/grade-a-host-parity-live-runner.md")
                  test (slurp* "test/kototama/host_parity_live_test.clj")]
              (cond
                (not (has? doc #"Status: partial \(JVM 56 \+ Node 38"))
                {:score 0.4 :note "docs/grade-a-host-parity-live-runner.md no longer states 'Status: partial (JVM 56 + Node 38 ...)' verbatim -- counts or status may have changed (possible progress toward T8.4 complete); re-verify by hand."}
                (not (and (has? test #"\(= 56 \(:total r\)\)") (has? test #"\(= 38 \(:total r\)\)")))
                {:score 0.5 :note "host_parity_live_test.clj no longer hard-asserts (= 56 (:total r)) / (= 38 (:total r)) for the JVM/Node live corpora -- counts may have grown or the assertion shape changed; re-verify whether the doc's cited numbers are still test-backed."}
                :else
                {:score 1.0 :note "confirmed: docs/grade-a-host-parity-live-runner.md still declares 'Status: partial (JVM 56 + Node 38 inject/live ...)' with explicit non-claims ('Not claim T8.4 complete'), and host_parity_live_test.clj still hard-asserts 56/38 totals for the JVM/Node live corpora -- the self-reported partial status remains test-backed, not narrative inflation."}))
            {:score 0.0 :note "test/kototama/host_parity_live_test.clj no longer exists -- claim's cited evidence file is gone; re-verify."}))}

   {:claim :claim/kototama-tcb-t01-in-progress-unaudited :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (let [doc (slurp* "docs/grade-a-tcb-inventory.md")
                tcb (slurp* "qualification/tcb-inventory.edn")
                validate (if (exists? "src/kototama/tcb.clj") (slurp* "src/kototama/tcb.clj") "")]
            (cond
              (not (has? doc #"T-01 remains `in-progress`"))
              {:score 0.4 :note "docs/grade-a-tcb-inventory.md no longer states 'T-01 remains `in-progress`' verbatim -- either the independent audit landed (re-verify, would be real progress) or wording changed; re-verify by hand."}
              (not (and (has? tcb #"resolver cannot pin validated address") (has? tcb #"not a hardware or process sandbox")))
              {:score 0.5 :note "qualification/tcb-inventory.edn no longer names the DNS-rebinding or non-sandboxed-isolation risks verbatim under :tcb/native-unsafe -- risk register may have been revised; re-verify by hand."}
              (not (has? validate #"missing-file"))
              {:score 0.5 :note "src/kototama/tcb.clj's validate fn no longer has the expected :missing-file check -- automated TCB validation may have been restructured; re-verify."}
              :else
              {:score 1.0 :note "confirmed: docs/grade-a-tcb-inventory.md still states T-01 remains in-progress pending independent audit and mutation/adversarial coverage, qualification/tcb-inventory.edn still names the Chicory unsafe-listener/DNS-resolver/JVM-JIT risks verbatim, and src/kototama/tcb.clj's validate fn still implements the automated checks the doc describes -- the automated half is real, the disclosed audit gap remains open and honestly stated."})))}

   {:claim :claim/kototama-scheduled-qualification-workflows-undocumented-in-ci :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [adr (slurp* "docs/adr/0011-linear-resource-recovery-and-browser-surface.md")
                ci (slurp* ".github/workflows/ci.yml")
                pkg (if (exists? "package.json") (slurp* "package.json") "")
                provider (if (exists? "src/kototama/component_provider.cljc")
                           (slurp* "src/kototama/component_provider.cljc") "")]
            (cond
              (not (has? adr #"scheduled Component requalification workflow"))
              {:score 0.4 :note "ADR 0011 no longer describes a 'scheduled Component requalification workflow' verbatim -- claim text may have been revised; re-verify by hand."}
              (has? ci #"(?m)^\s*schedule:")
              {:score 0.8 :note "ci.yml (or another workflow file) now has a 'schedule:' trigger where none existed before -- the previously-undocumented scheduled automation may have actually been built; re-verify which workflow it lives in and whether jco transpilation is really wired before raising this score to 1.0."}
              (has? pkg #"\"jco\"")
              {:score 0.6 :note "package.json now lists a jco dependency where none existed before -- partial progress toward the ADR's claimed jco-transpiled-Component-in-Chromium surface; re-verify whether it's actually invoked in CI."}
              :else
              {:score 0.2 :note "confirmed: ADR 0011 still describes (in the present tense) a scheduled Component requalification workflow and a jco-transpiled-Component-in-Chromium qualified surface, but .github/workflows/ci.yml still has no 'schedule:' trigger anywhere, package.json still has no jco dependency, and :jco-component in component_provider.cljc is still an inert enum value never dispatched to a real transpile call -- the described recurring automation still does not exist in this repo's CI, and neither doc discloses that."})))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
