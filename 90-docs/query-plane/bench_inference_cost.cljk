;; What does the ontology COST, and when should it be materialized instead?
;;
;; ADR-2609109700 D3 says an ontology enters an execution by one of two doors:
;; as `:rules` in the query value, or as derived datoms already in the graph.
;; It does not say which to use. `kotobase.server.admission` admits
;; `hierarchy-rules` because its predicates are literal -- and admission's own
;; docstring is explicit that a bound predicate says the read is a RANGE, not
;; that the range is small. So "admitted" is not "cheap", and nothing had
;; measured the difference.
;;
;; This bench measures the two doors against each other on the same graph and
;; reports the crossover: how many times a query has to be asked before
;; materializing the closure pays for itself.
;;
;; ## What is measured, and what makes each number honest
;;
;;   rows            the answer size. Both doors MUST return the same rows --
;;                   printed as an equality check, because a door that is fast
;;                   because it answered less is the failure this bench exists
;;                   to catch.
;;   datoms          asserted vs closed. Load-independent, so it is the number
;;                   to trust when the machine is busy.
;;   ms              median of REPS runs, interleaved variant by variant so a
;;                   load spike lands on both doors rather than on one.
;;   load            printed with the results, per the workspace rule that a
;;                   timing without the load it was taken under is not a
;;                   measurement.
;;
;; Run from the superproject root:
;;
;;   nbb --classpath "orgs/kotoba-lang/org-w3-owl2/src:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src" 90-docs/query-plane/bench_inference_cost.cljs

(ns bench-inference-cost
  (:require [owl.rules :as rules]
            [datalog.index :as idx]
            [datalog.core :as dl]
            ["os" :as os]))

(def ^:private ref? (constantly false))
(def ^:private visible? (constantly true))
(def reps 5)

(defn- klass [i] (str "C" i))
(defn- individual [i] (str "i" i))

(defn- asserted-triples
  "n individuals, all of the narrowest class, and a chain of `depth` classes."
  [n depth]
  (into (mapv (fn [i] [(individual i) :rdf/type (klass 0)]) (range n))
        (mapv (fn [d] [(klass d) :rdfs/subClassOf (klass (inc d))]) (range (dec depth)))))

(defn- closed-triples
  "The same graph with the rdfs9/rdfs11 closure already written down: every
  individual typed at every level. This is door (b)."
  [n depth]
  (into (asserted-triples n depth)
        (for [i (range n) d (range 1 depth)]
          [(individual i) :rdf/type (klass d)])))

(defn- db-of [triples]
  (reduce (fn [db [s p o]] (idx/assert-quad db {:s s :p p :o o} ref?))
          (idx/empty-db) triples))

