(ns onetime.core-test
  (:require [clojure.test :refer [deftest is]]
            [onetime.core :as c]
            [onetime.model :as m]
            [onetime.ports :as p]))

(deftest verifies-through-port
  (let [ch (m/challenge "o1" :email-code {})
        port (reify p/IOneTime
               (verify! [_ challenge _] (m/result challenge true {})))]
    (is (true? (:onetime/ok? (c/verify port ch "123456"))))))

(deftest successful-code-is-one-time
  (let [ch (m/challenge "o2" :email-code {})
        store (p/memory-attempt-store)
        port (reify p/IOneTime
               (verify! [_ challenge _] (m/result challenge true {})))]
    (is (true? (:onetime/ok? (c/verify-once port store ch "123456"))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/verify-once port store ch "123456")))))

(deftest rejects-attempts-over-rate-limit
  (let [ch (m/challenge "o3" :email-code {})
        store (p/memory-attempt-store)
        port (reify p/IOneTime
               (verify! [_ challenge _] (m/result challenge false {})))]
    (is (false? (:onetime/ok? (c/verify-once-limited port store ch "000000" {:max-attempts 2}))))
    (is (false? (:onetime/ok? (c/verify-once-limited port store ch "111111" {:max-attempts 2}))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (c/verify-once-limited port store ch "222222" {:max-attempts 2})))
    (is (= [{:onetime.problem/code :rate-limit/exceeded
             :onetime.attempts/count 2
             :onetime.attempts/max 2}]
           (c/rate-limit-problems store ch {:max-attempts 2})))))
