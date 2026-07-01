(ns aml.adapters.schema-conformance-test
  (:require [aml.adapters.schema-conformance :as sc]
            [aml.model :as m]
            [clojure.test :refer [deftest is]]))

(deftest validates-aml-result-schema-conformance
  (let [request (m/request "aml-1" "did:web:example.com:alice" {:case-ref "case-1"})
        result (m/result request :yabai :clear {})]
    (is (= result (sc/conform-result! result)))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (sc/conform-result! (dissoc result :aml/non-adjudicating))))))
