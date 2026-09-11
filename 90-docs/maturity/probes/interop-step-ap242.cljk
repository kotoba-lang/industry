(ns probe-interop-step-ap242
  (:require [kami.modeling.step :as step] [kami.modeling.brep :as b] [clojure.string :as str]))
;; ⚠ この軸は 2026-08-21 に **:absent から測り直した**。以前の marker は
;; org-iso-10303 に無い名前（"ap242" "write-ap242"）を探しており、AP242 が
;; どこにも無いと報告していた。実際には kami-engine-modeling の
;; `kami.modeling.step` が AP242 の schema 名を書き出し、PMI を読む。
;; CLAUDE.md「『無い』と結論する前に検索する」を、この台帳自身が破っていた。
;;
;; ただし実装の docstring は「**documented Kotoba AP242 subset**, not full AP242
;; conformance」と自分で言っている。したがって probe は「AP242 対応」ではなく
;; **その subset が往復するか**を測る:
;;   (1) 書き出しが AP242 の schema 名を宣言する（AP203 ではない）
;;   (2) 読み戻して profile が :ap242 と判定される
;;   (3) 対応外の entity を **:step/unsupported として名指しで報告する**
;;       —— 交換で最も危ないのは、読めなかったものを黙って捨てること
(try
  (let [body (b/box-body "probe/ap242" 10 10 10 1.0e-6)
        text (step/export-body body {})   ;; [body opts]
        info (step/inspect-file text)
        with-alien (str/replace text "ENDSEC;\nEND-ISO-10303-21;"
                                "#9001=SOME_UNSUPPORTED_ENTITY(1.0);\nENDSEC;\nEND-ISO-10303-21;")
        info2 (step/inspect-file with-alien)]
    (cond
      (not (map? (step/import-body text)))
      (println "PROBE interop-step-ap242 FAIL" "自分が書いた STEP を読み戻せない")
      (not (str/includes? text "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"))
      (println "PROBE interop-step-ap242 FAIL"
               "書き出しが AP242 の schema 名を宣言しない")
      (not= :ap242 (:step/profile info))
      (println "PROBE interop-step-ap242 FAIL"
               (str "読み戻した profile が " (pr-str (:step/profile info)) "（:ap242 を期待）"))
      (zero? (:step/entity-count info))
      (println "PROBE interop-step-ap242 FAIL" "entity が 1 件も読めない")
      (not (some #(str/includes? % "SOME_UNSUPPORTED_ENTITY") (:step/unsupported info2)))
      (println "PROBE interop-step-ap242 FAIL"
               (str "対応外 entity を報告しない: " (pr-str (:step/unsupported info2))
                    " —— 読めなかったものを黙って捨てている"))
      (seq (:step/unsupported info))
      (println "PROBE interop-step-ap242 FAIL"
               (str "自分が書いた STEP に対応外 entity を報告する: "
                    (pr-str (:step/unsupported info))))
      :else (println "PROBE interop-step-ap242 PASS"
                     (str "AP242 schema を宣言し profile を再認識 / entity "
                          (:step/entity-count info) " 件 / 対応外は名指しで報告"
                          "（**documented subset** であって full conformance ではない）"))))
  (catch :default ex (println "PROBE interop-step-ap242 UNMEASURABLE" (.-message ex))))
