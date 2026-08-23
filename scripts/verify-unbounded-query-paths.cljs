#!/usr/bin/env nbb
;; Report code that can issue a WHOLE-GRAPH read: a query path that reaches the
;; datom plane without an admission decision, and a hand-written index read that
;; binds no prefix.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-unbounded-query-paths.cljs
;;
;; ## Why
;;
;; `kotobase.server.datom-plan` maps `[s p o]` onto an index and a prefix, and
;; two of its rows bind no prefix at all -- `[_ _ o]` and `[_ _ _]` both become
;; `:eavt []`, the whole graph filtered in memory. `kotobase.server.admission`
;; refuses a query whose plan carries one. That refusal only holds where it is
;; CALLED, and a new surface reaching `source-for` directly is exactly the shape
;; that would quietly not call it.
;;
;; Measured 2026-08-23 for the cost this is about: `kotobase.query.bridge/
;; materialize`, the other whole-database read, is linear in the documents while
;; the query over the result stays near 1ms across a 64x range
;; (90-docs/kotobase-performance/2026-08-23-bridge-materialize-scaling.edn).
;;
;; ## Three findings, and none of them is "this file mentions materialize"
;;
;;   ungated-source     a file that calls `source-for` (prefetch the datom
;;                      plane) and never mentions `admission`. The read path
;;                      with no gate.
;;   unbounded-scan     a literal `:components []` handed to `hot-datoms` --
;;                      the full-plane read, written by hand, going around the
;;                      planner and therefore around the gate.
;;   raised-ceiling     a 3-arity `materialize` call whose ceiling argument is a
;;                      literal larger than `default-max-datoms`. Passing a
;;                      SMALLER one is the point of the arity and is not a
;;                      finding.
;;
;; Deliberately NOT a finding: calling `bridge/db-for` or `bridge/materialize`
;; at all. Those are bounded from the inside now, and reporting all six surface
;; repos every day would bury the three findings above -- the same reasoning
;; `verify-namespace-collisions` gives for splitting scaffolded duplication out
;; of its own report.
;;
;; Findings protocol: FINDING<TAB>sev<TAB>key<TAB>detail, plus a SCANNED line.

(require '[clojure.string :as str]
         '["fs" :as fs]
         '["path" :as path])

(def root (or (.-DETECTOR_ROOT js/process.env) "."))
(def orgs (path/join root "orgs"))

(defn- source-files
  "Every checked-out .clj/.cljc/.cljs under orgs/, skipping the places a copy
  of a file is not a call site: node_modules, build output, and .git."
  [dir]
  (letfn [(walk [d depth]
            (if (> depth 7)
              []
              (let [entries (try (js->clj (.readdirSync fs d #js {:withFileTypes true}))
                                 (catch :default _ []))]
                (mapcat
                 (fn [e]
                   (let [nm (.-name e) p (path/join d nm)]
                     (cond
                       (and (.isDirectory e)
                            (not (contains? #{"node_modules" ".git" "target" "out"
                                              "js" ".shadow-cljs" ".cpcache" "dist"} nm)))
                       (walk p (inc depth))

                       (and (.isFile e) (re-find #"\.clj[cs]?$" nm)) [p]
                       :else [])))
                 entries))))]
    (walk dir 0)))

(defn- rel [p] (str/replace p (str root "/") ""))

(defn- findings-for [p src]
  (let [test? (re-find #"/test/|_test\.clj" p)]
    (cond-> []
      ;; A prefetch of the datom plane with no admission decision anywhere in
      ;; the file. `admission` is matched by name rather than by call, because a
      ;; file that requires it and then does not use it is a different (and
      ;; louder) failure -- the compiler says so.
      ;; The REQUIRE, not the bare name. `source-for` is a common enough
      ;; function name that matching it alone reported htmldom, a bitcoin
      ;; node and a character creator on the first run -- none of which have
      ;; ever seen a datom. The defining namespace is excluded by the same
      ;; test: it requires nothing.
      (and (not test?)
           (re-find #"kotobase\.server\.pattern-source" src)
           (re-find #"/source-for" src)
           (not (re-find #"kotobase\.server\.admission" src)))
      (conj ["high" (str "ungated-source:" (rel p))
             "requires kotobase.server.pattern-source and calls source-for, without kotobase.server.admission -- a read path with no gate"])

      ;; `:components []` is the plan row that binds no prefix. Written by hand
      ;; it goes around datom-plan, and therefore around the gate.
      ;; The call SHAPE, `{:index … :components []}`, not the two keywords
      ;; anywhere in the file. `datom-plan`'s own docstring contains the plan
      ;; table, which is prose about this exact row, and the first version of
      ;; this rule reported it.
      (and (not test?)
           (re-find #":index\s+[^\s]+\s+:components\s*\[\s*\]" src)
           (re-find #"hot-datoms|cold-datoms" src))
      (conj ["high" (str "unbounded-scan:" (rel p))
             "hands hot-datoms a literal empty :components -- the whole-plane read, around the planner"])

      ;; A ceiling argument larger than the default. Smaller is the point of the
      ;; arity; larger is someone deciding the scan is fine after all.
      (and (not test?)
           (some (fn [[_ n]] (> (js/parseInt n 10) 200000))
                 (re-seq #"materialize[^\)\n]*?\s(\d{6,})\)" src)))
      (conj ["medium" (str "raised-ceiling:" (rel p))
             "passes materialize a ceiling above default-max-datoms (200000)"]))))

(defn -main []
  (if-not (try (.isDirectory (.statSync fs orgs)) (catch :default _ false))
    (do (println "Refusing to report a pass: no orgs/ directory at" orgs)
        (js/process.exit 3))
    (let [files (source-files orgs)
          results (mapcat (fn [p]
                            (let [src (try (str (.readFileSync fs p "utf8"))
                                           (catch :default _ nil))]
                              (when src (findings-for p src))))
                          files)]
      (when (zero? (count files))
        (println "Refusing to report a pass: scanned no files")
        (js/process.exit 3))
      (doseq [[sev k detail] (sort-by second results)]
        (println (str "FINDING\t" sev "\t" k "\t" detail)))
      (println (str "SCANNED\t" (count files)))
      (js/process.exit (if (seq results) 1 0)))))

(-main)
