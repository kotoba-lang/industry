(ns authorization.adapters.rules-test
  (:require [authorization.adapters.policy :as policy]
            [authorization.adapters.rules :as rules]
            [authorization.core :as c]
            [authorization.model :as m]
            [clojure.test :refer [deftest is]]))

(deftest evaluates-rules-policy-engine
  (let [engine (rules/rules-engine [{:principal "did:web:example.com:alice"
                                     :action #{"read" "list"}
                                     :resource "doc:1"
                                     :context {:tenant "t1"}
                                     :decision :allow
                                     :by "rules:test"}
                                    {:action "delete"
                                     :resource "doc:1"
                                     :decision :deny
                                     :reason :destructive-action}])
        port (policy/policy-port engine {})
        allowed (m/request "az1" "did:web:example.com:alice" "read" "doc:1"
                           {:context {:tenant "t1"}})
        denied (m/request "az2" "did:web:example.com:alice" "delete" "doc:1" {})]
    (is (= :allow (:authz.decision/decision (c/authorize port allowed))))
    (is (= :deny (:authz.decision/decision (c/authorize port denied))))
    (is (= :destructive-action (:authz.decision/reason (c/authorize port denied))))))

(deftest default-deny-has-reason
  (let [port (policy/policy-port (rules/rules-engine []) {})
        req (m/request "az3" "did:web:example.com:alice" "read" "doc:404" {})]
    (is (= :no-matching-rule (:authz.decision/reason (c/authorize port req))))))

(deftest carries-policy-bundle-version-and-effect-trace
  (let [bundle (rules/policy-bundle "kagi://policy/doc-access"
                                    "2026-07-01"
                                    [{:id "allow-reader"
                                      :principal "did:web:example.com:alice"
                                      :action "read"
                                      :resource "doc:1"
                                      :decision :allow
                                      :obligations [{:type :audit :sink "authz"}]}
                                     {:id "deny-delete"
                                      :action "delete"
                                      :resource "doc:1"
                                      :decision :deny
                                      :reason :destructive-action}]
                                    {})
        port (policy/policy-port (rules/rules-engine bundle {}) {})
        req (m/request "az4" "did:web:example.com:alice" "read" "doc:1" {})
        decision (c/authorize port req)]
    (is (= "kagi://policy/doc-access" (:authz.decision/policy-ref decision)))
    (is (= "2026-07-01" (:authz.decision/policy-version decision)))
    (is (= [{:rule-id "allow-reader" :decision :allow :matched? true}
            {:rule-id "deny-delete" :decision :deny :matched? false}]
           (:authz.decision/effect-trace decision)))
    (is (= [{:type :audit :sink "authz"}]
           (:authz.decision/obligations decision)))))
