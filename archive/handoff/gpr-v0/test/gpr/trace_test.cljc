(ns gpr.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [gpr.result :as r]
            [gpr.trace :as t]))

(deftest empty-b-scan-is-not-valid
  (testing "入力が無いときに pass を返す検査を作らない（ADR-2608136000）"
    (let [res (t/validate (t/b-scan []))]
      (is (r/err? res))
      (is (= :trace/empty (:code (r/error res)))))))

(deftest ragged-and-mismatched-are-rejected
  (let [a (t/a-scan [0.0 1.0 0.0 0.0] 0.1 0.0)
        b (t/a-scan [0.0 1.0 0.0] 0.1 0.1)
        c (t/a-scan [0.0 1.0 0.0 0.0] 0.2 0.2)]
    (is (= :trace/ragged (:code (r/error (t/validate (t/b-scan [a b]))))))
    (is (= :trace/dt-mismatch (:code (r/error (t/validate (t/b-scan [a c]))))))))

(deftest duplicate-positions-are-rejected
  (let [a (t/a-scan [0.0 1.0] 0.1 0.0)
        b (t/a-scan [0.0 1.0] 0.1 0.0)]
    (is (= :trace/x-not-monotonic (:code (r/error (t/validate (t/b-scan [a b]))))))))

(deftest well-formed-b-scan-passes
  (let [bs (t/b-scan [(t/a-scan [0.0 1.0] 0.1 0.1)
                      (t/a-scan [0.0 1.0] 0.1 0.0)])]
    (is (r/ok? (t/validate bs)))
    (testing "x の昇順に並べ替えて保持する"
      (is (= [0.0 0.1] (mapv :gpr/x-m (t/traces bs)))))))
