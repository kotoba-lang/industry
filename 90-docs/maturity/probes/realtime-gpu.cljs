(ns probe-realtime-gpu
  (:require ["fs" :as fs] ["path" :as path] ["child_process" :as cp]
            [clojure.edn :as edn] [clojure.string :as str]))
;; リアルタイム描画は実 GPU が要る。測定そのものは `scripts/threed-gpu-receipt.cljs`
;; が 1 回だけ行い、この probe は**受領書を読む**。
;;
;; 直接回していた版（2026-08-23 の最初の形）は、単体では通るのに**全軸 audit の
;; 中では 600 秒の上限に当たって UNMEASURABLE になった** —— 実測で load 100 と 308 の
;; 両方で再現。軸の状態がマシンの混み具合で揺れるのは、「測れなかった」と正直に
;; 言えていても判断には使えない。
;;
;; 受領書を使うことで緑が安くなってはいけないので、3 つを要求する:
;;   (1) **revision の一致** —— 受領書の sha が `kotoba-lang/webgpu` の現 HEAD と
;;       違えば PASS を出さない。古い測定で緑にするのは、測っていないものを
;;       緑にすることと同じ
;;   (2) **evidence floor** —— テスト数と assertion 数に床（8 / 30）
;;   (3) **実 GPU の申告** —— SwiftShader へ落ちた実行を「動く」と読まない
(try
  (let [orgs (or (aget js/process.env "THREED_ORGS") (path/join (js/process.cwd) "orgs"))
        repo (path/join orgs "kotoba-lang" "webgpu")
        receipt-file (path/join (js/process.cwd) "90-docs" "maturity" "receipts" "realtime-gpu.edn")
        head (when (fs/existsSync repo)
               (str/trim (str (.-stdout (cp/spawnSync "git" #js ["-C" repo "rev-parse" "HEAD"]
                                                      #js {:encoding "utf8"})))))
        receipt (when (fs/existsSync receipt-file)
                  (try (edn/read-string (str (fs/readFileSync receipt-file "utf8")))
                       (catch :default _ nil)))]
    (cond
      (nil? head)
      (println "PROBE realtime-gpu UNMEASURABLE"
               (str "kotoba-lang/webgpu の checkout が無い（" repo "）"))
      (nil? receipt)
      (println "PROBE realtime-gpu UNMEASURABLE"
               (str "受領書が無いか読めない（" receipt-file "）—— "
                    "nbb --classpath \".:scripts/nbb_compat\" scripts/threed-gpu-receipt.cljs で作る"))
      (not= head (:receipt/revision receipt))
      (println "PROBE realtime-gpu UNMEASURABLE"
               (str "受領書は別の revision のもの（受領書 "
                    (subs (str (:receipt/revision receipt)) 0 (min 8 (count (str (:receipt/revision receipt)))))
                    " / 現在 " (subs head 0 8) "）—— 測り直すこと。"
                    "古い測定で緑を出さない"))
      (or (< (:receipt/tests receipt 0) 8) (< (:receipt/assertions receipt 0) 30))
      (println "PROBE realtime-gpu FAIL"
               (str "走った本数が床を下回る: " (:receipt/tests receipt) " tests / "
                    (:receipt/assertions receipt) " assertions（床 8 / 30）"
                    " —— 静かに減った suite は「全部通った」と同じ顔をする"))
      (or (pos? (:receipt/failures receipt 1)) (pos? (:receipt/errors receipt 1)))
      (println "PROBE realtime-gpu FAIL"
               (str "実ブラウザの描画テストが落ちている: " (:receipt/failures receipt)
                    " failures / " (:receipt/errors receipt) " errors"))
      (str/blank? (str (:receipt/gpu receipt)))
      (println "PROBE realtime-gpu FAIL"
               "受領書に実 GPU の申告が無い —— SwiftShader へ落ちた実行を「リアルタイム描画が動く」と読まない")
      :else
      (println "PROBE realtime-gpu PASS"
               (str "実ブラウザ E2E が " (:receipt/tests receipt) " tests / "
                    (:receipt/assertions receipt) " assertions で通る（" (:receipt/gpu receipt)
                    "、" (:receipt/seconds receipt) "s、load1 "
                    (js/Math.round (or (:receipt/load1 receipt) 0)) "）"
                    " / 受領書は現 HEAD " (subs head 0 8) " のもの"))))
  (catch :default ex (println "PROBE realtime-gpu UNMEASURABLE" (.-message ex))))
