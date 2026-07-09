(ns mfa.core-test
  (:require [clojure.test :refer [deftest is]]
            [mfa.core :as c]
            [mfa.model :as m]))

(deftest requires-two-factors
  (let [p (m/policy "m1" #{:totp :touchid} {})
        out (c/evaluate p [{:authn.factor/type :totp :authn.factor/ok? true}
                           {:authn.factor/type :touchid :authn.factor/ok? true}])]
    (is (:mfa.result/ok? out))))

(deftest rejects-invalid-policy-and-factor-shapes
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (c/evaluate (m/policy "m2" #{} {}) [])))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (c/evaluate (m/policy "m3" #{:totp} {:phishing-resistant? true})
                           [{:authn.factor/type :totp :authn.factor/ok? true}]))))

(deftest phishing-resistant-policy-accepts-webauthn-family
  (let [p (m/policy "m4" #{:totp :webauthn} {:phishing-resistant? true})
        out (c/evaluate p [{:authn.factor/type :totp :authn.factor/ok? true}
                           {:authn.factor/type :webauthn :authn.factor/ok? true}])]
    (is (:mfa.result/ok? out))))
