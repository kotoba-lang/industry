#!/usr/bin/env nbb
;; scripts/valueflows-algorithm-readiness.cljs — which of the seven algorithms
;; have ever been given REAL input, and what exactly the rest are waiting for.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/valueflows-algorithm-readiness.cljs \
;;     --data-root $HOME/github/com-junkawasaki
;;   ... --check
;;
;; ADR-2608153000. `ws-valueflo-algorithms` has 79 passing tests and every one of
;; them runs on a fixture. That is not a criticism of the tests — it is the reason
;; this file exists. "The algorithms are implemented" and "the algorithms compute
;; anything about this workspace" are different claims, and without somewhere to
;; write the second one down the first one gets cited for it.
;;
;; ## Fixture-ness is read, not judged
;;
;; The distinction that matters is real input versus fixture input, and it is NOT
;; a matter of opinion here: cloud-itonami/credits' corpus declares
;; `#:corpus{:synthetic? true}` in the file, above a paragraph saying "IT IS NOT A
;; RECORD OF ANYONE'S ECONOMIC ACTIVITY. Do not cite balances or amounts here as
;; measurements of anything." So this generator READS that flag. If a corpus stops
;; declaring itself synthetic, the answer here changes, and if one starts, it
;; changes back. Nothing is asserted about a file that does not speak.
;;
;; ## What is measured versus what is described
;;
;; Measured from committed files: how many recipes exist, whether any process
;; carries a duration, whether any recipe's input is another recipe's output
;; (multi-level explosion), how many resources have a value, whether the event
;; corpora self-declare synthetic. `:needs` is prose describing the input shape —
;; documentation, not a measurement, and labelled as such.
;;
;; Exit codes: 0 written/identical · 1 STALE · 2 COULD NOT ANSWER.

(ns valueflows-algorithm-readiness
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]))

(def out-path "90-docs/valueflows/algorithm-readiness.datoms.edn")
(def dataset "valueflows-algorithm-readiness")

(def recipe-projection "90-docs/valueflows/uchiwake-recipes-vf.datoms.edn")
(def cost-projection "90-docs/valueflows/uchiwake-costs-vf.datoms.edn")
(def engi-projection "orgs/cloud-itonami/credits/resources/engi/example-journal.valueflows.edn")
(def engi-corpus "orgs/cloud-itonami/credits/resources/engi/example-journal.edn")

;; Measured 2026-08-15. A run that finds fewer has lost an input and must not
;; write a smaller file that reads as a smaller plane.
(def floor {:algorithms 7 :recipes 3})

(defn- die [code msg] (println msg) (js/process.exit code))
(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- exists? [p] (fs/existsSync p))

