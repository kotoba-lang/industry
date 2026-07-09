(ns identity.merge-test
  (:require [clojure.test :refer [deftest is]]
            [identity.merge :as merge]
            [identity.model :as m]))

(deftest merges-same-subject-by-did-and-aliases
  (let [a (m/subject "did:web:example.com:alice" :person
                     {:did "did:web:example.com:alice"
                      :labels #{:oidc}
                      :source :oidc})
        b (m/subject "account:alice" :person
                     {:did "did:web:example.com:alice"
                      :labels #{:ekyc}
                      :source :ekyc})
        out (merge/merge-by-policy [a b])]
    (is (= 1 (count out)))
    (is (= #{:oidc :ekyc} (:identity.subject/labels (first out))))
    (is (= ["account:alice"] (:identity.subject/aliases (first out))))
    (is (false? (:identity.subject/conflict? (first out))))))

(deftest marks-type-conflict-during-subject-merge
  (let [a (m/subject "did:web:example.com:alice" :person
                     {:did "did:web:example.com:alice"})
        b (m/subject "device:alice-phone" :device
                     {:did "did:web:example.com:alice"})
        out (merge/merge-by-policy [a b])]
    (is (= 1 (count out)))
    (is (true? (:identity.subject/conflict? (first out))))))

(deftest keeps-unrelated-subjects-separate
  (let [out (merge/merge-by-policy [(m/subject "did:web:example.com:alice" :person {})
                                    (m/subject "did:web:example.com:bob" :person {})])]
    (is (= 2 (count out)))))
