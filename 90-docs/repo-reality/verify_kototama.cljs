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
                {:score 1.0 :note "confirmed: fleet_fence.cljc's own namespace docstring still says 'not Raft/Paxos' and 'Does NOT implement network consensus, leader election, or clock sync' -- the maturity.md table's 'not Raft' framing matches the actual implementation's self-description, not an external gloss."}
                {:score 0.3 :note "expected 'not Raft/Paxos' + 'Does NOT implement network consensus' language no longer found verbatim in fleet_fence.cljc -- either consensus was added (which would be a major change worth flagging) or docstring wording changed; re-verify."}))
            {:score 0.0 :note "src/kototama/fleet_fence.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/kototama-fleet-broker-partial :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (if (exists? "src/kototama/fleet.cljc")
            (let [fleet (slurp* "src/kototama/fleet.cljc")
                  landed? (has? fleet #"aiueos GRANT/DENY E2E through fleet-exec")
                  not-yet? (has? fleet #"full aiueos fleet broker")]
              (cond
                (and landed? not-yet?)
                {:score 1.0 :note "confirmed: fleet.cljc's r3-report still lists 'aiueos GRANT/DENY E2E through fleet-exec + tender' under :landed AND 'full aiueos fleet broker (all actor:host kinds as first-class policy)' under :not-yet -- the code's own status report distinguishes basic-grant-works from full-policy-not-done, matching the claim precisely."}
                (and landed? (not not-yet?))
                {:score 0.6 :note "'aiueos GRANT/DENY E2E' still landed, but 'full aiueos fleet broker' no longer appears in :not-yet -- possible the full broker was completed (great, update the claim) or the r3-report shape changed; re-verify by hand."}
                :else
                {:score 0.3 :note "expected landed/:not-yet markers for aiueos fleet broker status no longer found verbatim in fleet.cljc's r3-report -- function may have been restructured; re-verify."}))
            {:score 0.0 :note "src/kototama/fleet.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
