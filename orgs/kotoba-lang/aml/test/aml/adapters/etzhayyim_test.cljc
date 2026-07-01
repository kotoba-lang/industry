(ns aml.adapters.etzhayyim-test
  (:require [aml.adapters.etzhayyim :as a]
            [aml.core :as c]
            [aml.model :as m]
            [clojure.test :refer [deftest is]]))

(deftest routes-aml-screening-to-etzhayyim-xrpc
  (let [calls (atom [])
        client (reify a/IXrpcClient
                 (invoke! [_ nsid payload]
                   (swap! calls conj [nsid payload])
                   (case nsid
                     "ai.gftd.apps.yabai.getRisk"
                     {:score 450
                      :flags [:pep]
                      :request-id "yr1"}
                     "ai.gftd.apps.malak.queryRiskChain"
                     {:severity "high"
                      :riskSignals [:linked-wallet]
                      :evidence-ref "kagi://malak/risk-chain/1"})))
        port (a/screening-port client)
        req (m/request "aml1" {:subject/id "did:web:example.com:alice"}
                       {:case-ref "case-1"
                        :purpose "onboarding"
                        :routes [:yabai :malak]})
        out (c/screen port req)]
    (is (= [["ai.gftd.apps.yabai.getRisk"
             {:entityId "did:web:example.com:alice"
              :caseRef "case-1"
              :purpose "onboarding"}]
            ["ai.gftd.apps.malak.queryRiskChain"
             {:address {:subject/id "did:web:example.com:alice"}
              :chain nil
              :caseRef "case-1"
              :purpose "onboarding"}]]
           @calls))
    (is (= :review (:aml/status out)))
    (is (= [:review :challenge] (mapv :aml/level (:aml/results out))))
    (is (every? :aml/non-adjudicating (:aml/results out)))
    (is (= ["etzhayyim/yabai" "etzhayyim/malak"]
           (mapv :aml/asserter (:aml/results out))))))
