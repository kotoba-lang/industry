(ns tasuke-app.oracle-test-cljs
  "The SAME truth table as the JVM gate, executed on ClojureScript against the
  SAME artifact.

  This build is the only evidence that crossing the seam works. jp-go-dds's
  oracle names two asymmetries that make a green JVM suite worth nothing here:

    1. an `:i64` field inside a record demands a js/BigInt and refuses a
       js/Number — this core declares no records, and `severity` /
       `documents-for-kind` take their `:i64` at the TOP level, where
       `kir/execute` coerces;
    2. `utf8-substring!` guarded with `(integer? start)` breaks on a BigInt —
       this core formats no integer into a string.

  Both are claims about THIS core, and a claim is not a measurement. That is
  what this file is for."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [tasuke-app.oracle :as o]))

(def report
  "（悲報）Xのアカウントを乗っ取られたかもしれません。メールアドレスが勝手に変更されたとの通知メール。")

(deftest classifies-the-report-on-cljs
  (is (= "account-takeover" (o/classify report)))
  (is (= "unauthorized-transfer" (o/classify "乗っ取られて勝手に振込されていた")))
  (is (= "sns-fraud" (o/classify "よくわからない DM が来た")))
  (is (= "ransomware" (o/classify report "ransomware"))))

(deftest i64-crosses-the-boundary
  (testing "a top-level :i64 argument takes a js/Number"
    (is (= "critical" (o/severity "unauthorized-transfer" 1 false)))
    (is (= "urgent" (o/severity "account-takeover" 0 false)))
    (is (= "info" (o/severity "unauthorized-transfer" 0 false)))
    (is (= "elevated" (o/severity "phishing" 0 false))))
  (testing "an :i64 result normalizes to a host number, not a BigInt"
    (is (= 0 (o/support-cost-jpy)))
    (is (number? (o/support-cost-jpy)))))

(deftest routing-on-cljs
  (is (= ["platform-abuse-desk" "police-cyber-9110" "jpcert"]
         (o/windows "account-takeover")))
  (is (= ["damage-report" "incident-statement" "evidence-index" "damage-calculation"
          "platform-request" "recovery-plan"]
         (o/documents-for-kind "account-takeover" 0)))
  (is (= [] (o/deadlines "ransomware")))
  (is (= 6 (count (o/actions "account-takeover"))))
  (is (= "アカウント乗っ取り" (o/ja-kind "account-takeover"))
      "the guest's Japanese survives the bundle: no mojibake across the embed"))

(deftest refuses-for-the-reason-it-names-on-cljs
  (let [e (try (o/call :hit? ["a" "b"]) nil (catch :default e e))]
    (is (some? e))
    (is (= "function is not exported" (ex-message e)))))

(deftest whole-answer
  (let [t (o/triage {:narrative report :loss-jpy 0 :ongoing? true})]
    (is (= "account-takeover" (:kind t)))
    (is (= "urgent" (:severity t)))
    (is (= 0 (:cost-jpy t)))
    (is (= 1 (count (:deadlines t))))))

(defn -main [& _] (run-tests))