(defn- median [xs]
  (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(defn- timed [f]
  (f)                                   ; warm
  (let [samples (mapv (fn [_]
                        (let [t0 (js/performance.now)
                              r (f)
                              t1 (js/performance.now)]
                          [(- t1 t0) r]))
                      (range reps))]
    {:ms (median (map first samples))
     :result (second (first samples))}))

(defn- row [n depth]
  (let [top (klass (dec depth))
        asserted (asserted-triples n depth)
        closed (closed-triples n depth)
        db-a (db-of asserted)
        db-c (db-of closed)
        hierarchy (rules/hierarchy-rules)
        q-virtual {:find '[?i] :where (list (list 'owl-type '?i top))
                   :rules hierarchy}
        q-material {:find '[?i] :where [['?i :rdf/type top]]}
        virtual (timed #(dl/q db-a q-virtual visible?))
        material (timed #(dl/q db-c q-material visible?))
        ;; door (b) is not free: someone has to derive and write the closure.
        ;; Charged here as one full build of the closed index -- an
        ;; OVERSTATEMENT of its marginal cost, kept deliberately, so that a
        ;; crossover below 1 is a conclusion the charge cannot have flattered.
        build (timed #(db-of closed))
        ;; The structural numbers. Load cannot move these, so when the machine
        ;; is busy these are what the timings have to be explained by.
        subclass-pairs (count (dl/q db-a {:find '[?a ?b]
                                          :where '[(owl-subclass ?a ?b)]
                                          :rules hierarchy} visible?))
        type-pairs (count (dl/q db-a {:find '[?i ?c]
                                      :where '[(owl-type ?i ?c)]
                                      :rules hierarchy} visible?))
        ;; Maintenance, the argument that runs the other way: one new
        ;; individual is one quad against the asserted graph and `depth` quads
        ;; against the closed one.
        add-asserted (timed #(idx/assert-quad db-a {:s "iNEW" :p :rdf/type :o (klass 0)} ref?))
        add-closed (timed #(reduce (fn [db d]
                                     (idx/assert-quad db {:s "iNEW" :p :rdf/type :o (klass d)} ref?))
                                   db-c (range depth)))
        vr (count (:result virtual))
        mr (count (:result material))
        delta (- (:ms virtual) (:ms material))]
    {:n n :depth depth
     :asserted-datoms (count asserted)
     :closed-datoms (count closed)
     :derived-subclass-pairs subclass-pairs
     :derived-type-pairs type-pairs
     :rows-virtual vr :rows-material mr :rows-agree? (= vr mr)
     :ms-virtual (:ms virtual) :ms-material (:ms material)
     :ms-build-closure (:ms build)
     :ms-add-one-asserted (:ms add-asserted)
     :ms-add-one-closed (:ms add-closed)
     :speedup (when (pos? (:ms material)) (/ (:ms virtual) (:ms material)))
     :crossover-queries (when (pos? delta) (/ (:ms build) delta))}))

(defn- fmt [x] (if (number? x) (.toFixed x 2) (str x)))

(defn -main []
  (println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg)))
                "  cpus " (count (os/cpus))
                "  reps " reps))
  (println)
  (println (str "     n depth  asserted   closed  sub-pairs type-pairs  rows agree"
                "  virtual-ms  material-ms  build-ms      x  crossover"))
  (let [rows (doall (for [n [100 1000] depth [2 4 8 16]] (row n depth)))]
    (doseq [r rows]
      (println (str (.padStart (str (:n r)) 6)
                    (.padStart (str (:depth r)) 6)
                    (.padStart (str (:asserted-datoms r)) 10)
                    (.padStart (str (:closed-datoms r)) 9)
                    (.padStart (str (:derived-subclass-pairs r)) 11)
                    (.padStart (str (:derived-type-pairs r)) 11)
                    (.padStart (str (:rows-virtual r)) 6)
                    (.padStart (str (:rows-agree? r)) 6)
                    (.padStart (fmt (:ms-virtual r)) 12)
                    (.padStart (fmt (:ms-material r)) 13)
                    (.padStart (fmt (:ms-build-closure r)) 10)
                    (.padStart (fmt (:speedup r)) 7)
                    (.padStart (fmt (:crossover-queries r)) 11))))
    (println)
    (println "maintenance -- adding ONE individual")
    (println "     n depth  asserted-ms  closed-ms")
    (doseq [r rows]
      (println (str (.padStart (str (:n r)) 6)
                    (.padStart (str (:depth r)) 6)
                    (.padStart (fmt (:ms-add-one-asserted r)) 13)
                    (.padStart (fmt (:ms-add-one-closed r)) 11)))))
  (println)
  (println "x = virtual / material. crossover = queries before building the")
  (println "closure pays for itself, at this graph size.")
  (println "rows agree must be true on every line: a door that answers less is")
  (println "not a faster door.")
  (println "sub-pairs is D(D-1)/2 and type-pairs is n*D -- the fixpoint derives")
  (println "both, so the timings above are explained by these, not by the load.")
  (println "NOT measured here: one new subClassOf edge invalidates every one of")
  (println "the n*D type-pairs, and this bench never edits the ontology."))

(-main)
