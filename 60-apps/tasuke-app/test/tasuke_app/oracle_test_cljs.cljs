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
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing run-tests]]
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


(deftest records-and-substring-cross-to-cljs
  (testing "a record argument crosses the entry boundary — all-string fields only,
            which is why the guest keeps its :i64 at the top level"
    (let [doc (o/damage-report {:subject "川崎 純" :station "渋谷警察署長 殿"
                                :kind "account-takeover" :occurred "2026-08-28 夜"
                                :narrative "Xのアカウントを乗っ取られた" :loss-jpy 480000})]
      (is (string? doc))
      (is (str/includes? doc "アカウント乗っ取り"))
      (is (str/includes? doc "本人作成・要署名・費用 ¥0・未提出"))))
  (testing "string-substring survives on ClojureScript at this kir pin.
            jp-go-dds records that an older `utf8-substring!` guarded with
            `(integer? start)` breaks on a js/BigInt, and that its own pin pair is
            not exposed. This app formats an integer into a string, so it IS the
            exposed shape — measured here rather than assumed."
    (is (= "1,234,567" (o/yen 1234567)))
    (is (= "0" (o/yen 0)))
    (is (= "999" (o/yen 999)))
    (is (= "1,000" (o/yen 1000)))
    (is (= "-1,234" (o/yen -1234)))))

(deftest every-filing-states-the-charter-on-cljs
  (doseq [[label doc]
          [["被害届" (o/damage-report {:subject "" :station "" :kind "phishing"
                                       :occurred "" :narrative "" :loss-jpy 0})]
           ["被害状況報告書" (o/incident-statement {:subject "" :timeline "" :discovery "" :current ""})]
           ["証拠目録" (o/evidence-index {:subject "" :rows "" :n 0})]
           ["被害額算定書" (o/damage-calculation {:subject "" :lines "" :total 0})]
           ["銀行組戻し" (o/bank-freeze-request {:subject "" :bank "" :occurred ""
                                                 :counterparty "" :loss-jpy 0})]]]
    (is (str/includes? doc "本人作成・要署名・費用 ¥0・未提出")
        (str label " must state member-authored / signed / free / unsubmitted"))))

(defn -main [& _] (run-tests))
