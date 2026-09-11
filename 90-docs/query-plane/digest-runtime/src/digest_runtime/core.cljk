(ns digest-runtime.core
  "The same three query values ADR-2609109800 measured, behind one entry point
  that can be compiled for workerd and for Node.

  Nothing here is a benchmark. It computes `value-cid` n times and returns the
  last address, so the caller times it from outside and the runtime cannot
  fold the loop away. Returning the address rather than nil is what stops a
  dead-code eliminator from making a fast measurement out of no work."
  (:require [owl.rules :as rules]
            [kotoba.value.codec :as vc]))

(def queries
  {"plain" {:find '[?c] :where '[["Felix" :rdf/type ?c]]}
   "hierarchy" {:find '[?c] :where '[(owl-type "Felix" ?c)]
                :rules (rules/hierarchy-rules)}
   "triple" {:find '[?o] :where '[(owl-triple "Felix" :knows ?o)]
             :rules (rules/triple-rules)}})

(defn digest-n [name n]
  (let [q (get queries name)]
    (loop [i 0 last nil]
      (if (< i n) (recur (inc i) (vc/value-cid q)) (str last)))))

(defn- answer [url]
  (let [p (.-searchParams (js/URL. url "http://localhost"))
        q (or (.get p "q") "plain")
        n (js/parseInt (or (.get p "n") "0") 10)]
    ;; js-obj rather than #js: a literal hyphenated key becomes an inferred
    ;; extern property, and `content-type` is not an identifier.
    (js/JSON.stringify (js-obj "runtime" "workerd" "q" q "n" n "last" (digest-n q n)))))

(def worker
  (js-obj "fetch"
          (fn [req]
            (js/Response. (answer (.-url req))
                          (js-obj "headers" (js-obj "content-type" "application/json"))))))