(defn- args []
  (let [a (vec (drop 2 (js->clj js/process.argv)))]
    {:data-root (or (second (drop-while #(not= "--data-root" %) a)) (.cwd js/process))
     :check? (boolean (some #{"--check"} a))}))

(defn- read-edn [p]
  (when (exists? p)
    (try (edn/read-string (slurp* p))
         (catch :default _ ::unreadable))))

;; ── measurements ──────────────────────────────────────────────────────────

(defn- measure-recipes []
  (let [v (read-edn recipe-projection)]
    (when (or (nil? v) (= ::unreadable v))
      (die 2 (str "CANNOT ANSWER: " recipe-projection " is absent or unreadable."
                  " Without it nothing can be said about which algorithms have"
                  " input, and saying `none` would blame the algorithms for a"
                  " missing file.")))
    (let [rs (filterv :vf.recipe/process-id v)
          outputs (set (map :vf.recipe/output-resource rs))
          inputs (set (mapcat #(map :resource-conforms-to (:vf.recipe/inputs %)) rs))]
      {:recipes (count rs)
       :input-flows (reduce + 0 (map :vf.recipe/input-count rs))
       ;; every process here is a single step, so no process carries a duration
       :with-duration (count (filter :vf.recipe/duration rs))
       ;; a multi-level explosion needs an input that something else produces
       :multi-level-links (count (filter outputs inputs))
       :leaf-resources (count (remove outputs inputs))})))

(defn- measure-values []
  (let [v (read-edn cost-projection)]
    (if (or (nil? v) (= ::unreadable v))
      {:present? false}
      (let [cov (last v)]
        {:present? true
         :priced-rows (:vf.coverage/priced-rows cov)
         :unpriced-rows (:vf.coverage/unpriced-rows cov)
         :price-basis (:vf.coverage/price-basis cov)
         :any-complete? (:vf.coverage/any-complete? cov)}))))

(defn- measure-events
  "Reads the SELF-DECLARATION, not the contents. A corpus that says
   `:corpus/synthetic? true` has settled the question about itself; one that says
   nothing is reported as undeclared rather than assumed real."
  [root]
  (let [corpus-f (path/join root engi-corpus)
        proj-f (path/join root engi-projection)
        corpus (read-edn corpus-f)
        proj (read-edn proj-f)]
    {:corpus-present? (exists? corpus-f)
     :projection-present? (exists? proj-f)
     :synthetic-declared (cond
                           (not (map? corpus)) :unreadable
                           (contains? corpus :corpus/synthetic?) (:corpus/synthetic? corpus)
                           :else :undeclared)
     :projected-events (cond
                         (= ::unreadable proj) nil
                         (sequential? proj) (count proj)
                         (map? proj) (count (:events proj))
                         :else nil)}))

;; ── the seven ─────────────────────────────────────────────────────────────

(defn- algorithms [{:keys [recipes multi-level-links with-duration leaf-resources
                           input-flows]}
                   values events]
  (let [real-recipes? (pos? recipes)
        real-values? (and (:present? values) (pos? (or (:priced-rows values) 0)))
        ;; the corpus says so itself
        events-real? (and (:corpus-present? events)
                          (false? (:synthetic-declared events)))]
    [{:id :flow-graph
      :needs "a recipe: processes with input and output flows"
      :real-input? real-recipes?
      :evidence (str recipes " recipes, " input-flows " input flows, from "
                     recipe-projection)}

     {:id :dependent-demand
      :needs "a recipe plus a wanted output quantity and a due period"
      :real-input? real-recipes?
      :evidence (str recipes " recipes with real quantities")
      ;; runs, but one level deep, and that is worth saying plainly
      :qualified-by
      (str "RUNS BUT SHALLOW. " multi-level-links " of the input resources are"
           " produced by another process in this data, so all " leaf-resources
           " resolve immediately to independent (purchased) demand and the"
           " explosion is one level deep. Multi-level explosion — the thing this"
           " algorithm is for — has not been exercised on real input. It needs a"
           " recipe whose input is another recipe's output.")}

     {:id :critical-path
      :needs "process durations, and dependencies between processes"
      :real-input? false
      :evidence (str with-duration " of " recipes
                     " processes carry a duration, and " multi-level-links
                     " process-to-process dependencies exist")
      :blocked-by
      (str "NO REAL DURATION DATA EXISTS IN THIS WORKSPACE. A schema for it does"
           " — kotoba-lang/plm's Bill of Process has :plm.op/std-time-hr and"
           " :plm.op/setup-time-hr per operation on a :plm.wc work center — and"
           " the only values anywhere are in that repository's own test fixture."
           " uchiwake, the one source with real recipe quantities, records net"
           " content and ingredient percentages and no times at all. Durations"
           " could be invented in a minute and would be indistinguishable from"
           " measured ones in the output, which is exactly why they are not."
           " Unblocked by either a populated plm routing for a real item, or a"
           " source that publishes process times.")}

     {:id :value-rollup
      :needs "a value per resource specification, denominated per a unit"
      :real-input? real-values?
      :evidence (str (:priced-rows values) " priced input rows against "
                     (:unpriced-rows values) " unpriced, basis "
                     (:price-basis values))
      :qualified-by
      (str "PARTIAL AND LOWER-BOUND. Every total is understated twice: unpriced"
           " ingredients contribute zero, and the prices are world commodity"
           " contracts rather than the processed ingredients the recipes name."
           " :complete? is false on every recipe.")}

     {:id :value-equation
      :needs "contributions to distribute over, and a distribution rule"
      :real-input? false
      :blocked-by
      (str "No real contribution record exists. This needs someone's actual"
           " claim on a distribution — hours worked, resources contributed — and"
           " the only such records here are in the synthetic credits corpus."
           " Inventing contributions would be inventing people's claims on"
           " money, which is the worst thing on this list to fabricate.")}

     {:id :track-trace
      :needs "economic events over identified resources, forming a chain"
      :real-input? events-real?
      :evidence (str "credits corpus present " (:corpus-present? events)
                     ", self-declared synthetic " (:synthetic-declared events)
                     ", projected events " (:projected-events events))
      :blocked-by
      (when-not events-real?
        (str "The only projected Valueflows event stream comes from"
             " cloud-itonami/credits, and that corpus declares"
             " :corpus/synthetic? " (pr-str (:synthetic-declared events))
             " in the file, above the sentence \"IT IS NOT A RECORD OF ANYONE'S"
             " ECONOMIC ACTIVITY\". Tracing it produces a correct trace of a"
             " fixture. Unblocked by a real ledger — the projection code is"
             " already written and tested, so this is a data question and not a"
             " code question."))}

     {:id :cash-flow
      :needs "events carrying value and a period"
      :real-input? events-real?
      :evidence (str "same source as track-trace")
      :blocked-by
      (when-not events-real?
        "Same synthetic corpus as track-trace, and blocked on the same thing.")}]))

;; ── build ─────────────────────────────────────────────────────────────────

(let [{:keys [data-root check?]} (args)
      recipes (measure-recipes)
      values (measure-values)
      events (measure-events data-root)
      algos (algorithms recipes values events)]
  (when (< (count algos) (:algorithms floor))
    (die 2 (str "CANNOT ANSWER: described " (count algos) " algorithms, floor "
                (:algorithms floor) ".")))
  (when (< (:recipes recipes) (:recipes floor))
    (die 2 (str "CANNOT ANSWER: read " (:recipes recipes) " recipes, floor "
                (:recipes floor) ". An input is missing and a smaller plane"
                " would look like a truthful smaller answer.")))
  (let [ents (vec (map-indexed
                   (fn [i a]
                     (into {:db/id (- (inc i)) :source/dataset dataset
                            :vf.algo/id (name (:id a))}
                           (keep (fn [[k v]]
                                   (when (some? v)
                                     [(keyword "vf.algo" (name k)) v]))
                                 (dissoc a :id))))
                   algos))
        with-real (filter :vf.algo/real-input? ents)
        cov {:db/id (- (inc (count ents)))
             :source/dataset dataset
             :vf.coverage/algorithms (count ents)
             :vf.coverage/with-real-input (count with-real)
             :vf.coverage/with-real-input-ids (mapv :vf.algo/id with-real)
             :vf.coverage/blocked (count (remove :vf.algo/real-input? ents))
             :vf.coverage/qualified (count (filter :vf.algo/qualified-by ents))
             :vf.coverage/recipes (:recipes recipes)
             :vf.coverage/multi-level-links (:multi-level-links recipes)
             :vf.coverage/processes-with-duration (:with-duration recipes)
             :vf.coverage/events-self-declared-synthetic (:synthetic-declared events)
             :vf.coverage/complete? false
             :vf.coverage/note
             (str "Of " (count ents) " algorithms, " (count with-real)
                  " have real input and " (count (remove :vf.algo/real-input? ents))
                  " have never been given any. All 79 tests in"
                  " ws-valueflo-algorithms pass, and all 79 run on fixtures;"
                  " those are different claims and this file exists so the first"
                  " cannot be cited for the second. Two of the ones that do run"
                  " carry a :vf.algo/qualified-by that must be read with them —"
                  " an explosion one level deep has not exercised explosion, and"
                  " a rollup whose every total is a lower bound has not priced"
                  " anything completely. Fixture-ness is not judged here: it is"
                  " read out of the corpus's own :corpus/synthetic? declaration.")}
        content (str ";; GENERATED by scripts/valueflows-algorithm-readiness.cljs."
                     " DO NOT EDIT BY HAND.\n"
                     ";; ADR-2608153000. Regenerate:\n"
                     ";;   nbb --classpath \".:scripts/nbb_compat\" \\\n"
                     ";;     scripts/valueflows-algorithm-readiness.cljs"
                     " --data-root $HOME/github/com-junkawasaki\n"
                     ";;\n"
                     ";; Which of the seven algorithms have real input, and what the rest are\n"
                     ";; waiting for. :vf.algo/needs is prose; everything else is measured from\n"
                     ";; committed files. The LAST entity is coverage.\n"
                     (with-out-str (pprint/pprint (conj ents cov))))]
    (println (str "algorithms " (count ents)
                  " · real input " (count with-real) " " (pr-str (mapv :vf.algo/id with-real))
                  " · blocked " (count (remove :vf.algo/real-input? ents))
                  " · qualified " (:vf.coverage/qualified cov)
                  " · multi-level links " (:multi-level-links recipes)
                  " · durations " (:with-duration recipes)))
    (if check?
      (let [have (when (exists? out-path) (slurp* out-path))]
        (cond
          (nil? have) (die 1 (str "STALE: " out-path " is absent"))
          (not= have content) (die 1 (str "STALE: " out-path " disagrees with the workspace"))
          :else (println (str "OK: " out-path " matches"))))
      (do (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
          (fs/writeFileSync out-path content)
          (println (str "wrote " out-path " (" (count content) " bytes)"))))))
