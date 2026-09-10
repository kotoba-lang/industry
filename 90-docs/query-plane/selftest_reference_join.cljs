;; The runtime half of `scripts/verify-materialized-reference-join.cljs`.
;;
;; The detector reports a SHAPE in source. This asserts that the shape is worth
;; reporting -- that a reference by key and a reference by entity id really do
;; get different answers from the same fixpoint over the same graph. A detector
;; whose failing direction has never been run is a detector nobody has
;; justified running.
;;
;; It also pins the SIZE of the difference, because the failure is not an
;; error and not an empty set: the key-referenced graph returns the asserted
;; class and stops. One row back is what makes it look like an answer.
;;
;;   nbb --classpath "orgs/kotoba-lang/org-w3-owl2/src:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src:orgs/kotoba-lang/ayatori/src:orgs/kotoba-lang/kotobase/src:orgs/kotoba-lang/arrangement/src:orgs/kotoba-lang/prolly-tree/src:orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/selftest_reference_join.cljs
;;
;; Exit 0 when the two shapes are separated, 1 when they are not. `main`
;; returns a COUNT of failing checks rather than a boolean, so one regression
;; and a broken build are not the same output.

(ns selftest-reference-join
  (:require [owl.rules :as rules]
            [kotobase.query.bridge :as bridge]
            [kotobase.local :as local]
            [datalog.core :as dl]))

(def ^:private visible? (constantly true))
(def depth 4)

(defn- klass [d] (str "C" d))

(defn- docs [ref-fn]
  {"individuals" {"i0" {:rdf/type (ref-fn 0)}}
   "classes" (into {} (map (fn [d] [(klass d) {:rdfs/subClassOf (ref-fn (inc d))}])
                           (range (dec depth))))})

(defn- classes-of [ref-fn]
  (let [db (bridge/materialize (local/local-store {:docs (docs ref-fn)})
                               ["individuals" "classes"])]
    (sort (map (comp str first)
               (dl/q db {:find '[?c]
                         :where (list (list 'owl-type :individuals/i0 '?c))
                         :rules (rules/hierarchy-rules)}
                     visible?)))))

(defn -main []
  (let [by-entity (classes-of (fn [d] (keyword "classes" (klass d))))
        by-key (classes-of (fn [d] (klass d)))
        checks [["entity-id references climb the whole chain"
                 (= depth (count by-entity))]
                ["key references answer strictly less"
                 (< (count by-key) (count by-entity))]
                ["key references do not answer ZERO -- the trap is that they answer"
                 (pos? (count by-key))]
                ["the two shapes are separated at all"
                 (not= by-entity by-key)]]
        failed (remove second checks)]
    (println (str "by entity id (" (count by-entity) "): " (pr-str by-entity)))
    (println (str "by key       (" (count by-key) "): " (pr-str by-key)))
    (doseq [[label ok?] checks]
      (println (str (if ok? "  ok   " "  FAIL ") label)))
    (println (str "failing checks: " (count failed)))
    (.exit js/process (if (seq failed) 1 0))))

(-main)
