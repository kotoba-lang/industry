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
            {:score 0.0 :note "src/kototama/fleet.cljc no longer exists -- re-verify."}))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
