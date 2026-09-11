;; Where the ontology's cost sits relative to the term that already dominated.
;;
;; ADR-2608252400 named this once already: the query was never what was slow.
;; `kotobase.query.bridge/materialize` rebuilds the whole index from the store
;; on every call -- O(database), not O(result) -- and ADR-2607299900 measured
;; the same shape from the block side.
;;
;; bench_inference_cost.cljs measures the ENGINE with the db already in hand,
;; so on its own it would invite the wrong conclusion: that a 100x slower
;; query is a 100x slower request. It is not, if materialize is the larger
;; term. This bench puts the two on one line so the fraction is visible.
;;
;;   nbb --classpath "orgs/kotoba-lang/org-w3-owl2/src:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src:orgs/kotoba-lang/ayatori/src:orgs/kotoba-lang/kotobase/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/bench_materialize_share.cljs

(ns bench-materialize-share
  (:require [owl.rules :as rules]
            [kotobase.query.bridge :as bridge]
            [kotobase.local :as local]
            [datalog.core :as dl]
            ["os" :as os]))

(def reps 5)
(def ^:private visible? (constantly true))

(defn- median [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(defn- timed [f]
  (f)
  (let [samples (mapv (fn [_] (let [t0 (js/performance.now) r (f)]
                                [(- (js/performance.now) t0) r]))
                      (range reps))]
    {:ms (median (map first samples)) :result (second (first samples))}))

(defn- klass [i] (str "C" i))

;; `materialize` mints an entity id of `:<collection>/<key>` per document, so a
;; document that refers to another BY KEY does not join to that document's
;; entity. Both shapes are built here, because the difference is the finding
;; rather than an implementation detail -- see the control row at the bottom.
(defn- class-ref [d] (keyword "classes" (klass d)))

(defn- store-of [n depth ref-fn]
  (local/local-store
   {:docs {"individuals" (into {} (map (fn [i]
                                         [(str "i" i) {:rdf/type (ref-fn 0)}])
                                       (range n)))
           "classes" (into {} (map (fn [d]
                                     [(klass d) {:rdfs/subClassOf (ref-fn (inc d))}])
                                   (range (dec depth))))}}))

(defn- fmt [x] (.toFixed x 2))

(println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg)))
              "  cpus " (count (os/cpus)) "  reps " reps))
(println)
(println (str "     n depth  datoms  rows-plain  rows-onto  materialize-ms"
              "  q-plain-ms  q-onto-ms  onto-share-of-request"))

(doseq [n [100 1000] depth [4 16]]
  (let [st (store-of n depth class-ref)
        colls ["individuals" "classes"]
        m (timed #(bridge/materialize st colls))
        db (:result m)
        top (class-ref (dec depth))
        plain (timed #(dl/q db {:find '[?i] :where [['?i :rdf/type (class-ref 0)]]} visible?))
        onto (timed #(dl/q db {:find '[?i]
                               :where (list (list 'owl-type '?i top))
                               :rules (rules/hierarchy-rules)} visible?))
        req-plain (+ (:ms m) (:ms plain))
        req-onto (+ (:ms m) (:ms onto))
        share (/ (- (:ms onto) (:ms plain)) req-onto)]
    (println (str (.padStart (str n) 6)
                  (.padStart (str depth) 6)
                  (.padStart (str (count (dl/q db {:find '[?e ?a ?v]
                                                   :where '[[?e ?a ?v]]} visible?))) 8)
                  (.padStart (str (count (:result plain))) 12)
                  (.padStart (str (count (:result onto))) 11)
                  (.padStart (fmt (:ms m)) 16)
                  (.padStart (fmt (:ms plain)) 12)
                  (.padStart (fmt (:ms onto)) 11)
                  (.padStart (str (fmt (* 100 share)) "%") 23)))))


;; The control, and the reason the rows columns above are not decoration.
(let [st (store-of 100 4 (fn [d] (klass d)))    ; class referred to BY KEY
      db (bridge/materialize st ["individuals" "classes"])
      rows (dl/q db {:find '[?c] :where (list (list 'owl-type :individuals/i0 '?c))
                     :rules (rules/hierarchy-rules)} visible?)]
  (println)
  (println (str "control -- the same graph with classes referred to by KEY rather"
                " than by entity id:"))
  (println (str "  owl-type of one individual returns " (count rows) " class(es): "
                (pr-str (sort (map (comp str first) rows)))))
  (println "  depth is 4, so a joined graph returns 4. This does not error and")
  (println "  does not return zero -- it returns the asserted class and stops,")
  (println "  which is a plausible answer and the wrong one."))

(println)
(println "onto-share = (q-onto - q-plain) / (materialize + q-onto). The share is")
(println "what a request actually pays for the ontology once the term that was")
(println "already dominant is on the same line.")
(println)
(println "The datoms column is counted by a wildcard query, so it is the number")
(println "the engine can see rather than the number the generator intended --")
(println "a store whose documents did not materialize would show up here.")
