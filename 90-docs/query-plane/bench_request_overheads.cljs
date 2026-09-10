;; What every governed request pays before the query runs, and whether the
;; ontology moves it.
;;
;; ADR-2609109700 D3 puts the ontology inside the query VALUE, so two fixed
;; per-request costs grow with it: `:query/digest` is `value-cid` over that
;; value, and admission walks every rule body. Both are paid on every request
;; whether or not the answer is cached, so if either were material the door
;; choice would not be free.
;;
;; The point of the bench is the RATIO to the query itself. An overhead is
;; only worth naming next to the thing it is overhead on -- see
;; bench_inference_cost.cljs for the other side of that ratio.
;;
;;   nbb --classpath "orgs/kotoba-lang/org-w3-owl2/src:orgs/kotoba-lang/kotobase-server/src:orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/bench_request_overheads.cljs

(ns bench-request-overheads
  (:require [owl.rules :as rules]
            [kotobase.server.admission :as adm]
            [kotoba.value.codec :as vc]
            ["os" :as os]))

(def reps 21)

(def plain {:find '[?c] :where '[["Felix" :rdf/type ?c]]})
(def hierarchy {:find '[?c] :where '[(owl-type "Felix" ?c)]
                :rules (rules/hierarchy-rules)})
(def triple {:find '[?o] :where '[(owl-triple "Felix" :knows ?o)]
             :rules (rules/triple-rules)})

(defn- median [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(defn- timed [f]
  (f)
  (median (mapv (fn [_]
                  (let [t0 (js/performance.now)] (f) (- (js/performance.now) t0)))
                (range reps))))

(defn- clauses [q] (+ (count (:where q))
                      (reduce + 0 (map #(dec (count %)) (or (:rules q) [])))))

(defn- fmt [x] (.toFixed x 4))

(println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg)))
              "  cpus " (count (os/cpus)) "  reps " reps))
(println)
(println "query                      clauses  rules  digest-ms  admit-ms  verdict")
(doseq [[label q] [["no ontology" plain]
                   ["hierarchy-rules" hierarchy]
                   ["triple-rules" triple]]]
  (let [d (timed #(vc/value-cid q))
        a (timed #(adm/admit q))
        v (:admitted? (adm/admit q))]
    (println (str (.padEnd label 26)
                  (.padStart (str (clauses q)) 8)
                  (.padStart (str (count (or (:rules q) []))) 7)
                  (.padStart (fmt d) 11)
                  (.padStart (fmt a) 10)
                  (.padStart (if v "admitted" "REFUSED") 10)))))
(println)
(println "The digest is over the whole query value, so the ontology is inside it")
(println "-- that is the point of D3, and this is what it costs to be there.")
(println)
(println "Read the digest column against bench_inference_cost.cljs rather than")
(println "against zero. Carrying hierarchy-rules in the query value is a FIXED")
(println "per-request tax of roughly the difference between rows one and two,")
(println "and at n=1000 that difference is the same order as the entire")
(println "materialized query. Admission is two orders below both and is not")
(println "worth optimising.")
(println)
(println "Runtime caveat, stated rather than glossed: this is nbb on a loaded")
(println "workstation, not workerd. The RATIOS are the result; the absolute")
(println "milliseconds are this machine's.")
