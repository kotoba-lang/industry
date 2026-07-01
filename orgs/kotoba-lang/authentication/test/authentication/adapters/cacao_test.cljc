(ns authentication.adapters.cacao-test
  (:require [clojure.test :refer [deftest is]]
            [authentication.adapters.cacao :as cacao]
            [authentication.core :as c]
            [authentication.model :as m]))

(deftest verifies-cacao-as-authentication-factor
  (let [verifier (cacao/cacao-factor-verifier
                  (cacao/static-cacao-verifier
                   {:ok? true :subject "did:web:example.com:alice"
                    :evidence-ref "cacao:1" :assurance :phishing-resistant}))
        fr (m/factor-request "fr-1" :cacao {:subject "did:web:example.com:alice"})]
    (is (= {:authn.factor/type :cacao
            :authn.factor/ok? true
            :authn.factor/evidence-ref "cacao:1"}
           (select-keys (c/verify-factor {:cacao verifier} fr {:signature "sig"})
                        [:authn.factor/type :authn.factor/ok? :authn.factor/evidence-ref])))))
